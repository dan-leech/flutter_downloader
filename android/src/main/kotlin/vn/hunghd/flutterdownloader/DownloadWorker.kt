package vn.hunghd.flutterdownloader

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.*
import java.util.regex.Pattern
import kotlin.coroutines.CoroutineContext

class DownloadWorker(context: Context,
                     params: WorkerParameters,
                     private val workerContext: CoroutineContext,
                     private val fileManager: FileManager
) : CoroutineWorker(context, params) {
    companion object {
        const val ARG_TITLE = "title"
        const val ARG_URL = "url"
        const val ARG_FILE_NAME = "file_name"
        const val ARG_SAVED_DIR = "saved_file"
        const val ARG_HEADERS = "headers"
        const val ARG_IS_RESUME = "is_resume"
        const val SHOW_FILE_PROGRESS_NOTIFICATION = "show_file_progress_notification"
        const val SHOW_ALL_COMPLETED_NOTIFICATION = "show_all_completed_notification"
        const val ARG_OPEN_FILE_FROM_NOTIFICATION = "open_file_from_notification"
        const val ARG_DEBUG = "debug"
        const val PROGRESS_PROGRESS = "PROGRESS"
        const val SUCCESS_FILE_NAME = "FILE_NAME"
        private val TAG: String = DownloadWorker::class.java.simpleName
        private const val CHANNEL_ID = "FLUTTER_DOWNLOADER_NOTIFICATION"
    }

    private val charsetPattern = Pattern.compile("(?i)\\bcharset=\\s*\"?([^\\s;\"]*)")

    private var dbHelper: TaskDbHelper? = null
    private var taskDao: TaskDao? = null
    private val builder: NotificationCompat.Builder? = null
    private var showEachFileProgressNotification = false
    private var showAllCompletedNotification = false
    private var clickToOpenDownloadedFile = false
    private var debug = false
    private var msgStarted: String? = null
    private var msgInProgress: String? = null
    private var msgCanceled: String? = null
    private var msgFailed: String? = null
    private var msgPaused: String? = null
    private var msgComplete: String? = null
    private var lastCallUpdateNotification: Long = 0

    init {
        // this is more precise for enqueueing
        setProgressAsync(Data.Builder().putInt(PROGRESS_PROGRESS, -1).build())
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        var title = inputData.getString(ARG_TITLE)
        val url = inputData.getString(ARG_URL).toString()
        val filename = inputData.getString(ARG_FILE_NAME)
        val savedDir = inputData.getString(ARG_SAVED_DIR)
        val headers = inputData.getString(ARG_HEADERS)
        val isResume = inputData.getBoolean(ARG_IS_RESUME, false)
        debug = inputData.getBoolean(ARG_DEBUG, false)
        val res = applicationContext.resources
        msgStarted = res.getString(R.string.flutter_downloader_notification_started)
        msgInProgress = res.getString(R.string.flutter_downloader_notification_in_progress)
        msgCanceled = res.getString(R.string.flutter_downloader_notification_canceled)
        msgFailed = res.getString(R.string.flutter_downloader_notification_failed)
        msgPaused = res.getString(R.string.flutter_downloader_notification_paused)
        msgComplete = res.getString(R.string.flutter_downloader_notification_complete)
        log("DownloadWorker {url=$url,filename=$filename,savedDir=$savedDir,header=$headers,isResume=$isResume")
        showEachFileProgressNotification = inputData.getBoolean(SHOW_FILE_PROGRESS_NOTIFICATION, false)
        showAllCompletedNotification = inputData.getBoolean(SHOW_ALL_COMPLETED_NOTIFICATION, false)
        clickToOpenDownloadedFile = inputData.getBoolean(ARG_OPEN_FILE_FROM_NOTIFICATION, false)

        setupEachFileProgressNotification(context)
        setupAllCompletedNotification(context)

        // Default to the filename or URL if a title has not been specified.
        if (title == null || title.isEmpty()) {
            title = filename ?: url
        }
//        updateEachFileProgressNotification(context, title, DownloadStatus.RUNNING, task.progress, null, false)

        val progress = "Starting Download"
        // todo: need this to prevent 10 min limit of background execution
//        setForeground(createForegroundInfo(progress))

        return withContext(workerContext) {
            try {
                // wait before start
                delay(1000)

                var fileName: String? = null
                fileName = downloadFile(context, title, url, savedDir, filename, headers, isResume)

                dbHelper = null
                taskDao = null

                Result.success(Data.Builder().putString(SUCCESS_FILE_NAME, fileName).build())
            } catch (e: Exception) {
                log("doWork() exception:" + e.message + "\n\t" + e.printStackTrace())
//            updateEachFileProgressNotification(context, title, DownloadStatus.FAILED, -1, null, true)
//            taskDao!!.updateTask(id.toString(), DownloadStatus.FAILED, lastProgress)

//            dbHelper = null
//            taskDao = null
                Result.failure()
            }
        }
    }


    private fun setupHeaders(conn: HttpURLConnection?, headers: String?) {
        if (!headers.isNullOrEmpty()) {
            log("Headers = $headers")
            try {
                val json = JSONObject(headers)
                val it = json.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    conn!!.setRequestProperty(key, json.getString(key))
                }
                conn!!.doInput = true
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }
    }

    private fun setupPartialDownloadedDataHeader(conn: HttpURLConnection?, filename: String?, savedDir: String?): Long {
        val saveFilePath = savedDir + File.separator + filename
        val partialFile = File(saveFilePath)
        val downloadedBytes = partialFile.length()
        log("Resume download: Range: bytes=$downloadedBytes-")
        conn!!.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Range", "bytes=$downloadedBytes-")
        conn.doInput = true
        return downloadedBytes
    }

    @kotlin.Throws(IOException::class)
    fun downloadFile(context: Context, title: String?, fileURL: String, savedDir: String?, filename: String?, headers: String?, isResume: Boolean): String? {
        var fileName = filename
        var url = fileURL
        var resourceUrl: URL
        var base: URL
        var next: URL
        val visited: MutableMap<String?, Int>
        var httpConn: HttpURLConnection? = null
        var inputStream: InputStream? = null
        val saveFilePath: String
        var location: String
        var responseCode: Int = -1
        var times: Int
        visited = HashMap()
        try {
            // handle redirection logic
            while (!isStopped) {
                if (!visited.containsKey(url)) {
                    times = 1
                    visited[url] = times
                } else {
                    times = visited[url]!! + 1
                }
                if (times > 3) throw IOException("Stuck in redirect loop")
                resourceUrl = URL(url)
                log("Open connection url: $url")
                httpConn = resourceUrl.openConnection() as HttpURLConnection
                if (resourceUrl.userInfo != null) {
                    val basicAuth = "Basic " + Base64.encodeToString(resourceUrl.userInfo.toByteArray(), Base64.DEFAULT)
                    httpConn.setRequestProperty("Authorization", basicAuth)
                }
                httpConn.connectTimeout = 45000
                httpConn.readTimeout = 45000
                httpConn.instanceFollowRedirects = false // Make the logic below easier to detect redirects
                httpConn.setRequestProperty("User-Agent", "Mozilla/5.0...")

                // setup request headers if it is set
                setupHeaders(httpConn, headers)
                // try to continue downloading a file from its partial downloaded data.
//                if (isResume) {
//                    downloadedBytes = setupPartialDownloadedDataHeader(httpConn, fileName, savedDir)
//                }
                responseCode = httpConn.responseCode
                when (responseCode) {
                    HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_SEE_OTHER, HttpURLConnection.HTTP_MOVED_TEMP -> {
                        log("Response with redirection code")
                        location = httpConn.getHeaderField("Location")
                        log("Location = $location")
                        base = URL(fileURL)
                        next = URL(base, location) // Deal with relative URLs
                        url = next.toExternalForm()
                        log("New url: $url")
                        continue
                    }
                }
                break
            }
            httpConn!!.connect()
            if ((responseCode == HttpURLConnection.HTTP_OK || isResume && responseCode == HttpURLConnection.HTTP_PARTIAL) && !isStopped) {
                val contentType = httpConn.contentType
                val contentLength: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // files over 2GB
                    httpConn.contentLengthLong
                } else {
                    httpConn.contentLength.toLong()
                }
                log("Content-Type = $contentType")
                log("Content-Length = $contentLength")
                val charset = getCharsetFromContentType(contentType)
                log("Charset = $charset")
                if (!isResume) {
                    // try to extract filename from HTTP headers or url if it is not given by user
                    val disposition = httpConn.getHeaderField("Content-Disposition")
                    log("Content-Disposition = $disposition")
                    fileName = CommonUtils.solveFilename(url, id.toString(), filename = fileName, disposition = disposition, charset = charset)
                }
                saveFilePath = savedDir + File.separator + fileName
                log("fileName = $fileName")

                // opens input stream from the HTTP connection
                inputStream = httpConn.inputStream

                var lastProgress = 0
                var lastProgressEmitTime: Long = System.currentTimeMillis()

                // return true or throws IOException
                fileManager.writeFile(inputStream, saveFilePath, isResume) {
                    if (isStopped) return@writeFile

                    val curTime = System.currentTimeMillis()

                    if (curTime - lastProgressEmitTime > 1000) {
                        lastProgressEmitTime = curTime

                        val done = if (it == -1L) contentLength else it
                        val progress = (done * 100 / contentLength).toInt()
                        if (progress == lastProgress) return@writeFile

                        lastProgress = progress
                        setProgressAsync(Data.Builder().putInt(PROGRESS_PROGRESS, progress).build())
                    }
                }

                setProgressAsync(Data.Builder().putInt(PROGRESS_PROGRESS, 100).build())

                val storage = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                var pendingIntent: PendingIntent? = null
                if (isImageOrVideoFile(contentType) && isExternalStoragePath(saveFilePath)) {
                    addImageOrVideoToGallery(fileName, saveFilePath, getContentTypeWithoutCharset(contentType))
                }
                if (clickToOpenDownloadedFile && storage == PackageManager.PERMISSION_GRANTED) {
                    val intent = IntentUtils.validatedFileIntent(applicationContext, saveFilePath, contentType)
                    if (intent != null) {
                        log("Setting an intent to open the file $saveFilePath")
                        pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT)
                    } else {
                        log("There's no application that can open the file $saveFilePath")
                    }
                }


//            todo: updateEachFileProgressNotification(context, title, status, progress, pendingIntent, true)
            } else {
                log("Task[$id] is stopped")
                throw Exception("Paused tasks are not implemented")
//                val task = taskDao!!.loadTask(id.toString())
//                status = if (isStopped) if (task.resumable) DownloadStatus.PAUSED else DownloadStatus.CANCELED else DownloadStatus.FAILED
//                updateEachFileProgressNotification(context, title, status, -1, null, true)
//                taskDao!!.updateTask(id.toString(), status, lastProgress)
            }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                log("Task[$id]  Server replied HTTP code: $responseCode")
            }
        } catch (e: IOException) {
            Log.d(TAG, "downloadFile() " + e.message)
//            updateEachFileProgressNotification(context, title, DownloadStatus.FAILED, -1, null, true)
//            taskDao!!.updateTask(id.toString(), DownloadStatus.FAILED, lastProgress)
            e.printStackTrace()
        } finally {
            inputStream?.close()
            httpConn?.disconnect()
        }

        return fileName
    }

    // Creates an instance of ForegroundInfo which can be used to update the
    // ongoing notification.
    private fun createForegroundInfo(progress: String): ForegroundInfo {
        val title = applicationContext.getString(R.string.flutter_downloader_notification_channel_name)
        val descr = applicationContext.getString(R.string.flutter_downloader_notification_channel_description)
        // This PendingIntent can be used to cancel the worker
        val intent = WorkManager.getInstance(applicationContext)
                .createCancelPendingIntent(id)

        // Create a Notification channel if necessary
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel()
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(descr)
                .setTicker(title)
                .setContentText(progress)
                .setSmallIcon(notificationIconRes)
                .setOngoing(true)
                // Add the cancel action to the notification which can
                // be used to cancel the worker
//                .addAction(android.R.drawable.ic_delete, cancel, intent)
                .build()

        return ForegroundInfo(id.hashCode(), notification)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel() {
        // Create a Notification channel
    }


    private val notificationIconRes: Int
        private get() {
            try {
                val applicationInfo = applicationContext.packageManager.getApplicationInfo(applicationContext.packageName, PackageManager.GET_META_DATA)
                val appIconResId = applicationInfo.icon
                return applicationInfo.metaData.getInt("vn.hunghd.flutterdownloader.NOTIFICATION_ICON", appIconResId)
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }
            return 0
        }

    private fun setupEachFileProgressNotification(context: Context) {
        if (!showEachFileProgressNotification) return
        // Make a channel if necessary
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create the NotificationChannel, but only on API 26+ because
            // the NotificationChannel class is new and not in the support library
            val res = applicationContext.resources
            val channelName = res.getString(R.string.flutter_downloader_notification_channel_name)
            val channelDescription = res.getString(R.string.flutter_downloader_notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, channelName, importance)
            channel.description = channelDescription
            channel.setSound(null, null)

            // Add the channel
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateEachFileProgressNotification(context: Context, title: String?, status: Int, progress: Int, intent: PendingIntent?, finalize: Boolean) {
//        sendUpdateProcessEvent(status, progress)

        // Show the notification
        if (showEachFileProgressNotification) {
            // Create the notification
            val builder = NotificationCompat.Builder(context, CHANNEL_ID).setContentTitle(title)
                    .setContentIntent(intent)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
            if (status == DownloadStatus.RUNNING) {
                if (progress <= 0) {
                    builder.setContentText(msgStarted)
                            .setProgress(0, 0, false)
                    builder.setOngoing(false)
                            .setSmallIcon(notificationIconRes)
                } else if (progress < 100) {
                    builder.setContentText(msgInProgress)
                            .setProgress(100, progress, false)
                    builder.setOngoing(true)
                            .setSmallIcon(android.R.drawable.stat_sys_download)
                } else {
                    builder.setContentText(msgComplete).setProgress(0, 0, false)
                    builder.setOngoing(false)
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                }
            } else if (status == DownloadStatus.CANCELED) {
                builder.setContentText(msgCanceled).setProgress(0, 0, false)
                builder.setOngoing(false)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
            } else if (status == DownloadStatus.FAILED) {
                builder.setContentText(msgFailed).setProgress(0, 0, false)
                builder.setOngoing(false)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
            } else if (status == DownloadStatus.PAUSED) {
                builder.setContentText(msgPaused).setProgress(0, 0, false)
                builder.setOngoing(false)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
            } else if (status == DownloadStatus.COMPLETE) {
                builder.setContentText(msgComplete).setProgress(0, 0, false)
                builder.setOngoing(false)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
            } else {
                builder.setProgress(0, 0, false)
                builder.setOngoing(false).setSmallIcon(notificationIconRes)
            }

            // Note: Android applies a rate limit when updating a notification.
            // If you post updates to a notification too frequently (many in less than one second),
            // the system might drop some updates. (https://developer.android.com/training/notify-user/build-notification#Updating)
            //
            // If this is progress update, it's not much important if it is dropped because there're still incoming updates later
            // If this is the final update, it must be success otherwise the notification will be stuck at the processing state
            // In order to ensure the final one is success, we check and sleep a second if need.
            if (System.currentTimeMillis() - lastCallUpdateNotification < 1000) {
                if (finalize) {
                    log("Update too frequently!!!!, but it is the final update, we should sleep a second to ensure the update call can be processed")
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                } else {
                    log("Update too frequently!!!!, this should be dropped")
                    return
                }
            }
            val id = id.hashCode()
            log("Update notification: {notificationId: $id, title: $title, status: $status, progress: $progress}")
            NotificationManagerCompat.from(context).notify(id, builder.build())
            lastCallUpdateNotification = System.currentTimeMillis()
        }
    }

    private fun setupAllCompletedNotification(context: Context) {
        if (!showAllCompletedNotification) return
        // Make a channel if necessary
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create the NotificationChannel, but only on API 26+ because
            // the NotificationChannel class is new and not in the support library
            val res = applicationContext.resources
            val channelName = res.getString(R.string.flutter_downloader_all_completed_notification_channel_name)
            val channelDescription = res.getString(R.string.flutter_downloader_all_completed_notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, channelName, importance)
            channel.description = channelDescription
            channel.setSound(null, null)

            // Add the channel
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getCharsetFromContentType(contentType: String?): String? {
        if (contentType == null) return null
        val m = charsetPattern.matcher(contentType)
        return if (m.find()) {
            m.group(1)?.trim { it <= ' ' }?.toUpperCase(Locale.getDefault())
        } else null
    }

    private fun getContentTypeWithoutCharset(contentType: String?): String? {
        return contentType?.split(";")?.toTypedArray()?.get(0)?.trim { it <= ' ' }
    }

    private fun isImageOrVideoFile(contentType: String): Boolean {
        var lContentType: String? = contentType
        lContentType = getContentTypeWithoutCharset(lContentType)
        return lContentType != null && (lContentType.startsWith("image/") || lContentType.startsWith("video"))
    }

    private fun isExternalStoragePath(filePath: String?): Boolean {
        val externalStorageDir = Environment.getExternalStorageDirectory()
        return filePath != null && externalStorageDir != null && filePath.startsWith(externalStorageDir.path)
    }

    private fun addImageOrVideoToGallery(fileName: String?, filePath: String?, contentType: String?) {
        if (contentType != null && filePath != null && fileName != null) {
            if (contentType.startsWith("image/")) {
                val values = ContentValues()
                values.put(MediaStore.Images.Media.TITLE, fileName)
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                values.put(MediaStore.Images.Media.DESCRIPTION, "")
                values.put(MediaStore.Images.Media.MIME_TYPE, contentType)
                values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                }
                values.put(MediaStore.Images.Media.DATA, filePath)
                log("insert $values to MediaStore")
                val contentResolver = applicationContext.contentResolver
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } else if (contentType.startsWith("video")) {
                val values = ContentValues()
                values.put(MediaStore.Video.Media.TITLE, fileName)
                values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                values.put(MediaStore.Video.Media.DESCRIPTION, "")
                values.put(MediaStore.Video.Media.MIME_TYPE, contentType)
                values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                }
                values.put(MediaStore.Video.Media.DATA, filePath)
                log("insert $values to MediaStore")
                val contentResolver = applicationContext.contentResolver
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }
        }
    }

    private fun log(message: String) {
        if (debug) {
            Log.d(TAG, message)
        }
    }
}
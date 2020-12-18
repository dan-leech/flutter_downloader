package vn.hunghd.flutterdownloader

import android.content.ContentUris
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Observer
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloadWorkerManager(private val context: Context, private val taskDao: TaskDao, private val isDebug: Boolean, private val showAllCompletedNotification: Boolean) {
    companion object {
        private const val TAG = "flutter_download_task"
    }

    private val workManager: WorkManager = WorkManager.getInstance(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val callbackMessenger = CallbackMessenger(context)

    init {
//        workManager.pruneWork() // for debugging
        var completed = 0
        val liveData = workManager.getWorkInfosByTagLiveData(TAG)
        val observer = Observer<List<WorkInfo>> { workInfos ->
//            log("workInfos size: " + workInfos?.size + " running: " + workInfos?.filter { workInfo -> workInfo.state == WorkInfo.State.RUNNING }?.size)
            val newCompleted = workInfos?.filter { workInfo -> workInfo.state == WorkInfo.State.SUCCEEDED }?.size
                    ?: 0
            var newRunning = workInfos?.filter { workInfo -> workInfo.state == WorkInfo.State.RUNNING }?.size
                    ?: 0

            // on GC can be less
            if (newCompleted != completed && newRunning == 0) {
                //            todo: all completed notification
                GlobalScope.launch(Dispatchers.Default) {
                    // wait to send it the last one
                    delay(1000)
                    callbackMessenger.sendUpdateProcessEvent("", DownloadStatus.ALL_COMPLETED, -1)
                }
            }

            completed = newCompleted

//            todo: store prev state to filter first and clean after cancel

            /* Here We remove the Observer if Not needed anymore
                     'this' here = the Observer */
//                liveData.removeObserver(this)
        }

        liveData.observeForever(observer)

        // fixme: the code below freezes flutter app on start if Kiwify cannot connect to API at first
        // listener on ListenableFuture fires once on receive data
        val workInfos = workManager.getWorkInfosByTag(TAG)
        workInfos.addListener({
            // add listeners to re-enqueued tasks
            val list = workInfos.get().filter { workInfo -> workInfo.state == WorkInfo.State.RUNNING || workInfo.state == WorkInfo.State.ENQUEUED }
            log("workInfos resumed size: " + list.size)

            for (work in list) {
                val liveWorkData = workManager.getWorkInfoByIdLiveData(work.id)
                log("observing taskId: " + work.id)
                GlobalScope.launch(Dispatchers.Main) {
                    liveWorkData.observeForever(DownloadWorkInfoObserver(callbackMessenger, taskDao, liveWorkData))
                }
            }

        }, executor)

        // current solution is to run tasks from flutter as on iOS
//        workManager.cancelAllWorkByTag(TAG)
    }

    fun enqueue(url: String, savedDir: String, title: String?, filename: String?, headers: String?, showNotification: Boolean, openFileFromNotification: Boolean, isResume: Boolean, requiresStorageNotLow: Boolean): String {
        val request = buildRequest(url, savedDir, title, filename, headers, showNotification, openFileFromNotification, isResume, requiresStorageNotLow);

        workManager.enqueue(request)
        val liveData = workManager.getWorkInfoByIdLiveData(request.id)
        liveData.observeForever(DownloadWorkInfoObserver(callbackMessenger, taskDao, liveData))

        val taskId = request.id.toString()
        taskDao.insertOrUpdateNewTask(taskId, title, url, DownloadStatus.ENQUEUED, 0, filename, savedDir, headers, showNotification, openFileFromNotification)

        return taskId
    }

    fun cancel(taskId: String) {
        workManager.cancelWorkById(UUID.fromString(taskId))
    }

    fun cancelAll() = workManager.cancelAllWorkByTag(TAG)

    fun remove(taskId: String, shouldDeleteContent: Boolean): Boolean {
        val task: DownloadTask = taskDao.loadTask(taskId) ?: return false

        val uuid = UUID.fromString(taskId)
        if (task.status == DownloadStatus.ENQUEUED || task.status == DownloadStatus.RUNNING) {
            workManager.cancelWorkById(uuid)
        }
        if (shouldDeleteContent) {
            val filename = CommonUtils.solveFilename(task.url, taskId, filename = task.filename)

            val saveFilePath = task.savedDir + File.separator + filename
            val tempFile = File(saveFilePath)
            if (tempFile.exists()) {
                deleteFileInMediaStore(tempFile)
                tempFile.delete()
            }
        }
        taskDao.deleteTask(taskId)
        NotificationManagerCompat.from(context).cancel(uuid.hashCode())
        return true
    }

    private fun buildRequest(url: String, savedDir: String, title: String?, filename: String?, headers: String?, showNotification: Boolean, openFileFromNotification: Boolean, isResume: Boolean, requiresStorageNotLow: Boolean): WorkRequest {
        return OneTimeWorkRequest.Builder(DownloadWorker::class.java)
                .setConstraints(Constraints.Builder()
                        .setRequiresStorageNotLow(requiresStorageNotLow)
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .addTag(TAG)
                // 10 sec is MINIMUM allowed backoff delay
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setInputData(Data.Builder()
                        .putString(DownloadWorker.ARG_TITLE, title)
                        .putString(DownloadWorker.ARG_URL, url)
                        .putString(DownloadWorker.ARG_SAVED_DIR, savedDir)
                        .putString(DownloadWorker.ARG_FILE_NAME, filename)
                        .putString(DownloadWorker.ARG_HEADERS, headers)
                        .putBoolean(DownloadWorker.SHOW_FILE_PROGRESS_NOTIFICATION, showNotification)
                        .putBoolean(DownloadWorker.ARG_OPEN_FILE_FROM_NOTIFICATION, openFileFromNotification)
                        .putBoolean(DownloadWorker.ARG_IS_RESUME, isResume)
                        .putBoolean(DownloadWorker.ARG_DEBUG, isDebug)
                        .build()
                )
                .build()
    }

    private fun deleteFileInMediaStore(file: File) {
        // Set up the projection (we only need the ID)
        val projection = arrayOf(MediaStore.Images.Media._ID)

        // Match on the file path
        val imageSelection = MediaStore.Images.Media.DATA + " = ?"
        val videoSelection = MediaStore.Video.Media.DATA + " = ?"
        val selectionArgs = arrayOf(file.absolutePath)

        // Query for the ID of the media matching the file path
        val imageQueryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val videoQueryUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val contentResolver = context.contentResolver

        // search the file in image store first
        val imageCursor = contentResolver.query(imageQueryUri, projection, imageSelection, selectionArgs, null)
        if (imageCursor != null && imageCursor.moveToFirst()) {
            // We found the ID. Deleting the item via the content provider will also remove the file
            val id = imageCursor.getLong(imageCursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            val deleteUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            contentResolver.delete(deleteUri, null, null)
        } else {
            // File not found in image store DB, try to search in video store
            val videoCursor = contentResolver.query(imageQueryUri, projection, imageSelection, selectionArgs, null)
            if (videoCursor != null && videoCursor.moveToFirst()) {
                // We found the ID. Deleting the item via the content provider will also remove the file
                val id = videoCursor.getLong(videoCursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val deleteUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                contentResolver.delete(deleteUri, null, null)
            } else {
                // can not find the file in media store DB at all
            }
            videoCursor?.close()
        }
        imageCursor?.close()
    }

    private fun log(message: String) {
        if (isDebug) {
            Log.d(TAG, message)
        }
    }
}
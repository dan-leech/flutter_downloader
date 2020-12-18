package vn.hunghd.flutterdownloader

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.*

private fun MethodChannel.Result.success() = success(null)

class FlutterDownloaderCallHandler(private val context: Context) {
    companion object {
        private var callbackHandle: Long = 0

        fun getCallBackHandler(): Long {
            return callbackHandle
        }
    }

    private val dbHelper: TaskDbHelper = TaskDbHelper.getInstance(context)
    private val taskDao: TaskDao
    private lateinit var downloadWorkerManager: DownloadWorkerManager

    init {
        this.taskDao = TaskDao(dbHelper)
    }

    fun handle(call: MethodCall, result: MethodChannel.Result) =
            when (call.method) {
                "initialize" -> initialize(call, result)
                "registerCallback" -> registerCallback(call, result)
                "enqueue" -> enqueue(call, result)
                "loadTasks" -> loadTasks(call, result)
                "loadTasksWithRawQuery" -> loadTasksWithRawQuery(call, result)
                "cancel" -> cancel(call, result)
                "cancelAll" -> cancelAll(call, result)
                "pause" -> pause(call, result)
                "resume" -> resume(call, result)
                "retry" -> retry(call, result)
                "open" -> open(call, result)
                "remove" -> remove(call, result)
                else -> result.notImplemented()
            }

    private fun initialize(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as List<*>
        val callbackHandle = args[0].toString().toLong()
        val debugMode = args[1].toString().toInt()
        val showAllCompletedNotification = java.lang.Boolean.parseBoolean(args[2].toString())
        val pref: SharedPreferences = context.getSharedPreferences(FlutterDownloaderPlugin.SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE)
        pref.edit().putLong(FlutterDownloaderPlugin.CALLBACK_DISPATCHER_HANDLE_KEY, callbackHandle).apply()
        downloadWorkerManager = DownloadWorkerManager(context, taskDao, debugMode == 1, showAllCompletedNotification)
        result.success()
    }

    private fun registerCallback(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as List<*>
        callbackHandle = args[0].toString().toLong()
        result.success()
    }

    private fun enqueue(call: MethodCall, result: MethodChannel.Result) {
        val title = call.argument<String>("title")
        val url = call.argument<String>("url").toString()
        val savedDir = call.argument<String>("saved_dir").toString()
        val filename = call.argument<String>("file_name")
        val headers = call.argument<String>("headers")
        val showFileProgressNotification = call.argument<Boolean>(DownloadWorker.SHOW_FILE_PROGRESS_NOTIFICATION)!!
        val openFileFromNotification = call.argument<Boolean>("open_file_from_notification")!!
        val requiresStorageNotLow = call.argument<Boolean>("requires_storage_not_low")!!

        val taskId = downloadWorkerManager.enqueue(url, savedDir, title, filename, headers, showFileProgressNotification, openFileFromNotification, isResume = false, requiresStorageNotLow)
        result.success(taskId)
    }

    private fun loadTasks(call: MethodCall, result: MethodChannel.Result) {
        val tasks: List<DownloadTask> = taskDao.loadAllTasks()
        val array: MutableList<Map<String, Any?>> = ArrayList()
        for (task in tasks) {
            val item: MutableMap<String, Any?> = HashMap()
            item["task_id"] = task.taskId
            item["status"] = task.status
            item["progress"] = task.progress
            item["title"] = task.title
            item["url"] = task.url
            item["file_name"] = task.filename
            item["saved_dir"] = task.savedDir
            item["time_created"] = task.timeCreated
            array.add(item)
        }
        result.success(array)
    }

    private fun loadTasksWithRawQuery(call: MethodCall, result: MethodChannel.Result) {
        val query = call.argument<String>("query")
        val tasks: List<DownloadTask> = taskDao.loadTasksWithRawQuery(query)
        val array: MutableList<Map<String, Any>> = ArrayList()
        for (task in tasks) {
            val item: MutableMap<String, Any> = HashMap()
            item["task_id"] = task.taskId
            item["status"] = task.status
            item["progress"] = task.progress
            item["title"] = task.title
            item["url"] = task.url
            item["file_name"] = task.filename
            item["saved_dir"] = task.savedDir
            item["time_created"] = task.timeCreated
            array.add(item)
        }
        result.success(array)
    }

    private fun cancel(call: MethodCall, result: MethodChannel.Result) {
        val taskId = call.argument<String>("task_id").toString()
        downloadWorkerManager.cancel(taskId)
        result.success()
    }

    private fun cancelAll(call: MethodCall, result: MethodChannel.Result) {
        downloadWorkerManager.cancelAll()
        result.success()
    }

    private fun pause(call: MethodCall, result: MethodChannel.Result) {
        // todo: take from the old java code and refactor, I do not need this now
        result.notImplemented()
    }

    private fun resume(call: MethodCall, result: MethodChannel.Result) {
        // todo: take from the old java code and refactor, I do not need this now
        result.notImplemented()
    }

    private fun retry(call: MethodCall, result: MethodChannel.Result) {
        // todo: take from the old java code and refactor, I do not need this now
        result.notImplemented()
    }

    private fun open(call: MethodCall, result: MethodChannel.Result) {
        // todo: take from the old java code and refactor, I do not need this now
        result.notImplemented()
    }

    private fun remove(call: MethodCall, result: MethodChannel.Result) {
        val taskId = call.argument<String>("task_id").toString()
        val shouldDeleteContent = call.argument<Boolean>("should_delete_content")!!

        val res = downloadWorkerManager.remove(taskId, shouldDeleteContent)
        if (res) {
            result.success()
        } else {
            result.error("invalid_task_id", "not found task corresponding to given task id", null)
        }
    }
}
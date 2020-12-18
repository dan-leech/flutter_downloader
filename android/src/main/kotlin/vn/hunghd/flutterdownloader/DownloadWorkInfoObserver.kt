package vn.hunghd.flutterdownloader

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.WorkInfo

class DownloadWorkInfoObserver(private val callbackMessenger: CallbackMessenger, private val taskDao: TaskDao, private val liveData: LiveData<WorkInfo>) : Observer<WorkInfo> {
    companion object {
        private const val TAG = "flutter_download_info"
    }

    override fun onChanged(workInfo: WorkInfo?) {
        if (workInfo == null) {
            Log.d(TAG, "workInfo is NULL")
            return
        }

        val id = workInfo.id
        val progress = workInfo.progress
        val progressValue = progress.getInt(DownloadWorker.PROGRESS_PROGRESS, 0)

        Log.d(TAG, "taskId[$id] state: " + workInfo.state + " progress: $progressValue")

        if (workInfo.state == WorkInfo.State.RUNNING) {
            if (progressValue < 0) {
                callbackMessenger.sendUpdateProcessEvent(id.toString(), DownloadStatus.ENQUEUED, 0)
            } else {
                taskDao.updateTask(id.toString(), DownloadStatus.RUNNING, progressValue)
                callbackMessenger.sendUpdateProcessEvent(id.toString(), DownloadStatus.RUNNING, progressValue)
            }
        }

        // unsubscribe if observer is not needed anymore to prevent memory leak
        if (workInfo.state == WorkInfo.State.FAILED || workInfo.state == WorkInfo.State.SUCCEEDED || workInfo.state == WorkInfo.State.CANCELLED) {
            if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                callbackMessenger.sendUpdateProcessEvent(id.toString(), DownloadStatus.RUNNING, 100)

                val output = workInfo.outputData
                val fileName = output.getString(DownloadWorker.SUCCESS_FILE_NAME)

                Log.d(TAG, "taskId[$id] state: " + workInfo.state + " fileName: $fileName")

                taskDao.updateTask(id.toString(), DownloadStatus.COMPLETE, 100, filename = fileName)
                callbackMessenger.sendUpdateProcessEvent(id.toString(), DownloadStatus.COMPLETE, 100)
            } else if (workInfo.state == WorkInfo.State.CANCELLED) {
                taskDao.updateTask(id.toString(), DownloadStatus.CANCELED, 0)
                callbackMessenger.sendUpdateProcessEvent(id.toString(), DownloadStatus.CANCELED, 0)
            } else if (workInfo.state == WorkInfo.State.FAILED) {
                taskDao.updateTask(id.toString(), DownloadStatus.FAILED, 0)
                callbackMessenger.sendUpdateProcessEvent(id.toString(), DownloadStatus.FAILED, 0)
            }

            liveData.removeObserver(this)
        }
    }
}
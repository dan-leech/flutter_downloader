package vn.hunghd.flutterdownloader

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import kotlin.coroutines.CoroutineContext

class DownloadWorkerFactory(private val workerCoroutineContext: CoroutineContext, private val fileManager: FileManager) : WorkerFactory() {

    override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            DownloadWorker::class.java.name ->
                DownloadWorker(appContext, workerParameters, workerCoroutineContext, fileManager)
            else ->
                // Return null, so that the base class can delegate to the default WorkerFactory.
                null
        }

    }
}
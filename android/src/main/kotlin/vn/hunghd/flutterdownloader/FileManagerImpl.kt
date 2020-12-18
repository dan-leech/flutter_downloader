package vn.hunghd.flutterdownloader

import java.io.*

class FileManagerImpl : FileManager {

    override fun writeFile(
            inputStream: InputStream,
            path: String,
            isResume: Boolean,
            onProgress: (done: Long) -> Unit
    ): Boolean {
        lateinit var outputFile: File
        lateinit var outputStream: OutputStream
        try {
            outputFile = File(path)
            outputFile.parentFile?.mkdirs()
            val fileReader = ByteArray(BUFFER_SIZE)
            outputStream = FileOutputStream(outputFile, isResume)
            var done = 0L
            var isReading = true
            var time: Long = System.currentTimeMillis()

            while (isReading) {
                val read = inputStream.read(fileReader)
                if (read == -1) {
                    done = -1
                    isReading = false
                } else {
                    done += read
                    outputStream.write(fileReader, 0, read)
                }

                if (System.currentTimeMillis() - time > 500) {
                    // insert pauses to free http bandwidth
                    Thread.sleep(250)
                    time = System.currentTimeMillis()
                }

                onProgress(done)
            }
            outputStream.flush()
            return true
        } catch (e: IOException) {
            if (outputFile.exists()) {
                outputFile.delete()
            }
            throw e
        } finally {
            inputStream.close()
            outputStream.close()
        }
    }

    override fun exists(path: String) = File(path).exists()

    companion object {
        private const val BUFFER_SIZE = 4096
    }
}
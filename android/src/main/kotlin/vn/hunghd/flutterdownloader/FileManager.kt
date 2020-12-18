package vn.hunghd.flutterdownloader

import java.io.InputStream

interface FileManager {

    fun writeFile(
            inputStream: InputStream,
            path: String,
            isResume: Boolean,
            onProgress: (done: Long) -> Unit
    ): Boolean

    fun exists(path: String): Boolean
}
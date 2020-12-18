package vn.hunghd.flutterdownloader

import java.net.URLDecoder

class CommonUtils {
    companion object {
        fun solveFilename(url: String, taskId: String, filename: String? = null, disposition: String? = null, charset: String? = null): String {
            if (!filename.isNullOrEmpty()) return filename

            val suggestedSource = if (disposition.isNullOrEmpty()) url else disposition

            val ext: String = suggestedSource.replaceFirst("(?i)^.*filename=\"?[^.]+.([^\"]+)\"?.*\$".toRegex(), "$1")
            return if (ext.isNotEmpty()) {
                "$taskId." + URLDecoder.decode(ext, charset
                        ?: "ISO-8859-1")
            } else {
                val ext2 = url.substring(url.lastIndexOf(".") + 1)
                if (ext2.isEmpty()) {
                    "$taskId-" + url.substring(url.lastIndexOf("/") + 1)
                } else {
                    "$taskId.$ext2"
                }
            }
        }
    }
}
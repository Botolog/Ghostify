package xyz.botolog.ghostify.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpUpdateDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : UpdateDownloader {
    override suspend fun download(
        url: String,
        destPart: File,
        resumeFrom: Long,
        totalHint: Long?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Long? = withContext(Dispatchers.IO) {
        destPart.parentFile?.mkdirs()
        var offset = if (resumeFrom > 0 && destPart.exists()) destPart.length() else 0L
        if (resumeFrom <= 0) {
            offset = 0L
        }
        var attempt = 0
        var result: Long? = null
        var finished = false
        while (!finished) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                if (offset > 0) {
                    setRequestProperty("Range", UpdateDownloadHelper.rangeHeader(offset))
                }
            }
            try {
                val code = conn.responseCode
                if (code == 416 && offset > 0 && attempt == 0) {
                    attempt++
                    try {
                        destPart.delete()
                    } catch (_: Exception) {
                    }
                    offset = 0L
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("Download failed: HTTP $code")
                }
                val isPartial = code == HttpURLConnection.HTTP_PARTIAL
                if (!isPartial && offset > 0) {
                    offset = 0L
                    try {
                        destPart.delete()
                    } catch (_: Exception) {
                    }
                }
                val contentLength = conn.contentLength.toLong()
                val contentRange = conn.getHeaderField("Content-Range")
                var total: Long? = UpdateDownloadHelper.resolveTotal(offset, contentLength, contentRange)
                if (total == null) total = totalHint
                var downloaded = offset
                onProgress(downloaded, total)
                conn.inputStream.use { input ->
                    FileOutputStream(destPart, offset > 0).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val snapshot = total
                            if (snapshot == null || downloaded > snapshot) {
                                if (contentLength > 0 && total == null) {
                                    total = downloaded + contentLength - (downloaded - offset)
                                }
                            }
                            onProgress(downloaded, total)
                        }
                    }
                }
                val finalLen = if (destPart.exists()) destPart.length() else downloaded
                if (total == null || total <= 0) {
                    result = contentLength.takeIf { it > 0 } ?: finalLen.takeIf { it > 0 }
                } else {
                    result = total
                }
                finished = true
            } finally {
                try {
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }
        result
    }
}

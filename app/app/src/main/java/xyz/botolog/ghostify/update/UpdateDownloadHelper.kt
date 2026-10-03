package xyz.botolog.ghostify.update

object UpdateDownloadHelper {
    const val FINAL_NAME = "ghostify-update.apk"
    const val PART_SUFFIX = ".part"
    const val META_NAME = "ghostify-update.meta"

    data class Meta(val url: String, val versionName: String?, val totalBytes: Long?)

    fun partName(): String = FINAL_NAME + PART_SUFFIX

    fun progress(downloadedBytes: Long, totalBytes: Long?): Float {
        if (totalBytes == null || totalBytes <= 0L) return 0f
        if (downloadedBytes <= 0L) return 0f
        return (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    }

    fun shouldResume(existingBytes: Long, totalBytes: Long?): Boolean {
        if (existingBytes <= 0L) return false
        if (totalBytes == null || totalBytes <= 0L) return true
        return existingBytes < totalBytes
    }

    fun isComplete(existingBytes: Long, totalBytes: Long?): Boolean {
        if (existingBytes <= 0L) return false
        if (totalBytes == null || totalBytes <= 0L) return false
        return existingBytes >= totalBytes
    }

    fun isDuplicate(activeUrl: String?, isActive: Boolean, requestedUrl: String): Boolean {
        return isActive && activeUrl == requestedUrl
    }

    fun rangeHeader(offsetBytes: Long): String = "bytes=$offsetBytes-"

    fun parseTotalFromContentRange(contentRange: String?, fallbackTotal: Long?): Long? {
        if (contentRange == null) return fallbackTotal
        val slash = contentRange.lastIndexOf('/')
        if (slash == -1) return fallbackTotal
        val totalPart = contentRange.substring(slash + 1).trim()
        if (totalPart == "*" || totalPart.isEmpty()) return fallbackTotal
        return totalPart.toLongOrNull() ?: fallbackTotal
    }

    fun resolveTotal(resumeFrom: Long, contentLength: Long, contentRange: String?): Long? {
        if (contentRange != null) {
            val parsed = parseTotalFromContentRange(contentRange, null)
            if (parsed != null && parsed > 0) return parsed
        }
        if (contentLength > 0) {
            return if (contentRange != null) contentLength + resumeFrom else contentLength
        }
        return null
    }

    fun isZipMagic(header: ByteArray): Boolean {
        if (header.size < 4) return false
        return header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()
    }

    fun encodeMeta(url: String, versionName: String?, totalBytes: Long?): String {
        return url + "\n" + (versionName ?: "") + "\n" + (totalBytes?.toString() ?: "")
    }

    fun decodeMeta(raw: String?): Meta? {
        if (raw.isNullOrEmpty()) return null
        val lines = raw.split('\n')
        val url = lines.getOrNull(0) ?: return null
        if (url.isBlank()) return null
        val version = lines.getOrNull(1)?.ifBlank { null }
        val total = lines.getOrNull(2)?.ifBlank { null }?.toLongOrNull()
        return Meta(url, version, total)
    }

    fun metaMatches(meta: Meta?, url: String, versionName: String?): Boolean {
        if (meta == null) return false
        if (meta.url != url) return false
        if (versionName != null && meta.versionName != null && meta.versionName != versionName) return false
        return true
    }
}

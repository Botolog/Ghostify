package xyz.botolog.ghostify.file

/**
 * Pure filename logic: sanitizing unsafe characters and truncating to the
 * filesystem byte limit without splitting a multi-byte UTF-8 character.
 *
 * This is the single source of truth for how a downloaded MP3 is named, so the
 * DB `file_path` written by the download worker always matches the file that
 * spotdl actually produces (`{artists} - {title}.mp3`).
 */
object FileNames {

    /** POSIX/Windows/NTFS/F2FS limit on a single filename component, in bytes. */
    const val MAX_FILENAME_BYTES = 255

    const val MP3_EXTENSION = ".mp3"

    /** Byte budget left for the stem once the extension is reserved. */
    val MAX_STEM_BYTES: Int = MAX_FILENAME_BYTES - MP3_EXTENSION.length

    private const val FALLBACK_STEM = "track"
    private const val CONTROL_CHAR_START = 0x00
    private const val CONTROL_CHAR_END = 0x1F
    private const val DEL_CODE = 0x7F
    private const val C1_CONTROL_END = 0x9F

    /**
     * Characters that are illegal in filenames on common filesystems, plus the
     * NUL byte (which terminates C strings and is rejected by many APIs).
     */
    private val INVALID_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')

    /**
     * Returns the sanitized file stem (no extension) for a raw song name.
     *
     * - Replaces `/\:*?"<>|`, NUL, C0/C1 control chars and DEL with `_`.
     * - Trims leading/trailing whitespace and dots (avoids hidden files and the
     *   trailing-dot pitfalls of Windows/NTFS; `/` and `\` are already removed
     *   so path traversal is impossible).
     * - Falls back to [FALLBACK_STEM] when nothing is left.
     */
    fun sanitizeStem(raw: String): String = clean(raw).ifBlank { FALLBACK_STEM }

    /**
     * The file stem (no extension) for the spotdl output template
     * `{artists} - {title}`, sanitized but not yet truncated. Derives from
     * [trackFileName] so both always agree on blank-part handling.
     */
    fun stem(artists: String, title: String): String =
        trackFileName(artists, title).removeSuffix(MP3_EXTENSION)

    /**
     * The full, filesystem-safe MP3 filename for the given artists and title.
     *
     * Blank artist/title parts are dropped (no dangling separator), and a track
     * with no usable name at all falls back to [FALLBACK_STEM]. The stem is
     * truncated so the complete name (including `.mp3`) is at most
     * [MAX_FILENAME_BYTES] bytes, never splitting a multi-byte UTF-8 character.
     */
    fun trackFileName(artists: String, title: String): String {
        val parts = listOfNotNull(
            clean(artists).ifBlank { null },
            clean(title).ifBlank { null },
        )
        val combined = parts.joinToString(ARTIST_TITLE_SEPARATOR).ifBlank { FALLBACK_STEM }
        return truncateToBytes(combined, MAX_STEM_BYTES) + MP3_EXTENSION
    }

    private const val ARTIST_TITLE_SEPARATOR = " - "

    private fun clean(raw: String): String {
        if (raw.isBlank()) return ""
        val builder = StringBuilder(raw.length)
        for (ch in raw) {
            val code = ch.code
            builder.append(
                if (ch in INVALID_CHARS || code in CONTROL_CHAR_START..CONTROL_CHAR_END || code in DEL_CODE..C1_CONTROL_END) '_' else ch,
            )
        }
        return builder.toString().trim { it == '.' || it.isWhitespace() }
    }

    /**
     * Truncates [value] to at most [maxBytes] UTF-8 bytes on a character
     * boundary. Multi-byte code points that would straddle the limit are
     * dropped whole, keeping the result valid UTF-8. Code-point aware, so
     * surrogate pairs (emoji, rare CJK) are never split.
     */
    fun truncateToBytes(value: String, maxBytes: Int): String {
        require(maxBytes >= 0) { "maxBytes must be >= 0, was $maxBytes" }
        if (maxBytes == 0) return ""
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value

        val builder = StringBuilder()
        var size = 0
        val iterator = value.codePoints().iterator()
        while (iterator.hasNext()) {
            val codePoint = iterator.nextInt()
            val charArray = Character.toChars(codePoint)
            val charBytes = String(charArray).toByteArray(Charsets.UTF_8).size
            if (size + charBytes > maxBytes) break
            builder.append(charArray)
            size += charBytes
        }
        return builder.toString()
    }
}

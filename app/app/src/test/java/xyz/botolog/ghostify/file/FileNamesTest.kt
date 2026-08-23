package xyz.botolog.ghostify.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    // ── sanitizeStem ──────────────────────────────────────────────────────

    @Test
    fun sanitizeStem_normalNameReturnsCleaned() {
        assertEquals("hello world", FileNames.sanitizeStem("hello world"))
    }

    @Test
    fun sanitizeStem_replacesInvalidChars() {
        assertEquals("a_b_c_d_e_f_g_h_i_j", FileNames.sanitizeStem("a/b\\c:d*e?f\"g<h>i|j"))
    }

    @Test
    fun sanitizeStem_replacesNullByte() {
        assertEquals("hello_world", FileNames.sanitizeStem("hello\u0000world"))
    }

    @Test
    fun sanitizeStem_replacesControlChars() {
        // Trailing DEL becomes a trailing underscore: only whitespace/dots are trimmed.
        assertEquals("ab_cd_ef_", FileNames.sanitizeStem("ab\u0001cd\u001Fef\u007F"))
    }

    @Test
    fun sanitizeStem_replacesC1ControlChars() {
        assertEquals("a_b", FileNames.sanitizeStem("a\u0080b"))
        assertEquals("a_b", FileNames.sanitizeStem("a\u009Fb"))
    }

    @Test
    fun sanitizeStem_trimsLeadingTrailingDots() {
        assertEquals("hello", FileNames.sanitizeStem("...hello..."))
    }

    @Test
    fun sanitizeStem_trimsLeadingTrailingWhitespace() {
        assertEquals("hello", FileNames.sanitizeStem("  hello  "))
    }

    @Test
    fun sanitizeStem_blankStringFallsBackToTrack() {
        assertEquals("track", FileNames.sanitizeStem(""))
        assertEquals("track", FileNames.sanitizeStem("   "))
        assertEquals("track", FileNames.sanitizeStem("..."))
    }

    @Test
    fun sanitizeStem_allInvalidCharsBecomeUnderscores() {
        // Every char is illegal -> one underscore each. The "track" fallback only
        // triggers when the CLEANED result is blank, and underscores are not blank.
        assertEquals("_________", FileNames.sanitizeStem("/\\:*?\"<>|"))
    }

    @Test
    fun sanitizeStem_preservesUnicodeLetters() {
        // No Unicode normalization happens: precomposed input stays precomposed.
        assertEquals("caf\u00e9", FileNames.sanitizeStem("caf\u00e9"))
        assertEquals("cafe\u0301", FileNames.sanitizeStem("cafe\u0301"))
    }

    @Test
    fun sanitizeStem_preservesUnicodeChinese() {
        assertEquals("\u4e16\u754c", FileNames.sanitizeStem("\u4e16\u754c"))
    }

    @Test
    fun sanitizeStem_replacesPathTraversal() {
        // Interior dots are legal filename chars; only the separator '/' is replaced.
        assertEquals("a.._b", FileNames.sanitizeStem("a../b"))
    }

    // ── trackFileName ─────────────────────────────────────────────────────

    @Test
    fun trackFileName_normalArtistAndTitle() {
        assertEquals("Artist - Title.mp3", FileNames.trackFileName("Artist", "Title"))
    }

    @Test
    fun trackFileName_blankArtistDropped() {
        assertEquals("Title.mp3", FileNames.trackFileName("", "Title"))
    }

    @Test
    fun trackFileName_blankTitleDropped() {
        assertEquals("Artist.mp3", FileNames.trackFileName("Artist", ""))
    }

    @Test
    fun trackFileName_bothBlankFallsBack() {
        assertEquals("track.mp3", FileNames.trackFileName("", ""))
    }

    @Test
    fun trackFileName_specialCharsInArtist() {
        assertEquals("Artist_Name - Title.mp3", FileNames.trackFileName("Artist/Name", "Title"))
    }

    @Test
    fun trackFileName_specialCharsInTitle() {
        assertEquals("Artist - Title_Name.mp3", FileNames.trackFileName("Artist", "Title:Name"))
    }

    @Test
    fun trackFileName_alwaysEndsWithMp3() {
        val result = FileNames.trackFileName("A", "B")
        assertTrue(result.endsWith(".mp3"))
    }

    @Test
    fun trackFileName_doesNotExceedMaxBytes() {
        val longName = "A".repeat(300)
        val result = FileNames.trackFileName(longName, "Title")
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_FILENAME_BYTES)
    }

    // ── stem ──────────────────────────────────────────────────────────────

    @Test
    fun stem_matchesTrackFileNameWithoutExtension() {
        val expected = FileNames.trackFileName("Artist", "Title").removeSuffix(".mp3")
        assertEquals(expected, FileNames.stem("Artist", "Title"))
    }

    @Test
    fun stem_blankParts() {
        val expected = FileNames.trackFileName("", "").removeSuffix(".mp3")
        assertEquals(expected, FileNames.stem("", ""))
    }

    // ── truncateToBytes ───────────────────────────────────────────────────

    @Test
    fun truncateToBytes_shortStringUnchanged() {
        assertEquals("abc", FileNames.truncateToBytes("abc", 10))
    }

    @Test
    fun truncateToBytes_exactlyAtLimit() {
        assertEquals("abc", FileNames.truncateToBytes("abc", 3))
    }

    @Test
    fun truncateToBytes_singleByteCharsTruncated() {
        assertEquals("abc", FileNames.truncateToBytes("abcdef", 3))
    }

    @Test
    fun truncateToBytes_multiByteCharsNotSplit() {
        // "café" with accent is 5 bytes: c(1) a(1) f(1) é(2)
        val input = "caf\u00e9"
        assertEquals(5, input.toByteArray(Charsets.UTF_8).size)
        val truncated = FileNames.truncateToBytes(input, 4)
        // Should drop é (2 bytes) to avoid splitting, leaving "caf"
        assertEquals("caf", truncated)
    }

    @Test
    fun truncateToBytes_emojiNotSplit() {
        val emoji = "\uD83D\uDE00" // 😀, 4 bytes in UTF-8
        val truncated = FileNames.truncateToBytes(emoji, 3)
        assertEquals("", truncated)
    }

    @Test
    fun truncateToBytes_emojiAtLimit() {
        val emoji = "\uD83D\uDE00"
        val truncated = FileNames.truncateToBytes(emoji, 4)
        assertEquals(emoji, truncated)
    }

    @Test
    fun truncateToBytes_zeroMaxBytesReturnsEmpty() {
        assertEquals("", FileNames.truncateToBytes("hello", 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncateToBytes_negativeMaxBytesThrows() {
        FileNames.truncateToBytes("hello", -1)
    }

    @Test
    fun truncateToBytes_emptyStringReturnsEmpty() {
        assertEquals("", FileNames.truncateToBytes("", 10))
    }

    @Test
    fun truncateToBytes_asciiAtExactBoundary() {
        assertEquals("abcde", FileNames.truncateToBytes("abcdefghij", 5))
    }

    @Test
    fun truncateToBytes_mixedAsciiAndMultiByte() {
        // "aéb" = a(1) + é(2) + b(1) = 4 bytes
        val input = "a\u00e9b"
        assertEquals(4, input.toByteArray(Charsets.UTF_8).size)
        assertEquals("a\u00e9b", FileNames.truncateToBytes(input, 4))
    }

    // ── constants ─────────────────────────────────────────────────────────

    @Test
    fun maxFilenameBytesIs255() {
        assertEquals(255, FileNames.MAX_FILENAME_BYTES)
    }

    @Test
    fun mp3ExtensionHasDot() {
        assertEquals(".mp3", FileNames.MP3_EXTENSION)
    }

    @Test
    fun maxStemBytesAccountsForExtension() {
        assertEquals(255 - 4, FileNames.MAX_STEM_BYTES)
    }
}

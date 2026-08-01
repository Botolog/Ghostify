package com.ghostify.file

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure-logic tests for filename sanitization and byte-limit truncation (T-154 core). */
class FileNameTest {

    // ---- sanitizeStem -------------------------------------------------------

    @Test
    fun `replaces filesystem-invalid characters with underscore but keeps valid ones`() {
        assertEquals("AC_DC - Let's Go!_ Remix", FileNames.sanitizeStem("AC/DC - Let's Go!: Remix"))
        assertEquals("a_b_c_d_e_f_", FileNames.sanitizeStem("a\\b*c?d\"e<f>"))
        assertEquals("x_y", FileNames.sanitizeStem("x|y"))
    }

    @Test
    fun `strips control characters`() {
        assertEquals("A_B_C_D", FileNames.sanitizeStem("A\u0000B\u0001C\u0007D"))
    }

    @Test
    fun `trims leading and trailing dots and whitespace`() {
        assertEquals("title", FileNames.sanitizeStem("..title.."))
        assertEquals("title", FileNames.sanitizeStem("  title  "))
        assertEquals("title", FileNames.sanitizeStem(".. title .."))
        assertEquals("title", FileNames.sanitizeStem(".  title . "))
    }

    @Test
    fun `falls back for blank or dot-only input`() {
        assertEquals("track", FileNames.sanitizeStem(""))
        assertEquals("track", FileNames.sanitizeStem("   "))
        assertEquals("track", FileNames.sanitizeStem("...."))
    }

    @Test
    fun `path traversal characters are neutralized`() {
        assertEquals("_etc_passwd", FileNames.sanitizeStem("../etc/passwd"))
        assertFalse(FileNames.sanitizeStem("../etc/passwd").contains('/'))
        assertFalse(FileNames.sanitizeStem("../etc/passwd").contains('\\'))
    }

    // ---- truncateToBytes ----------------------------------------------------

    @Test
    fun `short strings are returned unchanged`() {
        assertEquals("hello", FileNames.truncateToBytes("hello", 5))
        assertEquals("hello", FileNames.truncateToBytes("hello", 255))
        assertEquals("", FileNames.truncateToBytes("", 10))
        assertEquals("", FileNames.truncateToBytes("hello", 0))
    }

    @Test
    fun `truncates at byte boundary without splitting multibyte characters`() {
        val smiley = "\uD83D\uDE42" // 🙂 = 4 UTF-8 bytes
        assertEquals("", FileNames.truncateToBytes(smiley, 3))
        assertEquals(smiley, FileNames.truncateToBytes(smiley, 4))
        assertEquals(smiley, FileNames.truncateToBytes(smiley, 255))

        val chinese = "你好" // 3 bytes per char
        assertEquals("你", FileNames.truncateToBytes(chinese, 5))
        assertEquals("你", FileNames.truncateToBytes(chinese, 3))
        assertEquals("你好", FileNames.truncateToBytes(chinese, 6))
    }

    @Test
    fun `truncated output is always valid UTF-8`() {
        val mixed = "🎵😀🎧".repeat(50) // supplementary chars, 4 bytes each
        val truncated = FileNames.truncateToBytes(mixed, 251)
        val decoded = String(truncated.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        assertEquals(truncated, decoded)
        assertTrue(truncated.toByteArray(Charsets.UTF_8).size <= 251)
    }

    // ---- trackFileName (T-154) ----------------------------------------------

    @Test
    fun `produces artists - title dot mp3`() {
        assertEquals("Queen - Bohemian Rhapsody.mp3", FileNames.trackFileName("Queen", "Bohemian Rhapsody"))
    }

    @Test
    fun `long ascii names never exceed 255 bytes and keep the extension`() {
        val longTitle = "A".repeat(500)
        val name = FileNames.trackFileName("Artist", longTitle)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(name.endsWith(".mp3"))
        assertEquals(FileNames.MAX_FILENAME_BYTES, name.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `long multibyte names truncate to valid UTF-8 within 255 bytes`() {
        val cjk = "樂".repeat(200) // 3 bytes each -> 600 bytes raw
        val name = FileNames.trackFileName("歌手", cjk)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(name.endsWith(".mp3"))
        val stem = name.removeSuffix(".mp3")
        val roundTripped = String(stem.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        assertEquals(stem, roundTripped)
    }

    @Test
    fun `fully-dotted names fall back to the track stem`() {
        assertEquals("track.mp3", FileNames.trackFileName("...", "..."))
    }

    @Test
    fun `junk-only names still produce a safe writable name`() {
        val name = FileNames.trackFileName("///", "...")
        assertTrue(name.endsWith(".mp3"))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        assertFalse(name.any { it == '/' || it == '\\' })
    }
}

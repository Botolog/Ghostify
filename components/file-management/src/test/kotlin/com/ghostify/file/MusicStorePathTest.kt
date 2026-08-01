package com.ghostify.file

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T-150 — Output path resolution matches DB `file_path` after download.
 *
 * The download worker records `songs.file_path` via [MusicStore.dbFilePath],
 * which is derived from the same deterministic template spotdl uses. These
 * tests prove the resolved path is stable, lives inside the store root, and
 * equals the file found on disk after a simulated download.
 */
class MusicStorePathTest {

    private val fs = JvmFileSystem

    @TempDir
    lateinit var tempDir: File

    private fun store(): MusicStore = MusicStore(root = tempDir, fs = fs)

    @Test
    fun `resolved path is deterministic and matches the DB file_path`() {
        val store = store()
        val resolved = store.resolveOutputPath("Queen", "Bohemian Rhapsody")

        assertEquals("Queen - Bohemian Rhapsody.mp3", resolved.name)
        assertEquals(store.dbFilePath("Queen", "Bohemian Rhapsody"), resolved.absolutePath)
        assertEquals(resolved, store.resolveOutputPath("Queen", "Bohemian Rhapsody"))
    }

    @Test
    fun `resolved path stays inside the store root`() {
        val store = store()
        val path = store.resolveOutputPath("Artist", "Title").absolutePath
        assertTrue(path.startsWith(tempDir.absolutePath + File.separator))
    }

    @Test
    fun `downloaded file on disk matches the recorded file_path`() {
        val store = store()
        val expected = store.resolveOutputPath("Daft Punk", "Around The World")

        // Simulate spotdl writing the download at the templated path.
        assertTrue(fs.writeBytes(expected, ByteArray(1024)))

        // T-150: what the DB records after download == what spotdl produced.
        assertEquals(expected.absolutePath, store.dbFilePath("Daft Punk", "Around The World"))
        assertEquals(expected, store.findOutputFile("Daft Punk", "Around The World"))
        assertTrue(store.exists(expected))
        assertTrue(store.isDownloaded(expected.absolutePath))
    }

    @Test
    fun `findOutputFile discovers a deduplicated on-disk name`() {
        val store = store()
        // A collision forced spotdl to use its " (1)" suffix.
        val onDisk = File(tempDir, "Queen - We Are The Champions (1).mp3")
        assertTrue(fs.writeBytes(onDisk, ByteArray(512)))

        val found = store.findOutputFile("Queen", "We Are The Champions")
        assertEquals(onDisk, found)
        assertNotEquals(onDisk, store.resolveOutputPath("Queen", "We Are The Champions"))
    }

    @Test
    fun `findOutputFile falls back to the expected path when nothing exists`() {
        val store = store()
        assertEquals(
            store.resolveOutputPath("Nobody", "Has Downloaded This"),
            store.findOutputFile("Nobody", "Has Downloaded This"),
        )
    }

    @Test
    fun `resolveOutputPath yields the same path for the same track every time`() {
        val store = store()
        val a = store.resolveOutputPath("AC/DC", "Thunderstruck")
        val b = store.resolveOutputPath("AC/DC", "Thunderstruck")
        assertEquals(a, b)
        assertEquals("AC_DC - Thunderstruck.mp3", a.name)
    }
}

package com.ghostify.file

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-155 — Re-sync detects a manually-deleted file and re-queues it.
 *
 * The re-sync diff (PROJECT.md §6.2) resets a DOWNLOADED song to PENDING when
 * `song.file_path == null OR !fileExists(song.file_path)`. This test exercises
 * the [MusicStore.isDownloaded] check that drives that decision.
 */
class MusicStoreSyncTest {

    private val fs = JvmFileSystem

    @TempDir
    lateinit var tempDir: File

    private fun store(): MusicStore = MusicStore(root = tempDir, fs = fs)

    @Test
    fun `downloaded song with file on disk is not re-queued`() {
        val store = store()
        val file = File(tempDir, "Artist - Present.mp3")
        assertTrue(fs.writeBytes(file, ByteArray(2048)))

        // The exact predicate the sync diff evaluates:
        val shouldRequeue = store.dbFilePath("Artist", "Present").let { path ->
            store.isDownloaded(path).not()
        }
        assertTrue(store.isDownloaded(file.absolutePath))
        assertFalse(shouldRequeue, "file exists -> keep DOWNLOADED, do not re-download")
    }

    @Test
    fun `manually-deleted file is detected and re-queued`() {
        val store = store()
        val file = File(tempDir, "Artist - Deleted.mp3")
        assertTrue(fs.writeBytes(file, ByteArray(2048)))

        // Simulate the user deleting the MP3 behind the app's back.
        assertTrue(fs.delete(file))
        assertFalse(fs.exists(file))

        assertFalse(store.isDownloaded(file.absolutePath))
        // Re-sync predicate for a song whose file vanished:
        val shouldRequeue = !store.isDownloaded(file.absolutePath)
        assertTrue(shouldRequeue, "missing file -> reset to PENDING and re-download")
    }

    @Test
    fun `null file_path is treated as not downloaded`() {
        val store = store()
        assertFalse(store.isDownloaded(null))
        assertTrue(store.isDownloaded(null).not())
    }

    @Test
    fun `blank file_path is treated as not downloaded`() {
        val store = store()
        assertFalse(store.isDownloaded(""))
        assertFalse(store.isDownloaded("   "))
    }

    @Test
    fun `a path that never existed is detected as missing`() {
        val store = store()
        val ghost = File(tempDir, "Never - Downloaded.mp3").absolutePath
        assertFalse(store.isDownloaded(ghost))
    }
}

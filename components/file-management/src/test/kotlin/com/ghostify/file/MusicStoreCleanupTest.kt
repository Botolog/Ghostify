package com.ghostify.file

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-152 — Deleting a song also deletes its `.spotdl` sidecar.
 * T-153 — Orphan files (in the store but not in the DB) are removable via clear-cache.
 */
class MusicStoreCleanupTest {

    private val fs = JvmFileSystem

    @TempDir
    lateinit var tempDir: File

    private fun store(): MusicStore = MusicStore(root = tempDir, fs = fs)

    private fun write(name: String, size: Int = 256): File {
        val file = File(tempDir, name)
        assertTrue(fs.writeBytes(file, ByteArray(size)))
        return file
    }

    // ---- T-152 --------------------------------------------------------------

    @Test
    fun `deleting a song removes its sidecar spotdl file too`() {
        val store = store()
        val song = write("Queen - We Are The Champions.mp3")
        val sidecar = write("Queen - We Are The Champions.mp3.spotdl")
        val sibling = write("Other - Track.mp3")

        val result = store.deleteSongFile(song)

        assertTrue(result.fileExisted)
        assertTrue(result.fileDeleted)
        assertTrue(result.sidecarDeleted)
        assertTrue(result.cleaned)
        assertFalse(fs.exists(song))
        assertFalse(fs.exists(sidecar))
        assertTrue(fs.exists(sibling), "unrelated files must be untouched")
    }

    @Test
    fun `sidecar path is the mp3 path plus the spotdl suffix`() {
        val store = store()
        val song = File(tempDir, "A - B.mp3")
        assertEquals(File(tempDir, "A - B.mp3.spotdl"), store.sidecarOf(song))
    }

    @Test
    fun `deleting an already-missing song is not an error`() {
        val store = store()
        val ghost = File(tempDir, "Gone - Track.mp3")
        val sidecar = store.sidecarOf(ghost)

        val result = store.deleteSongFile(ghost)

        assertFalse(result.fileExisted)
        assertTrue(result.fileDeleted, "disk is consistent even when file was already gone")
        assertTrue(result.sidecarDeleted)
        assertFalse(fs.exists(ghost))
        assertFalse(fs.exists(sidecar))
    }

    @Test
    fun `deleteSongFile accepts the DB file_path string`() {
        val store = store()
        val song = write("Artist - Title.mp3")
        write("Artist - Title.mp3.spotdl")

        val result = store.deleteSongFile(song.absolutePath)
        assertTrue(result.cleaned)
        assertEquals(0, store.storeUsedBytes())
    }

    // ---- T-153 --------------------------------------------------------------

    @Test
    fun `orphan detection finds files not referenced by the DB`() {
        val store = store()
        val known = write("Known - Song.mp3")
        write("Known - Song.mp3.spotdl") // sidecar of a known song is NOT an orphan
        val strayMp3 = write("Stray - Track.mp3")
        val straySidecar = write("Abandoned - Track.mp3.spotdl")
        val temp = write(".tmp-download.part")

        val dbPaths = listOf(known.absolutePath)
        val orphans = store.orphanFiles(dbPaths)

        assertContains(orphans, strayMp3)
        assertContains(orphans, straySidecar)
        assertContains(orphans, temp)
        assertFalse(orphans.contains(known))
        assertFalse(orphans.contains(File(tempDir, "Known - Song.mp3.spotdl")))
    }

    @Test
    fun `clearOrphans removes only unreferenced files`() {
        val store = store()
        val known = write("Known - Song.mp3", 1024)
        write("Known - Song.mp3.spotdl")
        write("Orphan - One.mp3")
        write("Orphan - Two.mp3.spotdl")

        val before = store.storeUsedBytes()
        val removed = store.clearOrphans(listOf(known.absolutePath))

        assertEquals(2, removed.size)
        assertTrue(fs.exists(known), "DB-referenced song must survive clear-cache")
        assertTrue(fs.exists(store.sidecarOf(known)), "its sidecar must survive too")
        assertEquals(1024 + 256, store.storeUsedBytes())
        assertTrue(before > store.storeUsedBytes())
    }

    @Test
    fun `clear-cache frees space without deleting library records`() {
        val store = store()
        write("Library - Kept.mp3", 4096)
        write("Library - Kept.mp3.spotdl")
        repeat(5) { write("Garbage - $it.mp3", 512) }

        val dbPaths = listOf(File(tempDir, "Library - Kept.mp3").absolutePath)
        store.clearOrphans(dbPaths)

        val remaining = fs.listFiles(tempDir).map { it.name }
        assertEquals(listOf("Library - Kept.mp3", "Library - Kept.mp3.spotdl"), remaining.sorted())
    }

    @Test
    fun `directories inside the store are not treated as orphans`() {
        val store = store()
        val subdir = File(tempDir, "subdir")
        assertTrue(subdir.mkdirs())
        assertTrue(fs.writeBytes(File(subdir, "Nested - Track.mp3"), ByteArray(64)))

        val orphans = store.orphanFiles(emptyList())
        assertFalse(orphans.contains(subdir))
        assertFalse(orphans.contains(File(subdir, "Nested - Track.mp3")), "nested files are out of scope")
        assertEquals(0, store.storeUsedBytes(), "only direct regular files count toward store size")
    }
}

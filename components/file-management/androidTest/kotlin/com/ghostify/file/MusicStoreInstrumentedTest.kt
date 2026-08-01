package com.ghostify.file

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device-only instrumentation tests (T-151, T-152, T-153, T-154, T-155, T-156)
 * that can only run on an emulator/device because they exercise Android APIs.
 *
 * Run with:
 *   ./gradlew :app:connectedDebugAndroidTest --tests com.ghostify.file.MusicStoreInstrumentedTest
 */
@RunWith(AndroidJUnit4::class)
class MusicStoreInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun T151_app_scoped_dir_used_without_storage_permission() {
        // getExternalFilesDir requires no runtime permission by definition.
        val root = context.getExternalFilesDir(null)
        assertTrue("app-scoped dir must exist", root != null && root.isDirectory)
        val store = musicStore(context)

        // App-scoped external storage lives under /sdcard/Android/data/<pkg>/files.
        val expectedPrefix = File(
            context.getExternalFilesDir(null)!!.absolutePath,
        ).absolutePath
        assertEquals(expectedPrefix, store.rootDir.absolutePath)
        assertTrue(store.rootDir.absolutePath.contains(File("Android", "data").toString()))
    }

    @Test
    fun T152_delete_song_removes_sidecar() {
        val store = storeInCleanTempSubdir()
        val song = File(store.rootDir, "Instrument - Song.mp3")
        val sidecar = store.sidecarOf(song)
        song.writeBytes(ByteArray(128))
        sidecar.writeBytes(ByteArray(64))

        val result = store.deleteSongFile(song)
        assertTrue(result.cleaned)
        assertFalse(song.exists())
        assertFalse(sidecar.exists())
    }

    @Test
    fun T153_orphans_removed_clear_cache_keeps_db_files() {
        val store = storeInCleanTempSubdir()
        val known = File(store.rootDir, "Known - Song.mp3")
        val orphan = File(store.rootDir, "Stray - Track.mp3")
        known.writeBytes(ByteArray(128))
        orphan.writeBytes(ByteArray(64))

        val removed = store.clearOrphans(listOf(known.absolutePath))
        assertTrue(removed.contains(orphan))
        assertTrue(known.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun T154_long_filename_truncated_no_write_failure() {
        val store = storeInCleanTempSubdir()
        val title = "A".repeat(400)
        val path = store.resolveOutputPath("Instrument", title)
        assertTrue(path.name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(path.name.endsWith(".mp3"))
        path.writeBytes(ByteArray(16)) // must not throw
        assertTrue(path.exists())
    }

    @Test
    fun T155_resync_detects_manually_deleted_file() {
        val store = storeInCleanTempSubdir()
        val song = File(store.rootDir, "Sync - Present.mp3")
        song.writeBytes(ByteArray(128))
        assertTrue(store.isDownloaded(song.absolutePath))

        assertTrue(song.delete())
        assertFalse(store.isDownloaded(song.absolutePath))
    }

    @Test
    fun T156_disk_space_matches_statfs() {
        val store = storeInCleanTempSubdir()
        File(store.rootDir, "Measured - File.mp3").writeBytes(ByteArray(512))

        val report = store.diskSpace()
        assertEquals(512L, report.usedBytes)
        assertTrue(report.freeBytes > 0L)
        assertTrue(report.totalBytes > report.freeBytes)
    }

    /** Fresh store under a unique subdir so tests never collide. */
    private fun storeInCleanTempSubdir(): MusicStore {
        val root = File(context.getExternalFilesDir(null), "test-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        return MusicStore(root = root)
    }
}

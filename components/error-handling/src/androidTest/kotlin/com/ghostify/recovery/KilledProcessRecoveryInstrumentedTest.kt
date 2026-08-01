package com.ghostify.recovery

import android.content.ContentValues
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-159 (device half): after a forced kill the real Room DB is consistent, downloads are
 * recoverable, and nothing is left stuck in DOWNLOADING. The JVM half of T-159 (pure logic,
 * fake DAO) lives in `src/test/.../RecoveryLogicTest`; this test runs the SAME logic against
 * the real Room DAO and its generated SQL.
 *
 * DEVICE-ONLY — run via `connectedAndroidTest` on an emulator/device.
 */
@RunWith(AndroidJUnit4::class)
class KilledProcessRecoveryInstrumentedTest {

    private lateinit var db: RecoveryTestDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RecoveryTestDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seedSong(id: String, status: String, playlistId: String? = null) {
        db.openHelper.writableDatabase.insert("songs", null, ContentValues().apply {
            put("id", id)
            put("status", status)
            put("playlist_id", playlistId)
        })
    }

    private fun seedPlaylist(id: String, status: String) {
        db.openHelper.writableDatabase.insert("playlists", null, ContentValues().apply {
            put("id", id)
            put("status", status)
        })
    }

    @Test
    fun T159_killLeavesNothingStuckInDownloading() {
        seedPlaylist("p1", "DOWNLOADING")
        seedSong("s1", "DOWNLOADING", "p1")
        seedSong("s2", "QUEUED", "p1")
        seedSong("s3", "DOWNLOADED", "p1")
        seedSong("s4", "FAILED", "p1")

        val report = KilledProcessRecovery(db.recoveryDao()).recover()

        assertEquals(2, report.resetSongCount)
        assertEquals(1, report.resetPlaylistCount)

        val songs = db.recoveryDao().songsSnapshot()
        assertEquals(SongStatus.PENDING, songs.first { it.id == "s1" }.status)
        assertEquals(SongStatus.PENDING, songs.first { it.id == "s2" }.status)
        assertEquals(SongStatus.DOWNLOADED, songs.first { it.id == "s3" }.status)
        assertEquals(SongStatus.FAILED, songs.first { it.id == "s4" }.status)

        val playlists = db.recoveryDao().playlistsSnapshot()
        assertEquals(PlaylistStatus.READY, playlists.first { it.id == "p1" }.status)
    }

    @Test
    fun T159_playlistWithNoDownloadsSettlesToNew() {
        seedPlaylist("p1", "DOWNLOADING")
        seedSong("s1", "DOWNLOADING", "p1")

        KilledProcessRecovery(db.recoveryDao()).recover()

        assertEquals(PlaylistStatus.NEW, db.recoveryDao().playlistsSnapshot().single().status)
    }

    @Test
    fun T159_recoveryIsIdempotentAgainstRealDb() {
        seedPlaylist("p1", "DOWNLOADING")
        seedSong("s1", "DOWNLOADING", "p1")

        val recovery = KilledProcessRecovery(db.recoveryDao())
        recovery.recover()
        val second = recovery.recover()

        assertTrue("second recovery run must be a no-op", second.plan.isEmpty)
        assertEquals(
            listOf(SongStatus.PENDING),
            db.recoveryDao().songsSnapshot().map { it.status },
        )
    }
}

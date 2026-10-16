package xyz.botolog.ghostify.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CoverImageModelTest {

    private val localExists = { _: File -> true }
    private val localMissing = { _: File -> false }

    @Test
    fun localFileIsPreferredWhenItExists() {
        val model = resolveCoverImageModel("/covers/a.jpg", "https://img/a.jpg", localExists)

        assertEquals(File("/covers/a.jpg"), model)
    }

    @Test
    fun remoteUrlIsUsedWhenLocalFileIsGone() {
        val model = resolveCoverImageModel("/covers/a.jpg", "https://img/a.jpg", localMissing)

        assertEquals("https://img/a.jpg", model)
    }

    @Test
    fun remoteUrlIsUsedWhenThereIsNoLocalFile() {
        val model = resolveCoverImageModel(null, "https://img/a.jpg", localMissing)

        assertEquals("https://img/a.jpg", model)
    }

    @Test
    fun blankLocalPathIsIgnored() {
        val model = resolveCoverImageModel("   ", "https://img/a.jpg", localExists)

        assertEquals("https://img/a.jpg", model)
    }

    @Test
    fun nullIsReturnedWhenNeitherSourceIsUsable() {
        assertNull(resolveCoverImageModel(null, null, localMissing))
        assertNull(resolveCoverImageModel("/covers/a.jpg", null, localMissing))
        assertNull(resolveCoverImageModel("", "  ", localMissing))
    }

    @Test
    fun remoteUrlIsTrimmed() {
        val model = resolveCoverImageModel(null, "  https://img/a.jpg  ", localMissing)

        assertEquals("https://img/a.jpg", model)
    }

    // ── Playlist cover with last-resort track fallback ──────────────

    @Test
    fun playlistLocalIsPreferredOverOwnRemoteAndFallback() {
        val model = resolvePlaylistCoverModel(
            "/covers/pl.jpg",
            "https://img/pl.jpg",
            "/covers/song.jpg",
            "https://img/song.jpg",
            localExists,
        )

        assertEquals(File("/covers/pl.jpg"), model)
    }

    @Test
    fun playlistRemoteBeatsTrackFallback() {
        val model = resolvePlaylistCoverModel(
            null,
            "https://img/pl.jpg",
            "/covers/song.jpg",
            "https://img/song.jpg",
            localExists,
        )

        assertEquals("https://img/pl.jpg", model)
    }

    @Test
    fun stalePlaylistLocalFallsBackToPlaylistRemoteNotTrack() {
        val model = resolvePlaylistCoverModel(
            "/covers/pl.jpg",
            "https://img/pl.jpg",
            "/covers/song.jpg",
            "https://img/song.jpg",
            localMissing,
        )

        assertEquals("https://img/pl.jpg", model)
    }

    @Test
    fun trackLocalIsLastResortWhenPlaylistHasNoOwnArt() {
        val model = resolvePlaylistCoverModel(
            null,
            null,
            "/covers/song.jpg",
            "https://img/song.jpg",
            localExists,
        )

        assertEquals(File("/covers/song.jpg"), model)
    }

    @Test
    fun trackRemoteIsLastResortWhenNothingElseUsable() {
        val model = resolvePlaylistCoverModel(
            null,
            "  ",
            "/covers/song.jpg",
            "https://img/song.jpg",
            localMissing,
        )

        assertEquals("https://img/song.jpg", model)
    }

    @Test
    fun nullWhenNeitherPlaylistNorFallbackUsable() {
        assertNull(resolvePlaylistCoverModel(null, null, null, null, localMissing))
        assertNull(resolvePlaylistCoverModel("/covers/pl.jpg", null, "/covers/s.jpg", null, localMissing))
    }
}

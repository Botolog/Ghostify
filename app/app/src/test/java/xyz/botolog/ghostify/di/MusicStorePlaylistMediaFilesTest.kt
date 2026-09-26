package xyz.botolog.ghostify.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.botolog.ghostify.file.FileNames
import xyz.botolog.ghostify.file.MusicStore
import java.io.File

/**
 * The media-file half of playlist deletion runs against the *current* storage root, not the
 * path that happened to be recorded at download time: changing the storage location in Settings
 * rewrites [MusicStore.rootDir] while `songs.file_path` may still point at the old location.
 */
class MusicStorePlaylistMediaFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun candidatePathsCoverTheRecordedPathAndTheCurrentStorageRoot() {
        val original = temp.newFolder("original")
        val store = MusicStore(root = original)
        val moved = temp.newFolder("moved")
        store.updateRoot(moved)
        val files = MusicStorePlaylistMediaFiles(store)

        val paths = files.candidatePaths("Artist", "Alpha", "/old/location/Artist - Alpha.mp3")

        assertEquals(
            listOf(
                File("/old/location/Artist - Alpha.mp3").absolutePath,
                File(moved, FileNames.trackFileName("Artist", "Alpha")).absolutePath,
            ),
            paths,
        )
    }

    @Test
    fun candidatePathsFollowSpotDlDeduplicationSuffixUnderTheCurrentRoot() {
        val original = temp.newFolder("original")
        val store = MusicStore(root = original)
        val moved = temp.newFolder("moved")
        store.updateRoot(moved)
        val deduped = File(moved, FileNames.trackFileName("Artist", "Alpha").replace(".mp3", " (1).mp3"))
        deduped.writeText("audio")
        val files = MusicStorePlaylistMediaFiles(store)

        val paths = files.candidatePaths("Artist", "Alpha", null)

        assertEquals(listOf(deduped.absolutePath), paths)
    }

    @Test
    fun candidatePathsIgnoreABlankRecordedPath() {
        val store = MusicStore(root = temp.newFolder("root"))
        val files = MusicStorePlaylistMediaFiles(store)

        assertEquals(
            listOf(File(store.rootDir, FileNames.trackFileName("Artist", "Alpha")).absolutePath),
            files.candidatePaths("Artist", "Alpha", "   "),
        )
    }

    @Test
    fun deleteRemovesTheMediaFileAndItsSidecar() {
        val root = temp.newFolder("root")
        val store = MusicStore(root = root)
        val mp3 = File(root, FileNames.trackFileName("Artist", "Alpha")).apply { writeText("audio") }
        val sidecar = File(mp3.absolutePath + MusicStore.SIDECAR_SUFFIX).apply { writeText("meta") }
        val files = MusicStorePlaylistMediaFiles(store)

        assertTrue(files.delete(mp3.absolutePath))

        assertFalse(mp3.exists())
        assertFalse(sidecar.exists())
    }

    @Test
    fun deleteIsSafeForAFileThatIsAlreadyGone() {
        val root = temp.newFolder("root")
        val store = MusicStore(root = root)
        val files = MusicStorePlaylistMediaFiles(store)
        val missing = File(root, FileNames.trackFileName("Artist", "Ghost"))

        assertTrue(files.delete(missing.absolutePath))
        assertTrue(files.delete(""))
    }

    @Test
    fun artworkCleanupIsSkippedWhenNoCacheIsWired() {
        val store = MusicStore(root = temp.newFolder("root"))

        MusicStorePlaylistMediaFiles(store).deleteArtwork("playlist-1", listOf("s1", "s2"))
    }
}

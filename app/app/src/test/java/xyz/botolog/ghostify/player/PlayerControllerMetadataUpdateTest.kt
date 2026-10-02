package xyz.botolog.ghostify.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.player.core.PlaybackStatus
import xyz.botolog.ghostify.player.core.PlayerQueueBuilder
import xyz.botolog.ghostify.player.core.QueueBuildResult
import xyz.botolog.ghostify.player.core.QueueItem
import xyz.botolog.ghostify.player.core.Song
import xyz.botolog.ghostify.player.core.SongStatus

/**
 * A saved title/artist/album has to show up in the player without the queue being rebuilt:
 * the metadata is mirrored onto the ExoPlayer timeline (so the notification and the
 * now-playing line follow) and onto the queue the queue editor renders, while playback of
 * the current track continues from the position it was at.
 */
class PlayerControllerMetadataUpdateTest {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var controller: PlayerController
    private lateinit var controllerScope: CoroutineScope
    private val uncaughtInSetup = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private lateinit var mediaItems: MutableList<MediaItem>
    private var currentIndex = 0
    private var playWhenReady = true

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        currentIndex = 0
        playWhenReady = true
        mediaItems = ids().map(::mediaItem).toMutableList()
        exoPlayer = mockk(relaxed = true)
        every { exoPlayer.currentMediaItem } answers { mediaItems.getOrNull(currentIndex) }
        every { exoPlayer.currentMediaItemIndex } answers { currentIndex }
        every { exoPlayer.getMediaItemAt(any()) } answers { mediaItems[firstArg()] }
        every { exoPlayer.currentPosition } returns 1_000L
        every { exoPlayer.isPlaying } answers { playWhenReady }
        every { exoPlayer.playWhenReady } answers { playWhenReady }
        every { exoPlayer.isLoading } returns false
        every { exoPlayer.mediaItemCount } answers { mediaItems.size }
        every { exoPlayer.playbackState } returns Player.STATE_READY
        every { exoPlayer.duration } returns 180_000L
        every { exoPlayer.mediaMetadata } answers {
            mediaItems.getOrNull(currentIndex)?.mediaMetadata ?: MediaItem.Builder().build().mediaMetadata
        }
        every { exoPlayer.repeatMode } returns Player.REPEAT_MODE_OFF
        every { exoPlayer.shuffleModeEnabled } returns false
        every { exoPlayer.volume } returns 0.8f
        every { exoPlayer.play() } answers { playWhenReady = true }
        every { exoPlayer.pause() } answers { playWhenReady = false }
        every { exoPlayer.replaceMediaItem(any<Int>(), any()) } answers {
            mediaItems[firstArg<Int>()] = secondArg()
        }
        every { exoPlayer.seekTo(any<Int>(), any()) } answers {
            currentIndex = firstArg()
        }

        val persistence = mockk<PlayerStatePersistence>(relaxed = true)
        coEvery { persistence.load() } returns null
        val queueBuilder = mockk<PlayerQueueBuilder>()
        every { queueBuilder.build(any(), any()) } answers {
            QueueBuildResult.Ready(
                items = ids().map(::queueItem),
                startIndex = 0,
                startSongId = secondArg<String?>(),
            )
        }
        uncaughtInSetup.clear()
        controllerScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e ->
                uncaughtInSetup.add(e)
            },
        )
        val constructor = PlayerController::class.java.declaredConstructors
            .first { it.parameterCount == 8 }
            .apply { isAccessible = true }
        controller = constructor.newInstance(
            mockk<Context>(relaxed = true),
            exoPlayer,
            queueBuilder,
            ArtworkExtractor { null },
            null,
            controllerScope,
            persistence,
            flowOf(true),
        ) as PlayerController
        controller.startPlaybackService = {}
        controller.playPlaylist(
            songs = ids().map(::queueItem).map(::song),
            startSongId = ids().first(),
            playlistId = PLAYLIST_ID,
        )
        controller.queueManager.setQueue(ids().map(::queueItem))
        verify(timeout = 2_000) { exoPlayer.setMediaItems(any<List<MediaItem>>(), any(), any()) }
        clearMocks(exoPlayer, answers = false)
        if (uncaughtInSetup.isNotEmpty()) {
            throw AssertionError(uncaughtInSetup.first())
        }
    }

    @After
    fun tearDown() {
        try {
            if (::controller.isInitialized) {
                controller.release()
            }
            if (::controllerScope.isInitialized) {
                controllerScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
        if (uncaughtInSetup.isNotEmpty()) {
            throw AssertionError(uncaughtInSetup.first())
        }
    }

    @Test
    fun theNowPlayingLinePicksUpTheSavedMetadata() {
        assertTrue(controller.updateQueueItemMetadata(CURRENT_ID, "Renamed", "New Artist", "New Album"))

        val current = controller.state.value.currentItem
        assertEquals(CURRENT_ID, current?.mediaId)
        assertEquals("Renamed", current?.title)
        assertEquals("New Artist", current?.artist)
        assertEquals("New Album", current?.album)
        assertEquals(PlaybackStatus.READY, controller.state.value.playbackStatus)
    }

    @Test
    fun theQueueEditorPicksUpTheSavedMetadata() {
        controller.updateQueueItemMetadata(CURRENT_ID, "Renamed", "New Artist", "New Album")

        val queued = controller.state.value.queue.first { it.songId == CURRENT_ID }
        assertEquals("Renamed", queued.title)
        assertEquals("New Artist", queued.artist)
    }

    @Test
    fun savingKeepsTheCurrentTrackPlayingFromTheSamePosition() {
        controller.updateQueueItemMetadata(CURRENT_ID, "Renamed", "New Artist", "New Album")

        verify(exactly = 1) { exoPlayer.seekTo(0, 1_000L) }
        verify(exactly = 1) { exoPlayer.play() }
        assertEquals(CURRENT_ID, controller.state.value.currentItem?.mediaId)
    }

    @Test
    fun savingASongThatIsNotPlayingDoesNotInterruptPlayback() {
        assertTrue(controller.updateQueueItemMetadata(OTHER_ID, "Renamed", "New Artist", null))

        verify(exactly = 0) { exoPlayer.seekTo(any<Int>(), any()) }
        assertEquals(CURRENT_ID, controller.state.value.currentItem?.mediaId)
        assertEquals("Renamed", controller.state.value.queue.first { it.songId == OTHER_ID }.title)
    }

    @Test
    fun theSavedMetadataSurvivesTurningShuffleOff() {
        controller.setShuffleEnabled(true)
        controller.updateQueueItemMetadata(CURRENT_ID, "Renamed", "New Artist", "New Album")

        controller.setShuffleEnabled(false)

        assertEquals("Renamed", controller.state.value.queue.first { it.songId == CURRENT_ID }.title)
    }

    @Test
    fun aSongThatIsNotQueuedIsReportedAsUnchanged() {
        assertFalse(controller.updateQueueItemMetadata("song-404", "Renamed", "New Artist", null))

        verify(exactly = 0) { exoPlayer.replaceMediaItem(any<Int>(), any()) }
        assertEquals(CURRENT_ID, controller.state.value.currentItem?.mediaId)
    }

    private fun ids(): List<String> = listOf("song-0", "song-1", "song-2")

    private fun mediaItem(id: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri("/music/$id.mp3")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(id)
                .setArtist("Artist")
                .setAlbumTitle("Album")
                .build(),
        )
        .build()

    private fun queueItem(id: String) = QueueItem(
        songId = id,
        title = id,
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        filePath = "/music/$id.mp3",
        indexInQueue = 0,
    )

    private fun song(item: QueueItem) = Song(
        id = item.songId,
        title = item.title.orEmpty(),
        artists = item.artist.orEmpty(),
        album = item.album.orEmpty(),
        durationMs = item.durationMs ?: 0L,
        filePath = item.filePath.orEmpty(),
        status = SongStatus.DOWNLOADED,
    )

    private companion object {
        const val PLAYLIST_ID = "playlist-1"
        const val CURRENT_ID = "song-0"
        const val OTHER_ID = "song-1"
    }
}

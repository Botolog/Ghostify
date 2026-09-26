package xyz.botolog.ghostify.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.player.core.PlaybackStatus
import xyz.botolog.ghostify.player.core.PlayerQueueBuilder
import xyz.botolog.ghostify.player.core.QueueBuildResult
import xyz.botolog.ghostify.player.core.QueueItem

/**
 * Deleting a playlist that is loaded must stop playback, empty the ExoPlayer timeline and the
 * queue, drop the now-playing item and forget the persisted state — otherwise the mini player
 * keeps showing a deleted song, the full player can still be opened and the notification goes
 * on playing a file that no longer exists.
 */
class PlayerControllerPlaylistDeletionTest {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var persistence: PlayerStatePersistence
    private lateinit var controller: PlayerController
    private lateinit var scope: CoroutineScope
    private lateinit var mediaItems: MutableList<MediaItem>
    private var currentIndex = 0
    private var playWhenReady = true
    private var cleared = false

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        mediaItems = mutableListOf()
        cleared = false
        currentIndex = 0
        playWhenReady = true
        exoPlayer = mockk(relaxed = true)
        every { exoPlayer.currentMediaItem } answers {
            if (cleared) null else mediaItems.getOrNull(currentIndex)
        }
        every { exoPlayer.currentMediaItemIndex } answers { currentIndex }
        every { exoPlayer.currentPosition } returns 1_000L
        every { exoPlayer.isPlaying } answers { playWhenReady }
        every { exoPlayer.playWhenReady } answers { playWhenReady }
        every { exoPlayer.isLoading } returns false
        every { exoPlayer.mediaItemCount } answers { if (cleared) 0 else mediaItems.size }
        every { exoPlayer.playbackState } answers {
            if (cleared) Player.STATE_IDLE else Player.STATE_READY
        }
        every { exoPlayer.duration } returns 180_000L
        every { exoPlayer.mediaMetadata } answers {
            mediaItems.getOrNull(currentIndex)?.mediaMetadata ?: MediaItem.Builder().build().mediaMetadata
        }
        every { exoPlayer.repeatMode } returns Player.REPEAT_MODE_OFF
        every { exoPlayer.shuffleModeEnabled } answers { false }
        every { exoPlayer.volume } returns 0.8f
        every { exoPlayer.play() } answers { playWhenReady = true }
        every { exoPlayer.pause() } answers { playWhenReady = false }
        every { exoPlayer.stop() } answers { cleared = true; playWhenReady = false }
        every { exoPlayer.clearMediaItems() } answers { cleared = true }
        every { exoPlayer.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) } answers {
            cleared = false
            mediaItems = firstArg()
            currentIndex = 0
        }

        persistence = mockk(relaxed = true)
        coEvery { persistence.load() } returns null
        val queueBuilder = mockk<PlayerQueueBuilder>()
        every { queueBuilder.build(any(), any()) } answers {
            QueueBuildResult.Ready(
                items = listOf(queueItem("song-0"), queueItem("song-1"), queueItem("song-2")),
                startIndex = 0,
                startSongId = firstArg<String?>(),
            )
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val constructor = PlayerController::class.java.declaredConstructors
            .first { it.parameterCount == 8 }
            .apply { isAccessible = true }
        controller = constructor.newInstance(
            mockk<Context>(relaxed = true),
            exoPlayer,
            queueBuilder,
            ArtworkExtractor { null },
            null,
            scope,
            persistence,
            flowOf(true),
        ) as PlayerController
        controller.startPlaybackService = {}
    }

    @After
    fun tearDown() {
        controller.release()
        Dispatchers.resetMain()
    }

    @Test
    fun deletingTheLoadedPlaylistStopsPlaybackAndClearsTheQueue() {
        loadQueue(PLAYLIST_ID)

        assertTrue(controller.stopPlaylist(PLAYLIST_ID))

        verify { exoPlayer.stop() }
        verify { exoPlayer.clearMediaItems() }
        assertTrue(controller.queueManager.queue.isEmpty())
        assertFalse(controller.queueManager.isShuffled)
        assertNull(controller.currentPlaylistId)
        assertEquals(PlaybackStatus.IDLE, controller.state.value.playbackStatus)
        assertNull(controller.state.value.currentItem)
        assertTrue(controller.state.value.queue.isEmpty())
        assertFalse(controller.state.value.hasQueue)
        assertEquals(-1, controller.state.value.currentQueueIndex)
        runBlocking { coVerify(exactly = 1) { persistence.clear() } }
    }

    @Test
    fun deletingTheLoadedPlaylistDropsUserQueuedAdditionsToo() {
        loadQueue(PLAYLIST_ID)
        controller.queueManager.addToQueueNext("queued-by-user", mediaItem("queued-by-user"), 0)
        assertEquals(4, controller.queueManager.size)

        controller.stopPlaylist(PLAYLIST_ID)

        assertTrue(controller.queueManager.queue.isEmpty())
    }

    @Test
    fun deletingTheLoadedPlaylistLeavesOtherPlaylistsPlayable() {
        loadQueue("other-playlist")

        assertFalse(controller.stopPlaylist(PLAYLIST_ID))

        verify(exactly = 0) { exoPlayer.stop() }
        verify(exactly = 0) { exoPlayer.clearMediaItems() }
        assertEquals("other-playlist", controller.currentPlaylistId)
        assertEquals(3, controller.queueManager.size)
        runBlocking { coVerify(exactly = 0) { persistence.clear() } }
    }

    @Test
    fun deletingTheLoadedPlaylistTwiceIsIdempotent() {
        loadQueue(PLAYLIST_ID)

        assertTrue(controller.stopPlaylist(PLAYLIST_ID))
        assertFalse(controller.stopPlaylist(PLAYLIST_ID))
        assertTrue(controller.stopPlaylist(null))
        assertTrue(controller.queueManager.queue.isEmpty())
        assertNull(controller.currentPlaylistId)
    }

    @Test
    fun clearingWithoutAPlaylistIdIsUnconditional() {
        loadQueue(PLAYLIST_ID)

        assertTrue(controller.stopPlaylist(null))

        assertTrue(controller.queueManager.queue.isEmpty())
        assertNull(controller.currentPlaylistId)
    }

    private fun loadQueue(playlistId: String) {
        val ids = listOf("song-0", "song-1", "song-2")
        controller.playPlaylist(
            songs = ids.map { song(it) },
            startSongId = ids.first(),
            playlistId = playlistId,
        )
        controller.queueManager.setQueue(ids.map(::queueItem))
        mediaItems = ids.map(::mediaItem).toMutableList()
        cleared = false
    }

    private fun mediaItem(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    private fun queueItem(id: String): QueueItem = QueueItem(
        songId = id,
        title = id,
        artist = "Artist",
        album = null,
        durationMs = 180_000L,
        filePath = "/music/$id.mp3",
        indexInQueue = 0,
    )

    private fun song(id: String) = xyz.botolog.ghostify.player.core.Song(
        id = id,
        title = id,
        artists = "Artist",
        album = "",
        durationMs = 180_000L,
        filePath = "/music/$id.mp3",
        status = xyz.botolog.ghostify.player.core.SongStatus.DOWNLOADED,
    )

    private companion object {
        const val PLAYLIST_ID = "playlist-1"
    }
}

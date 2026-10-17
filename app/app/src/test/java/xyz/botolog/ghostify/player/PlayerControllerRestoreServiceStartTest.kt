package xyz.botolog.ghostify.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import xyz.botolog.ghostify.player.core.PlayerQueueBuilder
import xyz.botolog.ghostify.player.core.QueueBuildResult
import xyz.botolog.ghostify.player.core.QueueItem
import xyz.botolog.ghostify.player.core.RepeatMode
import xyz.botolog.ghostify.player.core.Song
import xyz.botolog.ghostify.player.core.SongStatus

/**
 * Regression test for the cold-start foreground-service ANR
 * ("Context.startForegroundService() did not then call Service.startForeground()").
 *
 * Root cause: `PlayerController` used to call `Context.startForegroundService()` for
 * [xyz.botolog.ghostify.background.PlaybackService]. The fix is to start the service by
 * BINDING it through a [androidx.media3.session.MediaController] instead, so the
 * `startPlaybackService` seam (the controller connect) is what makes the notification appear.
 *
 * Because starting the service is now an ANR-safe bind, [PlayerController.restoreSavedState]
 * may (and must) invoke it on every restore — including a PAUSED restore — so the restored
 * queue/notification survive a relaunch and the user's next play / media-button resume works
 * immediately. Only autoplay is still gated on `wasPlaying`.
 */
class PlayerControllerRestoreServiceStartTest {

    private lateinit var player: ExoPlayer
    private lateinit var controller: PlayerController
    private lateinit var scope: CoroutineScope
    private val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private val playbackServiceStarts = AtomicInteger(0)
    private val playCount = AtomicInteger(0)
    private var mediaItems: MutableList<MediaItem> = mutableListOf()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        mediaItems = mutableListOf()
        playCount.set(0)
        playbackServiceStarts.set(0)
        player = mockk(relaxed = true)
        every { player.currentMediaItem } answers { mediaItems.getOrNull(0) }
        every { player.currentMediaItemIndex } returns 0
        every { player.currentPosition } returns 0L
        every { player.playbackState } returns Player.STATE_READY
        every { player.playWhenReady } returns false
        every { player.isPlaying } returns false
        every { player.isLoading } returns false
        every { player.mediaItemCount } answers { mediaItems.size }
        every { player.getMediaItemAt(any()) } answers { mediaItems[firstArg<Int>()] }
        every { player.duration } returns 180_000L
        every { player.mediaMetadata } returns mockk(relaxed = true)
        every { player.repeatMode } returns Player.REPEAT_MODE_OFF
        every { player.volume } returns 1f
        every { player.setMediaItems(any<List<MediaItem>>(), any(), any()) } answers {
            mediaItems = firstArg<List<MediaItem>>().toMutableList()
        }
        every { player.play() } answers { playCount.incrementAndGet() }
    }

    @After
    fun tearDown() {
        try {
            if (::controller.isInitialized) controller.release()
            if (::scope.isInitialized) scope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
        if (uncaught.isNotEmpty()) throw AssertionError(uncaught.first())
    }

    /**
     * Builds a controller whose `restoreSavedState()` will find [wasPlaying] in the saved
     * state. The [PlayerController.startPlaybackService] seam is installed immediately after
     * construction: the restore suspends on its first `withContext(Dispatchers.IO)` hop, so
     * the seam is in place before the restore reaches the service-start decision.
     */
    private fun buildController(wasPlaying: Boolean): PlayerController {
        uncaught.clear()
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e ->
                uncaught.add(e)
            },
        )
        val persistence = mockk<PlayerStatePersistence>(relaxed = true)
        coEvery { persistence.load() } returns PlayerStatePersistence.SavedState(
            currentSongId = "song-a",
            playlistId = "p1",
            queueSongIds = listOf("song-a", "song-b"),
            shuffleEnabled = false,
            repeatMode = RepeatMode.OFF,
            volume = 1f,
            positionMs = 12_345L,
            wasPlaying = wasPlaying,
        )
        coEvery { persistence.restoreSongs(any()) } returns listOf(song("song-a"), song("song-b"))
        val queueBuilder = mockk<PlayerQueueBuilder>()
        every { queueBuilder.build(any(), any()) } answers {
            QueueBuildResult.Ready(
                items = listOf(queueItem("song-a"), queueItem("song-b")),
                startIndex = 0,
                startSongId = secondArg<String?>(),
            )
        }
        val constructor = PlayerController::class.java.declaredConstructors
            .first { it.parameterCount == 8 }
            .apply { isAccessible = true }
        val c = constructor.newInstance(
            mockk<Context>(relaxed = true),
            player,
            queueBuilder,
            ArtworkExtractor { null },
            null,
            scope,
            persistence,
            flowOf(true),
        ) as PlayerController
        c.startPlaybackService = { playbackServiceStarts.incrementAndGet() }
        return c
    }

    /** Blocks until the restore has applied the rebuilt queue to the player. */
    private fun awaitRestoreApplied() {
        verify(timeout = 5_000) { player.setMediaItems(any<List<MediaItem>>(), any(), any()) }
    }

    private fun awaitCondition(what: String, timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for $what")
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun restorePaused_startsServiceBindingWithoutAutoplay() {
        controller = buildController(wasPlaying = false)

        awaitRestoreApplied()
        awaitCondition("service (controller) connect") { playbackServiceStarts.get() >= 1 }

        assertEquals("paused restore must not resume playback", 0, playCount.get())
        assertEquals(
            "paused restore must still connect the service so the notification is restored",
            1,
            playbackServiceStarts.get(),
        )
    }

    @Test
    fun restorePaused_stillRestoresQueueAndPosition() {
        controller = buildController(wasPlaying = false)

        awaitRestoreApplied()

        assertEquals(2, controller.queueManager.size)
        assertEquals(
            "clicked song must still be the restored start item",
            "song-a",
            controller.queueManager.queue.first().songId,
        )
        verify(timeout = 5_000) { player.seekTo(12_345L) }
    }

    @Test
    fun restoreWhilePlaying_startsServiceBindingAndAutoplays() {
        controller = buildController(wasPlaying = true)

        awaitRestoreApplied()
        awaitCondition("service (controller) connect") { playbackServiceStarts.get() >= 1 }

        assertEquals(1, playCount.get())
        assertEquals(1, playbackServiceStarts.get())
    }

    private fun queueItem(id: String) = QueueItem(
        songId = id,
        title = id,
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        filePath = "/music/$id.mp3",
        indexInQueue = 0,
    )

    private fun song(id: String) = Song(
        id = id,
        title = id,
        artists = "Artist",
        album = "Album",
        durationMs = 180_000L,
        filePath = "/music/$id.mp3",
        status = SongStatus.DOWNLOADED,
    )
}

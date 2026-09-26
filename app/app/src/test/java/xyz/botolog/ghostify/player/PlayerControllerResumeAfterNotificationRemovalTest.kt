package xyz.botolog.ghostify.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.player.core.PlayerQueueBuilder
import xyz.botolog.ghostify.player.core.QueueItem

/**
 * Regression tests for the "remove the media notification, then resume" failure.
 *
 * Dismissing the Media3 notification while paused leaves the shared player in
 * [Player.STATE_IDLE] (the service's foreground teardown stops it) while the queue
 * and position are still intact. `play()` on an idle player only flips
 * `playWhenReady`, so playback never restarted and the resume appeared to do nothing.
 */
class PlayerControllerResumeAfterNotificationRemovalTest {

    private lateinit var player: ExoPlayer
    private lateinit var controller: PlayerController
    private lateinit var scope: CoroutineScope
    private var mediaItems: MutableList<MediaItem> = mutableListOf()
    private var playbackState = Player.STATE_READY
    private var playWhenReady = true
    private var isPlaying = true
    private var position = 68_347L
    private var prepareCount = 0
    private var playCount = 0
    private var pauseCount = 0
    private var playbackServiceStarts = 0

    @Before
    fun setUp() {
        mediaItems = mutableListOf(
            MediaItem.Builder().setMediaId("song-a").build(),
            MediaItem.Builder().setMediaId("song-b").build(),
        )
        player = mockk(relaxed = true)
        every { player.currentMediaItem } answers { mediaItems.getOrNull(0) }
        every { player.currentMediaItemIndex } returns 0
        every { player.currentPosition } answers { position }
        every { player.playbackState } answers { playbackState }
        every { player.playWhenReady } answers { playWhenReady }
        every { player.isPlaying } answers { isPlaying }
        every { player.isLoading } returns false
        every { player.mediaItemCount } answers { mediaItems.size }
        every { player.getMediaItemAt(any()) } answers { mediaItems[firstArg<Int>()] }
        every { player.duration } returns 295_920L
        every { player.mediaMetadata } returns mockk(relaxed = true)
        every { player.repeatMode } returns Player.REPEAT_MODE_OFF
        every { player.volume } returns 1f
        every { player.prepare() } answers {
            prepareCount++
            playbackState = Player.STATE_READY
        }
        every { player.play() } answers {
            playCount++
            playWhenReady = true
        }
        every { player.pause() } answers {
            pauseCount++
            playWhenReady = false
            isPlaying = false
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val constructor = PlayerController::class.java.declaredConstructors
            .first { it.parameterCount == 8 }
            .apply { isAccessible = true }
        controller = constructor.newInstance(
            mockk<Context>(relaxed = true),
            player,
            mockk<PlayerQueueBuilder>(relaxed = true),
            ArtworkExtractor { null },
            null,
            scope,
            null,
            flowOf(true),
        ) as PlayerController
        controller.startPlaybackService = { playbackServiceStarts++ }
    }

    @After
    fun tearDown() {
        controller.release()
    }

    private fun simulatePausedPlayerAfterNotificationRemoval() {
        playbackState = Player.STATE_IDLE
        playWhenReady = false
        isPlaying = false
    }

    @Test
    fun play_rePreparesIdlePlayerLeftByNotificationRemoval() {
        simulatePausedPlayerAfterNotificationRemoval()

        controller.play()

        assertEquals("idle player must be prepared before it can play", 1, prepareCount)
        assertEquals(1, playCount)
        assertTrue(playWhenReady)
    }

    @Test
    fun togglePlayPause_resumesPausedPlayerAfterNotificationRemoval() {
        simulatePausedPlayerAfterNotificationRemoval()

        controller.togglePlayPause()

        assertEquals("resume must not be a silent no-op", 1, prepareCount)
        assertEquals(1, playCount)
        assertEquals(0, pauseCount)
        assertTrue(playWhenReady)
    }

    @Test
    fun resumeAfterNotificationRemoval_keepsQueueAndPosition() {
        controller.queueManager.setQueue(
            listOf(
                queueItem("song-a"),
                queueItem("song-b"),
            ),
        )
        simulatePausedPlayerAfterNotificationRemoval()

        controller.togglePlayPause()

        assertEquals(1, prepareCount)
        assertEquals(1, playCount)
        assertEquals(2, controller.queueManager.size)
        assertEquals("song-a", controller.queueManager.queue.first().songId)
        assertEquals(position, player.currentPosition)
    }

    @Test
    fun resumeFromIdle_reassertsForegroundServiceSoNotificationComesBack() {
        simulatePausedPlayerAfterNotificationRemoval()

        controller.play()

        assertEquals(
            "dismissed notification must be restored by re-entering the foreground",
            1,
            playbackServiceStarts,
        )
    }

    @Test
    fun play_onReadyPlayer_doesNotRedundantlyPrepare() {
        playbackState = Player.STATE_READY

        controller.play()

        assertEquals(0, prepareCount)
        assertEquals(1, playCount)
        assertEquals(0, playbackServiceStarts)
    }

    @Test
    fun togglePlayPause_whilePlaying_pausesWithoutPreparing() {
        playbackState = Player.STATE_READY
        playWhenReady = true
        isPlaying = true

        controller.togglePlayPause()

        assertEquals(0, prepareCount)
        assertEquals(1, pauseCount)
        assertEquals(0, playCount)
    }

    @Test
    fun play_withEmptyQueue_doesNotPrepareOrStartService() {
        mediaItems = mutableListOf()
        simulatePausedPlayerAfterNotificationRemoval()

        controller.play()

        assertEquals("nothing to play must stay idle", 0, prepareCount)
        assertEquals(0, playbackServiceStarts)
    }

    @Test
    fun repeatedResumeFromIdle_doesNotLoseQueue() {
        controller.queueManager.setQueue(listOf(queueItem("song-a"), queueItem("song-b")))
        repeat(3) {
            simulatePausedPlayerAfterNotificationRemoval()
            controller.togglePlayPause()
        }

        assertEquals(3, prepareCount)
        assertEquals(3, playCount)
        assertEquals(2, controller.queueManager.size)
        assertEquals(position, player.currentPosition)
    }

    private fun queueItem(id: String) = QueueItem(
        songId = id,
        title = null,
        artist = null,
        album = null,
        durationMs = 295_920L,
        filePath = "/$id.mp3",
        indexInQueue = 0,
        queuedByUser = false,
    )
}

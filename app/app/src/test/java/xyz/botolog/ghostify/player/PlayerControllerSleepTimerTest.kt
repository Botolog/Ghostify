package xyz.botolog.ghostify.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.player.core.PlayerQueueBuilder
import xyz.botolog.ghostify.player.core.QueueItem

/**
 * The sleep timer pauses playback when it runs out, and only then: it never stops the player
 * or throws the queue away, it goes back to inactive so the button returns to its idle icon,
 * and a cancelled timer is dead for good — including one cancelled between the moment its
 * ticker was queued and the moment that ticker ran.
 */
class PlayerControllerSleepTimerTest {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var controller: PlayerController
    private lateinit var scheduler: TestCoroutineScheduler

    private var playWhenReady = true
    private var mediaItems: MutableList<MediaItem> = mutableListOf()

    @Before
    fun setUp() {
        exoPlayer = mockk(relaxed = true)
        every { exoPlayer.currentMediaItem } answers { mediaItems.getOrNull(0) }
        every { exoPlayer.currentMediaItemIndex } returns 0
        every { exoPlayer.currentPosition } returns 0L
        every { exoPlayer.isPlaying } answers { playWhenReady }
        every { exoPlayer.playWhenReady } answers { playWhenReady }
        every { exoPlayer.isLoading } returns false
        every { exoPlayer.mediaItemCount } answers { mediaItems.size }
        every { exoPlayer.playbackState } returns Player.STATE_READY
        every { exoPlayer.duration } returns 180_000L
        every { exoPlayer.mediaMetadata } answers { MediaItem.Builder().build().mediaMetadata }
        every { exoPlayer.repeatMode } returns Player.REPEAT_MODE_OFF
        every { exoPlayer.shuffleModeEnabled } returns false
        every { exoPlayer.volume } returns 0.8f
        every { exoPlayer.play() } answers { playWhenReady = true }
        every { exoPlayer.pause() } answers { playWhenReady = false }
        mediaItems = mutableListOf(MediaItem.Builder().setMediaId("song-0").build())

        scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val constructor = PlayerController::class.java.declaredConstructors
            .first { it.parameterCount == 8 }
            .apply { isAccessible = true }
        controller = constructor.newInstance(
            mockk<Context>(relaxed = true),
            exoPlayer,
            mockk<PlayerQueueBuilder>(relaxed = true),
            ArtworkExtractor { null },
            null,
            scope,
            null,
            flowOf(true),
        ) as PlayerController
        controller.startPlaybackService = {}
        controller.elapsedRealtimeMs = { scheduler.currentTime }
        scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        controller.release()
    }

    @Test
    fun startingATimerPublishesItWithTheWholeDurationLeft() {
        controller.startSleepTimer(TEN_MINUTES_MS)

        val state = controller.state.value
        assertTrue(state.sleepTimerActive)
        assertEquals(TEN_MINUTES_MS, state.sleepTimerRemainingMs)
        assertEquals(TEN_MINUTES_MS, state.sleepTimerTotalMs)
        verify(exactly = 0) { exoPlayer.pause() }
    }

    @Test
    fun theCountdownFollowsTheClock() {
        controller.startSleepTimer(TEN_MINUTES_MS)

        scheduler.advanceTimeBy(150_001L)
        scheduler.runCurrent()

        val state = controller.state.value
        assertTrue(state.sleepTimerActive)
        assertEquals(450_000L, state.sleepTimerRemainingMs)
        assertEquals(TEN_MINUTES_MS, state.sleepTimerTotalMs)
        assertTrue(playWhenReady)
    }

    @Test
    fun theTimerStillExpiresWhenNothingTickedForTheWholeDuration() {
        controller.startSleepTimer(TEN_MINUTES_MS)

        // The process was frozen for longer than the timer: the countdown is derived from
        // the deadline, so one tick after the gap is enough to notice it is over.
        scheduler.advanceTimeBy(TEN_MINUTES_MS + 1L)
        scheduler.runCurrent()

        assertFalse(playWhenReady)
        assertFalse(controller.state.value.sleepTimerActive)
        assertEquals(0L, controller.state.value.sleepTimerRemainingMs)
    }

    @Test
    fun anExpiredTimerPausesPlaybackButKeepsTheQueue() {
        controller.queueManager.setQueue((0 until 3).map { queueItem("song-$it") })
        controller.startSleepTimer(TEN_MINUTES_MS)

        scheduler.advanceTimeBy(TEN_MINUTES_MS + 1L)
        scheduler.runCurrent()

        verify(exactly = 1) { exoPlayer.pause() }
        verify(exactly = 0) { exoPlayer.stop() }
        verify(exactly = 0) { exoPlayer.clearMediaItems() }
        assertEquals(3, controller.queueManager.queue.size)
    }

    @Test
    fun anExpiredTimerGoesBackToInactive() {
        controller.startSleepTimer(TEN_MINUTES_MS)

        scheduler.advanceTimeBy(TEN_MINUTES_MS + 1L)
        scheduler.runCurrent()

        val state = controller.state.value
        assertFalse(state.sleepTimerActive)
        assertEquals(0L, state.sleepTimerRemainingMs)
        assertEquals(0L, state.sleepTimerTotalMs)
    }

    @Test
    fun cancellingStopsTheTimerAndLeavesPlaybackAlone() {
        controller.startSleepTimer(TEN_MINUTES_MS)

        assertTrue(controller.cancelSleepTimer())

        val state = controller.state.value
        assertFalse(state.sleepTimerActive)
        assertEquals(0L, state.sleepTimerRemainingMs)
        assertTrue(playWhenReady)
    }

    @Test
    fun aCancelledTimerNeverPausesPlayback() {
        controller.startSleepTimer(TEN_MINUTES_MS)
        controller.cancelSleepTimer()

        scheduler.advanceTimeBy(TEN_MINUTES_MS * 2)
        scheduler.runCurrent()

        verify(exactly = 0) { exoPlayer.pause() }
        assertTrue(playWhenReady)
        assertFalse(controller.state.value.sleepTimerActive)
    }

    @Test
    fun cancellingWithNoTimerRunningChangesNothing() {
        assertFalse(controller.cancelSleepTimer())

        scheduler.advanceTimeBy(60_000L)
        scheduler.runCurrent()

        verify(exactly = 0) { exoPlayer.pause() }
        assertFalse(controller.state.value.sleepTimerActive)
    }

    @Test
    fun startingASecondTimerReplacesTheFirstOne() {
        controller.startSleepTimer(TEN_MINUTES_MS)
        scheduler.advanceTimeBy(300_001L)
        scheduler.runCurrent()

        controller.startSleepTimer(ONE_MINUTE_MS)

        val state = controller.state.value
        assertEquals(ONE_MINUTE_MS, state.sleepTimerTotalMs)
        assertEquals(ONE_MINUTE_MS, state.sleepTimerRemainingMs)

        scheduler.advanceTimeBy(ONE_MINUTE_MS + 1L)
        scheduler.runCurrent()

        assertFalse(playWhenReady)
        verify(exactly = 1) { exoPlayer.pause() }
    }

    @Test
    fun aDurationUnderASecondIsRejectedAndCancelsInstead() {
        controller.startSleepTimer(TEN_MINUTES_MS)

        controller.startSleepTimer(999L)

        val state = controller.state.value
        assertFalse(state.sleepTimerActive)
        assertEquals(0L, state.sleepTimerRemainingMs)

        scheduler.advanceTimeBy(TEN_MINUTES_MS * 2)
        scheduler.runCurrent()

        verify(exactly = 0) { exoPlayer.pause() }
    }

    @Test
    fun aReleasedControllerLeavesNothingRunning() {
        controller.startSleepTimer(TEN_MINUTES_MS)
        controller.release()

        scheduler.advanceTimeBy(TEN_MINUTES_MS * 2)
        scheduler.runCurrent()

        verify(exactly = 0) { exoPlayer.pause() }
        assertTrue(playWhenReady)
    }

    private fun queueItem(songId: String) = QueueItem(
        songId = songId,
        title = songId,
        artist = "artist",
        album = "album",
        durationMs = 180_000L,
        filePath = "/$songId.mp3",
        indexInQueue = 0,
    )

    private companion object {
        const val ONE_MINUTE_MS = 60_000L
        const val TEN_MINUTES_MS = 600_000L
    }
}

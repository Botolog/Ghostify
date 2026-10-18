package xyz.botolog.ghostify.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.player.core.CurrentItem
import xyz.botolog.ghostify.player.core.PlayerUiState
import xyz.botolog.ghostify.player.core.QueueItem

/**
 * JVM tests for the snapshot additions the three new widgets read: the sleep-timer fields,
 * the shuffle flag, and the text-only "Next up" queue peek. Pure; no Android types.
 */
class WidgetCreativeSnapshotTest {

    private fun queueItem(id: String, index: Int, title: String = "T$id", artist: String = "A$id") =
        QueueItem(
            songId = id,
            title = title,
            artist = artist,
            album = "Alb",
            durationMs = 65_000L,
            filePath = "/tmp/$id.flac",
            indexInQueue = index,
        )

    private fun state(
        queue: List<QueueItem> = emptyList(),
        currentQueueIndex: Int = -1,
        sleepTimerActive: Boolean = false,
        sleepTimerRemainingMs: Long = 0L,
        sleepTimerTotalMs: Long = 0L,
        shuffleEnabled: Boolean = false,
    ) = PlayerUiState(
        isPlaying = true,
        positionMs = 1_000L,
        durationMs = 60_000L,
        currentItem = CurrentItem(
            mediaId = "song-1",
            title = "Title",
            artist = "Artist",
            album = "Album",
            artworkBytes = null,
        ),
        hasQueue = queue.isNotEmpty(),
        currentQueueIndex = currentQueueIndex,
        queue = queue,
        shuffleEnabled = shuffleEnabled,
        sleepTimerActive = sleepTimerActive,
        sleepTimerRemainingMs = sleepTimerRemainingMs,
        sleepTimerTotalMs = sleepTimerTotalMs,
    )

    // ── nextUpFromQueue ──────────────────────────────────────────────────

    @Test
    fun nextUpSkipsTheCurrentTrack() {
        val snapshot = snapshotFromPlayerState(
            state(queue = (1..5).map { queueItem("$it", it - 1) }, currentQueueIndex = 1),
        )
        assertEquals(listOf("T3", "T4", "T5"), snapshot.nextUp.map { it.title })
        assertEquals(5, snapshot.queueCount)
        assertEquals(1, snapshot.currentQueueIndex)
    }

    @Test
    fun nextUpTreatsNothingCurrentAsTheQueueStart() {
        val snapshot = snapshotFromPlayerState(
            state(queue = (1..3).map { queueItem("$it", it - 1) }, currentQueueIndex = -1),
        )
        assertEquals(listOf("T1", "T2", "T3"), snapshot.nextUp.map { it.title })
    }

    @Test
    fun nextUpIsCapped() {
        val snapshot = snapshotFromPlayerState(
            state(queue = (1..20).map { queueItem("$it", it - 1) }, currentQueueIndex = 0),
        )
        assertEquals(NEXT_UP_CAP, snapshot.nextUp.size)
        // 20 total - 1 current - 6 shown = 13 hidden.
        assertEquals(13, snapshot.hiddenNextUpCount())
    }

    @Test
    fun hiddenNextUpCountIsNeverNegative() {
        val snapshot = snapshotFromPlayerState(
            state(queue = listOf(queueItem("s1", 0)), currentQueueIndex = 0),
        )
        assertTrue(snapshot.nextUp.isEmpty())
        assertEquals(0, snapshot.hiddenNextUpCount())
    }

    @Test
    fun nextUpCarriesTextOnly() {
        val snapshot = snapshotFromPlayerState(
            state(queue = listOf(queueItem("1", 0), queueItem("2", 1)), currentQueueIndex = 0),
        )
        val item = snapshot.nextUp.single()
        assertEquals("T2", item.title)
        assertEquals("A2", item.artist)
        assertEquals(65_000L, item.durationMs)
        assertEquals(1, item.indexInQueue)
    }

    // ── nextUpQueueIds (cold path) ───────────────────────────────────────

    @Test
    fun nextUpQueueIdsStartAfterTheCurrentSong() {
        assertEquals(
            listOf("b", "c", "d"),
            nextUpQueueIds(listOf("a", "b", "c", "d", "e"), "a", cap = 3),
        )
    }

    @Test
    fun nextUpQueueIdsFallBackToTheFrontWhenCurrentIsMissing() {
        assertEquals(listOf("a", "b"), nextUpQueueIds(listOf("a", "b", "c"), "zzz", cap = 2))
        assertEquals(listOf("a", "b"), nextUpQueueIds(listOf("a", "b", "c"), null, cap = 2))
    }

    @Test
    fun nextUpQueueIdsHandleTheLastItemAndEmptyQueue() {
        assertTrue(nextUpQueueIds(listOf("a", "b"), "b").isEmpty())
        assertTrue(nextUpQueueIds(emptyList(), "a").isEmpty())
        assertTrue(nextUpQueueIds(listOf("a"), "a", cap = 0).isEmpty())
    }

    // ── sleep timer ──────────────────────────────────────────────────────

    @Test
    fun snapshotMapsSleepTimerFields() {
        val snapshot = snapshotFromPlayerState(
            state(sleepTimerActive = true, sleepTimerRemainingMs = 30_000L, sleepTimerTotalMs = 60_000L),
        )
        assertTrue(snapshot.sleepTimerActive)
        assertEquals(30_000L, snapshot.sleepTimerRemainingMs)
        assertEquals(60_000L, snapshot.sleepTimerTotalMs)
        assertEquals(0.5f, snapshot.sleepFraction(), 0.0001f)
    }

    @Test
    fun sleepFractionIsZeroWhenInactiveOrUnknownDuration() {
        assertEquals(0f, WidgetSnapshot.EMPTY.sleepFraction(), 0f)
        assertEquals(
            0f,
            snapshotFromPlayerState(state(sleepTimerActive = true, sleepTimerTotalMs = 0L)).sleepFraction(),
            0f,
        )
    }

    @Test
    fun sleepFractionClampsToUnitRange() {
        val snapshot = snapshotFromPlayerState(
            state(sleepTimerActive = true, sleepTimerRemainingMs = 90_000L, sleepTimerTotalMs = 60_000L),
        )
        assertEquals(1f, snapshot.sleepFraction(), 0f)
    }

    // ── shuffle + keys ───────────────────────────────────────────────────

    @Test
    fun snapshotMapsShuffleFlag() {
        assertTrue(snapshotFromPlayerState(state(shuffleEnabled = true)).shuffleEnabled)
        assertFalse(snapshotFromPlayerState(state(shuffleEnabled = false)).shuffleEnabled)
        assertFalse(WidgetSnapshot.EMPTY.shuffleEnabled)
    }

    @Test
    fun updateKeyTracksSleepShuffleAndQueue() {
        val base = snapshotFromPlayerState(state(queue = listOf(queueItem("s1", 0)), currentQueueIndex = 0))
        assertNotEquals(
            base.updateKey(),
            snapshotFromPlayerState(
                state(
                    queue = listOf(queueItem("s1", 0)),
                    currentQueueIndex = 0,
                    sleepTimerActive = true,
                    sleepTimerRemainingMs = 30_000L,
                    sleepTimerTotalMs = 60_000L,
                ),
            ).updateKey(),
        )
        assertNotEquals(
            base.updateKey(),
            snapshotFromPlayerState(
                state(queue = listOf(queueItem("s1", 0)), currentQueueIndex = 0, shuffleEnabled = true),
            ).updateKey(),
        )
    }

    @Test
    fun updateKeyTracksTheSleepCountdownSecond() {
        val a = snapshotFromPlayerState(
            state(sleepTimerActive = true, sleepTimerRemainingMs = 30_000L, sleepTimerTotalMs = 60_000L),
        )
        // 30.6s truncates to the same whole second as 30.0s: still throttled.
        val b = snapshotFromPlayerState(
            state(sleepTimerActive = true, sleepTimerRemainingMs = 30_600L, sleepTimerTotalMs = 60_000L),
        )
        val c = snapshotFromPlayerState(
            state(sleepTimerActive = true, sleepTimerRemainingMs = 29_000L, sleepTimerTotalMs = 60_000L),
        )
        assertEquals("sub-second countdown ticks are throttled", a.updateKey(), b.updateKey())
        assertNotEquals("a whole second of countdown repaints", b.updateKey(), c.updateKey())
    }

    @Test
    fun immediateKeyTracksSleepAndShuffle() {
        val base = snapshotFromPlayerState(state())
        assertNotEquals(
            base.immediateKey(),
            snapshotFromPlayerState(state(sleepTimerActive = true, sleepTimerTotalMs = 60_000L)).immediateKey(),
        )
        assertNotEquals(
            base.immediateKey(),
            snapshotFromPlayerState(state(shuffleEnabled = true)).immediateKey(),
        )
        // A pure position move must still NOT repaint immediately.
        assertEquals(
            base.immediateKey(),
            snapshotFromPlayerState(state(queue = emptyList(), currentQueueIndex = -1)).immediateKey(),
        )
    }
}

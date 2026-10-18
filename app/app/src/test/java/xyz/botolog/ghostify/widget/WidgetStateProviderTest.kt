package xyz.botolog.ghostify.widget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the widget's live-snapshot holder.
 *
 * The home-screen widgets render by observing [WidgetStateProvider.snapshot] *inside*
 * their composition, because Glance's `update`/`updateAll` do not restart `provideGlance`
 * while a session is alive. These tests pin that contract: whatever the coordinator
 * publishes must be exactly what the composition reads, and a process that has never
 * published must expose `null` (so the widget falls back to persisted state).
 */
class WidgetStateProviderTest {

    @After
    fun tearDown() {
        WidgetStateProvider.clear()
    }

    private fun snapshot(songId: String) = WidgetSnapshot(
        songId = songId,
        title = "Title",
        artist = "Artist",
        album = "Album",
        isPlaying = true,
        positionMs = 1_000L,
        durationMs = 60_000L,
        hasQueue = true,
    )

    @Test
    fun publishedSnapshotIsWhatTheCompositionObserves() {
        val snapshot = snapshot("song-1")

        WidgetStateProvider.publish(snapshot)

        assertEquals(snapshot, WidgetStateProvider.snapshot.value)
        assertEquals(snapshot, WidgetStateProvider.liveSnapshot)
    }

    @Test
    fun latestPublishWinsSoRepaintsNeverRenderStaleData() {
        WidgetStateProvider.publish(snapshot("song-1"))
        val second = snapshot("song-2")

        WidgetStateProvider.publish(second)

        assertEquals(second, WidgetStateProvider.snapshot.value)
    }

    @Test
    fun coldProcessHasNoLiveSnapshot() {
        assertNull(WidgetStateProvider.snapshot.value)
        assertNull(WidgetStateProvider.liveSnapshot)
    }
}

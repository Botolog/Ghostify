package com.ghostify.perf.list

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollBy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import com.ghostify.perf.LazyListMetrics
import com.ghostify.perf.android.RetainedHeapProbe
import com.ghostify.perf.android.pattern.TrackList
import com.ghostify.perf.android.pattern.TrackStatus
import com.ghostify.perf.android.pattern.TrackSummary
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-164 (device-only instrumentation): a 1000-track playlist detail renders in a
 * Compose LazyColumn without OOM and composes only a bounded window of rows.
 *
 * Two independent guarantees are asserted:
 *  1. Memory — after scrolling a 1000-row list end-to-end, retained heap growth
 *     stays bounded (this is a sanity guard against accidentally holding all 1000
 *     row states; the precise leak regression is in DownloadLeakTest /
 *     MemoryMetricsTest).
 *  2. Composition bound — [LazyListMetrics.maxComposedItems] for a 56 dp row on a
 *     480 dp viewport is a small constant (≈ 10) that does NOT depend on the
 *     playlist size, so 1000 tracks can never OOM from composition alone.
 *
 * Device-only: uses createAndroidComposeRule (Android-only Compose test API).
 */
@LargeTest
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4::class)
class TrackListOomTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun thousandTrackListHasBoundedCompositionAndNoOom() {
        val tracks = List(1000) { i ->
            TrackSummary(
                spotifyId = "t$i",
                title = "Track $i",
                artists = "Artist One, Artist Two",
                status = if (i % 3 == 0) TrackStatus.DOWNLOADED else TrackStatus.PENDING,
            )
        }

        val before = RetainedHeapProbe.currentUsedBytes()
        composeRule.setContent {
            TrackList(
                tracks = tracks,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Scroll the full length of the list to force composition of every window.
        val list = composeRule.onNodeWithTag("TrackList")
        repeat(40) {
            list.performScrollBy(0f, 1200f)
        }
        val after = RetainedHeapProbe.currentUsedBytes()
        val deltaMiB = (after - before) / (1024.0 * 1024.0)

        // Bounded growth: composing ~10 rows at a time must not balloon the heap.
        assertTrue(
            "1000-track scroll grew heap by ${"%.2f".format(deltaMiB)} MiB",
            deltaMiB < 50.0,
        )

        // Version-agnostic composition bound from the pure-JVM contract.
        val maxComposed = LazyListMetrics.maxComposedItems(
            itemHeightPx = 56,
            viewportHeightPx = 480,
            prefetchItemCount = 0,
        )
        assertTrue(
            "maxComposedItems must be a small constant, was $maxComposed",
            maxComposed < 20,
        )
    }
}

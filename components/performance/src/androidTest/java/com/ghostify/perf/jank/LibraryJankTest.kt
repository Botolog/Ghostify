package com.ghostify.perf.jank

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollBy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import com.ghostify.perf.FrameTiming
import com.ghostify.perf.android.FrameStatsCollector
import com.ghostify.perf.android.pattern.PlaylistList
import com.ghostify.perf.android.pattern.PlaylistSummary
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-163 (device-only instrumentation): the Library screen renders 100 playlists
 * without jank — every frame lands inside the 60 Hz budget (< 16 ms).
 *
 * Frames are captured with androidx's [FrameStatsCollector] (FrameMetricsAggregator)
 * while the Compose [PlaylistList] is scrolled, then classified by
 * [FrameTiming.summarize](...).passesBudget (p95 ≤ 16 ms).
 *
 * The jank *math* (percentiles + budget predicate) is unit-tested in
 * `FrameTimingTest`; this test exercises the real Compose list + frame pipeline on
 * a device only.
 */
@LargeTest
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4::class)
class LibraryJankTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun libraryRenders100PlaylistsWithinBudget() {
        val playlists = List(100) { i ->
            PlaylistSummary(
                id = "p$i",
                name = "Playlist $i",
                owner = "spotify.user",
                trackCount = 110 + (i % 40),
            )
        }

        val collector = FrameStatsCollector(composeRule.activity)
        collector.start()
        composeRule.setContent {
            PlaylistList(
                playlists = playlists,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Drive the list end-to-end a few times so FrameMetricsAggregator captures
        // a healthy sample of real scroll frames.
        val list = composeRule.onNodeWithTag("PlaylistList")
        repeat(12) {
            list.performScrollBy(0f, 600f)
        }
        collector.close()

        val stats = collector.stats()
        assertTrue(
            "Library list exceeded the 16 ms/frame jank budget: $stats",
            stats.passesBudget,
        )
    }
}

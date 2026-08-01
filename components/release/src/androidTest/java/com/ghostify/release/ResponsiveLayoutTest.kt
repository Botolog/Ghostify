package com.ghostify.release

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toPx
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Automated slice of T-177 (small 360dp screens / large tablets — no clipped or
 * overlapping UI).
 *
 * Device-only (instrumentation). Renders the reference Library screen inside a
 * fixed-size viewport at the two canonical widths and asserts that:
 *  - the viewport is actually measured at the requested width, and
 *  - the key interactive node (FAB) stays fully inside the viewport bounds
 *    (no horizontal overflow off a narrow screen, no vertical clipping).
 *
 * This catches gross overflow regressions in CI; T-177 remains a manual gate
 * for pixel-perfect overlap on real devices/emulators of both form factors.
 */
@RunWith(AndroidJUnit4::class)
class ResponsiveLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun noOverflowAt360dpPhoneWidth() = assertNoOverflow(width = 360.dp, visibleItem = "Playlist 1")

    @Test
    fun noOverflowAtTabletWidth() = assertNoOverflow(width = 1280.dp, visibleItem = "Playlist 6")

    private fun assertNoOverflow(width: Dp, visibleItem: String) {
        composeRule.setContent {
            GhostifyTheme(dynamicColor = false) {
                Box(
                    modifier = Modifier
                        .testTag("viewport")
                        .requiredSize(width, 640.dp),
                ) {
                    ReferenceScreen()
                }
            }
        }
        composeRule.waitForIdle()

        val viewport = composeRule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val density = composeRule.density
        assertEquals(
            "viewport not measured at requested width",
            with(density) { width.toPx() },
            viewport.width,
            1f,
        )

        composeRule.onNodeWithText(visibleItem).assertIsDisplayed()

        val fab = composeRule.onNodeWithTag("add_playlist_fab").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "FAB overflows viewport horizontally (clipped/off-screen at $width)",
            fab.left >= viewport.left - 0.5f && fab.right <= viewport.right + 0.5f,
        )
        assertTrue(
            "FAB clipped vertically at $width",
            fab.top >= viewport.top - 0.5f && fab.bottom <= viewport.bottom + 0.5f,
        )
    }
}

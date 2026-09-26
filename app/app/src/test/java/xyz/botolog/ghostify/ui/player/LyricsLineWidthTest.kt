package xyz.botolog.ghostify.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsLineWidthTest {

    private val available = 320.dp

    private fun width(emphasis: Float, availableWidth: Dp = available, fontScale: Float = 1f): Float =
        requireNotNull(lyricsLineWidthDp(availableWidth, fontScale, emphasis)).value

    private fun fontSizeDp(emphasis: Float, fontScale: Float = 1f): Float =
        lyricsLineFontSizeSp(emphasis).value * fontScale

    @Test
    fun theActiveLineUsesTheAvailableWidthWhileIdleLinesAreInset() {
        val idle = width(0f)
        val active = width(1f)

        assertTrue("the active line should use the viewport, not a slice of it", active > available.value * 0.9f)
        assertTrue("an idle line should be inset", idle < available.value * 0.8f)
        assertTrue("no line may be wider than the viewport", active <= available.value)
    }

    @Test
    fun everyEmphasisKeepsTheSameEmsPerLine() {
        val budget = lyricsLineEmBudget(available, fontScale = 1f)
        var emphasis = 0f

        while (emphasis <= 1f) {
            assertEquals(
                "emphasis $emphasis",
                budget,
                width(emphasis) / fontSizeDp(emphasis),
                0.001f,
            )
            emphasis += 0.05f
        }
    }

    @Test
    fun theWidthOnlyGrowsAsTheEmphasisGrows() {
        var emphasis = 0f
        var previous = 0f

        while (emphasis <= 1f) {
            val current = width(emphasis)

            assertTrue("emphasis $emphasis shrank the line", current >= previous)
            previous = current
            emphasis += 0.05f
        }
    }

    @Test
    fun anEmphasisOutsideTheAnimatedRangeStaysInsideTheViewport() {
        var emphasis = -1f

        while (emphasis <= 2f) {
            val lineWidth = width(emphasis)

            assertTrue("emphasis $emphasis gave $lineWidth", lineWidth > 0f)
            assertTrue("emphasis $emphasis gave $lineWidth", lineWidth <= available.value)
            emphasis += 0.1f
        }
    }

    @Test
    fun aLargerSystemFontKeepsTheWidthAndShrinksTheEmBudget() {
        listOf(1f, 1.5f, 2f).forEach { fontScale ->
            val budget = lyricsLineEmBudget(available, fontScale)

            assertEquals(
                "font scale $fontScale",
                lyricsLineEmBudget(available, 1f) / fontScale,
                budget,
                0.001f,
            )
            listOf(0f, 0.5f, 1f).forEach { emphasis ->
                assertEquals(
                    "font scale $fontScale, emphasis $emphasis",
                    width(emphasis, fontScale = 1f),
                    width(emphasis, fontScale = fontScale),
                    0.001f,
                )
                assertTrue(
                    "font scale $fontScale, emphasis $emphasis",
                    width(emphasis, fontScale = fontScale) <= available.value,
                )
            }
        }
    }

    @Test
    fun aVeryNarrowViewportKeepsTheSameProportions() {
        val narrow = 72.dp

        assertTrue(width(1f, availableWidth = narrow) > width(0f, availableWidth = narrow))
        assertTrue(width(1f, availableWidth = narrow) <= narrow.value)
        listOf(0f, 0.25f, 0.5f, 1f).forEach { emphasis ->
            assertEquals(
                "emphasis $emphasis",
                width(emphasis) / available.value,
                width(emphasis, availableWidth = narrow) / narrow.value,
                0.001f,
            )
            assertEquals(
                "emphasis $emphasis",
                lyricsLineEmBudget(narrow, 1f),
                width(emphasis, availableWidth = narrow) / fontSizeDp(emphasis),
                0.001f,
            )
        }
    }

    @Test
    fun aViewportWithoutAWidthLeavesTheLineToFill() {
        listOf(0.dp, Dp.Unspecified, Dp.Infinity).forEach { viewport ->
            assertNull(lyricsLineWidthDp(viewport, fontScale = 1f, emphasis = 0f))
            assertNull(lyricsLineWidthDp(viewport, fontScale = 1f, emphasis = 1f))
        }
        assertNull(lyricsLineWidthDp(available, fontScale = 0f, emphasis = 1f))
        assertNull(lyricsLineWidthDp(available, fontScale = -1f, emphasis = 1f))
        assertNull(lyricsLineWidthDp(available, fontScale = Float.NaN, emphasis = 1f))
    }
}

package xyz.botolog.ghostify.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsEmphasisTest {

    private val idle = Color(0x80FF0000)
    private val active = Color(0xFF0000FF)

    @Test
    fun onlyTheActiveLineIsAnimatedTowardsFullEmphasis() {
        assertEquals(0f, lyricsEmphasisTarget(isCurrent = false), 0f)
        assertEquals(1f, lyricsEmphasisTarget(isCurrent = true), 0f)
    }

    @Test
    fun fontSizeGrowsFromIdleToActive() {
        assertEquals(18f, lyricsLineFontSizeSp(0f).value, 0.01f)
        assertEquals(21f, lyricsLineFontSizeSp(0.5f).value, 0.01f)
        assertEquals(24f, lyricsLineFontSizeSp(1f).value, 0.01f)
    }

    @Test
    fun fontSizeKeepsTheOriginalScale() {
        assertEquals(18f.sp, lyricsLineFontSizeSp(0f))
        assertEquals(24f.sp, lyricsLineFontSizeSp(1f))
    }

    @Test
    fun fontWeightGoesFromRegularToBold() {
        assertEquals(FontWeight.Normal, lyricsLineFontWeight(0f))
        assertEquals(FontWeight(550), lyricsLineFontWeight(0.5f))
        assertEquals(FontWeight.Bold, lyricsLineFontWeight(1f))
    }

    @Test
    fun emphasisOutsideTheAnimatedRangeStaysOnAnEndpoint() {
        assertEquals(18f.sp, lyricsLineFontSizeSp(-1f))
        assertEquals(24f.sp, lyricsLineFontSizeSp(2f))
        assertEquals(FontWeight.Normal, lyricsLineFontWeight(-1f))
        assertEquals(FontWeight.Bold, lyricsLineFontWeight(2f))
    }

    @Test
    fun colorKeepsTheIdleAndActiveEnds() {
        assertEquals(idle, lyricsLineColor(idle = idle, active = active, emphasis = 0f))
        assertEquals(active, lyricsLineColor(idle = idle, active = active, emphasis = 1f))
    }

    @Test
    fun colorFadesInBetweenTheEnds() {
        val midpoint = lyricsLineColor(idle = idle, active = active, emphasis = 0.5f)

        assertEquals(0.75f, midpoint.alpha, 0.01f)
        assertEquals(idle.alpha, lyricsLineColor(idle, active, 0f).alpha, 0.01f)
    }
}

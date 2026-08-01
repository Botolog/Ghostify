package com.ghostify.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatTest {

    @Test
    fun `zero and negative render as 0_00`() {
        assertEquals("0:00", DurationFormat.format(0))
        assertEquals("0:00", DurationFormat.format(-500))
    }

    @Test
    fun `minutes and seconds render as m_ss`() {
        assertEquals("3:45", DurationFormat.format(3 * 60_000 + 45_000))
        assertEquals("0:07", DurationFormat.format(7_000))
    }

    @Test
    fun `seconds are zero padded`() {
        assertEquals("1:05", DurationFormat.format(65_000))
    }

    @Test
    fun `one hour or more renders as h_mm_ss`() {
        assertEquals("1:02:03", DurationFormat.format(3_600_000 + 2 * 60_000 + 3_000))
    }

    @Test
    fun `sub-second durations round down without crash`() {
        assertEquals("0:00", DurationFormat.format(999))
    }
}

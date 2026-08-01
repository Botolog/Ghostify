package com.ghostify.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressBadgeTest {

    @Test
    fun `empty playlist has no badge`() {
        assertNull(ProgressBadge.progressToBadge(inFlight = false, progressPercent = null, downloaded = 0, total = 0))
        assertNull(ProgressBadge.progressToBadge(inFlight = true, progressPercent = 0, downloaded = 0, total = 0))
    }

    @Test
    fun `nothing downloaded and not in flight has no badge`() {
        assertNull(ProgressBadge.progressToBadge(inFlight = false, progressPercent = null, downloaded = 0, total = 10))
    }

    @Test
    fun `in-flight uses explicit progress percent`() {
        val badge = ProgressBadge.progressToBadge(inFlight = true, progressPercent = 42, downloaded = 4, total = 10)
        assertEquals(42, badge?.percent)
        assertEquals("42%", badge?.label)
    }

    @Test
    fun `in-flight falls back to downloaded ratio when no explicit percent`() {
        val badge = ProgressBadge.progressToBadge(inFlight = true, progressPercent = null, downloaded = 4, total = 10)
        assertEquals(40, badge?.percent)
        assertEquals("40%", badge?.label)
    }

    @Test
    fun `in-flight percent is clamped to range 0 to 100`() {
        assertEquals(100, ProgressBadge.progressToBadge(inFlight = true, progressPercent = 250, downloaded = 10, total = 10)?.percent)
        assertEquals(0, ProgressBadge.progressToBadge(inFlight = true, progressPercent = -5, downloaded = 0, total = 10)?.percent)
    }

    @Test
    fun `complete download shows Downloaded at 100`() {
        val badge = ProgressBadge.progressToBadge(inFlight = false, progressPercent = null, downloaded = 10, total = 10)
        assertEquals(100, badge?.percent)
        assertEquals("Downloaded", badge?.label)
    }

    @Test
    fun `partial non-in-flight shows x slash y`() {
        val badge = ProgressBadge.progressToBadge(inFlight = false, progressPercent = null, downloaded = 3, total = 8)
        assertEquals("3/8 downloaded", badge?.label)
    }

    @Test
    fun `downloaded never exceeds total`() {
        val badge = ProgressBadge.progressToBadge(inFlight = false, progressPercent = null, downloaded = 999, total = 5)
        assertEquals("Downloaded", badge?.label)
    }
}

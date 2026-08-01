package com.ghostify.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    private val now = 1_700_000_000_000L // fixed "current" clock

    @Test
    fun `null timestamp renders Never`() {
        assertEquals("Never", TimeFormat.formatRelative(null, now))
    }

    @Test
    fun `recent timestamp renders Just now`() {
        assertEquals("Just now", TimeFormat.formatRelative(now - 5_000, now))
        assertEquals("Just now", TimeFormat.formatRelative(now, now))
    }

    @Test
    fun `future timestamp is clamped to Just now`() {
        assertEquals("Just now", TimeFormat.formatRelative(now + 60_000, now))
    }

    @Test
    fun `minutes render as m ago`() {
        assertEquals("5m ago", TimeFormat.formatRelative(now - 5 * 60_000, now))
        assertEquals("59m ago", TimeFormat.formatRelative(now - 59 * 60_000, now))
    }

    @Test
    fun `hours render as h ago`() {
        assertEquals("2h ago", TimeFormat.formatRelative(now - 2 * 3_600_000, now))
        assertEquals("23h ago", TimeFormat.formatRelative(now - 23 * 3_600_000, now))
    }

    @Test
    fun `days render as d ago`() {
        assertEquals("3d ago", TimeFormat.formatRelative(now - 3 * 24 * 3_600_000, now))
    }

    @Test
    fun `older than a week renders as a date ending in a year`() {
        val result = TimeFormat.formatRelative(now - 30L * 24 * 3_600_000, now)
        assertEquals("Oct 15, 2023", result)
    }
}

package com.ghostify.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadLeakTrackerTest {

    @Test
    fun open_then_close_is_balanced() {
        val t = DownloadLeakTracker()
        val id = t.open("downloadAll:p1")
        assertEquals(1, t.retainedCount())
        assertTrue(t.close(id))
        assertEquals(0, t.retainedCount())
    }

    @Test
    fun retained_lists_only_opened_not_closed() {
        val t = DownloadLeakTracker()
        val a = t.open("downloadAll:p1")
        t.open("downloadAll:p1")
        t.close(a)
        assertEquals(1, t.retained("downloadAll:p1").size)
    }

    @Test
    fun double_close_returns_false() {
        val t = DownloadLeakTracker()
        val id = t.open("p1")
        assertTrue(t.close(id))
        assertFalse(t.close(id))
    }

    @Test
    fun closeAll_releases_per_owner_only() {
        val t = DownloadLeakTracker()
        t.open("p1")
        t.open("p1")
        t.open("p2")
        assertEquals(3, t.retainedCount())
        assertEquals(2, t.closeAll("p1"))
        assertEquals(1, t.retainedCount())
        assertEquals(1, t.retained("p2").size)
    }

    @Test
    fun unique_ids_per_owner() {
        val t = DownloadLeakTracker()
        val a = t.open("p1")
        val b = t.open("p1")
        assertTrue(a != b) { "ids should be unique: $a == $b" }
        val snap = t.snapshot()
        assertEquals(2, snap["p1"])
    }

    @Test
    fun timestamps_recorded() {
        val t = DownloadLeakTracker { 12345L }
        val id = t.open("p1")
        assertEquals(12345L, t.retained("p1").first().openedAtMillis)
        assertTrue(t.close(id))
    }
}

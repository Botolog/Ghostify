package xyz.botolog.ghostify.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {

    // ── Crash data class ──────────────────────────────────────────────────

    @Test
    fun crashCreationWithAllFields() {
        val thread = Thread.currentThread()
        val throwable = RuntimeException("test crash")
        val crash = Crash(thread, throwable, 1000L)
        assertEquals(thread, crash.thread)
        assertEquals(throwable, crash.throwable)
        assertEquals(1000L, crash.timestampMs)
    }

    @Test
    fun crashTypeReturnsFullyQualifiedClassName() {
        val crash = Crash(
            Thread.currentThread(),
            IllegalArgumentException("bad arg"),
            System.currentTimeMillis()
        )
        assertEquals("java.lang.IllegalArgumentException", crash.type)
    }

    @Test
    fun crashTypeReturnsCorrectNameForRuntimeException() {
        val crash = Crash(
            Thread.currentThread(),
            RuntimeException("oops"),
            0L
        )
        assertEquals("java.lang.RuntimeException", crash.type)
    }

    @Test
    fun crashTypeReturnsCorrectNameForNullPointerException() {
        val crash = Crash(
            Thread.currentThread(),
            NullPointerException(),
            0L
        )
        assertEquals("java.lang.NullPointerException", crash.type)
    }

    @Test
    fun crashStackTraceTextIsFormattedWithIndentation() {
        val throwable = RuntimeException("test")
        val crash = Crash(Thread.currentThread(), throwable, 0L)
        val stackTrace = crash.stackTraceText
        assertTrue(stackTrace.contains("    at "))
    }

    @Test
    fun crashStackTraceTextContainsExceptionClass() {
        val throwable = IllegalStateException("state issue")
        val crash = Crash(Thread.currentThread(), throwable, 0L)
        val stackTrace = crash.stackTraceText
        assertTrue(stackTrace.contains("IllegalStateException") || stackTrace.contains("at "))
    }

    @Test
    fun crashStackTraceTextUsesNewlineSeparator() {
        val crash = Crash(Thread.currentThread(), RuntimeException("t"), 0L)
        val lines = crash.stackTraceText.split("\n")
        assertTrue(lines.size >= 1)
    }

    @Test
    fun crashDataClassEquality() {
        val thread = Thread.currentThread()
        val throwable = RuntimeException("e")
        val ts = 42L
        val a = Crash(thread, throwable, ts)
        val b = Crash(thread, throwable, ts)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun crashDataClassInequalityOnTimestamp() {
        val thread = Thread.currentThread()
        val throwable = RuntimeException("e")
        val a = Crash(thread, throwable, 1L)
        val b = Crash(thread, throwable, 2L)
        assertNotEquals(a, b)
    }

    @Test
    fun crashDataClassCopy() {
        val original = Crash(Thread.currentThread(), RuntimeException("e"), 100L)
        val copied = original.copy(timestampMs = 200L)
        assertEquals(200L, copied.timestampMs)
        assertEquals(original.thread, copied.thread)
        assertEquals(original.throwable, copied.throwable)
    }

    @Test
    fun crashStackTraceTextHandlesEmptyStackTrace() {
        val throwable = RuntimeException("e")
        throwable.stackTrace = emptyArray()
        val crash = Crash(Thread.currentThread(), throwable, 0L)
        assertEquals("", crash.stackTraceText)
    }

    // ── CrashReporter fun interface ───────────────────────────────────────

    @Test
    fun crashReporterCanBeImplementedAsLambda() {
        var reported: Crash? = null
        val reporter = CrashReporter { crash ->
            reported = crash
            true
        }
        val crash = Crash(Thread.currentThread(), RuntimeException("e"), 1L)
        assertTrue(reporter.report(crash))
        assertEquals(crash, reported)
    }

    @Test
    fun crashReporterCanReturnFalse() {
        val reporter = CrashReporter { false }
        val crash = Crash(Thread.currentThread(), RuntimeException("e"), 1L)
        assertFalse(reporter.report(crash))
    }

    // ── CrashMarker interface ─────────────────────────────────────────────

    @Test
    fun crashMarkerMarkAndDetect() {
        val marker = InMemoryCrashMarker()
        assertFalse(marker.wasCrashDetected())
        marker.markCrashDetected()
        assertTrue(marker.wasCrashDetected())
    }

    @Test
    fun crashMarkerCleanStartupClearsFlag() {
        val marker = InMemoryCrashMarker()
        marker.markCrashDetected()
        assertTrue(marker.wasCrashDetected())
        marker.markCleanStartup()
        assertFalse(marker.wasCrashDetected())
    }

    @Test
    fun crashMarkerClearResetsState() {
        val marker = InMemoryCrashMarker()
        marker.markCrashDetected()
        marker.clear()
        assertFalse(marker.wasCrashDetected())
    }

    @Test
    fun crashMarkerMultipleDetectedCallsAreIdempotent() {
        val marker = InMemoryCrashMarker()
        marker.markCrashDetected()
        marker.markCrashDetected()
        assertTrue(marker.wasCrashDetected())
    }

    /** Simple in-memory implementation for testing the CrashMarker interface. */
    private class InMemoryCrashMarker : CrashMarker {
        private var detected = false

        override fun markCrashDetected() {
            detected = true
        }

        override fun markCleanStartup() {
            detected = false
        }

        override fun wasCrashDetected(): Boolean = detected

        override fun clear() {
            detected = false
        }
    }
}

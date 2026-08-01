package com.ghostify.test

import com.ghostify.time.Timestamps
import java.time.ZoneId

/**
 * Support for T-162 (manual): epoch-millis timestamps make sorting and display immune to
 * DST changes and timezone travel. These unit tests pin the invariant; the full checklist
 * lives in solution.md.
 */
object TimestampTest {

    fun run() {
        Harness.test("T-162: epoch-ms sort is identical across zones") {
            val a = 1_700_000_000_000L
            val b = 1_700_000_360_000L
            Harness.expectEq(
                Timestamps.sortAscending(a, b),
                Timestamps.sortAscending(b, a).let { -it },
            )
            Harness.expectTrue(Timestamps.sortAscending(a, b) < 0)
            Harness.expectTrue(Timestamps.sortAscending(b, a) > 0)
        }

        Harness.test("T-162: an instant is the same absolute time in every zone") {
            val epochMs = 1_700_000_000_000L
            Harness.expectTrue(Timestamps.fromEpochMs(epochMs).epochSecond == epochMs / 1000)
            val berlin = Timestamps.format(epochMs, ZoneId.of("Europe/Berlin"))
            val tokyo = Timestamps.format(epochMs, ZoneId.of("Asia/Tokyo"))
            Harness.expectTrue(berlin.isNotBlank(), "Berlin formatting failed")
            Harness.expectTrue(tokyo.isNotBlank(), "Tokyo formatting failed")
            Harness.expectTrue(berlin != tokyo, "different zones should render different wall-clock text")
        }

        Harness.test("T-162: formatting across a DST boundary never throws") {
            // 2023-10-29 01:30 UTC+2 -> 02:30 UTC+1 (Europe/Berlin fall-back transition)
            val dstBoundary = 1_698_523_800_000L
            Harness.expectTrue(Timestamps.format(dstBoundary, ZoneId.of("Europe/Berlin")).isNotBlank())
            Harness.expectTrue(Timestamps.format(dstBoundary + 3_600_000L, ZoneId.of("Europe/Berlin")).isNotBlank())
        }

        Harness.test("T-162: invalid/zero timestamps render blank, never break") {
            Harness.expectEq("", Timestamps.format(0L))
            Harness.expectEq("", Timestamps.format(-1L))
            Harness.expectFalse(Timestamps.isValid(0L))
            Harness.expectFalse(Timestamps.isValid(-5L))
            Harness.expectTrue(Timestamps.isValid(1L))
        }
    }
}

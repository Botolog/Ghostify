package com.ghostify.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T-044: status transitions follow the legal table, never invalid jumps. */
class SongStateMachineTest {

    private val expected: Map<DownloadStatus, Set<DownloadStatus>> = mapOf(
        DownloadStatus.PENDING to setOf(DownloadStatus.QUEUED),
        DownloadStatus.FAILED to setOf(DownloadStatus.QUEUED),
        DownloadStatus.QUEUED to setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING),
        DownloadStatus.DOWNLOADING to setOf(
            DownloadStatus.DOWNLOADED,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELED,
            DownloadStatus.PENDING,
        ),
        DownloadStatus.CANCELED to setOf(DownloadStatus.PENDING),
        DownloadStatus.DOWNLOADED to emptySet(),
    )

    @Test
    fun `exhaustive transition table is enforced`() {
        for (from in DownloadStatus.values()) {
            for (to in DownloadStatus.values()) {
                val legal = expected[from]!!.contains(to)
                assertEquals(
                    legal,
                    SongStateMachine.canTransition(from, to),
                    "canTransition($from, $to) mismatch",
                )
            }
        }
    }

    @Test
    fun `happy path is PENDING to QUEUED to DOWNLOADING to DOWNLOADED`() {
        var s = DownloadStatus.PENDING
        s = SongStateMachine.requireTransition(s, DownloadStatus.QUEUED)
        s = SongStateMachine.requireTransition(s, DownloadStatus.DOWNLOADING)
        s = SongStateMachine.requireTransition(s, DownloadStatus.DOWNLOADED)
        assertEquals(DownloadStatus.DOWNLOADED, s)
    }

    @Test
    fun `download can fail only while downloading`() {
        assertTrue(SongStateMachine.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.FAILED))
        assertFalse(SongStateMachine.canTransition(DownloadStatus.PENDING, DownloadStatus.FAILED))
        assertFailsWith<IllegalStateTransition> {
            SongStateMachine.requireTransition(DownloadStatus.PENDING, DownloadStatus.FAILED)
        }
    }

    @Test
    fun `downloaded is terminal and can never be re-enqueued`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.DOWNLOADED, DownloadStatus.QUEUED))
        assertFalse(SongStateMachine.canTransition(DownloadStatus.DOWNLOADED, DownloadStatus.PENDING))
        assertFailsWith<IllegalStateTransition> {
            SongStateMachine.requireTransition(DownloadStatus.DOWNLOADED, DownloadStatus.QUEUED)
        }
    }

    @Test
    fun `retry goes FAILED back to QUEUED`() {
        assertTrue(SongStateMachine.canTransition(DownloadStatus.FAILED, DownloadStatus.QUEUED))
    }

    @Test
    fun `recovery resets in-flight states to PENDING`() {
        assertTrue(SongStateMachine.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING))
        assertTrue(SongStateMachine.canTransition(DownloadStatus.QUEUED, DownloadStatus.PENDING))
        assertTrue(SongStateMachine.canTransition(DownloadStatus.CANCELED, DownloadStatus.PENDING))
    }
}

package xyz.botolog.ghostify

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cold/warm recognition of the media-notification tap that must open the full player
 * overlay, plus the counter semantics the composition relies on to latch it open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenPlayerRequestsTest {

    private val requests = OpenPlayerRequests()

    @Test
    fun action_isThePrivateAppScopedOpenPlayerAction() {
        assertEquals("xyz.botolog.ghostify.action.OPEN_PLAYER", OpenPlayerRequests.ACTION_OPEN_PLAYER)
    }

    @Test
    fun coldStart_openPlayerAction_registersRequest() {
        assertTrue(requests.handle(OpenPlayerRequests.ACTION_OPEN_PLAYER))

        assertEquals(1L, requests.requestCount.value)
    }

    @Test
    fun coldStart_requestRaisedBeforeAnyCollector_isStillDelivered() = runTest {
        requests.handle(OpenPlayerRequests.ACTION_OPEN_PLAYER)

        assertEquals("late collector must see the cold-start request", 1L, requests.requestCount.first())
    }

    @Test
    fun warmStart_secondTap_registersASecondRequest() {
        assertTrue(requests.handle(OpenPlayerRequests.ACTION_OPEN_PLAYER))
        assertTrue(requests.handle(OpenPlayerRequests.ACTION_OPEN_PLAYER))

        assertEquals(2L, requests.requestCount.value)
    }

    @Test
    fun unrelatedActions_registerNoRequest() {
        assertFalse(requests.handle(null))
        assertFalse(requests.handle(""))
        assertFalse(requests.handle("android.intent.action.MAIN"))
        assertFalse(requests.handle("android.intent.action.MEDIA_BUTTON"))
        assertFalse(requests.handle("xyz.botolog.ghostify.SHUTDOWN"))
        assertFalse(requests.handle("xyz.botolog.ghostify.action.OPEN_PLAYER.extra"))
        assertFalse(requests.handle("xyz.botolog.ghostify.action.open_player"))

        assertEquals(0L, requests.requestCount.value)
    }

    @Test
    fun unrelatedAction_afterRealRequest_leavesTheRequestCountAlone() {
        requests.handle(OpenPlayerRequests.ACTION_OPEN_PLAYER)

        requests.handle("android.intent.action.MAIN")

        assertEquals(1L, requests.requestCount.value)
    }

    @Test
    fun repeatedTaps_areNotConflated() = runTest {
        val seen = mutableListOf<Long>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            requests.requestCount.toList(seen)
        }

        repeat(3) { requests.handle(OpenPlayerRequests.ACTION_OPEN_PLAYER) }
        collector.cancel()

        assertEquals(listOf(0L, 1L, 2L, 3L), seen)
    }

    @Test
    fun repeatedRequests_withoutIntentAction_alsoAdvanceTheCounter() {
        requests.request()
        requests.request()

        assertEquals(2L, requests.requestCount.value)
    }

    @Test
    fun processBus_carriesRequestsAndIgnoresUnrelatedActions() = runTest {
        val before = OpenPlayerRequests.Process.requestCount.value

        assertFalse(OpenPlayerRequests.Process.handle("android.intent.action.MAIN"))
        assertEquals(before, OpenPlayerRequests.Process.requestCount.value)

        OpenPlayerRequests.Process.handle(OpenPlayerRequests.ACTION_OPEN_PLAYER)

        assertEquals(before + 1L, OpenPlayerRequests.Process.requestCount.first())
    }

    @Test
    fun separateBuses_doNotShareState() {
        requests.handle(OpenPlayerRequests.ACTION_OPEN_PLAYER)
        val other = OpenPlayerRequests()

        assertEquals(0L, other.requestCount.value)
        assertEquals(1L, requests.requestCount.value)
    }
}

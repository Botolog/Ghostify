package xyz.botolog.ghostify.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InAppSnackbarTest {

    private class FakeClock(var now: Long = 0L) {
        fun read(): Long = now
        fun advance(millis: Long) {
            now += millis
        }
    }

    @Test
    fun `added to queue message includes the title`() {
        assertEquals("Blue Monday added to queue", InAppSnackbarText.addedToQueue("Blue Monday"))
    }

    @Test
    fun `added to queue message trims the title`() {
        assertEquals("Blue Monday added to queue", InAppSnackbarText.addedToQueue("  Blue Monday  "))
    }

    @Test
    fun `added to queue message falls back when the title is blank`() {
        assertEquals("Added to queue", InAppSnackbarText.addedToQueue("   "))
        assertEquals("Added to queue", InAppSnackbarText.addedToQueue(""))
    }

    @Test
    fun `dedup shows a first time message`() {
        val decision = inAppSnackbarDedup(
            text = "a",
            visibleText = null,
            lastShownText = null,
            lastShownAtMillis = Long.MIN_VALUE,
            nowMillis = 0L,
            windowMillis = 1_500L,
        )
        assertEquals(InAppSnackbarDedup.Show, decision)
    }

    @Test
    fun `dedup rejects a message that is already visible`() {
        val decision = inAppSnackbarDedup(
            text = "a",
            visibleText = "a",
            lastShownText = null,
            lastShownAtMillis = Long.MIN_VALUE,
            nowMillis = 0L,
            windowMillis = 1_500L,
        )
        assertEquals(InAppSnackbarDedup.AlreadyVisible, decision)
    }

    @Test
    fun `dedup rejects a repeat inside the dedup window`() {
        val decision = inAppSnackbarDedup(
            text = "a",
            visibleText = null,
            lastShownText = "a",
            lastShownAtMillis = 1_000L,
            nowMillis = 2_000L,
            windowMillis = 1_500L,
        )
        assertEquals(InAppSnackbarDedup.ShownRecently, decision)
    }

    @Test
    fun `dedup allows a repeat after the dedup window elapsed`() {
        val decision = inAppSnackbarDedup(
            text = "a",
            visibleText = null,
            lastShownText = "a",
            lastShownAtMillis = 1_000L,
            nowMillis = 10_000L,
            windowMillis = 1_500L,
        )
        assertEquals(InAppSnackbarDedup.Show, decision)
    }

    @Test
    fun `dedup allows a different message immediately`() {
        val decision = inAppSnackbarDedup(
            text = "b",
            visibleText = "a",
            lastShownText = "a",
            lastShownAtMillis = 1_000L,
            nowMillis = 1_100L,
            windowMillis = 1_500L,
        )
        assertEquals(InAppSnackbarDedup.Show, decision)
    }

    @Test
    fun `state publishes the message and dismisses it`() {
        val clock = FakeClock()
        val state = InAppSnackbarState(clock::read)

        assertNull(state.message)

        state.showAddedToQueue("Blue Monday")

        val shown = state.message
        assertNotNull(shown)
        assertEquals("Blue Monday added to queue", shown?.text)
        assertEquals(InAppSnackbarDefaults.LeadingIcon, shown?.leadingIcon)
        assertEquals(InAppSnackbarDefaults.DurationMillis, shown?.durationMillis)

        state.dismiss()
        assertNull(state.message)
    }

    @Test
    fun `state ignores a duplicate while the message is visible`() {
        val clock = FakeClock()
        val state = InAppSnackbarState(clock::read)

        state.showAddedToQueue("Blue Monday")
        val firstId = state.message?.id

        state.showAddedToQueue("Blue Monday")

        assertEquals(firstId, state.message?.id)
    }

    @Test
    fun `state ignores a duplicate shortly after dismissal`() {
        val clock = FakeClock()
        val state = InAppSnackbarState(clock::read)

        state.showAddedToQueue("Blue Monday")
        state.dismiss()
        clock.advance(200)
        state.showAddedToQueue("Blue Monday")

        assertNull(state.message)
    }

    @Test
    fun `state re-shows the same message after the dedup window`() {
        val clock = FakeClock()
        val state = InAppSnackbarState(clock::read)

        state.showAddedToQueue("Blue Monday")
        state.dismiss()
        clock.advance(InAppSnackbarDefaults.DedupWindowMillis + 1)
        state.showAddedToQueue("Blue Monday")

        assertEquals("Blue Monday added to queue", state.message?.text)
    }

    @Test
    fun `state replaces the message when a different song is queued`() {
        val clock = FakeClock()
        val state = InAppSnackbarState(clock::read)

        state.showAddedToQueue("Blue Monday")
        val firstId = state.message?.id
        state.showAddedToQueue("Just Like Heaven")

        assertEquals("Just Like Heaven added to queue", state.message?.text)
        check(state.message?.id != firstId)
    }

    @Test
    fun `state ignores blank messages`() {
        val clock = FakeClock()
        val state = InAppSnackbarState(clock::read)

        state.show("   ")

        assertNull(state.message)
    }
}

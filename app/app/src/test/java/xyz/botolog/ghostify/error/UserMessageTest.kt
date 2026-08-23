package xyz.botolog.ghostify.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageTest {

    // ── UserMessage defaults ──────────────────────────────────────────────

    @Test
    fun userMessage_defaultResKeyIsNull() {
        val msg = UserMessage(text = "hello")
        assertNull(msg.resKey)
    }

    @Test
    fun userMessage_defaultRetriableIsFalse() {
        val msg = UserMessage(text = "hello")
        assertFalse(msg.retriable)
    }

    @Test
    fun userMessage_defaultActionLabelIsNull() {
        val msg = UserMessage(text = "hello")
        assertNull(msg.actionLabel)
    }

    // ── isBlank ───────────────────────────────────────────────────────────

    @Test
    fun isBlank_emptyTextReturnsTrue() {
        assertTrue(UserMessage(text = "").isBlank)
    }

    @Test
    fun isBlank_blankTextReturnsTrue() {
        assertTrue(UserMessage(text = "   ").isBlank)
    }

    @Test
    fun isBlank_nonBlankTextReturnsFalse() {
        assertFalse(UserMessage(text = "hello").isBlank)
    }

    // ── EMPTY companion ───────────────────────────────────────────────────

    @Test
    fun empty_hasBlankText() {
        assertTrue(UserMessage.EMPTY.isBlank)
    }

    @Test
    fun empty_textIsEmptyString() {
        assertEquals("", UserMessage.EMPTY.text)
    }

    // ── GENERIC companion ─────────────────────────────────────────────────

    @Test
    fun generic_hasNonBlankText() {
        assertFalse(UserMessage.GENERIC.isBlank)
    }

    @Test
    fun generic_hasResKey() {
        assertEquals("error_unknown", UserMessage.GENERIC.resKey)
    }

    @Test
    fun generic_isRetriable() {
        assertTrue(UserMessage.GENERIC.retriable)
    }

    @Test
    fun generic_actionLabelIsNull() {
        assertNull(UserMessage.GENERIC.actionLabel)
    }

    // ── Throwable.toUserMessage ───────────────────────────────────────────

    @Test
    fun toUserMessage_runtimeExceptionReturnsNonBlankMessage() {
        val msg = RuntimeException("boom").toUserMessage()
        assertTrue(msg.text.isNotBlank())
    }

    @Test
    fun toUserMessage_neverContainsRawExceptionText() {
        val msg = RuntimeException("secret-internal-data-999").toUserMessage()
        assertFalse(msg.text.contains("secret-internal-data-999"))
    }

    @Test
    fun toUserMessage_hasResKey() {
        val msg = RuntimeException("test").toUserMessage()
        assertNotNull(msg.resKey)
    }

    @Test
    fun toUserMessage_networkErrorIsRetriable() {
        val msg = java.net.UnknownHostException("no internet").toUserMessage()
        assertTrue(msg.retriable)
    }

    // ── AppError.toUserMessage ────────────────────────────────────────────

    @Test
    fun appError_toUserMessage_matchesFields() {
        val error = NetworkError()
        val msg = error.toUserMessage()
        assertEquals(error.userMessage, msg.text)
        assertEquals(error.userMessageResKey, msg.resKey)
        assertEquals(error.retriable, msg.retriable)
        assertEquals(error.actionHint, msg.actionLabel)
    }

    @Test
    fun appError_toUserMessage_privatePlaylistNotRetriable() {
        val error = PrivatePlaylistError()
        val msg = error.toUserMessage()
        assertFalse(msg.retriable)
        assertEquals("Use a public playlist", msg.actionLabel)
    }

    @Test
    fun appError_toUserMessage_rateLimitRetriable() {
        val error = RateLimitError()
        val msg = error.toUserMessage()
        assertTrue(msg.retriable)
    }
}

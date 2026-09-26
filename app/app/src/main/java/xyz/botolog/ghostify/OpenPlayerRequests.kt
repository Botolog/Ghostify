package xyz.botolog.ghostify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Recognition rule and process-wide bus for "show the full player" requests raised
 * outside Compose — currently the media notification tap.
 *
 * The intent action is private (no browsable filter, no manifest entry): only the
 * media session's own pending intent carries it, and the activity matches it by
 * string before anything else looks at the intent.
 *
 * Ownership is deliberately process-scoped so a request survives activity
 * recreation, and the payload is a monotonic counter rather than a boolean so two
 * taps in a row are never conflated by [StateFlow] value equality.
 *
 * @property requestCount number of requests raised so far; increases by one per request.
 */
class OpenPlayerRequests {

    private val _requestCount = MutableStateFlow(0L)

    /** Monotonic count of open-player requests; one increment per request. */
    val requestCount: StateFlow<Long> = _requestCount.asStateFlow()

    /**
     * Registers a request when [action] is the open-player action.
     *
     * @param action action of the incoming intent, or null when it carries none.
     * @return true when a request was registered, false for every unrelated action.
     */
    fun handle(action: String?): Boolean {
        if (!isOpenPlayerAction(action)) return false
        request()
        return true
    }

    /** Registers one open-player request. */
    fun request() {
        _requestCount.update { it + 1L }
    }

    companion object {

        /** Private action carried by the media session's tap-through pending intent. */
        const val ACTION_OPEN_PLAYER = "xyz.botolog.ghostify.action.OPEN_PLAYER"

        /** Bus shared by the activity and the composition root. */
        val Process = OpenPlayerRequests()

        /** True only for the exact open-player action. */
        fun isOpenPlayerAction(action: String?): Boolean = action == ACTION_OPEN_PLAYER
    }
}

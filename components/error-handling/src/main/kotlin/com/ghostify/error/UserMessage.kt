package com.ghostify.error

/**
 * A user-facing message. Never carries a raw [Throwable].
 *
 * The app layer may resolve [resKey] against its string resources for localization;
 * [text] is the readable English default and the fallback when no resource matches.
 */
data class UserMessage(
    val text: String,
    val resKey: String? = null,
    val retriable: Boolean = false,
    val actionLabel: String? = null,
) {
    val isBlank: Boolean get() = text.isBlank()

    companion object {
        val EMPTY = UserMessage(text = "")

        val GENERIC = UserMessage(
            text = "Something went wrong. Please try again.",
            resKey = "error_unknown",
            retriable = true,
        )
    }
}

/** Convenience for UI boundaries: any failure becomes a readable message, never a raw exception. */
fun Throwable.toUserMessage(): UserMessage = ErrorMapper.map(this).toUserMessage()

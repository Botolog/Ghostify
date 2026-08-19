package xyz.botolog.ghostify.error

import timber.log.Timber

/**
 * Machine-readable, stable error code.
 *
 * These are the canonical typed failures of the app. Any [Throwable] that reaches a
 * component boundary is classified into exactly one of these before it can reach the UI,
 * so no raw exception is ever shown to the user (T-157).
 */
enum class ErrorCode {
    NO_NETWORK,
    RATE_LIMITED,
    PRIVATE,
    TIMEOUT,
    NOT_FOUND,
    API_ERROR,
    DOWNLOAD_FAILED,
    STORAGE_FULL,
    INVALID_URL,
    CORRUPT_FILE,
    EMPTY_PLAYLIST,
    NOTHING_TO_PLAY,
    CANCELLED,
    UNKNOWN,
}

/**
 * Root of the typed error hierarchy.
 *
 * Design decisions:
 *  - [AppError] extends [Exception] so a failure can flow through Kotlin [Result],
 *    `runCatching`, coroutines and WorkManager without losing its type.
 *  - Every leaf carries a human-readable [userMessage]. [detail] may carry technical
 *    context for logs only — it is never rendered to the user.
 *  - [retriable] and [actionHint] let the UI offer a sensible recovery affordance.
 *  - The UI boundary converts an [AppError] to a [UserMessage] (T-157). The exact class
 *    of the exception is irrelevant at the boundary; [code] is the stable contract.
 */
sealed class AppError(
    val code: ErrorCode,
    val userMessage: String,
    val detail: String? = null,
    val retriable: Boolean = false,
    val actionHint: String? = null,
    causeOf: Throwable? = null,
) : Exception(sanitizeCause(causeOf)) {

    /** Expose the readable message through [Throwable.message] so logs stay readable. */
    override val message: String get() = userMessage

    /** String-resource key for localized variants; [userMessage] is the fallback text. */
    val userMessageResKey: String
        get() = "error_" + code.name.lowercase()

    fun toUserMessage(): UserMessage {
        Timber.i("AppError.toUserMessage: START")
        val result = UserMessage(
            text = userMessage,
            resKey = userMessageResKey,
            retriable = retriable,
            actionLabel = actionHint,
        )
        Timber.i("AppError.toUserMessage: returning $result")
        return result
    }

    override fun toString(): String = "AppError(code=$code, detail=$detail)"

    private companion object {
        /**
         * `Throwable(cause)` calls `cause.toString()` while initializing, which can itself
         * throw for pathological throwables (e.g. a `getMessage()` override that throws).
         * Guard the super call so constructing an [AppError] — and therefore [ErrorMapper.map] —
         * can never crash. A failed probe degrades to `null` cause, losing only chaining.
         */
        private fun sanitizeCause(cause: Throwable?): Throwable? = try {
            if (cause == null) null else cause.toString().let { cause }
        } catch (t: Throwable) {
            Timber.e(t, "AppError: sanitizeCause FAILED")
            null
        }
    }
}

/** Network is unreachable, DNS resolution failed, or a connection was refused. */
class NetworkError(
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.NO_NETWORK,
    userMessage = "You're offline. Check your internet connection and try again.",
    detail = detail,
    retriable = true,
    actionHint = "Reconnect and retry",
    causeOf = causeOf,
)

/** Spotify/YouTube returned HTTP 429 or a rate-limit signal. */
class RateLimitError(
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.RATE_LIMITED,
    userMessage = "Too many requests. Wait a minute, then try again.",
    detail = detail,
    retriable = true,
    actionHint = "Try again in a moment",
    causeOf = causeOf,
)

/** The target playlist is private; v1 only supports public playlists. */
class PrivatePlaylistError(
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.PRIVATE,
    userMessage = "This playlist is private. Ghostify can only save public playlists.",
    detail = detail,
    retriable = false,
    actionHint = "Use a public playlist",
    causeOf = causeOf,
)

/** A request exceeded its deadline. */
class TimeoutError(
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.TIMEOUT,
    userMessage = "The request took too long and timed out. Try again.",
    detail = detail,
    retriable = true,
    actionHint = "Retry",
    causeOf = causeOf,
)

/** The playlist, track, or local file no longer exists. */
class NotFoundError(
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.NOT_FOUND,
    userMessage = "This playlist or track no longer exists.",
    detail = detail,
    retriable = false,
    actionHint = null,
    causeOf = causeOf,
)

/** A general server/API failure (4xx other than 429/403, or any 5xx). */
class ApiError(
    val statusCode: Int,
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.API_ERROR,
    userMessage = "Spotify had a hiccup. Try again in a moment.",
    detail = detail ?: "HTTP $statusCode",
    retriable = true,
    actionHint = "Retry",
    causeOf = causeOf,
)

/** A track download failed (region block, unavailable YouTube source, etc.). */
class DownloadFailedError(
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.DOWNLOAD_FAILED,
    userMessage = "Couldn't download this track. It may be unavailable in your region.",
    detail = detail,
    retriable = true,
    actionHint = "Retry this track",
    causeOf = causeOf,
)

/** The device ran out of storage space. */
class StorageFullError(
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.STORAGE_FULL,
    userMessage = "Not enough storage space on your device. Free up space and try again.",
    detail = detail,
    retriable = true,
    actionHint = "Free up space",
    causeOf = causeOf,
)

/** A pasted URL is not a valid Spotify playlist link. */
class InvalidUrlError(
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.INVALID_URL,
    userMessage = "That doesn't look like a valid Spotify playlist link.",
    detail = detail,
    retriable = false,
    actionHint = null,
    causeOf = causeOf,
)

/** A local media file is corrupt or truncated. */
class CorruptFileError(
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.CORRUPT_FILE,
    userMessage = "This file appears to be damaged. Try re-downloading it.",
    detail = detail,
    retriable = true,
    actionHint = "Re-download",
    causeOf = causeOf,
)

/** The playlist exists but has zero tracks — benign, not a crash. */
class EmptyPlaylistError(
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.EMPTY_PLAYLIST,
    userMessage = "This playlist doesn't have any tracks yet.",
    detail = detail,
    retriable = false,
    actionHint = null,
    causeOf = causeOf,
)

/** Playback was requested but there is nothing downloadable/playable. */
class NothingToPlayError(
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.NOTHING_TO_PLAY,
    userMessage = "No downloaded tracks to play yet. Download some tracks first.",
    detail = detail,
    retriable = false,
    actionHint = "Download tracks",
    causeOf = causeOf,
)

/** The operation was cancelled by the user or by structured cancellation. */
class CancelledError(
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.CANCELLED,
    userMessage = "This was cancelled.",
    detail = detail,
    retriable = false,
    actionHint = null,
    causeOf = causeOf,
)

/** Catch-all fallback — always readable, never a raw exception. */
class UnknownError(
    detail: String? = null,
    causeOf: Throwable? = null,
) : AppError(
    code = ErrorCode.UNKNOWN,
    userMessage = "Something went wrong. Please try again.",
    detail = detail,
    retriable = true,
    actionHint = "Retry",
    causeOf = causeOf,
)

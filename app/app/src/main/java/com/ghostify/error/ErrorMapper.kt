package com.ghostify.error

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpRetryException
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/**
 * Total, side-effect-free classifier: any [Throwable] — or an already-typed [AppError] —
 * becomes exactly one [AppError] whose [AppError.userMessage] is readable.
 *
 * Invariants (backed by the unit tests):
 *  - [map] never throws and never returns null.
 *  - [map] is an identity for [AppError] instances.
 *  - Raw exception text is never exposed through [AppError.userMessage].
 *
 * [map] is context-free; call sites that know the failing operation can construct the
 * specific leaf directly (e.g. the download pipeline throws [DownloadFailedError]).
 */
object ErrorMapper {

    fun map(error: AppError): AppError = error

    fun map(throwable: Throwable): AppError {
        return when (throwable) {
            is AppError -> throwable
            is CancellationException -> CancelledError(causeOf = throwable)
            else -> try {
                classify(throwable)
            } catch (_: Throwable) {
                UnknownError(detail = "mapper failure", causeOf = throwable)
            }
        }
    }

    /** Classify an HTTP status code into a typed error (used by the API layer). */
    fun map(statusCode: Int, detail: String? = null, cause: Throwable? = null): AppError = when (statusCode) {
        400 -> InvalidUrlError(detail = detail ?: "HTTP 400", causeOf = cause)
        401 -> ApiError(statusCode = statusCode, detail = detail ?: "HTTP 401", causeOf = cause)
        403 -> PrivatePlaylistError(detail = detail ?: "HTTP 403", causeOf = cause)
        404 -> NotFoundError(detail = detail ?: "HTTP 404", causeOf = cause)
        429 -> RateLimitError(detail = detail, causeOf = cause)
        else -> ApiError(statusCode = statusCode, detail = detail, causeOf = cause)
    }

    private fun classify(t: Throwable): AppError {
        val message = t.message?.lowercase() ?: ""
        return when (t) {
            is UnknownHostException, is NoRouteToHostException, is ConnectException ->
                NetworkError(detail = message, causeOf = t)

            is SocketTimeoutException, is TimeoutException, is InterruptedIOException ->
                TimeoutError(detail = message, causeOf = t)

            is FileNotFoundException ->
                NotFoundError(detail = message, causeOf = t)

            is HttpRetryException ->
                ApiError(statusCode = t.responseCode(), detail = message, causeOf = t)

            is MalformedURLException ->
                InvalidUrlError(detail = message, causeOf = t)

            is IllegalArgumentException ->
                if (looksLikeUrlProblem(message)) InvalidUrlError(detail = message, causeOf = t)
                else UnknownError(detail = message, causeOf = t)

            is SecurityException ->
                ApiError(statusCode = 403, detail = message, causeOf = t)

            is IOException -> classifyIo(t, message)

            else -> classifyByMessage(t, message)
        }
    }

    private fun classifyIo(t: IOException, message: String): AppError = when {
        isStorageFull(message) -> StorageFullError(detail = message, causeOf = t)
        isCorruptFile(message) -> CorruptFileError(detail = message, causeOf = t)
        isNetworkLike(message) -> NetworkError(detail = message, causeOf = t)
        else -> UnknownError(detail = message, causeOf = t)
    }

    /** Heuristic pass for unknown exception types (Python bridge errors, spotdl, etc.). */
    private fun classifyByMessage(t: Throwable, message: String): AppError = when {
        isStorageFull(message) -> StorageFullError(detail = message, causeOf = t)
        containsAny(message, "private playlist", "is private", "requires authentication") ->
            PrivatePlaylistError(detail = message, causeOf = t)
        containsAny(message, "rate limit", "too many requests", "http 429", "status: 429", "429") ->
            RateLimitError(detail = message, causeOf = t)
        containsAny(message, "http 404", "not found", "couldn't find", "doesn't exist", "invalid playlist") ->
            NotFoundError(detail = message, causeOf = t)
        containsAny(message, "timed out", "timeout", "deadline exceeded") ->
            TimeoutError(detail = message, causeOf = t)
        isCorruptFile(message) -> CorruptFileError(detail = message, causeOf = t)
        looksLikeUrlProblem(message) -> InvalidUrlError(detail = message, causeOf = t)
        containsAny(message, "http 5", "server error", "internal error", "bad gateway") ->
            ApiError(statusCode = 500, detail = message, causeOf = t)
        isNetworkLike(message) -> NetworkError(detail = message, causeOf = t)
        containsAny(message, "youtube", "unavailable", "region", "copyright", "could not download") ->
            DownloadFailedError(detail = message, causeOf = t)
        else -> UnknownError(detail = message, causeOf = t)
    }

    private fun isStorageFull(m: String) = containsAny(
        m,
        "enospc", "no space left on device", "not enough space", "disk full",
        "out of space", "storage is full", "write failed", "cannot write",
    )

    private fun isCorruptFile(m: String) = containsAny(
        m,
        "corrupt", "malformed", "truncated", "unsupported format", "format not supported",
        "bad file", "invalid data", "decode error", "decoder failed", "error reading",
        "damaged", "invalid bitstream", "media error", "invalid data found",
    )

    private fun isNetworkLike(m: String) = containsAny(
        m,
        "failed to connect", "connection refused", "connection reset", "cleartext",
        "unable to resolve host", "network is unreachable", "name or service not known",
        "no route to host", "ssl handshake", "cipher",
    )

    private fun looksLikeUrlProblem(m: String) = containsAny(
        m,
        "invalid url", "malformed url", "invalid uri", "illegal character in url",
        "no protocol", "unexpected end of url", "unknown protocol",
    )

    private fun containsAny(text: String, vararg needles: String): Boolean =
        needles.any { it in text }
}

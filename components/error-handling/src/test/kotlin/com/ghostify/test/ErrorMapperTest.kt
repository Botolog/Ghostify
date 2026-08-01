package com.ghostify.test

import com.ghostify.error.ApiError
import com.ghostify.error.AppError
import com.ghostify.error.CancelledError
import com.ghostify.error.CorruptFileError
import com.ghostify.error.DownloadFailedError
import com.ghostify.error.EmptyPlaylistError
import com.ghostify.error.ErrorCode
import com.ghostify.error.ErrorMapper
import com.ghostify.error.InvalidUrlError
import com.ghostify.error.NetworkError
import com.ghostify.error.NotFoundError
import com.ghostify.error.PrivatePlaylistError
import com.ghostify.error.RateLimitError
import com.ghostify.error.StorageFullError
import com.ghostify.error.UnknownError
import com.ghostify.error.safeCall
import com.ghostify.error.errorOrNull
import com.ghostify.error.toUserMessage
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

object ErrorMapperTest {

    private fun everyLeaf(): List<AppError> = listOf(
        NetworkError(),
        RateLimitError(),
        PrivatePlaylistError(),
        com.ghostify.error.TimeoutError(),
        NotFoundError(),
        ApiError(statusCode = 500),
        DownloadFailedError(),
        StorageFullError(),
        InvalidUrlError(),
        CorruptFileError(),
        EmptyPlaylistError(),
        com.ghostify.error.NothingToPlayError(),
        CancelledError(),
        UnknownError(),
    )

    /** T-157: every user-facing error maps to a readable message; no raw exceptions in UI. */
    fun t157() {
        val all = everyLeaf()
        Harness.test("T-157: every AppError leaf has a non-blank readable userMessage") {
            all.forEach { e ->
                Harness.expectTrue(
                    e.userMessage.isNotBlank(),
                    "blank userMessage for ${e.code}",
                )
                Harness.expectTrue(
                    e.toUserMessage().text.isNotBlank(),
                    "blank UserMessage for ${e.code}",
                )
            }
        }

        Harness.test("T-157: userMessage never leaks raw exception text") {
            val secret = "internal-xyz-42"
            val mapped = ErrorMapper.map(RuntimeException("boom $secret"))
            Harness.expectFalse(mapped.userMessage.contains(secret), "leaked raw detail into UI text")
            Harness.expectFalse(mapped.userMessage.contains("Exception"), "raw class name in UI text")
            Harness.expectFalse(mapped.userMessage.contains("StackTrace"), "stack trace in UI text")
        }

        Harness.test("T-157: mapper is total over an adversarial battery of throwables") {
            val battery: List<Throwable> = listOf(
                RuntimeException("boom"),
                RuntimeException(null as String?),
                NullPointerException(),
                IllegalStateException("state"),
                Exception("HTTP 429 Too Many Requests"),
                IOException("No space left on device"),
                UnknownHostException("host"),
                ArrayIndexOutOfBoundsException(5),
                ClassCastException(),
            )
            battery.forEach { t ->
                val error = ErrorMapper.map(t)
                Harness.expectNotNull(error, "map returned null for ${t.javaClass.simpleName}")
                Harness.expectTrue(
                    error.userMessage.isNotBlank(),
                    "non-readable message for ${t.javaClass.simpleName}",
                )
            }
        }

        Harness.test("T-157: mapper never throws, even for broken throwables") {
            class BrokenThrowable : Throwable() {
                override val message: String? get() = throw IllegalStateException("broken getMessage")
            }
            val error = ErrorMapper.map(BrokenThrowable())
            Harness.expectTrue(error.userMessage.isNotBlank(), "broken throwable produced blank message")
        }
    }

    /** T-158: no crashes on network down, API error, empty playlist, corrupt file, storage full. */
    fun t158() {
        Harness.test("T-158: network down maps to NO_NETWORK") {
            Harness.expectEq(ErrorCode.NO_NETWORK, ErrorMapper.map(UnknownHostException("spotify.com")).code)
            Harness.expectEq(ErrorCode.NO_NETWORK, ErrorMapper.map(ConnectException("refused")).code)
            Harness.expectEq(
                ErrorCode.NO_NETWORK,
                ErrorMapper.map(IOException("failed to connect to /1.2.3.4")).code,
            )
        }

        Harness.test("T-158: API errors map to typed, retriable errors") {
            Harness.expectEq(ErrorCode.API_ERROR, ErrorMapper.map(500).code)
            Harness.expectEq(ErrorCode.NOT_FOUND, ErrorMapper.map(404).code)
            Harness.expectEq(ErrorCode.RATE_LIMITED, ErrorMapper.map(429).code)
            Harness.expectEq(ErrorCode.PRIVATE, ErrorMapper.map(403).code)
            Harness.expectTrue(ErrorMapper.map(500).retriable, "5xx should be retriable")
        }

        Harness.test("T-158: empty playlist is benign and readable") {
            val error = EmptyPlaylistError()
            Harness.expectTrue(error.userMessage.isNotBlank(), "empty-playlist message blank")
            Harness.expectEq(ErrorCode.EMPTY_PLAYLIST, error.code)
        }

        Harness.test("T-158: corrupt file maps to CORRUPT_FILE, no crash") {
            Harness.expectEq(ErrorCode.CORRUPT_FILE, ErrorMapper.map(IOException("Invalid bitstream in header")).code)
            Harness.expectEq(ErrorCode.CORRUPT_FILE, ErrorMapper.map(IOException("Format not supported")).code)
            Harness.expectEq(ErrorCode.CORRUPT_FILE, ErrorMapper.map(IOException("corrupt file")).code)
            Harness.expectEq(ErrorCode.NOT_FOUND, ErrorMapper.map(FileNotFoundException("/sdcard/a.mp3")).code)
        }

        Harness.test("T-158: storage full maps to STORAGE_FULL") {
            Harness.expectEq(ErrorCode.STORAGE_FULL, ErrorMapper.map(IOException("No space left on device")).code)
            Harness.expectEq(ErrorCode.STORAGE_FULL, ErrorMapper.map(IOException("ENOSPC: write failed")).code)
        }

        Harness.test("T-158: timeout maps to TIMEOUT") {
            Harness.expectEq(ErrorCode.TIMEOUT, ErrorMapper.map(SocketTimeoutException("read timed out")).code)
            Harness.expectEq(ErrorCode.TIMEOUT, ErrorMapper.map(TimeoutException("deadline")).code)
        }

        Harness.test("T-158: invalid URL maps to INVALID_URL") {
            Harness.expectEq(ErrorCode.INVALID_URL, ErrorMapper.map(MalformedURLException("no protocol")).code)
            Harness.expectEq(
                ErrorCode.INVALID_URL,
                ErrorMapper.map(IllegalArgumentException("Invalid URL: open.spotify.com/playlist/")).code,
            )
        }

        Harness.test("T-158: Python-bridge style messages are classified") {
            Harness.expectEq(ErrorCode.PRIVATE, ErrorMapper.map(RuntimeException("This playlist is private")).code)
            Harness.expectEq(ErrorCode.RATE_LIMITED, ErrorMapper.map(RuntimeException("Spotify returned 429")).code)
            Harness.expectEq(ErrorCode.NOT_FOUND, ErrorMapper.map(RuntimeException("Playlist not found")).code)
            Harness.expectEq(ErrorCode.TIMEOUT, ErrorMapper.map(RuntimeException("Connection timed out")).code)
            Harness.expectEq(ErrorCode.UNKNOWN, ErrorMapper.map(RuntimeException("weird py thing")).code)
        }

        Harness.test("T-158: safeCall catches and maps; success passes through") {
            val ok = safeCall { 42 }
            Harness.expectEq(42, ok.getOrNull())
            val bad = safeCall { throw UnknownHostException("host") }
            Harness.expectEq(ErrorCode.NO_NETWORK, bad.errorOrNull()?.code)
        }

        Harness.test("T-158: safeCall rethrows CancellationException (structured concurrency)") {
            Harness.expectThrows(CancellationException()) {
                safeCall { throw CancellationException("user cancelled") }
            }
        }

        Harness.test("T-158: already-typed errors pass through the mapper unchanged") {
            val original = StorageFullError(detail = "d")
            Harness.expectTrue(ErrorMapper.map(original) === original, "mapper should be identity for AppError")
        }

        Harness.test("T-158: classify 400 as invalid url, 401 as API error") {
            Harness.expectEq(ErrorCode.INVALID_URL, ErrorMapper.map(400).code)
            Harness.expectEq(ErrorCode.API_ERROR, ErrorMapper.map(401).code)
        }
    }
}

package xyz.botolog.ghostify.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

class ErrorMapperTest {

    // ── map(AppError) identity ────────────────────────────────────────────

    @Test
    fun mapAppError_returnsSameInstance() {
        val error = NetworkError(detail = "test")
        assertEquals(error, ErrorMapper.map(error))
    }

    @Test
    fun mapAppError_anyAppErrorIsIdentity() {
        val errors = listOf(
            NetworkError(),
            RateLimitError(),
            PrivatePlaylistError(),
            TimeoutError(),
            NotFoundError(),
            ApiError(statusCode = 500),
            DownloadFailedError(),
            StorageFullError(),
            InvalidUrlError(),
            CorruptFileError(),
            EmptyPlaylistError(),
            NothingToPlayError(),
            CancelledError(),
            UnknownError(),
        )
        for (error in errors) {
            assertEquals(error, ErrorMapper.map(error))
        }
    }

    // ── map(Throwable) → AppError subclasses ──────────────────────────────

    @Test
    fun mapCancellationException_returnsCancelledError() {
        val result = ErrorMapper.map(CancellationException("cancelled"))
        assertTrue(result is CancelledError)
        assertEquals(ErrorCode.CANCELLED, result.code)
    }

    @Test
    fun mapUnknownHostException_returnsNetworkError() {
        val result = ErrorMapper.map(UnknownHostException("unknown host"))
        assertTrue(result is NetworkError)
        assertEquals(ErrorCode.NO_NETWORK, result.code)
    }

    @Test
    fun mapNoRouteToHostException_returnsNetworkError() {
        val result = ErrorMapper.map(NoRouteToHostException("no route"))
        assertTrue(result is NetworkError)
    }

    @Test
    fun mapConnectException_returnsNetworkError() {
        val result = ErrorMapper.map(ConnectException("connection refused"))
        assertTrue(result is NetworkError)
    }

    @Test
    fun mapSocketTimeoutException_returnsTimeoutError() {
        val result = ErrorMapper.map(SocketTimeoutException("timed out"))
        assertTrue(result is TimeoutError)
        assertEquals(ErrorCode.TIMEOUT, result.code)
    }

    @Test
    fun mapTimeoutException_returnsTimeoutError() {
        val result = ErrorMapper.map(TimeoutException("deadline exceeded"))
        assertTrue(result is TimeoutError)
    }

    @Test
    fun mapFileNotFoundException_returnsNotFound() {
        val result = ErrorMapper.map(FileNotFoundException("file not found"))
        assertTrue(result is NotFoundError)
        assertEquals(ErrorCode.NOT_FOUND, result.code)
    }

    @Test
    fun mapMalformedURLException_returnsInvalidUrl() {
        val result = ErrorMapper.map(MalformedURLException("malformed url"))
        assertTrue(result is InvalidUrlError)
        assertEquals(ErrorCode.INVALID_URL, result.code)
    }

    @Test
    fun mapSecurityException_returnsApiError403() {
        val result = ErrorMapper.map(SecurityException("permission denied"))
        assertTrue(result is ApiError)
        assertEquals(403, (result as ApiError).statusCode)
    }

    @Test
    fun mapGenericIOException_returnsUnknownError() {
        val result = ErrorMapper.map(IOException("some io error"))
        assertTrue(result is UnknownError)
    }

    @Test
    fun mapStorageFullIOException_returnsStorageFullError() {
        val result = ErrorMapper.map(IOException("No space left on device"))
        assertTrue(result is StorageFullError)
        assertEquals(ErrorCode.STORAGE_FULL, result.code)
    }

    @Test
    fun mapCorruptFileIOException_returnsCorruptFileError() {
        val result = ErrorMapper.map(IOException("decoder failed"))
        assertTrue(result is CorruptFileError)
    }

    @Test
    fun mapNetworkLikeIOException_returnsNetworkError() {
        val result = ErrorMapper.map(IOException("connection reset"))
        assertTrue(result is NetworkError)
    }

    // ── map(Throwable) by message heuristic ───────────────────────────────

    @Test
    fun classifyByMessage_privatePlaylist_returnsPrivatePlaylistError() {
        val t = RuntimeException("this playlist is private")
        val result = ErrorMapper.map(t)
        assertTrue(result is PrivatePlaylistError)
    }

    @Test
    fun classifyByMessage_rateLimit_returnsRateLimitError() {
        val t = RuntimeException("rate limit exceeded")
        val result = ErrorMapper.map(t)
        assertTrue(result is RateLimitError)
    }

    @Test
    fun classifyByMessage_tooManyRequests_returnsRateLimitError() {
        val t = RuntimeException("too many requests")
        val result = ErrorMapper.map(t)
        assertTrue(result is RateLimitError)
    }

    @Test
    fun classifyByMessage_http429_returnsRateLimitError() {
        val t = RuntimeException("http 429")
        val result = ErrorMapper.map(t)
        assertTrue(result is RateLimitError)
    }

    @Test
    fun classifyByMessage_notFound_returnsNotFoundError() {
        val t = RuntimeException("not found")
        val result = ErrorMapper.map(t)
        assertTrue(result is NotFoundError)
    }

    @Test
    fun classifyByMessage_couldntFind_returnsNotFoundError() {
        val t = RuntimeException("couldn't find the playlist")
        val result = ErrorMapper.map(t)
        assertTrue(result is NotFoundError)
    }

    @Test
    fun classifyByMessage_timeout_returnsTimeoutError() {
        val t = RuntimeException("timed out")
        val result = ErrorMapper.map(t)
        assertTrue(result is TimeoutError)
    }

    @Test
    fun classifyByMessage_deadlineExceeded_returnsTimeoutError() {
        val t = RuntimeException("deadline exceeded")
        val result = ErrorMapper.map(t)
        assertTrue(result is TimeoutError)
    }

    @Test
    fun classifyByMessage_serverError_returnsApiError500() {
        val t = RuntimeException("internal server error")
        val result = ErrorMapper.map(t)
        assertTrue(result is ApiError)
        assertEquals(500, (result as ApiError).statusCode)
    }

    @Test
    fun classifyByMessage_youtubeUnavailable_returnsDownloadFailed() {
        val t = RuntimeException("youtube video unavailable in your region")
        val result = ErrorMapper.map(t)
        assertTrue(result is DownloadFailedError)
    }

    @Test
    fun classifyByMessage_corruptFile_returnsCorruptFileError() {
        val t = RuntimeException("file is corrupt and cannot be read")
        val result = ErrorMapper.map(t)
        assertTrue(result is CorruptFileError)
    }

    @Test
    fun classifyByMessage_invalidUrl_returnsInvalidUrlError() {
        val t = IllegalArgumentException("invalid url format")
        val result = ErrorMapper.map(t)
        assertTrue(result is InvalidUrlError)
    }

    @Test
    fun classifyByMessage_enospc_returnsStorageFullError() {
        val t = RuntimeException("enospc")
        val result = ErrorMapper.map(t)
        assertTrue(result is StorageFullError)
    }

    @Test
    fun classifyByMessage_diskFull_returnsStorageFullError() {
        val t = RuntimeException("disk full")
        val result = ErrorMapper.map(t)
        assertTrue(result is StorageFullError)
    }

    @Test
    fun classifyByMessage_unknown_returnsUnknownError() {
        val t = RuntimeException("something random happened")
        val result = ErrorMapper.map(t)
        assertTrue(result is UnknownError)
    }

    // ── map(statusCode) ───────────────────────────────────────────────────

    @Test
    fun mapStatusCode_400_returnsInvalidUrl() {
        val result = ErrorMapper.map(400)
        assertTrue(result is InvalidUrlError)
    }

    @Test
    fun mapStatusCode_401_returnsApiError() {
        val result = ErrorMapper.map(401)
        assertTrue(result is ApiError)
        assertEquals(401, (result as ApiError).statusCode)
    }

    @Test
    fun mapStatusCode_403_returnsPrivatePlaylist() {
        val result = ErrorMapper.map(403)
        assertTrue(result is PrivatePlaylistError)
    }

    @Test
    fun mapStatusCode_404_returnsNotFound() {
        val result = ErrorMapper.map(404)
        assertTrue(result is NotFoundError)
    }

    @Test
    fun mapStatusCode_429_returnsRateLimit() {
        val result = ErrorMapper.map(429)
        assertTrue(result is RateLimitError)
    }

    @Test
    fun mapStatusCode_500_returnsApiError() {
        val result = ErrorMapper.map(500)
        assertTrue(result is ApiError)
        assertEquals(500, (result as ApiError).statusCode)
    }

    @Test
    fun mapStatusCode_503_returnsApiError() {
        val result = ErrorMapper.map(503)
        assertTrue(result is ApiError)
        assertEquals(503, (result as ApiError).statusCode)
    }

    @Test
    fun mapStatusCode_withDetailAndCause() {
        val cause = RuntimeException("original")
        val result = ErrorMapper.map(502, detail = "bad gateway", cause = cause)
        assertTrue(result is ApiError)
        assertEquals("bad gateway", result.detail)
    }

    @Test
    fun mapStatusCode_400_usesDefaultMessage() {
        val result = ErrorMapper.map(400)
        assertEquals("HTTP 400", result.detail)
    }

    // ── userMessage never exposes raw exception ───────────────────────────

    @Test
    fun userMessage_neverContainsRawExceptionText() {
        val t = RuntimeException("secret-internal-stack-trace-12345")
        val result = ErrorMapper.map(t)
        assertFalse(result.userMessage.contains("secret-internal-stack-trace-12345"))
    }

    // ── all AppErrors have non-blank userMessage ──────────────────────────

    @Test
    fun allAppErrorsHaveNonBlankUserMessage() {
        val errors = listOf(
            NetworkError(),
            RateLimitError(),
            PrivatePlaylistError(),
            TimeoutError(),
            NotFoundError(),
            ApiError(statusCode = 500),
            DownloadFailedError(),
            StorageFullError(),
            InvalidUrlError(),
            CorruptFileError(),
            EmptyPlaylistError(),
            NothingToPlayError(),
            CancelledError(),
            UnknownError(),
        )
        for (error in errors) {
            assertTrue(
                "AppError ${error.code} has blank userMessage",
                error.userMessage.isNotBlank(),
            )
        }
    }

    // ── map never returns null ────────────────────────────────────────────

    @Test
    fun mapNeverReturnsNull() {
        val throwables = listOf(
            RuntimeException("test"),
            IOException("io"),
            UnknownHostException("dns"),
            SocketTimeoutException("timeout"),
            FileNotFoundException("missing"),
            MalformedURLException("bad url"),
            SecurityException("no perms"),
            Exception("generic"),
        )
        for (t in throwables) {
            val result = ErrorMapper.map(t)
            assertNotNull("map should not return null for ${t::class.simpleName}", result)
        }
    }

    // ── illegal argument with URL-like message ────────────────────────────

    @Test
    fun mapIllegalArgumentException_withUrlMessage_returnsInvalidUrl() {
        val result = ErrorMapper.map(IllegalArgumentException("no protocol specified"))
        assertTrue(result is InvalidUrlError)
    }

    @Test
    fun mapIllegalArgumentException_withoutUrlMessage_returnsUnknown() {
        val result = ErrorMapper.map(IllegalArgumentException("bad value"))
        assertTrue(result is UnknownError)
    }

}

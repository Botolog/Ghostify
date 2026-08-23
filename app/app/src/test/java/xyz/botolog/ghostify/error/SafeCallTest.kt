package xyz.botolog.ghostify.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class SafeCallTest {

    // ── safeCall success ──────────────────────────────────────────────────

    @Test
    fun safeCall_successReturnsSuccessResult() {
        val result = safeCall { 42 }
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun safeCall_successWithNullReturn() {
        val result = safeCall { null }
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    // ── safeCall failure ──────────────────────────────────────────────────

    @Test
    fun safeCall_runtimeExceptionReturnsFailureWithAppError() {
        val result = safeCall { throw RuntimeException("boom") }
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is AppError)
    }

    @Test
    fun safeCall_illegalArgumentExceptionReturnsUnknownError() {
        val result = safeCall { throw IllegalArgumentException("bad arg") }
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as AppError
        assertEquals(ErrorCode.UNKNOWN, error.code)
    }

    @Test
    fun safeCall_ioExceptionReturnsAppError() {
        val result = safeCall { throw java.io.IOException("io failed") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AppError)
    }

    // ── safeCall rethrows CancellationException ───────────────────────────

    @Test(expected = CancellationException::class)
    fun safeCall_cancellationExceptionIsRethrown() {
        safeCall { throw CancellationException() }
    }

    // ── safeCall wraps exception in Result ────────────────────────────────

    @Test
    fun safeCall_exceptionDoesNotLeakDirectly() {
        val result = safeCall { throw RuntimeException("secret") }
        assertTrue(result.isFailure)
        // The exception in the Result is an AppError, not the original RuntimeException
        val ex = result.exceptionOrNull()
        assertTrue(ex is AppError)
    }

    // ── errorOrNull ───────────────────────────────────────────────────────

    @Test
    fun errorOrNull_successReturnsNull() {
        val result = Result.success("ok")
        assertNull(result.errorOrNull())
    }

    @Test
    fun errorOrNull_failureReturnsAppError() {
        val result = Result.failure<Any>(RuntimeException("boom"))
        val error = result.errorOrNull()
        assertNotNull(error)
        assertTrue(error is AppError)
    }

    @Test
    fun errorOrNull_appErrorFailureReturnsSameError() {
        val appError = NetworkError(detail = "test")
        val result = Result.failure<Any>(appError)
        val error = result.errorOrNull()
        assertNotNull(error)
        assertEquals(appError, error)
    }

    // ── getOrError ────────────────────────────────────────────────────────

    @Test
    fun getOrError_successReturnsDefault() {
        val default = UnknownError()
        val result = Result.success("ok")
        assertEquals(default, result.getOrError(default))
    }

    @Test
    fun getOrError_failureReturnsMappedError() {
        val default = UnknownError()
        val result = Result.failure<Any>(RuntimeException("boom"))
        val error = result.getOrError(default)
        assertNotNull(error)
        assertTrue(error is AppError)
    }

    @Test
    fun getOrError_appErrorFailureReturnsMappedAppError() {
        val default = UnknownError()
        val original = NetworkError(detail = "test")
        val result = Result.failure<Any>(original)
        assertEquals(original, result.getOrError(default))
    }

    // ── Throwable.toAppError ──────────────────────────────────────────────

    @Test
    fun toAppError_runtimeExceptionReturnsAppError() {
        val error = RuntimeException("boom").toAppError()
        assertNotNull(error)
        assertTrue(error is AppError)
    }

    @Test
    fun toAppError_networkExceptionReturnsNetworkError() {
        val error = java.net.UnknownHostException("dns").toAppError()
        assertTrue(error is NetworkError)
    }

    @Test
    fun toAppError_appErrorReturnsSameInstance() {
        val original = RateLimitError()
        assertEquals(original, original.toAppError())
    }

    @Test
    fun toAppError_cancellationExceptionReturnsCancelledError() {
        val error = CancellationException("cancelled").toAppError()
        assertTrue(error is CancelledError)
    }
}

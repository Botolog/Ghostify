package xyz.botolog.ghostify.error

import java.util.concurrent.CancellationException
import timber.log.Timber

/**
 * Boundary helpers. Guarantee: any failure that crosses an app boundary becomes a typed
 * [AppError] — or, for coroutine cancellation, is rethrown so structured concurrency is
 * preserved. A raw exception can never leak to the UI through these helpers.
 */

/** Run [block] and convert any failure to a typed [AppError]. Cancellation is rethrown. */
inline fun <T> safeCall(crossinline block: () -> T): Result<T> {
    Timber.i("safeCall: START")
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Timber.e(t, "safeCall: block FAILED")
        Result.failure(ErrorMapper.map(t))
    }
}

/** Suspend variant of [safeCall]. Cancellation is rethrown. */
suspend fun <T> safeSuspend(block: suspend () -> T): Result<T> {
    Timber.i("safeSuspend: START")
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Timber.e(t, "safeSuspend: block FAILED")
        Result.failure(ErrorMapper.map(t))
    }
}

/** Returns the typed error of this [Result], or null on success. Never throws. */
fun <T> Result<T>.errorOrNull(): AppError? {
    Timber.i("Result.errorOrNull: START")
    val result = exceptionOrNull()?.let { ErrorMapper.map(it) }
    Timber.i("Result.errorOrNull: returning $result")
    return result
}

/** Converts a failure to an [AppError], returning [default] on success. */
fun <T> Result<T>.getOrError(default: AppError): AppError {
    Timber.i("Result.getOrError: START")
    val result = errorOrNull() ?: default
    Timber.i("Result.getOrError: returning $result")
    return result
}

/** Maps any [Throwable] to a typed [AppError]; never throws. */
fun Throwable.toAppError(): AppError {
    Timber.i("Throwable.toAppError: START")
    val result = ErrorMapper.map(this)
    Timber.i("Throwable.toAppError: returning $result")
    return result
}

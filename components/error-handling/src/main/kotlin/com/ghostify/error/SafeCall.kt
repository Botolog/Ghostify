package com.ghostify.error

import java.util.concurrent.CancellationException

/**
 * Boundary helpers. Guarantee: any failure that crosses an app boundary becomes a typed
 * [AppError] — or, for coroutine cancellation, is rethrown so structured concurrency is
 * preserved. A raw exception can never leak to the UI through these helpers.
 */

/** Run [block] and convert any failure to a typed [AppError]. Cancellation is rethrown. */
inline fun <T> safeCall(crossinline block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(ErrorMapper.map(t))
    }

/** Suspend variant of [safeCall]. Cancellation is rethrown. */
suspend fun <T> safeSuspend(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(ErrorMapper.map(t))
    }

/** Returns the typed error of this [Result], or null on success. Never throws. */
fun <T> Result<T>.errorOrNull(): AppError? =
    exceptionOrNull()?.let { ErrorMapper.map(it) }

/** Converts a failure to an [AppError], returning [default] on success. */
fun <T> Result<T>.getOrError(default: AppError): AppError =
    errorOrNull() ?: default

/** Maps any [Throwable] to a typed [AppError]; never throws. */
fun Throwable.toAppError(): AppError = ErrorMapper.map(this)

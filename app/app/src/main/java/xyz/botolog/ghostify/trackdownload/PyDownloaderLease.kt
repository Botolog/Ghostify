package xyz.botolog.ghostify.trackdownload

import com.chaquo.python.PyObject
import timber.log.Timber
import xyz.botolog.ghostify.perf.DownloadLeakTracker
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the acquire/release lifecycle of a single, freshly-created Python
 * `TrackDownloader` (a Chaquopy `PyObject`, which is an [AutoCloseable]).
 *
 * Each call into [TrackDownloadBridge] used to `make_downloader(...)` and then
 * drop the handle on the floor. Those handles hold native references into the
 * Chaquopy interpreter (`libpython3.11.so`), so they accumulated in the native
 * heap until the process was killed — the real-device symptom was a 370–700 MB
 * native heap with a tiny Java heap.
 *
 * The lease is generic over the handle type so its lifecycle can be unit-tested
 * on the JVM with a plain [AutoCloseable] fake: `PyObject`'s `toString`/`equals`/
 * `hashCode` are `native` and its constructor is private, so it can neither be
 * faked nor mocked-and-verified without a working interpreter.
 *
 * A lease guarantees:
 *  - the native handle is released **exactly once** ([close] and [cancel] race
 *    through a single state CAS, so only one of them releases);
 *  - a released handle can never be used again ([use] fails fast instead of
 *    corrupting the interpreter);
 *  - a failure while releasing is swallowed and logged, so disposing can never
 *    turn a completed/failed download into an unhandled crash;
 *  - before the handle is closed, a Chaquopy [PyObject] handle gets its
 *    Python-side `shutdown()` invoked **exactly once** (best-effort; any error
 *    is swallowed) so the downloader's asyncio loop and heavy native state are
 *    torn down deterministically instead of piling up until the process dies;
 *  - the optional [DownloadLeakTracker] balance is kept (`open` on acquire,
 *    `close` on release) so the T-166 "closed exactly once per use" contract can
 *    be asserted in tests.
 *
 * @param H the owned handle type; `PyObject` in production.
 * @property downloader the handle this lease owns.
 * @property tracker optional lifecycle tracker (see `perf/jvm/DownloadLeakTracker`).
 * @property ownerTag tag used for leak tracking.
 */
internal class PyDownloaderLease<H : AutoCloseable>(
    private val downloader: H,
    private val tracker: DownloadLeakTracker? = null,
    private val ownerTag: String = DEFAULT_OWNER_TAG,
) : AutoCloseable {

    private enum class State { ACQUIRED, IN_USE, CLOSED }

    private val state = AtomicReference(State.ACQUIRED)
    private val leakId: String? = tracker?.open(ownerTag)

    /** The owned handle. Throws if the lease has already been released or is in use. */
    fun use(): H {
        check(state.compareAndSet(State.ACQUIRED, State.IN_USE)) {
            "Python downloader lease already disposed or in use (owner=$ownerTag)"
        }
        return downloader
    }

    /** True once the lease has been released. */
    val isClosed: Boolean get() = state.get() == State.CLOSED

    /**
     * Releases the native handle. Safe to call more than once: only the first
     * call releases, later calls are ignored (no double-close) and never throw.
     */
    override fun close() {
        if (state.compareAndSet(State.ACQUIRED, State.CLOSED) ||
            state.compareAndSet(State.IN_USE, State.CLOSED)
        ) {
            release()
        } else {
            Timber.w("PyDownloaderLease.close: already disposed (owner=$ownerTag), ignoring")
        }
    }

    /**
     * Cancellation release. If the handle has not been taken for use yet it is
     * released immediately; if it is already in use the owner's `finally` releases
     * it, so a running Python call can never have its handle yanked underneath it
     * (no use-after-close). Idempotent with [close].
     */
    fun cancel() {
        if (state.compareAndSet(State.ACQUIRED, State.CLOSED)) {
            release()
        } else {
            Timber.d("PyDownloaderLease.cancel: deferring release to owner (owner=$ownerTag)")
        }
    }

    private fun release() {
        disposeHandle(downloader, ownerTag)
        leakId?.let { tracker?.close(it) }
    }

    companion object {
        /** Default owner tag used by [TrackDownloadBridge]. */
        const val DEFAULT_OWNER_TAG = "track-download"
    }
}

/**
 * Releases a native handle. Never throws: a failure here must not mask the
 * download result or crash the caller that is already unwinding.
 *
 * Before the handle is closed, a Chaquopy [PyObject] handle gets its Python-side
 * `shutdown()` invoked (which closes the downloader's asyncio event loop and
 * drops its heavy references). Only [PyObject] exposes that method, so a plain
 * [AutoCloseable] fake (unit tests) is left untouched; a missing method (older
 * downloaders) or any Python error is swallowed and logged. This is called only
 * from the winning CLOSED transition in [PyDownloaderLease], so it runs **at
 * most once** per handle.
 *
 * @param handle the handle to release.
 * @param ownerTag tag used only for logging.
 */
internal fun disposeHandle(handle: AutoCloseable, ownerTag: String) {
    if (handle is PyObject) {
        runCatching { handle.callAttr("shutdown") }
            .onFailure {
                Timber.w(it, "disposeHandle: Python shutdown() failed (owner=$ownerTag)")
            }
    }
    try {
        handle.close()
    } catch (e: Exception) {
        Timber.w(e, "disposeHandle: failed to close Python downloader (owner=$ownerTag)")
    }
}

/**
 * Runs [block] with a fresh handle produced by [factory], guaranteeing the handle
 * is released exactly once afterwards — on success, on failure, and on
 * cancellation (the `finally` always runs).
 *
 * Extracted so the release contract can be unit-tested on the JVM with a fake
 * [AutoCloseable] handle and a recording [DownloadLeakTracker], without a Python
 * interpreter.
 *
 * @param H the handle type (`PyObject` in production).
 * @param tracker optional lifecycle tracker.
 * @param ownerTag leak-tracker owner tag.
 * @param onAcquired invoked with the lease right after acquisition (e.g. to
 *   register it for cancellation).
 * @param onReleased invoked with the lease just before it is released (e.g. to
 *   deregister it from cancellation).
 * @param factory creates the handle.
 * @param block uses the handle.
 * @return the value returned by [block].
 */
internal inline fun <H : AutoCloseable, T> withPyDownloaderLease(
    tracker: DownloadLeakTracker? = null,
    ownerTag: String = PyDownloaderLease.DEFAULT_OWNER_TAG,
    onAcquired: (PyDownloaderLease<H>) -> Unit = {},
    onReleased: (PyDownloaderLease<H>) -> Unit = {},
    factory: () -> H,
    block: (H) -> T,
): T {
    val lease = PyDownloaderLease(factory(), tracker, ownerTag)
    onAcquired(lease)
    try {
        return block(lease.use())
    } finally {
        onReleased(lease)
        lease.close()
    }
}

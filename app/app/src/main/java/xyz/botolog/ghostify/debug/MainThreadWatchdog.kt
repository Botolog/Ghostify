package xyz.botolog.ghostify.debug

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import xyz.botolog.ghostify.BuildConfig

/**
 * Debug-only main-thread ("UI thread") watchdog.
 *
 * "Ghostify isn't responding" ANRs leave no trace in the crash handler (which only
 * sees uncaught exceptions) and are often silent in logcat too.
 * This watchdog fills that gap: a single daemon [ScheduledExecutorService] posts a
 * trivial probe [Runnable] to the main [Looper] roughly once a second. If the main
 * thread fails to consume the probe within [STALL_THRESHOLD_MS], the UI thread is
 * treated as stalled: its current stack trace is captured and logged via Timber so
 * the report also lands in `filesDir/logs/`.
 *
 * Release safety: [install] is a no-op unless `BuildConfig.DEBUG` is true, and every
 * step is wrapped so a watchdog failure can never affect the running app.
 */
object MainThreadWatchdog {

    private const val TAG = "MainThreadWatchdog"
    private const val THREAD_NAME = "MainThreadWatchdog"

    /** How often the probe is (re)posted while the main thread is healthy. */
    private const val TICK_INTERVAL_MS = 1_000L

    /** A posted probe that has not run after this long counts as a stall. */
    private const val STALL_THRESHOLD_MS = 3_000L

    /** Never emit more than one stall report per this window. */
    private const val REPORT_THROTTLE_MS = 5_000L

    private val installed = AtomicBoolean(false)

    @Volatile
    private var executor: ScheduledExecutorService? = null

    @Volatile
    private var mainHandler: Handler? = null

    /** Uptime (ms) at which the in-flight probe was posted, or 0 before install. */
    private val postedAt = AtomicLong(0L)

    /** Uptime (ms) at which the last probe actually ran on the main thread. */
    private val ranAt = AtomicLong(0L)

    /** Uptime (ms) of the last emitted stall report, for throttling. */
    private val lastReportAt = AtomicLong(0L)

    /** Starts the watchdog. Safe to call repeatedly and a no-op outside debug builds. */
    fun install() {
        if (!BuildConfig.DEBUG) return
        if (!installed.compareAndSet(false, true)) return
        try {
            val looper = Looper.getMainLooper() ?: run {
                installed.set(false)
                return
            }
            mainHandler = Handler(looper)
            val now = SystemClock.uptimeMillis()
            postedAt.set(now)
            ranAt.set(now)
            lastReportAt.set(0L)
            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, THREAD_NAME).apply { isDaemon = true }
            }
            executor = scheduler
            scheduler.scheduleWithFixedDelay(
                { tick() },
                TICK_INTERVAL_MS,
                TICK_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            Timber.tag(TAG).i("MainThreadWatchdog installed")
        } catch (t: Throwable) {
            installed.set(false)
            executor = null
            mainHandler = null
            Timber.tag(TAG).w(t, "MainThreadWatchdog install failed")
        }
    }

    /** Stops the watchdog and releases its daemon thread. Safe to call when not installed. */
    fun stop() {
        if (!installed.compareAndSet(true, false)) return
        mainHandler = null
        try {
            executor?.shutdownNow()
        } catch (_: Throwable) {
            // ignore: stopping a watchdog must never throw
        }
        executor = null
    }

    /** Runs on the scheduler thread; never touches the main thread except to post. */
    private fun tick() {
        try {
            val now = SystemClock.uptimeMillis()
            val posted = postedAt.get()
            val ran = ranAt.get()
            if (posted > ran) {
                // Probe posted but not yet consumed: the main thread is (likely) blocked.
                val lateBy = now - posted
                if (lateBy >= STALL_THRESHOLD_MS) reportStall(lateBy)
                // Do NOT queue another probe — keep measuring the same in-flight one.
            } else {
                val delay = ran - posted
                if (delay >= STALL_THRESHOLD_MS) reportStall(delay)
                postProbe()
            }
        } catch (t: Throwable) {
            // A watchdog must never take the app down.
            Timber.tag(TAG).w(t, "MainThreadWatchdog tick failed")
        }
    }

    private fun postProbe() {
        val handler = mainHandler ?: return
        postedAt.set(SystemClock.uptimeMillis())
        handler.post { ranAt.set(SystemClock.uptimeMillis()) }
    }

    /** Captures the main thread's stack and logs it, throttled to once per window. */
    private fun reportStall(stallMs: Long) {
        val now = SystemClock.uptimeMillis()
        if (now - lastReportAt.get() < REPORT_THROTTLE_MS) return
        lastReportAt.set(now)

        val frames = Looper.getMainLooper()?.thread?.stackTrace
        val message = buildString {
            append("Main thread stalled for ")
            append(stallMs)
            append(" ms (threshold ")
            append(STALL_THRESHOLD_MS)
            append(" ms). Main-thread stack at detection:")
            if (frames.isNullOrEmpty()) {
                append("\n\t<no stack trace available>")
            } else {
                for (frame in frames) {
                    append("\n\tat ")
                    append(frame)
                }
            }
        }
        Timber.tag(TAG).w(message)
    }
}

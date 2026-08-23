package xyz.botolog.ghostify.perf

/**
 * Coalescing throttle for high-frequency progress events (T-167, T-163).
 *
 * Download progress can arrive tens of times per second (per-track hooks, byte
 * counters). Pushing every raw event into a Compose `State` triggers a
 * recomposition per event — needless CPU, battery drain, and potential frame
 * jank. The correct shape is: **coalesce in the producer, emit at most one
 * value per interval, force-flush the latest value on completion.**
 *
 * ```
 * val throttle = CoalescingThrottle(minIntervalMillis = 500L)
 * progressFlow.collect { pct ->
 *     throttle.offer(pct)?.let { uiState.update(it) }   // at most 2 updates/sec
 * }
 * throttle.flush()?.let { uiState.update(it) }          // final value
 * ```
 *
 * The throttle never spins, sleeps, or loops — a pure state machine, which is
 * exactly what the battery budget wants from background work.
 *
 * Pure JVM — unit-tested in `UiBusyLoopGuardTest`.
 *
 * @param minIntervalMillis minimum milliseconds between emissions (default 500).
 * @param clock a time source returning epoch millis (default [System.currentTimeMillis]).
 */
class CoalescingThrottle(
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var lastEmittedAt = Long.MIN_VALUE
    private var pending: Double? = null

    /** Number of values actually released to the UI. */
    var emissionCount: Int = 0
        private set

    /**
     * Feed one raw value. Returns the value to emit *now*, or null if it is
     * coalesced into the next emission (the latest pending value wins).
     */
    fun offer(value: Double): Double? {
        require(value >= 0.0) { "progress must be >= 0, got $value" }
        pending = value
        val now = clock()
        if (lastEmittedAt == Long.MIN_VALUE || now - lastEmittedAt >= minIntervalMillis) {
            lastEmittedAt = now
            emissionCount++
            val emitted = pending
            pending = null
            return emitted
        }
        return null
    }

    /**
     * Force-release the last pending value (call on completion/error). Returns
     * null if there is nothing new to emit.
     */
    fun flush(): Double? {
        val value = pending
        pending = null
        return value
    }

    companion object {
        /** UI progress updates more often than this add no user-visible value. */
        const val DEFAULT_MIN_INTERVAL_MILLIS = 500L
    }
}

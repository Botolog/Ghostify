package com.ghostify.perf

/**
 * Lifecycle tracker for Python-bridge "wrapper" objects (T-166).
 *
 * The memory budget hinges on one rule: a bridge wrapper (the object that owns
 * a spotdl downloader session / a Chaquopy interpreter handle) must be closed
 * exactly once per use. This tracker models that contract so the leak test and
 * the production download manager can both assert it:
 *
 * ```
 * val id = tracker.open("downloadAll:p1")   // wrapper acquired
 * ... run the download ...
 * tracker.close(id)                          // wrapper released
 * check(tracker.retained("downloadAll:p1").isEmpty())
 * ```
 *
 * `retainedCount() == 0` after a workload is the leak assertion, and it is the
 * counter the heap-growth test correlates with time.
 *
 * Pure JVM — unit-tested in `DownloadLeakTrackerTest`.
 */
data class RetainedWrapper(
    val id: String,
    val ownerTag: String,
    val openedAtMillis: Long,
)

class DownloadLeakTracker(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val active = linkedMapOf<String, RetainedWrapper>()
    private var nextId = 0

    /** Record a wrapper acquisition and return its unique id. */
    fun open(ownerTag: String): String {
        val id = "$ownerTag#${nextId++}"
        active[id] = RetainedWrapper(id, ownerTag, clock())
        return id
    }

    /** Release a wrapper. Returns true if it was actually tracked (leak vs. double-close). */
    fun close(id: String): Boolean = active.remove(id) != null

    /** Release every wrapper for a given owner; returns how many were released. */
    fun closeAll(ownerTag: String): Int {
        val ids = active.filterValues { it.ownerTag == ownerTag }.keys.toList()
        ids.forEach { active.remove(it) }
        return ids.size
    }

    /** Wrappers still held for [ownerTag] — non-empty means a leak. */
    fun retained(ownerTag: String): List<RetainedWrapper> =
        active.values.filter { it.ownerTag == ownerTag }

    /** Total wrappers still held. */
    fun retainedCount(): Int = active.size

    /** owner tag -> count of retained wrappers. */
    fun snapshot(): Map<String, Int> =
        active.values.groupingBy { it.ownerTag }.eachCount()
}

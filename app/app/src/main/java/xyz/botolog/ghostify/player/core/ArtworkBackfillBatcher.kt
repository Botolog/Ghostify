package xyz.botolog.ghostify.player.core

import timber.log.Timber

/**
 * Pure decision policy for publishing player snapshots while artwork is backfilled.
 *
 * Publishing a full snapshot per extracted artwork would churn the UI during large-queue
 * backfills, so publishes are batched: one flush per [batchSize] buffered extractions or every
 * [intervalMs], whichever comes first. Artwork landing on the song that is *currently* playing
 * always publishes immediately so the now-playing view fills without waiting for a batch
 * boundary (the player also republishes on every transition, which covers future tracks).
 *
 * Pure and unit-testable: inject [timeSourceMs] for a deterministic clock.
 *
 * @param batchSize number of buffered extractions between snapshot publishes.
 * @param intervalMs maximum wall-clock gap between snapshot publishes, in milliseconds.
 * @param timeSourceMs monotonic-ish wall clock used to measure publish gaps.
 */
class ArtworkBackfillBatcher(
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val timeSourceMs: () -> Long = System::currentTimeMillis,
) {

    init {
        require(batchSize > 0) { "batchSize must be positive, was $batchSize" }
        require(intervalMs > 0L) { "intervalMs must be positive, was $intervalMs" }
    }

    private var bufferedSincePublish = 0
    private var lastPublishAtMs = timeSourceMs()

    /**
     * Records one extracted artwork and decides whether the UI snapshot should be published.
     *
     * @param extractedMediaId media ID whose artwork was just extracted.
     * @param currentMediaId media ID currently playing, or `null` if unknown.
     * @return `true` when a snapshot publish is due now.
     */
    fun shouldPublish(extractedMediaId: String, currentMediaId: String?): Boolean {
        if (extractedMediaId == currentMediaId) {
            Timber.d("ArtworkBackfillBatcher.shouldPublish: immediate flush for current item")
            markPublished()
            return true
        }
        bufferedSincePublish++
        val due = bufferedSincePublish >= batchSize ||
            timeSourceMs() - lastPublishAtMs >= intervalMs
        if (due) {
            Timber.d(
                "ArtworkBackfillBatcher.shouldPublish: batch flush after $bufferedSincePublish " +
                    "buffered extraction(s)",
            )
            markPublished()
        }
        return due
    }

    /** Resets the buffer and restarts the interval window at the current time. */
    private fun markPublished() {
        bufferedSincePublish = 0
        lastPublishAtMs = timeSourceMs()
    }

    companion object {
        /** Number of buffered extractions between snapshot publishes. */
        const val DEFAULT_BATCH_SIZE = 5

        /** Maximum wall-clock gap between snapshot publishes, in milliseconds. */
        const val DEFAULT_INTERVAL_MS = 200L
    }
}

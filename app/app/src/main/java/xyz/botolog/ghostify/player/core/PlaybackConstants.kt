package xyz.botolog.ghostify.player.core

/**
 * Pure constants used by the player core that mirror public Media3 values.
 *
 * Keeping them here (rather than depending on `androidx.media3.common.C`) keeps the
 * `core` package free of Android dependencies so the whole logic layer is unit-testable
 * on a plain JVM. The values below are part of Media3's stable public API.
 */
object PlaybackConstants {

    /** Mirrors `androidx.media3.common.C.TIME_UNSET`. */
    const val TIME_UNSET: Long = Long.MIN_VALUE + 1
}

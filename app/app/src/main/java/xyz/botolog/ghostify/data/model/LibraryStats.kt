package xyz.botolog.ghostify.data.model

/**
 * Whole-library aggregate counts, as shown by the "Library" home-screen widget.
 *
 * A deliberately tiny, immutable value with **no Android types**, so the mapping and the
 * widget's formatting stay JVM-testable. It is produced by
 * [xyz.botolog.ghostify.data.repo.LibraryStatsRepository], which combines the individual
 * `COUNT(*)` DAO queries; the widgets never query Room for these numbers themselves.
 *
 * @property totalSongs every row in the `songs` table.
 * @property downloadedSongs rows whose status is `DOWNLOADED` (playable offline).
 * @property songsWithLyrics rows carrying non-blank lyrics text.
 * @property playlists every row in the `playlists` table.
 */
data class LibraryStats(
    val totalSongs: Int,
    val downloadedSongs: Int,
    val songsWithLyrics: Int,
    val playlists: Int,
) {

    /**
     * Fraction of the library that is downloaded, in `0f..1f`, for the widget's progress
     * bar. Returns `0f` for an empty library instead of dividing by zero.
     */
    val downloadedFraction: Float
        get() = if (totalSongs <= 0) 0f else (downloadedSongs.toFloat() / totalSongs.toFloat()).coerceIn(0f, 1f)

    /** Percentage of the library that is downloaded, `0..100`, rounded down. */
    val downloadedPercent: Int
        get() = (downloadedFraction * 100f).toInt().coerceIn(0, 100)

    companion object {
        /** An empty library (fresh install / nothing imported yet). */
        val EMPTY = LibraryStats(totalSongs = 0, downloadedSongs = 0, songsWithLyrics = 0, playlists = 0)
    }
}

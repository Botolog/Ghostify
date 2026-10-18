package xyz.botolog.ghostify.widget

import kotlin.math.abs

/**
 * Pure lyric-handling logic for the large "Lyrics" widget (pass 2).
 *
 * Everything here is Android-free so it can be exercised on the JVM. The composable in
 * `LyricsWidget.kt` only paints what these functions return.
 */

/**
 * Number of lyric rows the 4x4 Lyrics widget shows at once.
 *
 * Sized to what the real ~250x250dp cell can actually hold: header + progress +
 * transport leave room for about five 13-16sp rows, so five is the count that fits
 * without clipping the top/bottom of the window.
 *
 * **Must stay `<=` [WIDGET_COLUMN_MAX_CHILDREN]**: every row is a *single* Glance
 * child of one `Column` (the previous design emitted a `Text` + a `Spacer` per row,
 * which blew past the RemoteViews cap and silently dropped the highlighted line).
 */
const val LYRICS_WINDOW_SIZE: Int = 5

/**
 * Hard cap the Glance RemoteViews backend imposes on a `Column`'s **direct children**.
 *
 * Exceeding it makes Glance log
 * `Column container cannot have more than 10 elements … Truncated Column container from
 * N to 10 elements` and silently drop the overflow — which is exactly what hid the
 * highlighted lyric line. Keeping the window at/below this bound keeps every row.
 */
const val WIDGET_COLUMN_MAX_CHILDREN: Int = 10

/**
 * One rendered lyric row: its [index] in the full list, its [text], and whether it is
 * the line currently being sung (the one to highlight).
 */
data class LyricsWindowLine(
    val index: Int,
    val text: String,
    val isCurrent: Boolean,
)

/**
 * Splits raw stored lyrics into static display lines for the plain-text fallback.
 *
 * Used only when `parseLrc` finds no timestamps (plain lyrics from
 * genius/azlyrics/musixmatch). Every line is trimmed and blank lines are dropped, so a
 * tidy block of text is rendered without any highlight. Pure.
 */
fun plainLyricsLines(raw: String?): List<String> =
    raw?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

/**
 * Chooses the rows the widget paints: the synced (timestamped) lines when they exist,
 * otherwise the static plain-text fallback. Pure.
 *
 * @param syncedLineTexts text of the timestamped LRC lines; empty when the track has no
 *   synced lyrics.
 * @param rawLyrics the untrimmed stored lyrics, consulted only for the fallback.
 */
fun widgetLyricsLines(syncedLineTexts: List<String>, rawLyrics: String?): List<String> =
    if (syncedLineTexts.isNotEmpty()) syncedLineTexts else plainLyricsLines(rawLyrics)

/**
 * The window of lyric rows to render, centred on [currentLineIndex] where possible and
 * clamped at the top/bottom of [lines]. Pure and JVM-testable.
 *
 * Centring keeps the sung line at the middle of the returned window (index
 * `windowSize / 2`), so it lands in the vertical middle of the widget body. Near the
 * start/end of a song the window slides until it hits the bounds of [lines]; the active
 * line then moves toward the edge of the window but is **always included** — it is never
 * scrolled out of view.
 *
 * @param lines the full, ordered lyric lines.
 * @param currentLineIndex the active line, or `-1` for plain/unhighlighted lyrics.
 *   Out-of-range values behave like `-1` (nothing is highlighted).
 * @param windowSize maximum number of rows to return (values below 1 are treated as 1).
 *   Keep this `<=` [WIDGET_COLUMN_MAX_CHILDREN] because each returned row is one
 *   `Column` child.
 */
fun lyricsWindow(
    lines: List<String>,
    currentLineIndex: Int = -1,
    windowSize: Int = LYRICS_WINDOW_SIZE,
): List<LyricsWindowLine> {
    if (lines.isEmpty()) return emptyList()
    val size = windowSize.coerceAtLeast(1)
    val hasActive = currentLineIndex in lines.indices
    val start = if (!hasActive) {
        0
    } else {
        (currentLineIndex - size / 2).coerceIn(0, (lines.size - size).coerceAtLeast(0))
    }
    val end = (start + size).coerceAtMost(lines.size)
    return (start until end).map { index ->
        LyricsWindowLine(
            index = index,
            text = lines[index],
            isCurrent = hasActive && index == currentLineIndex,
        )
    }
}

/**
 * Signed distance of the row at [index] from the active line [currentLineIndex], used to
 * fade neighbouring lyric lines in the karaoke view. Pure.
 *
 * `0` is the currently sung line. A positive/negative value is a line after/before it.
 * Returns `-1` when nothing is highlighted (no active line), which the widget paints as
 * a plain, unhighlighted row.
 */
fun lyricRowDistance(index: Int, currentLineIndex: Int): Int =
    if (currentLineIndex < 0) -1 else abs(index - currentLineIndex)

package xyz.botolog.ghostify.widget

/**
 * Pure, Android-free size→layout logic shared by the two widgets.
 *
 * Both widgets opt into `SizeMode.Exact` (see the `GlanceAppWidget` subclasses), so Glance
 * re-runs the composition with the *real* widget size on every host resize and exposes it
 * through `androidx.glance.LocalSize`. The composables feed that size (in dp) into the
 * functions here and paint whatever they return.
 *
 * Keeping the decision-making here — no `Dp`, no `Glance`, no `Context` — means the
 * breakpoints are exercised on the JVM by `WidgetLayoutTest` instead of only being
 * eyeballed on a device, and the composables stay a thin mapping of numbers to Glance
 * modifiers.
 */

/**
 * Average glyph width of the app's UI type, as a fraction of the text size.
 *
 * Glance 1.1's `Text` has no `TextOverflow`, so a widget cannot ask the platform to
 * ellipsize: it would simply clip. This ratio turns an available width into a character
 * budget so the composables can truncate explicitly (see [ellipsize]) — which also makes
 * the behaviour JVM-testable.
 */
internal const val WIDGET_CHAR_WIDTH_RATIO: Float = 0.5f

/**
 * Average glyph width of a *count* line ("1,234 tracks"), a hair under the UI average: the
 * text is digits and lowercase, so budgeting it at [WIDGET_CHAR_WIDTH_RATIO] would cut a
 * character that in fact fits (e.g. "128 tracks" beside a cover on the 6x2 cell).
 */
internal const val WIDGET_COUNT_CHAR_WIDTH_RATIO: Float = 0.45f

/** Characters of `sp`-sized text that fit in [widthDp]; always at least 1. */
fun textCharBudget(widthDp: Int, sp: Int, ratio: Float = WIDGET_CHAR_WIDTH_RATIO): Int {
    if (widthDp <= 0 || sp <= 0) return 1
    return (widthDp / (sp * ratio)).toInt().coerceAtLeast(1)
}

/**
 * Truncates [text] to at most [maxChars] characters with a trailing ellipsis, trimming
 * surrounding whitespace. Pure.
 */
fun ellipsize(text: String, maxChars: Int): String {
    val trimmed = text.trim()
    if (maxChars <= 0) return ""
    if (trimmed.length <= maxChars) return trimmed
    if (maxChars == 1) return "…"
    return trimmed.take(maxChars - 1).trimEnd() + "…"
}

/** Which of the player's deliberately distinct compositions should be painted. */
enum class PlayerLayoutKind {
    /** One row, no progress: 2x1 / 4x1 (short cells). */
    SHORT,

    /** Small square: cover, title, a single play button. 2x2 / 110x110dp. */
    COMPACT,

    /** The 4x2 reference design, tuned. */
    STANDARD,

    /**
     * Extra width is put to work: bigger cover, `artist · album` on one line, spread-out
     * times. 6x2.
     */
    WIDE,

    /** Tall cells (4x3, 4x4): scaled-up, vertically centred, no dead space. */
    TALL,
}

/**
 * Concrete metrics + feature flags for one player composition. Every length is in dp and
 * every text size in sp so the value is trivially JVM-testable and cheap to compare.
 *
 * @property kind which composition the composable paints.
 * @property coverDp square cover-art side.
 * @property titleSp / [artistSp] / [albumSp] text sizes.
 * @property showText whether the title/artist column is painted at all (dropped when the
 *   row is too narrow for any legible text, e.g. a 2x1 cell).
 * @property showArtist whether the artist line is painted (title only when very tight).
 * @property showAlbum whether the album is painted as its **own** stacked line. The wide
 *   cell carries it inline on the artist line instead (see [playerSecondaryLine]) because
 *   it is exactly as tall as the 4x2 cell.
 * @property showProgress whether the thin progress bar is painted.
 * @property showTimes whether elapsed/duration are painted.
 * @property spreadTimes `true` to push elapsed left and duration right (wide/tall), rather
 *   than the compact `elapsed / duration` cluster (standard).
 * @property showSkip whether prev/next flank the play button.
 * @property skipDp / [playDp] transport button diameters.
 * @property timeSp elapsed/duration text size.
 */
data class PlayerLayout(
    val kind: PlayerLayoutKind,
    val coverDp: Int,
    val titleSp: Int,
    val artistSp: Int,
    val albumSp: Int,
    val showText: Boolean,
    val showArtist: Boolean,
    val showAlbum: Boolean,
    val showProgress: Boolean,
    val showTimes: Boolean,
    val spreadTimes: Boolean,
    val showSkip: Boolean,
    val skipDp: Int,
    val playDp: Int,
    val timeSp: Int,
)

/**
 * Picks the player composition for a widget of [widthDp] x [heightDp] (dp, the *whole*
 * widget surface including its own padding).
 *
 * The ordering of the branches is the priority: a genuinely short cell is always the
 * one-row layout even if it is also wide, and a tall cell is always the scaled-up column
 * even if it is wide. Only then do width-driven variants (wide, small-square) apply, and
 * everything else falls back to the tuned 4x2 design.
 *
 * Sizes are the widget's **content box** (what a launcher reports after removing its own
 * gutter), not the raw launcher cell. The lab measures and reports the same box.
 *
 * Thresholds (dp):
 *  - `height < 88`            → [PlayerLayoutKind.SHORT]   (4x1 ≈ 44 content)
 *  - `height >= 150`          → [PlayerLayoutKind.TALL]    (4x3 ≈ 164, 4x4 ≈ 234)
 *  - `width >= 320`           → [PlayerLayoutKind.WIDE]    (6x2 ≈ 374)
 *  - `width < 232 || h < 90`  → [PlayerLayoutKind.COMPACT] (2x2 ≈ 94x94, 3x2 ≈ 164x94)
 *  - otherwise                → [PlayerLayoutKind.STANDARD](4x2 ≈ 234x94)
 */
fun playerLayout(widthDp: Int, heightDp: Int): PlayerLayout {
    val w = widthDp.coerceAtLeast(1)
    val h = heightDp.coerceAtLeast(1)
    return when {
        h < SHORT_MAX_HEIGHT -> {
            val contentH = h - OUTER_PADDING_DP * 2
            val cover = contentH.coerceIn(24, 52)
            // The transport must fit the *padded* height. At the 2x1/4x1 reference the
            // content box is only 28dp, so the old fixed 30dp play button was sliced flat
            // top-and-bottom by the Row (it rendered as a 30x27dp ellipse). Deriving it
            // from the box keeps the circle round at every short height.
            val play = (contentH - 2).coerceIn(20, 30)
            val skip = 22
            val showSkip = w >= 196
            // Width left for the text column once the cover, spacers and transport are
            // placed. A 2x1 (110dp) cell leaves ~6dp, so the text is dropped entirely.
            val textBudget = (w - 16) - (cover + 8 + 6 + play) -
                (if (showSkip) 2 * (skip + 4) else 0)
            // Vertical budget: a 13sp title box plus a 10sp artist box need roughly
            // SHORT_TWO_LINE_MIN_HEIGHT dp of padded height once Android's font padding
            // is included. Below that the artist line would have its glyph bottoms
            // clipped, so it is dropped and only the title is painted (see 4x1 ≈ 44dp).
            val roomForTwoLines = contentH >= SHORT_TWO_LINE_MIN_HEIGHT
            PlayerLayout(
                kind = PlayerLayoutKind.SHORT,
                coverDp = cover,
                titleSp = 13,
                artistSp = 10,
                albumSp = 0,
                showText = textBudget >= 40 && contentH >= SHORT_TITLE_MIN_HEIGHT,
                showArtist = textBudget >= 68 && roomForTwoLines,
                showAlbum = false,
                showProgress = false,
                showTimes = false,
                spreadTimes = false,
                showSkip = showSkip,
                skipDp = skip,
                playDp = play,
                timeSp = 10,
            )
        }

        h >= TALL_MIN_HEIGHT -> {
            // Scale the transport with the height, then give the cover whatever is left so
            // the column fills the cell instead of leaving a gap. The album line only
            // appears when the cell is tall enough to hold three text lines plus the
            // transport without clipping.
            val play = (h * 20 / 100).coerceIn(34, 46)
            val skip = play * 3 / 4
            val withAlbum = w >= 180 && h >= 200
            val textBlock = if (withAlbum) 58 else 38
            // Give the cover all the height that is left after the text block and the
            // transport, so a very tall cell is filled by art instead of by a dead band
            // (4x5 used to leave ~50dp empty). Capped by the width so the square never
            // overflows a narrow tall cell.
            val coverMax = (w - 24).coerceIn(36, 160)
            val cover = (h - 41 - textBlock - play).coerceIn(36, coverMax)
            PlayerLayout(
                kind = PlayerLayoutKind.TALL,
                coverDp = cover,
                titleSp = 17,
                artistSp = 13,
                albumSp = 11,
                showText = true,
                showArtist = true,
                showAlbum = withAlbum,
                showProgress = true,
                showTimes = true,
                spreadTimes = true,
                showSkip = true,
                skipDp = skip,
                playDp = play,
                timeSp = 11,
            )
        }

        w >= WIDE_MIN_WIDTH -> PlayerLayout(
            kind = PlayerLayoutKind.WIDE,
            // Fill most of the height with art but leave breathing room for the text.
            coverDp = (h - 20).coerceIn(52, 82),
            titleSp = 16,
            artistSp = 12,
            albumSp = 0,
            showText = true,
            showArtist = true,
            // A 6x2 cell is exactly as tall as the 4x2 one, so a third *stacked* text
            // line pushed the transport ~10dp past the bottom edge and the Row sliced the
            // play circle into an ellipse. The album rides on the artist line instead
            // (see [playerSecondaryLine]): same information, one row, no clipping.
            showAlbum = false,
            showProgress = true,
            showTimes = true,
            spreadTimes = true,
            showSkip = true,
            skipDp = 26,
            playDp = 34,
            timeSp = 11,
        )

        w < STANDARD_MIN_WIDTH || h < COMPACT_MAX_HEIGHT -> {
            // Size the cover and the play button from the height that is left after the
            // title line and the two gaps, not from the raw side. At the 2x2 reference
            // (110x110dp) the old side-derived buttons overflowed the padded height, so
            // the play/pause circle was clipped by the bottom edge.
            val contentH = h - OUTER_PADDING_DP * 2
            val available = (contentH - COMPACT_TEXT_BLOCK_DP - COMPACT_GAPS_DP)
                .coerceAtLeast(36)
            PlayerLayout(
                kind = PlayerLayoutKind.COMPACT,
                coverDp = (available * 48 / 100).coerceIn(22, 52),
                titleSp = 12,
                artistSp = 10,
                albumSp = 0,
                showText = true,
                showArtist = false,
                showAlbum = false,
                showProgress = false,
                showTimes = false,
                spreadTimes = false,
                showSkip = false,
                skipDp = 22,
                playDp = (available * 44 / 100).coerceIn(20, 38),
                timeSp = 10,
            )
        }

        else -> PlayerLayout(
            kind = PlayerLayoutKind.STANDARD,
            coverDp = 52,
            titleSp = 15,
            artistSp = 12,
            albumSp = 0,
            showText = true,
            showArtist = true,
            showAlbum = false,
            showProgress = true,
            showTimes = true,
            spreadTimes = false,
            showSkip = true,
            skipDp = 28,
            playDp = 34,
            timeSp = 11,
        )
    }
}

/** `height` below which the player collapses to its single-row [PlayerLayoutKind.SHORT] form. */
const val SHORT_MAX_HEIGHT: Int = 88

/**
 * Padded height (dp) the single-row player needs to stack a 13sp title above a 10sp artist
 * without clipping the artist's descenders. Below this the artist line is dropped.
 */
const val SHORT_TWO_LINE_MIN_HEIGHT: Int = 42

/** Padded height (dp) below which even the single title line is dropped. */
const val SHORT_TITLE_MIN_HEIGHT: Int = 22

/**
 * Height (dp) reserved for the compact player's single 12sp title line, including Android's
 * font padding.
 */
private const val COMPACT_TEXT_BLOCK_DP: Int = 22

/** Inter-element gaps (dp) in the compact player column (title↔cover and title↔play). */
private const val COMPACT_GAPS_DP: Int = 8

/** Widget-surface padding (dp), mirrored by `WidgetDimens.outerPadding`. */
private const val OUTER_PADDING_DP: Int = 8

/** `height` at/above which the player uses its scaled-up [PlayerLayoutKind.TALL] form. */
const val TALL_MIN_HEIGHT: Int = 150

/** `width` at/above which the player spends the extra space ([PlayerLayoutKind.WIDE]). */
const val WIDE_MIN_WIDTH: Int = 320

/** `width` below which the player uses its small-square [PlayerLayoutKind.COMPACT] form. */
const val COMPACT_MAX_WIDTH: Int = 160

/**
 * `height` below which the player uses its small-square [PlayerLayoutKind.COMPACT] form even
 * when it is wide: a cell just above the single-row height is too short for the standard
 * cover-beside-text stack. Tuned to the real content box (4x2 ≈ 94dp), not the launcher cell.
 */
const val COMPACT_MAX_HEIGHT: Int = 90

/**
 * `width` at/below which the STANDARD text column cannot fit its own transport, so the cell
 * falls back to [PlayerLayoutKind.COMPACT] instead: 16dp of surface padding + the 52dp cover
 * + the 10dp cover gap + the ~60dp `elapsed / duration` cluster (11 chars at 11sp × 0.5) +
 * the 94dp skip/play/skip transport ≈ 232dp. A 3x2 (164x94dp) used to slip the old
 * `w < 160 || h < 90` gate into the STANDARD branch and clipped the transport at both edges.
 */
const val STANDARD_MIN_WIDTH: Int = 232

// ── Playlist launcher ─────────────────────────────────────────────────

/**
 * Metrics for the "Playlists" launcher-tiles widget.
 *
 * @property columns tiles per row (width-driven).
 * @property rows tile rows (height-driven, at most [PLAYLIST_MAX_ROWS]).
 * @property showHeader whether the "Playlists" header line is painted.
 * @property showCover whether each tile paints its rounded cover thumb. Dropped on a very
 *   narrow cell, where the width is better spent on the name.
 * @property stacked `true` when a covered tile paints its cover **above** a full-width name
 *   (tall cells, see [PLAYLIST_STACKED_MIN_ROW_DP]) instead of beside it. The stacked tile
 *   fills its row exactly, so a tall covered cell has no dead band under the grid.
 * @property showTileCount whether each tile paints its track count under the name (only
 *   when the row is tall enough for a second text line).
 * @property coverDp side of each tile's rounded cover thumb.
 * @property rowHeightDp height each row is pinned to, so the rows plus their gaps fill the
 *   free height exactly (the pooled remainder is what used to show as a bottom band).
 * @property nameSp / [countSp] / [headerSp] text sizes.
 * @property nameChars character budget **per name line** (see [ellipsize]); Glance 1.1
 *   has no `TextOverflow`, so the name is truncated explicitly instead of being clipped.
 * @property nameMaxLines name lines the tile affords.
 * @property countChars character budget for the track-count line.
 * @property tileGapDp gap between tiles, both directions.
 * @property overflowCount playlists that exist but do not fit; the composable surfaces this
 *   as an explicit "+N" indicator instead of silently hiding them. `0` when nothing is hidden.
 */
data class PlaylistLayout(
    val columns: Int,
    val rows: Int,
    val showHeader: Boolean,
    val showCover: Boolean,
    val stacked: Boolean,
    val showTileCount: Boolean,
    val coverDp: Int,
    val rowHeightDp: Int,
    val nameSp: Int,
    val countSp: Int,
    val headerSp: Int,
    val nameChars: Int,
    val nameMaxLines: Int,
    val countChars: Int,
    val tileGapDp: Int,
    val overflowCount: Int,
) {
    /** How many tiles this layout paints (never above [PLAYLIST_TILE_CAP]). */
    val tiles: Int get() = (columns * rows).coerceAtMost(PLAYLIST_TILE_CAP)
}

/** Height (dp) at/above which the "Playlists" header line is kept. */
const val PLAYLIST_HEADER_MIN_HEIGHT: Int = 72

/** Smallest tile width (dp) that still fits a cover beside a legible name. */
const val PLAYLIST_MIN_TILE_WIDTH_DP: Int = 84

/** Minimum tile-row pitch (dp): a cover thumb plus one text line. */
const val PLAYLIST_TILE_PITCH_DP: Int = 26

/** Hard cap on tile rows for a covered tile; a taller cell grows the tiles instead. */
const val PLAYLIST_MAX_ROWS: Int = 2

/**
 * Row pitch (dp) the grid is **counted** with: a name line, a count line and the gap. Rows
 * are only added while every added row can still afford its text, so [PLAYLIST_TILE_PITCH_DP]
 * (cover + one text line) stays the floor used by the count-line and two-line checks below.
 */
private const val PLAYLIST_ROW_PITCH_DP: Int = PLAYLIST_TILE_PITCH_DP + 12

/**
 * Minimum row height (dp) that fits a **two-line** name plus the count line, at the tile's
 * own text sizes.
 *
 * The old rule used one flat threshold tuned for the 13sp label, so a 4-column 6x2 (11sp)
 * was forced onto a single line and cut "Night Drive" to "Night D…" even though its 51dp row
 * had room for two 11sp lines. Deriving the floor from the real sizes lets each cell wrap
 * when it actually fits.
 */
private fun playlistTwoLineNameMinHeight(nameSp: Int, countSp: Int): Int {
    val nameLine = nameSp * 14 / 10
    val countLine = countSp * 14 / 10
    return nameLine * 2 + countLine + 2
}

/** Smallest cover side (dp) a tile paints; the thumb never collapses below a legible square. */
internal const val PLAYLIST_MIN_COVER_DP: Int = 20

/**
 * Width (dp) the name strip beside a cover must keep for a typical playlist name.
 *
 * A 4x2/4x3 tile is ~106dp wide; the old cover took ~31-37dp of that and the 0.5-char-ratio
 * budget then cut the strip to ten characters, which truncated "Night Drive" to "Night Dri…".
 * Reserving this much width (the ~72dp "Night Drive" needs at 13sp) makes the cover yield
 * instead, so the common name reads whole.
 */
internal const val PLAYLIST_MIN_TEXT_WIDTH_DP: Int = 72

/**
 * Row height (dp) at/above which a covered tile stops putting the cover *beside* the name and
 * **stacks** it above a full-width, up-to-two-line name.
 *
 * Beside a large cover the text strip is only a few characters wide, so a tall covered cell
 * (4x4, 4x5) would both cut every name and pool the leftover height into a lopsided dead band
 * under the grid. The stacked tile spends that height on a bigger square cover plus a
 * full-width label, exactly filling its row (see [PlaylistLayout.stacked]).
 */
internal const val PLAYLIST_STACKED_MIN_ROW_DP: Int = 76

/**
 * Height (dp) a stacked tile reserves under its cover: a 4dp gap, two name lines and the
 * count line. The cover takes whatever is left, so the stacked tile's height is the row
 * height (with a couple of dp of slack, absorbed by the tile's vertical centring).
 */
private const val PLAYLIST_STACKED_TEXT_BLOCK_DP: Int = 50

/** Name size on a stacked tile (the label spans the whole tile, so it runs a hair smaller). */
private const val PLAYLIST_STACKED_NAME_SP: Int = 12

/** Width (dp) below which a tile drops its cover and spends the width on the name. */
const val PLAYLIST_COVER_MIN_WIDTH: Int = 150

/** Playlist-widget surface padding (dp), mirrored by `WidgetDimens.outerPadding`. */
private const val PLAYLIST_OUTER_PADDING_DP: Int = 8

/** Height reserved for the "Playlists" header line. */
private const val PLAYLIST_HEADER_DP: Int = 20

/** Vertical gap below the header. */
private const val PLAYLIST_HEADER_GAP_DP: Int = 6

/** Characters of the header label ("Playlists") at the header's own `sp`. */
private const val PLAYLIST_HEADER_LABEL_CHARS: Int = 9

/** The widest overflow marker ("+99 more") that can ride the header line. */
private const val PLAYLIST_HEADER_MORE_MAX_CHARS: Int = 8

/**
 * Picks the tile grid for a widget of [widthDp] x [heightDp].
 *
 * The cell is filled with as many tiles as fit: the row count comes from the height that is
 * left after the header divided by the readable row pitch (name + count + gap), the column
 * count from the width divided by a legible minimum tile width, and the product is capped at
 * [PLAYLIST_TILE_CAP] because every tile may carry a (small) cover. A covered cell stops at
 * [PLAYLIST_MAX_ROWS] rows and grows its covers instead; a text-only cell (too narrow for a
 * cover) adds rows up to [PLAYLIST_TILE_CAP], so a tall narrow cell is filled with playlists
 * instead of blank space.
 *
 * Reference boxes: a 4x2 (≈234×94dp) is one row of two cover tiles; a 4x3 (≈234×164dp) is a
 * 2×2 grid of four; a 4x4 (≈234×234dp) stacks each cover above a full-width name; a 2x2
 * (≈94×94dp) drops to one column and shows one text-only tile; a 2x3 (≈94×163dp) lists three
 * text-only tiles; a 6x2 (≈369×94dp) widens to four columns in a single row.
 *
 * @param tileCount how many playlists are actually available. The grid never reserves more
 *   columns or rows than that, so one playlist on a 4x4 paints one large centred tile instead
 *   of a lone tile against three empty quadrants.
 */
fun playlistLayout(widthDp: Int, heightDp: Int, tileCount: Int = PLAYLIST_TILE_CAP): PlaylistLayout {
    val w = widthDp.coerceAtLeast(1)
    val h = heightDp.coerceAtLeast(1)
    val available = tileCount.coerceIn(0, PLAYLIST_TILE_CAP)
    val showHeader = h >= PLAYLIST_HEADER_MIN_HEIGHT
    val columnFit = ((w - PLAYLIST_OUTER_PADDING_DP * 2) / PLAYLIST_MIN_TILE_WIDTH_DP)
        .coerceIn(1, PLAYLIST_TILE_CAP)
    // Spend no more columns than there are tiles to fill them, so two playlists on a 6x2 are
    // two comfortable tiles rather than two tiles beside two empty ones.
    val columns = columnFit.coerceAtMost(available.coerceAtLeast(1))
    val canCover = w >= PLAYLIST_COVER_MIN_WIDTH
    val maxRows = if (canCover) PLAYLIST_MAX_ROWS else PLAYLIST_TILE_CAP
    // Rows painted for [freeGrid] dp of grid height, once all chrome is removed. Shared by
    // the two passes below (with and without the overflow line). The floor rule keeps at
    // least one tile: a cell whose free height cannot afford the readable row pitch used to
    // collapse to zero tiles and paint a false "No playlists yet" over two real ones.
    fun rowsFor(freeGrid: Int): Int {
        var rows = (freeGrid / PLAYLIST_ROW_PITCH_DP).coerceIn(0, maxRows)
        if (rows == 0 && available > 0 && freeGrid >= PLAYLIST_TILE_PITCH_DP) rows = 1
        while (rows > 0 && columns * rows > PLAYLIST_TILE_CAP) rows--
        val neededRows = (available + columns - 1) / columns
        return minOf(rows, if (available > 0) neededRows else 0)
    }
    var chrome = PLAYLIST_OUTER_PADDING_DP * 2
    if (showHeader) chrome += PLAYLIST_HEADER_DP + PLAYLIST_HEADER_GAP_DP
    val free = (h - chrome).coerceAtLeast(0)
    var rows = rowsFor(free)
    // Overflow affordance: with the header on screen the count rides the header line (no
    // extra height); headerless cells reserve a compact line under the grid instead, and
    // only when doing so does not collapse the grid below one compact row.
    var overflow = available - columns * rows
    var overflowChrome = 0
    if (overflow > 0 && !showHeader) {
        val withLine = rowsFor((free - PLAYLIST_OVERFLOW_TOTAL_DP).coerceAtLeast(0))
        if (withLine > 0) {
            rows = withLine
            overflowChrome = PLAYLIST_OVERFLOW_TOTAL_DP
            overflow = available - columns * rows
        } else if (rows > 0) {
            // The line cannot be afforded, and the floor rule above already kept a tile:
            // 2x1/3x1/4x1 (~44dp tall) end here with one compact tile painted. An
            // unreserved "+N" under that tile would draw out of the cell, so the hidden
            // playlists go unmarked rather than clipped — and the empty message is never
            // shown while tiles fit.
            overflow = 0
        }
    }
    // On header cells the count rides the header line — but only when both strings fit on
    // it: a narrow header cell (2x2 ≈ 93dp wide) used to clip the trailing marker mid-glyph
    // at the squircle edge (sibling of the headerless rule above, which hides the count
    // rather than afford a line it cannot fit). "Playlists +99 more" ≈ 17 characters.
    val headerSp = if (w < COMPACT_MAX_WIDTH) 12 else 13
    if (showHeader && overflow > 0) {
        val headerBudget = textCharBudget(w - PLAYLIST_OUTER_PADDING_DP * 2, headerSp)
        if (headerBudget < PLAYLIST_HEADER_LABEL_CHARS + PLAYLIST_HEADER_MORE_MAX_CHARS) overflow = 0
    }
    // Rows are counted with the readable pitch (name + count + gap) but then *pinned* to a
    // fixed height that also absorbs the inter-row gaps, so `rows * rowHeight + gaps == free`
    // and the grid fills the cell instead of pooling the remainder into a band above the
    // bottom edge (the old 4x4 left ~74dp of empty card under the tiles).
    val rowHeight = if (rows > 0) (free - overflowChrome - (rows - 1) * PLAYLIST_TILE_GAP_DP) / rows else free
    val compact = w < COMPACT_MAX_WIDTH
    val showCover = canCover && rows > 0
    // A tall covered row switches from "cover beside the name" to "cover above a full-width
    // name": beside a big cover the text strip is a few characters wide, which cut names and
    // left a void under the grid (see [PLAYLIST_STACKED_MIN_ROW_DP]).
    val stacked = showCover && rowHeight >= PLAYLIST_STACKED_MIN_ROW_DP
    val nameSp = when {
        stacked -> PLAYLIST_STACKED_NAME_SP
        compact || columns >= 4 -> 11
        else -> 13
    }
    val countSp = if (compact || columns >= 4) 10 else 11
    val showTileCount = rows > 0 && rowHeight >= PLAYLIST_TILE_PITCH_DP + 8
    val tileWidth = ((w - PLAYLIST_OUTER_PADDING_DP * 2 - (columns - 1) * PLAYLIST_TILE_GAP_DP) / columns)
        .coerceAtLeast(1)
    val cover = if (stacked) {
        // Everything the two-line name + count do not need, square and inside the tile width,
        // so the stacked tile's own height is exactly the row height.
        (rowHeight - PLAYLIST_STACKED_TEXT_BLOCK_DP)
            .coerceIn(PLAYLIST_MIN_COVER_DP, tileWidth)
    } else {
        // The cover yields to a legible name strip even when the row would allow a bigger
        // thumb, so "Night Drive" reads whole on a 4x2 / 4x3 / 6x2 tile.
        val maxCover = (tileWidth - PLAYLIST_TILE_GAP_DP - PLAYLIST_MIN_TEXT_WIDTH_DP)
            .coerceAtLeast(PLAYLIST_MIN_COVER_DP)
        (rowHeight * 62 / 100).coerceIn(PLAYLIST_MIN_COVER_DP, maxCover)
    }
    val textWidth =
        if (stacked || !showCover) tileWidth
        else (tileWidth - cover - PLAYLIST_TILE_GAP_DP).coerceAtLeast(1)
    val nameMaxLines = when {
        stacked -> 2
        rowHeight >= playlistTwoLineNameMinHeight(nameSp, countSp) -> 2
        else -> 1
    }
    return PlaylistLayout(
        columns = columns,
        rows = rows,
        showHeader = showHeader,
        showCover = showCover,
        stacked = stacked,
        showTileCount = showTileCount,
        coverDp = cover,
        rowHeightDp = rowHeight,
        nameSp = nameSp,
        countSp = countSp,
        headerSp = headerSp,
        nameChars = textCharBudget(textWidth, nameSp),
        nameMaxLines = nameMaxLines,
        countChars = textCharBudget(textWidth, countSp, WIDGET_COUNT_CHAR_WIDTH_RATIO),
        tileGapDp = PLAYLIST_TILE_GAP_DP,
        overflowCount = overflow.coerceAtLeast(0),
    )
}

/** Gap (dp) between tiles, both directions. */
internal const val PLAYLIST_TILE_GAP_DP: Int = 6

/** Vertical cost (line + gap) of the compact "+N more" overflow line on a headerless cell. */
internal const val PLAYLIST_OVERFLOW_TOTAL_DP: Int = 22

// ── Library stats + sync ──────────────────────────────────────────────

/** The individual figures the "Library" widget can paint, in priority order. */
enum class LibraryStatKind {
    /** total songs */
    SONGS,

    /** downloaded songs */
    DOWNLOADED,

    /** songs with lyrics */
    LYRICS,

    /** playlists */
    PLAYLISTS,
}

/** Whole-library stat figures, most important first; trimmed to fit the cell. */
val LIBRARY_STAT_ORDER: List<LibraryStatKind> = listOf(
    LibraryStatKind.SONGS,
    LibraryStatKind.DOWNLOADED,
    LibraryStatKind.LYRICS,
    LibraryStatKind.PLAYLISTS,
)

/**
 * Groups the stat figures into painted rows of [itemsPerRow].
 *
 * Pure, so "how many 'a · b' lines fit" is pinned down and tested rather than guessed.
 */
fun libraryStatRows(
    kinds: List<LibraryStatKind> = LIBRARY_STAT_ORDER,
    itemsPerRow: Int,
): List<List<LibraryStatKind>> {
    if (itemsPerRow <= 0) return emptyList()
    return kinds.chunked(itemsPerRow)
}

/**
 * Metrics for the "Library" (sync status + stats) widget.
 *
 * @property showHeader whether the "Library" header line is painted (with the sync badge
 *   beside it).
 * @property showSyncText whether the sync badge carries its words; on a very narrow cell
 *   only the coloured dot remains, so nothing is clipped.
 * @property showSyncLine whether the dedicated, full-length sync line is painted. It backs
 *   the sync readout whenever the header cannot carry it: on a **short** cell the header
 *   itself is dropped, and on a **narrow** cell the badge cannot spell its state out.
 * @property shortSync whether the sync readout should use its short forms ("3 min ago",
 *   "Just now"). A narrow cell has only ~74dp for the header badge (~14 characters), so
 *   "Synced 3 min ago" was cut to "Synced 3 min…"; the short forms fit whole there and on
 *   the dedicated sync line.
 * @property showProgress whether the downloaded-progress block (bar + counts) is painted —
 *   the tall-cell filler.
 * @property showHeroPercent whether the progress block leads with the big percentage figure.
 * @property progressLabel whether the progress block's caption keeps its "Downloaded" word
 *   beside the percentage. Dropped on a narrow cell (see [STATS_SYNC_TEXT_MIN_WIDTH]), where
 *   the word would touch the figure — the 2x3 read "Downloaded76%" with no gap.
 * @property itemsPerRow stat figures per line (1 on a narrow cell, else 2).
 * @property statItems the figures to paint, in [LIBRARY_STAT_ORDER] priority.
 * @property headerSp / [syncSp] / [statSp] text sizes.
 * @property percentSp size of the big percentage figure on a tall cell.
 * @property rowPitchDp vertical pitch reserved per stat row.
 * @property statItemChars character budget **per figure** (see [ellipsize]); a row paints
 *   [itemsPerRow] figures plus separators, so each gets an equal share of the line.
 * @property badgeChars character budget for the header badge label (it shares the line with
 *   the "Library" title, so it is only part of the width).
 * @property syncChars character budget for the dedicated sync line (full width).
 * @property singleLine `true` on the shortest headerless cells: the figures and the sync
 *   readout fold into **one** combined line rather than a dedicated line eating the whole
 *   budget (the old 3x1/4x1 dropped every figure; the old 2x1 dropped the readout).
 * @property syncOnLine whether the combined line attempts the short sync readout's words
 *   after the figures; when the budget cannot hold them the sync-state dot still marks it.
 * @property singleLineChars character budget for the combined single line.
 * @property topAlign `true` when the content column is pinned to the top instead of being
 *   vertically centred: a tall cell with the progress block but not the hero figure pools a
 *   ~40dp band above AND below the group when centred.
 */
data class StatsLayout(
    val showHeader: Boolean,
    val showSyncText: Boolean,
    val showSyncLine: Boolean,
    val shortSync: Boolean,
    val showProgress: Boolean,
    val showHeroPercent: Boolean,
    val progressLabel: Boolean,
    val itemsPerRow: Int,
    val statItems: List<LibraryStatKind>,
    val headerSp: Int,
    val syncSp: Int,
    val statSp: Int,
    val percentSp: Int,
    val rowPitchDp: Int,
    val statItemChars: Int,
    val badgeChars: Int,
    val syncChars: Int,
    val singleLine: Boolean,
    val syncOnLine: Boolean,
    val singleLineChars: Int,
    val topAlign: Boolean,
) {
    /** Stat rows the composable paints. */
    val statRows: List<List<LibraryStatKind>> get() = libraryStatRows(statItems, itemsPerRow)
}

/** Height (dp) at/above which the "Library" header + inline sync badge are kept. */
const val STATS_HEADER_MIN_HEIGHT: Int = 66

/** Width (dp) at/above which the sync badge spells its state out; below it, dot only. */
const val STATS_SYNC_TEXT_MIN_WIDTH: Int = 150

/** Height (dp) at/above which a dedicated full-width sync line is added (narrow cells only). */
const val STATS_SYNC_LINE_MIN_HEIGHT: Int = 120

/** Height (dp) at/above which the downloaded-progress block is added. */
const val STATS_PROGRESS_MIN_HEIGHT: Int = 150

/** Width (dp) below which stat figures stack one per line. */
const val STATS_COMPACT_WIDTH: Int = 200

/**
 * Width (dp) at/above which the stat figures may run at [statsLayout]'s larger size. A
 * 234dp 4x4 is **not** wide enough: at 14sp its two figures share only ~15 characters each,
 * which cut "742 with lyrics" to "742 with lyri…". The wider 6x2/6x3 cells can afford it.
 */
const val STATS_WIDE_WIDTH: Int = 320

/** Height (dp) at/above which the progress percentage is drawn as a hero figure. */
const val STATS_HERO_MIN_HEIGHT: Int = 200

/** Library-widget surface padding (dp), mirrored by `WidgetDimens.outerPadding`. */
private const val STATS_OUTER_PADDING_DP: Int = 8

/**
 * Width (dp) reserved ahead of the combined single line for the sync-state dot plus its gap
 * (see [StatsLayout.singleLine]) — the dot marks the sync state even where no readout fits.
 */
private const val STATS_SINGLE_DOT_RESERVE_DP: Int = 12

/** Height reserved for the header line (it also carries the sync badge). */
private const val STATS_HEADER_DP: Int = 20

/** Height reserved for the dedicated sync line. */
private const val STATS_SYNC_LINE_DP: Int = 16

/** Height reserved for the progress block (caption + bar, or hero + bar). */
private const val STATS_PROGRESS_DP: Int = 34

/** Vertical gap the composer places around its blocks. */
private const val STATS_GAP_DP: Int = 6

/** Width reserved between two figures on one stat line (` · `). */
private const val STATS_STAT_SEPARATOR_DP: Int = 14

/**
 * Picks the "Library" composition for a widget of [widthDp] x [heightDp].
 *
 * Graceful degradation: the header/badge are dropped first, then the progress block, and
 * the stat figures are trimmed (highest priority first) to whatever the remaining height
 * holds. Nothing is ever clipped: every visible block is budgeted before the figure count
 * is derived, and each line's character budget comes from the real width.
 *
 * The sync readout is never lost: it rides the header badge when the header is shown and
 * the width allows words, and otherwise gets its own line (see [StatsLayout.showSyncLine]).
 */
fun statsLayout(widthDp: Int, heightDp: Int): StatsLayout {
    val w = widthDp.coerceAtLeast(1)
    val h = heightDp.coerceAtLeast(1)
    val showHeader = h >= STATS_HEADER_MIN_HEIGHT
    // The shortest headerless cells fold everything into one combined line: a dedicated
    // sync line plus a figure row needs `padding + line + gap + pitch` (~60dp), and cells
    // below that used to let the line eat the whole budget (the 3x1/4x1 "Synced 3 min ago"
    // with zero figures). On those the figures and the readout share one line, led by the
    // sync-state dot.
    val singleLine = !showHeader &&
        (h - STATS_OUTER_PADDING_DP * 2 - STATS_SYNC_LINE_DP - STATS_GAP_DP) < STATS_SINGLE_LINE_ROW_PITCH_DP
    val showSyncText = !singleLine && w >= STATS_SYNC_TEXT_MIN_WIDTH
    // With the header there, the badge owns the readout on a wide cell, and only a narrow
    // cell needs the extra line. Without the header, the line is the readout on a wide cell.
    val showSyncLine = if (singleLine) {
        false
    } else if (showSyncText) {
        !showHeader
    } else {
        h >= STATS_SYNC_LINE_MIN_HEIGHT
    }
    val showProgress = h >= STATS_PROGRESS_MIN_HEIGHT
    val compact = w < STATS_COMPACT_WIDTH
    val rowPitch = if (h >= STATS_HERO_MIN_HEIGHT) 30 else 22
    val itemsPerRow = when {
        singleLine -> if (w >= STATS_SYNC_TEXT_MIN_WIDTH) 2 else 1
        compact -> 1
        else -> 2
    }
    var chrome = STATS_OUTER_PADDING_DP * 2
    if (showHeader) chrome += STATS_HEADER_DP + STATS_GAP_DP
    if (showSyncLine) chrome += STATS_SYNC_LINE_DP + STATS_GAP_DP
    if (showProgress) chrome += STATS_PROGRESS_DP + STATS_GAP_DP
    val free = (h - chrome).coerceAtLeast(0)
    val rowsFit = (free / rowPitch).coerceIn(0, 3)
    val items = (rowsFit * itemsPerRow).coerceIn(0, LIBRARY_STAT_ORDER.size)
    // Figures stay at 13sp on the 4-column-wide cells (4x2/4x3/4x4/4x5) and drop to 12sp on
    // the narrow 2-wide cells. 13sp does not fit "742 with lyrics" beside a separator in a
    // 234dp cell (see [STATS_WIDE_WIDTH]), and it also cut the grouped large figures
    // ("12,345 songs" → "12,345 son…") in a 94dp one, so the narrow cells run a hair smaller
    // and the grouped thousands read whole. Only the truly wide 6x2/6x3 cells use 14sp. The
    // combined single line runs at 10sp: both figures and the readout share one 44dp cell.
    val statSp = when {
        singleLine -> 10
        w >= STATS_WIDE_WIDTH -> 14
        w < STATS_COMPACT_WIDTH -> 12
        else -> 13
    }
    val syncSp = if (compact) 11 else 12
    val innerWidth = (w - STATS_OUTER_PADDING_DP * 2).coerceAtLeast(1)
    // Figures share the line with `itemsPerRow - 1` separators; budget each one for its share.
    val figureWidth = (innerWidth - (itemsPerRow - 1) * STATS_STAT_SEPARATOR_DP).coerceAtLeast(1) / itemsPerRow
    return StatsLayout(
        showHeader = showHeader,
        showSyncText = showSyncText,
        showSyncLine = showSyncLine,
        shortSync = compact || singleLine,
        showProgress = showProgress,
        showHeroPercent = h >= STATS_HERO_MIN_HEIGHT,
        // The "Downloaded 76%" caption keeps its word only where the readout itself is wide
        // enough to carry words; a narrower cell drops it rather than letting the word touch
        // the figure (the 2x3 read "Downloaded76%").
        progressLabel = w >= STATS_SYNC_TEXT_MIN_WIDTH,
        itemsPerRow = itemsPerRow,
        statItems = LIBRARY_STAT_ORDER.take(items),
        headerSp = if (compact) 13 else 14,
        syncSp = syncSp,
        statSp = statSp,
        // The hero percentage is the tall cell's filler, so it grows with the free height and
        // is capped by the width too, or a narrow 2x4 cell would clip "76%". Centring the
        // column then turns whatever slack is left into an even top/bottom margin instead of
        // the lopsided band the old 39sp hero left under the card.
        percentSp = minOf(h / 4, innerWidth / 2).coerceIn(24, 60),
        rowPitchDp = rowPitch,
        statItemChars = textCharBudget(figureWidth, statSp),
        badgeChars = textCharBudget((innerWidth / 2).coerceAtLeast(1), syncSp),
        syncChars = textCharBudget(innerWidth, syncSp),
        singleLine = singleLine,
        syncOnLine = singleLine && w >= STATS_SYNC_TEXT_MIN_WIDTH,
        singleLineChars = textCharBudget(
            (innerWidth - STATS_SINGLE_DOT_RESERVE_DP).coerceAtLeast(1),
            statSp,
        ),
        // The progress-only tall cells (4x3/6x3) pool a ~40dp band above AND below the
        // centred group, so they are pinned to the top; the hero figure (the taller filler)
        // keeps its centring.
        topAlign = showProgress && !singleLine && h < STATS_HERO_MIN_HEIGHT,
    )
}

/**
 * Vertical pitch (dp) a plain stat figure row needs. A headerless cell that cannot hold a
 * sync line AND this pitch folds its content into the combined single line instead.
 */
private const val STATS_SINGLE_LINE_ROW_PITCH_DP: Int = 22

// ── Lyrics ────────────────────────────────────────────────────────────

/**
 * Metrics + feature flags for one lyrics composition. Same "plain values only" rule as
 * [PlayerLayout].
 *
 * @property windowSize how many lyric rows are painted (always `>= 1`, always
 *   `<=` [WIDGET_COLUMN_MAX_CHILDREN] because each row is one `Column` child).
 * @property showHeader whether the cover thumb + title/artist header is painted.
 * @property spreadHeader whether the header sits **beside** the lyric block (wide, short
 *   cells) instead of above it, so a wide box is filled by the lyrics rather than left
 *   empty on the right.
 * @property headerWidthDp width reserved for the header when [spreadHeader]; `0` otherwise.
 * @property showProgress whether the thin progress bar is painted.
 * @property showTransport whether the centred prev/play/next row is painted.
 * @property titleSp / [artistSp] header text sizes.
 * @property currentSp / [neighborSp] highlighted / faded lyric text sizes.
 * @property headerCoverDp header cover thumb side.
 * @property showSkip whether the prev/next buttons flank the play button. When the
 *   three-button cluster cannot fit the cell's inner width it overflows and slices the skip
 *   glyphs at both edges, so a narrow cell falls back to the play/pause pill alone.
 * @property skipDp / [playDp] transport button diameters.
 */
data class LyricsLayout(
    val windowSize: Int,
    val showHeader: Boolean,
    val spreadHeader: Boolean,
    val headerWidthDp: Int,
    val showProgress: Boolean,
    val showTransport: Boolean,
    val titleSp: Int,
    val artistSp: Int,
    val currentSp: Int,
    val neighborSp: Int,
    val headerCoverDp: Int,
    val showSkip: Boolean,
    val skipDp: Int,
    val playDp: Int,
)

/**
 * Height at/above which the lyrics header is kept. Tuned to the real content box: a 4x2 cell
 * reports ≈94dp once the host's own gutter is removed, so the header survives there.
 */
const val LYRICS_HEADER_MIN_HEIGHT: Int = 88

/** Height at/above which the lyrics transport row is kept. */
const val LYRICS_TRANSPORT_MIN_HEIGHT: Int = 150

/**
 * Height at/above which the lyrics progress bar is kept.
 *
 * Kept equal to [LYRICS_TRANSPORT_MIN_HEIGHT] so the slim bar and the transport row appear
 * and disappear **together**: a widget that shows transport but no progress reads lopsided
 * (see the old 4x3, where the reversed behaviour left dead space under the lyrics).
 */
const val LYRICS_PROGRESS_MIN_HEIGHT: Int = LYRICS_TRANSPORT_MIN_HEIGHT

/** Upper bound on visible lyric rows; keeps the body from overflowing any sane cell. */
const val LYRICS_MAX_WINDOW_SIZE: Int = 8

/** Padding applied inside the widget surface, mirrored by WidgetDimens.outerPadding. */
private const val LYRICS_OUTER_PADDING_DP: Int = 8

/**
 * Height of one plain lyric row at the default density: the 13sp text line plus its 2dp
 * top/bottom padding.
 *
 * **Measured** on the device (13sp row → 21.3dp, 16sp highlighted row → 24.5dp) rather
 * than guessed. The old 27dp value over-reserved ~6dp *per row*, so the 4x4 cell could
 * only fit four lines and the unfilled remainder pooled into the visible dead band under
 * the lyrics; the corrected figure lets the block nearly fill its slot.
 */
internal const val LYRICS_ROW_HEIGHT_DP: Int = 22

/**
 * Extra height the highlighted (current) row takes over [LYRICS_ROW_HEIGHT_DP]: it is
 * drawn at `currentSp` (16sp vs 13sp) with a slightly larger vertical padding.
 *
 * A synced window always contains exactly one such row, so a window of `n` rows needs
 * `n * LYRICS_ROW_HEIGHT_DP + LYRICS_CURRENT_ROW_EXTRA_DP` of free height.
 */
internal const val LYRICS_CURRENT_ROW_EXTRA_DP: Int = 3

/** Header block height (cover thumb + two text lines). */
private const val LYRICS_HEADER_DP: Int = 38

/** Vertical gap the body places around its blocks. */
private const val LYRICS_GAP_DP: Int = 6

/** Thin progress bar height. */
private const val LYRICS_PROGRESS_DP: Int = 3

/** Transport row height (the play button dominates it). */
private const val LYRICS_TRANSPORT_DP: Int = 38

/** Horizontal gap between the transport buttons; mirrored by `LyricsWidget`'s Row. */
internal const val LYRICS_TRANSPORT_GAP_DP: Int = 10

/**
 * Chooses how many lyric rows fit in [heightDp] once the header, progress bar, transport
 * row and inter-block gaps the composer will actually paint have been subtracted. Pure and
 * unit-tested, so the "5 lines at 4x4, 8 at 4x5, 2 when short" rule is pinned down rather
 * than guessed.
 */
fun lyricLinesForHeight(heightDp: Int, showHeader: Boolean, showProgress: Boolean, showTransport: Boolean): Int {
    var chrome = LYRICS_OUTER_PADDING_DP * 2
    if (showHeader) chrome += LYRICS_HEADER_DP + LYRICS_GAP_DP
    if (showProgress) chrome += LYRICS_PROGRESS_DP + LYRICS_GAP_DP
    if (showTransport) chrome += LYRICS_TRANSPORT_DP + LYRICS_GAP_DP
    val free = heightDp - chrome
    return lyricLinesForFreeHeight(free)
}

/** Maps free vertical space (dp, chrome already removed) to a visible-row count. */
fun lyricLinesForFreeHeight(freeDp: Int): Int {
    // The window always rolls around the highlighted row, which is taller than the rest,
    // so subtract its extra height before dividing by the plain row pitch.
    val fit = (freeDp - LYRICS_CURRENT_ROW_EXTRA_DP) / LYRICS_ROW_HEIGHT_DP
    // A modest 8-row default keeps mid-size cells from over-reserving rows they cannot
    // fill; a genuinely tall cell (6x6 leaves ~260dp free) fills to the RemoteViews
    // child cap instead ([WIDGET_COLUMN_MAX_CHILDREN]), which is what bounds the window.
    return fit
        .coerceAtLeast(1)
        .coerceAtMost(if (freeDp >= LYRICS_MAX_WINDOW_FREE_DP) WIDGET_COLUMN_MAX_CHILDREN else LYRICS_MAX_WINDOW_SIZE)
}

/**
 * Free height (dp, all chrome removed) at/above which the visible window may grow from
 * [LYRICS_MAX_WINDOW_SIZE] rows to [WIDGET_COLUMN_MAX_CHILDREN].
 */
internal const val LYRICS_MAX_WINDOW_FREE_DP: Int =
    WIDGET_COLUMN_MAX_CHILDREN * LYRICS_ROW_HEIGHT_DP + LYRICS_CURRENT_ROW_EXTRA_DP

// ── Sleep timer ring ──────────────────────────────────────────────────

/**
 * Metrics for the "Sleep timer" widget. The same values drive both states: the ring
 * geometry when a countdown is running and the start buttons when it is idle.
 *
 * @property wide `true` for the wide-and-short active row (ring beside caption + Cancel,
 *   e.g. 4x2); `false` for the stacked / small compositions.
 * @property activeRing `true` when the countdown is drawn as the ring (wide row, a tall
 *   square, **or** the 2x2 square once its caption is dropped); `false` only for a
 *   degenerate cell too small for any legible ring, which falls back to a number over a bar.
 * @property ringDp diameter of the countdown ring.
 * @property ringStrokeDp ring thickness.
 * @property ringGlyphDp side of the moon glyph drawn **inside** the active ring; `0` when
 *   the ring is too small to carry it without crowding the countdown.
 * @property ringTimeSp text size of the `mm:ss` drawn inside the active ring. Kept small
 *   enough that the glyph plus the time always clear the ring stroke (see
 *   `sleepRingContentsClearTheStroke`).
 * @property activeCaption whether the "Sleep timer" caption row is painted **above the
 *   active ring** (stacked shapes). It is dropped on a 2x2 square, where the height is
 *   better spent on a legible ring; the host's own label still names the widget.
 * @property timeSp text size of the remaining `mm:ss` in the wide row / bar fallback.
 * @property showLabel whether the "Sleep timer" caption / moon glyph is painted (idle,
 *   and the stacked active shape when [activeCaption]).
 * @property showLabelText whether the caption carries its words or only the moon glyph
 *   (a 2x2 cell is too narrow for "Sleep timer").
 * @property labelSp caption text size.
 * @property showPresets `true` to offer three 15/30/60 minute buttons, `false` for one.
 * @property singlePresetMinutes the lone preset offered on a narrow cell.
 * @property stackPresets `true` to stack the three presets as full-width pills (a very tall
 *   cell) instead of a single centred circle row.
 * @property buttonDp diameter/height of the idle start buttons (presets / single pill).
 * @property buttonSp label size inside the idle start buttons.
 * @property cancelDp height of the active Cancel pill. It is deliberately more compact than
 *   [buttonDp]: the stacked active shape has to fit a caption, the ring *and* Cancel in one
 *   cell, which the tall idle preset size cannot do.
 * @property cancelSp label size inside the Cancel pill; it also tightens on a compact-width
 *   cell so the taller full-height pill still fits its "× Cancel" label.
 * @property cancelPadDp horizontal padding inside the Cancel pill; it shrinks on a small or
 *   narrow cell so "× Cancel" never gets ellipsised.
 * @property iconDp the moon/cancel glyph side.
 */
data class SleepLayout(
    val wide: Boolean,
    val activeRing: Boolean,
    val ringDp: Int,
    val ringStrokeDp: Int,
    val ringGlyphDp: Int,
    val ringTimeSp: Int,
    val activeCaption: Boolean,
    val timeSp: Int,
    val showLabel: Boolean,
    val showLabelText: Boolean,
    val labelSp: Int,
    val showPresets: Boolean,
    val singlePresetMinutes: Int,
    val stackPresets: Boolean,
    val buttonDp: Int,
    val buttonSp: Int,
    val cancelDp: Int,
    val cancelSp: Int,
    val cancelPadDp: Int,
    val iconDp: Int,
)

/** Height (dp) at/above which the "Sleep timer" caption + moon glyph are kept. */
const val SLEEP_LABEL_MIN_HEIGHT: Int = 64

/** Width (dp) at/above which the caption keeps its words; below it the moon stands alone. */
const val SLEEP_LABEL_TEXT_MIN_WIDTH: Int = 110

/** Width (dp) at/above which the three 15/30/60 presets fit instead of a single start. */
const val SLEEP_PRESETS_MIN_WIDTH: Int = 170

/**
 * Narrowest row width (dp) at which the wide active row may still be used with a **shrunk**
 * ring: the 3x2 (~164dp) misses the classic [SLEEP_PRESETS_MIN_WIDTH] gate by 6dp, which
 * used to strand its small stacked ring between two ~46dp gutters.
 */
const val SLEEP_WIDE_ROW_MIN_WIDTH: Int = 164

/** Horizontal gap (dp) between the wide row's ring and its caption/Cancel column. */
internal const val SLEEP_WIDE_RING_GAP_DP: Int = 12

/**
 * Narrowest caption/Cancel column (dp) the wide row accepts: enough for the "× Cancel" pill
 * at its compact sizing (~72dp painted). A wider cell is not forced to shrink its ring for
 * the row to count as "fitting".
 */
const val SLEEP_WIDE_COLUMN_MIN_DP: Int = 76

/**
 * Height (dp) of the wide-row Cancel pill when the ring has been shrunk to leave the column
 * its minimum width: the pill's own painted width grows with its size, so a narrow column
 * borrows the compact 4x2 reference sizing instead of the roomy tall-cell pill.
 */
const val SLEEP_WIDE_SHRUNK_CANCEL_DP: Int = 28

/** Height (dp) at/above which the presets stack as full-width pills instead of a circle row. */
const val SLEEP_STACK_PRESETS_MIN_HEIGHT: Int = 190

/** Largest idle preset circle. Sized to the width that is left for three of them. */
const val SLEEP_PRESET_MAX_DP: Int = 68

/** Largest stacked preset pill height. */
const val SLEEP_STACK_PILL_MAX_DP: Int = 68

/** Smallest ring diameter that still holds a legible `mm:ss`; below it the bar is used. */
const val SLEEP_MIN_RING_DP: Int = 64

/**
 * Smallest **active** ring, in dp: the 2x2 square floor. Between this and [SLEEP_MIN_RING_DP]
 * the ring is still a legible instrument (the countdown sits inside it) but drops the caption
 * above and only carries the moon glyph once it reaches [SLEEP_MOON_MIN_RING_DP].
 */
const val SLEEP_COMPACT_RING_MIN_DP: Int = 44

/** Cancel-pill height (dp) on the 2x2 square, where it shares the cell with the ring. */
const val SLEEP_COMPACT_CANCEL_DP: Int = 22

/** Ring diameter (dp) at/above which the moon glyph rides above the countdown. */
const val SLEEP_MOON_MIN_RING_DP: Int = 56

/** Painted height (dp) reserved for the "Sleep timer" caption row in the stacked shapes. */
const val SLEEP_CAPTION_DP: Int = 20

/** Vertical gap (dp) between the stacked caption / ring / Cancel. */
const val SLEEP_STACK_GAP_DP: Int = 8

/**
 * Picks the sleep-timer composition for a widget of [widthDp] x [heightDp].
 *
 * Three active shapes, by aspect:
 *  - **wide & short** (4x2): the ring beside the caption + Cancel, using the spare width;
 *  - **tall** (4x3/4x4/4x5): the caption, ring and Cancel stacked, sized from the height;
 *  - **small square** (2x2): the caption is dropped and a compact Cancel shrinks, so the
 *    ring — with the countdown inside it — still fits rather than degrading to a bar.
 *
 * A cell that misses the classic gate of the wide row by a hair (the 3x2's 164dp or the
 * 4x3's 1.43 aspect) still gets the row when the ring can shrink far enough to leave the
 * caption/Cancel column its width **and** the shrunk ring beats the stacked one — otherwise
 * it keeps the stacked shape. The stacked ring is additionally capped by the cell width, so
 * a narrow tall cell (2x4) keeps its arcs inside the squircle instead of running them off
 * both edges.
 *
 * Only a cell below the 2x2 minimum (no ring above [SLEEP_COMPACT_RING_MIN_DP] can be
 * afforded) falls back to a large `mm:ss` over a thick rounded bar.
 *
 * The idle state has the same three aspects: a centred circle row, a stacked pill list on a
 * very tall cell, or one full-width "Start N min" pill on a narrow one.
 */
fun sleepLayout(widthDp: Int, heightDp: Int): SleepLayout {
    val w = widthDp.coerceAtLeast(1)
    val h = heightDp.coerceAtLeast(1)
    val wideByRule = w >= SLEEP_PRESETS_MIN_WIDTH && w >= h * 3 / 2
    val showLabel = h >= SLEEP_LABEL_MIN_HEIGHT
    val showPresets = w >= SLEEP_PRESETS_MIN_WIDTH && h >= 88
    val caption = if (showLabel) SLEEP_CAPTION_DP + SLEEP_STACK_GAP_DP else 0
    // A very tall cell would leave the circle row floating in empty space, so the presets
    // stack into a full-width list instead.
    val stackPresets = showPresets && h >= SLEEP_STACK_PRESETS_MIN_HEIGHT
    val button = when {
        stackPresets -> (
            (h - SLEEP_OUTER_PADDING_DP * 2 - caption - SLEEP_GAP_PRESETS_DP * 2) / 3
            ).coerceIn(30, SLEEP_STACK_PILL_MAX_DP)
        showPresets -> {
            // Presets are round, so one diameter has to satisfy both the row width and the
            // cell height (caption + gap above the row). Keeping one outer padding as a
            // gutter on each side stops the outermost circles touching (and clipping on)
            // the cell edge.
            val byWidth = (
                w - SLEEP_OUTER_PADDING_DP * 4 - SLEEP_GAP_PRESETS_DP * 2
                ) / 3
            minOf(byWidth, h - SLEEP_OUTER_PADDING_DP * 2 - caption)
                .coerceIn(28, SLEEP_PRESET_MAX_DP)
        }
        else -> (h / 4).coerceIn(28, 44)
    }
    // ── Active shapes ────────────────────────────────────────────────────
    // The active Cancel pill is deliberately more compact than the idle presets, so the
    // stacked shape can hold caption + ring + Cancel inside one cell. On a cell too short
    // for a caption *and* a legible ring — the 2x2 square, where the caption carries only
    // the moon anyway — the caption is dropped first; if even that is too tight the Cancel
    // pill shrinks, so the ring survives as the instrument instead of being replaced by the
    // number-over-bar fallback.
    val fullCancel = (h / 4).coerceIn(28, 40)
    val ringWithCaption = h - SLEEP_OUTER_PADDING_DP * 2 - caption - (fullCancel + SLEEP_STACK_GAP_DP)
    val ringNoCaption = h - SLEEP_OUTER_PADDING_DP * 2 - (fullCancel + SLEEP_STACK_GAP_DP)
    val ringCompactCancel =
        h - SLEEP_OUTER_PADDING_DP * 2 - SLEEP_COMPACT_CANCEL_DP - SLEEP_STACK_GAP_DP
    // The ring the stacked shapes would afford; the wide-row comparison below needs it, so
    // it is resolved from the same chain of shrinking choices.
    val stackedCandidate = when {
        !wideByRule && ringWithCaption >= SLEEP_COMPACT_RING_MIN_DP -> ringWithCaption
        !wideByRule && ringNoCaption >= SLEEP_COMPACT_RING_MIN_DP -> ringNoCaption
        !wideByRule && ringCompactCancel >= SLEEP_COMPACT_RING_MIN_DP -> ringCompactCancel
        else -> 0
    }
    // Wide row: sometimes the ring must shrink below its height-derived side so the
    // caption/Cancel column fits the width. A cell the classic aspect rule misses by a hair
    // (the 3x2, 6dp under the width gate; the 4x3, 12dp under the `w >= h*3/2` gate) still
    // gets the row when the shrunk ring is *bigger* than the ring stacking would afford —
    // otherwise it keeps the stacked shape instead of trading 2×46dp ridged gutters for it.
    val ringByHeight = h - SLEEP_OUTER_PADDING_DP * 2
    val wideRingShrunk = ringByHeight
        .coerceAtMost(w - SLEEP_OUTER_PADDING_DP * 2 - SLEEP_WIDE_RING_GAP_DP - SLEEP_WIDE_COLUMN_MIN_DP)
    val wide = wideByRule ||
        (w >= SLEEP_WIDE_ROW_MIN_WIDTH &&
            wideRingShrunk >= SLEEP_COMPACT_RING_MIN_DP &&
            wideRingShrunk > stackedCandidate)
    val activeCaption: Boolean
    val cancel: Int
    val stackedRing: Int
    when {
        wide && wideByRule -> {
            stackedRing = ringByHeight
            cancel = fullCancel
            activeCaption = showLabel
        }
        wide -> {
            stackedRing = wideRingShrunk
            cancel = minOf(fullCancel, SLEEP_WIDE_SHRUNK_CANCEL_DP)
            activeCaption = showLabel
        }
        ringWithCaption >= SLEEP_COMPACT_RING_MIN_DP -> {
            stackedRing = ringWithCaption
            cancel = fullCancel
            activeCaption = showLabel
        }
        ringNoCaption >= SLEEP_COMPACT_RING_MIN_DP -> {
            stackedRing = ringNoCaption
            cancel = fullCancel
            activeCaption = false
        }
        else -> {
            stackedRing = ringCompactCancel
            cancel = SLEEP_COMPACT_CANCEL_DP
            activeCaption = false
        }
    }
    // The stacked ring derives from the height alone, so on a narrow tall cell (2x4: 93dp
    // wide, ring up to ~141dp) the arcs ran off both edges of the squircle. Cap it by the
    // cell's padded width as well; the inner glyph and countdown scale with it below. The
    // cap itself floors at [40] so a degenerate (0dp) width still resolves to a ring.
    val ring = stackedRing
        .coerceAtLeast(40)
        .coerceAtMost(minOf(168, (w - SLEEP_OUTER_PADDING_DP * 2).coerceAtLeast(40)))
    val activeRing = ring >= SLEEP_COMPACT_RING_MIN_DP
    // A tall-but-narrow cell (2x3/2x4: ~94dp wide) can afford the full-height Cancel pill but
    // not its roomy padding — "× Cancel" would ellipsise to "× Ca…". Tighten the pill's own
    // metrics (not just its height) whenever the width is compact.
    val narrow = w < COMPACT_MAX_WIDTH
    // Inside the ring: the moon rides above the countdown only once the ring is wide enough
    // to keep both clear of the stroke; the `mm:ss` shrinks with the ring. Both values are
    // pure here so the "moon + time never touch the stroke" bound is unit-tested.
    val ringGlyph = if (ring >= SLEEP_MOON_MIN_RING_DP) (ring * 22 / 100).coerceIn(14, 38) else 0
    val ringTime = (ring * 22 / 100).coerceIn(12, 26)
    return SleepLayout(
        wide = wide,
        activeRing = activeRing,
        ringDp = ring,
        ringStrokeDp = if (ring >= 112) 8 else 6,
        ringGlyphDp = ringGlyph,
        ringTimeSp = ringTime,
        activeCaption = activeCaption,
        timeSp = if (activeRing) {
            (ring * 24 / 100).coerceIn(if (ring >= SLEEP_MIN_RING_DP) 15 else 12, 30)
        } else {
            (h * 24 / 100).coerceIn(22, 34)
        },
        showLabel = showLabel,
        // A shrunk-ring wide row leaves only a ~76dp column; when the cell is that tight the
        // caption rides as its moon glyph alone, since the words would not fit beside it.
        showLabelText = if (wide && stackedRing < ringByHeight) {
            false
        } else {
            w >= SLEEP_LABEL_TEXT_MIN_WIDTH
        },
        labelSp = if (w < COMPACT_MAX_WIDTH) 13 else 15,
        showPresets = showPresets,
        singlePresetMinutes = 30,
        stackPresets = stackPresets,
        buttonDp = button,
        buttonSp = (button * 28 / 100).coerceIn(13, 20),
        cancelDp = cancel,
        cancelSp = if (narrow) 12 else if (cancel >= 34) 14 else 12,
        cancelPadDp = if (cancel < 34) 8 else if (narrow) 10 else 12,
        iconDp = if (narrow) minOf(16, (cancel * 5 / 10).coerceIn(14, 20))
        else (cancel * 5 / 10).coerceIn(14, 20),
    )
}

/**
 * Estimated painted width (dp) of the non-fill Cancel pill for [layout]: its horizontal
 * padding, the "×" glyph, the gap beside it and the label. The label is approximated at
 * `0.55 x` its sp size per character (Roboto's average for "Cancel"), which is precise
 * enough to pin the "never ellipsised" bound for the narrowest cell without an emulator.
 */
internal fun sleepCancelWidthDp(layout: SleepLayout): Int {
    val text = (layout.cancelSp * 55 / 100) * "Cancel".length
    return layout.cancelPadDp * 2 + layout.iconDp + 6 + text
}

/** Sleep-widget surface padding (dp), mirrored by `WidgetDimens.outerPadding`. */
private const val SLEEP_OUTER_PADDING_DP: Int = 8

/** Gap (dp) between the three preset buttons. */
internal const val SLEEP_GAP_PRESETS_DP: Int = 6

// ── Next up / queue peek ──────────────────────────────────────────────

/**
 * Metrics for the text-only "Next up" widget.
 *
 * @property rows how many upcoming items are painted (0 when there is no room).
 * @property showHeader whether the "Next up · n of m" header + divider are painted.
 * @property showNowPlaying whether the "Now playing: …" line is painted.
 * @property showMore whether the "+N more" line is painted (driven by the hidden count).
 * @property dense `true` for a **one-line** row ("title … artist · time") on a short cell: a
 *   wide-but-short 6x2 spends two lines on a single item and leaves the rest of the cell
 *   empty, and the headerless 4x1 floor has no room for a two-line row at all. In dense mode
 *   the header's own "n of m" carries the queue count, so the separate "+N more" line is
 *   skipped rather than stealing the height of a real item.
 * @property headerSp header / position text size.
 * @property titleSp / [artistSp] per-row text sizes.
 */
data class NextUpLayout(
    val rows: Int,
    val showHeader: Boolean,
    val showNowPlaying: Boolean,
    val showMore: Boolean,
    val dense: Boolean,
    val headerSp: Int,
    val titleSp: Int,
    val artistSp: Int,
)

/** Hard cap on painted upcoming rows; leaves the outer Column well under the child cap. */
const val NEXT_UP_MAX_ROWS: Int = 6

/** Height (dp) at/above which the "Next up" header + divider are kept. */
const val NEXT_UP_HEADER_MIN_HEIGHT: Int = 76

/** Height (dp) at/above which the extra "Now playing: …" line is kept. */
const val NEXT_UP_NOW_PLAYING_MIN_HEIGHT: Int = 150

/**
 * Measured height (dp) of one two-line queue row: a 12sp title over a 10sp artist plus a
 * small gap. Pinned rather than guessed, like `LYRICS_ROW_HEIGHT_DP`.
 */
internal const val NEXT_UP_ROW_HEIGHT_DP: Int = 34

/**
 * Height (dp) of one **dense** single-line row (see [NextUpLayout.dense]): a 12sp line plus
 * its gap. Smaller than [NEXT_UP_ROW_HEIGHT_DP], which is exactly why a 6x2 fits two items
 * instead of one.
 */
internal const val NEXT_UP_DENSE_ROW_HEIGHT_DP: Int = 21

/** Width (dp) at/above which a short cell switches to dense single-line rows. */
const val NEXT_UP_DENSE_MIN_WIDTH: Int = WIDE_MIN_WIDTH

/** Vertical chrome the composer paints around the rows (padding, header, divider, lines). */
private const val NEXT_UP_OUTER_PADDING_DP: Int = 8
private const val NEXT_UP_HEADER_DP: Int = 20
private const val NEXT_UP_DIVIDER_DP: Int = 1
private const val NEXT_UP_LINE_GAP_DP: Int = 6

/**
 * Maps free vertical space (dp, all chrome removed) to a visible-row count, capped.
 *
 * @param rowHeightDp pitch of one row: [NEXT_UP_ROW_HEIGHT_DP] for a two-line row, or
 *   [NEXT_UP_DENSE_ROW_HEIGHT_DP] for the dense single-line form.
 */
fun nextUpRowsForFreeHeight(
    freeDp: Int,
    rowHeightDp: Int = NEXT_UP_ROW_HEIGHT_DP,
): Int {
    val pitch = rowHeightDp.coerceAtLeast(1)
    return (freeDp / pitch).coerceIn(0, NEXT_UP_MAX_ROWS)
}

/**
 * Picks the "Next up" composition for a widget of [widthDp] x [heightDp].
 *
 * @param hasMore whether any upcoming items are hidden (`+N more`); reserving a line for
 *   it is what keeps the row count honest instead of pushing the last row off the cell.
 */
fun nextUpLayout(widthDp: Int, heightDp: Int, hasMore: Boolean = false): NextUpLayout {
    val w = widthDp.coerceAtLeast(1)
    val h = heightDp.coerceAtLeast(1)
    val showHeader = h >= NEXT_UP_HEADER_MIN_HEIGHT
    val showNowPlaying = h >= NEXT_UP_NOW_PLAYING_MIN_HEIGHT
    // A wide-but-short cell (6x2) would spend two lines per item and leave the lower half
    // empty, and the headerless 4x1 floor cannot hold a two-line row at all: both go dense.
    val dense = !showNowPlaying && (!showHeader || w >= NEXT_UP_DENSE_MIN_WIDTH)
    val rowPitch = if (dense) NEXT_UP_DENSE_ROW_HEIGHT_DP else NEXT_UP_ROW_HEIGHT_DP
    var chrome = NEXT_UP_OUTER_PADDING_DP * 2
    if (showHeader) chrome += NEXT_UP_HEADER_DP + NEXT_UP_LINE_GAP_DP + NEXT_UP_DIVIDER_DP + NEXT_UP_LINE_GAP_DP
    if (showNowPlaying) chrome += NEXT_UP_HEADER_DP / 2 + NEXT_UP_LINE_GAP_DP
    // Dense rows only exist when the header is (or its "n of m" is) on screen, so the extra
    // "+N more" line would be redundant *and* cost a whole row: skip it in dense mode.
    val shortLine = if (dense) 0 else NEXT_UP_HEADER_DP / 2 + NEXT_UP_LINE_GAP_DP
    val rowsWithMore = nextUpRowsForFreeHeight(h - chrome - (if (hasMore) shortLine else 0), rowPitch)
    // "+N more" with no listed row is noise: if reserving its line would leave zero rows,
    // drop it and spend the space on a real row instead.
    val showMore = hasMore && !dense && rowsWithMore > 0
    val rows = if (hasMore && rowsWithMore == 0) nextUpRowsForFreeHeight(h - chrome, rowPitch) else rowsWithMore
    return NextUpLayout(
        rows = rows,
        showHeader = showHeader,
        showNowPlaying = showNowPlaying,
        showMore = showMore,
        dense = dense,
        headerSp = if (w < COMPACT_MAX_WIDTH) 12 else 13,
        titleSp = if (dense) 12 else if (w < COMPACT_MAX_WIDTH) 12 else 13,
        artistSp = if (dense) 10 else if (w < COMPACT_MAX_WIDTH) 10 else 11,
    )
}

// ── Shuffle roulette ──────────────────────────────────────────────────

/**
 * Metrics for the "Shuffle Roulette" widget.
 *
 * @property showStatus whether the shuffle state readout ("SHUFFLE ON/OFF", or the header
 *   row when [showHeader]) is painted. A 1-row cell (44dp) drops it — the status block plus
 *   even a minimum SPIN pill overflows the ~28dp inner height.
 * @property showHeader whether the "Shuffle Roulette" header row is painted.
 * @property showTitle whether the current-track title is painted.
 * @property showArtist whether the current-track artist is painted.
 * @property showShuffleToggle whether the small shuffle toggle accompanies SPIN.
 * @property spread `true` for a **wide, short** cell with no header (4x2 5x2 6x2): the state +
 *   current track sit on the left and SPIN on the right, so the extra width is used instead
 *   of leaving two mid-size dead gutters around a centred stack (mirrors
 *   `LyricsLayout.spreadHeader`).
 * @property showHint whether the small "Tap SPIN to shuffle" hint is painted under SPIN.
 *   On a tall cell (4x4/4x5) SPIN alone cannot absorb all the spare height, so the hint
 *   fills the bottom band instead of leaving a lopsided void between the header and the
 *   title.
 * @property spinDp diameter of the round SPIN button.
 * @property shuffleDp diameter of the shuffle toggle.
 * @property headerSp / [titleSp] / [artistSp] text sizes.
 */
data class RouletteLayout(
    val showStatus: Boolean,
    val showHeader: Boolean,
    val showTitle: Boolean,
    val showArtist: Boolean,
    val showShuffleToggle: Boolean,
    val spread: Boolean,
    val showHint: Boolean,
    val spinDp: Int,
    val shuffleDp: Int,
    val headerSp: Int,
    val titleSp: Int,
    val artistSp: Int,
)

/** Height (dp) at/above which the "Shuffle Roulette" header is kept. */
const val ROULETTE_HEADER_MIN_HEIGHT: Int = 130

/**
 * Height (dp) at/above which the shuffle state readout is kept. A 1-row cell (~44dp) drops
 * it: the 18dp status block plus even a minimum SPIN pill overflows the ~28dp inner column.
 */
const val ROULETTE_STATUS_MIN_HEIGHT: Int = 64

/** Height (dp) at/above which the current artist line is kept. */
const val ROULETTE_ARTIST_MIN_HEIGHT: Int = 120

/** Width (dp) at/above which the shuffle toggle sits beside SPIN. */
const val ROULETTE_TOGGLE_MIN_WIDTH: Int = 150

/** Width (dp) at/above which a headerless cell spreads text + SPIN side by side (4x2+). */
const val ROULETTE_SPREAD_MIN_WIDTH: Int = 234

/** Height (dp) at/above which the "Tap SPIN to shuffle" hint is painted under SPIN. */
const val ROULETTE_HINT_MIN_HEIGHT: Int = 196

/** Upper bound for the SPIN pill; on a tall cell the extra room also feeds the hint. */
const val ROULETTE_SPIN_MAX_DP: Int = 112

/** Floor for the SPIN pill diameter on a cell tall enough for it (see [rouletteLayout]). */
const val ROULETTE_SPIN_MIN_DP: Int = 34

/** Smallest shuffle-toggle circle the wide shapes paint. */
const val ROULETTE_TOGGLE_MIN_DP: Int = 30

/** Largest shuffle-toggle circle (the tall cells' SPIN companion). */
const val ROULETTE_TOGGLE_MAX_DP: Int = 48

/**
 * Picks the roulette composition for a widget of [widthDp] x [heightDp].
 *
 * The 4x2 reference is a compact status/title/SPIN stack — wide enough now to spread the
 * text beside SPIN rather than strand it mid-cell. Taller cells add the header and the
 * artist line and grow SPIN, and the tallest cells add the hint line so the space between
 * the header and the title stays tight. Wider cells add the small shuffle toggle beside
 * SPIN; a wide *and* short cell ([spread]) moves the text beside SPIN instead of stacking,
 * so the width is used rather than left as dead gutters.
 */
fun rouletteLayout(widthDp: Int, heightDp: Int): RouletteLayout {
    val w = widthDp.coerceAtLeast(1)
    val h = heightDp.coerceAtLeast(1)
    val showHeader = h >= ROULETTE_HEADER_MIN_HEIGHT
    val showTitle = h >= 64
    val showArtist = h >= ROULETTE_ARTIST_MIN_HEIGHT
    val showHint = h >= ROULETTE_HINT_MIN_HEIGHT
    // A 1-row cell (~28dp of inner height) cannot afford the status block AND a minimum
    // read pill, so the state line is dropped there and the pill takes the whole height.
    val showStatus = h >= ROULETTE_STATUS_MIN_HEIGHT
    val statusBlock = if (showStatus) ROULETTE_STATUS_BLOCK_DP else 0
    val titleBlock = if (showTitle) ROULETTE_TITLE_BLOCK_DP else 0
    val artistBlock = if (showArtist) ROULETTE_ARTIST_BLOCK_DP else 0
    val hintBlock = if (showHint) ROULETTE_HINT_BLOCK_DP else 0
    val raw = (h - ROULETTE_OUTER_PADDING_DP * 2 - statusBlock - titleBlock - artistBlock - hintBlock)
    // The 34dp floor keeps the pill readable and tappable on every cell that affords it,
    // but never above the height that is actually left: a 44dp cell shrinks the pill to
    // its available height instead of slicing it flat by the bottom edge (the old floor
    // overflowed the inner column by ~24dp).
    val spin = raw.coerceAtMost(ROULETTE_SPIN_MAX_DP)
        .coerceAtLeast(minOf(ROULETTE_SPIN_MIN_DP, raw).coerceAtLeast(0))
    return RouletteLayout(
        showStatus = showStatus,
        showHeader = showHeader,
        showTitle = showTitle,
        showArtist = showArtist,
        showShuffleToggle = w >= ROULETTE_TOGGLE_MIN_WIDTH,
        // The spread shape covers the headerless, short cells from the 4x2 (~234dp) up: at
        // the old 320dp gate the 4x2/5x2 stranded their centred stack between 59–94dp of
        // dead gutters on each side.
        spread = !showHeader && showStatus && w >= ROULETTE_SPREAD_MIN_WIDTH,
        showHint = showHint,
        spinDp = spin,
        shuffleDp = (spin * 3 / 4).coerceIn(minOf(ROULETTE_TOGGLE_MIN_DP, spin), ROULETTE_TOGGLE_MAX_DP),
        headerSp = 12,
        titleSp = if (w < COMPACT_MAX_WIDTH) 13 else if (showHint) 17 else 15,
        artistSp = if (w < COMPACT_MAX_WIDTH) 10 else if (showHint) 13 else 12,
    )
}

/** Roulette surface padding (dp), mirrored by `WidgetDimens.outerPadding`. */
private const val ROULETTE_OUTER_PADDING_DP: Int = 8

/** Height reserved for the always-present shuffle status line. */
private const val ROULETTE_STATUS_BLOCK_DP: Int = 18

/** Height reserved for the current-track title line. */
private const val ROULETTE_TITLE_BLOCK_DP: Int = 20

/** Height reserved for the current-track artist line. */
private const val ROULETTE_ARTIST_BLOCK_DP: Int = 16

/** Height reserved for the "Tap SPIN to shuffle" hint line (its text plus the gap above). */
internal const val ROULETTE_HINT_BLOCK_DP: Int = 22

/**
 * Picks the lyrics composition for a widget of [widthDp] x [heightDp].
 *
 * Graceful degradation, top to bottom:
 *  - below [LYRICS_HEADER_MIN_HEIGHT] the header is dropped (lyrics only);
 *  - below [LYRICS_TRANSPORT_MIN_HEIGHT] the transport row **and** the progress bar are
 *    dropped together (they share [LYRICS_PROGRESS_MIN_HEIGHT]);
 *  - the visible line count is derived from the height that is actually left.
 *
 * A **wide, short** cell (e.g. 6x2, `>= 320dp` wide with no transport) additionally moves
 * the header *beside* the lyric block ([LyricsLayout.spreadHeader]): the box has plenty of
 * width but no spare height, so stacking the header on top would leave the right half of
 * the widget empty. Beside it, the lyrics get the full height and the width is filled.
 *
 * Narrow cells additionally shrink the header thumb and the text sizes.
 */
fun lyricsLayout(widthDp: Int, heightDp: Int): LyricsLayout {
    val w = widthDp.coerceAtLeast(1)
    val h = heightDp.coerceAtLeast(1)
    val compact = w < COMPACT_MAX_WIDTH
    val showHeader = h >= LYRICS_HEADER_MIN_HEIGHT
    val showTransport = h >= LYRICS_TRANSPORT_MIN_HEIGHT
    val showProgress = h >= LYRICS_PROGRESS_MIN_HEIGHT && showTransport
    val spreadHeader = showHeader && !showTransport && w >= WIDE_MIN_WIDTH
    val skipDp = if (compact) 30 else 34
    val playDp = if (compact) 34 else 38
    // The three-button cluster (2 × skip + play + two gaps) must fit the padded width: on a
    // ~94dp-wide cell (~77dp inner) the ~114dp cluster overflowed by ~37dp and sliced the
    // skip glyphs at both widget edges. A cell that tight falls back to the play/pause pill
    // alone — still centred and tappable — and keeps the full trio wherever it fits.
    val showSkip = showTransport &&
        skipDp * 2 + playDp + 2 * LYRICS_TRANSPORT_GAP_DP <= w - LYRICS_OUTER_PADDING_DP * 2
    return LyricsLayout(
        // When the header is beside the lyrics it costs no vertical space, so the line
        // count is derived without its chrome.
        windowSize = lyricLinesForHeight(
            h,
            showHeader = showHeader && !spreadHeader,
            showProgress = showProgress,
            showTransport = showTransport,
        ),
        showHeader = showHeader,
        spreadHeader = spreadHeader,
        headerWidthDp = if (spreadHeader) (w * 35 / 100).coerceIn(112, 160) else 0,
        showProgress = showProgress,
        showTransport = showTransport,
        titleSp = if (compact) 13 else 15,
        artistSp = if (compact) 10 else 12,
        currentSp = if (compact) 15 else 16,
        neighborSp = if (compact) 12 else 13,
        headerCoverDp = if (compact) 30 else 38,
        showSkip = showSkip,
        skipDp = skipDp,
        playDp = playDp,
    )
}

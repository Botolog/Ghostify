package xyz.botolog.ghostify.widget

import xyz.botolog.ghostify.player.core.PlayerUiState

/**
 * Everything the home-screen widget renders, as an immutable, Android-light value.
 *
 * This is the single boundary between the player (Android/compose-free core) and the
 * widget (Glance/RemoteViews). It deliberately carries no Android types beyond
 * [ByteArray] so the mapping logic stays unit-testable on the JVM.
 *
 * Artwork is carried as raw [coverBytes] rather than a `java.io.File`: a Glance widget
 * is inflated by the *host* (launcher) process, which cannot read our app-private
 * `filesDir`, so a `file://` URI would render blank. The bytes are decoded into a
 * `Bitmap` inside the widget process just before composition.
 *
 * @property songId database id of the current track, or `null` when nothing is playing.
 * @property title display title (never null; empty when unknown).
 * @property artist display artist (never null; empty when unknown).
 * @property album album name, or `null`.
 * @property isPlaying whether audio is actively playing.
 * @property positionMs current playback position in milliseconds.
 * @property durationMs total duration of the current track in milliseconds.
 * @property hasQueue whether the player holds a non-empty queue.
 * @property coverBytes raw JPEG/PNG artwork bytes, or `null`.
 * @property lyricsLines plain lyric lines (pass 2), empty when unavailable.
 * @property currentLineIndex index of the active lyric line, or `-1` (pass 2).
 * @property sleepTimerActive whether the app-process sleep timer is counting down. The
 *   timer is deliberately never persisted, so the cold path always reports `false`.
 * @property sleepTimerRemainingMs time left on the sleep timer, in milliseconds.
 * @property sleepTimerTotalMs duration the running sleep timer was started with, or `0`.
 * @property nextUp the next few upcoming queue items (text only, capped at [NEXT_UP_CAP]),
 *   in playback order.
 * @property queueCount number of items in the whole queue (`nextUp.size + currentIndex + 1`
 *   when the queue is intact); used for the "+N more" affordance and the "3 of 12" label.
 * @property currentQueueIndex playlist-order index of the current item, or `-1`.
 * @property shuffleEnabled whether shuffle mode is active.
 */
data class WidgetSnapshot(
    val songId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val hasQueue: Boolean,
    val coverBytes: ByteArray? = null,
    val lyricsLines: List<String> = emptyList(),
    val currentLineIndex: Int = -1,
    val sleepTimerActive: Boolean = false,
    val sleepTimerRemainingMs: Long = 0L,
    val sleepTimerTotalMs: Long = 0L,
    val nextUp: List<NextUpItem> = emptyList(),
    val queueCount: Int = 0,
    val currentQueueIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
) {

    /** `true` when there is nothing to render (cold start with no saved track). */
    val isEmpty: Boolean get() = songId == null

    /**
     * Fraction of the sleep timer still to run, in `0f..1f`, for the ring.
     *
     * `1f` when a full-duration timer has just started, `0f` when it is inactive or a
     * whole countdown has elapsed. Mirrors `SleepTimerState.fractionRemaining` without
     * pulling the core type into the widget.
     */
    fun sleepFraction(): Float {
        if (!sleepTimerActive || sleepTimerTotalMs <= 0L) return 0f
        return (sleepTimerRemainingMs.toFloat() / sleepTimerTotalMs.toFloat()).coerceIn(0f, 1f)
    }

    /** Number of upcoming items hidden beyond the visible [nextUp] window (never negative). */
    fun hiddenNextUpCount(): Int =
        (queueCount - (currentQueueIndex + 1) - nextUp.size).coerceAtLeast(0)

    /**
     * Playback progress in `0f..1f`, used by the thin progress indicator.
     *
     * Returns `0f` for an unknown/zero duration instead of dividing by zero.
     */
    fun progressFraction(): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Key covering only what is *visible* on the widget.
     *
     * Position is quantised to whole seconds so the per-tick player position (which
     * changes several times a second) does not by itself mark the widget dirty; a
     * track/play-state/artwork/lyric-line change always does.
     */
    fun updateKey(): String = listOf(
        songId,
        title,
        artist,
        album,
        isPlaying,
        hasQueue,
        durationMs / 1000L,
        positionMs / 1000L,
        currentLineIndex,
        lyricsLines.size,
        coverBytes?.size ?: -1,
        sleepTimerActive,
        // Quantised to whole seconds: the countdown changes visibly once a second, so a
        // 500ms ticker tick must not, by itself, mark the widget dirty.
        sleepTimerRemainingMs / 1000L,
        shuffleEnabled,
        queueCount,
        currentQueueIndex,
        nextUp.size,
        nextUp.firstOrNull()?.title,
    ).joinToString("|")

    /**
     * Key for changes that must repaint the widget *immediately* (no throttle):
     * the track, the play/pause state, and the sleep-timer / shuffle toggles (which the
     * user just tapped and expects to see reflected at once).
     */
    fun immediateKey(): String = "$songId|$isPlaying|$hasQueue|$sleepTimerActive|$shuffleEnabled"

    companion object {
        /** The "nothing playing" snapshot drawn by a cold, empty process. */
        val EMPTY = WidgetSnapshot(
            songId = null,
            title = "",
            artist = "",
            album = null,
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            hasQueue = false,
        )
    }
}

/**
 * Formats a millisecond duration as `m:ss` (e.g. `65000` -> `"1:05"`).
 *
 * Negative values are clamped to zero; hours roll into minutes (`60:00`).
 */
fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/**
 * The secondary line the **wide** player paints: the artist, with the album appended when
 * both are known (`"Neon Tides · Static Bloom"`).
 *
 * A 6x2 cell is exactly as tall as a 4x2 one, so a separate stacked album line pushed the
 * transport past the bottom edge (see [PlayerLayoutKind.WIDE]); putting the album on the
 * artist line keeps the information and spends the cell's spare *width* instead. Blank
 * parts collapse so a missing album never leaves a dangling separator. Pure.
 */
fun playerSecondaryLine(artist: String, album: String?): String {
    val trimmedArtist = artist.trim()
    val trimmedAlbum = album.orEmpty().trim()
    return when {
        trimmedArtist.isEmpty() -> trimmedAlbum
        trimmedAlbum.isEmpty() -> trimmedArtist
        else -> "$trimmedArtist · $trimmedAlbum"
    }
}

/**
 * One upcoming queue entry, as the "Next up" widget paints it.
 *
 * Deliberately **text only**: the OOM lesson is that artwork must never be carried for
 * non-current queue items, so this type has no bytes/paths at all.
 *
 * @property title display title (never null; empty when unknown).
 * @property artist display artist (never null; empty when unknown).
 * @property durationMs track duration in milliseconds, or `0` when unknown.
 * @property indexInQueue playlist-order index this item will play at.
 */
data class NextUpItem(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val indexInQueue: Int,
)

/** Upper bound on upcoming items carried in a snapshot; the widget shows fewer by height. */
const val NEXT_UP_CAP: Int = 6

/**
 * The upcoming items after [currentIndex], capped at [cap]. Pure and JVM-testable.
 *
 * `currentIndex < 0` (nothing current) is treated as "the queue starts here", so the
 * whole queue is peeked rather than dropping its first entry.
 */
fun nextUpFromQueue(
    queue: List<xyz.botolog.ghostify.player.core.QueueItem>,
    currentIndex: Int,
    cap: Int = NEXT_UP_CAP,
): List<NextUpItem> {
    if (queue.isEmpty() || cap <= 0) return emptyList()
    val start = (currentIndex + 1).coerceIn(0, queue.size)
    return queue.drop(start).take(cap).map { item ->
        NextUpItem(
            title = item.title.orEmpty(),
            artist = item.artist.orEmpty(),
            durationMs = item.durationMs ?: 0L,
            indexInQueue = item.indexInQueue,
        )
    }
}

/**
 * The song ids to peek at on the cold path: the queue entries *after* [currentSongId].
 *
 * Pure so the "which few rows do we spend queries on" decision is pinned down on the JVM
 * rather than tangled into the Room read. When the current id is not in the queue (or the
 * queue is empty) the first [cap] ids are returned.
 */
fun nextUpQueueIds(queueSongIds: List<String>, currentSongId: String?, cap: Int = COLD_NEXT_UP_CAP): List<String> {
    if (queueSongIds.isEmpty() || cap <= 0) return emptyList()
    val current = currentSongId?.let { id -> queueSongIds.indexOf(id) } ?: -1
    val start = if (current in queueSongIds.indices) current + 1 else 0
    return queueSongIds.drop(start).take(cap)
}

/**
 * Upper bound on the Room lookups the cold widget path spends rebuilding "Next up".
 *
 * The player persists only the queue's *ids*, so every upcoming row costs one
 * `SongDao.getById`; three keeps a cold launch cheap while still filling a 4x3 cell.
 */
const val COLD_NEXT_UP_CAP: Int = 3

/**
 * Builds a [WidgetSnapshot] from the player's core [PlayerUiState].
 *
 * Pure: no Android, no I/O. Artwork, lyrics and the active lyric line are passed in
 * by the caller (which owns the side effects).
 */
fun snapshotFromPlayerState(
    state: PlayerUiState,
    coverBytes: ByteArray? = null,
    lyricsLines: List<String> = emptyList(),
    currentLineIndex: Int = -1,
): WidgetSnapshot {
    val item = state.currentItem
    if (item?.mediaId.isNullOrBlank()) return WidgetSnapshot.EMPTY
    return WidgetSnapshot(
        songId = item.mediaId,
        title = item.title.orEmpty(),
        artist = item.artist.orEmpty(),
        album = item.album,
        isPlaying = state.isPlaying,
        positionMs = state.positionMs,
        durationMs = state.durationMs,
        hasQueue = state.hasQueue,
        coverBytes = coverBytes,
        lyricsLines = lyricsLines,
        currentLineIndex = currentLineIndex,
        sleepTimerActive = state.sleepTimerActive,
        sleepTimerRemainingMs = state.sleepTimerRemainingMs,
        sleepTimerTotalMs = state.sleepTimerTotalMs,
        nextUp = nextUpFromQueue(state.queue, state.currentQueueIndex),
        queueCount = state.queue.size,
        currentQueueIndex = state.currentQueueIndex,
        shuffleEnabled = state.shuffleEnabled,
    )
}

package xyz.botolog.ghostify.widget

import xyz.botolog.ghostify.data.db.entity.PlaylistEntity

/**
 * One playlist tile as the "Playlists" widget paints it: a short label plus an optional
 * small local cover.
 *
 * The cover is carried as **bytes**, never a `file://` path, for the same reason the
 * player artwork is (a widget is inflated by the launcher process, which cannot read our
 * private `filesDir`). It is bounded on purpose: [PLAYLIST_TILE_CAP] tiles at most, each
 * already decoded down to a small size by the provider (see `WidgetCovers`), which is the
 * "no artwork for queue items" lesson applied to a genuine, tiny exception — four
 * per-playlist thumbnails.
 *
 * @property id playlist row id, used as the tap action's parameter.
 * @property name display name (never null; falls back to a placeholder at paint time).
 * @property trackCount number of tracks, for the optional count line.
 * @property coverBytes small local cover bytes, or `null` for the placeholder glyph.
 */
data class PlaylistTile(
    val id: String,
    val name: String,
    val trackCount: Int,
    val coverBytes: ByteArray? = null,
)

/** Hard cap on tiles the widget ever paints (and thus on cover bytes it carries). */
const val PLAYLIST_TILE_CAP: Int = 4

/**
 * Maps the playlists to paint, in order, keeping the top [cap].
 *
 * Pure (the cover lookup is injected) so the "which playlists and how many" decision is
 * JVM-tested; the caller owns the file read.
 */
fun playlistTilesFrom(
    playlists: List<PlaylistEntity>,
    cap: Int = PLAYLIST_TILE_CAP,
    cover: (PlaylistEntity) -> ByteArray? = { null },
): List<PlaylistTile> {
    if (cap <= 0) return emptyList()
    return playlists.take(cap).map { playlist ->
        PlaylistTile(
            id = playlist.id,
            name = playlist.name,
            trackCount = playlist.trackCount,
            coverBytes = cover(playlist),
        )
    }
}

package xyz.botolog.ghostify.data.db.entity

/**
 * Minimal projection of a [SongEntity] used to rewrite stored ordering.
 *
 * Maps to the `songs` table but carries only the primary key and the
 * `position` column, so a playlist sort rewrites order without touching any of
 * the other columns. That matters because the download manager writes
 * `status` / `file_path` / `downloaded_at` concurrently: a full-entity update
 * could resurrect a stale status over a fresh download.
 *
 * Deliberately **not** an [androidx.room.Entity] — a partial `@Entity` would be
 * validated against the physical table (which has more columns) and fail
 * Room's schema identity check. Room maps a plain data class used in a DAO
 * method onto the table that owns all of its columns instead.
 *
 * @property id the song row id (primary key of the `songs` table).
 * @property position the new 0-based position within the playlist.
 */
data class SongPositionUpdate(
    val id: String,
    val position: Int,
)

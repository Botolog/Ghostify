package xyz.botolog.ghostify.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Simple key/value preferences store backed by Room.
 *
 * Each row represents a single user preference. The [key] is the primary
 * key and is used for upserts (insert-or-replace semantics).
 *
 * @property key Unique preference identifier (e.g. "storage_dir", "default_bitrate").
 * @property value Serialised preference value as a string.
 */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey
    val key: String,

    val value: String,
)

package com.ghostify.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Simple key/value preferences store. */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey
    val key: String,

    val value: String
)

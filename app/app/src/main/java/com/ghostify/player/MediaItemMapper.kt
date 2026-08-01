package com.ghostify.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.ghostify.player.core.QueueItem

/**
 * Converts a [QueueItem] into the [MediaItem] handed to ExoPlayer.
 *
 * The DB-provided title/artist/album plus extracted artwork are embedded in the item's
 * [MediaMetadata] so the player, the MediaSession and the notification/lockscreen all show
 * the same now-playing information without any extra lookups.
 */
object MediaItemMapper {

    fun toMediaItem(item: QueueItem, artworkBytes: ByteArray?): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.artist)
            .setAlbumTitle(item.album)
            .setDurationMs(item.durationMs ?: C.TIME_UNSET)

        artworkBytes?.let { bytes ->
            metadataBuilder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        return MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(item.filePath)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }
}

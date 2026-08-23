package xyz.botolog.ghostify.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import xyz.botolog.ghostify.player.core.QueueItem
import timber.log.Timber

/**
 * Converts a [QueueItem] into the [MediaItem] handed to ExoPlayer.
 *
 * The DB-provided title/artist/album plus extracted artwork are embedded in the item's
 * [MediaMetadata] so the player, the MediaSession and the notification/lockscreen all show
 * the same now-playing information without any extra lookups.
 */
object MediaItemMapper {

    /**
     * Maps a [QueueItem] to a Media3 [MediaItem] with full metadata.
     *
     * @param item the queue item to convert.
     * @param artworkBytes optional embedded artwork bytes (typically JPEG).
     * @return a fully-built [MediaItem] ready for ExoPlayer.
     */
    fun toMediaItem(item: QueueItem, artworkBytes: ByteArray?): MediaItem {
        Timber.i("MediaItemMapper.toMediaItem: START mediaId=${item.mediaId}")
        val metadata = buildMetadata(item, artworkBytes)
        val result = MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(item.filePath)
            .setMediaMetadata(metadata)
            .build()
        Timber.i("MediaItemMapper.toMediaItem: returning ${result.mediaId}")
        return result
    }

    /** Builds [MediaMetadata] with title, artist, album, duration and optional artwork. */
    private fun buildMetadata(item: QueueItem, artworkBytes: ByteArray?): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.artist)
            .setAlbumTitle(item.album)
            .setDurationMs(item.durationMs ?: C.TIME_UNSET)
        artworkBytes?.let { bytes ->
            builder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return builder.build()
    }
}

package xyz.botolog.ghostify.player

import android.net.Uri
import android.os.Bundle
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
     * @param artworkBytes optional embedded artwork bytes (typically JPEG) for notification/lockscreen.
     * @param artworkUri optional artwork URI for Android Auto browse tree display.
     * @return a fully-built [MediaItem] ready for ExoPlayer.
     */
    fun toMediaItem(item: QueueItem, artworkBytes: ByteArray?, artworkUri: Uri? = null): MediaItem {
        Timber.i("MediaItemMapper.toMediaItem: START mediaId=${item.mediaId}")
        val metadata = buildMetadata(item, artworkBytes, artworkUri)
        val result = MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(item.filePath)
            .setMediaMetadata(metadata)
            .build()
        Timber.i("MediaItemMapper.toMediaItem: returning ${result.mediaId}")
        return result
    }

    /** Builds [MediaMetadata] with title, artist, album, duration, optional artwork and lyrics. */
    private fun buildMetadata(item: QueueItem, artworkBytes: ByteArray?, artworkUri: Uri?): MediaMetadata {
        val extras = Bundle()
        item.lyrics?.let { extras.putString(EXTRA_LYRICS, it) }
        val builder = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.artist)
            .setAlbumTitle(item.album)
            .setDurationMs(item.durationMs ?: C.TIME_UNSET)
            .setExtras(extras)
        artworkBytes?.let { bytes ->
            builder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        artworkUri?.let { uri ->
            builder.setArtworkUri(uri)
        }
        return builder.build()
    }

    private const val EXTRA_LYRICS = "xyz.botolog.ghostify.LYRICS"
}

package xyz.botolog.ghostify.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.player.core.PlayerQueueBuilder
import xyz.botolog.ghostify.player.core.QueueBuildResult
import xyz.botolog.ghostify.player.core.QueueItem
import xyz.botolog.ghostify.player.core.RepeatMode
import xyz.botolog.ghostify.player.core.Song
import xyz.botolog.ghostify.player.core.SongStatus

/**
 * Only the *current* item may carry `MediaMetadata.artworkData`.
 *
 * media3's legacy session stub (`MediaSessionLegacyStub.updateQueue`) walks the whole timeline
 * and decodes every item's `artworkData` into a full-resolution bitmap, so stamping artwork on
 * the entire queue retains ~1.5 MiB native per item and OOMs the app. The stock
 * `DefaultMediaNotificationProvider` only reads the current item, so the fix is: attach art to
 * the item playback starts on, and (re)apply it — clearing the previous one — on every
 * media-item transition.
 */
class PlayerControllerArtworkBackfillTest {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var controller: PlayerController
    private lateinit var controllerScope: CoroutineScope
    private val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private lateinit var mediaItems: MutableList<MediaItem>
    private var currentIndex = 0
    private var playWhenReady = true
    private var position = 5_000L
    private var timelineApplied = false

    private fun artFor(id: String) = "art-$id".toByteArray()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        currentIndex = 0
        playWhenReady = true
        position = 5_000L
        timelineApplied = false
        mediaItems = mutableListOf()
        exoPlayer = mockk(relaxed = true)
        every { exoPlayer.currentMediaItem } answers { mediaItems.getOrNull(currentIndex) }
        every { exoPlayer.currentMediaItemIndex } answers { currentIndex }
        every { exoPlayer.getMediaItemAt(any()) } answers { mediaItems[firstArg()] }
        every { exoPlayer.currentPosition } answers { position }
        every { exoPlayer.isPlaying } answers { playWhenReady }
        every { exoPlayer.playWhenReady } answers { playWhenReady }
        every { exoPlayer.isLoading } returns false
        every { exoPlayer.mediaItemCount } answers { mediaItems.size }
        every { exoPlayer.playbackState } returns Player.STATE_READY
        every { exoPlayer.duration } returns 180_000L
        every { exoPlayer.mediaMetadata } answers {
            mediaItems.getOrNull(currentIndex)?.mediaMetadata ?: MediaItem.Builder().build().mediaMetadata
        }
        every { exoPlayer.repeatMode } returns Player.REPEAT_MODE_OFF
        every { exoPlayer.shuffleModeEnabled } returns false
        every { exoPlayer.volume } returns 0.8f
        every { exoPlayer.play() } answers { playWhenReady = true }
        every { exoPlayer.pause() } answers { playWhenReady = false }
        every { exoPlayer.replaceMediaItem(any<Int>(), any()) } answers {
            mediaItems[firstArg<Int>()] = secondArg()
        }
        every { exoPlayer.addMediaItem(any<Int>(), any()) } answers {
            mediaItems.add(firstArg<Int>(), secondArg())
        }
        every { exoPlayer.seekTo(any<Int>(), any<Long>()) } answers {
            currentIndex = firstArg()
            position = secondArg()
        }
        every { exoPlayer.setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>()) } answers {
            mediaItems = firstArg<List<MediaItem>>().toMutableList()
            currentIndex = secondArg()
            timelineApplied = true
        }
        every { exoPlayer.addMediaItems(any<List<MediaItem>>()) } answers {
            mediaItems.addAll(firstArg())
        }
    }

    @After
    fun tearDown() {
        try {
            if (::controller.isInitialized) controller.release()
            if (::controllerScope.isInitialized) controllerScope.cancel()
        } finally {
            Dispatchers.resetMain()
        }
        if (uncaught.isNotEmpty()) throw AssertionError(uncaught.first())
    }

    private fun buildController(
        extractor: ArtworkExtractor,
        coverProvider: ((String, String?) -> ByteArray?)? = null,
        restore: PlayerStatePersistence.SavedState? = null,
    ) {
        uncaught.clear()
        timelineApplied = false
        controllerScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> uncaught.add(e) },
        )
        val persistence = mockk<PlayerStatePersistence>(relaxed = true)
        coEvery { persistence.load() } returns restore
        coEvery { persistence.restoreSongs(any()) } returns ids().map(::queueItem).map(::song)
        val queueBuilder = mockk<PlayerQueueBuilder>()
        every { queueBuilder.build(any(), any()) } answers {
            QueueBuildResult.Ready(
                items = ids().map(::queueItem),
                startIndex = 0,
                startSongId = secondArg<String?>(),
            )
        }
        val ctor = PlayerController::class.java.declaredConstructors
            .first { it.parameterCount == 8 }
            .apply { isAccessible = true }
        controller = ctor.newInstance(
            mockk<Context>(relaxed = true),
            exoPlayer,
            queueBuilder,
            extractor,
            null,
            controllerScope,
            persistence,
            flowOf(true),
        ) as PlayerController
        controller.startPlaybackService = {}
        if (coverProvider != null) controller.coverBytesProvider = coverProvider
    }

    private fun ids() = listOf("song-0", "song-1", "song-2")

    private fun queueItem(id: String) = QueueItem(
        songId = id,
        title = id,
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        filePath = "/music/$id.mp3",
        indexInQueue = 0,
    )

    private fun song(item: QueueItem) = Song(
        id = item.songId,
        title = item.title.orEmpty(),
        artists = item.artist.orEmpty(),
        album = item.album.orEmpty(),
        durationMs = item.durationMs ?: 0L,
        filePath = item.filePath,
        status = SongStatus.DOWNLOADED,
    )

    /** Polls until the queue has been applied to the (mocked) player. */
    private fun awaitTimelineApplied(timeoutMs: Long = 5_000) =
        awaitCondition("timeline applied", timeoutMs) { timelineApplied }

    /** Polls until [mediaId] carries artworkData on the timeline. */
    private fun awaitArtwork(mediaId: String, timeoutMs: Long = 5_000) = awaitCondition(
        "artwork on $mediaId",
        timeoutMs,
    ) { mediaItems.firstOrNull { it.mediaId == mediaId }?.mediaMetadata?.artworkData != null }

    private fun awaitCondition(what: String, timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    /** Asserts the current item carries art and every other queued item carries none. */
    private fun assertOnlyCurrentItemHasArtwork() {
        val currentId = mediaItems.getOrNull(currentIndex)?.mediaId
        assertNotNull("expected a current item on the timeline", currentId)
        var currentSeen = false
        for (item in mediaItems) {
            if (item.mediaId == currentId) {
                currentSeen = true
                assertNotNull("current item $currentId must carry artworkData", item.mediaMetadata.artworkData)
            } else {
                assertNull(
                    "non-current item ${item.mediaId} must NOT carry artworkData",
                    item.mediaMetadata.artworkData,
                )
            }
        }
        assert(currentSeen) { "current item $currentId not found on the timeline" }
    }

    @Test
    fun playPlaylistOnlyCurrentItemCarriesArtwork() {
        buildController(ArtworkExtractor { path -> artFor(path) })
        controller.playPlaylist(ids().map(::queueItem).map(::song), startSongId = ids().first(), playlistId = "p1")

        awaitTimelineApplied()

        assertEquals(3, mediaItems.size)
        assertOnlyCurrentItemHasArtwork()
        assertArrayEquals(artFor("/music/song-0.mp3"), mediaItems[0].mediaMetadata.artworkData)
    }

    @Test
    fun restoreOnlyCurrentItemCarriesArtwork() {
        buildController(
            extractor = ArtworkExtractor { path -> artFor(path) },
            restore = savedState(currentSongId = "song-0"),
        )

        awaitTimelineApplied()

        assertEquals(3, mediaItems.size)
        assertOnlyCurrentItemHasArtwork()
    }

    @Test
    fun notificationPathYieldsArtworkForRestoredCurrentItem() {
        buildController(
            extractor = ArtworkExtractor { path -> artFor(path) },
            restore = savedState(currentSongId = "song-1"),
        )

        awaitTimelineApplied()

        // The stock notification/lockscreen reads artworkData off the current timeline item.
        val current = exoPlayer.currentMediaItem
        assertNotNull("notification must find art for the current item", current?.mediaMetadata?.artworkData)
        assertArrayEquals(artFor("/music/song-1.mp3"), current?.mediaMetadata?.artworkData)
    }

    @Test
    fun transitionStampsNewCurrentAndClearsPrevious() {
        buildController(ArtworkExtractor { path -> artFor(path) })
        controller.playPlaylist(ids().map(::queueItem).map(::song), startSongId = ids().first(), playlistId = "p1")
        awaitTimelineApplied()

        // Simulate a skip to song-1.
        currentIndex = 1
        controller.onMediaItemTransition(
            mediaItems[1],
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
        )

        awaitArtwork("song-1")

        assertOnlyCurrentItemHasArtwork()
        assertNull("previous item must have been stripped", mediaItems[0].mediaMetadata.artworkData)
        assertArrayEquals(artFor("/music/song-1.mp3"), mediaItems[1].mediaMetadata.artworkData)
    }

    @Test
    fun transitionWithoutLocalCoverLeavesCurrentArtNull() {
        buildController(ArtworkExtractor { null })
        controller.playPlaylist(ids().map(::queueItem).map(::song), startSongId = ids().first(), playlistId = "p1")
        awaitTimelineApplied()

        currentIndex = 1
        controller.onMediaItemTransition(mediaItems[1], Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        // No cover anywhere: the previous item is still stripped, and nothing is invented.
        assertNull(mediaItems[0].mediaMetadata.artworkData)
        assertNull(mediaItems[1].mediaMetadata.artworkData)
    }

    @Test
    fun addToQueueNextDoesNotStampArtworkOnQueuedItem() {
        buildController(
            extractor = ArtworkExtractor { null },
            coverProvider = { songId, _ -> artFor(songId) },
        )
        controller.playPlaylist(ids().map(::queueItem).map(::song), startSongId = ids().first(), playlistId = "p1")
        awaitTimelineApplied()

        // A user-queued item arrives with art attached (old view-model behaviour); the
        // controller must strip it before inserting because it is not the current item.
        val queued = queueItem("song-9")
        val incoming = MediaItemMapper.toMediaItem(queued, artFor("song-9"))
        controller.addToQueueNext("song-9", incoming, coverUrl = null, coverArtLocalPath = null)

        val inserted = mediaItems.firstOrNull { it.mediaId == "song-9" }
        assertNotNull("queued item must have been inserted", inserted)
        assertNull("queued item must not carry artworkData", inserted?.mediaMetadata?.artworkData)
        // The current item keeps its own art.
        assertNotNull(mediaItems[0].mediaMetadata.artworkData)
    }

    @Test
    fun cachedCoverBytesEmbeddedOnlyForCurrentItem() {
        buildController(
            extractor = ArtworkExtractor { null },
            coverProvider = { songId, _ -> artFor(songId) },
        )
        controller.playPlaylist(ids().map(::queueItem).map(::song), startSongId = ids().first(), playlistId = "p1")

        awaitTimelineApplied()

        assertOnlyCurrentItemHasArtwork()
        assertArrayEquals(artFor("song-0"), mediaItems[0].mediaMetadata.artworkData)
    }

    private fun savedState(currentSongId: String) = PlayerStatePersistence.SavedState(
        currentSongId = currentSongId,
        playlistId = "p1",
        queueSongIds = ids(),
        shuffleEnabled = false,
        repeatMode = RepeatMode.OFF,
        volume = 1f,
        positionMs = 12_345L,
        wasPlaying = false,
    )
}

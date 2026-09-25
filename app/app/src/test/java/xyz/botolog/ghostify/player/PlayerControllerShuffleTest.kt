package xyz.botolog.ghostify.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.player.core.PlayerQueueBuilder
import xyz.botolog.ghostify.player.core.QueueItem

class PlayerControllerShuffleTest {

    private lateinit var player: ExoPlayer
    private lateinit var controller: PlayerController
    private lateinit var scope: CoroutineScope
    private lateinit var canonicalIds: List<String>
    private lateinit var mediaItems: MutableList<MediaItem>
    private val replacementStarts = mutableListOf<Int>()
    private var currentIndex = 0
    private var position = 0L
    private var playWhenReady = true
    private var exoShuffleEnabled = false

    @Before
    fun setUp() {
        mediaItems = mutableListOf()
        player = mockk(relaxed = true)
        every { player.currentMediaItem } answers { mediaItems.getOrNull(currentIndex) }
        every { player.currentMediaItemIndex } answers { currentIndex }
        every { player.currentPosition } answers { position }
        every { player.playWhenReady } answers { playWhenReady }
        every { player.isPlaying } answers { playWhenReady }
        every { player.isLoading } returns false
        every { player.mediaItemCount } answers { mediaItems.size }
        every { player.getMediaItemAt(any()) } answers { mediaItems[firstArg<Int>()] }
        every { player.playbackState } returns Player.STATE_READY
        every { player.duration } returns 100_000L
        every { player.mediaMetadata } answers {
            mediaItems.getOrNull(currentIndex)?.mediaMetadata ?: mockk(relaxed = true)
        }
        every { player.repeatMode } returns Player.REPEAT_MODE_OFF
        every { player.volume } returns 1f
        every { player.shuffleModeEnabled } answers { exoShuffleEnabled }
        every { player.shuffleModeEnabled = any() } answers { exoShuffleEnabled = arg(0) }
        every { player.play() } answers { playWhenReady = true }
        every { player.pause() } answers { playWhenReady = false }
        every { player.seekTo(any<Int>(), any<Long>()) } answers {
            currentIndex = firstArg()
            position = secondArg()
        }
        every { player.replaceMediaItems(any(), any(), any()) } answers {
            val from = firstArg<Int>()
            val to = secondArg<Int>()
            val replacement = thirdArg<List<MediaItem>>()
            val oldCurrentId = mediaItems.getOrNull(currentIndex)?.mediaId
            val currentWasReplaced = currentIndex in from until to
            replacementStarts += from
            mediaItems = (mediaItems.take(from) + replacement + mediaItems.drop(to)).toMutableList()
            currentIndex = mediaItems.indexOfFirst { it.mediaId == oldCurrentId }
                .takeIf { it >= 0 }
                ?: currentIndex.coerceAtMost(mediaItems.lastIndex)
            if (currentWasReplaced) position = 0L
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val constructor = PlayerController::class.java.declaredConstructors
            .first { it.parameterCount == 8 }
            .apply { isAccessible = true }
        controller = constructor.newInstance(
            mockk<Context>(relaxed = true),
            player,
            mockk<PlayerQueueBuilder>(relaxed = true),
            ArtworkExtractor { null },
            null,
            scope,
            null,
            flowOf(true),
        ) as PlayerController
    }

    @After
    fun tearDown() {
        controller.release()
    }

    @Test
    fun disablingShuffleKeepsActiveItemAnchoredAndPlaybackPosition() {
        setCanonicalQueue(size = 12, activeIndex = 5)
        val savedPosition = 23_456L
        position = savedPosition

        var attempts = 0
        do {
            controller.setShuffleEnabled(false)
            controller.setShuffleEnabled(true)
            attempts++
        } while (controller.queueManager.queue.map { it.songId } != canonicalIds && attempts < 100)
        assertNotEquals(canonicalIds, controller.queueManager.queue.map { it.songId })

        controller.setShuffleEnabled(false)

        assertEquals(canonicalIds, controller.queueManager.queue.map { it.songId })
        assertEquals("song-5", player.currentMediaItem?.mediaId)
        assertEquals(5, player.currentMediaItemIndex)
        assertEquals(savedPosition, player.currentPosition)
        assertTrue(player.playWhenReady)
        assertFalse(player.shuffleModeEnabled)
        assertTrue(replacementStarts.isNotEmpty())
        assertTrue(replacementStarts.all { it == 6 })
        verify(exactly = 0) { player.seekTo(any<Int>(), any<Long>()) }
        verify(exactly = 0) { player.prepare() }
        verify(exactly = 0) { player.clearMediaItems() }
    }

    @Test
    fun disablingShuffleAfterActiveItemMovedRestoresCanonicalPosition() {
        setCanonicalQueue(size = 8, activeIndex = 0)
        controller.queueManager.shuffle()

        var shuffledIndex = -1
        repeat(100) {
            if (shuffledIndex < 0) {
                shuffledIndex = controller.queueManager.queue.indexOfFirst { item ->
                    canonicalIds.indexOf(item.songId) != controller.queueManager.queue.indexOf(item)
                }
            }
            if (shuffledIndex < 0) controller.queueManager.reshuffle()
        }
        assertTrue(shuffledIndex >= 0)

        val activeMediaId = controller.queueManager.queue[shuffledIndex].songId
        val canonicalIndex = canonicalIds.indexOf(activeMediaId)
        mediaItems = controller.queueManager.queue.map { mediaItem(it.songId) }.toMutableList()
        currentIndex = shuffledIndex
        val savedPosition = 41_234L
        position = savedPosition
        playWhenReady = true
        replacementStarts.clear()

        controller.setShuffleEnabled(false)

        assertEquals(canonicalIds, controller.queueManager.queue.map { it.songId })
        assertEquals(activeMediaId, player.currentMediaItem?.mediaId)
        assertEquals(canonicalIndex, player.currentMediaItemIndex)
        assertEquals(savedPosition, player.currentPosition)
        assertTrue(player.playWhenReady)
        assertFalse(player.shuffleModeEnabled)
        assertEquals(listOf(0), replacementStarts)
        verify(exactly = 1) { player.seekTo(canonicalIndex, savedPosition) }
        verify(exactly = 0) { player.prepare() }
        verify(exactly = 0) { player.clearMediaItems() }
    }

    private fun setCanonicalQueue(size: Int, activeIndex: Int) {
        canonicalIds = (0 until size).map { "song-$it" }
        controller.queueManager.setQueue(canonicalIds.map(::queueItem))
        mediaItems = canonicalIds.map(::mediaItem).toMutableList()
        currentIndex = activeIndex
        position = 0L
        playWhenReady = true
        exoShuffleEnabled = false
        replacementStarts.clear()
    }

    private fun mediaItem(id: String): MediaItem =
        MediaItem.Builder().setMediaId(id).build()

    private fun queueItem(id: String): QueueItem = QueueItem(
        songId = id,
        title = null,
        artist = null,
        album = null,
        durationMs = 100_000L,
        filePath = "/$id.mp3",
        indexInQueue = 0,
    )
}

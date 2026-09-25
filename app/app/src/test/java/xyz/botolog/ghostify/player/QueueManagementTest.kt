package xyz.botolog.ghostify.player

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.player.core.QueueItem

/**
 * Tests for [PlayerQueueManager] — the single source of truth for queue ordering
 * and user-queued song tracking.
 */
class QueueManagementTest {

    private lateinit var manager: PlayerQueueManager

    @Before
    fun setup() {
        manager = PlayerQueueManager()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun mediaItem(id: String): MediaItem =
        MediaItem.Builder().setMediaId(id).build()

    private fun queueItem(id: String, queuedByUser: Boolean = false) = QueueItem(
        songId = id, title = null, artist = null, album = null,
        durationMs = 1000L, filePath = "/$id.mp3", indexInQueue = 0,
        queuedByUser = queuedByUser,
    )

    private fun setupQueue(ids: List<String>) {
        manager.setQueue(ids.map { queueItem(it) })
    }

    private fun queueIds(): List<String> = manager.queue.map { it.songId }

    // ══════════════════════════════════════════════════════════════════════
    //  Initial state
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun initialState_isEmpty() {
        assertEquals(0, manager.size)
    }

    @Test
    fun setQueue_populatesQueue() {
        setupQueue(listOf("a", "b", "c"))
        assertEquals(3, manager.size)
        assertEquals(listOf("a", "b", "c"), queueIds())
    }

    @Test
    fun setQueue_clearsPreviousQueue() {
        setupQueue(listOf("a", "b"))
        setupQueue(listOf("x", "y", "z"))
        assertEquals(listOf("x", "y", "z"), queueIds())
    }

    @Test
    fun setQueue_marksAllAsNotUserQueued() {
        manager.setQueue(listOf(queueItem("a", queuedByUser = true), queueItem("b", queuedByUser = true)))
        assertFalse(manager.queue[0].queuedByUser)
        assertFalse(manager.queue[1].queuedByUser)
    }

    @Test
    fun clear_emptiesQueue() {
        setupQueue(listOf("a", "b"))
        manager.clear()
        assertEquals(0, manager.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  indexOf
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun indexOf_returnsMinusOne_forEmptyQueue() {
        assertEquals(-1, manager.indexOf("a"))
    }

    @Test
    fun indexOf_returnsMinusOne_whenNotInQueue() {
        setupQueue(listOf("a", "b"))
        assertEquals(-1, manager.indexOf("z"))
    }

    @Test
    fun indexOf_returnsCorrectIndex() {
        setupQueue(listOf("a", "b", "c"))
        assertEquals(0, manager.indexOf("a"))
        assertEquals(1, manager.indexOf("b"))
        assertEquals(2, manager.indexOf("c"))
    }

    @Test
    fun indexOf_findsItemAfterAddToQueueNext() {
        setupQueue(listOf("a", "b"))
        manager.addToQueueNext("c", mediaItem("c"), 0)
        assertEquals(1, manager.indexOf("c"))
    }

    @Test
    fun indexOf_findsItemAfterReorder() {
        setupQueue(listOf("a", "b", "c"))
        manager.reorder(0, 2)
        assertEquals(2, manager.indexOf("a"))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  queuedByUser flag
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun queuedByUser_false_initially() {
        setupQueue(listOf("a", "b"))
        assertFalse(manager.queue[0].queuedByUser)
        assertFalse(manager.queue[1].queuedByUser)
    }

    @Test
    fun queuedByUser_true_afterAddToQueueNext_existingSong() {
        setupQueue(listOf("a", "b"))
        manager.addToQueueNext("b", mediaItem("b"), 0)
        assertTrue(manager.queue[manager.indexOf("b")].queuedByUser)
    }

    @Test
    fun queuedByUser_true_forNewSong() {
        setupQueue(listOf("a", "b"))
        manager.addToQueueNext("x", mediaItem("x"), 0)
        assertTrue(manager.queue[manager.indexOf("x")].queuedByUser)
    }

    @Test
    fun queuedByUser_staysFalse_forUnmovedSongs() {
        setupQueue(listOf("a", "b", "c"))
        manager.addToQueueNext("x", mediaItem("x"), 0)
        assertFalse(manager.queue[manager.indexOf("a")].queuedByUser)
        assertFalse(manager.queue[manager.indexOf("c")].queuedByUser)
    }

    @Test
    fun queuedByUser_preservedAfterReorder() {
        setupQueue(listOf("a", "b"))
        manager.addToQueueNext("b", mediaItem("b"), 0)
        val bIndex = manager.indexOf("b")
        assertTrue(manager.queue[bIndex].queuedByUser)
        manager.reorder(0, 1)
        val bNewIndex = manager.indexOf("b")
        assertTrue(manager.queue[bNewIndex].queuedByUser)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  addToQueueNext — new songs
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun addToQueueNext_returnsNull_whenQueueIsEmpty() {
        assertNull(manager.addToQueueNext("x", mediaItem("x"), 0))
    }

    @Test
    fun addToQueueNext_returnsNull_whenSongIsCurrentlyPlaying() {
        setupQueue(listOf("a", "b"))
        assertNull(manager.addToQueueNext("a", mediaItem("a"), 0))
        assertEquals(listOf("a", "b"), queueIds())
    }

    @Test
    fun addToQueueNext_addsNewSong_rightAfterCurrent() {
        setupQueue(listOf("a", "b"))
        val m = manager.addToQueueNext("x", mediaItem("x"), 0)
        assertTrue(m is QueueMutation.Add)
        assertEquals(listOf("a", "x", "b"), queueIds())
    }

    @Test
    fun addToQueueNext_addsNewSong_afterLastUserQueued() {
        setupQueue(listOf("a", "b", "c"))
        manager.addToQueueNext("b", mediaItem("b"), 0)
        manager.addToQueueNext("x", mediaItem("x"), 0)
        assertEquals(listOf("a", "b", "x", "c"), queueIds())
    }

    @Test
    fun addToQueueNext_addsNewSong_atEnd_whenAllAreUserQueued() {
        setupQueue(listOf("a", "b"))
        manager.addToQueueNext("b", mediaItem("b"), 0)
        manager.addToQueueNext("x", mediaItem("x"), 0)
        assertEquals(listOf("a", "b", "x"), queueIds())
    }

    @Test
    fun addToQueueNext_addsNewSong_atEnd_whenCurrentIsLast() {
        setupQueue(listOf("a", "b"))
        manager.addToQueueNext("x", mediaItem("x"), 1)
        assertEquals(listOf("a", "b", "x"), queueIds())
    }

    @Test
    fun addToQueueNext_multipleAdds_preserveInsertionOrder() {
        setupQueue(listOf("a", "b"))
        manager.addToQueueNext("x", mediaItem("x"), 0)
        manager.addToQueueNext("y", mediaItem("y"), 0)
        manager.addToQueueNext("z", mediaItem("z"), 0)
        assertEquals(listOf("a", "x", "y", "z", "b"), queueIds())
    }

    @Test
    fun addToQueueNext_addsNewSong_afterCurrentWhenCurrentIsNotZero() {
        setupQueue(listOf("a", "b", "c"))
        manager.addToQueueNext("x", mediaItem("x"), 1)
        assertEquals(listOf("a", "b", "x", "c"), queueIds())
    }

    @Test
    fun addToQueueNext_multipleUserQueued_afterCurrent() {
        setupQueue(listOf("a", "b", "c"))
        manager.addToQueueNext("b", mediaItem("b"), 0)
        manager.addToQueueNext("d", mediaItem("d"), 0)
        manager.addToQueueNext("e", mediaItem("e"), 0)
        assertEquals(listOf("a", "b", "d", "e", "c"), queueIds())
    }

    // ══════════════════════════════════════════════════════════════════════
    //  addToQueueNext — existing songs (move)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun addToQueueNext_movesExistingSong_afterLastUserQueued() {
        setupQueue(listOf("a", "b", "c", "d"))
        manager.addToQueueNext("b", mediaItem("b"), 0)
        manager.addToQueueNext("d", mediaItem("d"), 0)
        assertEquals(listOf("a", "b", "d", "c"), queueIds())
    }

    @Test
    fun addToQueueNext_movesExistingSong_rightAfterCurrent_whenNoUserQueued() {
        setupQueue(listOf("a", "b", "c"))
        val m = manager.addToQueueNext("c", mediaItem("c"), 0)
        assertTrue(m is QueueMutation.Move)
        assertEquals(listOf("a", "c", "b"), queueIds())
    }

    @Test
    fun addToQueueNext_songAlreadyAtCorrectPosition_noOp() {
        setupQueue(listOf("a", "b", "c"))
        manager.addToQueueNext("b", mediaItem("b"), 0)
        val m = manager.addToQueueNext("b", mediaItem("b"), 0)
        assertNull(m)
        assertEquals(listOf("a", "b", "c"), queueIds())
    }

    @Test
    fun addToQueueNext_movesExistingSongBeforeCurrent_toAfterCurrent() {
        setupQueue(listOf("a", "b", "c"))
        manager.addToQueueNext("a", mediaItem("a"), 2)
        assertEquals(listOf("b", "c", "a"), queueIds())
    }

    @Test
    fun addToQueueNext_movesExistingSongFromEnd_toAfterUserQueued() {
        setupQueue(listOf("a", "b", "c", "d", "e"))
        manager.addToQueueNext("b", mediaItem("b"), 0)
        manager.addToQueueNext("e", mediaItem("e"), 0)
        assertEquals(listOf("a", "b", "e", "c", "d"), queueIds())
    }

    // ══════════════════════════════════════════════════════════════════════
    //  addToQueueNext — mutation return value
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun addToQueueNext_returnsAddMutation_forNewSong() {
        setupQueue(listOf("a", "b"))
        val m = manager.addToQueueNext("x", mediaItem("x"), 0)
        assertTrue(m is QueueMutation.Add)
        assertEquals(1, (m as QueueMutation.Add).index)
    }

    @Test
    fun addToQueueNext_returnsMoveMutation_forMovedSong() {
        setupQueue(listOf("a", "b", "c"))
        val m = manager.addToQueueNext("c", mediaItem("c"), 0)
        assertTrue(m is QueueMutation.Move)
        assertEquals(2, (m as QueueMutation.Move).from)
        assertEquals(1, m.to)
    }

    @Test
    fun addToQueueNext_returnsNullForNoAction() {
        setupQueue(listOf("a", "b"))
        assertNull(manager.addToQueueNext("a", mediaItem("a"), 0))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  reorder
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun reorder_doesNothing_whenFromEqualsTo() {
        setupQueue(listOf("a", "b", "c"))
        assertFalse(manager.reorder(1, 1))
        assertEquals(listOf("a", "b", "c"), queueIds())
    }

    @Test
    fun reorder_doesNothing_whenFromIndexOutOfBounds_negative() {
        setupQueue(listOf("a", "b"))
        assertFalse(manager.reorder(-1, 1))
    }

    @Test
    fun reorder_doesNothing_whenFromIndexOutOfBounds_tooLarge() {
        setupQueue(listOf("a", "b"))
        assertFalse(manager.reorder(5, 0))
    }

    @Test
    fun reorder_doesNothing_whenToIndexOutOfBounds() {
        setupQueue(listOf("a", "b"))
        assertFalse(manager.reorder(0, 5))
    }

    @Test
    fun reorder_movesItemForward() {
        setupQueue(listOf("a", "b", "c"))
        assertTrue(manager.reorder(0, 2))
        assertEquals(listOf("b", "c", "a"), queueIds())
    }

    @Test
    fun reorder_movesItemBackward() {
        setupQueue(listOf("a", "b", "c"))
        assertTrue(manager.reorder(2, 0))
        assertEquals(listOf("c", "a", "b"), queueIds())
    }

    @Test
    fun reorder_swapsAdjacentItems() {
        setupQueue(listOf("a", "b"))
        assertTrue(manager.reorder(0, 1))
        assertEquals(listOf("b", "a"), queueIds())
    }

    @Test
    fun reorder_movesFirstToLast() {
        setupQueue(listOf("a", "b", "c", "d"))
        assertTrue(manager.reorder(0, 3))
        assertEquals(listOf("b", "c", "d", "a"), queueIds())
    }

    @Test
    fun reorder_movesLastToFirst() {
        setupQueue(listOf("a", "b", "c", "d"))
        assertTrue(manager.reorder(3, 0))
        assertEquals(listOf("d", "a", "b", "c"), queueIds())
    }

    @Test
    fun reorder_multipleMoves_maintainCorrectOrder() {
        setupQueue(listOf("a", "b", "c", "d"))
        manager.reorder(0, 3)
        assertEquals(listOf("b", "c", "d", "a"), queueIds())
        manager.reorder(0, 2)
        assertEquals(listOf("c", "d", "b", "a"), queueIds())
    }

    // ══════════════════════════════════════════════════════════════════════
    //  appendItems
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun appendItems_addsToEnd() {
        setupQueue(listOf("a", "b"))
        manager.appendItems(listOf(queueItem("c")))
        assertEquals(listOf("a", "b", "c"), queueIds())
    }

    @Test
    fun appendItems_multipleBatches() {
        setupQueue(listOf("a"))
        manager.appendItems(listOf(queueItem("b")))
        manager.appendItems(listOf(queueItem("c")))
        assertEquals(listOf("a", "b", "c"), queueIds())
    }

    // ══════════════════════════════════════════════════════════════════════
    //  getOrNull
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun shuffleAfter_preservesPrefixAndActiveAndKeepsOriginalOrder() {
        setupQueue(listOf("a", "b", "c", "d", "e"))
        manager.addToQueueNext("b", mediaItem("b"), 0)
        val original = manager.queue

        manager.shuffleAfter(2)

        assertTrue(manager.isShuffled)
        assertEquals(listOf("a", "b", "c"), queueIds().take(3))
        assertEquals(setOf("d", "e"), queueIds().drop(3).toSet())
        assertEquals(original.toSet(), manager.queue.toSet())
        assertTrue(manager.queue.first { it.songId == "b" }.queuedByUser)

        manager.unshuffle()

        assertFalse(manager.isShuffled)
        assertEquals(original, manager.queue)
    }

    @Test
    fun shuffleAfter_keepsFirstItemPinned() {
        setupQueue(listOf("a", "b", "c", "d", "e"))
        val original = manager.queue

        manager.shuffleAfter(0)

        assertTrue(manager.isShuffled)
        assertEquals("a", queueIds().first())
        assertEquals(setOf("b", "c", "d", "e"), queueIds().drop(1).toSet())
        assertEquals(original.toSet(), manager.queue.toSet())
    }

    @Test
    fun shuffleAfter_handlesSingleAndEmptyQueues() {
        manager.shuffleAfter(0)
        assertEquals(0, manager.size)
        assertFalse(manager.isShuffled)

        setupQueue(listOf("a"))
        manager.shuffleAfter(0)

        assertEquals(listOf("a"), queueIds())
        assertTrue(manager.isShuffled)

        manager.unshuffle()

        assertEquals(listOf("a"), queueIds())
        assertFalse(manager.isShuffled)
    }

    @Test
    fun shuffleAfter_invalidAnchorIsNoOp() {
        setupQueue(listOf("a", "b"))
        val original = manager.queue

        manager.shuffleAfter(5)

        assertFalse(manager.isShuffled)
        assertEquals(original, manager.queue)
    }

    @Test
    fun reshuffle_preservesItemsMetadataFlagsAndOriginalOrder() {
        setupQueue(listOf("a", "b", "c", "d", "e", "f"))
        manager.addToQueueNext("c", mediaItem("c"), 0)
        manager.addToQueueNext("x", mediaItem("x"), 0)
        val original = manager.queue
        manager.shuffle()

        repeat(5) { manager.reshuffle() }

        assertTrue(manager.isShuffled)
        assertEquals(original.toSet(), manager.queue.toSet())
        assertEquals(
            original.associateBy { it.songId },
            manager.queue.associateBy { it.songId },
        )
        assertTrue(manager.queue.first { it.songId == "x" }.queuedByUser)
        assertTrue(manager.queue.first { it.songId == "c" }.queuedByUser)

        manager.unshuffle()

        assertFalse(manager.isShuffled)
        assertEquals(original, manager.queue)
    }

    @Test
    fun reshuffle_keepsLazilyAppendedItemsInOriginalOrder() {
        setupQueue(listOf("a", "b"))
        manager.shuffle()
        manager.appendItems(listOf(queueItem("c"), queueItem("d")))
        manager.reshuffle()
        manager.unshuffle()

        assertEquals(listOf("a", "b", "c", "d"), queueIds())
    }

    @Test
    fun reshuffle_handlesEmptyAndSingleItemQueues() {
        manager.reshuffle()
        assertEquals(0, manager.size)
        assertFalse(manager.isShuffled)

        setupQueue(listOf("a"))
        manager.reshuffle()
        assertEquals(listOf("a"), queueIds())
        assertTrue(manager.isShuffled)

        manager.unshuffle()
        assertEquals(listOf("a"), queueIds())
        assertFalse(manager.isShuffled)
    }

    @Test
    fun getOrNull_returnsItem_atValidIndex() {
        setupQueue(listOf("a", "b"))
        assertEquals("a", manager.getOrNull(0)?.songId)
        assertEquals("b", manager.getOrNull(1)?.songId)
    }

    @Test
    fun getOrNull_returnsNull_atOutOfBounds() {
        setupQueue(listOf("a"))
        assertEquals(null, manager.getOrNull(5))
        assertEquals(null, manager.getOrNull(-1))
    }
}

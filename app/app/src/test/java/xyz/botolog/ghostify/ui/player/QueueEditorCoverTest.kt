package xyz.botolog.ghostify.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEditorCoverTest {

    @Test
    fun coverShownWhenEnabledAndArtworkPresent() {
        assertTrue(shouldShowQueueItemCover(showCover = true, coverArtLocalPath = null, coverUrl = "http://art/1.jpg"))
    }

    @Test
    fun coverShownWhenLocalPresentAndUrlMissing_offline() {
        assertTrue(shouldShowQueueItemCover(showCover = true, coverArtLocalPath = "/data/covers/1.jpg", coverUrl = null))
    }

    @Test
    fun coverShownWhenBothPresent_localFirst() {
        assertTrue(
            shouldShowQueueItemCover(
                showCover = true,
                coverArtLocalPath = "/data/covers/1.jpg",
                coverUrl = "http://art/1.jpg",
            ),
        )
    }

    @Test
    fun coverShownWhenLocalPresentAndUrlBlank() {
        assertTrue(shouldShowQueueItemCover(showCover = true, coverArtLocalPath = "/data/covers/1.jpg", coverUrl = "  "))
    }

    @Test
    fun coverHiddenWhenSettingDisabled() {
        assertFalse(shouldShowQueueItemCover(showCover = false, coverArtLocalPath = null, coverUrl = "http://art/1.jpg"))
    }

    @Test
    fun coverHiddenWhenSettingDisabledAndLocalPresent() {
        assertFalse(
            shouldShowQueueItemCover(
                showCover = false,
                coverArtLocalPath = "/data/covers/1.jpg",
                coverUrl = "http://art/1.jpg",
            ),
        )
    }

    @Test
    fun coverHiddenWhenArtworkMissing() {
        assertFalse(shouldShowQueueItemCover(showCover = true, coverArtLocalPath = null, coverUrl = null))
    }

    @Test
    fun coverHiddenWhenArtworkBlank() {
        assertFalse(shouldShowQueueItemCover(showCover = true, coverArtLocalPath = "  ", coverUrl = "  "))
    }

    @Test
    fun coverHiddenWhenSettingDisabledAndArtworkMissing() {
        assertFalse(shouldShowQueueItemCover(showCover = false, coverArtLocalPath = null, coverUrl = null))
    }
}

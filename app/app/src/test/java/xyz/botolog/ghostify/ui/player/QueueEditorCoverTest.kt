package xyz.botolog.ghostify.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEditorCoverTest {

    @Test
    fun coverShownWhenEnabledAndArtworkPresent() {
        assertTrue(shouldShowQueueItemCover(showCover = true, coverUrl = "http://art/1.jpg"))
    }

    @Test
    fun coverHiddenWhenSettingDisabled() {
        assertFalse(shouldShowQueueItemCover(showCover = false, coverUrl = "http://art/1.jpg"))
    }

    @Test
    fun coverHiddenWhenArtworkMissing() {
        assertFalse(shouldShowQueueItemCover(showCover = true, coverUrl = null))
    }

    @Test
    fun coverHiddenWhenArtworkBlank() {
        assertFalse(shouldShowQueueItemCover(showCover = true, coverUrl = "  "))
    }

    @Test
    fun coverHiddenWhenSettingDisabledAndArtworkMissing() {
        assertFalse(shouldShowQueueItemCover(showCover = false, coverUrl = null))
    }
}

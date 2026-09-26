package xyz.botolog.ghostify.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CoverImageModelTest {

    private val localExists = { _: File -> true }
    private val localMissing = { _: File -> false }

    @Test
    fun localFileIsPreferredWhenItExists() {
        val model = resolveCoverImageModel("/covers/a.jpg", "https://img/a.jpg", localExists)

        assertEquals(File("/covers/a.jpg"), model)
    }

    @Test
    fun remoteUrlIsUsedWhenLocalFileIsGone() {
        val model = resolveCoverImageModel("/covers/a.jpg", "https://img/a.jpg", localMissing)

        assertEquals("https://img/a.jpg", model)
    }

    @Test
    fun remoteUrlIsUsedWhenThereIsNoLocalFile() {
        val model = resolveCoverImageModel(null, "https://img/a.jpg", localMissing)

        assertEquals("https://img/a.jpg", model)
    }

    @Test
    fun blankLocalPathIsIgnored() {
        val model = resolveCoverImageModel("   ", "https://img/a.jpg", localExists)

        assertEquals("https://img/a.jpg", model)
    }

    @Test
    fun nullIsReturnedWhenNeitherSourceIsUsable() {
        assertNull(resolveCoverImageModel(null, null, localMissing))
        assertNull(resolveCoverImageModel("/covers/a.jpg", null, localMissing))
        assertNull(resolveCoverImageModel("", "  ", localMissing))
    }

    @Test
    fun remoteUrlIsTrimmed() {
        val model = resolveCoverImageModel(null, "  https://img/a.jpg  ", localMissing)

        assertEquals("https://img/a.jpg", model)
    }
}

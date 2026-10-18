package xyz.botolog.ghostify.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the bounded cover path shared by the player / lyrics / playlists widgets.
 *
 * [decodeCover] itself touches `BitmapFactory`, so on the JVM only its total, non-throwing
 * contract can be checked (the stub decoder yields nothing => `null`). The *bounding* logic
 * it relies on is pure ([coverSampleSize]) and is pinned here so the derived bitmap can
 * never blow the system's RemoteViews bitmap cap.
 */
class WidgetCoversTest {

    // ── Bounding: a real full-resolution cover is sampled far down ────────

    @Test
    fun samplesARealFullResolutionCover() {
        // 1368x1368 is the size of the app's real embedded artwork.
        assertEquals(4, coverSampleSize(1368, 1368, WIDGET_COVER_MAX_PX))
        assertEquals(4, coverSampleSize(1280, 1280, WIDGET_COVER_MAX_PX))
        assertEquals(8, coverSampleSize(4000, 3000, WIDGET_COVER_MAX_PX))
        assertEquals(16, coverSampleSize(8192, 8192, WIDGET_COVER_MAX_PX))
        // A non-square image is sampled on its longer side.
        assertEquals(8, coverSampleSize(4000, 200, WIDGET_COVER_MAX_PX))
    }

    @Test
    fun sampledIntermediateIsAlwaysBounded() {
        // coverSampleSize guarantees the *decoded* long edge is < 2 * target, so the
        // full-resolution pixels are never allocated; scaleToFit then lands it at/below the
        // target. Pin the invariant for a spread of real cover sizes.
        val sizes = listOf(320, 500, 640, 900, 1024, 1368, 2048, 4000, 6000)
        for (w in sizes) {
            for (h in sizes) {
                val sample = coverSampleSize(w, h, WIDGET_COVER_MAX_PX)
                assertTrue("sample >= 1 for ${w}x$h", sample >= 1)
                assertTrue(
                    "sampled long edge < 2x target for ${w}x$h (sample=$sample)",
                    maxOf(w, h) / sample < 2 * WIDGET_COVER_MAX_PX,
                )
            }
        }
    }

    @Test
    fun targetStaysUnderTheSystemBitmapCap() {
        // The system rejects a RemoteViews whose bitmaps exceed 6 * screenW * screenH; the
        // smallest common phone display gives a cap around 2.43 MB (450x900). A bounded
        // ARGB cover must sit well under that with room for the rest of the widget.
        assertTrue(WIDGET_COVER_MAX_PX in 256..320)
        val coverBytes = WIDGET_COVER_MAX_PX.toLong() * WIDGET_COVER_MAX_PX * 4
        assertTrue("bounded cover is $coverBytes bytes", coverBytes < 1_000_000)
    }

    // ── decodeCover: total, never throws, null in -> null out ────────────

    @Test
    fun nullAndEmptyCoversYieldNull() {
        assertNull(decodeCover(null))
        assertNull(decodeCover(ByteArray(0)))
    }

    @Test
    fun garbageCoverYieldsNullInsteadOfThrowing() {
        // Truncated / non-image bytes, and bytes that decode to nothing, must fall back to
        // the placeholder rather than propagating an exception into the Glance composition.
        assertNull(decodeCover(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(decodeCover("not an image at all".encodeToByteArray()))
        assertNull(decodeCover(ByteArray(4096) { (it * 31).toByte() }))
    }

    @Test
    fun degenerateTargetStillYieldsNullForUndecodableBytes() {
        assertNull(decodeCover(byteArrayOf(9, 9, 9), targetPx = 0))
        assertNull(decodeCover(byteArrayOf(9, 9, 9), targetPx = -100))
    }
}

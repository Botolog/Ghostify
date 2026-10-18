package xyz.botolog.ghostify.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * The sleep-timer **ring**, drawn as a Bitmap for Glance.
 *
 * Glance 1.1.1 has no arbitrary `Canvas`/`drawArc` composable: its only circular
 * primitive, `androidx.glance.appwidget.CircularProgressIndicator`, is an
 * *indeterminate* spinner with no progress parameter (signature
 * `CircularProgressIndicator(GlanceModifier, ColorProvider)`), so it cannot show a
 * fraction. Instead the arc is rasterised here onto a transparent Bitmap and handed to
 * `Image` (exactly how decoded cover art already reaches a widget). This is a true
 * determinate ring with rounded caps, at the cost of one small bitmap per visible
 * countdown second — created off the main thread inside the Glance session.
 */
internal data class RingColors(val track: Int, val progress: Int)

/**
 * Ring colours derived from the same Material palettes as [WidgetTheme], resolved
 * against the host's night mode (a Bitmap cannot use Glance's day/night `ColorProvider`).
 */
internal fun ringColors(context: Context): RingColors {
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    return if (night) {
        RingColors(track = 0xFF49454F.toInt(), progress = 0xFFD0BCFF.toInt())
    } else {
        RingColors(track = 0xFFE7E0EC.toInt(), progress = 0xFF6750A4.toInt())
    }
}

/**
 * Rasterises a determinate progress ring: a full track circle under a `fraction`-swept
 * arc that starts at 12 o'clock and runs clockwise, both with rounded caps.
 *
 * @param fraction amount of the ring still to run, `0f..1f` (values outside are clamped).
 * @param sizePx square bitmap side in pixels (clamped to at least 16).
 * @param strokePx ring stroke width in pixels.
 */
internal fun ringBitmap(
    fraction: Float,
    sizePx: Int,
    strokePx: Int,
    trackColor: Int,
    progressColor: Int,
): Bitmap {
    val size = sizePx.coerceAtLeast(16)
    val stroke = strokePx.coerceIn(2, size / 3)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val inset = stroke / 2f + 1f
    val oval = RectF(inset, inset, size - inset, size - inset)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke.toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    paint.color = trackColor
    canvas.drawArc(oval, 0f, 360f, false, paint)
    val sweep = 360f * fraction.coerceIn(0f, 1f)
    if (sweep > 0f) {
        paint.color = progressColor
        canvas.drawArc(oval, -90f, sweep, false, paint)
    }
    return bitmap
}

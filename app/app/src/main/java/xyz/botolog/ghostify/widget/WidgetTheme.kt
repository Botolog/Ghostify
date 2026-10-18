package xyz.botolog.ghostify.widget

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.material3.ColorProviders
import xyz.botolog.ghostify.ui.theme.DarkOnSurface
import xyz.botolog.ghostify.ui.theme.DarkSurface
import xyz.botolog.ghostify.ui.theme.LightOnSurface
import xyz.botolog.ghostify.ui.theme.LightSurface
import xyz.botolog.ghostify.ui.theme.Purple40
import xyz.botolog.ghostify.ui.theme.Purple80

/**
 * Widget colour palette, derived from the app's Material 3 theme so a home-screen
 * widget looks on-brand in both light and dark system themes.
 *
 * [ColorProviders] is the `glance-material3` bridge: it maps a Compose
 * [androidx.compose.material3.ColorScheme] pair (light, dark) onto Glance's
 * `androidx.glance.color.ColorProviders`. Glance picks the right side from the
 * host's night mode, and computes the `widgetBackground` tone for us.
 *
 * Only the roles the widget actually paints are overridden; the rest fall back to
 * Material's baseline purple scheme.
 */
internal object WidgetTheme {

    private val LightScheme = lightColorScheme(
        primary = Purple40,
        onPrimary = Color(0xFFFFFFFF),
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = Color(0xFFE7E0EC),
        onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFF79747E),
    )

    private val DarkScheme = darkColorScheme(
        primary = Purple80,
        onPrimary = Color(0xFF381E72),
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = Color(0xFF49454F),
        onSurfaceVariant = Color(0xFFCAC4D0),
        outline = Color(0xFF938F99),
    )

    /** Light + dark palettes handed to `GlanceTheme(colors = …)`. */
    val colors = ColorProviders(LightScheme, DarkScheme)
}

/**
 * Shared metrics for the two widgets, so the player (4x2) and lyrics (4x4) read as one
 * family: same outer rhythm, same cover rounding, same transport sizing.
 *
 * Sizes are tuned against the real rendered RemoteViews: the launcher reserves ~8dp
 * around every widget, so [outerPadding] is deliberately modest and the *effective*
 * outer gutter lands near Material's 16dp one.
 */
internal object WidgetDimens {

    /** Padding applied inside the widget surface (the launcher adds ~8dp of its own). */
    val outerPadding: Dp = 8.dp

    /** Corner radius on square cover art. */
    val coverCorner: Dp = 12.dp

    /** Thin progress bar height + corner radius. */
    val progressHeight: Dp = 3.dp
    val progressCorner: Dp = 2.dp

    /** Player (4x2): smaller cover, compact transport (the cell is only ~94dp tall). */
    val playerCover: Dp = 52.dp
    val playerSkip: Dp = 28.dp
    val playerPlay: Dp = 34.dp

    /** Lyrics (4x4): the square cell affords a bigger cover and play button. */
    val lyricsCover: Dp = 38.dp
    val lyricsSkip: Dp = 34.dp
    val lyricsPlay: Dp = 38.dp

    /** Transport glyph sizes. */
    val skipIcon: Dp = 22.dp
    val playIcon: Dp = 20.dp
}

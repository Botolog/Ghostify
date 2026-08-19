package xyz.botolog.ghostify.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Full light + dark M3 theme for the whole app. Every screen is rendered inside
 * this composable (via the UI component's MainActivity), so the dark/light
 * guarantee from T-175 holds globally as long as screens read colors from
 * `MaterialTheme.colorScheme` — the release checks in `checks/CI-CHECKLIST.md`
 * enforce that convention.
 *
 * - [darkTheme]: defaults to the system uiMode (works with Android 12+ per-app
 *   and user theme settings).
 * - [dynamicColor]: on Android 12+ the scheme derives from the wallpaper. The
 *   render tests pass `dynamicColor = false` explicitly so their assertions are
 *   deterministic across devices and wallpapers.
 */
@Composable
fun GhostifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

private val LightColors = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = LightBackground,
    surface = LightSurface,
    onBackground = LightOnBackground,
    onSurface = LightOnSurface,
)

private val DarkColors = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = DarkOnBackground,
    onSurface = DarkOnSurface,
)

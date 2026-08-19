package xyz.botolog.ghostify.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Ghostify static Material 3 palettes.
 *
 * These are the baseline M3 tones (purple seed). They are the deterministic
 * fallback used below API 31 and whenever dynamic color is disabled. The XML
 * side (values/colors.xml, values-night/colors.xml) keeps `windowBackground`
 * in sync with these surfaces so there is no white/dark flash at launch.
 */
val Purple40 = Color(0xFF6750A4)
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val LightBackground = Color(0xFFFEF7FF)
val LightSurface = Color(0xFFFEF7FF)
val LightOnBackground = Color(0xFF1D1B20)
val LightOnSurface = Color(0xFF1D1B20)

val DarkBackground = Color(0xFF1C1B1F)
val DarkSurface = Color(0xFF1C1B1F)
val DarkOnBackground = Color(0xFFE6E1E5)
val DarkOnSurface = Color(0xFFE6E1E5)

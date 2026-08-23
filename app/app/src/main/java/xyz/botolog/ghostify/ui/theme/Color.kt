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

/** Primary accent for light theme — deep purple. */
val Purple40 = Color(0xFF6750A4)

/** Secondary accent for light theme — muted purple-grey. */
val PurpleGrey40 = Color(0xFF625B71)

/** Tertiary accent for light theme — soft pink. */
val Pink40 = Color(0xFF7D5260)

/** Primary accent for dark theme — light purple. */
val Purple80 = Color(0xFFD0BCFF)

/** Secondary accent for dark theme — muted lavender. */
val PurpleGrey80 = Color(0xFFCCC2DC)

/** Tertiary accent for dark theme — light pink. */
val Pink80 = Color(0xFFEFB8C8)

/** Light theme background color. */
val LightBackground = Color(0xFFFEF7FF)

/** Light theme surface color. */
val LightSurface = Color(0xFFFEF7FF)

/** Text/icon color on light background. */
val LightOnBackground = Color(0xFF1D1B20)

/** Text/icon color on light surface. */
val LightOnSurface = Color(0xFF1D1B20)

/** Dark theme background color. */
val DarkBackground = Color(0xFF1C1B1F)

/** Dark theme surface color. */
val DarkSurface = Color(0xFF1C1B1F)

/** Text/icon color on dark background. */
val DarkOnBackground = Color(0xFFE6E1E5)

/** Text/icon color on dark surface. */
val DarkOnSurface = Color(0xFFE6E1E5)

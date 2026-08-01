package com.ghostify.ui.theme

import androidx.compose.material3.Typography

/**
 * Default M3 type scale. Kept as the platform default so the UI component can
 * build screens immediately; screens should apply these styles rather than
 * hardcoding sp sizes, which keeps large-font accessibility (Settings →
 * Font size) working without clipped labels (T-177).
 */
val Typography = Typography()

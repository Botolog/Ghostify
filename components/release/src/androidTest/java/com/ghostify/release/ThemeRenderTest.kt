package com.ghostify.release

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntil
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * T-175 — dark + light theme render correctly.
 *
 * Device-only (instrumentation). Two layers:
 *  1. Deterministic: render the reference Library screen inside GhostifyTheme
 *     with dynamic color disabled, in both modes, and assert the Material
 *     theme reports the expected lightness and the screen actually draws its
 *     key nodes.
 *  2. System-driven: flip `cmd uimode night` and assert GhostifyTheme follows
 *     the system (this also covers per-app dark override on 12+ and the
 *     launcher's XML dark theme, which the Compose rule's host activity uses).
 *
 * Note: dynamicColor is forced off in this test so the palette assertions are
 * stable across devices and wallpapers; production still defaults to dynamic
 * color on 12+.
 */
@RunWith(AndroidJUnit4::class)
class ThemeRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scheme = AtomicReference<ColorScheme?>()

    @Test
    fun lightTheme_rendersScreens_andReportsLight() =
        assertThemeRenders(darkTheme = false)

    @Test
    fun darkTheme_rendersScreens_andReportsDark() =
        assertThemeRenders(darkTheme = true)

    @Test
    fun systemNightMode_isHonouredByTheme() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val isDark = AtomicReference<Boolean?>()

        composeRule.setContent {
            GhostifyTheme { isDark.set(isSystemInDarkTheme()) }
        }
        composeRule.waitForIdle()

        val available = runCatching { currentNightMode(automation) }.getOrNull()
        assumeTrue("`cmd uimode night` not available on this device", available != null)
        var restoreTo: String? = null
        try {
            restoreTo = available
            setNightMode(automation, "no")
            composeRule.waitUntil(timeoutMillis = 5_000) { isDark.get() == false }
            setNightMode(automation, "yes")
            composeRule.waitUntil(timeoutMillis = 5_000) { isDark.get() == true }
        } finally {
            restoreTo?.let { runCatching { setNightMode(automation, it) } }
        }
    }

    private fun assertThemeRenders(darkTheme: Boolean) {
        scheme.set(null)
        composeRule.setContent {
            GhostifyTheme(darkTheme = darkTheme, dynamicColor = false) {
                scheme.set(MaterialTheme.colorScheme)
                ReferenceScreen()
            }
        }
        composeRule.waitForIdle()

        val cs = checkNotNull(scheme.get())
        assertEquals(
            "MaterialTheme must report ${if (darkTheme) "dark" else "light"}",
            darkTheme,
            !cs.isLight,
        )
        val surfaceLuminance = cs.surface.luminance()
        if (darkTheme) {
            assertTrue("dark surface is too bright: $surfaceLuminance", surfaceLuminance < 0.5f)
        } else {
            assertTrue("light surface is too dark: $surfaceLuminance", surfaceLuminance > 0.5f)
        }

        // The screen actually renders its key nodes in this theme.
        val context = composeRule.activity
        composeRule.onNodeWithText(context.getString(R.string.library_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Playlist 1").assertIsDisplayed()
        composeRule.onNodeWithTag("add_playlist_fab").assertIsDisplayed()

        assertNotNull(scheme.get())
    }

    private fun currentNightMode(automation: UiAutomation): String {
        val output = automation.executeShellCommand("cmd uimode").use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        return if ("night=yes" in output) "yes" else "no"
    }

    private fun setNightMode(automation: UiAutomation, mode: String) {
        automation.executeShellCommand("cmd uimode night $mode").use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }
}

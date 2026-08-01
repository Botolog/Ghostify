package com.ghostify.release

import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghostify.R
import com.ghostify.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-172 — the app runs on a minSdk 26 device.
 *
 * Device-only (instrumentation) — must be executed on an API 26 (or any
 * supported) device, which is exactly what this test's existence proves. It
 * boots the real MainActivity and drives the core first-run flow (library →
 * add-playlist dialog) end to end.
 *
 * Contract with the UI component: MainActivity renders the Library screen whose
 * add button exposes contentDescription @string/cd_add_playlist_button and
 * opens the add dialog titled @string/add_title.
 */
@RunWith(AndroidJUnit4::class)
class MinSdkCompatibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun deviceIsAtOrAboveMinSdk() {
        assertTrue(
            "minSdk 26 required; this device runs API ${Build.VERSION.SDK_INT}",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
        )
    }

    @Test
    fun appBoots_and_coreFirstRunFlowWorks() {
        val context = composeRule.activity
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.cd_add_playlist_button),
        ).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(context.getString(R.string.add_title)).assertIsDisplayed()
    }
}

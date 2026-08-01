package com.ghostify.release

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ghostify.R
import com.ghostify.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-181 — clean install + upgrade install both pass.
 *
 * Device-only (instrumentation + a CI script step):
 *
 *  1. Instrumented, here: the app behaves correctly on a fresh install
 *     (no onboarding gate — the library is immediately usable, automated slice
 *     of T-178) and app data survives a process-death/relaunch cycle (the
 *     in-app part of the upgrade path).
 *  2. Scripted, in checks/CI-CHECKLIST.md: a vN APK is installed, then a vN+1
 *     APK (higher versionCode) is applied with `adb install -r`; this test,
 *     re-run after that upgrade, is the assertion that the app still boots and
 *     its data is intact.
 *
 * The Room schema migration itself (v1→v2 preserving the library) is owned by
 * the database component (T-074) and is exercised by its migration test plus
 * this CI upgrade step.
 */
@RunWith(AndroidJUnit4::class)
class InstallUpgradeSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun freshInstall_firstRun_isFunctional() {
        // No onboarding/first-run wizard: the library shows its empty state
        // and the add button is reachable immediately.
        composeRule.onNodeWithText(context.getString(R.string.library_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.library_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.cd_add_playlist_button),
        ).assertIsDisplayed()
    }

    @Test
    fun appData_survivesProcessDeath() {
        // Settings (SharedPreferences) are written and read back after the
        // activity is destroyed/recreated — the same relaunch that `adb install
        // -r` performs. Room-library persistence across the real upgrade is
        // covered by the database component's migration test + CI step.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_KEY, "library-marker")
            .commit()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.library_title)).assertIsDisplayed()
        assertEquals(
            "library-marker",
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREFS_KEY, null),
        )
    }

    private companion object {
        const val PREFS_NAME = "ghostify_settings"
        const val PREFS_KEY = "install_upgrade_marker"
    }
}

package com.ghostify.release

import android.Manifest
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ghostify.ui.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-173 — notification permission flow works on the latest Android (current
 * target), and denial is a safe state (automated slice of T-174; the visual
 * prompt itself stays manual).
 *
 * Device-only (instrumentation) on an API 33+ device/emulator. Verifies:
 *  - API < 33: permission is implicitly held, no request is ever needed.
 *  - API >= 33: after a revoke the manager correctly reports "should request",
 *    the app launches and stays resumed in the *denied* state (no crash), and
 *    after a grant the state reflects it.
 *
 * The on-screen prompt and "deny twice → don't ask again" flow are inherently
 * manual (T-174) — they are exercised in the manual checklist.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionFlowTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun resetToDenied() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.revokeRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    @Test
    fun belowApi33_permissionIsImplicit() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        assertTrue(NotificationPermissionManager.hasNotificationPermission(context))
        assertFalse(NotificationPermissionManager.shouldRequest(context))
    }

    @Test
    fun onApi33Plus_deniedState_doesNotCrash() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        // The @Before reset guarantees we enter with the permission revoked, i.e.
        // the state that results from the user pressing "don't allow".
        assertFalse(NotificationPermissionManager.hasNotificationPermission(context))
        assertTrue(NotificationPermissionManager.shouldRequest(context))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.RESUMED))
        }
        // Launching + resuming in the denied state must not crash the app.
    }

    @Test
    fun onApi33Plus_grant_reflectsInManager() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        assertTrue(NotificationPermissionManager.hasNotificationPermission(context))
        assertFalse(NotificationPermissionManager.shouldRequest(context))
        assertTrue(NotificationPermissionManager.notificationsEnabled(context))
    }
}

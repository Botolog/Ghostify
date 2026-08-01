package com.ghostify.release

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Centralizes the Android 13+ (API 33) notification permission flow so that a
 * denial is a normal state transition — never a crash.
 *
 * Requirements this encodes:
 *  - API < 33: no runtime prompt exists; the permission is implicitly granted.
 *  - API >= 33: we request `POST_NOTIFICATIONS` once at first run. The result
 *    must be handled gracefully whether the user grants, denies, or denies
 *    twice ("don't ask again"). Denial only means we skip notification-based
 *    features; playback/downloads still function (the user just loses
 *    notification surfaces), matching T-174's "deniable without crash".
 *
 * Callers (MainActivity / ViewModels) should funnel the Activity's
 * `onRequestPermissionsResult` through [onRequestPermissionsResult] so the
 * decision is handled in exactly one place.
 */
object NotificationPermissionManager {

    const val REQUEST_CODE_NOTIFICATIONS = 3301

    /** Whether the runtime prompt applies at all on [sdk]. */
    fun isRequired(sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk >= Build.VERSION_CODES.TIRAMISU

    /** True when the app may post notifications. Never throws on old devices. */
    fun hasNotificationPermission(context: Context): Boolean =
        !isRequired() ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /** True only when a runtime request is both possible and still needed. */
    fun shouldRequest(context: Context): Boolean =
        isRequired() && !hasNotificationPermission(context)

    /**
     * Requests the permission once if needed. No-op on API < 33 and when the
     * permission is already granted, so callers can invoke it unconditionally
     * at first-run without branching on the SDK level themselves.
     */
    fun requestIfNeeded(activity: Activity) {
        if (shouldRequest(activity)) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_NOTIFICATIONS,
            )
        }
    }

    /**
     * Handles the system result. Returns true if [requestCode] was ours.
     * It performs no action and throws nothing: whether the user granted,
     * denied, or permanently denied, the app stays alive and the UI reflects
     * [hasNotificationPermission] on the next recomposition.
     */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != REQUEST_CODE_NOTIFICATIONS) return false
        return true
    }

    /** User-level switch (Settings → Apps → Ghostify → Notifications). */
    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Deep-link to the per-app notification settings, e.g. from a rationale banner. */
    fun openNotificationSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

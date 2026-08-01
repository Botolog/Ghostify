package com.ghostify.python

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The `:pythond` process. Runs in its own process (`android:process=":pythond"`)
 * so that a Python segfault can only ever kill this process — never the app
 * (TEST_PLAN T-028). The app process binds to it lazily via [PythonRuntime].
 *
 * Lifecycle:
 * - `onCreate` (main thread): starts the interpreter exactly once and wires up
 *   the ffmpeg PATH. If this fails, the process dies before any client
 *   connects and the client reports [PythonError.InterpreterUnavailable].
 * - Requests are executed on a single-thread executor, which is the host-side
 *   half of the serialization guarantee (TEST_PLAN T-029): even if several
 *   AIDL calls arrive concurrently, the interpreter runs them strictly one at
 *   a time.
 * - The process is kept alive by the app's binding; long-running work may call
 *   [IPythonHost.enterForeground] to hold it while the app is backgrounded
 *   (used by the download manager).
 */
class PythondService : Service() {

    private val tag = "PythondService"
    private val host = PythonHost(this)
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "python-host") }
    private val callbacks = CopyOnWriteArraySet<IPythonHostCallback>()

    override fun onCreate() {
        super.onCreate()
        try {
            host.ensureStarted()
        } catch (t: Throwable) {
            Log.e(tag, "Python interpreter failed to start", t)
            stopSelf()
            throw t
        }
    }

    override fun onBind(intent: Intent?): IBinder = HostBinder()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private inner class HostBinder : IPythonHost.Stub() {

        override fun call(requestJson: String): String {
            val future: Future<String> = executor.submit(Callable { host.handleRequest(requestJson) })
            return try {
                future.get(HOST_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                HostProtocol.encodeError(
                    kind = "Timeout",
                    type = "Timeout",
                    message = "Python call exceeded $HOST_CALL_TIMEOUT_MS ms",
                )
            } catch (e: Exception) {
                HostProtocol.encodeError(
                    kind = "HostError",
                    type = e.javaClass.simpleName,
                    message = e.message ?: "host execution failure",
                )
            }
        }

        override fun registerCallback(callback: IPythonHostCallback?) {
            callback?.let(callbacks::add)
        }

        override fun unregisterCallback(callback: IPythonHostCallback?) {
            callback?.let(callbacks::remove)
        }

        override fun enterForeground(notificationId: Int, channelId: String, title: String, text: String) {
            val notification = foregroundNotification(notificationId, channelId, title, text)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(notificationId, notification)
            }
        }

        override fun leaveForeground() {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun foregroundNotification(id: Int, channelId: String, title: String, text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Ghostify background work", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private companion object {
        // Generous; a per-call client timeout is the primary limit. A hung
        // interpreter is recovered by PythonRuntime.reset().
        const val HOST_CALL_TIMEOUT_MS = 10 * 60 * 1000L
    }
}

package com.ghostify.python

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.io.Closeable

/**
 * Manages the connection from the app process to the `:pythond` service that
 * owns the single Chaquopy interpreter.
 *
 * Responsibilities:
 * - lazy process spawn via `bindService` (the system creates the process, and
 *   the service stays alive while bound),
 * - crash detection via binder death *and* `ServiceConnection`, so a Python
 *   segfault is surfaced as a typed [PythonError.InterpreterCrashed] instead of
 *   taking the app down (TEST_PLAN T-028),
 * - automatic re-bind on the next call after a death, with a short grace period
 *   so a crash-looping interpreter cannot hammer the process spawner,
 * - a registered event callback per connection (progress hooks from Python).
 *
 * All methods are designed to be called from the single bridge worker thread
 * (see [PythonRuntime]); internal state is synchronized on [lock] because the
 * binder connection callbacks arrive on the main thread.
 */
internal class InterpreterClient(
    private val appContext: Context,
    private val onEvent: (PythonEvent) -> Unit = {},
) : Closeable {

    private val tag = "PythonRuntime"
    private val lock = Object()
    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() = onHostLost("binder death")
    }
    private val eventProxy = object : IPythonHostCallback.Stub() {
        override fun onEvent(json: String?) {
            json?.let { onEvent(PythonEvent.PythonEmitted(it)) }
        }
    }

    private enum class State { IDLE, BINDING, READY, DEAD }

    private var state = State.IDLE
    private var binder: IPythonHost? = null
    private var connection: ServiceConnection? = null
    private var registeredOn: IBinder? = null
    private var lastDeathAt = 0L

    /** Binds if needed and runs [block] with a live host handle. */
    fun <T> withHost(bindTimeoutMs: Long, block: (IPythonHost) -> T): T {
        val host = acquire(bindTimeoutMs)
        return try {
            ensureEventsRegistered(host)
            block(host)
        } catch (e: DeadObjectException) {
            onHostLost("DeadObjectException")
            throw PythonError.InterpreterCrashed(e)
        } catch (e: RemoteException) {
            onHostLost("RemoteException")
            throw PythonError.InterpreterCrashed(e)
        }
    }

    private fun acquire(bindTimeoutMs: Long): IPythonHost {
        val deadline = SystemClock.uptimeMillis() + maxOf(bindTimeoutMs, 1L)
        synchronized(lock) {
            while (true) {
                when (state) {
                    State.READY -> {
                        return binder ?: throw PythonError.InterpreterUnavailable("binder is null")
                    }
                    State.BINDING -> {
                        val remaining = deadline - SystemClock.uptimeMillis()
                        if (remaining <= 0) throw PythonError.BindTimeout(bindTimeoutMs)
                        lock.wait(remaining)
                    }
                    State.DEAD -> {
                        // Crash-loop throttle: never respawn more than once per
                        // REBIND_GRACE_MS unless a caller explicitly wants to retry.
                        val sinceDeath = SystemClock.uptimeMillis() - lastDeathAt
                        if (sinceDeath < REBIND_GRACE_MS) {
                            lock.wait(REBIND_GRACE_MS - sinceDeath)
                        }
                        startBindLocked()
                    }
                    State.IDLE -> startBindLocked()
                }
            }
        }
    }

    private fun startBindLocked() {
        state = State.BINDING
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val ipc = service?.let { IPythonHost.Stub.asInterface(it) }
                synchronized(lock) {
                    binder = ipc
                    if (ipc != null) {
                        try {
                            ipc.asBinder().linkToDeath(deathRecipient, 0)
                        } catch (e: RemoteException) {
                            state = State.DEAD
                        }
                        if (state == State.BINDING) state = State.READY
                    } else {
                        state = State.DEAD
                    }
                    lock.notifyAll()
                }
                if (ipc != null) onEvent(PythonEvent.HostConnected)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                onHostLost("service disconnected")
            }
        }
        connection = conn
        val ok = appContext.bindService(
            Intent(appContext, PythondService::class.java),
            conn,
            Context.BIND_AUTO_CREATE,
        )
        if (!ok) {
            try {
                appContext.unbindService(conn)
            } catch (_: Exception) {
                // no-op
            }
            synchronized(lock) {
                state = State.DEAD
                lock.notifyAll()
            }
            throw PythonError.InterpreterUnavailable("bindService returned false")
        }
    }

    private fun ensureEventsRegistered(host: IPythonHost) {
        val ipcBinder = host.asBinder()
        if (registeredOn !== ipcBinder) {
            try {
                host.registerCallback(eventProxy)
                registeredOn = ipcBinder
            } catch (e: RemoteException) {
                Log.w(tag, "could not register event callback", e)
            }
        }
    }

    private fun onHostLost(reason: String) {
        var notify = false
        synchronized(lock) {
            binder?.asBinder()?.unlinkToDeath(deathRecipient, 0)
            binder = null
            if (state == State.READY || state == State.BINDING) {
                state = State.DEAD
                lastDeathAt = SystemClock.uptimeMillis()
                notify = true
            }
            if (notify) lock.notifyAll()
        }
        Log.w(tag, "Python host lost: $reason")
        onEvent(PythonEvent.HostDied)
    }

    /** Kill the host process (used by [PythonRuntime.reset]). */
    fun stopHost() {
        try {
            appContext.stopService(Intent(appContext, PythondService::class.java))
        } catch (_: Exception) {
            // no-op
        }
    }

    override fun close() {
        synchronized(lock) {
            connection?.let {
                try {
                    appContext.unbindService(it)
                } catch (_: Exception) {
                    // no-op
                }
            }
            connection = null
            binder = null
            state = State.IDLE
        }
    }

    private companion object {
        const val REBIND_GRACE_MS = 2_000L
    }
}

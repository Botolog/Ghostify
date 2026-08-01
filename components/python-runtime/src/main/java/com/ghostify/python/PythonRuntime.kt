package com.ghostify.python

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/**
 * Ghostify's Python runtime bootstrap.
 *
 * The single Chaquopy interpreter lives in the `:pythond` process (see
 * [PythondService]) so that a Python crash can never take down the app process
 * (TEST_PLAN T-028). This object is the app-process facade:
 *
 * - lazy init: the host process is spawned on the first bridge call, at most
 *   once per host lifetime (TEST_PLAN T-024),
 * - serialized calls: every call is funneled through a single-threaded
 *   [SerialExecutor] (TEST_PLAN T-029) and executed by the host's own
 *   single-thread executor — concurrent bridge calls can never interleave,
 * - off the main thread: the suspend API is main-safe; the blocking API
 *   throws [PythonError.MainThreadCall] if invoked from the main thread
 *   (TEST_PLAN T-027),
 * - crash containment: host death is detected via binder death and surfaced as
 *   a typed [PythonError.InterpreterCrashed]; the next call transparently
 *   restarts the host.
 *
 * Init from anywhere with an app [Context] (e.g. `Application.onCreate` or a
 * Hilt module): `PythonRuntime.init(context)`.
 */
object PythonRuntime {

    private const val BRIDGE_THREAD_NAME = "python-bridge"
    private const val DEFAULT_BIND_TIMEOUT_MS = 20_000L
    private const val DEFAULT_CALL_TIMEOUT_MS = 60_000L

    @Volatile private var appContext: Context? = null
    @Volatile private var bridgeClient: InterpreterClient? = null
    @Volatile private var serialExecutor: SerialExecutor? = null

    @Volatile private var lastMeta: HostMeta? = null

    private val lifecycleLock = Object()
    private val eventFlowInternal = MutableSharedFlow<PythonEvent>(extraBufferCapacity = 16)

    /** Timeout for connecting (spawning + starting) the host process. */
    @Volatile var bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS

    /** Default per-call timeout when none is supplied to [callBlocking]. */
    @Volatile var callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS

    /** Observes host lifecycle/transport events. */
    val eventFlow: Flow<PythonEvent> = eventFlowInternal

    /**
     * Stores the application context and prepares the bridge. Idempotent.
     * In the `:pythond` process this is a no-op (the host uses [PythonHost]
     * directly and must not spawn itself).
     */
    fun init(context: Context) {
        if (isPythondProcess()) return
        synchronized(lifecycleLock) {
            if (appContext == null) {
                val app = context.applicationContext
                appContext = app
                bridgeClient = InterpreterClient(app) { eventFlowInternal.tryEmit(it) }
                serialExecutor = SerialExecutor(BRIDGE_THREAD_NAME)
            }
        }
    }

    fun isInitialized(): Boolean = appContext != null

    /** True once the host has answered at least one call. */
    fun isBooted(): Boolean = lastMeta != null

    /**
     * Eagerly spawns and boots the host in the background so the first real
     * call is fast. Failures are logged, never propagated — it is a hint.
     */
    fun warmUp() {
        if (isPythondProcess()) return
        appContext ?: throw PythonError.NotInitialized()
        thread(name = "python-warmup", isDaemon = true) {
            try {
                bootBlocking()
            } catch (e: PythonError) {
                // warm-up is best-effort; a later real call will retry
            }
        }
    }

    /**
     * Runs a bridge call on the calling (non-main) thread and blocks until it
     * completes. Prefer the suspend [call] from coroutines.
     *
     * @param timeoutMs per-call limit in ms; `0` waits indefinitely.
     * @throws PythonError on any failure (typed, never a raw crash).
     */
    fun callBlocking(
        module: String,
        method: String,
        args: List<Any?> = emptyList(),
        timeoutMs: Long = callTimeoutMs,
    ): PyValue = invokeBlocking(module, method, args, timeoutMs).ok

    /** Main-safe suspend equivalent of [callBlocking]. */
    suspend fun call(module: String, method: String, vararg args: Any?): PyValue =
        withContext(Dispatchers.IO) { callBlocking(module, method, args.toList()) }

    /**
     * Boots (or reuses) the interpreter and returns boot facts. The first call
     * includes host spawn + interpreter start, so [BootResult.loadMs] is the
     * cold-start figure the 10&nbsp;s budget is measured against (T-023).
     */
    suspend fun boot(): BootResult = withContext(Dispatchers.IO) { bootBlocking() }

    fun bootBlocking(): BootResult {
        val start = SystemClock.elapsedRealtime()
        val probe = invokeBlocking("ghostify_dl", "boot_probe", emptyList(), callTimeoutMs).ok as Map<*, *>
        val loadMs = SystemClock.elapsedRealtime() - start
        val meta = lastMeta ?: throw PythonError.InterpreterUnavailable("missing host metadata")
        return BootResult(
            interpreterId = meta.interpreterId,
            abi = meta.abi,
            pythonVersion = probe["python_version"] as? String ?: "unknown",
            loadMs = loadMs,
            hostUptimeMs = meta.hostUptimeMs,
            probe = probe,
        )
    }

    /**
     * Drops the current host (stops `:pythond`) and re-arms the bridge. Use
     * this to recover from a hung interpreter: the next call starts a fresh
     * host. Safe to call from any thread.
     */
    fun reset() {
        val app = appContext ?: return
        val oldClient: InterpreterClient?
        val oldExecutor: SerialExecutor?
        synchronized(lifecycleLock) {
            oldClient = bridgeClient
            oldExecutor = serialExecutor
            bridgeClient = InterpreterClient(app) { eventFlowInternal.tryEmit(it) }
            serialExecutor = SerialExecutor(BRIDGE_THREAD_NAME)
            lastMeta = null
        }
        oldClient?.stopHost()
        oldClient?.close()
        oldExecutor?.close()
    }

    /** Name of the thread on which bridge calls are dispatched (tests: T-027). */
    fun dispatchThreadName(): String = serialExecutor?.threadName ?: "uninitialized"

    private fun invokeBlocking(
        module: String,
        method: String,
        args: List<Any?>,
        timeoutMs: Long,
    ): HostResponse {
        if (appContext == null) throw PythonError.NotInitialized()
        ThreadGuard.checkNotMain()
        val client = bridgeClient ?: throw PythonError.NotInitialized()
        val executor = serialExecutor ?: throw PythonError.NotInitialized()

        val future = CompletableFuture<HostResponse>()
        executor.execute {
            try {
                val response = client.withHost(bindTimeoutMs) { host ->
                    val request = HostProtocol.encodeRequest(module, method, args)
                    val json = host.call(request)
                    HostProtocol.parseResponse(json)
                }
                val previousId = lastMeta?.interpreterId
                response.meta?.let { lastMeta = it }
                if (previousId != null && response.meta != null && previousId != response.meta.interpreterId) {
                    eventFlowInternal.tryEmit(PythonEvent.HostRestarted(response.meta.interpreterId))
                }
                future.complete(response)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }

        return try {
            if (timeoutMs > 0) future.get(timeoutMs, TimeUnit.MILLISECONDS)
            else future.get()
        } catch (e: TimeoutException) {
            throw PythonError.CallTimeout(timeoutMs)
        } catch (e: ExecutionException) {
            throw e.cause as? PythonError
                ?: PythonError.HostErrorInfo(
                    kind = "HostError",
                    hostType = e.cause?.javaClass?.simpleName ?: "Unknown",
                    hostMessage = e.cause?.message ?: "unknown bridge failure",
                )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PythonError.HostErrorInfo("Interrupted", "InterruptedException", "bridge call interrupted")
        }
    }

    private fun isPythondProcess(): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            return Application.getProcessName()?.endsWith(":pythond") == true
        }
        return false
    }
}

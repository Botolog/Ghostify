package com.ghostify.python

import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException

/**
 * Minimal single-thread [Executor]: tasks run one at a time, strictly FIFO, on
 * one dedicated worker thread. This is the client-side guarantee that
 * concurrent bridge calls are serialized (TEST_PLAN T-029) and that no Python
 * work is ever triggered from the main thread.
 *
 * Pure JVM — unit-tested in `SerialExecutorTest`.
 *
 * [close] is non-blocking: tasks already queued are drained first (a poison
 * pill is appended behind them), then the worker exits; new submissions are
 * rejected.
 */
class SerialExecutor(name: String) : Executor, AutoCloseable {

    private val queue = LinkedBlockingQueue<Runnable>()

    @Volatile
    private var closed = false

    /** The worker thread's name; also used as the bridge's dispatch thread name. */
    val threadName: String = name

    private val worker = Thread({ runLoop() }, name).apply {
        isDaemon = false
        start()
    }

    private fun runLoop() {
        while (true) {
            val task = queue.take()
            if (task === POISON) return
            task.run()
        }
    }

    override fun execute(command: Runnable) {
        if (closed) throw RejectedExecutionException("SerialExecutor '$threadName' is closed")
        queue.put(command)
    }

    fun isOnWorker(): Boolean = Thread.currentThread() === worker

    override fun close() {
        closed = true
        queue.put(POISON)
    }

    private companion object {
        val POISON = Runnable {}
    }
}

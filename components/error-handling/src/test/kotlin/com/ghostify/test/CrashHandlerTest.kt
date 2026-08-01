package com.ghostify.test

import com.ghostify.crash.Crash
import com.ghostify.crash.CrashReporter
import com.ghostify.crash.GhostifyCrashHandler

/** T-158 safety net: the top-level handler never shows dialogs and never crashes itself. */
object CrashHandlerTest {

    fun run() {
        Harness.test("T-158: crash handler reports the crash to the reporter") {
            var captured: Crash? = null
            val handler = GhostifyCrashHandler(CrashReporter { captured = it; true })
            val thread = Thread.currentThread()
            val boom = IllegalStateException("boom")
            handler.uncaughtException(thread, boom)
            Harness.expectNotNull(captured, "reporter was not called")
            Harness.expectTrue(captured!!.throwable === boom, "wrong throwable captured")
            Harness.expectTrue(captured!!.thread === thread, "wrong thread captured")
        }

        Harness.test("T-158: handler delegates to the previous handler") {
            var previousCalled = false
            val handler = GhostifyCrashHandler(
                reporter = CrashReporter { true },
                previous = Thread.UncaughtExceptionHandler { _, _ -> previousCalled = true },
            )
            handler.uncaughtException(Thread.currentThread(), RuntimeException("x"))
            Harness.expectTrue(previousCalled, "previous handler was not delegated to")
        }

        Harness.test("T-158: handler does nothing when there is no previous handler") {
            val handler = GhostifyCrashHandler(
                reporter = CrashReporter { true },
                previous = null,
            )
            handler.uncaughtException(Thread.currentThread(), RuntimeException("x"))
        }

        Harness.test("T-158: a throwing reporter cannot crash the handler") {
            val throwingReporter = CrashReporter { throw RuntimeException("reporter broke") }
            val handler = GhostifyCrashHandler(reporter = throwingReporter, previous = null)
            handler.uncaughtException(Thread.currentThread(), RuntimeException("x"))
        }

        Harness.test("T-158: handler maps the throwable for diagnostics without crashing") {
            val handler = GhostifyCrashHandler(reporter = CrashReporter { true }, previous = null)
            handler.uncaughtException(Thread.currentThread(), java.net.UnknownHostException("host"))
        }

        Harness.test("T-158: install/restore wiring does not throw") {
            val handler = GhostifyCrashHandler(reporter = CrashReporter { true })
            val original = Thread.getDefaultUncaughtExceptionHandler()
            handler.install()
            Harness.expectTrue(Thread.getDefaultUncaughtExceptionHandler() === handler)
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}

package com.ghostify.test

/**
 * Minimal dependency-free JVM test harness. Run via `run-tests.sh`.
 */
object Harness {
    private var passed = 0
    private var failed = 0
    private val failures = mutableListOf<String>()

    fun test(name: String, block: () -> Unit) {
        try {
            block()
            passed++
            println("  PASS  $name")
        } catch (e: Throwable) {
            failed++
            failures.add(name)
            println("  FAIL  $name")
            e.printStackTrace()
        }
    }

    fun expectTrue(cond: Boolean, message: String = "expected condition to be true") {
        if (!cond) throw AssertionError(message)
    }

    fun expectFalse(cond: Boolean, message: String = "expected condition to be false") {
        if (cond) throw AssertionError(message)
    }

    fun expectEq(expected: Any?, actual: Any?, message: String = "") {
        if (expected != actual) {
            throw AssertionError("$message expected <$expected> but was <$actual>")
        }
    }

    fun expectNotNull(value: Any?, message: String = "expected non-null value") {
        if (value == null) throw AssertionError(message)
    }

    fun expectThrows(t: Throwable, message: String = "expected block to throw", block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (t.javaClass.isInstance(e)) return
            throw AssertionError("$message — threw ${e.javaClass.simpleName} instead of ${t.javaClass.simpleName}")
        }
        throw AssertionError(message)
    }

    fun summary() {
        println()
        println("==================================================")
        println("  PASSED: $passed    FAILED: $failed")
        if (failures.isNotEmpty()) {
            println("  Failed tests:")
            failures.forEach { println("    - $it") }
        }
        println("==================================================")
        if (failed > 0) kotlin.system.exitProcess(1)
    }
}

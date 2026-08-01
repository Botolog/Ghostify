package com.ghostify.test

/**
 * Entry point for the JVM unit-test harness.
 *
 * Coverage map:
 *  - [ErrorMapperTest.t157]  -> T-157 (every failure -> readable message, no raw exceptions)
 *  - [ErrorMapperTest.t158]  -> T-158 (no crashes on the five realistic failure modes)
 *  - [RecoveryLogicTest]     -> T-159 core (killed-process recovery, no stuck DOWNLOADING)
 *  - [CrashHandlerTest]      -> T-158 safety net (no crash dialogs, handler never throws)
 *  - [TimestampTest]         -> T-162 support (epoch-ms sorting immune to DST/travel)
 *
 * T-159/T-160 device halves live in src/androidTest and are marked device-only.
 * T-161/T-162 manual checklists live in solution.md.
 */
fun main() {
    println("Ghostify error-handling unit tests")
    println("----------------------------------")
    ErrorMapperTest.t157()
    ErrorMapperTest.t158()
    RecoveryLogicTest.run()
    CrashHandlerTest.run()
    TimestampTest.run()
    Harness.summary()
}

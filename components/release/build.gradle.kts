// ============================================================================
//  Ghostify — release-readiness static gates (standalone JVM harness)
//
//  This component's real home is the Android app module
//  (see gradle/app.build.gradle.kts for the release wiring: minify, proguard,
//  Chaquopy). This standalone kotlin-jvm build exists so the *static* gates
//  (LocalizationCheckTest, ReleaseContractTest) run on a plain Linux CI host
//  with no Android SDK, exactly like the database/file-management components.
//
//  The Android source sets (src/main, src/androidTest, androidTestRelease) are
//  intentionally NOT compiled here: they need the Android + Compose + Chaquopy
//  toolchains and are verified by :app:assembleRelease /
//  :app:connectedDebugAndroidTest in the merged project (checks/CI-CHECKLIST.md).
//
//  Run:  ./gradlew test --offline
// ============================================================================
plugins {
    kotlin("jvm") version "2.0.21"
}

kotlin {
    jvmToolchain(21)
    sourceSets {
        // Only the JVM static-gate tests belong in this harness; the rest of
        // src/ is Android code owned by the app module's build.
        main {
            // `srcDirs` appends, `setSrcDirs` replaces: clear the default
            // (src/main/java holds Android-only code owned by the app module).
            kotlin.setSrcDirs(emptyList<String>())
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

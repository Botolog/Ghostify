// ============================================================================
//  Ghostify — Security & Privacy component (standalone JVM harness)
//
//  Pure-JVM module so the static gates (T-168, T-169 static portion, T-170,
//  T-171) run on a plain Linux CI host with no Android SDK, exactly like the
//  database/file-management components.
//
//  src/main/java   — LogPolicy + SecretRedactor + SpotifySanitizer (runtime),
//                    HttpPolicy, ManifestAudit, SecurityScan.
//  src/test/java   — unit tests + the repo-wide static scan tests.
//  src/androidTest — T-169 instrumentation test. NOT compiled here: it needs
//                    the Android toolchain and a device; it runs via
//                    :app:connectedDebugAndroidTest in the merged project.
//
//  Run:  gradle test --offline
// ============================================================================
plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    sourceSets {
        main { kotlin.srcDirs("src/main/java") }
        test { kotlin.srcDirs("src/test/java") }
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

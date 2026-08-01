import java.nio.file.Files
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "1.9.24"
}

repositories {
    mavenCentral()
    google()
}

// ============================================================================
// Ghostify Performance component — standalone verification build.
//
// Source sets:
//   * `main`         -> the pure, Android-free performance logic
//                      (FrameTiming, LazyListMetrics, MemoryMetrics,
//                      DownloadLeakTracker, CoalescingThrottle,
//                      BridgeStallDetector) + the Android-only perf harness
//                      helpers (FrameStatsCollector, ChoreographerStallProbe,
//                      RetainedHeapProbe) and reusable Compose lazy-list
//                      patterns (PlaylistList, TrackList). The JVM core is
//                      unit-tested; the android harness + patterns are
//                      compile-checked against android.jar + AARs.
//   * `test`         -> JVM unit tests (tests/unit). Proves the math behind every
//                      budget (jank percentiles, heap-growth regression, lazy-list
//                      composition bound, bridge-stall detection, coalescing).
//
// Device-only (NOT compiled by this JVM build, runs on device via the real AGP
// `connectedAndroidTest` in the Ghostify app):
//   * src/androidTest -> T-163..T-166 instrumentation tests. T-163/T-164 render
//                      Compose lists via `createAndroidComposeRule` (an Android-
//                      only entry point absent from the Compose `jvmStubs`
//                      artifacts); AGP cannot link resources on this host anyway.
//   * T-167 is manual (background battery sanity), per TESTS.md.
// ============================================================================

val androidJar: Provider<String> = providers.gradleProperty("androidJar")
    .orElse(
        System.getenv("ANDROID_HOME")?.let { "$it/platforms/android-36/android.jar" }
            ?: "/opt/android-sdk/platforms/android-36/android.jar"
    )

val androidAars: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = true
}

sourceSets {
    main {
        // Remove the kotlin plugin's default `src/main/java` convention (and any
        // other roots it seeded) before adding only the pure-JVM subdir, so the
        // JVM build never sees the android/ harness helpers (which need
        // android.jar). `androidCheck` (below) still compiles the full tree.
        java {
            setSrcDirs(listOf("src/main/java/com/ghostify/perf/jvm"))
        }
        kotlin {
            setSrcDirs(listOf("src/main/java/com/ghostify/perf/jvm"))
        }
    }
    test {
        kotlin {
            srcDirs("tests/unit")
        }
    }
    create("androidCheck") {
        kotlin {
            // Production sources — pure JVM core + Android harness helpers
            // (FrameStatsCollector, ChoreographerStallProbe, RetainedHeapProbe).
            // Compiled against android.jar + the androidx/LeakCanary AARs so the IDE
            // host can prove the device-only code is well-formed.
            //
            // Plus the two instrumentation tests that are STUB-COMPATIBLE
            // (no Compose rendering): T-165 (bridge threading) and T-166 (memory/
            // leak). These exercise the real Choreographer/RetainedHeap/LeakCanary
            // probes and are local-compile-verified here.
            srcDirs("src/main/java/com/ghostify/perf")
            srcDirs("src/androidTest/java/com/ghostify/perf/bridge")
            srcDirs("src/androidTest/java/com/ghostify/perf/memory")
        }
    }
}

// Resolve the androidx / LeakCanary AARs (as files) and unpack each one's
// classes.jar into a flat build dir that androidCheck can put on its compile
// classpath. Plain `.jar` dependencies (junit, kotlin-stdlib, shark, ...) are
// copied through untouched.
val androidClassesDir = layout.buildDirectory.dir("androidCheck/classes")

val extractAndroidAarClasses by tasks.registering {
    val aarFiles = androidAars.incoming.artifactView { lenient(true) }.files
    inputs.files(aarFiles)
    outputs.dir(androidClassesDir)
    doLast {
        val out = androidClassesDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        aarFiles.forEach { f ->
            if (f.name.endsWith(".aar")) {
                val zip = ZipFile(f)
                try {
                    val entry = zip.getEntry("classes.jar") ?: return@forEach
                    val name = f.name.removeSuffix(".aar") + ".jar"
                    zip.getInputStream(entry).use { input ->
                        Files.copy(input, out.toPath().resolve(name))
                    }
                } finally {
                    zip.close()
                }
            } else if (f.name.endsWith(".jar")) {
                Files.copy(f.toPath(), out.toPath().resolve(f.name))
            }
        }
        logger.lifecycle("androidCheck: unpacked ${aarFiles.count()} AAR/jar files")
    }
}

dependencies {
    implementation(kotlin("stdlib"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.24")

    // Android-only deps for the androidCheck source set. Versions are pinned to
    // what the rest of the repo builds against (androidx.core 1.13.1,
    // Compose 1.7.3, material3 1.3.0, androidx.test runner/ext/core).
    androidAars("androidx.core:core:1.13.1")
    androidAars("androidx.activity:activity-compose:1.9.2")
    androidAars("androidx.compose.ui:ui:1.7.3")
    androidAars("androidx.compose.ui:ui-test-junit4:1.7.3")
    androidAars("androidx.compose.ui:ui-test-manifest:1.7.3")
    androidAars("androidx.compose.foundation:foundation:1.7.3")
    androidAars("androidx.compose.material3:material3:1.3.0")
    androidAars("androidx.test:runner:1.6.2")
    androidAars("androidx.test:core:1.6.1")
    androidAars("androidx.test.ext:junit:1.2.1")

    // LeakCanary instrumentation: provides LeakAssertions + FailTestOnLeak used
    // by the T-166 memory/leak test (mavenCentral).
    androidAars("com.squareup.leakcanary:leakcanary-android-instrumentation:2.14")

    // JUnit4 (for the instrumentation tests) and kotlin-stdlib for androidCheck.
    "androidCheckCompileOnly"("junit:junit:4.13.2")
    "androidCheckCompileOnly"(fileTree(androidClassesDir) { include("*.jar") })
    "androidCheckCompileOnly"(files(androidJar))
    "androidCheckCompileOnly"(kotlin("stdlib"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // Verifying that the device-only sources compile is part of this build.
    dependsOn("compileAndroidCheckKotlin")
}

tasks.named("compileAndroidCheckKotlin") {
    dependsOn(extractAndroidAarClasses)
    doFirst {
        logger.lifecycle("androidCheck: compiling ALL performance + instrumentation sources against android.jar + androidx/LeakCanary")
    }
}

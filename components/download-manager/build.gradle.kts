import java.nio.file.Files
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "1.9.24"
}

repositories {
    mavenCentral()
    google()
}

// The androidCheck source set (below) compiles the Android-only files against the
// real android.jar + the classes.jar of the androidx AARs. AARs cannot be consumed
// directly by a pure JVM project, so we resolve them as raw files in the
// `androidAars` configuration and unpack classes.jar into build/androidCheck.
val androidJar: Provider<String> = providers.gradleProperty("androidJar")
    .orElse(System.getenv("ANDROID_HOME")?.let { "$it/platforms/android-34/android.jar" })
    .orElse("/opt/android-sdk/platforms/android-34/android.jar")

val androidAars: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = true
}

// ============================================================================
// The JVM build compiles the SAME production sources as the Android app module
// (android/app/src/main/java/com/ghostify/download/):
//
//  * `main`    -> the pure, Android-free core (DownloadQueueRunner, DownloadManager,
//                 state machine, progress, executor, recovery, selector, models,
//                 track-downloader interface).
//  * `androidCheck` -> ALL sources including the Android-only files (WorkManager
//                 worker, scheduler, app provider, Room data layer), compiled
//                 against the android.jar stub + androidx libs. This proves the
//                 full component compiles even though the AGP build cannot link
//                 resources on this aarch64 host (aapt2 is x86-64 only).
//
// Tests (T-042..T-055, except device-only T-050) live in tests/unit.
// ============================================================================

sourceSets {
    main {
        kotlin {
            srcDirs("../../android/app/src/main/java")
            exclude("**/DownloadWorker.kt")
            exclude("**/DownloadScheduler.kt")
            exclude("**/DownloadProvider.kt")
            exclude("**/data/**")
        }
    }
    test {
        kotlin {
            srcDirs("tests/unit")
        }
    }
    create("androidCheck") {
        kotlin {
            srcDirs("../../android/app/src/main/java")
        }
    }
}

// Resolve the androidx AARs (as files) and unpack each one's classes.jar into a
// flat build dir that androidCheck can put on its compile classpath.
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
        }
        logger.lifecycle("androidCheck: unpacked ${aarFiles.count()} AAR classes.jar files")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.24")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // Android-only deps for the androidCheck source set.
    androidAars("androidx.work:work-runtime:2.9.0")
    androidAars("androidx.work:work-runtime-ktx:2.9.0")
    androidAars("androidx.room:room-runtime:2.6.1")
    androidAars("androidx.room:room-ktx:2.6.1")
    androidAars("androidx.core:core:1.13.1")
    androidAars("androidx.core:core-ktx:1.13.1")
    androidAars("androidx.annotation:annotation:1.7.1")

    // Room annotations (Entity, Dao, Query, ...) ship in room-common, a plain jar.
    "androidCheckCompileOnly"("androidx.room:room-common:2.6.1")

    "androidCheckCompileOnly"(fileTree(androidClassesDir) { include("*.jar") })
    "androidCheckCompileOnly"(files(androidJar))
    "androidCheckCompileOnly"(kotlin("stdlib"))
    "androidCheckCompileOnly"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // Verifying that the Android-only files compile is part of this build.
    dependsOn("compileAndroidCheckKotlin")
}

tasks.named("compileAndroidCheckKotlin") {
    dependsOn(extractAndroidAarClasses)
    doFirst {
        logger.lifecycle("androidCheck: compiling ALL download sources against android.jar + androidx")
    }
}

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

plugins {
    kotlin("jvm") version "2.0.21"
}

group = "com.ghostify"
version = "1.0"

repositories {
    mavenCentral()
    google()
}

// ============================================================================
// The JVM build compiles the SAME production sources as the Android app module
// (android/app/src/main/java/com/ghostify/player/):
//
//  * `main`           -> the pure, Android-free core (com.ghostify.player.core):
//                        queue builder, state mapper, error classifier, enums.
//                        These are the unit tests (T-079/080/083/084/086/087/088
//                        logic that is JVM-testable).
//  * `androidCheck`   -> ALL main sources including the Android-only glue
//                        (PlayerController, MediaItemMapper, MediaSessionHolder,
//                        ArtworkExtractor), compiled against android.jar + the
//                        real Media3 androidx classes. Proves the full component
//                        compiles even though the AGP build cannot link resources
//                        on this aarch64 host (aapt2 is x86-64 only).
//  * `androidCheckTest`-> the instrumentation test sources (T-079..T-092) compiled
//                        against android.jar + Media3 + androidx.test + JUnit,
//                        proving the device tests are compile-clean and ready.
// ============================================================================

sourceSets {
    main {
        kotlin {
            srcDir("../android/app/src/main/java/com/ghostify/player/core")
        }
    }
    create("androidCheck") {
        kotlin {
            srcDir("../android/app/src/main/java")
        }
    }
    create("androidCheckTest") {
        kotlin {
            srcDir("../android/app/src/main/java")
            srcDir("../android/app/src/androidTest/java")
        }
    }
}

val androidJar: String = providers.gradleProperty("androidJar")
    .orElse(System.getenv("ANDROID_HOME")?.let { "$it/platforms/android-34/android.jar" })
    .orElse("/opt/android-sdk/platforms/android-34/android.jar")
    .get()

val androidDeps: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = true
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.0.21")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Android-only deps for the androidCheck / androidCheckTest source sets.
    // Resolved as raw files (AARs + jars) and unpacked/copied below so a pure
    // JVM project can put the real androidx classes on the compile classpath.
    androidDeps("androidx.media3:media3-exoplayer:1.5.1")
    androidDeps("androidx.media3:media3-session:1.5.1")
    androidDeps("androidx.media3:media3-common:1.5.1")
    androidDeps("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    androidDeps("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    androidDeps("androidx.test:core:1.6.1")
    androidDeps("androidx.test.ext:junit:1.2.1")
    androidDeps("junit:junit:4.13.2")
}

val androidClassesDir = layout.buildDirectory.dir("androidCheck/classes")

val prepareAndroidClasses by tasks.registering {
    val depFiles = androidDeps.incoming.artifactView { lenient(true) }.files
    inputs.files(depFiles)
    outputs.dir(androidClassesDir)
    doLast {
        val out = androidClassesDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        depFiles.forEach { f ->
            if (f.name.endsWith(".aar")) {
                val zip = ZipFile(f)
                try {
                    val entry = zip.getEntry("classes.jar") ?: return@forEach
                    val name = f.name.removeSuffix(".aar") + ".jar"
                    zip.getInputStream(entry).use { input ->
                        Files.copy(input, out.toPath().resolve(name), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    zip.close()
                }
            } else {
                Files.copy(f.toPath(), out.toPath().resolve(f.name), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        logger.lifecycle("androidCheck: prepared ${depFiles.count()} dependency files in $out")
    }
}

// Both Android source sets share the same compile classpath: the unpacked
// androidx/media3 classes + android.jar + the Kotlin stdlib.
dependencies {
    "androidCheckCompileOnly"(fileTree(androidClassesDir) { include("*.jar") })
    "androidCheckCompileOnly"(files(androidJar))
    "androidCheckCompileOnly"("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")

    "androidCheckTestCompileOnly"(fileTree(androidClassesDir) { include("*.jar") })
    "androidCheckTestCompileOnly"(files(androidJar))
    "androidCheckTestCompileOnly"("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}

tasks.named("compileAndroidCheckKotlin") {
    dependsOn(prepareAndroidClasses)
}
tasks.named("compileAndroidCheckTestKotlin") {
    dependsOn(prepareAndroidClasses)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    // Verifying that the Android-only files and the instrumentation tests compile
    // is part of this build.
    dependsOn("compileAndroidCheckKotlin", "compileAndroidCheckTestKotlin")
}

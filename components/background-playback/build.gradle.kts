import org.gradle.api.tasks.testing.Test
import java.util.zip.ZipFile

plugins { kotlin("jvm") version "1.9.24" }

repositories { mavenCentral(); google() }

val androidJar: String = providers.gradleProperty("androidJar")
    .getOrElse(System.getenv("ANDROID_HOME")?.let { "$it/platforms/android-34/android.jar" }
        ?: "/opt/android-sdk/platforms/android-34/android.jar")

val androidAars: Configuration by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = true
}

sourceSets {
    main { kotlin { srcDir("android/app/src/main/java/com/ghostify/background/core") } }
    create("androidCheck") {
        kotlin { srcDir("android/app/src/main/java"); srcDir("stubs") }
    }
    create("androidCheckTest") {
        kotlin { srcDir("android/app/src/main/java"); srcDir("stubs"); srcDir("tests/androidInstrumented") }
    }
    create("robolectricTest") { kotlin { srcDir("tests/robolectric") } }
    test { kotlin { srcDir("tests/unit") } }
}

val androidClassesDir = layout.buildDirectory.dir("androidCheck/classes")

val extractAndroidAarClasses by tasks.registering {
    val aarFiles = androidAars.incoming.artifactView { lenient(true) }.files
    inputs.files(aarFiles)
    outputs.dir(androidClassesDir)
    doLast {
        val out = androidClassesDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        aarFiles.forEach { f ->
            if (f.name.endsWith(".aar")) {
                val zf = ZipFile(f)
                try {
                    zf.getEntry("classes.jar")?.let { entry ->
                        zf.getInputStream(entry).use { inp ->
                            val target = out.resolve(f.name.removeSuffix(".aar") + ".jar")
                            inp.copyTo(target.outputStream())
                        }
                    }
                } finally { zf.close() }
            } else {
                f.copyTo(out.resolve(f.name))
            }
        }
        logger.lifecycle("androidCheck: unpacked ${aarFiles.count()} files into $out")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    androidAars("androidx.media3:media3-common:1.5.1")
    androidAars("androidx.media3:media3-exoplayer:1.5.1")
    androidAars("androidx.media3:media3-session:1.5.1")
    androidAars("com.google.guava:guava:33.2.1-android")
    androidAars("androidx.test:core:1.6.1")
    androidAars("androidx.test.ext:junit:1.2.1")
    androidAars("junit:junit:4.13.2")

    "androidCheckCompileOnly"(fileTree(androidClassesDir) { include("*.jar") })
    "androidCheckCompileOnly"(files(androidJar))
    "androidCheckCompileOnly"("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.24")

    "robolectricTestImplementation"(fileTree(androidClassesDir) { include("*.jar") })
    "robolectricTestImplementation"(files(androidJar))
    "robolectricTestImplementation"("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    "robolectricTestImplementation"("org.jetbrains.kotlin:kotlin-test:1.9.24")
    "robolectricTestImplementation"("org.robolectric:robolectric:4.13")
    "robolectricTestImplementation"("junit:junit:4.13.2")
    "robolectricTestImplementation"("org.junit.vintage:junit-vintage-engine:5.10.2")
    "robolectricTestImplementation"(files(sourceSets.getByName("androidCheck").output))
    "robolectricTestCompileOnly"("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")

    "androidCheckTestCompileOnly"(fileTree(androidClassesDir) { include("*.jar") })
    "androidCheckTestCompileOnly"(files(androidJar))
    "androidCheckTestCompileOnly"("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}

tasks.named("compileAndroidCheckKotlin") { dependsOn(extractAndroidAarClasses) }
tasks.named("compileAndroidCheckTestKotlin") { dependsOn(extractAndroidAarClasses) }
tasks.named("compileRobolectricTestKotlin") { dependsOn(extractAndroidAarClasses) }

// `test` runs the pure-JVM tests and verifies every Android/Robolectric/
// instrumentation source compiles (via the dependsOn below).
tasks.test {
    useJUnitPlatform()
    testLogging { events("skipped", "failed") }
    dependsOn("compileAndroidCheckKotlin", "compileAndroidCheckTestKotlin", "compileRobolectricTestKotlin")
}

// Robolectric: runs on a host that has the linux-x86_64 native runtime (CI).
// Not executed by `gradle test`; compile-checked via compileRobolectricTestKotlin above.
tasks.register("robolectricTest", Test::class) {
    useJUnitPlatform()
    val rs = sourceSets.getByName("robolectricTest")
    classpath = rs.runtimeClasspath
    testClassesDirs = rs.output
    testLogging { events("skipped", "failed") }
}

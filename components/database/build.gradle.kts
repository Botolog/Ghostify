plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("com.google.devtools.ksp") version "1.9.24-1.0.20"
}

android {
    namespace = "com.ghostify.data"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        // schemas/ is the KSP schema export dir. `test.resources` makes the exported
        // v2 schema loadable by the JVM (sqlite-jdbc) migration test; `androidTest.assets`
        // is where MigrationTestHelper looks for it on a device.
        getByName("test").resources.srcDir("$projectDir/schemas")
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // NOTE: aapt2 for aarch64 Linux is unavailable in this environment, so
            // Android resource merging is disabled. These tests exercise Room against
            // real in-memory SQLite and need no app resources.
            isIncludeAndroidResources = false
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

tasks.withType<Test>().configureEach {
    // Robolectric bundles no Linux/aarch64 native runtime (verified up to
    // nativeruntime-dist-compat 1.0.19), so the Robolectric suite can only run on
    // x86-64 hosts. The pure-JVM MigrationSqlJvmTest runs on every host.
    val arch = System.getProperty("os.arch").lowercase()
    val isArmHost = arch.contains("aarch64") || arch.contains("arm")
    if (isArmHost) {
        exclude(
            "**/PlaylistCrudTest*", "**/SongConstraintTest*", "**/SongQueryTest*",
            "**/RepositoryFlowTest*", "**/AtomicityTest*", "**/BulkInsertPerfTest*",
            "**/SettingsRepositoryTest*"
        )
    }
}

dependencies {
    api("androidx.room:room-runtime:2.6.1")
    api("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.google.truth:truth:1.4.4")

    // Pure-JVM verification with real SQLite (ships linux/aarch64 natives, so it
    // runs on this host where Robolectric's native runtime is unavailable).
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

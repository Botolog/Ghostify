// ============================================================================
// Ghostify — app-module build.gradle.kts (release-relevant portion)
//
// Reference for wiring this component into the merged project
// (ghostify/android/app/build.gradle.kts). Other components' Gradle needs
// (Room, Media3, WorkManager) are expected in the same file; only the parts
// this component owns are shown here.
// ============================================================================

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")          // Kotlin 2.x Compose compiler
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")   // 2.57.x (AGP-8 compatible)
    id("com.chaquo.python")                            // Chaquopy 17.0.0
}

android {
    namespace = "com.ghostify"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ghostify"
        minSdk = 26
        targetSdk = 36

        // Release readiness: versionCode must increase monotonically on every
        // release — the T-181 upgrade test installs a vN APK then `adb install
        // -r` a vN+1 APK and asserts the library survives.
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Chaquopy requires explicit ABIs. armeabi-v7a needs Python <= 3.11
        // (3.12+ is 64-bit only), which is exactly why chaquopy.version = "3.11"
        // below. spotdl needs Python >= 3.10, so 3.11 is the overlap.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // T-180: R8 must not break Chaquopy/spotdl
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-chaquopy.pro",
            )
            // signingConfig = ...  (release signing is out of scope here)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true                  // T-180 needs BuildConfig.DEBUG
    }

    testOptions {
        unitTests {
            // Lets JVM unit tests parse manifest/resources under res/ if needed.
            isIncludeAndroidResources = true
        }
    }
}

// Chaquopy: Python 3.11 (supports all three ABIs incl. armeabi-v7a) with the
// spotdl runtime. `buildPython` must point at a Python 3.11 on the build/CI
// machine (e.g. `python3.11`). Version pins are owned by the python-runtime
// component; shown here for completeness.
chaquopy {
    defaultConfig {
        version = "3.11"
        // buildPython = "python3.11"
        pip {
            // install("spotdl==...")   — pinned by python-runtime component
        }
        pyc {
            src = true                    // ship .pyc; faster cold start, hides source
        }
    }
}

dependencies {
    // --- Compose (BOM pins versions) ---
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

    // --- Hilt / DI (2.57.x: AGP-8 compatible; 2.59+ requires AGP 9) ---
    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-compiler:2.57.2")

    // --- Room / WorkManager / Media3 (owned by their components) ---
    // implementation("androidx.room:room-runtime:2.8.4") + ksp(room-compiler)
    // implementation("androidx.work:work-runtime-ktx:2.11.1")
    // implementation("androidx.media3:media3-exoplayer:1.10.1")
    // implementation("androidx.media3:media3-session:1.10.1")

    // Chaquopy runtime is added automatically by the plugin.

    // --- Unit tests (run in plain CI, no device) ---
    testImplementation("junit:junit:4.13.2")

    // --- Instrumented tests (device/emulator) ---
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.04.01"))
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Hosts the ComponentActivity used by the Compose render tests.
    androidTestImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.core:core-ktx:1.19.0")
    androidTestImplementation("androidx.activity:activity-compose:1.13.0")
}

// =============================================================================
// app/build.gradle.kts — Chaquopy runtime + bundled ffmpeg (PythonRuntime)
//
// Prerequisites:
//   * root build.gradle.kts declares:  id("com.chaquo.python") version "17.0.0" apply false
//   * settings.gradle pluginManagement.repositories includes mavenCentral()
//   * build machine has python3.11 on PATH (buildPython), matching `version`
//   * AGP between 7.3.x and 9.2.x (see Chaquopy 17 compatibility table)
// =============================================================================

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.ghostify"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ghostify"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Python interpreter ships as native libraries, so every ABI we
        // claim must have a matching Chaquopy build AND a bundled ffmpeg
        // binary (jniLibs/<abi>/libffmpeg.so). T-022 runs on all three.
        // Note: Chaquopy 17 only supports armeabi-v7a (32-bit) on Python
        // 3.11 and older, hence `version = "3.11"` below.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    packaging {
        jniLibs {
            // The bundled ffmpeg is an EXECUTABLE, not a shared library, so it
            // needs special handling:
            //  * name it "libffmpeg.so" — files under lib/<abi>/ that do not
            //    match lib*.so are dropped by the package manager at install
            //    time, and exec() needs a real on-disk file.
            //  * useLegacyPackaging = true — keeps the legacy behavior of
            //    extracting lib/<abi>/ to applicationInfo.nativeLibraryDir at
            //    install. The modern default (extractNativeLibs=false) serves
            //    .so files mmap'd straight from the APK, which cannot be
            //    fork/exec'd. (Equiv. to android:extractNativeLibs="true".)
            //  * keepDebugSymbols — prevents AGP from trying to strip a static
            //    executable as if it were a dynamic .so.
            useLegacyPackaging = true
            keepDebugSymbols += "libffmpeg.so"
        }
    }

    // Chaquopy ships consumer proguard rules; keep minify on for release.
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

chaquopy {
    defaultConfig {
        // 3.11 = the last Python minor that still supports armeabi-v7a
        // (32-bit); 3.12+ is 64-bit-only. The build machine's buildPython
        // must be 3.11.x as well.
        version = "3.11"

        pip {
            // spotdl is pure Python; the Chaquopy pip resolver must pull its
            // dependency tree. Validate the exact pin in the Phase-0 spike
            // (PROJECT.md §9) and tighten the version there.
            install("spotdl")
            // install("spotdl==4.2.11")
        }
    }
}

dependencies {
    // Chaquopy's runtime is added automatically by the plugin (no manual line).

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // --- unit tests (JVM) ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // same API as the Android framework org.json

    // --- instrumentation tests (device, T-022..T-029) ---
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

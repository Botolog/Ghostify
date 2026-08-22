plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.chaquo.python")
}

android {
    namespace = "xyz.botolog.ghostify"
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.botolog.ghostify"
        minSdk = 26
        targetSdk = 35
        versionCode = 33
        versionName = "0.1.32"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-chaquopy.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            // Extract native libs (libpython.so, libffmpeg.so) so the ffmpeg
            // binary can be exec()'d by Python subprocesses (FfmpegLocator).
            useLegacyPackaging = true
            keepDebugSymbols += "libffmpeg.so"
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            // Chaquopy ships no rapidfuzz wheel (C++ extension); install our
            // pure-Python difflib-based fallback first so spotdl's dependency
            // `rapidfuzz>=3.0.0` resolves (see python-rapidfuzz/).
            install("./python-rapidfuzz")
            // spotdl 4.5.2's anonymous client chain (SpotipyFree -> spotapi)
            // depends on curl_cffi (compiled TLS lib) which has no Android
            // wheel. Install a pure-Python requests-based shim that satisfies
            // the `from curl_cffi.requests import ...` imports (see
            // python-curl-cffi/).
            install("./python-curl-cffi")
            // spotapi (the private-API wrapper) is vendored with pymongo,
            // redis and readerwriterlock savers stripped out and curl_cffi
            // replaced by the shim above (see python-spotapi/).
            install("./python-spotapi")
            // spotipyfree (Spotipy-compatible anonymous client) is vendored
            // because the PyPI copy hard-requires pymongo, which has no
            // Android wheel (see python-spotipyfree/).
            install("./python-spotipyfree")
            // ghostify_dl targets the spotdl 4.x API (spotdl.types.playlist).
            // Chaquopy's resolver would pick 3.9.6 (no spotdl/types/) and the
            // PyPI 4.x line requires pydantic-core (Rust) which has no Android
            // wheel — so install our vendored 4.5.2 copy with the web-UI deps
            // (fastapi/uvicorn/pydantic) stripped out (see python-spotdl/).
            install("./python-spotdl")
            install("yt-dlp")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

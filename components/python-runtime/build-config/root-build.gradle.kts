// =============================================================================
// Top-level build.gradle.kts — add Chaquopy to the build classpath.
// =============================================================================

plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("com.chaquo.python") version "17.0.0" apply false
}

// settings.gradle pluginManagement.repositories must include:
//     google(); mavenCentral(); gradlePluginPortal()

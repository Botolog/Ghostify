package com.ghostify.file

import java.io.File

/**
 * Provides the store's root directory without coupling the core to Android.
 * On the device the root is the app-scoped external dir
 * (`context.getExternalFilesDir(null)`), which needs no storage permission;
 * in JVM tests it is a temp directory.
 */
fun interface AppStorageDir {

    fun dir(): File

    companion object {
        fun fixed(dir: File): AppStorageDir = AppStorageDir { dir }
    }
}

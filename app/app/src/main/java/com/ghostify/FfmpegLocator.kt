package com.ghostify.python

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Locates the bundled static ffmpeg binary and makes it exec()-able by Python
 * subprocesses (TEST_PLAN T-025).
 *
 * Packaging (see `build-config/app-build.gradle.kts`):
 * - the binary ships in `src/main/jniLibs/<abi>/libffmpeg.so`. It must be
 *   named `lib*.so` or the package manager will not install it, and it must be
 *   a *static* build (no shared-library dependencies) so it runs standalone.
 * - `useLegacyPackaging = true` makes the package manager extract `lib/<abi>/`
 *   to `applicationInfo.nativeLibraryDir` at install time (modern Android
 *   otherwise serves `.so` files mmap'd from the APK, which `exec()` cannot
 *   use).
 * - `keepDebugSymbols += "libffmpeg.so"` stops AGP from trying to strip a
 *   static executable as if it were a dynamic `.so`.
 *
 * Runtime:
 * - **The binary is exec'd in place** at
 *   `<nativeLibraryDir>/libffmpeg.so`. Since Android 10 (targetSdk >= 29),
 *   SELinux forbids `exec()` of files in the app's own data directory
 *   (`app_data_file` only allows `dlopen`); the extracted APK native-library
 *   directory is the sole location the app may exec (its files carry the
 *   `apk_data_file` context, for which `untrusted_app` holds `execute` and
 *   `execute_no_trans`). Copying the binary to `filesDir/ffmpeg` would land in
 *   `app_data_file` and every `exec()` would fail with EACCES, so we never do
 *   that here.
 * - the directory is prepended to `os.environ["PATH"]` in the interpreter (via
 *   `ghostify_dl.prepend_path`) and, more importantly, the absolute in-place
 *   path is handed to spotdl as the `ffmpeg` setting so its subprocess execs
 *   the file directly.
 */
internal object FfmpegLocator {

    private const val TAG = "FfmpegLocator"
    private const val BUNDLED_NAME = "libffmpeg.so"

    @Volatile
    private var cached: File? = null

    /** Returns the bundled executable, run in place from the APK lib dir. */
    fun ensureExecutable(context: Context): File {
        cached?.let { return it }
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val bundled = File(nativeDir, BUNDLED_NAME)
        require(bundled.isFile) {
            "Bundled ffmpeg missing at ${bundled.path}; expected " +
                "src/main/jniLibs/<abi>/$BUNDLED_NAME with matching ndk.abiFilters"
        }
        if (!bundled.canExecute()) {
            // apk_data_file artifacts are normally executable; be defensive.
            bundled.setExecutable(true, false)
        }
        cached = bundled
        Log.i(TAG, "Running bundled ffmpeg in place: ${bundled.path}")
        return bundled
    }

    /** Absolute path to the executable (set by [ensureExecutable]); null before boot. */
    val executablePath: String?
        get() = cached?.absolutePath
}

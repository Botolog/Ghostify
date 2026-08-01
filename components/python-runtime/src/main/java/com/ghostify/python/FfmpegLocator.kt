package com.ghostify.python

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Locates the bundled static ffmpeg binary and makes it executable and usable
 * by Python subprocesses (TEST_PLAN T-025).
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
 * - the extracted `libffmpeg.so` is copied once to `filesDir/ffmpeg/ffmpeg`
 *   and marked executable (exec bit is not guaranteed on every device), and
 * - that directory is prepended to `os.environ["PATH"]` in the interpreter
 *   (via `ghostify_dl.prepend_path`) so spotdl's `subprocess`/`shutil.which`
 *   resolves `ffmpeg` by name.
 */
internal object FfmpegLocator {

    private const val TAG = "FfmpegLocator"
    private const val BUNDLED_NAME = "libffmpeg.so"
    private const val EXEC_NAME = "ffmpeg"

    /** Returns the directory whose `ffmpeg` entry can be put on PATH. */
    fun ensureExecutable(context: Context): File {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val bundled = File(nativeDir, BUNDLED_NAME)
        require(bundled.isFile) {
            "Bundled ffmpeg missing at ${bundled.path}; expected " +
                "src/main/jniLibs/<abi>/$BUNDLED_NAME with matching ndk.abiFilters"
        }

        val binDir = File(context.filesDir, "ffmpeg")
        val executable = File(binDir, EXEC_NAME)
        if (!executable.isFile) {
            binDir.mkdirs()
            check(binDir.isDirectory) { "Cannot create ffmpeg dir ${binDir.path}" }
            bundled.copyTo(executable, overwrite = true)
            check(executable.setExecutable(true, false)) { "Cannot make ${executable.path} executable" }
            Log.i(TAG, "Installed ffmpeg: ${bundled.path} -> ${executable.path}")
        }
        if (!executable.canExecute()) {
            check(executable.setExecutable(true, false)) { "Cannot make ${executable.path} executable" }
        }
        return binDir
    }
}

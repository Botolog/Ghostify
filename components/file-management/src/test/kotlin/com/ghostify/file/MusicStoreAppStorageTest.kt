package com.ghostify.file

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-151 — App-scoped dir used; no storage permission needed.
 *
 * JVM-verifiable half: the core never hardcodes an external/public path, never
 * requests a permission, and operates entirely within the injected root. The
 * device-only half (that the root really is `getExternalFilesDir`) lives in
 * `androidTest` / `android/MusicStoreAndroid.kt`.
 */
class MusicStoreAppStorageTest {

    private val fs = JvmFileSystem

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `store operates entirely within the injected root`() {
        val store = MusicStore(root = tempDir, fs = fs)

        val file = store.resolveOutputPath("Artist", "Title")
        assertTrue(fs.writeBytes(file, ByteArray(64)))

        // Every resolved path is rooted in the store dir — nothing escapes it.
        assertEquals(tempDir.absolutePath, file.parentFile!!.absolutePath)
        assertTrue(fs.listFiles(tempDir).isNotEmpty())
    }

    @Test
    fun `hostile names cannot escape the store root`() {
        val store = MusicStore(root = tempDir, fs = fs)

        for (title in listOf("../escape", "..", "/etc/passwd", "a/../../b", "\\..\\win")) {
            val path = store.resolveOutputPath("Artist", title)
            assertTrue(
                path.absolutePath.startsWith(tempDir.absolutePath + File.separator),
                "must stay inside root: $path",
            )
            assertTrue(fs.writeBytes(path, ByteArray(8)), "sanitized name must be writable: $path")
        }
    }

    @Test
    fun `core depends on an injectable root, not a global path`() {
        // AppStorageDir is the indirection point: a temp dir on JVM,
        // getExternalFilesDir on device.
        val dir = AppStorageDir.fixed(tempDir)
        assertEquals(tempDir, dir.dir())
        assertTrue(tempDir.isDirectory)
    }

    @Test
    fun `ensureRoot creates a missing store directory`() {
        val nested = File(tempDir, "songs/sub")
        val store = MusicStore(root = nested, fs = fs)
        assertFalse(fs.exists(nested))
        assertTrue(store.ensureRoot())
        assertTrue(fs.exists(nested))
    }

    @Test
    fun `android adapter uses app-scoped storage and never a permission`() {
        // Contract check on the device-side adapter: it must bind the store to
        // getExternalFilesDir (no READ/WRITE_EXTERNAL_STORAGE). The adapter
        // lives outside the JVM module so it cannot be compiled here.
        val adapter = File("android/MusicStoreAndroid.kt")
        assertTrue(adapter.isFile, "adapter source must exist: ${adapter.absolutePath}")
        val source = adapter.readText()
        assertTrue(source.contains("getExternalFilesDir"))
        assertFalse(source.contains("READ_EXTERNAL_STORAGE"))
        assertFalse(source.contains("WRITE_EXTERNAL_STORAGE"))
        assertFalse(source.contains("MANAGE_EXTERNAL_STORAGE"))
    }
}

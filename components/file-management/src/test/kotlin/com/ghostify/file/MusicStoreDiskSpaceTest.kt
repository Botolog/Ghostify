package com.ghostify.file

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-156 — Disk space calculation accurate for UI display (JVM-verifiable part).
 *
 * `storeUsedBytes()` (the "clear-cache frees this" figure) is exactly the sum
 * of the store's own files and is verified byte-for-byte here. Volume-level
 * free/total space come from the real filesystem backing the root, so the
 * numbers match what the OS reports for that volume. Full on-device
 * verification against `StatFs` lives in `androidTest`.
 */
class MusicStoreDiskSpaceTest {

    private val fs = JvmFileSystem

    @TempDir
    lateinit var tempDir: File

    private fun store(): MusicStore = MusicStore(root = tempDir, fs = fs)

    private fun write(name: String, size: Int): File {
        val file = File(tempDir, name)
        assertTrue(fs.writeBytes(file, ByteArray(size)))
        return file
    }

    @Test
    fun `empty store reports zero used bytes`() {
        assertEquals(0L, store().storeUsedBytes())
    }

    @Test
    fun `used bytes equals the exact sum of store files`() {
        val store = store()
        write("A - One.mp3", 100)
        write("B - Two.mp3", 2048)
        write("C - Three.mp3", 0)

        assertEquals(100L + 2048L + 0L, store.storeUsedBytes())
    }

    @Test
    fun `sidecars and leftover files count toward store size`() {
        val store = store()
        write("A - One.mp3", 100)
        write("A - One.mp3.spotdl", 40)

        assertEquals(140L, store.storeUsedBytes())
    }

    @Test
    fun `deleting a song frees its bytes from the store total`() {
        val store = store()
        write("A - One.mp3", 1000)
        write("A - One.mp3.spotdl", 100)

        store.deleteSongFile(File(tempDir, "A - One.mp3"))
        assertEquals(0L, store.storeUsedBytes())
    }

    @Test
    fun `report exposes real free and total space with a sane percentage`() {
        val store = store()
        write("A - One.mp3", 1000)

        val report = store.diskSpace()
        assertEquals(1000L, report.usedBytes)
        assertTrue(report.freeBytes > 0L, "temp dir sits on a real volume with free space")
        assertTrue(report.totalBytes > report.freeBytes, "total covers the volume")
        assertTrue(report.usedPercent in 0.0..100.0)
        assertTrue(report.usedPercent > 0.0)
    }

    @Test
    fun `report matches the raw filesystem figures`() {
        val store = store()
        assertEquals(fs.freeSpace(tempDir), store.freeBytes())
        assertEquals(fs.totalSpace(tempDir), store.totalBytes())
    }
}

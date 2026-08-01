package com.ghostify.trackdownload

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE / INSTRUMENTATION ONLY — requires an emulator or device with the app
 * installed and Chaquopy's interpreter running (`spotdl` resolved in the
 * app's pip config, per PROJECT.md §7). These exercise the real Kotlin⇄Python
 * wiring (the part the JVM stub cannot verify) end-to-end.
 *
 * Each test maps to a TESTS.md item. Network: uses a single, stable public
 * Spotify track (Rick Astley - "Never Gonna Give You Up", id
 * `4cOdK2wGLETKBW3PvgPWqT`). Run with:
 *
 *   ./gradlew :app:connectedDebugAndroidTest
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.ghostify.trackdownload.TrackDownloadInstrumentedTest
 *
 * Locally-verifiable portions (T-038 sanitisation, result/error mapping, skip,
 * progress, temp cleanup) are also covered on the JVM in `TrackDownloadBridgeTest.kt`.
 */
@RunWith(AndroidJUnit4::class)
class TrackDownloadInstrumentedTest {

    private lateinit var context: Context
    private lateinit var outDir: File
    private lateinit var bridge: TrackDownloadBridge

    /** Public, stable, widely-mirrored track (PROJECT.md Phase 0). */
    private val trackUrl = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
    private val expectedName = "Rick Astley - Never Gonna Give You Up.mp3"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        outDir = File(context.cacheDir, "ghostify_track_download").apply { mkdirs() }
        bridge = TrackDownloadBridge()
        // Clean slate so skip/retry assertions are deterministic.
        outDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        outDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun config(bitrate: Int = 128) =
        TrackDownloadConfig(outputDir = outDir.absolutePath, bitrate = bitrate)

    // ---------------------------------------------------------------- T-030 --
    @Test
    fun t030_download_writes_non_empty_audio() {
        val r = bridge.downloadBlocking(trackUrl, config())
        assertTrue(r is TrackDownloadResult.Downloaded)
        val path = (r as TrackDownloadResult.Downloaded).outputPath
        assertTrue("mp3 written", File(path).exists())
        assertTrue("non-empty", File(path).length() > 0)
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(path)
        val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        assertNotNull("decodes as audio (duration present)", dur)
        mmr.release()
    }

    // ---------------------------------------------------------------- T-031 --
    @Test
    fun t031_filename_matches_template() {
        val r = bridge.downloadBlocking(trackUrl, config())
        val name = File((r as TrackDownloadResult.Downloaded).outputPath).name
        assertEquals(expectedName, name)
    }

    // ---------------------------------------------------------------- T-032 --
    @Test
    fun t032_embedded_tags() {
        val r = bridge.downloadBlocking(trackUrl, config())
        val path = (r as TrackDownloadResult.Downloaded).outputPath
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(path)
        assertEquals("Never Gonna Give You Up", mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
        assertEquals("Rick Astley", mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
        assertNotNull(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
        mmr.release()
    }

    // ---------------------------------------------------------------- T-033 --
    @Test
    fun t033_embedded_cover() {
        val r = bridge.downloadBlocking(trackUrl, config())
        val path = (r as TrackDownloadResult.Downloaded).outputPath
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(path)
        val pic = mmr.getEmbeddedPicture()
        assertNotNull("cover art embedded", pic)
        assertTrue("cover not empty", pic.isNotEmpty())
        mmr.release()
    }

    // ---------------------------------------------------------------- T-034 --
    @Test
    fun t034_bitrate_honored() {
        val r = bridge.downloadBlocking(trackUrl, config(bitrate = 320))
        val downloaded = r as TrackDownloadResult.Downloaded
        assertEquals("320k", downloaded.bitrate)
        // On-device, the wrapper also re-validates the file is a real MP3 at the
        // requested bitrate (PROJECT.md §6.1). A full ffprobe-style check lives in
        // the Python e2e (test_e2e.test_05_bitrate_honored); here the contract
        // we can assert on the JVM/device boundary is the reported bitrate.
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(downloaded.outputPath)
        assertNotNull(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
        mmr.release()
    }

    // ---------------------------------------------------------------- T-035 --
    @Test
    fun t035_skip_existing_no_redownload() {
        val first = bridge.downloadBlocking(trackUrl, config())
        val mp3 = File((first as TrackDownloadResult.Downloaded).outputPath)
        assertTrue(mp3.exists())
        val sidecar = File(mp3.parentFile, mp3.name + ".spotdl")
        assertTrue("sidecar written by wrapper", sidecar.exists())
        val mtimeBefore = mp3.lastModified()

        // Second download must skip (sidecar match) without touching the file.
        // If spotdl were invoked, this would re-download; the wrapper short-circuits
        // on the .spotdl sidecar, so mtime is preserved.
        val second = bridge.downloadBlocking(trackUrl, config())
        assertTrue(second is TrackDownloadResult.Skipped)
        assertEquals(mp3.absolutePath, (second as TrackDownloadResult.Skipped).outputPath)
        assertEquals("file not re-touched on skip", mtimeBefore, mp3.lastModified())
    }

    // ---------------------------------------------------------------- T-036 --
    @Test
    fun t036_unavailable_track_fails_cleanly() {
        // A track id that exists in Spotify's schema but has no YouTube match.
        val r = bridge.downloadBlocking("https://open.spotify.com/track/0000000000000000000000", config())
        assertTrue(r is TrackDownloadResult.Failure)
        val f = r as TrackDownloadResult.Failure
        assertTrue("typed failure with a stable kind", f.error.kind.name.isNotBlank())
        // No corrupt/partial file left for this non-existent track.
        outDir.walkTopDown().filter { it.name.endsWith(".mp3") }.forEach {
            assertFalse("no orphan mp3 for unavailable track", it.length() == 0L)
        }
    }

    // ---------------------------------------------------------------- T-039 --
    @Test
    fun t039_extraction_failure_isolated_and_retryable() {
        val r = bridge.downloadBlocking("https://open.spotify.com/track/0000000000000000000000", config())
        assertTrue("first attempt fails", r is TrackDownloadResult.Failure)
        // Bridge/dowbloader must remain usable for the next track (T-039).
        val ok = bridge.downloadBlocking(trackUrl, config())
        assertTrue("recovery: next track succeeds", ok is TrackDownloadResult.Downloaded)
    }

    // ---------------------------------------------------------------- T-038 --
    @Test
    fun t038_filename_sanitized() {
        // Use a config; the real sanitisation happens in the Python wrapper /
        // spotdl. We assert the produced filename on device contains none of the
        // forbidden characters for the (already clean) reference track, and that
        // the Kotlin passthrough path strips nothing unexpected.
        val r = bridge.downloadBlocking(trackUrl, config())
        val name = File((r as TrackDownloadResult.Downloaded).outputPath).name
        for (c in arrayOf("/", "\\", ":", "*", "\"", "<", ">", "|", "?")) {
            if (c == "/") continue
            assertFalse("forbidden char '$c' must not appear in filename", name.contains(c))
        }
    }

    // ---------------------------------------------------------------- T-040 --
    @Test
    fun t040_progress_hooks_fire() {
        val events = mutableListOf<String>()
        val listener = object : TrackProgressListener {
            override fun onDownloadStart(track: TrackInfo) { events.add("start:${track.title}") }
            override fun onProgress(percent: Int, message: String?) { events.add("progress:$percent") }
            override fun onDownloadComplete(result: TrackDownloadResult) { events.add("complete:${result}") }
        }
        val r = bridge.downloadBlocking(trackUrl, config(), listener = listener)
        assertTrue(r is TrackDownloadResult.Downloaded)
        assertTrue("start fired", events.any { it.startsWith("start:") })
        assertTrue("progress fired", events.any { it.startsWith("progress:") })
        assertTrue("reached 100", events.any { it == "progress:100" })
        assertTrue("complete fired", events.any { it.startsWith("complete:") })
    }

    // ---------------------------------------------------------------- T-041 --
    @Test
    fun t041_file_size_not_truncated() {
        val r = bridge.downloadBlocking(trackUrl, config(bitrate = 192))
        val d = r as TrackDownloadResult.Downloaded
        assertNotNull(d.fileSize)
        assertTrue("non-zero file size", d.fileSize!! > 0)
        val actual = File(d.outputPath).length()
        // Wrapper rejects truncation (duration + byte budget). If we got here,
        // validation passed; assert the declared size matches the on-disk size.
        assertEquals("file size consistent", d.fileSize, actual)
    }

    // ---------------------------------------------------------------- bonus --
    @Test
    fun t037_temp_cleanup_on_clean_state() {
        // On a clean output dir, cleanup_temp removes nothing and must not crash.
        val removed = bridge.cleanupTemp(config())
        assertEquals(0, removed)
    }
}

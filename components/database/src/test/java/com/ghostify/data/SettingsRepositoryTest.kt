package com.ghostify.data

import com.ghostify.data.repo.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T-077: `settings` get/set round-trip for all keys, plus defaults for unset keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsRepositoryTest : BaseDbTest() {

    @Test
    fun defaultsWhenNothingSet() = runTest {
        val repo = SettingsRepository(db.settingDao())
        assertEquals(SettingsRepository.DEFAULT_STORAGE_DIR, repo.getStorageDir())
        assertEquals(SettingsRepository.DEFAULT_BITRATE, repo.getBitrate())
        assertEquals(SettingsRepository.DEFAULT_CONCURRENCY, repo.getConcurrency())
        assertFalse(repo.getAutoDownload())
    }

    @Test
    fun roundTripAllKeys() = runTest {
        val repo = SettingsRepository(db.settingDao())

        repo.setStorageDir("/storage/ghostify")
        repo.setBitrate("192")
        repo.setConcurrency(4)
        repo.setAutoDownload(true)

        assertEquals("/storage/ghostify", repo.getStorageDir())
        assertEquals("192", repo.getBitrate())
        assertEquals(4, repo.getConcurrency())
        assertTrue(repo.getAutoDownload())

        // Re-open the store on a fresh instance backed by the same table.
        val reopened = SettingsRepository(db.settingDao())
        assertEquals("/storage/ghostify", reopened.getStorageDir())
        assertEquals("192", reopened.getBitrate())
        assertEquals(4, reopened.getConcurrency())
        assertTrue(reopened.getAutoDownload())
    }

    @Test
    fun overwriteAndObserveFlows() = runTest {
        val repo = SettingsRepository(db.settingDao())

        repo.setConcurrency(1)
        assertEquals(1, repo.observeConcurrency().first())

        repo.setConcurrency(8)
        assertEquals(8, repo.observeConcurrency().first())

        repo.setAutoDownload(false)
        repo.setAutoDownload(true)
        assertTrue(repo.observeAutoDownload().first())

        repo.set("concurrent_downloads", "not-a-number")
        assertEquals("corrupt value falls back to default", SettingsRepository.DEFAULT_CONCURRENCY, repo.getConcurrency())
    }

    @Test
    fun genericGetSetAndObserveAll() = runTest {
        val repo = SettingsRepository(db.settingDao())
        repo.set("storage_dir", "/music")
        assertEquals("/music", repo.get("storage_dir", "fallback"))
        assertEquals("fallback", repo.get("missing_key", "fallback"))

        repo.set("storage_dir", "/music")
        repo.set("default_bitrate", "256")
        val all = repo.observeAll().first()
        assertEquals("/music", all["storage_dir"])
        assertEquals("256", all["default_bitrate"])
    }
}

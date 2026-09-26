package xyz.botolog.ghostify.data.repo

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.data.db.dao.SettingDao
import xyz.botolog.ghostify.data.db.entity.SettingEntity

class SettingsRepositoryTest {

    private lateinit var settingDao: SettingDao
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        settingDao = mockk()
        repository = SettingsRepository(settingDao)
    }

    @Test
    fun observeLoopPlaylists_defaultsToTrueWhenMissing() = runTest {
        every {
            settingDao.observeValue(SettingsRepository.KEY_LOOP_PLAYLISTS)
        } returns flowOf(null)

        assertTrue(repository.observeLoopPlaylists().first())
    }

    @Test
    fun observeLoopPlaylists_readsStoredValue() = runTest {
        every {
            settingDao.observeValue(SettingsRepository.KEY_LOOP_PLAYLISTS)
        } returns flowOf("false")

        assertFalse(repository.observeLoopPlaylists().first())
    }

    @Test
    fun setLoopPlaylists_persistsBooleanValue() = runTest {
        val setting = SettingEntity(SettingsRepository.KEY_LOOP_PLAYLISTS, "false")
        coEvery { settingDao.upsert(setting) } returns Unit

        repository.setLoopPlaylists(false)

        coVerify(exactly = 1) { settingDao.upsert(setting) }
    }

    @Test
    fun defaultFullPlayerLayoutIsNormal() {
        assertEquals("normal", SettingsRepository.DEFAULT_FULL_PLAYER_LAYOUT)
    }

    @Test
    fun observeFullPlayerLayout_defaultsToNormalWhenMissing() = runTest {
        every {
            settingDao.observeValue(SettingsRepository.KEY_FULL_PLAYER_LAYOUT)
        } returns flowOf(null)

        assertEquals("normal", repository.observeFullPlayerLayout().first())
    }

    @Test
    fun observeFullPlayerLayout_readsStoredValue() = runTest {
        every {
            settingDao.observeValue(SettingsRepository.KEY_FULL_PLAYER_LAYOUT)
        } returns flowOf("super_compact")

        assertEquals("super_compact", repository.observeFullPlayerLayout().first())
    }

    @Test
    fun observeFullPlayerLayout_readsLegacyBooleanValue() = runTest {
        every {
            settingDao.observeValue(SettingsRepository.KEY_FULL_PLAYER_LAYOUT)
        } returns flowOf("true")

        assertEquals("true", repository.observeFullPlayerLayout().first())
    }

    @Test
    fun getFullPlayerLayout_returnsDefaultWhenMissing() = runTest {
        coEvery { settingDao.getValue(SettingsRepository.KEY_FULL_PLAYER_LAYOUT) } returns null

        assertEquals("normal", repository.getFullPlayerLayout())
    }

    @Test
    fun getFullPlayerLayout_readsStoredValue() = runTest {
        coEvery { settingDao.getValue(SettingsRepository.KEY_FULL_PLAYER_LAYOUT) } returns "compact"

        assertEquals("compact", repository.getFullPlayerLayout())
    }

    @Test
    fun setFullPlayerLayout_roundTripsStoredValue() = runTest {
        val stored = mutableMapOf<String, String?>()
        coEvery { settingDao.upsert(any()) } answers {
            stored[firstArg<SettingEntity>().key] = firstArg<SettingEntity>().value
        }
        coEvery { settingDao.getValue(any()) } answers { stored[firstArg()] }

        repository.setFullPlayerLayout("super_compact")
        assertEquals("super_compact", repository.getFullPlayerLayout())

        repository.setFullPlayerLayout("normal")
        assertEquals("normal", repository.getFullPlayerLayout())
    }

    @Test
    fun setFullPlayerLayout_persistsUnderLegacyKey() = runTest {
        val stored = mutableMapOf<String, String?>()
        coEvery { settingDao.upsert(any()) } answers {
            stored[firstArg<SettingEntity>().key] = firstArg<SettingEntity>().value
        }

        repository.setFullPlayerLayout("compact")

        assertEquals("compact", stored[SettingsRepository.KEY_FULL_PLAYER_LAYOUT])
        assertEquals("full_player_compact", SettingsRepository.KEY_FULL_PLAYER_LAYOUT)
    }

    @Test
    fun defaultShowQueueCoversIsTrue() {
        assertTrue(SettingsRepository.DEFAULT_SHOW_QUEUE_COVERS)
    }

    @Test
    fun observeShowQueueCovers_defaultsToTrueWhenMissing() = runTest {
        every {
            settingDao.observeValue(SettingsRepository.KEY_SHOW_QUEUE_COVERS)
        } returns flowOf(null)

        assertTrue(repository.observeShowQueueCovers().first())
    }

    @Test
    fun observeShowQueueCovers_keepsExplicitlyStoredFalse() = runTest {
        every {
            settingDao.observeValue(SettingsRepository.KEY_SHOW_QUEUE_COVERS)
        } returns flowOf("false")

        assertFalse(repository.observeShowQueueCovers().first())
    }

    @Test
    fun observeShowQueueCovers_readsStoredTrue() = runTest {
        every {
            settingDao.observeValue(SettingsRepository.KEY_SHOW_QUEUE_COVERS)
        } returns flowOf("true")

        assertTrue(repository.observeShowQueueCovers().first())
    }

    @Test
    fun getShowQueueCovers_returnsTrueWhenMissing() = runTest {
        coEvery { settingDao.getValue(SettingsRepository.KEY_SHOW_QUEUE_COVERS) } returns null

        assertTrue(repository.getShowQueueCovers())
    }

    @Test
    fun getShowQueueCovers_keepsExplicitlyStoredFalse() = runTest {
        coEvery { settingDao.getValue(SettingsRepository.KEY_SHOW_QUEUE_COVERS) } returns "false"

        assertFalse(repository.getShowQueueCovers())
    }

    @Test
    fun setShowQueueCovers_persistsBooleanValue() = runTest {
        val setting = SettingEntity(SettingsRepository.KEY_SHOW_QUEUE_COVERS, "false")
        coEvery { settingDao.upsert(setting) } returns Unit

        repository.setShowQueueCovers(false)

        coVerify(exactly = 1) { settingDao.upsert(setting) }
    }
}

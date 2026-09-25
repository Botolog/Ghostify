package xyz.botolog.ghostify.data.repo

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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
}

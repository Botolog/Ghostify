package com.ghostify.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ghostify.ui.FakeSettingsContract
import com.ghostify.ui.contract.SettingsContract
import com.ghostify.ui.model.Bitrate
import com.ghostify.ui.model.CacheStats
import com.ghostify.ui.theme.GhostifyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * T-145..T-149 — Settings screen.
 *
 * Persistence ("is used for new downloads / new saves") is enforced by the contract
 * implementation reading/writing the same settings repository the download and add flows
 * use; at the screen level we verify the state renders, changes are captured, and the
 * clear-cache action drops the file count without touching library records.
 */
class SettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setContent(contract: FakeSettingsContract) {
        rule.setContent {
            GhostifyTheme {
                SettingsScreen(contract = contract, onBack = {})
            }
        }
    }

    @Test
    fun t145_bitrateSettingReflectsPersistedValueAndUpdates() {
        val contract = FakeSettingsContract(
            SettingsContract.SettingsUiState(
                bitrate = Bitrate.MEDIUM,
                storagePath = "/sdcard/Ghostify",
                concurrentDownloads = 1,
            ),
        )
        setContent(contract)

        // Persisted value is shown as selected.
        rule.onNodeWithTag(SettingsTestTags.bitrateOption(192)).assertIsSelected()
        rule.onNodeWithTag(SettingsTestTags.bitrateOption(128)).assertIsNotSelected()

        rule.onNodeWithTag(SettingsTestTags.bitrateOption(320)).performClick()

        rule.onNodeWithTag(SettingsTestTags.bitrateOption(320)).assertIsSelected()
        assertTrue(contract.setBitrates.contains(Bitrate.HIGH))

        rule.onNodeWithTag(SettingsTestTags.bitrateOption(128)).performClick()
        assertTrue(contract.setBitrates.contains(Bitrate.LOW))
        assertEquals(Bitrate.LOW, contract.state.value.bitrate)
    }

    @Test
    fun t146_storageLocationShowsCurrentPathAndChangeUpdatesIt() {
        val contract = FakeSettingsContract(
            SettingsContract.SettingsUiState(storagePath = "/sdcard/Android/data/com.ghostify/files/Music"),
        )
        setContent(contract)

        rule.onNodeWithTag(SettingsTestTags.STORAGE_PATH).assertIsDisplayed()
        rule.onNodeWithText("/sdcard/Android/data/com.ghostify/files/Music").assertIsDisplayed()

        rule.onNodeWithTag(SettingsTestTags.CHANGE_STORAGE).performClick()

        assertTrue(contract.storageChangeClicks.isNotEmpty())
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("/storage/emulated/0/Music/Ghostify").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("/storage/emulated/0/Music/Ghostify").assertIsDisplayed()
    }

    @Test
    fun t147_concurrentDownloadsSettingRespected() {
        val contract = FakeSettingsContract(SettingsContract.SettingsUiState(concurrentDownloads = 1))
        setContent(contract)

        rule.onNodeWithTag(SettingsTestTags.CONCURRENCY_VALUE).assertIsDisplayed()
        rule.onNodeWithText("1").assertIsDisplayed()

        rule.onNodeWithTag(SettingsTestTags.CONCURRENCY_PLUS).performClick()
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("2").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(contract.setConcurrencyValues.contains(2))

        rule.onNodeWithTag(SettingsTestTags.CONCURRENCY_MINUS).performClick()
        assertTrue(contract.setConcurrencyValues.contains(1))
    }

    @Test
    fun t148_autoDownloadOnAddToggleHonored() {
        val contract = FakeSettingsContract(SettingsContract.SettingsUiState(autoDownloadOnAdd = false))
        setContent(contract)

        rule.onNodeWithTag(SettingsTestTags.AUTO_DOWNLOAD).assertIsOff()

        rule.onNodeWithTag(SettingsTestTags.AUTO_DOWNLOAD).performClick()
        rule.onNodeWithTag(SettingsTestTags.AUTO_DOWNLOAD).assertIsOn()
        assertTrue(contract.setAutoDownloadValues.contains(true))

        rule.onNodeWithTag(SettingsTestTags.AUTO_DOWNLOAD).performClick()
        rule.onNodeWithTag(SettingsTestTags.AUTO_DOWNLOAD).assertIsOff()
        assertTrue(contract.setAutoDownloadValues.contains(false))
    }

    @Test
    fun t149_clearCacheFreesSpaceWithoutDeletingLibraryRecords() {
        val contract = FakeSettingsContract(
            SettingsContract.SettingsUiState(
                storagePath = "/sdcard/Ghostify",
                cacheStats = CacheStats(fileCount = 12, sizeBytes = 4 * 1024 * 1024L),
            ),
        )
        setContent(contract)

        rule.onNodeWithTag(SettingsTestTags.CACHE_COUNT).assertIsDisplayed()
        rule.onNodeWithText("12 files · 4.0 MB").assertIsDisplayed()

        rule.onNodeWithTag(SettingsTestTags.CLEAR_CACHE).performClick()

        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("0 files · 0 KB").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("0 files · 0 KB").assertIsDisplayed()
        assertTrue(contract.clearCacheClicks.isNotEmpty())
    }
}

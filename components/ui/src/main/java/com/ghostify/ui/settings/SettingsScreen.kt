package com.ghostify.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghostify.ui.contract.SettingsContract
import com.ghostify.ui.contract.SettingsContract.SettingsUiState
import com.ghostify.ui.model.Bitrate

object SettingsTestTags {
    const val BITRATE = "setting_bitrate"
    const val STORAGE_PATH = "setting_storage_path"
    const val CHANGE_STORAGE = "setting_change_storage"
    const val CONCURRENCY = "setting_concurrency"
    const val CONCURRENCY_VALUE = "setting_concurrency_value"
    const val CONCURRENCY_PLUS = "setting_concurrency_plus"
    const val CONCURRENCY_MINUS = "setting_concurrency_minus"
    const val AUTO_DOWNLOAD = "setting_auto_download"
    const val CACHE_COUNT = "setting_cache_count"
    const val CLEAR_CACHE = "setting_clear_cache"

    fun bitrateOption(kbps: Int) = "setting_bitrate_$kbps"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    contract: SettingsContract,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by contract.state.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionHeader("Downloads")

            BitrateSetting(state = state, contract = contract)
            Spacer(modifier = Modifier.height(16.dp))

            StorageSetting(state = state, contract = contract)
            Spacer(modifier = Modifier.height(16.dp))

            ConcurrencySetting(state = state, contract = contract)
            Spacer(modifier = Modifier.height(16.dp))

            AutoDownloadSetting(state = state, contract = contract)
            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader("Storage")
            CacheSetting(state = state, contract = contract)

            Spacer(modifier = Modifier.weight(1f))

            val context = LocalContext.current
            val versionName = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: Exception) { null }
            val versionCode = try {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            } catch (_: Exception) { 0L }
            Text(
                text = "Ghostify v${versionName ?: "?"} ($versionCode)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .testTag("setting_version"),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun BitrateSetting(state: SettingsUiState, contract: SettingsContract) {
    Text("Download bitrate", style = MaterialTheme.typography.bodyLarge)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(SettingsTestTags.BITRATE),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        Bitrate.entries.forEach { bitrate ->
            FilterChip(
                selected = state.bitrate == bitrate,
                onClick = { contract.setBitrate(bitrate) },
                label = { Text(bitrate.label) },
                modifier = Modifier.testTag(SettingsTestTags.bitrateOption(bitrate.kbps)),
            )
        }
    }
}

@Composable
private fun StorageSetting(state: SettingsUiState, contract: SettingsContract) {
    Text("Storage location", style = MaterialTheme.typography.bodyLarge)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.storagePath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .testTag(SettingsTestTags.STORAGE_PATH),
        )
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = contract::changeStoragePath,
            modifier = Modifier.testTag(SettingsTestTags.CHANGE_STORAGE),
        ) {
            Text("Change")
        }
    }
}

@Composable
private fun ConcurrencySetting(state: SettingsUiState, contract: SettingsContract) {
    Text("Concurrent downloads", style = MaterialTheme.typography.bodyLarge)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .testTag(SettingsTestTags.CONCURRENCY),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { contract.setConcurrency((state.concurrentDownloads - 1).coerceAtLeast(1)) },
            enabled = state.concurrentDownloads > 1,
            modifier = Modifier.testTag(SettingsTestTags.CONCURRENCY_MINUS),
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease")
        }
        Text(
            text = "${state.concurrentDownloads}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .width(40.dp)
                .testTag(SettingsTestTags.CONCURRENCY_VALUE),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(
            onClick = { contract.setConcurrency((state.concurrentDownloads + 1).coerceAtMost(8)) },
            enabled = state.concurrentDownloads < 8,
            modifier = Modifier.testTag(SettingsTestTags.CONCURRENCY_PLUS),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Increase")
        }
    }
}

@Composable
private fun AutoDownloadSetting(state: SettingsUiState, contract: SettingsContract) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Auto-download on add", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Start downloading new playlists immediately after saving.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = state.autoDownloadOnAdd,
            onCheckedChange = contract::setAutoDownload,
            modifier = Modifier.testTag(SettingsTestTags.AUTO_DOWNLOAD),
        )
    }
}

@Composable
private fun CacheSetting(state: SettingsUiState, contract: SettingsContract) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${state.cacheStats.fileCount} files · ${state.cacheStats.sizeText}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(SettingsTestTags.CACHE_COUNT),
            )
            Text(
                "Clears orphaned/cache files. Your library is kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = contract::clearCache,
            enabled = !state.isClearingCache && state.cacheStats.fileCount > 0,
            modifier = Modifier.testTag(SettingsTestTags.CLEAR_CACHE),
        ) {
            if (state.isClearingCache) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            } else {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear cache")
            }
        }
    }
}

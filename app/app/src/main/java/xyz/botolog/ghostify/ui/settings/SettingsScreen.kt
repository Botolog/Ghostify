package xyz.botolog.ghostify.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import xyz.botolog.ghostify.GhostifyApplication
import xyz.botolog.ghostify.ui.contract.SettingsContract
import xyz.botolog.ghostify.ui.contract.SettingsContract.SettingsUiState
import xyz.botolog.ghostify.ui.model.Bitrate
import xyz.botolog.ghostify.ui.viewmodel.SettingsViewModel
import xyz.botolog.ghostify.update.DownloadState

/**
 * Test tag constants for the settings screen. Used by Compose UI tests to locate elements.
 */
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
    const val CHECK_UPDATE = "setting_check_update"

    /**
     * Returns the test tag for a specific bitrate option.
     *
     * @param kbps the bitrate value in kilobits per second.
     */
    fun bitrateOption(kbps: Int) = "setting_bitrate_$kbps"
}

private val SECTION_SPACING = 16.dp
private val SECTION_SPACING_LARGE = 24.dp
private val INNER_SPACING = 8.dp
private val CONCURRENCY_TEXT_WIDTH = 40.dp

/**
 * Full settings screen with download, storage, and debug sections.
 *
 * @param contract ViewModel contract driving the settings state and actions.
 * @param onBack callback invoked when the back button is tapped.
 * @param modifier optional modifier applied to the screen root.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    contract: SettingsContract,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by contract.state.collectAsState()
    var showPermissionRationale by remember { mutableStateOf(false) }

    val storagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (contract is SettingsViewModel) {
            contract.handleStoragePickerResult(uri)
        }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // After returning from settings, check if permission was granted and proceed
        if (hasManageStoragePermission()) {
            storagePickerLauncher.launch(null)
        }
    }

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text("Storage permission needed") },
            text = {
                Text(
                    "Ghostify needs access to all files to save songs to your chosen folder. " +
                    "You'll be taken to the system settings page to enable this."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    manageStorageLauncher.launch(intent)
                }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRationale = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        contract.openStoragePicker.collect {
            if (hasManageStoragePermission()) {
                storagePickerLauncher.launch(null)
            } else {
                showPermissionRationale = true
            }
        }
    }

    // Migration dialog — shown after storage path changes if songs exist at old path
    state.pendingMigrationPath?.let { oldPath ->
        AlertDialog(
            onDismissRequest = contract::dismissMigration,
            title = { Text("Migrate songs?") },
            text = {
                if (state.isMigrating) {
                    Text("Moving songs to the new folder...")
                } else {
                    Text(
                        "You have downloaded songs at the old location.\n\n" +
                        "Move them to the new folder? New songs will always go to the new location regardless."
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = contract::migrateSongs,
                    enabled = !state.isMigrating,
                ) {
                    Text("Move songs")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = contract::dismissMigration,
                    enabled = !state.isMigrating,
                ) {
                    Text("Leave them")
                }
            },
        )
    }

    // Migration result snackbar
    state.migrationResult?.let { result ->
        AlertDialog(
            onDismissRequest = contract::dismissMigration,
            title = { Text("Migration complete") },
            text = { Text(result) },
            confirmButton = {
                TextButton(onClick = contract::dismissMigration) {
                    Text("OK")
                }
            },
        )
    }

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
        SettingsContent(
            state = state,
            contract = contract,
            padding = padding,
        )
    }
}

/**
 * Scrollable content area of the settings screen, organized into Downloads, Storage,
 * and Debug sections.
 */
@Composable
private fun SettingsContent(
    state: SettingsUiState,
    contract: SettingsContract,
    padding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionHeader("Downloads")

        BitrateSetting(state = state, contract = contract)
        Spacer(modifier = Modifier.height(SECTION_SPACING))

        StorageSetting(state = state, contract = contract)
        Spacer(modifier = Modifier.height(SECTION_SPACING))

        ConcurrencySetting(state = state, contract = contract)
        Spacer(modifier = Modifier.height(SECTION_SPACING))

        AutoDownloadSetting(state = state, contract = contract)
        Spacer(modifier = Modifier.height(SECTION_SPACING_LARGE))

        SectionHeader("UI")
        LandscapeControlsSideSetting(state = state, contract = contract)
        Spacer(modifier = Modifier.height(SECTION_SPACING_LARGE))

        SectionHeader("Storage")
        CacheSetting(state = state, contract = contract)
        Spacer(modifier = Modifier.height(SECTION_SPACING_LARGE))

        SectionHeader("Debug")
        DebugSetting()
        Spacer(modifier = Modifier.height(SECTION_SPACING_LARGE))

        SectionHeader("About")
        UpdateSetting(state = state, contract = contract)

        Spacer(modifier = Modifier.weight(1f))

        VersionInfo()
    }
}

/**
 * Section header label with primary color styling.
 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = INNER_SPACING),
    )
}

/**
 * Bitrate selection chips allowing the user to choose download quality.
 */
@Composable
private fun BitrateSetting(state: SettingsUiState, contract: SettingsContract) {
    Text("Download bitrate", style = MaterialTheme.typography.bodyLarge)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = INNER_SPACING)
            .testTag(SettingsTestTags.BITRATE),
        horizontalArrangement = Arrangement.spacedBy(INNER_SPACING),
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

/**
 * Storage location display with "Change" and "Reset" buttons.
 */
@Composable
private fun StorageSetting(state: SettingsUiState, contract: SettingsContract) {
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset storage location") },
            text = {
                Text(
                    "This will move future downloads back to the app's internal storage folder. " +
                    "Songs already downloaded to the custom folder will NOT be moved."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    contract.resetStoragePath()
                    showResetDialog = false
                }) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

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
        Spacer(modifier = Modifier.width(INNER_SPACING))
        OutlinedButton(
            onClick = contract::changeStoragePath,
            modifier = Modifier.testTag(SettingsTestTags.CHANGE_STORAGE),
        ) {
            Text("Change")
        }
        Spacer(modifier = Modifier.width(INNER_SPACING))
        OutlinedButton(
            onClick = { showResetDialog = true },
        ) {
            Text("Reset")
        }
    }
}

/**
 * Concurrent downloads control with increment/decrement buttons.
 */
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
                .width(CONCURRENCY_TEXT_WIDTH)
                .testTag(SettingsTestTags.CONCURRENCY_VALUE),
            textAlign = TextAlign.Center,
        )
        IconButton(
            onClick = { contract.setConcurrency(state.concurrentDownloads + 1) },
            enabled = true,
            modifier = Modifier.testTag(SettingsTestTags.CONCURRENCY_PLUS),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Increase")
        }
    }
}

/**
 * Auto-download toggle that starts downloading new playlists immediately after saving.
 */
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

/**
 * Landscape controls side selection chips for choosing left or right placement.
 */
@Composable
private fun LandscapeControlsSideSetting(state: SettingsUiState, contract: SettingsContract) {
    Text("Landscape controls side", style = MaterialTheme.typography.bodyLarge)
    Text(
        "Choose which side the playback controls appear on in landscape mode.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = INNER_SPACING),
        horizontalArrangement = Arrangement.spacedBy(INNER_SPACING),
    ) {
        FilterChip(
            selected = state.landscapeControlsSide == "left",
            onClick = { contract.setLandscapeControlsSide("left") },
            label = { Text("Left") },
        )
        FilterChip(
            selected = state.landscapeControlsSide == "right",
            onClick = { contract.setLandscapeControlsSide("right") },
            label = { Text("Right") },
        )
    }
}

/**
 * Cache statistics display with a "Clear cache" button.
 */
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
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear cache")
            }
        }
    }
}

private val ICON_SIZE = 18.dp

/**
 * Debug section with "Share logs" and "Clear logs" buttons.
 */
@Composable
private fun DebugSetting() {
    val context = LocalContext.current
    val app = context.applicationContext as GhostifyApplication

    OutlinedButton(
        onClick = { shareLogs(context, app) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Share logs")
    }
    Spacer(modifier = Modifier.height(INNER_SPACING))
    OutlinedButton(
        onClick = {
            app.fileLoggingTree.clearLogs()
            Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Clear logs")
    }
}

/**
 * Builds and launches a share intent for all available log files.
 */
private fun shareLogs(
    context: android.content.Context,
    app: GhostifyApplication,
) {
    val tree = app.fileLoggingTree
    val files = tree.logFiles()
    if (files.isEmpty()) {
        Toast.makeText(context, "No log files yet", Toast.LENGTH_SHORT).show()
        return
    }
    val uris = files.map { f ->
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share logs"))
}

/**
 * "Check for Update" button with download progress and update dialog.
 */
@Composable
private fun UpdateSetting(state: SettingsUiState, contract: SettingsContract) {
    // Update available dialog
    if (state.showUpdateDialog && state.updateInfo != null) {
        AlertDialog(
            onDismissRequest = contract::dismissUpdate,
            title = { Text("Update available") },
            text = {
                Column {
                    Text(
                        text = "v${state.updateInfo.versionName}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(INNER_SPACING))
                    if (state.updateInfo.releaseNotes.isNotBlank()) {
                        Text(
                            text = state.updateInfo.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = contract::confirmUpdate) {
                    Text("Download & Install")
                }
            },
            dismissButton = {
                TextButton(onClick = contract::dismissUpdate) {
                    Text("Cancel")
                }
            },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            when {
                state.downloadState is DownloadState.Downloading -> {
                    Text("Downloading update...", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.downloadState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setting_update_progress"),
                    )
                }
                state.downloadState is DownloadState.Downloaded -> {
                    Text("Download complete", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Tap Install to update.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.updateError != null -> {
                    Text(
                        text = state.updateError!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.updateError!!.startsWith("You're")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                else -> {
                    Text("Check for new versions", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Spacer(modifier = Modifier.width(INNER_SPACING))
        when {
            state.downloadState is DownloadState.Downloading -> {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            }
            state.downloadState is DownloadState.Downloaded -> {
                OutlinedButton(
                    onClick = contract::installUpdate,
                    modifier = Modifier.testTag("setting_install_update"),
                ) {
                    Text("Install")
                }
            }
            else -> {
                OutlinedButton(
                    onClick = contract::checkForUpdate,
                    enabled = !state.isCheckingUpdate,
                    modifier = Modifier.testTag(SettingsTestTags.CHECK_UPDATE),
                ) {
                    if (state.isCheckingUpdate) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Check for Update")
                    }
                }
            }
        }
    }
}

/**
 * App version info label displayed at the bottom of the settings screen.
 */
@Composable
private fun VersionInfo() {
    val context = LocalContext.current
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (_: Exception) {
        null
    }
    val versionCode = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } catch (_: Exception) {
        0L
    }
    Text(
        text = "Ghostify v${versionName ?: "?"} ($versionCode)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = SECTION_SPACING_LARGE)
            .testTag("setting_version"),
    )
}

/**
 * Checks if the app has MANAGE_EXTERNAL_STORAGE permission.
 * On API < 30 this always returns true (not needed).
 */
private fun hasManageStoragePermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

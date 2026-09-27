package xyz.botolog.ghostify.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import xyz.botolog.ghostify.python.PythonLibraryInfo

/** Read-only sheet listing the version and file of every loaded Python library. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PythonLibrariesSheet(
    pythonVersion: String?,
    implementation: String?,
    libraries: List<PythonLibraryInfo>,
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(SettingsTestTags.PYTHON_LIBRARIES_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(SettingsTestTags.PYTHON_LIBRARIES_CONTENT),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Python libraries",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    pythonRuntimeLabel(pythonVersion, implementation)?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
            Text(
                text = "Versions reported by the interpreter running in this build. " +
                    "Read-only: nothing is changed or downloaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(SHEET_SPACING))

            when {
                isLoading -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.PYTHON_LIBRARIES_LOADING),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(SHEET_SPACING))
                    Text("Reading the loaded libraries...", style = MaterialTheme.typography.bodyMedium)
                }

                error != null -> Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(SettingsTestTags.PYTHON_LIBRARIES_ERROR),
                )

                libraries.isEmpty() -> Text(
                    text = "No Python libraries were reported.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(SettingsTestTags.PYTHON_LIBRARIES_EMPTY),
                )

                else -> Column(verticalArrangement = Arrangement.spacedBy(SHEET_SPACING)) {
                    libraries.forEach { library ->
                        PythonLibraryRow(library = library)
                    }
                }
            }

            Spacer(modifier = Modifier.height(SHEET_SPACING))
        }
    }
}

/** One library row: name, reported version and the file it came from. */
@Composable
private fun PythonLibraryRow(library: PythonLibraryInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SettingsTestTags.pythonLibraryRow(library.name)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = library.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(SHEET_SPACING))
            Text(
                text = libraryVersionLabel(library),
                style = MaterialTheme.typography.bodyMedium,
                color = if (library.isAvailable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        Text(
            text = librarySourceLabel(library),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val SHEET_SPACING = 8.dp
private const val LABEL_SEPARATOR = " · "
private const val NO_SOURCE = "No file reported."

internal fun pythonRuntimeLabel(pythonVersion: String?, implementation: String?): String? {
    val version = pythonVersion?.trim()?.ifEmpty { null } ?: return null
    val name = implementation?.trim()?.ifEmpty { null }
    return if (name == null) version else "$name$LABEL_SEPARATOR$version"
}

internal fun libraryVersionLabel(library: PythonLibraryInfo): String {
    val error = library.error?.trim()?.ifEmpty { null }
    return if (error == null) library.version else "${library.version}$LABEL_SEPARATOR$error"
}

internal fun librarySourceLabel(library: PythonLibraryInfo): String =
    library.source.trim().ifEmpty { NO_SOURCE }

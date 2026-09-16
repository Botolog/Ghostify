package xyz.botolog.ghostify.ui.player

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

internal data class LrcLine(val timeMs: Long, val text: String)

internal fun parseLrc(lrc: String): List<LrcLine> {
    val regex = Regex("""^\[(\d{2}):(\d{2})\.(\d{2})\](.*)""")
    return lrc.lines().mapNotNull { line ->
        val m = regex.matchEntire(line.trim()) ?: return@mapNotNull null
        val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val sec = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val cs = m.groupValues[3].toLongOrNull() ?: return@mapNotNull null
        val text = m.groupValues[4].trim()
        if (text.isEmpty()) return@mapNotNull null
        LrcLine(timeMs = min * 60_000 + sec * 1_000 + cs * 10, text = text)
    }
}

internal fun toLrcString(lines: List<LrcLine>): String =
    lines.joinToString("\n") { line ->
        val min = line.timeMs / 60_000
        val sec = (line.timeMs % 60_000) / 1_000
        val cs = (line.timeMs % 1_000) / 10
        "[%02d:%02d.%02d] %s".format(min, sec, cs, line.text)
    }

private fun formatTimestamp(ms: Long): String {
    val min = ms / 60_000
    val sec = (ms % 60_000) / 1_000
    val cs = (ms % 1_000) / 10
    return "%02d:%02d.%02d".format(min, sec, cs)
}

private fun parseTimestamp(text: String): Long? {
    val m = Regex("""(\d{1,2}):(\d{2})\.?(\d{0,2})""").matchEntire(text.trim()) ?: return null
    val min = m.groupValues[1].toLongOrNull() ?: return null
    val sec = m.groupValues[2].toLongOrNull() ?: return null
    val cs = m.groupValues[3].ifEmpty { "0" }.toLongOrNull() ?: return null
    return min * 60_000 + sec * 1_000 + cs * 10
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LyricsEditView(
    lyrics: String?,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onRefetch: (String, (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val initialLines = remember(lyrics) { lyrics?.let { parseLrc(it) } ?: emptyList() }
    val lines = remember { mutableStateListOf<LrcLine>().apply { addAll(initialLines) } }
    var showShiftFromLineSheet by remember { mutableStateOf(false) }
    var shiftFromLineIndex by remember { mutableIntStateOf(0) }
    var timestampEditIndex by remember { mutableIntStateOf(-1) }
    var showRefetchPicker by remember { mutableStateOf(false) }
    var isRefetching by remember { mutableStateOf(false) }
    var showRevertDialog by remember { mutableStateOf(false) }
    val undoStack = remember { mutableStateListOf<List<LrcLine>>() }
    val listState = rememberLazyListState()

    fun pushUndo() {
        if (undoStack.size >= 50) undoStack.removeAt(0)
        undoStack.add(lines.toList())
    }

    fun undo() {
        if (undoStack.isEmpty()) {
            Toast.makeText(context, "Nothing to undo", Toast.LENGTH_SHORT).show()
            return
        }
        lines.clear()
        lines.addAll(undoStack.removeLast())
    }

    fun shiftAll(offsetMs: Long) {
        pushUndo()
        for (i in lines.indices) {
            lines[i] = lines[i].copy(timeMs = max(0L, lines[i].timeMs + offsetMs))
        }
    }

    fun shiftFrom(index: Int, offsetMs: Long) {
        pushUndo()
        for (i in index until lines.size) {
            lines[i] = lines[i].copy(timeMs = max(0L, lines[i].timeMs + offsetMs))
        }
    }

    fun deleteLine(index: Int) {
        pushUndo()
        lines.removeAt(index)
    }

    fun save() {
        onSave(toLrcString(lines))
        Toast.makeText(context, "Lyrics saved", Toast.LENGTH_SHORT).show()
        onBack()
    }

    if (timestampEditIndex in lines.indices) {
        TimestampEditDialog(
            initialMs = lines[timestampEditIndex].timeMs,
            onDismiss = { timestampEditIndex = -1 },
            onConfirm = { newMs ->
                pushUndo()
                lines[timestampEditIndex] = lines[timestampEditIndex].copy(timeMs = newMs)
                timestampEditIndex = -1
            },
        )
    }

    if (showRevertDialog) {
        AlertDialog(
            onDismissRequest = { showRevertDialog = false },
            title = { Text("Revert lyrics?") },
            text = { Text("This will discard all edits and restore the original lyrics.") },
            confirmButton = {
                TextButton(onClick = {
                    lines.clear()
                    lines.addAll(initialLines)
                    undoStack.clear()
                    showRevertDialog = false
                    Toast.makeText(context, "Lyrics reverted", Toast.LENGTH_SHORT).show()
                }) { Text("Revert") }
            },
            dismissButton = {
                TextButton(onClick = { showRevertDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showShiftFromLineSheet) {
        ShiftFromLineSheet(
            lineIndex = shiftFromLineIndex,
            lineCount = lines.size,
            onShift = { offsetMs -> shiftFrom(shiftFromLineIndex, offsetMs) },
            onDismiss = { showShiftFromLineSheet = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel editing")
            }
            Text(
                text = "Edit Lyrics",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { showRevertDialog = true }) { Text("Revert") }
            TextButton(onClick = ::save) { Text("Save") }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            itemsIndexed(
                items = lines,
                key = { index, line -> "${line.timeMs}_$index" },
                contentType = { _, _ -> "edit_line" },
            ) { index, line ->
                EditableLine(
                    line = line,
                    onTimestampTap = { timestampEditIndex = index },
                    onTextChange = { newText ->
                        pushUndo()
                        lines[index] = lines[index].copy(text = newText)
                    },
                    onDelete = { deleteLine(index) },
                    onLongPress = {
                        shiftFromLineIndex = index
                        showShiftFromLineSheet = true
                    },
                )
            }
        }

        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Shift all:",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    listOf(-5000L to "-5s", -1000L to "-1s", -100L to "-0.1s",
                           100L to "+0.1s", 1000L to "+1s", 5000L to "+5s"
                    ).forEach { (offset, label) ->
                        ShiftButton(label = label) { shiftAll(offset) }
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isRefetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    RefetchSourcePicker(
                        enabled = !isRefetching,
                        onSourceSelected = { provider ->
                            isRefetching = true
                            onRefetch(provider) { result ->
                                isRefetching = false
                                if (result != null) {
                                    pushUndo()
                                    lines.clear()
                                    lines.addAll(parseLrc(result))
                                    Toast.makeText(context, "Lyrics refetched", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "No lyrics found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    TextButton(onClick = ::undo) { Text("Undo") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditableLine(
    line: LrcLine,
    onTimestampTap: () -> Unit,
    onTextChange: (String) -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit,
) {
    var textValue by remember(line) { mutableStateOf(line.text) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onTimestampTap,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(end = 8.dp),
        ) {
            Text(
                text = formatTimestamp(line.timeMs),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        TextField(
            value = textValue,
            onValueChange = { textValue = it; onTextChange(it) },
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )

        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete line",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun TimestampEditDialog(
    initialMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var input by remember { mutableStateOf(formatTimestamp(initialMs)) }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit timestamp") },
        text = {
            Column {
                Text(
                    text = "Format: MM:SS.xx",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = false },
                    isError = error,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error) {
                    Text(
                        text = "Invalid format. Use MM:SS.xx",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ms = parseTimestamp(input)
                if (ms != null) onConfirm(ms) else error = true
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ShiftButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefetchSourcePicker(
    enabled: Boolean,
    onSourceSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        Surface(
            onClick = { if (enabled) expanded = true },
            shape = MaterialTheme.shapes.small,
            color = if (enabled) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        ) {
            Text(
                text = "Refetch ▾",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        if (enabled) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                listOf(
                    "synced" to "Synced (lrclib)",
                    "genius" to "Genius",
                    "musixmatch" to "MusixMatch",
                    "azlyrics" to "AzLyrics",
                ).forEach { (key, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { expanded = false; onSourceSelected(key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShiftFromLineSheet(
    lineIndex: Int,
    lineCount: Int,
    onShift: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shift from line ${lineIndex + 1}") },
        text = {
            Text(
                text = "Shift line ${lineIndex + 1} and all ${lineCount - lineIndex} lines below it.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {},
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(-5000L to "-5s", -1000L to "-1s", -100L to "-0.1s",
                       100L to "+0.1s", 1000L to "+1s", 5000L to "+5s"
                ).forEach { (offset, label) ->
                    ShiftButton(label = label) { onShift(offset); onDismiss() }
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
        },
    )
}

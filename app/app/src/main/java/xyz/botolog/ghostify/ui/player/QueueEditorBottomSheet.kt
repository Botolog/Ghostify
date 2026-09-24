package xyz.botolog.ghostify.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.botolog.ghostify.ui.contract.PlayerContract
import xyz.botolog.ghostify.ui.model.QueueItem
import xyz.botolog.ghostify.ui.util.DurationFormat

/**
 * Bottom sheet queue editor with three sections: Played, Current, and Up Next.
 *
 * Items in the "Played" and "Up Next" sections can be drag-reordered.
 * The "Current" item is highlighted and not draggable.
 *
 * @param contract the player contract for state and actions.
 * @param onDismiss callback when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueEditorBottomSheet(
    contract: PlayerContract,
    onDismiss: () -> Unit,
) {
    val state by contract.state.collectAsState()
    val queue = state.queue
    val currentIndex = queue.indexOfFirst { it.isCurrent }.coerceAtLeast(0)

    val playedItems = if (currentIndex > 0) queue.subList(0, currentIndex) else emptyList()
    val currentItem = queue.getOrNull(currentIndex)
    val nextItems = if (currentIndex < queue.size - 1) queue.subList(currentIndex + 1, queue.size) else emptyList()

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        QueueEditorContent(
            playedItems = playedItems,
            currentItem = currentItem,
            nextItems = nextItems,
            currentIndex = currentIndex,
            contract = contract,
        )
    }
}

/**
 * Main content of the queue editor, split into Played / Current / Up Next sections.
 */
@Composable
private fun QueueEditorContent(
    playedItems: List<QueueItem>,
    currentItem: QueueItem?,
    nextItems: List<QueueItem>,
    currentIndex: Int,
    contract: PlayerContract,
) {
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 500.dp),
    ) {
        // Played section
        if (playedItems.isNotEmpty()) {
            item(key = "header_played") {
                SectionHeader(title = "Played")
            }
            itemsIndexed(
                items = playedItems,
                key = { _, item -> "played_${item.songId}" },
            ) { localIndex, item ->
                val globalIndex = localIndex
                DraggableQueueItem(
                    item = item,
                    globalIndex = globalIndex,
                    totalItems = playedItems.size + 1 + nextItems.size,
                    allItems = playedItems + listOfNotNull(currentItem) + nextItems,
                    contract = contract,
                )
            }
        }

        // Current section
        if (currentItem != null) {
            item(key = "header_current") {
                SectionHeader(title = "Current")
            }
            item(key = "current_${currentItem.songId}") {
                CurrentQueueItem(item = currentItem, onClick = { contract.jumpToQueueIndex(currentIndex) })
            }
        }

        // Up Next section
        if (nextItems.isNotEmpty()) {
            item(key = "header_next") {
                SectionHeader(title = "Up Next")
            }
            itemsIndexed(
                items = nextItems,
                key = { _, item -> "next_${item.songId}" },
            ) { localIndex, item ->
                val globalIndex = currentIndex + 1 + localIndex
                DraggableQueueItem(
                    item = item,
                    globalIndex = globalIndex,
                    totalItems = playedItems.size + 1 + nextItems.size,
                    allItems = playedItems + listOfNotNull(currentItem) + nextItems,
                    contract = contract,
                )
            }
        }

        // Bottom spacing
        item(key = "spacer_bottom") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * A section header (e.g. "Played", "Current", "Up Next").
 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * The currently playing queue item — highlighted, not draggable.
 */
@Composable
private fun CurrentQueueItem(
    item: QueueItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = DurationFormat.format(item.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A draggable queue item with a drag handle. Supports long-press-then-drag reordering.
 *
 * @param item the queue item to display.
 * @param globalIndex the item's index in the full queue (not just the section).
 * @param totalItems total number of items in the full queue.
 * @param allItems the full queue list (for reordering).
 * @param contract the player contract for reorder actions.
 */
@Composable
private fun DraggableQueueItem(
    item: QueueItem,
    globalIndex: Int,
    totalItems: Int,
    allItems: List<QueueItem>,
    contract: PlayerContract,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragTargetIndex by remember { mutableIntStateOf(globalIndex) }

    val elevation by animateFloatAsState(
        targetValue = if (isDragging) 8f else 0f,
        label = "elevation",
    )

    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.03f else 1f,
        label = "scale",
    )

    val itemHeightPx = with(LocalDensity.current) { 56.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = if (isDragging) dragOffset.x else 0f
                translationY = if (isDragging) dragOffset.y else 0f
                shadowElevation = elevation
                shape = RoundedCornerShape(8.dp)
                clip = true
            }
            .then(
                if (isDragging) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                }
            )
            .pointerInput(globalIndex, totalItems) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        isDragging = true
                        dragOffset = Offset.Zero
                        dragTargetIndex = globalIndex
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffset += amount
                        // Calculate which position the item is being dragged over.
                        val rawTarget = globalIndex + (dragOffset.y / itemHeightPx).toInt()
                        dragTargetIndex = rawTarget.coerceIn(0, totalItems - 1)
                    },
                    onDragEnd = {
                        isDragging = false
                        dragOffset = Offset.Zero
                        if (dragTargetIndex != globalIndex) {
                            contract.reorderQueue(globalIndex, dragTargetIndex)
                        }
                        dragTargetIndex = globalIndex
                    },
                    onDragCancel = {
                        isDragging = false
                        dragOffset = Offset.Zero
                        dragTargetIndex = globalIndex
                    },
                )
            },
    ) {
        QueueItemRow(item = item)
    }
}

/**
 * A single row in the queue editor showing track title, artist, and duration.
 */
@Composable
private fun QueueItemRow(item: QueueItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = "Drag to reorder",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.outline,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.queuedByUser) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            ),
                    )
                }
            }
            Text(
                text = item.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = DurationFormat.format(item.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.isActive
import xyz.botolog.ghostify.ui.contract.PlayerContract
import xyz.botolog.ghostify.ui.model.QueueItem
import xyz.botolog.ghostify.ui.util.DurationFormat
import kotlin.math.roundToInt

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
    val showQueueCovers = state.showQueueCovers

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
            showQueueCovers = showQueueCovers,
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
    showQueueCovers: Boolean,
) {
    val listState = rememberLazyListState()

    // Improvement 1: Auto-scroll to the currently playing song.
    // The current item is at index = 1 (after the "Played" header) when playedItems is non-empty,
    // or index = 1 when there are no played items (just header + current).
    // We use a key-based approach: scroll to find the current item after composition.
    LaunchedEffect(Unit) {
        // Wait for the list to be laid out, then scroll to the current item.
        // The current item index in the LazyColumn depends on whether played section exists.
        // Layout: [header_played?] [played items...] [header_current] [current item] [header_next?] [next items...] [spacer]
        val targetIndex = if (playedItems.isNotEmpty()) {
            1 + playedItems.size + 1 // header_played + playedItems + header_current
        } else {
            1 // header_current (no played section, so header_current is at 1)
        }
        listState.animateScrollToItem(targetIndex)
    }

    // Improvement 3: Auto-scroll during drag near edges.
    var draggedGlobalIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val itemHeightPx = with(LocalDensity.current) { 56.dp.toPx() }
    val edgeZonePx = with(LocalDensity.current) { 80.dp.toPx() }

    LaunchedEffect(isDragging) {
        if (isDragging && draggedGlobalIndex >= 0) {
            while (isActive) {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                if (visibleItems.isEmpty()) break

                val viewportStart = listState.layoutInfo.viewportStartOffset.toFloat()
                val viewportEnd = listState.layoutInfo.viewportEndOffset.toFloat()
                val viewportHeight = viewportEnd - viewportStart

                // Find the dragged item by its LazyColumn key
                val queue = playedItems + listOfNotNull(currentItem) + nextItems
                val songId = queue.getOrNull(draggedGlobalIndex)?.songId
                val key = if (songId != null) {
                    if (draggedGlobalIndex < playedItems.size) "played_$songId" else "next_$songId"
                } else null

                val draggedItemInfo = key?.let { k -> visibleItems.find { it.key == k } }

                if (draggedItemInfo != null) {
                    // Calculate pointer position in viewport coordinates
                    val itemTop = draggedItemInfo.offset.toFloat()
                    val pointerY = itemTop + (dragOffsetY % itemHeightPx)

                    when {
                        pointerY < edgeZonePx -> {
                            val speed = (1f - pointerY / edgeZonePx).coerceIn(0.1f, 1f) * 12f
                            listState.scroll { scrollBy(-speed) }
                            dragOffsetY -= speed
                        }
                        pointerY > viewportHeight - edgeZonePx -> {
                            val speed = (1f - (viewportHeight - pointerY) / edgeZonePx).coerceIn(0.1f, 1f) * 12f
                            listState.scroll { scrollBy(speed) }
                            dragOffsetY += speed
                        }
                    }
                }
                kotlinx.coroutines.delay(16L)
            }
        }
    }

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
                    contract = contract,
                    showQueueCovers = showQueueCovers,
                    draggedGlobalIndex = draggedGlobalIndex,
                    dragOffsetY = dragOffsetY,
                    itemHeightPx = itemHeightPx,
                    isCurrentItem = false,
                    onDragStart = { index ->
                        draggedGlobalIndex = index
                        isDragging = true
                    },
                    onDragUpdate = { offset ->
                        dragOffsetY = offset
                    },
                    onDragEnd = { fromIndex, toIndex ->
                        isDragging = false
                        draggedGlobalIndex = -1
                        dragOffsetY = 0f
                        if (fromIndex != toIndex) {
                            contract.reorderQueue(fromIndex, toIndex)
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        draggedGlobalIndex = -1
                        dragOffsetY = 0f
                    },
                )
            }
        }

        // Current section
        if (currentItem != null) {
            item(key = "header_current") {
                SectionHeader(title = "Current")
            }
            item(key = "current_${currentItem.songId}") {
                CurrentQueueItem(
                    item = currentItem,
                    showCover = showQueueCovers,
                    onClick = { contract.jumpToQueueIndex(currentIndex) },
                )
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
                    contract = contract,
                    showQueueCovers = showQueueCovers,
                    draggedGlobalIndex = draggedGlobalIndex,
                    dragOffsetY = dragOffsetY,
                    itemHeightPx = itemHeightPx,
                    isCurrentItem = false,
                    onDragStart = { index ->
                        draggedGlobalIndex = index
                        isDragging = true
                    },
                    onDragUpdate = { offset ->
                        dragOffsetY = offset
                    },
                    onDragEnd = { fromIndex, toIndex ->
                        isDragging = false
                        draggedGlobalIndex = -1
                        dragOffsetY = 0f
                        if (fromIndex != toIndex) {
                            contract.reorderQueue(fromIndex, toIndex)
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        draggedGlobalIndex = -1
                        dragOffsetY = 0f
                    },
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
 *
 * Leads with a playing indicator instead of a drag handle so its content lines up
 * with the draggable rows, and shows the same cover thumbnail when covers are enabled.
 *
 * @param item the currently playing queue item.
 * @param showCover whether album covers should be rendered.
 * @param onClick invoked when the row is activated.
 */
@Composable
private fun CurrentQueueItem(
    item: QueueItem,
    showCover: Boolean,
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
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "Now playing",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.width(12.dp))

        QueueItemCover(item = item, showCover = showCover)

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
 * During drag the item follows the finger and renders above other items.
 * Non-dragged items between the original and target positions shift smoothly.
 *
 * @param item the queue item to display.
 * @param globalIndex the item's index in the full queue (not just the section).
 * @param totalItems total number of items in the full queue.
 * @param contract the player contract for reorder actions.
 * @param draggedGlobalIndex the global index of the item currently being dragged, or -1 if none.
 * @param dragOffsetY the cumulative Y offset of the dragged item.
 * @param itemHeightPx the height of a single item in pixels.
 * @param isCurrentItem whether this is the currently playing item (not draggable).
 * @param onDragStart called when a long-press drag begins with the global index.
 * @param onDragUpdate called with the updated cumulative Y offset during drag.
 * @param onDragEnd called with (fromIndex, toIndex) when drag finishes.
 * @param onDragCancel called when drag is cancelled.
 */
@Composable
private fun DraggableQueueItem(
    item: QueueItem,
    globalIndex: Int,
    totalItems: Int,
    contract: PlayerContract,
    showQueueCovers: Boolean,
    draggedGlobalIndex: Int,
    dragOffsetY: Float,
    itemHeightPx: Float,
    isCurrentItem: Boolean,
    onDragStart: (Int) -> Unit,
    onDragUpdate: (Float) -> Unit,
    onDragEnd: (Int, Int) -> Unit,
    onDragCancel: () -> Unit,
) {
    val isThisDragged = draggedGlobalIndex == globalIndex

    // Proportional shift for non-dragged items during drag.
    val shiftOffset = when {
        !isThisDragged && draggedGlobalIndex >= 0 && dragOffsetY != 0f -> {
            if (dragOffsetY > 0 && globalIndex > draggedGlobalIndex) {
                // Dragging down: shift items above up, proportional to drag distance
                val dist = globalIndex - draggedGlobalIndex
                val shift = (dragOffsetY - (dist - 1) * itemHeightPx).coerceIn(0f, itemHeightPx)
                -shift
            } else if (dragOffsetY < 0 && globalIndex < draggedGlobalIndex) {
                // Dragging up: shift items below down, proportional to drag distance
                val dist = draggedGlobalIndex - globalIndex
                val shift = (-dragOffsetY - (dist - 1) * itemHeightPx).coerceIn(0f, itemHeightPx)
                shift
            } else {
                0f
            }
        }
        else -> 0f
    }

    val animatedShift by animateFloatAsState(
        targetValue = shiftOffset,
        label = "shift",
    )

    // Elevation and scale for the dragged item
    val elevation by animateFloatAsState(
        targetValue = if (isThisDragged) 8f else 0f,
        label = "elevation",
    )
    val scale by animateFloatAsState(
        targetValue = if (isThisDragged) 1.03f else 1f,
        label = "scale",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isThisDragged) 1f else 0f)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = if (isThisDragged) dragOffsetY - itemHeightPx else animatedShift
                shadowElevation = elevation
                // Improvement 2: dragged item renders above all others
                if (isThisDragged) {
                    translationX = 0f
                    alpha = 1f
                }
                shape = RoundedCornerShape(8.dp)
                clip = true
            }
            .then(
                if (isThisDragged) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                }
            )
            .pointerInput(globalIndex, totalItems) {
                var localOffset = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        localOffset = 0f
                        onDragStart(globalIndex)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        localOffset += amount.y
                        onDragUpdate(localOffset)
                    },
                    onDragEnd = {
                        val finalTarget = (globalIndex + (localOffset / itemHeightPx).roundToInt())
                            .coerceIn(0, totalItems - 1)
                        onDragEnd(globalIndex, finalTarget)
                    },
                    onDragCancel = {
                        onDragCancel()
                    },
                )
            },
    ) {
        QueueItemRow(item = item, showCover = showQueueCovers)
    }
}

/**
 * A single row in the queue editor showing track title, artist, and duration.
 */
@Composable
private fun QueueItemRow(item: QueueItem, showCover: Boolean = false) {
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

        QueueItemCover(item = item, showCover = showCover)

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

/**
 * The album cover thumbnail shared by every queue row, followed by its trailing spacer.
 *
 * Renders nothing when covers are disabled or the item has no artwork, so all rows
 * (including the highlighted current one) stay aligned.
 *
 * @param item the queue item whose artwork should be rendered.
 * @param showCover whether the covers-in-queue setting is enabled.
 */
@Composable
private fun QueueItemCover(item: QueueItem, showCover: Boolean) {
    if (!shouldShowQueueItemCover(showCover, item.coverUrl)) return

    AsyncImage(
        model = item.coverUrl,
        contentDescription = "Album art",
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(4.dp)),
        contentScale = ContentScale.Crop,
    )
    Spacer(modifier = Modifier.width(12.dp))
}

/**
 * Whether a queue row should render its album cover thumbnail.
 *
 * Applies to every row, including the currently playing one.
 *
 * @param showCover the value of the covers-in-queue setting.
 * @param coverUrl the item's artwork URL, which may be absent.
 * @return `true` when covers are enabled and artwork is available.
 */
internal fun shouldShowQueueItemCover(showCover: Boolean, coverUrl: String?): Boolean =
    showCover && !coverUrl.isNullOrBlank()

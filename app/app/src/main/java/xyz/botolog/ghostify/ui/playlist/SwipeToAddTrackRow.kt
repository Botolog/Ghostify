package xyz.botolog.ghostify.ui.playlist

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun performTickVibration(context: Context) {
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Exception) {
    }
}

@Composable
fun SwipeToAddTrackRow(
    onAddToQueue: () -> Unit,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val revealPx = with(LocalDensity.current) { 40.dp.toPx() }
    val commitThreshold = revealPx * 0.4f
    val maxExtra = revealPx * 0.35f
    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    var rawOffset by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var animJob by remember { mutableStateOf<Job?>(null) }
    var hasCommitted by remember { mutableStateOf(false) }

    fun rubberBand(raw: Float): Float {
        if (raw <= revealPx) return raw
        val over = raw - revealPx
        return revealPx + maxExtra * over / (maxExtra + over)
    }

    fun springBack() {
        animJob?.cancel()
        animJob = scope.launch {
            animate(
                initialValue = offsetX,
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
            ) { v, _ -> offsetX = v }
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled) springBack()
    }

    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
        Box(
            Modifier.matchParentSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier.fillMaxWidth(0.5f).fillMaxHeight().background(Color(0xFF4CAF50)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlaylistAdd,
                    contentDescription = "Add to queue",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .offset(x = 14.dp),
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.surface)
                .then(
                    if (!enabled) Modifier
                    else Modifier
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    animJob?.cancel()
                                    rawOffset = offsetX
                                    hasCommitted = false
                                },
                                onDragEnd = {
                                    if (offsetX >= commitThreshold && !hasCommitted) {
                                        performTickVibration(context)
                                        onAddToQueue()
                                        hasCommitted = true
                                    }
                                    springBack()
                                },
                                onDragCancel = { springBack() },
                            ) { change, dragAmount ->
                                change.consume()
                                rawOffset = (rawOffset + dragAmount).coerceAtLeast(0f)
                                offsetX = rubberBand(rawOffset)
                            }
                        }
                        .clickable { onClick() }
                ),
        ) { content() }
    }
}

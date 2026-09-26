package xyz.botolog.ghostify.ui.util

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

object InAppSnackbarTestTags {
    const val HOST = "in_app_snackbar_host"
    const val MESSAGE = "in_app_snackbar_message"
    const val ICON = "in_app_snackbar_icon"
    const val TEXT = "in_app_snackbar_text"
}

object InAppSnackbarDefaults {
    const val DurationMillis = 2_200L
    const val DedupWindowMillis = 1_500L
    val LeadingIcon: ImageVector = Icons.Filled.Check
    val AccentOnLightContainer = Color(0xFF1565C0)
    val AccentOnDarkContainer = Color(0xFF90CAF9)
}

@Immutable
data class InAppSnackbarMessage(
    val id: Long,
    val text: String,
    val durationMillis: Long,
    val leadingIcon: ImageVector?,
)

internal enum class InAppSnackbarDedup { Show, AlreadyVisible, ShownRecently }

internal fun inAppSnackbarDedup(
    text: String,
    visibleText: String?,
    lastShownText: String?,
    lastShownAtMillis: Long,
    nowMillis: Long,
    windowMillis: Long,
): InAppSnackbarDedup = when {
    visibleText == text -> InAppSnackbarDedup.AlreadyVisible
    lastShownText == text && nowMillis - lastShownAtMillis < windowMillis ->
        InAppSnackbarDedup.ShownRecently
    else -> InAppSnackbarDedup.Show
}

object InAppSnackbarText {
    fun addedToQueue(title: String): String {
        val trimmed = title.trim()
        return if (trimmed.isEmpty()) "Added to queue" else "$trimmed added to queue"
    }
}

@Stable
class InAppSnackbarState internal constructor(
    private val nowMillis: () -> Long = { SystemClock.uptimeMillis() },
) {
    var message: InAppSnackbarMessage? by mutableStateOf(null)
        private set

    private var nextId = 0L
    private var lastShownText: String? = null
    private var lastShownAtMillis = Long.MIN_VALUE

    fun show(
        text: String,
        durationMillis: Long = InAppSnackbarDefaults.DurationMillis,
        leadingIcon: ImageVector? = InAppSnackbarDefaults.LeadingIcon,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val now = nowMillis()
        val decision = inAppSnackbarDedup(
            text = trimmed,
            visibleText = message?.text,
            lastShownText = lastShownText,
            lastShownAtMillis = lastShownAtMillis,
            nowMillis = now,
            windowMillis = InAppSnackbarDefaults.DedupWindowMillis,
        )
        if (decision != InAppSnackbarDedup.Show) return
        nextId += 1
        lastShownText = trimmed
        lastShownAtMillis = now
        message = InAppSnackbarMessage(
            id = nextId,
            text = trimmed,
            durationMillis = durationMillis,
            leadingIcon = leadingIcon,
        )
    }

    fun showAddedToQueue(title: String) {
        show(InAppSnackbarText.addedToQueue(title))
    }

    fun dismiss() {
        message = null
    }
}

@Composable
fun rememberInAppSnackbarState(): InAppSnackbarState = remember { InAppSnackbarState() }

@Composable
fun InAppSnackbarHost(
    state: InAppSnackbarState,
    modifier: Modifier = Modifier,
) {
    val message = state.message
    var rendered by remember { mutableStateOf<InAppSnackbarMessage?>(null) }

    SideEffect {
        if (message != null) rendered = message
    }

    LaunchedEffect(message?.id) {
        val active = message ?: return@LaunchedEffect
        delay(active.durationMillis)
        state.dismiss()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .testTag(InAppSnackbarTestTags.HOST),
        contentAlignment = Alignment.BottomCenter,
        propagateMinConstraints = false,
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(160)) + slideInVertically(tween(240)) { it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it / 2 },
        ) {
            rendered?.let { active ->
                InAppSnackbarBanner(
                    text = active.text,
                    leadingIcon = active.leadingIcon,
                )
            }
        }
    }
}

@Composable
fun InAppSnackbarBanner(
    text: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = InAppSnackbarDefaults.LeadingIcon,
    containerColor: Color = MaterialTheme.colorScheme.inverseSurface,
    contentColor: Color = MaterialTheme.colorScheme.inverseOnSurface,
    accentColor: Color = inAppSnackbarAccentColor(),
) {
    Surface(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .heightIn(min = 40.dp)
            .testTag(InAppSnackbarTestTags.MESSAGE)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 10.dp,
        tonalElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.18f))
                        .testTag(InAppSnackbarTestTags.ICON),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(InAppSnackbarTestTags.TEXT),
            )
        }
    }
}

@Composable
private fun inAppSnackbarAccentColor(): Color =
    if (isSystemInDarkTheme()) InAppSnackbarDefaults.AccentOnLightContainer
    else InAppSnackbarDefaults.AccentOnDarkContainer

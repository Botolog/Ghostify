package xyz.botolog.ghostify.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.compose.LocalImageLoader
import coil.memory.MemoryCache

/**
 * Default memory budget for a screen-scoped cover cache: 40 MiB.
 *
 * Deliberately its own bound rather than the process-wide cache's `maxSizePercent(0.25)`:
 * this cache only ever holds the small thumbnails of one list (40-56 dp), and it is thrown
 * away with the list, so a fixed, modest cap is easier to reason about.
 */
private const val DEFAULT_MAX_SIZE_BYTES: Int = 40 * 1024 * 1024

/**
 * Wraps scrollable cover lists in a screen-scoped Coil [coil.ImageLoader] whose memory cache is
 * cleared when the wrapped content leaves composition.
 *
 * Why this exists: the process-wide loader keeps every decoded cover in its LRU until the size
 * budget is exceeded, so after the recent OOM fix a long song list (queue editor, playlist
 * detail, library, search) still leaves ~all of its thumbnails resident once the screen or
 * sheet is dismissed. Rather than shrinking the global cache — which the now-playing artwork of
 * the mini/full player relies on — each heavy list gets its own bounded cache that is dropped
 * on leave.
 *
 * The scoped loader is derived with [coil.ImageLoader.newBuilder], so it reuses the process
 * loader's components (including the SVG decoder) and disk cache; only the memory cache is
 * swapped for a fresh, bounded one. Re-entering the wrapped screen re-decodes from the disk
 * cache, which is cheap and keeps the resident set proportional to what is actually on screen.
 *
 * [coil.compose.LocalImageLoader] is overridden, so any `AsyncImage` inside [content] that does
 * not pass an explicit loader uses the scoped cache. Nothing outside this composition — in
 * particular the mini-player and full-player now-playing artwork — is affected.
 *
 * @param maxSizeBytes maximum size of the scoped memory cache in bytes.
 * @param content the list content whose covers should live in the scoped cache.
 */
@Composable
fun UnloadingCoverCache(
    maxSizeBytes: Int = DEFAULT_MAX_SIZE_BYTES,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val base = LocalImageLoader.current
    val scoped = remember(base, context, maxSizeBytes) {
        base.newBuilder()
            .memoryCache(
                MemoryCache.Builder(context)
                    .maxSizeBytes(maxSizeBytes)
                    .build(),
            )
            .build()
    }
    DisposableEffect(scoped) {
        onDispose { scoped.memoryCache?.clear() }
    }
    CompositionLocalProvider(LocalImageLoader provides scoped) { content() }
}

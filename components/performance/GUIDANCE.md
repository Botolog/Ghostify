# Ghostify — Performance guidance

This component pins the four budgets from `TESTS.md`:

| Test | Budget | Enforced by |
|---|---|---|
| T-163 | Library list: 100 playlists, < 16 ms/frame | `FrameStatsCollector` + `FrameTiming.summarize(..).passesBudget` |
| T-164 | Playlist detail: 1000 tracks, no OOM | `LazyColumn` bounded composition + `LazyListMetrics.maxComposedItems` |
| T-165 | Python bridge never blocks UI | `Dispatchers.IO` dispatch + `ChoreographerStallProbe` |
| T-166 | Repeated downloads don't leak/grow heap | `DownloadLeakTracker` + `RetainedHeapProbe` + `HeapGrowthAnalyzer` |
| T-167 | No busy loops in background | work is event-driven (WorkManager / callbacks) |

All the "math" (percentiles, heap trend, frame-gap stall detection, composition bound,
coalescing throttle) is **pure JVM** and unit-tested in `tests/unit`. The Android
wrappers (`FrameStatsCollector`, `ChoreographerStallProbe`, `RetainedHeapProbe`)
are thin, compile-checked against `android.jar` + AARs. The Compose-rendering and
bridge/loop instruments are **device-only** (`src/androidTest`).

---

## 1. Jank budget — measure frames, assert percentiles

`16.666 ms` is a 60 Hz frame. A single 33 ms frame is a skipped frame; one is
noticeable, many are jank. We don't average — we assert on the **p95**:

```kotlin
// src/main/.../jvm/FrameTiming.kt
object JankBudget {
    const val FRAME_BUDGET_MS = 16.0
    const val FRAME_BUDGET_NS = 16_666_667L
}
```

Capture real frames on device with androidx's aggregator:

```kotlin
// src/main/.../android/FrameStatsCollector.kt
FrameStatsCollector(activity).use { c ->
    c.start()
    ... drive the list ...
    check(c.stats().passesBudget) { "janky: ${c.stats()}" }
}
```

`FrameTiming.summarize(..)` returns p50/p90/p95/p99 + janky count; `passesBudget`
requires at least one frame **and** p95 ≤ 16 ms (an empty sample fails, never
passes silently). See `FrameTimingTest`.

> **No micro-optimization.** We never "shave 2 ms off a row." We keep rows
> fixed-height (no intrinsic measure), give them stable keys, and compose only
> the viewport. If p95 is still over budget on a real device, the list is big
> enough that it is a data problem, not a row-cost problem.

---

## 2. Lazy lists — compose only the viewport (the T-164 guarantee)

A `LazyColumn` is O(visible), not O(n). We make that concrete with
`LazyListMetrics.maxComposedItems`:

```kotlin
// src/main/.../jvm/LazyListMetrics.kt
fun maxComposedItems(itemHeightPx: Int, viewportHeightPx: Int, prefetchItemCount: Int): Int =
    ceil(viewportHeightPx.toDouble() / itemHeightPx).toInt() + 2 * prefetchItemCount
```

For a 56 dp track row on a 480 dp viewport this is ~10 regardless of playlist
size — so 1000 tracks compose ~10 rows, never 1000. That is the "no OOM by
construction" invariant, and it is unit-tested
(`maxComposedItems_is_constant_wrt_playlist_size`).

Reusable pattern (device-only `@Composable`, `src/androidTest/.../pattern/`):

```kotlin
@Composable
fun TrackList(tracks: List<TrackSummary>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("TrackList"),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(
            items = tracks,
            key = { it.spotifyId },            // stable identity across re-syncs
            contentType = { TrackRowContentType }, // ONE constant → rows are recycled, not re-inflated
        ) { track -> TrackRow(track = track) }
    }
}
```

The four rules that keep a giant list cheap:

1. **`LazyColumn`** (not `Column`) — materializes only the visible window.
2. **Fixed-height rows** — a height-based row (`height(56.dp)`) avoids the
   expensive intrinsic-measure pass that kills frame budget.
3. **Stable `key`** — row identity survives list diffs so Compose
   recomposes only changed rows.
4. **Constant `contentType`** — lets Compose recycle the row composition across
   mutations (added/removed items) instead of re-inflating.

> Note: Compose 1.7.x `LazyColumn` has **no** `beyondBoundsItemCount` parameter
> (it arrived later); prefetch here is the default bounded window. The exact
> composition bound is computed version-agnostically by `LazyListMetrics`.

---

## 3. Python bridge — never touch the main thread (the T-165 invariant)

Chaquopy / spotdl is synchronous Python. If you call it on the main thread you
freeze the UI for seconds. The bridge is a thin Kotlin wrapper that dispatches
to a background dispatcher and posts results back:

```kotlin
class PythonBridge @Inject constructor(
    private val background: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun fetchPlaylist(spotifyId: String): PlaylistMeta =
        withContext(background) {
            // spotdl.get_playlist_metadata(...) — NEVER called on the main thread.
            ghostifyDl.fetchPlaylist(spotifyId)
        }
}
```

Callers are `suspend`, so they can only run from a coroutine — the compiler
refuses any accidental main-thread call. Results flow back to the UI via
`withContext(Dispatchers.Main)`.

**Progress coalescing.** spotdl reports progress many times/sec per track; pushing
each raw event into Compose state triggers a recomposition per event (battery +
jank). Coalesce at the producer:

```kotlin
// src/main/.../jvm/UiBusyLoopGuard.kt
val throttle = CoalescingThrottle(minIntervalMillis = 500L)
progressFlow.collect { pct ->
    throttle.offer(pct)?.let { uiState.update(it) }   // ≤ 2 updates/sec
}
throttle.flush()?.let { uiState.update(it) }          // final value
```

`CoalescingThrottle` is a pure state machine — no timer thread, no sleep, no loop
(it emits on tick boundaries the caller chooses). That is exactly what the battery
budget wants from background work.

**Detecting regressions.** `ChoreographerStallProbe` registers a frame callback;
consecutive frame gaps are classified by `BridgeStallDetector`:

```kotlin
val stats = ChoreographerStallProbe.runBlockingOnMain { /* block under test */ }
// stats.stallCount > 0  ⟹  the main thread was blocked
```

The stall threshold is **2 frames** (32 ms) — sub-frame GC/render hiccups are
tolerated, genuine main-thread blocks are not. `BridgeStallDetectorTest` covers
the math (smooth run = no stalls; a 100 ms gap = 1 stall).

---

## 4. Memory & leaks — bound the wrapper lifecycle (the T-166 invariant)

A bridge wrapper (Chaquopy interpreter / spotdl downloader session) must be
**closed exactly once per use** — otherwise the interpreter handle (and its
native heap) leaks. `DownloadLeakTracker` models that contract so both the
production manager and the test can assert it:

```kotlin
val id = tracker.open("downloadAll:p1")   // wrapper acquired
... run the download ...
tracker.close(id)                         // wrapper released
check(tracker.retained("downloadAll:p1").isEmpty())
```

After a workload, `retainedCount() == 0` is the deterministic leak assertion; it
is also the counter the heap-growth test correlates with time.

**Heap-growth regression.** Two-point snapshots can't prove a leak. Sample the
heap across many iterations (GC between samples), then fit:

```kotlin
// src/main/.../jvm/MemoryMetrics.kt
val report = HeapGrowthAnalyzer.analyze(samples)
report.unbounded   // ⟺ slope > 2 MiB/min AND Pearson r ≥ 0.85
```

A positive slope that is **strongly correlated with time** is a steady leak; a
flat or noisy profile is GC churn. `MemoryMetricsTest` checks flat = not
unbounded, steady linear = unbounded, noisy = not unbounded, < 3 samples =
not unbounded (too short to establish a trend).

`RetainedHeapProbe` provides `currentUsedBytes()`, `nativeHeapBytes()` (Chaquopy
buffers) and `gcAndWait()` (a few best-effort GC passes so short-lived objects
are collected before measuring).

---

## 5. Battery — no busy loops (the T-167 invariant)

There is no polling thread. All background work is event-driven:

- **Downloads** run under `WorkManager`, which the OS schedules and coalesces —
  the app never spins waiting for progress.
- **Progress** arrives via spotdl hooks → a `Flow<DownloadProgress>`, observed
  with `CoalescingThrottle` (see §3) — one UI update per half-second, not one per
  byte.
- **Coroutines** are bound to a lifecycle (`repeatOnLifecycle` / a ViewModel
  scope); the moment the screen is gone, the collector cancels — no lingering
  loops.
- The **Python interpreter** (`Chaquopy`) is created once and reused for the whole
  download session; it is never re-created per track.
- **Idle in background** → no background service holds a wake lock, so the device
  sleeps like other media apps. (T-167 is a manual sanity check, not automatable
  in a unit test.)

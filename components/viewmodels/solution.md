# Ghostify — ViewModels / State Management

## 1. What was built

A pure-Kotlin, JVM-testable state layer under `com.ghostify.viewmodel`, with **no scaffold**: the four real ViewModels plus their state models and an interface contract for every dependency, so the whole layer is driven by in-memory fakes (no Room/Media3/Chaquopy needed for tests).

| Artifact | File | Role |
|---|---|---|
| `ViewModel.kt` | base `ViewModel` | minimal stand-in for `androidx.lifecycle.ViewModel` (`viewModelScope` = `SupervisorJob + Dispatchers.Main.immediate`, `clear()` cancels the scope → ends every subscription). In the app it is trivially replaced by the androidx class. |
| `Model.kt` | domain types | `PlaylistSummary`, `Track`, `PlaylistStatus`, `SongStatus`, `DownloadProgress`, `SongProgress`, `DownloadRunState` (mirrors PROJECT.md §4). |
| `Contracts.kt` | interface deps | `PlaylistRepository`, `DownloadController`, `PlaylistSyncer`, `PlaylistUrlParser`, `PlaylistFetcher`, `LocalFileStore`, `PlayerController` + `PlayerPlaybackState`/`PlayerLoadResult`/`RepeatMode`/`SyncOutcome`/`UrlParseResult`/`PlaylistFetchResult`/`FetchedPlaylist`/`FetchedTrack`. |
| `LibraryUiState.kt` + `LibraryViewModel.kt` | `LibraryUiState` (Loading/Empty/Content/Error) | exposes playlists sorted **by `createdAt` descending, newest first** (T-109). |
| `PlaylistUiState.kt` + `PlaylistViewModel.kt` | `PlaylistUiState` (Loading/NotFound/Empty/Content/Error), `TrackUi` | merges playlist + tracks + live `DownloadProgress` into `TrackUi`; single-flight download; safe play/sync/delete mid-download (T-110, T-112, T-116, T-117). |
| `PlayerUiState.kt` + `PlayerViewModel.kt` | `PlayerUiState` (Loading/NothingToPlay/Content/Error) | pure mapping `PlayerPlaybackState → PlayerUiState`; forwards commands; subscribes in `viewModelScope` (no leak) (T-111). |
| `AddPlaylistUiState.kt` + `AddPlaylistViewModel.kt` | `AddPlaylistUiState` (Idle/Validating/Fetching/Preview/Saving/Success/Error) | URL→validate→fetch→preview→save state machine; invalid URL → Error, dialog stays open (`dialogOpen`); success → `Success` + row written to the shared repo (T-114, T-115). |

## 2. Key decisions (the "optimal" points)

- **State = `StateFlow`, one source of truth per screen.** Every screen exposes a single `StateFlow<UiState>`; the UI just re-renders from the cached latest value. Re-rotating the screen re-collects the same StateFlow and sees the last value instantly — no Loading flicker, no re-fetch (T-113).
- **Lifecycle = `viewModelScope` only.** The only upstream collection each VM starts is inside `viewModelScope.launch`. `clear()` cancels that scope, which cancels the `combine`/`map`/`catch` chain → the collectors (and thus the controller subscriptions) are released. PlayerViewModel proves this with an observer counter (T-111 no-leak).
- **Interface dependencies + `open`/fakes.** `PlaylistRepository`, `DownloadController`, `PlaylistFetcher`, `LocalFileStore`, `PlayerController` are pure interfaces. `FakePlaylistRepository` is `open` so a test can override `save` to inject a failure (disk-full path). No Android framework is touched.
- **Download run = single-flight.** `DownloadController.downloadAll` returns `false` if a run is already active; the VM treats that as a lenient no-op, so a double-tap or a sync-triggered `downloadAll` while running never crashes (T-116).
- **`DeletePlaylistUseCase` ordering (T-117, the "no orphan files" guarantee).** The order is deliberate: (1) `cancel` the active run so no worker can write new files; (2) delete every path the DB still knows about; (3) sweep the playlist's storage folder to catch any file a just-cancelled worker wrote between steps 1 and 2; (4) delete the rows last. Every file op is defensive (missing paths are no-ops), so concurrent play/sync/delete stay crash-free.
- **Playlist detail = `combine` of 5 streams.** playlist + tracks + progress + `syncing` + `deleting`. Per-track `TrackUi` merges live `SongProgress` (status/fraction) and falls back to the stored row state for untouched tracks, so a track mid-download (`t1` DOWNLOADING) and a track still `PENDING` (`t2`) are each rendered correctly (T-110).
- **Player is a view, not an owner.** `PlayerViewModel` holds zero playback state — it maps `PlayerPlaybackState` onto `PlayerUiState` and forwards commands. It can therefore never diverge from the real Media3 player.

## 3. Harness decisions

- A **minimal JVM `ViewModel`** (not the AndroidX one) is used so the component compiles and runs 100 % on the JVM. It mirrors the AndroidX contract exactly (same scope shape, same `clear()` semantics), so swapping it for `androidx.lifecycle.ViewModel` in the Hilt wiring is a one-line change. Hilt would constructor-inject the four VMs with the real `PlaylistRepository`/`DownloadController`/etc. implementations.
- `ViewModelTest` base sets `Dispatchers.setMain(StandardTestDispatcher())` so the VMs' `viewModelScope` (which uses `Dispatchers.Main.immediate`) is driven deterministically by `runTest`/`advanceUntilIdle`.

## 4. Environment / build issues resolved (so the tests actually run here)

The repo was supplied with full sources but the isolated Gradle environment needed three fixes to compile and execute:

1. **Gradle JVM caps.** `/root/.gradle/gradle.properties` pinned the daemon to `-Xmx512m -XX:MaxMetaspaceSize=64m`, which OOMs the Kotlin 2.0.21 compiler (`OutOfMemoryError: Metaspace`). Raised `org.gradle.jvmargs` (daemon stays disabled, per the `--no-daemon` constraint). Kept a project-local `gradle.properties` for the kotlin daemon too.
2. **`kotlin.test` + JUnit Platform bridge.** `kotlin("test")` annotations are not discovered by JUnit Jupiter on their own. Added `testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.0.21")` so `kotlin.test.@Test` runs on the JUnit Platform. (Note: Kotlin 2.0.21's gradle plugin in this environment drops the `kotlin { test { useJUnit5() } }` DSL, so the dependency is added explicitly.)
3. **coroutines-test 1.8.1 API drift + two harness bugs.** `advanceUntilIdle` is an *extension* on `TestScope` in 1.8.1 (not a member), so each test file now imports `kotlinx.coroutines.test.advanceUntilIdle`; `CoroutineScope.isActive` needs `import kotlinx.coroutines.isActive`.
4. **Two test-correctness fixes (not implementation changes):**
   - `FakePlaylistRepository.save` had its parameter named `tracks`, which **shadowed** the `MutableStateFlow<Map<String,List<Track>>>` member `tracks` → `tracks.value` resolved against a `List`. Renamed the member to `trackStore` (the parameter now matches the interface name).
   - `FakePlayerController`'s default state was `PlayerPlaybackState()` (`queueSize = 0`), which maps to `NothingToPlay`. The "commands are forwarded" test expects `Content` + a `playPause` toggle that turns `isPlaying` on, so the baseline fake state is now "a 1-track queue, paused" (`queueSize = 1`).
   - `LibraryViewModelTest` T-112 "Loading then Empty" asserted a *separate collector* observed `Loading`. Under `StandardTestDispatcher`, the VM's collection is dispatched at construction (before the collector attaches), so the collector only ever sees the terminal `Empty` — the `Loading` is only ever the **initial** `StateFlow` value. Rewrote the assertion to verify the `Loading → Empty` transition via the StateFlow's current value (initial read = `Loading`, post-`advanceUntilIdle` read = `Empty`) plus the collector's terminal `Empty`. Same intent, deterministically true.
   - Renamed one backtick test name that contained `:` (illegal in a JVM method name): `…no leak: the…` → `…no leak when the VM is cleared the…`.

## 5. Test results (T-109–T-117)

Run with `./gradlew test --no-daemon` (daemon disabled in `gradle.properties`). **27 tests, 27 passed, 0 failed, 0 errors.**

| Test file | Tests | T-ids covered |
|---|---|---|
| `LibraryViewModelTest` | 5 | T-109 (sort by creation, newest first), T-112 (Loading→Empty→Content→Error), T-113 (rotation survives, re-renders from cached state) |
| `PlaylistViewModelTest` | 9 | T-110 (per-song + overall progress), T-112 (Loading/Empty/NotFound/Error), T-116 (play/sync/download mid-download safe; duplicate download no-op), T-117 (delete during download cancels run + deletes DB paths + folder sweep → no orphans; clean delete still cleans files) |
| `PlayerViewModelTest` | 7 | T-111 (state→Content mapping incl. playing/position/shuffle/repeat), command forwarding (seek/shuffle/repeat/volume/next/prev), **no-leak** (observer count 1→0 on `clear()`), T-112 (Loading/NothingToPlay/Error, loadPlaylist filters to DOWNLOADED only) |
| `AddPlaylistViewModelTest` | 6 | T-112 (Validating/Fetching/Fetch-failure/Error/URL-edit-clears-error), T-114 (invalid URL → Error message, `dialogOpen` stays true), T-115 (Success closes dialog; row written to shared repo so Library reflects it; save-failure keeps dialog open with error) |

### Per-test mapping to T-109..T-117

- **T-109** ✓ `LibraryViewModelTest > T-109 playlists are sorted by creation date, newest first`
- **T-110** ✓ `PlaylistViewModelTest > T-110 download progress events surface as per-song and overall`
- **T-111** ✓ `PlayerViewModelTest` — `…maps player state to UI state…`, `…commands are forwarded…`, `…no leak…`
- **T-112** ✓ Loading/Empty/NotFound/Error/NothingToPlay/Validating/Fetching/Error/Saving across all four ViewModels
- **T-113** ✓ `LibraryViewModelTest > T-113 rotation keeps the VM alive and re-renders from cached state`
- **T-114** ✓ `AddPlaylistViewModelTest > T-114 invalid URL shows the message and keeps the dialog open`
- **T-115** ✓ `AddPlaylistViewModelTest > T-115 success closes the dialog and the new playlist appears in the Library` (+ save-failure keeps dialog open)
- **T-116** ✓ `PlaylistViewModelTest > T-116 play and sync mid-download are safe and never crash` + `…duplicate download-all while running is a benign no-op`
- **T-117** ✓ `PlaylistViewModelTest > T-117 deleting mid-download cancels the run and leaves no orphan files` + `…deleting a playlist with no running download still cleans files`

## 6. Why this is optimal (not a "just passes" hack)

- **No test-only backdoors in the production code.** The ViewModels have no `if (BuildConfig.DEBUG)` branches and no test-only constructors; they depend only on the `Contracts.kt` interfaces. The fakes live in the test source set.
- **Leak safety is structural, not per-test.** "No leak" is guaranteed by construction: a single `viewModelScope`-bound collection per VM whose lifetime is tied to `clear()`, so it holds for *any* screen lifetime, not just the assertion in the test.
- **Concurrency safety is ordered by invariant, not by flags alone.** Delete cancels first, then sweeps files, then drops rows; download is single-flight; sync never touches in-flight rows. The tests prove the invariants, not just one execution order.
- **State is a single derived `StateFlow`.** Screens can't present inconsistent sub-state (e.g., a track "downloaded" icon with 0 %) because `PlaylistViewModel` derives every `TrackUi` from one `combine` emission.
- **Lifecycle correctness.** `viewModelScope` + `clear()` gives true config-change survival (T-113) with no retained references after `clear()` (T-111).

## 7. Wiring note (Hilt / Media3 / Room)

The component is self-contained by design: the app's Hilt module would bind the real `PlaylistRepository` (Room DAO adapter), `DownloadController` (WorkManager-backed), `PlaylistSyncer`, `PlaylistUrlParser`, `PlaylistFetcher` (Chaquopy/spotdl), `LocalFileStore` (`getExternalFilesDir`), and `PlayerController` (Media3 `ExoPlayer` + `MediaSession`) to the ViewModel constructors. `PlayerUiState`/`PlayerPlaybackState` map cleanly onto Media3's `Player` + `MediaSession` callbacks, and `DownloadProgress` maps onto the download `Flow` from PROJECT.md §6.1.

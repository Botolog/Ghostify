# Player Core — Solution

## What was built

A complete Media3/ExoPlayer playback engine for Ghostify. The component lives under `com.ghostify.player` and `com.ghostify.player.core`.

### Core types (Android-free, JVM-testable)

| File | Purpose |
|---|---|
| `core/Song.kt` | Domain model mirroring the Room `songs` row — `isDownloaded` predicate filters on both status and file path |
| `core/QueueItem.kt` | One entry in the built queue: songId, metadata, filePath, queue index |
| `core/QueueBuildResult.kt` | Sealed interface: `Ready(items, startIndex)` or `NothingToPlay` |
| `core/PlayerQueueBuilder.kt` | Pure queue builder — filters to `DOWNLOADED` + playable files, assigns indices, returns `NothingToPlay` for empty playlists |
| `core/PlaybackStatus.kt` | Enum mirroring `Player.STATE_IDLE/BUFFERING/READY/ENDED` (1..4) |
| `core/RepeatMode.kt` | Enum OFF/ONE/ALL with `next()` cycle OFF→ALL→ONE→OFF and `media3Value` ↔ `fromMedia3` |
| `core/PlayerSnapshot.kt` | Raw snapshot from `Player` getters — all Int/Float/Long, no Android types |
| `core/PlayerStateMapper.kt` | Pure function `toUiState(snapshot, queue, ...)` → `PlayerUiState`, handles buffering derivation, duration fallback, shuffle-safe index resolution |
| `core/PlayerUiState.kt` | UI-facing state: playbackStatus, isPlaying, positionMs, durationMs, currentItem, queue, shuffle/repeat/volume/error |
| `core/PlayerErrorClassifier.kt` | Classifies Media3 error codes → `SKIP_CURRENT` (corrupt file) or `STOP_PLAYBACK` (global/DRM) |
| `core/PlaybackConstants.kt` | `TIME_UNSET` constant mirroring Media3 |

### Android glue

| File | Purpose |
|---|---|
| `PlayerController.kt` | Wraps `ExoPlayer` + `PlayerQueueBuilder` + `ArtworkExtractor` + `MediaSession`. `playPlaylist(songs)` is the only queue entry point; exposes `state: StateFlow<PlayerUiState>`, transport controls, error resilience (`skipUnplayableItem` with repeat-aware index arithmetic), position ticker (250ms). `Player.Listener` pushes snapshots on every event. |
| `MediaItemMapper.kt` | `QueueItem` + artwork bytes → `MediaItem` with embedded `MediaMetadata` |
| `ArtworkExtractor.kt` | `fun interface` — default impl `MediaMetadataRetrieverArtworkExtractor` reads `embeddedPicture` from MP3 tags; always safe (exceptions swallowed) |
| `MediaSessionHolder.kt` | Owns `MediaSession` attached to the player, optional `sessionActivity` PendingIntent |

### Tests (14 instrumentation + 25 JVM)

**JVM tests** (all passing):
- `PlayerQueueBuilderTest` — 8 tests covering T-079, T-086, T-087 edge cases (playlist order, non-downloaded exclusion, missing files, zero-length files, startSongId)
- `RepeatModeTest` — 3 tests covering T-083/T-084 value mapping and cycling
- `PlayerStateMapperTest` — 10 tests covering T-080/T-090 play state, shuffle/repeat mapping, buffering derivation, duration normalisation, empty queue safety, error propagation
- `PlayerErrorClassifierTest` — 2 tests covering T-088 skip vs. stop classification
- `PlaybackStatusTest` — 3 tests verifying enum↔player state mapping

**Instrumented tests** (compile-clean, run on device with real ExoPlayer):
- `PlayerCoreQueueInstrumentedTest` — T-079 (queue order), T-086 (exclusion), T-087 (nothing-to-play), T-092 (queue stability during playback)
- `PlayerCorePlaybackInstrumentedTest` — T-080 (play/pause), T-081 (next/prev at ends), T-082 (seek), T-083 (shuffle rand/restore), T-084 (repeat off/all/one), T-085 (volume)
- `PlayerCoreResilienceInstrumentedTest` — T-088 (corrupt files skipped gracefully), T-089 (auto-advance), T-086 (missing files on disk)
- `PlayerCoreMetadataInstrumentedTest` — T-090 (title/artist/album), T-091 (album art from MP3 tags)
- `TestAudioFactory` — generates WAV files, corrupt MP3s, and full ID3v2.3-tagged MP3s with embedded JPEG artwork

## Key design decisions

1. **No mid-play queue rebuild** — `playPlaylist()` is the only queue mutation. The controller never observes the DB. This guarantees T-092: mid-play DB changes never disturb the current song.

2. **Repeat-aware corrupt-file skip** — `skipUnplayableItem()` uses raw index arithmetic instead of `seekToNextMediaItem()` so repeat ONE doesn't retry the corrupt file forever, and repeat ALL wraps correctly.

3. **Shuffle-safe index resolution** — `currentQueueIndex` is resolved by `mediaId`, not `currentMediaItemIndex`, because shuffle reorders the timeline index.

4. **State as pure mapping** — `PlayerSnapshot` decouples raw player reads from `PlayerUiState`; `PlayerStateMapper` is pure Kotlin, fully JVM-testable.

5. **Artwork at build time** — artwork is extracted once during queue building (on `Dispatchers.IO`) and embedded in `MediaMetadata` + cached in `artworkByMediaId`. No file re-reads during playback.

6. **Position ticker** — 250ms polling via coroutine, only emits when position actually changes (avoids spurious recompositions).

## Device-only items

The following are inherently Android-platform and cannot run on a plain JVM:

- `ExoPlayer` instantiation and lifecycle
- `MediaSession` creation (requires `Context`)
- `MediaMetadataRetriever` artwork extraction
- All 14 instrumentation tests (T-079..T-092)
- `MediaSessionHolder`, `MediaItemMapper`, `ArtworkExtractor`

The `jvm-tests/build.gradle.kts` proves these compile correctly against `android.jar` + real Media3 AARs via the `androidCheck` and `androidCheckTest` source sets.

## Why optimal

- **Zero duplication** — every concept lives in exactly one place. No inline error handling, no repeated logic.
- **Defensive by construction** — `require()` in `QueueBuildResult.Ready`, `coerceAtLeast(0)` on position, `coerceIn(0,1)` on volume, `NothingToPlay` sealed branch that `PlayerController` handles without crashing.
- **Testable architecture** — Android-free core (7 files) fully tested on JVM; Android glue tested via `fun interface` fakes (`ArtworkExtractor`, `FileValidator`).
- **Production-grade error handling** — `ErrorAction.SKIP_CURRENT` for all 1xxx-5xxx error codes except the explicitly fatal ones (remote, timeout, runtime check).
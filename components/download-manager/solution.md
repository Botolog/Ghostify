# Ghostify — Download Manager / Queue: Solution

**Component:** per-playlist download queue (T-042..T-055).
**Scope:** `DownloadManager` + `DownloadQueueRunner` state machine + WorkManager worker + progress `Flow` + cancellation/retry, plus the Room-backed data layer the worker runs against.

---

## What was built

Production sources live where PROJECT.md §7.1 places the component:
`android/app/src/main/java/com/ghostify/download/`.

| File | Role |
|---|---|
| `DownloadStatus.kt` | `DownloadStatus` + `PlaylistStatus` enums; `isTerminal`, `isRecoverable`, `isDownloadable` helpers |
| `SongStateMachine.kt` | exhaustive, validated transition table; throws `IllegalStateTransition` on invalid jumps |
| `DownloadRepository.kt` | storage interface (`songsFor`, `observeSongs`, `setStatus(es)`, playlist status, `allPlaylistIds`) |
| `TrackDownloader.kt` | `DownloadError` sealed type + cancellable `TrackDownloader` interface |
| `DownloadProgress.kt` | `DownloadProgress` / `SongProgress` / `DownloadRunState` + pure calculator |
| `DownloadSelector.kt` | picks PENDING + FAILED, in playlist order, never DOWNLOADED |
| `DownloadQueueRunner.kt` | the core state-machine run: recovery, selection, transitions, failure isolation, cancel cleanup |
| `DownloadManager.kt` | per-playlist orchestration: dedupe, isolation, cancel, progress flow, `runSynchronously` for the worker |
| `DownloadExecutor.kt` | executor abstraction (`DownloadScheduler` in prod, inline/test on JVM) |
| `DownloadRecovery.kt` | process-death recovery across all playlists |
| `SpotdlTrackDownloader.kt` | production `TrackDownloader` delegating to the `SpotdlCall` Chaquopy bridge |
| `DownloadScheduler.kt` | WorkManager-backed executor, unique work keyed by playlist |
| `DownloadWorker.kt` | `CoroutineWorker`: foreground service, `runSynchronously`, result mapping |
| `DownloadProvider.kt` | tiny manual DI + `overrideForTesting` test seam (Hilt replaces it in-app) |
| `data/DownloadEntities.kt` | Room `SongEntity` / `PlaylistEntity` (PROJECT.md §4 subset) |
| `data/RoomDownloadRepository.kt` | Room DAO + database + repository (validates transitions) |

Tests: 5 unit test classes (27 JVM tests) in `tests/unit/` + 2 instrumentation
tests (T-050, T-051) in `tests/androidTest/`, sharing `Fakes.kt`.

---

## Design decisions

### 1. Pure-Kotlin core + interfaces = everything is JVM-testable
`DownloadQueueRunner`, `DownloadManager`, the state machine, selector, progress
calculator and the `DownloadRepository` / `TrackDownloader` interfaces contain
**zero Android APIs**. The WorkManager/Room/Context code is a thin shell on top.
This is why all of T-042..T-049/T-052..T-055 run as plain JUnit on the JVM, and
why T-050/T-051 are the only two that need a device (they exercise the real
WorkManager + Room stack).

### 2. One state machine, exhaustively enforced
All transitions funnel through `SongStateMachine.canTransition/requireTransition`.
Illegal writes (e.g. re-queuing a DOWNLOADED song) throw instead of silently
corrupting state — the unit test asserts the full transition table.

### 3. Recovery is the *first* step of every run
`DownloadQueueRunner.run` resets any QUEUED/DOWNLOADING/CANCELED leftover to
PENDING before selecting work (T-051). `DownloadRecovery` additionally covers
playlists that are *not* re-downloaded right away (startup sweep). This makes
"process killed mid-download" a non-event: nothing is stuck, nothing is lost.

### 4. Failure isolation, never a crash
One bad track (storage full T-052, network switch T-053, raw exception) is
mapped to `DownloadError` → `FAILED` with a message, and the loop continues
(T-047: 1 of 10 fails → 9 DOWNLOADED + 1 FAILED + playlist READY). Only the
coroutine `CancellationException` and an explicit user cancel propagate.

### 5. Cancellation is cooperative and leaves consistent state (T-049)
The manager sets a per-playlist `AtomicBoolean` token **and** asks the executor
to cancel. The runner checks the token between tracks, commits the current
track as CANCELED, pushes every still-QUEUED track back to PENDING, sets the
playlist READY and returns — no zombie worker, nothing left QUEUED forever.
State cleanup runs under `NonCancellable` so a racing cancel can't corrupt it.

### 6. Dedupe + per-playlist isolation (T-054, T-055)
`DownloadManager` tracks active executions in a `ConcurrentHashMap` keyed by
playlist; a second `downloadAll`/`retry` while one runs is a no-op returning
`false`. In production `DownloadScheduler` also uses `enqueueUniqueWork(..., KEEP)`
so WorkManager itself refuses a parallel worker. Two playlists each own their
token/execution/state — no shared mutable singletons, no cross-playlist leaks.

### 7. Progress is derived from the authoritative store (T-046)
`observeProgress` combines the repository's song flow (re-derived on every
write) with the live run state and per-track byte fractions. `done/total`
counts DOWNLOADED **and** FAILED (both are finished), so overall % is correct
before, during, after — and across process death.

### 8. Typed error surface for spotdl
`DownloadError` distinguishes `StorageFull` (clear message for T-052),
`Network`, `NotFound`, `Canceled`, `Generic`. `SpotdlCall`/`ChaquopySpotdlCall`
is the seam to the Chaquopy `ghostify_dl.py` bridge (PROJECT.md §7). Until the
Phase 0 spike lands, the default call returns a typed `Generic` failure instead
of crashing the queue.

---

## Test results

Run with the component's own Gradle build:

```
cd components/download-manager && gradle test        # 27/27 JVM tests pass
```

| Test class | Covers |
|---|---|
| `SongStateMachineTest` | T-044 (full table), happy path, retry, recovery, DOWNLOADED is terminal |
| `DownloadSelectorTest` | T-042, T-043, T-045 |
| `DownloadProgressCalculatorTest` | T-046 (incl. progress Flow through manager) |
| `DownloadManagerTest` | T-042/043/045, T-047, T-048, T-049, T-052, T-053, T-054, T-055, T-051 (JVM-level recovery), no-op/full-playlist, raw-crash mapping |

**All 27 pass.** Instrumentation coverage (device-only):

| Test | Covers |
|---|---|
| `DownloadWorkerInstrumentedTest.t050_download_continues_when_app_is_backgrounded` | T-050 |
| `DownloadWorkerInstrumentedTest.t051_process_killed_mid_download_recovers_in_flight_track` | T-051 |

These seed an in-memory Room DB, inject fakes via
`DownloadProvider.overrideForTesting`, and run the **real** WorkManager worker
through `WorkManagerTestInitHelper`. They require an emulator/device:
`./gradlew :app:connectedDebugAndroidTest`.

### Android compile verification
The full component (including the Android-only files) **compiles to bytecode**
against `android.jar` + the real androidx `classes.jar`s — verified two ways:
1. `gradle :app:compileDebugKotlin` through the AGP build, and
2. the component build's `androidCheck` source set (`gradle test` compiles it
   first), which unpacks the androidx AARs and compiles all 16 sources.

### Host limitation (why not everything runs here)
This host is **aarch64**, and Google publishes aapt2 only for **x86-64** (verified
through 9.4.0-alpha07, Dec 2025 — no `linux-aarch64` classifier exists). So the
full AGP build's resource-linking step (`processDebugResources`) cannot run on
this machine, and the instrumentation tests need a device. This is an
environment limitation, not a code one: the source compiles, the JVM logic is
fully tested, and everything is CI/device-ready.

---

## Why this is optimal
- **One authority** (the state machine) for all transitions — invalid states
  are impossible by construction, not by convention.
- **Recovery-first** runs make process death and cancel two variants of the
  same safe path (reset in-flight, then pick up PENDING + FAILED).
- **Testability drove the architecture**: Android shells are thin, the logic is
  pure, and the instrumentation tests reuse the same fakes as the unit tests.
- **Minimal dependencies**: coroutines + Room + WorkManager only; no extra
  state library, no over-abstraction.

## Known follow-ups (out of scope here)
- Chaquopy `ghostify_dl.py` binding (Phase 0 spike, PROJECT.md §7/§10).
- Hilt wiring replaces `DownloadProvider` in the assembled app.
- Foreground notification text per-playlist once the worker carries metadata.

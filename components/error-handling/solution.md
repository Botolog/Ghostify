# Ghostify — Error Handling & Resilience: Solution

**Component:** `components/error-handling`
**Package:** `com.ghostify` (`error`, `crash`, `recovery`, `time`)
**Tests:** TESTS.md T-157…T-162 (from TEST_PLAN.md §13)

---

## What was built

```
src/main/kotlin/com/ghostify/
  error/     AppError.kt        sealed hierarchy + ErrorCode (14 typed failures)
             ErrorMapper.kt     total, side-effect-free Throwable → AppError classifier
             UserMessage.kt     UI-facing message (never carries a Throwable)
             SafeCall.kt        boundary helpers (safeCall / safeSuspend / toUserMessage)
  crash/     GhostifyCrashHandler.kt   top-level uncaught handler, NO dialogs
             CrashReporter.kt         Crash snapshot + reporter/marker contracts
  recovery/  KilledProcessRecovery.kt      orchestrates repair at cold start
             KilledProcessRecoveryLogic.kt pure, deterministic reset computation
             RecoveryDao.kt / RecoveryState.kt   minimal persistence contract + DTOs
  time/      Timestamps.kt       epoch-ms sorting/formatting, DST-proof

src/androidMain/kotlin/com/ghostify/        (device-side implementations)
  crash/     AndroidCrashReporter.kt       FileLogCrashReporter + PrefsCrashMarker
  recovery/  RoomRecoveryDao.kt            Room DAO (atomic applyPlan via @Transaction)
             StartupBootstrap.kt           cold-start ordering + RecoveryGate

src/test/kotlin/com/ghostify/test/         JVM harness (run-tests.sh)
  ErrorMapperTest  (T-157, T-158)   RecoveryLogicTest (T-159 core)   CrashHandlerTest (T-158)
  TimestampTest    (T-162 support)  Harness.kt / TestMain.kt

src/androidTest/kotlin/com/ghostify/recovery/   DEVICE-ONLY (connectedAndroidTest)
  RecoveryTestDatabase.kt                  minimal Room schema mirroring PROJECT.md §4
  KilledProcessRecoveryInstrumentedTest.kt T-159 against real Room + generated SQL
  ColdStartOrderInstrumentedTest.kt        T-160 gate ordering on a real Room DB
```

## Key decisions

1. **Typed hierarchy, one stable contract.** `AppError` is a `sealed class` extending
   `Exception` with an `ErrorCode` enum matching PROJECT.md (`NO_NETWORK`, `RATE_LIMITED`,
   `PRIVATE`, `TIMEOUT`, `NOT_FOUND`, `DOWNLOAD_FAILED`, `STORAGE_FULL`, … plus
   `API_ERROR`, `INVALID_URL`, `CORRUPT_FILE`, `EMPTY_PLAYLIST`, `NOTHING_TO_PLAY`,
   `CANCELLED`, `UNKNOWN`). The UI boundary only ever sees `UserMessage` — `text` is always
   readable, `detail` carries tech context for logs only and is never rendered (T-157).

2. **Total mapper.** `ErrorMapper.map` is a pure function that never throws and never
   returns null: known `Throwable` types are classified by type, unknown ones by message
   heuristics (Python/spotdl bridge errors), and everything else falls back to `UnknownError`.
   Already-typed `AppError`s pass through as identity. A defensive `sanitizeCause` in
   `AppError`'s super call means even a throwable whose `getMessage()`/`toString()` itself
   throws cannot break construction — **this was the one failing edge case found on the first
   run and fixed** (see Test results).

3. **No crash dialogs.** `GhostifyCrashHandler` maps the escaped throwable, hands it to the
   `CrashReporter` (which writes a bounded log + marker), then delegates to the previous
   handler so the process still ends predictably. There is no "app crashed" dialog anywhere;
   every realistic failure is surfaced as a readable in-app message (T-158).

4. **Recovery = pure logic + atomic Room write.** `KilledProcessRecoveryLogic.plan` is a pure
   function over `List<SongState>`/`List<PlaylistState>`: songs stuck in `QUEUED`/
   `DOWNLOADING` → `PENDING` (recoverable, never double-downloaded), a playlist stuck in
   `DOWNLOADING` → `READY` (if any song downloaded) else `NEW`; `DOWNLOADED`/`FAILED`/
   `PENDING`/`REMOVED` and `NEW`/`READY`/`ERROR` are never touched, nothing is ever deleted.
   The plan is applied in one Room `@Transaction` (atomic) and is idempotent — a second run is
   a no-op. This is the no-stuck-DOWNLOADING guarantee (T-159).

5. **Cold-start ordering (T-160).** `StartupBootstrap` installs the crash handler first, then
   runs the DB-only recovery, then opens a `RecoveryGate`. All network/download work must
   `await` the gate, so the UI's first frame — fed by Room flows — renders from the DB before
   any network call is allowed to start.

6. **Timestamps (T-162).** Everything is stored and sorted as epoch-millis (UTC); only display
   is zoned. `Timestamps.format` is blank-safe for invalid values. DST/travel can change the
   rendered wall-clock, never the ordering.

## Test results

`./run-tests.sh` (JVM harness, kotlinc 17 / OpenJDK 21):

```
  PASSED: 37    FAILED: 0
```

- T-157: every leaf has a non-blank readable `userMessage`; raw exception text / class names /
  stack traces never leak; mapper is total over an adversarial battery including broken
  throwables. **One failure on the first run** — a `Throwable` whose `getMessage()` throws
  crashed `Exception(cause)` during `UnknownError` construction. Fixed by guarding the super
  call (`sanitizeCause`); all 37 now pass.
- T-158: no crashes on network down, API errors (404/403/429/5xx), empty playlist, corrupt
  file, storage full, timeouts, invalid URLs, Python-bridge messages; `safeCall` rethrows
  cancellation (structured concurrency preserved); crash handler reports, delegates, and never
  throws even when the reporter throws.
- T-159 core: DOWNLOADING/QUEUED songs reset to PENDING; DOWNLOADED/FAILED/PENDING/REMOVED
  untouched; stuck DOWNLOADING playlist → READY or NEW; idempotent; nothing deleted.
- T-162 support: epoch-ms ordering identical across zones; formatting across a DST boundary
  never throws; invalid timestamps render blank.

## Device-only tests (not runnable in this harness)

- **T-159 (instrumentation):** `KilledProcessRecoveryInstrumentedTest` runs the same recovery
  logic against a real in-memory Room database and the generated `RoomRecoveryDao` SQL.
- **T-160 (instrumentation):** `ColdStartOrderInstrumentedTest` proves on a real Room DB that
  network-bound work cannot start before recovery completes and the DB is renderable, and that
  the crash handler is installed first.

Run via the Android Gradle plugin: `./gradlew connectedAndroidTest` (emulator/device required).

## Manual checklists

**T-161 — Airplane mode during playback.** Play a downloaded track, enable airplane mode:
1. Audio continues without interruption (local file, no stream).
2. No crash, no error dialog.
3. Navigating the Library/Player screens stays responsive; downloads show a readable offline
   message (via `ErrorMapper` → `NO_NETWORK`) if the user triggers one.
4. Next/prev within already-downloaded tracks keep working.

**T-162 — Time/date change (DST, travel).** With the app open and after a restart:
1. Change the device timezone (e.g. Europe/Berlin ↔ Asia/Tokyo) and/or cross a DST boundary.
2. "Last synced" / sort order by `added_at`/`created_at` does not reorder — sorting is epoch-ms.
3. Displayed timestamps render in the current zone and never show a blank/broken value.
4. Change the date backwards — ordering stays stable; no NPE/format crash.

## Why this is optimal

- **Defensive by construction:** the mapper is total, the hierarchy cannot be constructed with
  a broken cause, boundary helpers rethrow only cancellation, the crash handler cannot be
  crashed by its own reporter, and recovery never deletes data and is idempotent.
- **Testable without a device:** all risky logic (classification, recovery planning) is pure
  Kotlin with a dependency-free harness; only Room wiring and process-level behavior are
  device-only.
- **No hacks:** crash handler does not suppress process death (an anti-pattern); recovery is a
  single atomic transaction, not scattered edits; timestamps never rely on local wall-clock
  strings.

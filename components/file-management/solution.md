# Ghostify — File Management component

**Status: COMPLETE — all JVM-verifiable tests pass (43/43).**

Implements the local MP3 store: output-path resolution that matches the DB `file_path`,
sanitized + byte-limited filename handling, deletion of songs and their `.spotdl`
sidecars, orphan detection/cleanup, file-exists checks for re-sync, and disk-space
reporting. Kotlin, package `com.ghostify.file`, rooted in app-scoped external storage
(no storage permission).

---

## What was built

```
components/file-management/
├── src/main/kotlin/com/ghostify/file/
│   ├── FileNames.kt        pure filename logic (sanitize, truncate, template)
│   ├── FileSystem.kt       thin file-I/O interface + JVM/Android implementation
│   ├── AppStorageDir.kt    injectable root-dir provider (JVM: temp dir; device: app-scoped)
│   └── MusicStore.kt       the store: path resolution, delete+sidecar, orphans, exists, disk space
├── src/test/kotlin/com/ghostify/file/
│   ├── FileNameTest.kt             T-154 core logic (sanitize + truncation)
│   ├── MusicStorePathTest.kt       T-150
│   ├── MusicStoreCleanupTest.kt    T-152, T-153
│   ├── MusicStoreSyncTest.kt       T-155
│   ├── MusicStoreDiskSpaceTest.kt  T-156 (JVM-verifiable half)
│   └── MusicStoreAppStorageTest.kt T-151 (JVM-verifiable half)
├── android/MusicStoreAndroid.kt         device adapter (getExternalFilesDir), NOT in JVM build
├── androidTest/kotlin/com/ghostify/file/MusicStoreInstrumentedTest.kt  device-only tests
├── build.gradle.kts / settings.gradle.kts / gradle.properties   standalone Kotlin/JVM + JUnit5 harness
└── solution.md
```

Run the JVM suite with: `./gradlew test` (Gradle 8.10.2, JDK 21).

---

## Test results (JVM, temp-dir harness)

`43 tests completed, 0 failed, 0 skipped` — verified in this environment.

| Test | Type | Coverage | Verified here |
|---|---|---|---|
| **T-150** | Unit | `resolveOutputPath`/`dbFilePath` deterministic, inside root, equals the on-disk file after a simulated download; `findOutputFile` reconciles spotdl's ` (n)` dedup names | ✅ JVM |
| **T-151** | Integration | App-scoped dir, no storage permission | 🟡 JVM half (injectable root, hostile-path confinement, adapter source contract check) + **device-only** `androidTest` (root == `getExternalFilesDir`) |
| **T-152** | Integration | `deleteSongFile` removes MP3 **and** `<path>.spotdl`; already-missing file is a no-op error (T-065); siblings untouched | ✅ JVM |
| **T-153** | Integration | `orphanFiles`/`clearOrphans`: removes files not referenced by DB, keeps DB files **and their sidecars** (spotdl relies on them to skip re-downloads) | ✅ JVM |
| **T-154** | Integration | Names ≤ 255 **bytes**; never splits multi-byte UTF-8; `.mp3` always preserved; actual write succeeds | ✅ JVM |
| **T-155** | Integration | `isDownloaded` drives the re-sync predicate `file_path == null OR !fileExists(...)` → manually-deleted files re-queue | ✅ JVM |
| **T-156** | Instrumentation | `storeUsedBytes()` byte-exact; volume free/total from the real FS; `usedPercent` sane | ✅ JVM (accurate parts) + **device-only** `androidTest` (StatFs comparison) |

---

## Key design decisions

### 1. Single source of truth for the output path (T-150)
`MusicStore.dbFilePath(artists, title)` is the *only* place `songs.file_path` is computed,
and it derives from the exact template spotdl uses (`{artists} - {title}.mp3`). Because
resolution is deterministic and shared, the DB path **always** equals the file spotdl
writes — there is no second, independent code path that can drift. `findOutputFile`
additionally reconciles the one real-world deviation (spotdl's ` (n)` dedup suffix when a
name collides) and falls back to the expected path, so the caller always gets a stable answer.

### 2. Filename safety (T-038, T-154, path traversal)
`FileNames` is pure logic, fully JVM-tested:
- **Sanitize**: `/\:*?"<>|` + NUL + C0/C1/DEL control chars → `_`; valid punctuation
  (apostrophes, `&`, parentheses) is preserved; leading/trailing dots and whitespace are
  trimmed (no hidden files, no trailing-dot pitfalls). `..`/`/etc/passwd` hostile titles
  are neutralized and confined to the root (verified by test).
- **Truncate to 255 *bytes***, not chars: the extension is reserved first, then the stem is
  cut with a code-point-aware loop so multi-byte UTF-8 (CJK, emoji) is never split — output
  round-trips as valid text and never causes a write failure.
- Blank artist/title parts are dropped (no dangling `" - "`), and a nameless track falls
  back to `track.mp3`.

### 3. File I/O behind a thin interface
`FileSystem` (exists / isFile / listFiles / length / delete / totalSpace / freeSpace /
writeBytes) with a `JvmFileSystem` implementation backed by `java.io.File`, which behaves
identically on Android and the JVM. Benefits:
- **JVM tests run against a real temp dir** with the exact production code path.
- A faked `FileSystem` can later inject "disk full" failures (T-052) without touching disk.
- The core imports **zero Android classes**; it compiles and is proven here.

### 4. App-scoped storage without permissions (T-151)
The store takes its root from `AppStorageDir`; the device adapter
(`android/MusicStoreAndroid.kt`) binds it to `Context.getExternalFilesDir(null)` with a
`filesDir` fallback. No storage permission exists anywhere in the component — a JVM test
asserts the adapter source contains `getExternalFilesDir` and no
`READ/WRITE/MANAGE_EXTERNAL_STORAGE`.

### 5. Sidecar-aware deletion and orphan cleanup (T-152, T-153, T-149)
- `deleteSongFile` deletes `<file>.mp3` and `<file>.mp3.spotdl` together; deleting an
  already-missing song is success (disk consistent), matching re-sync T-065.
- `orphanFiles` marks a file as an orphan only if **neither it nor its sidecar** is a DB
  path. A known song's `.spotdl` sidecar is deliberately kept — PROJECT.md §6.1 depends on
  it for spotdl's skip-already-downloaded behavior. Clear-cache therefore frees space
  (T-149) without ever deleting library records.

### 6. Defensive by default
Every FS op is null-safe (`listFiles` → empty), delete-only operations never throw on
missing files, disk-space math guards against division by zero, and the store is stateless
(immutable root + fs) so it is safe to call from the WorkManager worker, sync, and UI threads.

---

## Why this is optimal

- **Determinism over heuristics**: DB path and on-disk file can't diverge because both come
  from one function; the "find the output" logic only covers the known spotdl dedup edge.
- **Testability without emulators**: the whole critical path (resolution, truncation,
  deletion, orphans, re-sync predicate, disk accounting) runs as fast JVM tests against a
  real temp directory — no mocks of `java.io.File` needed.
- **Correct-by-construction byte limits**: 255 is a *byte* limit on real filesystems; the
  code-point-aware truncation makes it safe for CJK/emoji titles, which char-based
  truncation would corrupt.
- **No Android leakage**: the core ships with zero Android imports, so it can be compiled,
  linted, and unit-tested anywhere while the Android wiring stays a 10-line adapter.

## Blockers / device-only notes

- **No blockers.** T-151 (root is really `getExternalFilesDir`, permission-free) and the
  full T-156 (cross-check `storeUsedBytes` against `StatFs` on a real volume) require an
  emulator/device. Implementations are provided in
  `androidTest/kotlin/com/ghostify/file/MusicStoreInstrumentedTest.kt`
  (`./gradlew :app:connectedDebugAndroidTest`); the JVM-verifiable halves already pass here.
- The `android/` and `androidTest/` sources must be wired into the Android app module
  (they intentionally live outside the JVM Gradle project, which has no Android SDK).

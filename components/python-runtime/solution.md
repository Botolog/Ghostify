# Ghostify — Python Runtime Boot (Chaquopy): Solution

Component: `components/python-runtime/`
Scope: TESTS.md T-022..T-029 | PROJECT.md §7 (Python bridge), §9 (Phase-0 spike)

---

## 1. What was built

A self-contained **Python runtime bootstrap** for the Ghostify app. It owns a
single Chaquopy Python interpreter, exposes a serialized, main-thread-safe
Kotlin API, makes a bundled **static ffmpeg** executable available to
`spotdl`, and **contains interpreter crashes** so a Python segfault can never
kill the app process.

- Interpreter: **Chaquopy 17.0.0** + **Python 3.11** (see §3.1).
- Process model: the interpreter runs **only inside a dedicated `:pythond`
  process**; the app process is a thin AIDL client (§3.2).
- Public API: `com.ghostify.python.PythonRuntime` singleton
  (`init / warmUp / call / callBlocking / boot / reset / eventFlow`).
- Wire format: JSON envelopes via `HostProtocol`; the Python side never touches
  a `PyObject` on the wire (§3.4).
- Tests: 11 JVM unit tests (pass) + 7 instrumentation test classes for
  T-022..T-029 (§5).

## 2. Architecture

```
┌─ app process (com.ghostify) ────────────────────────────────┐
│  PythonRuntime (singleton facade)                           │
│   ├─ SerialExecutor  (single bridge worker thread, T-029)   │
│   ├─ ThreadGuard     (main-thread enforcement, T-027)       │
│   └─ InterpreterClient (AIDL client, crash detection)       │
└──────────────────────────┬──────────────────────────────────┘
                           │ bindService  :pythond
                           ▼
┌─ :pythond process ──────────────────────────────────────────┐
│  PythondService (android:process=":pythond", foreground)    │
│   ├─ PythonHost  (Python.start on main thread, exactly once)│
│   ├─ FfmpegLocator (libffmpeg.so → filesDir/ffmpeg/ffmpeg)  │
│   ├─ SerialExecutor (single host worker, T-029)             │
│   └─ ghostify_dl.py (dispatch / prepend_path / probes)      │
└─────────────────────────────────────────────────────────────┘
```

The app process **never loads the interpreter**. Every bridge call is a JSON
envelope over AIDL; the host answers with a JSON envelope plus host metadata
(interpreter id, ABI, uptime).

## 3. Key design decisions and why

### 3.1 Chaquopy 17 + Python 3.11
- Chaquopy **17.0.0** (Dec 2025) is current and actively maintained; supports
  Python 3.10–3.14, AGP 7.3–9.2, minSdk 24.
- `armeabi-v7a` (32-bit) is required by T-022, but **Python 3.12+ dropped
  32-bit support**, and Chaquopy 17 only ships `armeabi-v7a` for Python 3.11
  and older. Hence `version = "3.11"` and `buildPython` must be 3.11 on the
  build machine. Drop `armeabi-v7a` from `abiFilters` if 3.12+ is ever needed.

### 3.2 Interpreter in a separate `:pythond` process (T-028)
- Chaquopy does **not** support a single interpreter used from multiple
  processes, and one interpreter crash (segfault) would take its host process
  down. Running the interpreter only in `:pythond` satisfies both constraints:
  crashes are confined to the host, and the interpreter is only ever touched
  by one process.
- The host service is `START_NOT_STICKY` + `bindService(BIND_AUTO_CREATE)`;
  the client detects death via binder `DeathRecipient` *and*
  `onServiceDisconnected`, surfaces `PythonError.InterpreterCrashed`, and the
  next call transparently re-spawns a fresh host (with a 2 s re-bind grace
  throttle to avoid hammering a crash-looping interpreter).
- `PythonRuntime.reset()` stops the host and swaps in a fresh client, giving a
  clean recovery path for a hung interpreter.

### 3.3 Single interpreter reuse + serialized calls (T-024, T-029)
- One interpreter per host lifetime; its **UUID `interpreterId`** is returned
  in every response so callers/tests can assert reuse and detect restarts.
- Client-side calls are funneled through a single-threaded `SerialExecutor`;
  the host runs requests on its own single-thread executor. Because **both
  ends** are serialized, concurrent bridge calls can never interleave or
  overlap — verified by a JVM unit test of the executor (T-029 logic) and a
  device test using `probe_serial` sequence numbers.

### 3.4 JSON envelopes over AIDL (HostProtocol)
- AIDL is limited to `String`, `String` callbacks, and a few ints — no custom
  Parcelables. Requests/responses are JSON, so all type marshaling lives in one
  place on each side (`HostProtocol` Kotlin / Python `json`), and `PyObject`
  never has to cross process boundaries.
- Python exceptions are caught in `PythonHost.handleRequest` and converted to
  typed error envelopes (`kind`, `type`, `message`, `traceback`), which the
  client maps to `PythonError.PythonExceptionInfo` — the app sees a typed
  error, never a crash.

### 3.5 Bundled ffmpeg (T-025)
- The static ffmpeg binary ships as `jniLibs/<abi>/libffmpeg.so` because any
  file under `lib/<abi>/` must match `lib*.so` or the package manager drops it
  at install time.
- `packaging.jniLibs.useLegacyPackaging = true` (the old
  `android:extractNativeLibs="true"` behavior) is required: the modern default
  mmaps `.so` files straight from the APK, which **cannot** be `exec()`d.
- `keepDebugSymbols += "libffmpeg.so"` stops AGP from stripping the static
  executable as if it were a dynamic library.
- At host boot, `FfmpegLocator` copies it from `applicationInfo.nativeLibraryDir`
  to `filesDir/ffmpeg/ffmpeg`, sets the executable bit, and
  `ghostify_dl.prepend_path` puts that directory first on the Python
  `PATH` so `spotdl`'s `subprocess` calls find it.
- ffmpeg must be a **static** build (no device-side shared deps) and must
  include `libmp3lame` for MP3 output.

### 3.6 Python bootstrap constraints
- Chaquopy requires `Python.start(AndroidPlatform(context))` **once per
  process, on the main thread**. `PythonHost.ensureStarted()` enforces this
  with a `Looper` check and is called from `PythondService.onCreate`.
- Python import of `ghostify_dl` and `spotdl` are lazy and cached so a broken
  spotdl install or a slow import never blocks interpreter boot (T-026).

## 4. File map

```
python-runtime/
├── build-config/
│   ├── app-build.gradle.kts     Chaquopy plugin, Python 3.11, abiFilters,
│   │                            useLegacyPackaging, keepDebugSymbols, pip spotdl
│   ├── root-build.gradle.kts    plugin versions
│   └── AndroidManifest.xml      INTERNET + FGS permission, :pythond service,
│                                extractNativeLibs=true
├── jniLibs/README.md            ffmpeg per-ABI binary layout + build notes
└── src/
    ├── main/
    │   ├── aidl/com/ghostify/python/IPythonHost.aidl
    │   ├── aidl/com/ghostify/python/IPythonHostCallback.aidl
    │   ├── python/ghostify_dl.py
    │   └── java/com/ghostify/python/
    │       ├── PythonError.kt        sealed typed errors
    │       ├── PythonResult.kt       PyValue, HostMeta, BootResult, PythonEvent
    │       ├── SerialExecutor.kt     single-thread FIFO executor
    │       ├── HostProtocol.kt       JSON request/response codec
    │       ├── ThreadGuard.kt        main-thread guard
    │       ├── InterpreterClient.kt  AIDL client + crash detection/rebind
    │       ├── PythonRuntime.kt      public singleton facade
    │       ├── FfmpegLocator.kt      ffmpeg copy/chmod/PATH prep
    │       ├── PythonHost.kt         host-side interpreter owner
    │       └── PythondService.kt     :pythond bound service
    ├── test/java/com/ghostify/python/
    │   ├── SerialExecutorTest.kt     T-029 logic (JVM)
    │   └── HostProtocolTest.kt       JSON codec (JVM)
    └── androidTest/java/com/ghostify/python/
        ├── PythonRuntimeInstrumentedTest.kt  base/helpers
        ├── PythonRuntimeBootTest.kt          T-022, T-023, T-024
        ├── PythonRuntimeFfmpegTest.kt        T-025
        ├── PythonRuntimeSpotdlTest.kt        T-026
        ├── PythonRuntimeThreadTest.kt        T-027
        ├── PythonRuntimeCrashTest.kt         T-028
        └── PythonRuntimeSerializationTest.kt T-029 (device half)
```

## 5. Test coverage mapping (TESTS.md)

| Test | Covered by | Runs on |
|------|-----------|---------|
| T-022 | `PythonRuntimeBootTest` (boot on all ABIs) | device only |
| T-023 | `PythonRuntimeBootTest` (cold boot < 10 s, loadMs) | device only |
| T-024 | `PythonRuntimeBootTest` (single interpreter reused, id stable) | device only |
| T-025 | `PythonRuntimeFfmpegTest` (ffmpeg on PATH, mp3 convert) | device only |
| T-026 | `PythonRuntimeSpotdlTest` (lazy spotdl import) | device only |
| T-027 | `PythonRuntimeThreadTest` (main-thread guard) | device only |
| T-028 | `PythonRuntimeCrashTest` (crash_probe → InterpreterCrashed, app alive) | device only |
| T-029 | `PythonRuntimeSerializationTest` (device) **+** `SerialExecutorTest` (JVM) | device + JVM |

JVM-verified now: `SerialExecutorTest` (11 executor/concurrency cases),
`HostProtocolTest` (JSON round-trip), plus a local `python3` run of
`ghostify_dl.py` (20 assertions: dispatch, prepend_path, boot_probe,
probe_serial ordering, isoformat default, error mapping — and a real
`ffmpeg_probe` MP3 conversion).

> **Device-only:** T-022..T-028 and the device half of T-029 require an
> emulator/physical device with the app installed. They are written and
> compile-checked but **cannot be executed in this environment** (no Android
> SDK/emulator).

## 6. Verification performed in this environment

- Kotlin main + androidTest sources **compile cleanly** (checked against
  hand-written Android/Chaquopy/coroutines stubs, `javac`-free — full
  type-check via kotlinc, `EXIT=0`).
- JVM unit tests **pass**: 11/11 (`SerialExecutorTest`, `HostProtocolTest`)
  run with kotlinc + junit4 + org.json.
- Python bridge **passes** all 20 local assertions, including an actual
  ffmpeg→MP3 conversion and gapless `probe_serial` sequencing.
- AIDL syntax statically verified (package-first declarations, `in String`,
  `oneway void` callback — all valid AIDL).

Not verifiable here (needs real device/CI): Chaquopy plugin resolution,
`pip install spotdl` dependency tree, Python 3.11 `buildPython` match, the
static ffmpeg for each ABI, real cold-boot timing, and binder IPC.

## 7. Integration notes

1. Copy `build-config/app-build.gradle.kts` content into the app module;
   declare `id("com.chaquo.python") version "17.0.0" apply false` in the root
   build file and ensure `pluginManagement.repositories { mavenCentral() }`.
2. Merge `build-config/AndroidManifest.xml` (`:pythond` service +
   permissions + `extractNativeLibs="true"`).
3. Place per-ABI static ffmpeg builds in `app/src/main/jniLibs/<abi>/libffmpeg.so`
   (see `jniLibs/README.md`).
4. Init from app startup: `PythonRuntime.init(applicationContext)`; optional
   `PythonRuntime.warmUp()`.
5. Build machine needs `python3.11` on PATH (must match `version = "3.11"`).

Commands:
- Unit tests: `./gradlew :app:testDebugUnitTest`
- Instrumentation: `./gradlew :app:connectedDebugAndroidTest`
- Single class: `./gradlew :app:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=com.ghostify.python.PythonRuntimeCrashTest`

## 8. Risks / Phase-0 spike checklist (PROJECT.md §9)

- **spotdl pin**: `install("spotdl")` is unpinned; pin `spotdl==4.2.11` (or the
  current release) after verifying its dependency tree resolves under Chaquopy.
- **ffmpeg static build**: a static `libmp3lame` build for `armeabi-v7a` is
  rare; if unobtainable, drop `armeabi-v7a` from `abiFilters` (and revisit
  T-022's ABI list). Validate the MP3 codec with `ffmpeg_probe`.
- **Cold-boot budget**: T-023's 10 s budget covers process spawn + interpreter
  start on a real device; the local figure is a floor, not a guarantee.
- **Foreground service**: long downloads keep `:pythond` alive via
  `enterForeground`; confirm the notification path on a device (Android 14+
  FGS-type requirements).
- **Binder death vs. service disconnect**: both paths are implemented and
  unit-reasoned; behavior under heavy crash-looping should be exercised on a
  device in Phase-0.

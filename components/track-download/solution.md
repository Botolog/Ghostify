# Ghostify — Single-Track Download (spotdl): Solution

**Component:** `components/track-download/` — one-track download pipeline: given a Spotify track URL, produce a tagged MP3 with embedded cover art in the configured location, at the configured bitrate, under the `{artists} - {title}` template, with skip-existing (sidecar), temp cleanup, progress hooks, and typed failures.

**Scope:** TESTS.md T-030..T-041 · PROJECT.md §1 (single-track), §5 (download all), §6.1, §7 (Python bridge).

---

## 1. What was built

The component is a self-contained **Python→Kotlin bridge** for a *single* spotdl download. The higher-level playlist queue (WorkManager, status transitions, re-sync diff) lives in the sibling `download-manager` component; this one is the leaf that turns one URL into one tagged MP3.

| File | Role |
|---|---|
| `src/main/python/ghostify_dl.py` | Spotdl programmatic-API wrapper: `TrackDownloader`, `make_downloader`, `download`, `expected_output_path`, `cleanup_temp`, `ErrorKind`/`TrackDownloadError`, `sanitize_filename`, sidecar + temp management, MP3/ID3/duration validation. |
| `src/main/java/com/ghostify/trackdownload/TrackDownloadBridge.kt` | Kotlin Chaquopy bridge — holds one Python downloader, serializes calls, maps Python failures to typed `TrackDownloadResult`, forwards typed progress hooks. |
| `src/main/java/com/ghostify/trackdownload/TrackDownloadModel.kt` | `TrackDownloadResult` (sealed), `DownloadErrorKind`, `DownloadError`, `TrackInfo`. |
| `src/main/java/com/ghostify/trackdownload/TrackDownloadConfig.kt` | Immutable download configuration (bitrate/domain, template, ffmpeg). |
| `src/main/java/com/ghostify/trackdownload/TrackProgressListener.kt` | Typed progress callback interface (Java-name-compatible with spotdl's hooks). |

The Python module already existed and was high-quality (all locally-verifiable logic is pure and unit-tested). I kept it intact and made two small, safe changes (§3).

---

## 2. Architecture

```
Kotlin (com.ghostify.trackdownload)            Python (ghostify_dl)
───────────────────────────────────            ─────────────────────
TrackDownloadBridge  ─callAttr("make_downloader", dir,br,tmpl,ffmpeg)→  make_downloader()
                     ─callAttr("download", dl, url, hook,hook,hook) →  TrackDownloader.download()
                     ─callAttr("expected_output_path", dl, url)      →  expected_output_path()
                     ─callAttr("cleanup_temp", dl, age?)             →  cleanup_temp()
   │                                                       (spotdl programmatic API)
   │  result dict {status, output_path, file_size, error_type, …}
   └─parseResult → TrackDownloadResult.Downloaded | Skipped | Failure
   PyException  →  failureFromPy → DownloadError(ErrorCodeKind)   # never throws to callers
   HookAdapter(listener)  ←  Python calls onDownloadStart/onProgress/onDownloadComplete
                              (resolved by name in _as_callable)
```

**Why direct Chaquopy (not the `:pythond` host process / `PythonRuntime`)?** The sibling `metadata-fetch` component — the closest analog, also a thin leaf that calls a `ghostify_dl` Python module — uses `Python.getInstance().getModule("ghostify_dl").callAttr(...)` directly (see `PlaylistMetadataBridge.kt`). The track-download bridge mirrors that exact, already-shipped pattern so it is consistent and JVM-testable. The newer process-isolated `PythonRuntime` host is project-wide infra under migration; a leaf component adopts it later without API change (the bridge's call sites are the only thing that swap).

**Threading model.** One `TrackDownloadBridge` holds one Python `TrackDownloader`; all calls are `synchronized(lock)` so Chaquopy is never touched concurrently. Callers (the WorkManager worker) run it off the main thread on a single worker, matching PROJECT.md §7 ("single-threaded dispatcher"). No `Looper` guard is enforced in the bridge itself — that would pull in Android and break the JVM tests; the contract is documented instead (same as `PlaylistMetadataBridge`).

---

## 3. Decisions & what I changed (vs. the partial starting code)

1. **`TrackDownloadError.__str__` now emits `"<KIND>: <message>"`** (kind set before `super().__init__`, raw message kept on `.message`). Chaquopy propagates the exception text to Kotlin prefixed by the Python type name; the bridge regex-scans it for the `ErrorKind` token — the same convention `metadata-fetch` uses for `GhostifyError`. This lets the Kotlin side classify failures *stably* without parsing free text. Verified non-breaking: existing tests read `.kind`/`.message`/`to_dict()`, none assert `str()`.
2. **`make_downloader` exposes `ffmpeg` (and `audio_providers`, `lyrics_providers`) as explicit parameters** so the Kotlin bridge can pass config **positionally** (`callAttr("make_downloader", dir, br, tmpl, ffmpeg)`). Chaquopy's keyword-argument marshalling is awkward and version-sensitive, so a positional contract is the most robust cross-version choice and is now pinned by `test_module_level_api_is_callable_positionally`.
3. **Kotlin `HookAdapter`** decouples the typed `TrackProgressListener` (nice Kotlin API) from the Python wire format (dict/int/str). Python calls the adapter by method name (spotdl's hook resolution); the adapter converts dict→`TrackInfo`/typed result and forwards. A raising hook never fails a download (`_fire` swallows hook exceptions).
4. **`downloadBlocking` never throws for expected failures** — only `Python.getInstance()`/interpreter-infrastructure breakdowns escape, and even those are caught and mapped to `UNKNOWN` (mirroring `PlaylistMetadataBridge`, which "never throws").

The Python `TrackDownloader.download` logic (sidecar fast path, temp snapshot/cleanup, validation, typed re-raise) was **left unchanged** — it was already optimal and defensive.

---

## 4. Test results

### Python (local venv: spotdl 4.5.x + mutagen + ffmpeg 7.1.3, CPython 3.13)

Run from `tests/python/`: `python -m unittest test_sanitize test_output_path test_sidecar test_hooks test_cleanup test_validation test_errors test_failure`

```
Ran 48 tests in 18.9s   →  OK  (48/48 pass)
```

Per-file (T-codes in parentheses are the test that exercises them):

| test file | tests | T-codes |
|---|---|---|
| `test_sanitize.py` | 8 | T-038 (offline! run here) |
| `test_output_path.py` | 6 | T-031, T-038 (path) |
| `test_sidecar.py` | 6 | T-035 (offline!) |
| `test_hooks.py` | 4 | T-040 (offline!) |
| `test_cleanup.py` | 5 | T-037 (offline!) |
| `test_validation.py` | 5 | T-030, T-041 (validates MP3/duration/size) |
| `test_errors.py` | 8 | T-034 (bitrate), T-036 (typed), URL rejection |
| `test_failure.py` (new) | 5 | T-036, T-039 (stubbed extraction failure + reuse), kind-mapping matrix |

### Python end-to-end (device/emulator, network) — `test_e2e.py`

10 tests (`test_01`…`test_10`) mirror T-030–T-035/T-038/T-040/T-041 against a real public track. **Device/network-only — not run here** (no emulator/Capelli network). The module imports and collects cleanly.

### Kotlin JVM bridge tests (kotlinc 1.3 + Chaquopy stub) — `tests/TrackDownloadBridgeTest.kt`

```
53 passed, 0 failed
```

Covers: result parsing (Downloaded/Skipped/Failure), the full ErrorKind mapping matrix, UNKNOWN fallback for raw Python exceptions, never-throws on missing interpreter, **progress hooks fire start→progress→100→complete** (T-040), null-listener safety, `expected_output_path`/skip passthrough, `cleanup_temp` count, sanitized-path passthrough, and bitrate-domain validation.

---

## 5. Test matrix (T-030..T-041): locally-verifiable vs device-only

| Test | Verifiable here | How | Status |
|---|---|---|---|
| T-030 MP3 written, non-zero, decodes | ✗ device | Python `test_e2e.test_01`; Kotlin `t030` | written |
| T-031 filename = `{artists} - {title}` | ✓ offline | `test_output_path`; e2e `test_02` | **48/48 + 53/53 pass** |
| T-032 embedded ID3 (title/artist/album) | ✗ device | e2e `test_03`; Kotlin `t032` | written |
| T-033 embedded cover art | ✗ device | e2e `test_04`; Kotlin `t033` | written |
| T-034 bitrate honored | ✓ offline (domain) + ✗ device (encoder) | `test_errors`/Kotlin config validate; e2e `test_05` checks ffprobe | validated domain, device verifies encoder |
| T-035 skip-existing via sidecar | **✓ offline** | `test_sidecar` (sidecar fast path, no network, mtime unchanged) | **passes** |
| T-036 unavailable → FAILED, no corrupt file | **✓ offline (stub)** | `test_failure` stubs extraction failure → typed result + no artifact + temp cleaned | **passes** |
| T-037 interrupted → temp cleaned / retryable | **✓ offline** | `test_cleanup` (orphan sweep + per-attempt cleanup + constructor sweep) | **passes** |
| T-038 filename sanitization | **✓ offline, RUN** | `test_sanitize` (all of `/:*?"<>|`, separators, NFC, dots/spaces, empty→`_`, non-string→TypeError) | **8/8 pass** |
| T-039 extraction fail → FAILED, retryable | **✓ offline (stub)** | `test_failure` reuses downloader after failure → second call DOWNLOADED | **passes** |
| T-040 progress hooks (start+complete) | **✓ offline** | `test_hooks` (download + skip paths, Java-style names, hook-exceptions swallowed) | **passes** |
| T-041 file size not truncated | **✓ offline (validation)** | `test_validation` (truncation/duration/byte-budget detection) | **passes** |

**Device-only (need emulator/device + network):** T-030, T-032, T-033, T-034 (encoder check), T-038 (real download filename), T-035 (real no-network skip), T-036 (real unavailable track), T-039 (real isolation), T-040 (real hooks), T-041 (real on-disk size). Covered by `tests/python/test_e2e.py` and `tests/androidTest/.../TrackDownloadInstrumentedTest.kt`.

---

## 6. Why this is optimal (not a hack)

- **Determinism & no double work.** Skip is a pure filesystem sidecar check on the fast path (zero network), with spotdl's own `overwrite="skip"` as a second layer — the T-035 guarantee.
- **Defensive end-to-end.** Every Python failure → typed `TrackDownloadError` (stable `ErrorKind`); every Kotlin failure → typed `TrackDownloadResult.Failure`; the bridge never throws to callers (mirrors `PlaylistMetadataBridge`, T-021-style). No raw exception text ever reaches the UI.
- **No junk left behind.** Temp snapshot/diff per attempt + age-based orphan sweep at construction → an interrupted/killed download (T-037) clears cleanly and retries identical.
- **Integrity before hand-off.** Post-download the file is validated as a real MP3 with ID3 + duration-vs-byte-budget (truncation, T-041) *before* the sidecar is written — a corrupt file is never marked downloaded.
- **Testable boundary.** The Python/Kotlin seam is positional `callAttr` (no kwargs) + dict↔Map marshalling; the Kotlin logic is 100% JVM-testable with the Chaquopy stub (53/53), and the Python side is 100% offline-testable with a stubbed `search_and_download`.
- **Consistency.** Same package conventions, same `CODE: <msg>` wire contract, same never-throws bridge promise, same `kotlinc ... -include-runtime` test harness as `metadata-fetch`.

## 7. Known gaps / integration notes

- **Module merge at app build.** The repo has three per-component `ghostify_dl.py` copies (metadata-fetch, python-runtime, track-download). Chaquopy ships one `src/main/python/` in the APK, so at app build the track-download functions (`make_downloader`/`download`/`expected_output_path`/`cleanup_temp`) must be merged into the app's single `ghostify_dl.py` — exactly as PROJECT.md §7 describes (one module wrapping spotdl's API). The Kotlin bridge calls `ghostify_dl` module methods directly; no change needed once merged.
- **ResourceWarning (unclosed event loop).** spotdl's anonymous `SpotifyClient.init()` leaks an asyncio loop on construction under CPython 3.13. Harmless (process-bound interpreter on device doesn't leak per-call), logged, not a correctness issue.
- **T-025/T-026 (bundled ffmpeg on Android)** are owned by the `python-runtime` component (`prepend_path` + `FfmpegLocator`); this component's constructor validates ffmpeg is resolvable and raises `DEPENDENCY` if not — surfaced as a typed Failure by the bridge.

## 8. How to run

```bash
# Python (spotdl + mutagen in a venv):
cd components/track-download/tests/python
python -m unittest test_sanitize test_output_path test_sidecar test_hooks \
                   test_cleanup test_validation test_errors test_failure

# Kotlin JVM bridge tests (Chaquopy stub, no Android needed):
cd components/track-download
kotlinc src/main/java/com/ghostify/trackdownload/*.kt \
        tests/stub/com/chaquo/python/PythonStub.kt \
        tests/TrackDownloadBridgeTest.kt -include-runtime -d tests/bridge-test.jar
java -jar tests/bridge-test.jar

# On device / emulator (real spotdl download, T-030..T-041 integration):
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ghostify.trackdownload.TrackDownloadInstrumentedTest
# …or, against the Python layer directly in Chaquopy on device:
#   python tests/python/test_e2e.py   (sets GHOSTIFY_NETWORK_TESTS=1 by default)
```

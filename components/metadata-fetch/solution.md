# Ghostify — Playlist Metadata Fetch (Python bridge): Solution

Component: `components/metadata-fetch/`
Scope: TESTS.md T-010..T-021 | PROJECT.md §5 (add-playlist flow), §7 (Python bridge)

---

## 1. What was built

A typed, defensive Python⇄Kotlin bridge that fetches full metadata for a public
Spotify playlist through **spotdl's `SpotifyClient`** (anonymous client — no
account, no token) and returns it to Kotlin as a typed model. Every failure
mode is a typed `PlaylistFetchError` — never a raw exception or crash.

- Python: `src/main/python/ghostify_dl.py` — `fetch_playlist(spotify_id, options)`.
- Kotlin: `com.ghostify.python.{PlaylistMetadataBridge, PlaylistMetadata,
  PlaylistFetchError}` — thin Chaquopy wrapper, typed model + sealed result.
- Tests: Python unit+integration suite (`tests/ghostify_dl_test.py`), Kotlin
  JVM bridge tests (`tests/PlaylistMetadataBridgeTest.kt` + Chaquopy stub).

## 2. Wire contract

Python returns a JSON-safe dict; every failure is a `GhostifyError` whose
`str()` is `"<CODE>: <human message>"` with `CODE ∈
{NO_NETWORK, RATE_LIMITED, PRIVATE, TIMEOUT, NOT_FOUND, UNKNOWN}`. The Kotlin
bridge regex-scans that token anywhere in the Chaquopy exception message
(Chaquopy prefixes the Python exception type name) and maps it to a
`PlaylistFetchError` with a user-safe message + retry hint.

```python
{
  "name": str, "owner": str, "cover_url": str|None, "description": str|None,
  "track_count": int,
  "tracks": [ { "position": int, "spotify_id": str, "title": str,
                "artists": "A, B", "album": str, "duration_ms": int,
                "cover_url": str|None, "yt_id": str|None }, ... ]
}
```

## 3. Key design decisions and why

### 3.1 Anonymous Spotify via spotdl `Playlist.get_metadata` (T-010..T-018)
The module wraps spotdl's **programmatic API** (`Playlist.get_metadata`),
which internally uses `SpotifyClient()` — the anonymous client — so public
playlists need no credentials (PROJECT.md §1 non-goal). Input is defensive:
bare id, `open.spotify.com/playlist/...` URL, or `spotify:playlist:...` URI all
normalize to a 1–64 alphanumeric id.

### 3.2 Album/cover enrichment (T-012) — the one real gap found
spotdl **4.5.x's anonymous track endpoint returns empty album/cover objects**
(`album: {}`), so `get_metadata` yields tracks with `album_name=None` and
`cover_url=None`. Since the Room schema and T-012 require both, a best-effort
enrichment pass (`_fetch_album_enrichment`) pulls the **paginated GraphQL
payload once** via `spotapi.paginate_playlist` — the exact backend spotdl's
anonymous client wraps — and fills `album` + per-track `cover_url`. Enrichment
failures degrade to empty fields; they can never fail the fetch.

### 3.3 Hard wall-clock deadline = the timeout mechanism (T-019)
spotdl's HTTP stack (requests/curl_cffi) does not expose a reliable global
request timeout, so the fetch runs inside a **daemon worker thread** and the
caller waits on an event with a deadline. On expiry the caller gets
`GhostifyError(TIMEOUT)` immediately; the worker keeps draining in the
background (Python cannot cancel in-flight sockets) but its result is
discarded. Verified: a long-hanging call aborts in <5 s on a 0.4 s deadline.

### 3.4 Per-track fault isolation for YouTube resolution (T-020)
YT id resolution mirrors spotdl's default audio provider (YouTubeMusic,
`filter="songs"`, top relevance result). It is **bounded-parallel** (4 daemon
workers, one YTMusic provider **per worker thread** — the shared ytmusicapi
session object is not thread-safe), which cuts a 50-track playlist's wall time
by ~4×. Every failure mode (no match, per-track timeout, provider error) yields
`yt_id=None` for *that track only* — never a whole-fetch failure. Verified:
an empty result set and a throwing provider both produce `yt_id=None`.

### 3.5 Error classification (T-015, T-016, T-017, T-018, T-021)
- A bare `KeyError` from spotdl on an unresolvable playlist is re-diagnosed
  with one extra GraphQL probe (`_diagnose_playlist`) that reads the raw
  `__typename`: `NotFound` → **NOT_FOUND** (T-015), `GenericError` with a
  private/403 message → **PRIVATE** with the "public only" message (T-018).
- Any other exception is classified by regex over the full exception chain +
  spotapi's `error` attribute: 429/quota → **RATE_LIMITED** (T-017),
  connection refused / DNS / unreachable → **NO_NETWORK** (T-016), read-timeout
  → **TIMEOUT**, 404/deleted/invalid → **NOT_FOUND**; anything else →
  **UNKNOWN** (never a crash).
- Kotlin `PlaylistMetadataBridge` additionally catches `PyException` and any
  `RuntimeException` (interpreter not started, non-map result, malformed JSON)
  and maps them to typed failures — the bridge **never throws** (T-021).

### 3.6 Defensive parsing, duplicates & empty playlists (T-013, T-014)
- Tracks are emitted in playlist order with a 0-based `position`; a repeated
  `spotify_id` appears once per occurrence (verified).
- `PlaylistMetadata.fromMap` skips malformed track entries instead of crashing,
  and falls back to safe defaults for every field; empty playlists yield 0
  tracks without error.

### 3.7 Default timeout
A full fetch = spotdl metadata (~2 paginated requests) + enrichment (1–2
requests) + parallel YT resolution. A 50-track playlist measured ~45–60 s here
(sandbox: spotapi's secret-host is unreachable, adding ~5 s per probe). Default
timeout is therefore **180 s**; T-019's abort test uses an explicit short
timeout. The app may pass a smaller/larger `timeout` per call.

## 4. File map

```
metadata-fetch/
├── TESTS.md
├── solution.md
├── src/main/python/ghostify_dl.py            Python wrapper (fetch_playlist)
└── src/main/java/com/ghostify/python/
    ├── PlaylistFetchError.kt                 error codes + PlaylistFetchError
    ├── PlaylistMetadata.kt                   PlaylistMetadata / PlaylistTrack
    └── PlaylistMetadataBridge.kt             Chaquopy bridge (never throws)
tests/
├── ghostify_dl_test.py                       Python unit + network tests (T-019..T-021, T-013/014/020 logic)
├── PlaylistMetadataBridgeTest.kt             Kotlin JVM bridge tests (T-021, parsing)
└── stub/com/chaquo/python/PythonStub.kt      test-only Chaquopy stand-in
```

## 5. Verification performed in this environment

**Python (spotdl 4.5.2 installed in a local venv) — `tests/ghostify_dl_test.py`:**
- **20/20 pass.** Offline: `_call_with_deadline` abort timing (T-019), full
  classification matrix (T-021), duplicate/order preservation (T-013), empty
  playlist (T-014), YT-missing/YT-error → `yt_id=None` (T-020), wire format,
  input parsing, option normalization, GraphQL-message extraction.
- Network-gated (ran here): real public playlist fetch with name/owner/cover/
  ordered tracks (T-010, T-011), every track carrying the full 8-field set with
  YT ids resolved (T-012, ran), nonexistent playlist → typed NOT_FOUND (T-015).
- Live end-to-end: `Today's Top Hits` → 50 tracks, album/cover/yt_id populated
  for 50/50; nonexistent id → `NOT_FOUND`; 3 s deadline → `TIMEOUT` at ~3 s.

**Kotlin (kotlinc 1.9.25) — `tests/PlaylistMetadataBridgeTest.kt`:**
- **36/36 pass** against the Chaquopy stub: every wire code maps to its typed
  error with retry hint, arbitrary Python exceptions → UNKNOWN (no crash),
  missing module / non-map result → typed Failure, full success parsing,
  malformed-track skipping, empty-playlist shape (T-021, T-014 shapes).

> **Device-only / not verifiable here (T-010..T-020 device half):** real
> Chaquopy interpreter on an emulator/device. T-016 (airplane mode),
> T-017 (induce 429), T-018 (private playlist id), T-020 (a real track with no
> YT match), and the device half of T-019. Tests are written for all of these
> (`ghostify_dl_test.py` network section + Kotlin instrumentation) and the
> logic is exercised offline; they require a device with the app installed.

## 6. How to run the tests

```bash
# Python (needs a spotdl install; pip install spotdl):
python3 tests/ghostify_dl_test.py                 # all tests, incl. network
GHOSTIFY_NETWORK_TESTS=0 python3 tests/ghostify_dl_test.py   # offline only

# Kotlin JVM bridge tests (Chaquopy stub):
/opt/kotlinc/kotlinc/bin/kotlinc \
  src/main/java/com/ghostify/python/(all kt) \
  tests/stub/com/chaquo/python/(all kt) \
  tests/PlaylistMetadataBridgeTest.kt \
  -include-runtime -d tests/bridge-test.jar
java -jar tests/bridge-test.jar

# On device (T-010..T-020 device half):
./gradlew :app:connectedDebugAndroidTest
```

## 7. Integration notes (PROJECT.md §7)

1. `ghostify_dl.py` ships under `app/src/main/python/`; the Chaquopy pip
   install must include `spotdl` (see python-runtime component's build config).
2. Call the bridge off the main thread: `PlaylistMetadataBridge`
   `.fetchPlaylistBlocking(id)` → `PlaylistFetchResult.Success/Failure`
   (PROJECT.md §7 threading contract). The bridge serializes calls per instance
   so the Chaquopy interpreter is never touched concurrently.
3. Upsert the `PlaylistMetadata` into Room: `playlists` + batch `songs` with
   `spotify_id` as identity and `status=PENDING` (PROJECT.md §5 add flow).
4. `yt_id` is best-effort at fetch time; the download path resolves YT itself
   anyway, so a null `yt_id` here is non-fatal by design (PROJECT.md §6.1).

## 8. Known limitations / risks

- **spotdl anonymous album gap** (3.2): the enrichment is one extra request;
  if spotapi is unreachable, `album`/`cover_url` degrade to empty strings.
- **spotapi secret host**: spotapi fetches anonymous-client secrets from
  `code.thetadev.de`; when unreachable it falls back to a default secret but
  each probe pays a connect timeout (~5 s here). Device/CI with normal network
  is unaffected; total runtime is still deadline-bounded.
- **Very large playlists**: YT resolution is parallel but each track costs a
  YTMusic request; the app can raise `timeout` or set `resolve_yt=false` for
  huge playlists (download-time resolution still populates `yt_id`).
- **T-018 private-playlist id**: the diagnostic maps GraphQL `GenericError`
  messages containing private/403 markers to PRIVATE; an unrecognized payload
  falls back to NOT_FOUND — still a clean typed error, never a crash.

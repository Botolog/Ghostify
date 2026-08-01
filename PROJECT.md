# Ghostify

A **fully offline, on-device** Android app that saves Spotify playlists, downloads every track as an MP3 (via `spotdl`), and plays them back like a Spotify playlist — pause, next/prev, shuffle, repeat, volume, seek, and true background playback with lockscreen controls.

Everything runs locally on the phone. No backend server, no cloud storage.

---

## 1. Overview

The user pastes a **Spotify playlist URL**. The app:

1. **Saves** the playlist (metadata: name, cover, owner, track list) to its own database.
2. Lets the user press **Download all** — every track is downloaded as an MP3 onto the device using `spotdl`.
3. **Plays** those MP3s like a Spotify playlist: background playback, lockscreen/media controls, shuffle, repeat, seek, volume.
4. Offers a **Re-sync / Redownload** button per playlist that refreshes the track list from Spotify:
   - new tracks added → downloaded
   - tracks removed from the Spotify playlist → local files deleted
   - tracks already downloaded → **skipped** (never re-downloaded)

### Goals
- Private, self-contained, works without an account.
- One-tap playlist save → full offline library.
- Real background audio (Android MediaSession + foreground service).
- Efficient re-sync that only touches what changed.

### Non-goals (v1)
- Spotify account login / OAuth. (v1 supports **public** playlists only — spotdl fetches metadata from Spotify's public web endpoints with no token. Private playlists require OAuth and are a future extension.)
- Streaming from Spotify itself. (We only ever download via YouTube, as spotdl does.)
- Multi-device sync / cloud backup. (No server, by design.)
- DRM'd or region-restricted content.

---

## 2. High-level architecture

```
┌───────────────────────────────────────────────────────────────┐
│                    ANDROID DEVICE (single APK)                 │
│                                                               │
│   ┌────────────────────────────────────────────────────────┐  │
│   │  Android App (Kotlin / Jetpack Compose)                │  │
│   │                                                        │  │
│   │  ┌──────────────┐  ┌─────────────────┐  ┌───────────┐  │  │
│   │  │   UI layer   │  │  Player core    │  │ Download  │  │  │
│   │  │  (Compose)   │  │  Media3/        │  │  Manager  │  │  │
│   │  │  Library     │  │  ExoPlayer      │  │ (WorkMgr) │  │  │
│   │  │  Playlist    │  │  + MediaSession │  │ queue +   │  │  │
│   │  │  Player      │  │  background &   │  │ progress  │  │  │
│   │  │  Settings    │  │  lockscreen     │  │           │  │  │
│   │  └──────┬───────┘  └────────┬────────┘  └─────┬─────┘  │  │
│   │         └──────── ViewModels / UseCases / Repos ─┘      │  │
│   │  ┌────────────────────────────────────────────────┐    │  │
│   │  │        Room (SQLite): playlists, songs,        │    │  │
│   │  │        sync state, settings                    │    │  │
│   │  └────────────────────────────────────────────────┘    │  │
│   │  ┌────────────────────────────────────────────────┐    │  │
│   │  │     Python bridge — Chaquopy runtime           │    │  │
│   │  │   ┌──────────────────────────────────────────┐ │    │  │
│   │  │   │  spotdl (Python) + yt-dlp (in-process)   │ │    │  │
│   │  │   │  metadata fetch · download · tag/cover   │ │    │  │
│   │  │   └──────────────────┬───────────────────────┘ │    │  │
│   │  └──────────────────────┼─────────────────────────┘    │  │
│   │  ┌──────────────────────▼─────────────────────────┐    │  │
│   │  │  Local MP3 store (app-scoped storage)          │    │  │
│   │  │  + bundled ffmpeg binary (jniLibs)             │    │  │
│   │  └────────────────────────────────────────────────┘    │  │
│   └────────────────────────────────────────────────────────┘  │
│                                                               │
│   Network: Spotify public web API (metadata only)             │
│            YouTube  (audio resolution, via yt-dlp inside spotdl)│
└───────────────────────────────────────────────────────────────┘
```

### Why this shape
- **Audio is local** → the player just plays files; no streaming fragility.
- **spotdl is Python** → we embed a Python runtime inside the app rather than reimplementing download logic in Kotlin.
- **No server** → nothing to host, no accounts, works anywhere.

---

## 3. Tech stack

| Concern | Choice | Why |
|---|---|---|
| Platform | **Android** (Kotlin, minSdk 26+, target latest) | User requirement; background audio is a first-class Android citizen |
| UI | **Jetpack Compose** + Material 3 | Modern, declarative, fast to build |
| Audio playback | **Media3 / ExoPlayer** + `MediaSessionService` | Gapless playlists, background play, lockscreen & notification controls, shuffle/repeat, audio focus, seek |
| Persistence | **Room (SQLite)** | Playlists, songs, download state, settings |
| Async / queue | **Kotlin Coroutines + Flow**, **WorkManager** for long downloads | Progress streaming to UI; reliable background jobs |
| DI | **Hilt** | Standard Android DI |
| Python runtime | **Chaquopy** (Python SDK for Android) | Runs spotdl as a real Python library *inside* the app — single APK, nothing else to install |
| Downloader | **spotdl** (Python lib) + bundled **yt-dlp** | Spotify→YouTube resolution + MP3 download + tag/cover embedding |
| Audio codec | **ffmpeg** bundled as native binary (`jniLibs`) | spotdl calls ffmpeg to convert to MP3 |
| Album art for player | Extracted from MP3 tags (`MediaMetadataRetriever`) | Cover already embedded by spotdl; no extra network needed |

---

## 4. Data model (Room)

```
playlists
  id               TEXT PK        -- our own UUID
  spotify_id       TEXT UNIQUE    -- Spotify playlist id (from URL)
  name             TEXT
  owner            TEXT
  cover_url        TEXT
  track_count      INT
  status           TEXT           -- NEW | READY | DOWNLOADING | ERROR
  created_at       INTEGER        -- epoch ms
  last_synced_at   INTEGER        -- epoch ms

songs
  id               TEXT PK        -- our own UUID
  playlist_id      TEXT FK → playlists.id   (ON DELETE CASCADE)
  spotify_id       TEXT           -- Spotify track id
  title            TEXT
  artists          TEXT           -- "Artist A, Artist B"
  album            TEXT
  duration_ms      INTEGER
  cover_url        TEXT
  yt_id            TEXT           -- resolved YouTube id (from spotdl metadata)
  file_path        TEXT           -- local mp3 path; NULL = not downloaded
  status           TEXT           -- PENDING | QUEUED | DOWNLOADING | DOWNLOADED | FAILED | REMOVED
  added_at         INTEGER

  UNIQUE(playlist_id, spotify_id)

settings (key/value)
  storage_dir, default_bitrate, concurrent_downloads, auto_download_on_add, ...
```

Key invariants:
- `songs.spotify_id` is the **authoritative track identity** — the sync diff operates on it.
- `songs.file_path` non-null + `status = DOWNLOADED` ⇒ file should exist on disk. (Re-sync verifies existence.)
- `UNIQUE(playlist_id, spotify_id)` prevents duplicate rows across re-syncs.

---

## 5. App flows & screens

### Screens
1. **Library (home)** — list of saved playlists: cover, name, track count, download progress badge, last sync time. FAB → "Add playlist".
2. **Add playlist dialog** — paste Spotify URL → app validates, fetches metadata (no download yet), shows preview → Save.
3. **Playlist detail** — track list with per-track download status icons; top actions:
   - **▶ Play all** (plays downloaded tracks)
   - **⬇ Download all** (downloads every missing track)
   - **🔄 Re-sync / Redownload** (diff logic, §6)
   - per-track: long-press → re-download that one track
4. **Player screen** — large cover art, title/artist, seek bar, play/pause, prev/next, **shuffle toggle**, **repeat (off / all / one)**, **volume slider**, queue drawer.
5. **Settings** — storage location, download bitrate, concurrent downloads, auto-download-on-add, clear cache.

### Add-playlist flow (no download yet)
```
User pastes URL
  → parse spotify_id (support full URL or open.spotify.com/playlist/...)
  → call Python bridge: spotdl get_playlist_metadata(spotify_id)
       (name, owner, cover, full track list — metadata only, no download)
  → upsert playlist row + batch-insert song rows (status=PENDING)
  → show in Library
```

---

## 6. Core logic

### 6.1 Download all
```
downloadAll(playlistId):
  songs = songsFor(playlistId) where status in (PENDING, FAILED)
  mark each → QUEUED
  runner = spotdl.Downloader(bitrate=settings, output_template="{artists} - {title}")
  for song in QUEUED:
    song.status = DOWNLOADING;  emit progress event
    try:
      result = runner.download(song.url)        # in-process Python call
      song.file_path = findOutputFile(result)   # spotdl writes <template>.mp3
      song.status = DOWNLOADED
    except:
      song.status = FAILED                      # shown as retryable
  update playlist.status → READY
```

Notes:
- **spotdl skips files it already has** (it writes a sidecar `.spotdl` metadata file next to each MP3). That check, plus our DB diff, is the "won't re-download" guarantee.
- Runs under **WorkManager** so downloads continue when the app is backgrounded.
- Progress streamed to UI via a `Flow<DownloadProgress>` (per-song + overall %).

### 6.2 Re-sync / redownload (the diff)
```
syncPlaylist(playlistId):
  current   = fetchSpotifyTracks(playlistId)        # spotdl metadata, no download
  stored    = songsFor(playlistId)
  storedById = map(stored, spotify_id)

  # 1) Tracks that are NEW
  for track in current:
    song = storedById[track.spotify_id]
    if song == null:
        insert song(status = PENDING)               # will be downloaded
    else if song.file_path == null OR !fileExists(song.file_path):
        song.status = PENDING                        # re-queue missing file

  # 2) Tracks REMOVED from the Spotify playlist
  currentIds = set(current, spotify_id)
  for song in stored:
    if song.spotify_id not in currentIds:
        deleteLocalFile(song.file_path)              # clean up disk
        deleteRow(song)

  # 3) Everything left untouched → status DOWNLOADED, NOT re-downloaded
  #    (already-downloaded + still-present songs are skipped)

  update playlist meta (name, cover, count), last_synced_at
  start queued PENDING downloads
```

Resulting behavior:
- Added tracks → downloaded.
- Removed tracks → files deleted from disk + DB.
- Existing tracks → untouched, zero re-downloads.
- Track whose local file was deleted manually → re-downloaded.

### 6.3 Playback
- Player builds a **Media3 queue** from all `DOWNLOADED` songs of the playlist (`MediaItem` per file, URI from `file_path`).
- **Shuffle** = ExoPlayer shuffle mode; **repeat** = repeat mode (off/all/one); **next/prev**, **seek**, and **volume** are native ExoPlayer APIs.
- **Background play** = `MediaSessionService` running as a **foreground service** → keeps playing with screen off; shows **notification + lockscreen controls**; handles **audio focus** and "become noisy" (headphone unplug).
- **Album art** extracted from each MP3's embedded tag via `MediaMetadataRetriever`; also used for the lockscreen artwork.

---

## 7. Python bridge (Chaquopy) details

- Chaquopy exposes a Python interpreter inside the APK. A thin Kotlin wrapper calls a small Python module `ghostify_dl.py` (shipped with the app) that wraps spotdl's **programmatic API** — not its CLI:

```python
# ghostify_dl.py (sketch)
from spotdl import Spotdl, SpotifyClient

def fetch_playlist(spotify_id):
    return SpotifyClient().get_playlist(spotify_id).to_serializable()

def make_downloader(bitrate):                       # bitrate comes from app Settings
    return Spotdl(client_id=None, client_secret=None,  # public endpoints, no auth
                  output_format="mp3", bitrate=bitrate,
                  output="{artists} - {title}")

def download(downloader, url, on_progress):
    # use spotdl hooks (on_download_start / on_download_complete)
    # to report per-track progress back to Kotlin
    ...
```

- **ffmpeg**: spotdl shells out to `ffmpeg`. We bundle static ffmpeg binaries in `app/src/main/jniLibs/<abi>/` and prepend that dir to `PATH` for the Python subprocess. (Architectures: `arm64-v8a`, `armeabi-v7a`, `x86_64`.)
- **Threading**: all Python calls run on a dedicated dispatcher (`Dispatchers.IO` or a single-threaded executor) — never on the main thread.
- **Known risks** (spike early!): Chaquopy's `pip` must resolve spotdl's dependency tree; spotdl uses `requests` (works via Chaquopy's network layer); verify per-track hooks and output-path detection behave under Android's file system. If spotdl can't run inside Chaquopy, we'll discuss alternatives together before proceeding.

### 7.1 Repo layout
```
ghostify/
  PROJECT.md
  android/                     # Android Studio project (Kotlin)
    app/
      src/main/java/com/ghostify/
        ui/                    # Compose screens (library, playlist, player, settings)
        player/                # MediaSessionService, PlayerController, queue builder
        download/              # DownloadManager, WorkManager worker, progress flow
        data/                  # Room (entities, DAOs), repositories
        python/                # PythonBridge (Chaquopy wrapper)
        di/                    # Hilt modules
      src/main/python/         # ghostify_dl.py (spotdl wrapper)
      src/main/jniLibs/<abi>/  # bundled ffmpeg binaries
    build.gradle.kts
  README.md
```

---

## 8. Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Spotify metadata fetch + YouTube download |
| `POST_NOTIFICATIONS` (API 33+) | download & playback notifications |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | background playback |
| `FOREGROUND_SERVICE_DATA_SYNC` | background downloads |
| storage | **none** — files live in app-scoped external dir (`getExternalFilesDir`) |

---

## 9. Roadmap

1. **Phase 0 — Python spike (highest risk, do first):** get `spotdl` running under Chaquopy on an emulator/device, download a single track, verify tags/cover output.
2. **Phase 1 — Add playlist:** URL parse → metadata fetch → Room save → Library screen.
3. **Phase 2 — Download all:** DownloadManager + WorkManager + progress UI + file store.
4. **Phase 3 — Player:** Media3 queue, MediaSessionService, background + lockscreen, shuffle/repeat/seek/volume, player screen.
5. **Phase 4 — Re-sync/redownload:** diff logic + per-track re-download + file cleanup.
6. **Phase 5 — Polish:** settings, edge cases (empty playlist, failed downloads, killed-process recovery), error handling.

## 10. Risks & mitigations

| Risk | Mitigation |
|---|---|
| spotdl doesn't run under Chaquopy | Spike first (Phase 0); if blocked, discuss alternatives together |
| YouTube availability/region blocks some tracks | Track-level FAILED status + retry button; `--ignore-errors` |
| Large playlists take long / battery | WorkManager, sequential or limited concurrency, app-scoped storage |
| Private playlists need auth | v1 scope = public only; OAuth listed as future work |

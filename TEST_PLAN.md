# Ghostify — Complete Test Plan

Every test a component must pass **before** the app is assembled. Tests are grouped by component. Each test has an ID, type, and pass criteria.

Test types:
- **Unit** — pure logic, JVM, fast.
- **Integration** — multiple components together (Room + repo, bridge + file system).
- **Instrumentation** — on emulator/device, Android APIs involved.
- **UI** — Compose UI test.
- **Manual** — physical device, human-verified.

---

## 1. Spotify URL parsing (`SpotifyUrlParser`)

- [ ] **T-001** (Unit) `https://open.spotify.com/playlist/37i9dQZF1DX...` → extracts the playlist id correctly.
- [ ] **T-002** (Unit) `spotify:playlist:37i9dQZF1DX...` URI form → extracts id.
- [ ] **T-003** (Unit) Short URL `https://spotify.link/xxxx` → resolves to a full URL and extracts id (requires network).
- [ ] **T-004** (Unit) Trailing slash / query params (`?si=abc&utm=...`) → still extracts correct id.
- [ ] **T-005** (Unit) Wrong type: `open.spotify.com/album/...` or `/track/...` → rejected with clear error.
- [ ] **T-006** (Unit) Garbage string / empty / null → rejected.
- [ ] **T-007** (Unit) Malformed URL (`https://open.spotify.com/playlist/`) → rejected.
- [ ] **T-008** (Unit) URL with path traversal or encoded chars → no crash, safe rejection.
- [ ] **T-009** (Unit) Unrelated domain (`youtube.com/playlist/...`) → rejected.

## 2. Playlist metadata fetch — Python bridge (`ghostify_dl.fetch_playlist`)

- [ ] **T-010** (Integration) Valid public playlist id → returns name, owner, cover_url, track list.
- [ ] **T-011** (Integration) Track list matches Spotify count (compare against web).
- [ ] **T-012** (Integration) Each track has: spotify_id, title, artists, album, duration_ms, cover_url, yt_id.
- [ ] **T-013** (Integration) Duplicate track appears once per occurrence position (playlist order preserved).
- [ ] **T-014** (Integration) Empty playlist → returns 0 tracks, no crash.
- [ ] **T-015** (Integration) Non-existent / deleted playlist id → clean error, no crash.
- [ ] **T-016** (Integration) Network unavailable → typed error (NO_NETWORK), no crash.
- [ ] **T-017** (Integration) Spotify rate-limited / 429 → typed error (RATE_LIMITED) with retry hint.
- [ ] **T-018** (Integration) Private playlist (no auth) → typed error (PRIVATE) with "public only" message.
- [ ] **T-019** (Unit) Fetch timeout (long-hanging request) → aborts within N seconds, typed error.
- [ ] **T-020** (Integration) Playlist with a track whose YouTube match is missing → track returned with `yt_id = null`, NOT a whole-fetch failure.
- [ ] **T-021** (Unit) Python bridge exceptions propagate to Kotlin as typed failures, never as raw crashes.

## 3. Python runtime boot (Chaquopy)

- [ ] **T-022** (Instrumentation) Interpreter boots on all three ABIs (arm64-v8a, armeabi-v7a, x86_64).
- [ ] **T-023** (Instrumentation) Cold-start interpreter load time < 10 s on a mid-range device.
- [ ] **T-024** (Instrumentation) Second call reuses interpreter (no reload).
- [ ] **T-025** (Instrumentation) `ffmpeg` is found on PATH and executes a trivial conversion.
- [ ] **T-026** (Instrumentation) spotdl module imports without error on device.
- [ ] **T-027** (Instrumentation) Python calls never run on the main thread (verified via StrictMode / log).
- [ ] **T-028** (Instrumentation) Interpreter crash/segfault → app catches it, no process death.
- [ ] **T-029** (Integration) Concurrent bridge calls are serialized (no thread-safety corruption).

## 4. Single-track download (`Downloader` + spotdl)

- [ ] **T-030** (Integration) Download one public track URL → MP3 written, non-zero size, decodes as audio.
- [ ] **T-031** (Integration) Output filename matches configured template `{artists} - {title}.mp3` (sanitized).
- [ ] **T-032** (Integration) MP3 has embedded ID3 tags (title, artist, album) — verified via tag reader.
- [ ] **T-033** (Integration) MP3 has embedded cover art — extractable via `MediaMetadataRetriever`.
- [ ] **T-034** (Integration) Bitrate setting (128k/192k/320k) honored — verify with ffprobe equivalent.
- [ ] **T-035** (Integration) Track already downloaded (sidecar `.spotdl` exists) → spotdl skips, no re-download (verify no network hit / file mtime unchanged).
- [ ] **T-036** (Integration) YouTube video unavailable/region-blocked → FAILED status, no partial corrupt file left.
- [ ] **T-037** (Integration) Download interrupted (kill mid-download) → temp file cleaned up or ignored; status FAILED, retryable.
- [ ] **T-038** (Integration) Filename containing invalid chars (`/\:*?"<>|`) → sanitized, file still written.
- [ ] **T-039** (Integration) Download of a track where YouTube audio extraction fails → FAILED, other tracks unaffected.
- [ ] **T-040** (Integration) Progress hook fires (start + complete) for the track.
- [ ] **T-041** (Integration) File size matches expected (not truncated) after download.

## 5. Download manager / queue

- [ ] **T-042** (Unit) `downloadAll` picks up only PENDING + FAILED songs.
- [ ] **T-043** (Unit) DOWNLOADED songs are never re-enqueued by `downloadAll`.
- [ ] **T-044** (Unit) Status transitions follow PENDING→QUEUED→DOWNLOADING→DOWNLOADED (or FAILED), never invalid jumps.
- [ ] **T-045** (Unit) Order respects playlist track order.
- [ ] **T-046** (Integration) Overall progress % computed correctly (done / total), including FAILED.
- [ ] **T-047** (Integration) Partial failure: 1 of 10 fails → 9 DOWNLOADED, 1 FAILED, playlist status READY.
- [ ] **T-048** (Integration) Retry button re-queues only FAILED tracks.
- [ ] **T-049** (Integration) Cancel download-all → current track marked FAILED(or CANCELED), rest stay PENDING, no zombie workers.
- [ ] **T-050** (Instrumentation) WorkManager continues download when app is backgrounded.
- [ ] **T-051** (Instrumentation) Process killed mid-download → on restart, in-flight track resets to PENDING (recoverable), others consistent.
- [ ] **T-052** (Integration) Download with storage full → FAILED with clear message, app doesn't crash.
- [ ] **T-053** (Integration) Device network switches (WiFi→mobile) mid-download → resumes or fails cleanly, no corruption.
- [ ] **T-054** (Unit) Duplicate "download all" presses → single run, second call is a no-op or resumes, never double-downloads.
- [ ] **T-055** (Integration) Two different playlists downloaded "simultaneously" → both complete correctly, no shared-state corruption.

## 6. Re-sync / redownload diff logic

- [ ] **T-056** (Unit) Playlist with 2 new tracks added on Spotify → 2 new song rows inserted with PENDING.
- [ ] **T-057** (Unit) 1 track removed on Spotify → its local file deleted AND row deleted.
- [ ] **T-058** (Unit) Tracks unchanged → rows untouched, status stays DOWNLOADED, file mtime unchanged (no re-download).
- [ ] **T-059** (Unit) Track present in DB but file missing on disk → status reset to PENDING (re-queue).
- [ ] **T-060** (Unit) Track moved position in playlist → order updated, not treated as add/remove.
- [ ] **T-061** (Unit) Track's metadata changed on Spotify (title/artist renamed) → same spotify_id → updated, NOT re-downloaded.
- [ ] **T-062** (Unit) Sync when playlist was renamed → playlist name/cover/count updated.
- [ ] **T-063** (Unit) Empty playlist after sync → all local files deleted, playlist shows 0 tracks.
- [ ] **T-064** (Unit) `UNIQUE(playlist_id, spotify_id)` — re-sync never creates duplicate rows.
- [ ] **T-065** (Unit) Remove-track deletion of a file that's already gone from disk → no crash, row still deleted.
- [ ] **T-066** (Integration) Full cycle: sync → download → edit Spotify playlist → sync → diff matches expected (adds downloaded, removes deleted, existing kept).
- [ ] **T-067** (Integration) Sync with network failure mid-fetch → database unchanged (transactional rollback), playlist still consistent.
- [ ] **T-068** (Unit) Concurrent sync + user starts a manual download → no deadlock, no duplicate downloads.

## 7. Room database / repositories

- [ ] **T-069** (Unit) `playlists` insert / read / update / delete round-trip.
- [ ] **T-070** (Unit) `songs` insert with FK → playlist delete cascades to songs.
- [ ] **T-071** (Unit) `UNIQUE(playlist_id, spotify_id)` enforced — duplicate insert rejected.
- [ ] **T-072** (Unit) `songsFor(playlistId)` ordered by playlist position.
- [ ] **T-073** (Unit) Repository exposes Flow; UI observes updates on insert/update/delete.
- [ ] **T-074** (Unit) Schema migration from v1 → v2 preserves data (migration test with seeded rows).
- [ ] **T-075** (Unit) Writes are atomic (a failed batch leaves no half-inserted playlist).
- [ ] **T-076** (Unit) Large playlist (1000 tracks) batch insert completes < 5 s.
- [ ] **T-077** (Unit) `settings` get/set round-trip for all keys (storage_dir, bitrate, concurrency, auto_download).
- [ ] **T-078** (Unit) Querying playlist with a song status filter returns correct subsets (DOWNLOADED / FAILED / etc.).

## 8. Player core (Media3 / ExoPlayer)

- [ ] **T-079** (Instrumentation) Queue builds one MediaItem per DOWNLOADED song, in playlist order.
- [ ] **T-080** (Instrumentation) Play / pause toggles playback state.
- [ ] **T-081** (Instrumentation) Next / previous move through queue correctly (incl. at ends).
- [ ] **T-082** (Instrumentation) Seek to arbitrary position → resumes from that position.
- [ ] **T-083** (Instrumentation) Shuffle ON → order randomized; OFF → playlist order restored.
- [ ] **T-084** (Instrumentation) Repeat off → stops after last; repeat all → loops; repeat one → repeats current.
- [ ] **T-085** (Instrumentation) Volume slider changes actual output volume.
- [ ] **T-086** (Instrumentation) Playing a DOWNLOADED-only queue when some songs aren't downloaded → only downloaded songs in queue, no errors.
- [ ] **T-087** (Instrumentation) Playlist with 0 downloaded songs → player shows "nothing to play" state, no crash.
- [ ] **T-088** (Instrumentation) Corrupt/truncated MP3 file → skipped gracefully (auto-advance), no app crash.
- [ ] **T-089** (Instrumentation) Song ends → auto-advance to next.
- [ ] **T-090** (Instrumentation) Now-playing metadata (title/artist/album) shown correctly in player UI.
- [ ] **T-091** (Instrumentation) Album art extracted from file tags and shown.
- [ ] **T-092** (Instrumentation) Queue persists while playing; player reflects DB changes only on next queue build (no mid-play rebuild of the playing song).

## 9. MediaSessionService / background playback

- [ ] **T-093** (Manual) Play → screen off → music keeps playing.
- [ ] **T-094** (Manual) Lockscreen shows media notification with title/artist/art.
- [ ] **T-095** (Manual) Lockscreen controls: play/pause, next, prev, seek work.
- [ ] **T-096** (Manual) Swiping away app from recents → music continues.
- [ ] **T-097** (Manual) Notification stop button → playback stops, service stops, no zombie notification.
- [ ] **T-098** (Manual) Headphones unplugged → playback pauses (becoming-noisy handler).
- [ ] **T-099** (Manual) Phone call / another app requests audio focus → playback pauses or ducks per policy; resumes after focus restored.
- [ ] **T-100** (Manual) Notification actions (play/pause/next/prev) work from notification itself.
- [ ] **T-101** (Manual) Media buttons on Bluetooth headphones control the app.
- [ ] **T-102** (Manual) Device rotation during playback → player state preserved, no restart.
- [ ] **T-103** (Instrumentation) `MediaSession` playback state matches actual player state (position, state, speed).
- [ ] **T-104** (Manual) Volume keys on device adjust player volume.
- [ ] **T-105** (Manual) Android Auto / car head unit basic media display (if targeted) shows metadata.
- [ ] **T-106** (Manual) Battery saver mode on → playback continues (foreground service works).
- [ ] **T-107** (Manual) Two sessions conflict? No — app releases/regains focus correctly (multiple audio apps test).
- [ ] **T-108** (Manual) Force-stop app from settings → notification cleared, no crash on next launch.

## 10. ViewModels / state management

- [ ] **T-109** (Unit) Library VM exposes playlists sorted by creation date.
- [ ] **T-110** (Unit) Playlist VM reflects download progress events (per-song + overall).
- [ ] **T-111** (Unit) Player VM maps player state → UI state (playing, position, shuffle, repeat) without leaks.
- [ ] **T-112** (Unit) Loading / error / empty states exposed correctly for each screen.
- [ ] **T-113** (Unit) Config change (rotation) → VM survives, UI re-renders from state.
- [ ] **T-114** (Unit) Add-playlist flow: error on invalid URL shows message, dialog stays open.
- [ ] **T-115** (Unit) Add-playlist flow: success → closes dialog, new playlist appears.
- [ ] **T-116** (Unit) Actions on a playlist mid-download (play/sync/delete) are safe — no illegal-state crashes.
- [ ] **T-117** (Unit) Deleting a playlist during download → download cancelled/cleaned, no orphan files left on disk.

## 11. UI (Compose) — per screen

Library screen:
- [ ] **T-118** (UI) Empty state shows "no playlists" message.
- [ ] **T-119** (UI) Playlist list renders cover, name, track count.
- [ ] **T-120** (UI) Download progress badge updates live.
- [ ] **T-121** (UI) FAB opens add-playlist dialog.
- [ ] **T-122** (UI) Tap playlist → navigates to detail.
- [ ] **T-123** (UI) Last-synced time displayed and formatted.

Add-playlist dialog:
- [ ] **T-124** (UI) Paste URL → validation error shown for invalid URL.
- [ ] **T-125** (UI) Fetch metadata shows loading spinner.
- [ ] **T-126** (UI) Fetch success shows preview (name, cover, track count) before save.
- [ ] **T-127** (UI) Save disabled until fetch succeeds.
- [ ] **T-128** (UI) Fetch error (private/network) shows readable message.
- [ ] **T-129** (UI) Duplicate playlist already saved → warning, does not duplicate.

Playlist detail:
- [ ] **T-130** (UI) Track list renders title/artist/duration + status icon per track.
- [ ] **T-131** (UI) Download-all button starts queue; button shows progress while running.
- [ ] **T-132** (UI) Re-sync button triggers diff and shows "syncing" state.
- [ ] **T-133** (UI) Play-all disabled with hint when 0 downloaded tracks.
- [ ] **T-134** (UI) Per-track long-press → re-download single track.
- [ ] **T-135** (UI) FAILED tracks visually distinct, tap to retry.
- [ ] **T-136** (UI) Track removed from Spotify → disappears after sync (not just stale rows).

Player screen:
- [ ] **T-137** (UI) Shows cover, title, artist, album.
- [ ] **T-138** (UI) Progress bar reflects position; drag seeks.
- [ ] **T-139** (UI) Play/pause, next, prev buttons functional.
- [ ] **T-140** (UI) Shuffle toggle reflects state; repeat cycles off→all→one.
- [ ] **T-141** (UI) Volume slider works.
- [ ] **T-142** (UI) Queue drawer lists upcoming tracks; tapping one jumps to it.
- [ ] **T-143** (UI) Player shows even when app was launched from notification.
- [ ] **T-144** (UI) Empty queue → player screen shows "nothing playing".

Settings:
- [ ] **T-145** (UI) Bitrate setting (128/192/320) persists and is used for new downloads.
- [ ] **T-146** (UI) Storage location shows current path; change → new downloads go there.
- [ ] **T-147** (UI) Concurrent downloads setting respected.
- [ ] **T-148** (UI) Auto-download-on-add toggle honored on new playlist save.
- [ ] **T-149** (UI) Clear cache frees space (file count drops) without deleting library records.

## 12. File management

- [ ] **T-150** (Unit) Output path resolution matches DB `file_path` after download.
- [ ] **T-151** (Integration) App-scoped dir used; no storage permission needed.
- [ ] **T-152** (Integration) Deleted song file also deletes its sidecar `.spotdl` file.
- [ ] **T-153** (Integration) Orphan files (in store but not in DB) are removable via clear-cache.
- [ ] **T-154** (Integration) Long filenames (> 255 bytes) truncated safely, no write failure.
- [ ] **T-155** (Integration) Re-sync detects manually-deleted file and re-queues (T-059 covered by file-exists check).
- [ ] **T-156** (Instrumentation) Disk space calculation accurate for UI display.

## 13. Error handling & resilience (cross-cutting)

- [ ] **T-157** (Unit) Every user-facing error maps to a readable message (no raw exceptions in UI).
- [ ] **T-158** (Unit) No crashes on: network down, API error, empty playlist, corrupt file, storage full.
- [ ] **T-159** (Instrumentation) App restart after forced kill → DB consistent, downloads recoverable, no stuck DOWNLOADING status.
- [ ] **T-160** (Instrumentation) Cold start → UI renders from DB before any network call.
- [ ] **T-161** (Manual) Airplane mode on during playback → current song continues (local file), no crash.
- [ ] **T-162** (Manual) Time/date change (DST, travel) → timestamps don't break sort/display.

## 14. Performance

- [ ] **T-163** (Instrumentation) Library screen renders 100 playlists without jank (< 16 ms/frame).
- [ ] **T-164** (Instrumentation) Playlist detail renders 1000-track list with lazy list (no OOM).
- [ ] **T-165** (Instrumentation) Python bridge calls don't block UI (verified via Choreographer frame logs).
- [ ] **T-166** (Instrumentation) Memory: repeated downloads don't grow heap unboundedly (no leak in bridge wrapper).
- [ ] **T-167** (Manual) App idle in background → battery drain comparable to other media apps (no busy loops).

## 15. Security & privacy

- [ ] **T-168** (Unit) No secrets/credentials in source, logs, or stored data.
- [ ] **T-169** (Instrumentation) App data not exported to other apps (no exported storage).
- [ ] **T-170** (Unit) Logs never contain full Spotify URLs / track metadata beyond debug need.
- [ ] **T-171** (Unit) All network calls are HTTPS.

## 16. Platform / release readiness

- [ ] **T-172** (Instrumentation) Runs on minSdk 26 device.
- [ ] **T-173** (Instrumentation) Runs on latest Android (current target) — notification permission flow works.
- [ ] **T-174** (Manual) Notification permission prompt appears correctly on Android 13+ and is deniable without crash.
- [ ] **T-175** (Instrumentation) Dark mode + light mode render correctly (all screens).
- [ ] **T-176** (Manual) Non-English locale — no hardcoded user-visible strings.
- [ ] **T-177** (Manual) Small screen (360dp) and large screen/tablet — no clipped/overlapping UI.
- [ ] **T-178** (Manual) Fresh install → onboarding-free first run is functional.
- [ ] **T-179** (Manual) App update preserves existing library (schema migration path tested).
- [ ] **T-180** (Instrumentation) Release build (R8/minify) doesn't break Chaquopy reflection/spotdl imports.
- [ ] **T-181** (Instrumentation) `adb install` clean install + upgrade install both pass.

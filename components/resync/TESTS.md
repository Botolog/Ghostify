# Ghostify — Re-sync / Redownload Diff Logic

**Component:** `syncPlaylist(playlistId)` — diff current Spotify track list against stored songs: insert new, delete removed (files + rows), re-queue missing files, skip everything else. Never re-downloads unchanged tracks.

**Source of tests:** `TEST_PLAN.md` §6.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-056 (Unit)
Playlist with 2 new tracks added on Spotify → 2 new song rows inserted with PENDING.

### T-057 (Unit)
1 track removed on Spotify → its local file deleted AND row deleted.

### T-058 (Unit)
Tracks unchanged → rows untouched, status stays DOWNLOADED, file mtime unchanged (no re-download).

### T-059 (Unit)
Track present in DB but file missing on disk → status reset to PENDING (re-queue).

### T-060 (Unit)
Track moved position in playlist → order updated, not treated as add/remove.

### T-061 (Unit)
Track's metadata changed on Spotify (title/artist renamed) → same spotify_id → updated, NOT re-downloaded.

### T-062 (Unit)
Sync when playlist was renamed → playlist name/cover/count updated.

### T-063 (Unit)
Empty playlist after sync → all local files deleted, playlist shows 0 tracks.

### T-064 (Unit)
`UNIQUE(playlist_id, spotify_id)` — re-sync never creates duplicate rows.

### T-065 (Unit)
Remove-track deletion of a file that's already gone from disk → no crash, row still deleted.

### T-066 (Integration)
Full cycle: sync → download → edit Spotify playlist → sync → diff matches expected (adds downloaded, removes deleted, existing kept).

### T-067 (Integration)
Sync with network failure mid-fetch → database unchanged (transactional rollback), playlist still consistent.

### T-068 (Unit)
Concurrent sync + user starts a manual download → no deadlock, no duplicate downloads.

---

**Deliverables in this folder:** SyncUseCase + diff algorithm + transactional DAO usage, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

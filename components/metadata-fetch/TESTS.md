# Ghostify — Playlist Metadata Fetch (Python bridge)

**Component:** `ghostify_dl.fetch_playlist(spotify_id)` — fetches full playlist metadata from Spotify via spotdl's `SpotifyClient`, returning name, owner, cover, and ordered track list. Exposed to Kotlin through the Chaquopy bridge.

**Source of tests:** `TEST_PLAN.md` §2.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-010 (Integration)
Valid public playlist id → returns name, owner, cover_url, track list.

### T-011 (Integration)
Track list matches Spotify count (compare against web).

### T-012 (Integration)
Each track has: spotify_id, title, artists, album, duration_ms, cover_url, yt_id.

### T-013 (Integration)
Duplicate track appears once per occurrence position (playlist order preserved).

### T-014 (Integration)
Empty playlist → returns 0 tracks, no crash.

### T-015 (Integration)
Non-existent / deleted playlist id → clean error, no crash.

### T-016 (Integration)
Network unavailable → typed error (NO_NETWORK), no crash.

### T-017 (Integration)
Spotify rate-limited / 429 → typed error (RATE_LIMITED) with retry hint.

### T-018 (Integration)
Private playlist (no auth) → typed error (PRIVATE) with "public only" message.

### T-019 (Unit)
Fetch timeout (long-hanging request) → aborts within N seconds, typed error.

### T-020 (Integration)
Playlist with a track whose YouTube match is missing → track returned with `yt_id = null`, NOT a whole-fetch failure.

### T-021 (Unit)
Python bridge exceptions propagate to Kotlin as typed failures, never as raw crashes.

---

**Deliverables in this folder:** Python wrapper module + Kotlin bridge wrapper, typed error model, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

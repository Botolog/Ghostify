# Ghostify — Room Database / Repositories

**Component:** Room schema (playlists, songs, settings), DAOs, migrations, repository layer exposing reactive Flows. The data contract every other component depends on.

**Source of tests:** `TEST_PLAN.md` §7.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-069 (Unit)
`playlists` insert / read / update / delete round-trip.

### T-070 (Unit)
`songs` insert with FK → playlist delete cascades to songs.

### T-071 (Unit)
`UNIQUE(playlist_id, spotify_id)` enforced — duplicate insert rejected.

### T-072 (Unit)
`songsFor(playlistId)` ordered by playlist position.

### T-073 (Unit)
Repository exposes Flow; UI observes updates on insert/update/delete.

### T-074 (Unit)
Schema migration from v1 → v2 preserves data (migration test with seeded rows).

### T-075 (Unit)
Writes are atomic (a failed batch leaves no half-inserted playlist).

### T-076 (Unit)
Large playlist (1000 tracks) batch insert completes < 5 s.

### T-077 (Unit)
`settings` get/set round-trip for all keys (storage_dir, bitrate, concurrency, auto_download).

### T-078 (Unit)
Querying playlist with a song status filter returns correct subsets (DOWNLOADED / FAILED / etc.).

---

**Deliverables in this folder:** Room entities, DAOs, AppDatabase with migrations, repositories, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

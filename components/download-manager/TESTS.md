# Ghostify — Download Manager / Queue

**Component:** Per-playlist download queue — selects which songs to download, drives status transitions, emits progress, survives backgrounding and process death, supports cancel/retry.

**Source of tests:** `TEST_PLAN.md` §5.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-042 (Unit)
`downloadAll` picks up only PENDING + FAILED songs.

### T-043 (Unit)
DOWNLOADED songs are never re-enqueued by `downloadAll`.

### T-044 (Unit)
Status transitions follow PENDING→QUEUED→DOWNLOADING→DOWNLOADED (or FAILED), never invalid jumps.

### T-045 (Unit)
Order respects playlist track order.

### T-046 (Integration)
Overall progress % computed correctly (done / total), including FAILED.

### T-047 (Integration)
Partial failure: 1 of 10 fails → 9 DOWNLOADED, 1 FAILED, playlist status READY.

### T-048 (Integration)
Retry button re-queues only FAILED tracks.

### T-049 (Integration)
Cancel download-all → current track marked FAILED(or CANCELED), rest stay PENDING, no zombie workers.

### T-050 (Instrumentation)
WorkManager continues download when app is backgrounded.

### T-051 (Instrumentation)
Process killed mid-download → on restart, in-flight track resets to PENDING (recoverable), others consistent.

### T-052 (Integration)
Download with storage full → FAILED with clear message, app doesn't crash.

### T-053 (Integration)
Device network switches (WiFi→mobile) mid-download → resumes or fails cleanly, no corruption.

### T-054 (Unit)
Duplicate "download all" presses → single run, second call is a no-op or resumes, never double-downloads.

### T-055 (Integration)
Two different playlists downloaded "simultaneously" → both complete correctly, no shared-state corruption.

---

**Deliverables in this folder:** DownloadManager + WorkManager worker + progress Flow + cancellation logic, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

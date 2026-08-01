# Ghostify — File Management

**Component:** Local MP3 store on disk — output-path resolution, cleanup of deleted songs and their sidecar files, orphan detection, long-filename handling, disk-space reporting.

**Source of tests:** `TEST_PLAN.md` §12.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-150 (Unit)
Output path resolution matches DB `file_path` after download.

### T-151 (Integration)
App-scoped dir used; no storage permission needed.

### T-152 (Integration)
Deleted song file also deletes its sidecar `.spotdl` file.

### T-153 (Integration)
Orphan files (in store but not in DB) are removable via clear-cache.

### T-154 (Integration)
Long filenames (> 255 bytes) truncated safely, no write failure.

### T-155 (Integration)
Re-sync detects manually-deleted file and re-queues (file-exists check).

### T-156 (Instrumentation)
Disk space calculation accurate for UI display.

---

**Deliverables in this folder:** MusicStore / LocalFileManager + filename sanitization/truncation + orphan cleanup, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

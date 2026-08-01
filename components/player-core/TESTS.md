# Ghostify — Player Core (Media3 / ExoPlayer)

**Component:** The playback engine — builds a Media3 queue from a playlist's DOWNLOADED songs, exposes play/pause/next/prev/seek/shuffle/repeat/volume, robust to corrupt files and empty playlists.

**Source of tests:** `TEST_PLAN.md` §8.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-079 (Instrumentation)
Queue builds one MediaItem per DOWNLOADED song, in playlist order.

### T-080 (Instrumentation)
Play / pause toggles playback state.

### T-081 (Instrumentation)
Next / previous move through queue correctly (incl. at ends).

### T-082 (Instrumentation)
Seek to arbitrary position → resumes from that position.

### T-083 (Instrumentation)
Shuffle ON → order randomized; OFF → playlist order restored.

### T-084 (Instrumentation)
Repeat off → stops after last; repeat all → loops; repeat one → repeats current.

### T-085 (Instrumentation)
Volume slider changes actual output volume.

### T-086 (Instrumentation)
Playing a DOWNLOADED-only queue when some songs aren't downloaded → only downloaded songs in queue, no errors.

### T-087 (Instrumentation)
Playlist with 0 downloaded songs → player shows "nothing to play" state, no crash.

### T-088 (Instrumentation)
Corrupt/truncated MP3 file → skipped gracefully (auto-advance), no app crash.

### T-089 (Instrumentation)
Song ends → auto-advance to next.

### T-090 (Instrumentation)
Now-playing metadata (title/artist/album) shown correctly in player UI.

### T-091 (Instrumentation)
Album art extracted from file tags and shown.

### T-092 (Instrumentation)
Queue persists while playing; player reflects DB changes only on next queue build (no mid-play rebuild of the playing song).

---

**Deliverables in this folder:** PlayerController wrapping ExoPlayer, queue builder, state exposure as Flow, error resilience, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

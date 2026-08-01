# Ghostify — UI (Compose screens)

**Component:** All Compose screens — Library, Add-playlist dialog, Playlist detail, Player, Settings. Correct rendering, live progress, navigation, and edge-case states.

**Source of tests:** `TEST_PLAN.md` §11.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-118 (UI) Library
Empty state shows "no playlists" message.

### T-119 (UI) Library
Playlist list renders cover, name, track count.

### T-120 (UI) Library
Download progress badge updates live.

### T-121 (UI) Library
FAB opens add-playlist dialog.

### T-122 (UI) Library
Tap playlist → navigates to detail.

### T-123 (UI) Library
Last-synced time displayed and formatted.

### T-124 (UI) Add dialog
Paste URL → validation error shown for invalid URL.

### T-125 (UI) Add dialog
Fetch metadata shows loading spinner.

### T-126 (UI) Add dialog
Fetch success shows preview (name, cover, track count) before save.

### T-127 (UI) Add dialog
Save disabled until fetch succeeds.

### T-128 (UI) Add dialog
Fetch error (private/network) shows readable message.

### T-129 (UI) Add dialog
Duplicate playlist already saved → warning, does not duplicate.

### T-130 (UI) Playlist detail
Track list renders title/artist/duration + status icon per track.

### T-131 (UI) Playlist detail
Download-all button starts queue; button shows progress while running.

### T-132 (UI) Playlist detail
Re-sync button triggers diff and shows "syncing" state.

### T-133 (UI) Playlist detail
Play-all disabled with hint when 0 downloaded tracks.

### T-134 (UI) Playlist detail
Per-track long-press → re-download single track.

### T-135 (UI) Playlist detail
FAILED tracks visually distinct, tap to retry.

### T-136 (UI) Playlist detail
Track removed from Spotify → disappears after sync (not just stale rows).

### T-137 (UI) Player
Shows cover, title, artist, album.

### T-138 (UI) Player
Progress bar reflects position; drag seeks.

### T-139 (UI) Player
Play/pause, next, prev buttons functional.

### T-140 (UI) Player
Shuffle toggle reflects state; repeat cycles off→all→one.

### T-141 (UI) Player
Volume slider works.

### T-142 (UI) Player
Queue drawer lists upcoming tracks; tapping one jumps to it.

### T-143 (UI) Player
Player shows even when app was launched from notification.

### T-144 (UI) Player
Empty queue → player screen shows "nothing playing".

### T-145 (UI) Settings
Bitrate setting (128/192/320) persists and is used for new downloads.

### T-146 (UI) Settings
Storage location shows current path; change → new downloads go there.

### T-147 (UI) Settings
Concurrent downloads setting respected.

### T-148 (UI) Settings
Auto-download-on-add toggle honored on new playlist save.

### T-149 (UI) Settings
Clear cache frees space (file count drops) without deleting library records.

---

**Deliverables in this folder:** all Compose screens + navigation + Compose UI tests, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

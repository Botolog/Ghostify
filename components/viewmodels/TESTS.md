# Ghostify — ViewModels / State Management

**Component:** ViewModels exposing UI state (as StateFlow), orchestrating use cases, surviving config changes, and safely coordinating concurrent operations (sync + download + delete).

**Source of tests:** `TEST_PLAN.md` §10.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-109 (Unit)
Library VM exposes playlists sorted by creation date.

### T-110 (Unit)
Playlist VM reflects download progress events (per-song + overall).

### T-111 (Unit)
Player VM maps player state → UI state (playing, position, shuffle, repeat) without leaks.

### T-112 (Unit)
Loading / error / empty states exposed correctly for each screen.

### T-113 (Unit)
Config change (rotation) → VM survives, UI re-renders from state.

### T-114 (Unit)
Add-playlist flow: error on invalid URL shows message, dialog stays open.

### T-115 (Unit)
Add-playlist flow: success → closes dialog, new playlist appears.

### T-116 (Unit)
Actions on a playlist mid-download (play/sync/delete) are safe — no illegal-state crashes.

### T-117 (Unit)
Deleting a playlist during download → download cancelled/cleaned, no orphan files left on disk.

---

**Deliverables in this folder:** LibraryViewModel, PlaylistViewModel, PlayerViewModel, AddPlaylistViewModel + state models, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

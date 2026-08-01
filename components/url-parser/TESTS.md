# Ghostify — Spotify URL Parser

**Component:** `SpotifyUrlParser` — parses user-pasted input into a valid Spotify playlist id.

**Source of tests:** `TEST_PLAN.md` §1.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-001 (Unit)
`https://open.spotify.com/playlist/37i9dQZF1DX...` → extracts the playlist id correctly.

### T-002 (Unit)
`spotify:playlist:37i9dQZF1DX...` URI form → extracts id.

### T-003 (Unit)
Short URL `https://spotify.link/xxxx` → resolves to a full URL and extracts id (requires network).

### T-004 (Unit)
Trailing slash / query params (`?si=abc&utm=...`) → still extracts correct id.

### T-005 (Unit)
Wrong type: `open.spotify.com/album/...` or `/track/...` → rejected with clear error.

### T-006 (Unit)
Garbage string / empty / null → rejected.

### T-007 (Unit)
Malformed URL (`https://open.spotify.com/playlist/`) → rejected.

### T-008 (Unit)
URL with path traversal or encoded chars → no crash, safe rejection.

### T-009 (Unit)
Unrelated domain (`youtube.com/playlist/...`) → rejected.

---

**Deliverables in this folder:** implementation code, the unit tests themselves, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

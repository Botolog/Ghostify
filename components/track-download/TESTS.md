# Ghostify — Single-Track Download (spotdl)

**Component:** One-track download pipeline — given one track URL, produce a tagged MP3 with embedded cover art in the configured location, honoring bitrate setting and spotdl's skip-existing behavior.

**Source of tests:** `TEST_PLAN.md` §4.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-030 (Integration)
Download one public track URL → MP3 written, non-zero size, decodes as audio.

### T-031 (Integration)
Output filename matches configured template `{artists} - {title}.mp3` (sanitized).

### T-032 (Integration)
MP3 has embedded ID3 tags (title, artist, album) — verified via tag reader.

### T-033 (Integration)
MP3 has embedded cover art — extractable via `MediaMetadataRetriever`.

### T-034 (Integration)
Bitrate setting (128k/192k/320k) honored — verify with ffprobe equivalent.

### T-035 (Integration)
Track already downloaded (sidecar `.spotdl` exists) → spotdl skips, no re-download (verify no network hit / file mtime unchanged).

### T-036 (Integration)
YouTube video unavailable/region-blocked → FAILED status, no partial corrupt file left.

### T-037 (Integration)
Download interrupted (kill mid-download) → temp file cleaned up or ignored; status FAILED, retryable.

### T-038 (Integration)
Filename containing invalid chars (`/\:*?"<>|`) → sanitized, file still written.

### T-039 (Integration)
Download of a track where YouTube audio extraction fails → FAILED, other tracks unaffected.

### T-040 (Integration)
Progress hook fires (start + complete) for the track.

### T-041 (Integration)
File size matches expected (not truncated) after download.

---

**Deliverables in this folder:** Python download wrapper (with progress hooks, output-path detection, sanitization) + Kotlin wrapper, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

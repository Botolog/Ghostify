# Ghostify — Performance

**Component:** Performance budget for every screen and the Python bridge: smooth scrolling with large data, no UI blocking, no memory leaks, sane battery behavior.

**Source of tests:** `TEST_PLAN.md` §14.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-163 (Instrumentation)
Library screen renders 100 playlists without jank (< 16 ms/frame).

### T-164 (Instrumentation)
Playlist detail renders 1000-track list with lazy list (no OOM).

### T-165 (Instrumentation)
Python bridge calls don't block UI (verified via Choreographer frame logs).

### T-166 (Instrumentation)
Memory: repeated downloads don't grow heap unboundedly (no leak in bridge wrapper).

### T-167 (Manual)
App idle in background → battery drain comparable to other media apps (no busy loops).

---

**Deliverables in this folder:** performance benchmarks/tests (frame stats, memory, leak checks), guidance on lazy lists + bridge threading, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

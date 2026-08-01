# Ghostify — Error Handling & Resilience

**Component:** Cross-cutting error mapping and crash-resilience: every failure becomes a readable user message, no crashes on any realistic failure mode, and the app recovers consistently after being killed.

**Source of tests:** `TEST_PLAN.md` §13.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-157 (Unit)
Every user-facing error maps to a readable message (no raw exceptions in UI).

### T-158 (Unit)
No crashes on: network down, API error, empty playlist, corrupt file, storage full.

### T-159 (Instrumentation)
App restart after forced kill → DB consistent, downloads recoverable, no stuck DOWNLOADING status.

### T-160 (Instrumentation)
Cold start → UI renders from DB before any network call.

### T-161 (Manual)
Airplane mode on during playback → current song continues (local file), no crash.

### T-162 (Manual)
Time/date change (DST, travel) → timestamps don't break sort/display.

---

**Deliverables in this folder:** central error model + mapper, app-level crash handler (no crash dialogs), recovery logic for killed-process states, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

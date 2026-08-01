# Ghostify — Security & Privacy

**Component:** No secrets in the app, no exported data, no sensitive logging, HTTPS everywhere.

**Source of tests:** `TEST_PLAN.md` §15.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-168 (Unit)
No secrets/credentials in source, logs, or stored data.

### T-169 (Instrumentation)
App data not exported to other apps (no exported storage).

### T-170 (Unit)
Logs never contain full Spotify URLs / track metadata beyond debug need.

### T-171 (Unit)
All network calls are HTTPS.

---

**Deliverables in this folder:** security checks (static scan script/tests), logging policy, manifest audit, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

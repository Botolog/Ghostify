# Ghostify — Platform / Release Readiness

**Component:** The app works across the supported Android range, survives packaging (R8/proguard), migrates cleanly on update, and behaves correctly on fresh install, in both themes, all locales, and different screen sizes.

**Source of tests:** `TEST_PLAN.md` §16.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-172 (Instrumentation)
Runs on minSdk 26 device.

### T-173 (Instrumentation)
Runs on latest Android (current target) — notification permission flow works.

### T-174 (Manual)
Notification permission prompt appears correctly on Android 13+ and is deniable without crash.

### T-175 (Instrumentation)
Dark mode + light mode render correctly (all screens).

### T-176 (Manual)
Non-English locale — no hardcoded user-visible strings.

### T-177 (Manual)
Small screen (360dp) and large screen/tablet — no clipped/overlapping UI.

### T-178 (Manual)
Fresh install → onboarding-free first run is functional.

### T-179 (Manual)
App update preserves existing library (schema migration path tested).

### T-180 (Instrumentation)
Release build (R8/minify) doesn't break Chaquopy reflection/spotdl imports.

### T-181 (Instrumentation)
`adb install` clean install + upgrade install both pass.

---

**Deliverables in this folder:** manifest/theme/locale setup, proguard rules (incl. Chaquopy), CI-ready checks, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

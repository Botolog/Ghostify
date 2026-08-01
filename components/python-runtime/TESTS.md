# Ghostify — Python Runtime Boot (Chaquopy)

**Component:** Chaquopy runtime initialization — interpreter boot, ABI support, bundled ffmpeg on PATH, spotdl import, thread confinement, crash containment.

**Source of tests:** `TEST_PLAN.md` §3.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-022 (Instrumentation)
Interpreter boots on all three ABIs (arm64-v8a, armeabi-v7a, x86_64).

### T-023 (Instrumentation)
Cold-start interpreter load time < 10 s on a mid-range device.

### T-024 (Instrumentation)
Second call reuses interpreter (no reload).

### T-025 (Instrumentation)
`ffmpeg` is found on PATH and executes a trivial conversion.

### T-026 (Instrumentation)
spotdl module imports without error on device.

### T-027 (Instrumentation)
Python calls never run on the main thread (verified via StrictMode / log).

### T-028 (Instrumentation)
Interpreter crash/segfault → app catches it, no process death.

### T-029 (Integration)
Concurrent bridge calls are serialized (no thread-safety corruption).

---

**Deliverables in this folder:** Chaquopy Gradle config, PythonRuntime bootstrap class, ffmpeg bundling strategy, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

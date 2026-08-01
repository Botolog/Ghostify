# Ghostify — MediaSessionService / Background Playback

**Component:** Foreground-service playback with lockscreen + notification media controls, audio focus handling, becoming-noisy behavior, and notification actions. This is the "plays like Spotify in the background" guarantee.

**Source of tests:** `TEST_PLAN.md` §9.
**Context:** read `../../PROJECT.md` for architecture and stack.
**Goal:** implement the optimal solution so all tests below pass. Put your final solution + explanation in `solution.md`.

---

### T-093 (Manual)
Play → screen off → music keeps playing.

### T-094 (Manual)
Lockscreen shows media notification with title/artist/art.

### T-095 (Manual)
Lockscreen controls: play/pause, next, prev, seek work.

### T-096 (Manual)
Swiping away app from recents → music continues.

### T-097 (Manual)
Notification stop button → playback stops, service stops, no zombie notification.

### T-098 (Manual)
Headphones unplugged → playback pauses (becoming-noisy handler).

### T-099 (Manual)
Phone call / another app requests audio focus → playback pauses or ducks per policy; resumes after focus restored.

### T-100 (Manual)
Notification actions (play/pause/next/prev) work from notification itself.

### T-101 (Manual)
Media buttons on Bluetooth headphones control the app.

### T-102 (Manual)
Device rotation during playback → player state preserved, no restart.

### T-103 (Instrumentation)
`MediaSession` playback state matches actual player state (position, state, speed).

### T-104 (Manual)
Volume keys on device adjust player volume.

### T-105 (Manual)
Android Auto / car head unit basic media display (if targeted) shows metadata.

### T-106 (Manual)
Battery saver mode on → playback continues (foreground service works).

### T-107 (Manual)
Two sessions conflict? No — app releases/regains focus correctly (multiple audio apps test).

### T-108 (Manual)
Force-stop app from settings → notification cleared, no crash on next launch.

---

**Deliverables in this folder:** `MediaSessionService` (Media3), foreground service manifest wiring, notification, audio-focus + becoming-noisy handling, and `solution.md` (final solution + short explanation). Prefer the cleanest, most defensive implementation — not a hack that merely works.

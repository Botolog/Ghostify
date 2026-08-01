# Ghostify — Release Readiness: Manual (device-only) Check List

These five checks require a human (or a device farm with visual review) and
cannot be asserted purely in CI. Each is paired with the automated slice that
already guards the underlying mechanism.

---

## T-174 — Notification permission prompt on Android 13+ is deniable without crash

**Automated slice already covered:** `NotificationPermissionFlowTest` asserts
the denied state is safe and the manager's bookkeeping is correct.

**Manual steps (API 33+ phone, fresh install):**
1. Fresh-install the app. On first launch the system permission dialog must
   appear (prompted by `NotificationPermissionManager.requestIfNeeded`).
2. Tap **Don't allow**. The app must not crash; the library remains usable.
3. Repeat on a second install: **Don't allow again** ("don't ask again" state).
   The app must still not crash and must not re-prompt. A non-blocking banner
   with a "Turn on notifications / Open settings" action is the expected UI.
4. Third install: **Allow**. Download/playback notifications appear and work.
5. Verify `notificationsEnabled` state changes track the system toggle in
   Settings → Apps → Ghostify → Notifications.
6. Regression on API 26–32: no prompt is shown, app works (permission is
   implicit) — install on the minSdk device and confirm no prompt appears.

**Pass:** no crash on any denial path; prompt shows exactly once; both grants
and denials leave the app fully functional.

---

## T-176 — Non-English locale: no hardcoded user-visible strings

**Automated slice already covered:** Lint `HardcodedText` (= error) and
`LocalizationCheckTest` scan `src/main` for literals.

**Manual steps:**
1. Set device language to a non-English locale shipped in `res/values-*`
   (currently `fr`; add more locales as they are translated).
2. Walk all five screens (Library, Add dialog, Playlist detail, Player,
   Settings) plus notifications and every empty/error state.
3. Assert every user-visible string is translated; nothing falls back to raw
   English **except** keys that have no translation yet (fallback is the
   designed behaviour — record untranslated keys so translators can catch up).
4. Set a right-to-left locale (e.g. `ar`) and assert layouts mirror (no
   left-aligned padding bugs); `android:supportsRtl="true"` is already set.
5. Large-font mode (Settings → Display → Font size = Large/Huge): text must
   reflow, not clip (overlaps the T-177 pass criteria).

**Pass:** zero hardcoded strings in any locale; all shipped locales render.

---

## T-177 — Small (360dp) and large (tablet) screens: no clipped/overlapping UI

**Automated slice already covered:** `ResponsiveLayoutTest` renders the
reference Library screen at 360dp and 1280dp and asserts the key nodes fit
inside the viewport. `ThemeRenderTest` renders the same screen in both themes.

**Manual steps:**
1. **360dp phone** (e.g. Pixel 2 AVD, or a 320dp budget device): walk all
   screens in portrait and landscape. Assert: no text truncation (other than
   intentional `maxLines` ellipsis), no FAB/button overlap, no horizontal
   scrolling, dialogs fit, and nothing is covered by system bars
   (edge-to-edge insets handled on API 35+ — targetSdk 36 enforces
   edge-to-edge, so screens must apply `WindowInsets` padding).
2. **Tablet** (e.g. Pixel Tablet AVD, 10" landscape): same walk. Assert content
   uses the extra width (window-size classes) rather than stretching to full
   width or clipping; large-screen resize with app not locked to portrait.
3. Rotation on every screen: state preserved, no clipping after rotation.

**Pass:** no clipped/overlapping UI at either extreme; nothing under the
status/nav bars; rotation is clean.

---

## T-178 — Fresh install → onboarding-free first run is functional

**Automated slice already covered:** `InstallUpgradeSmokeTest.freshInstall...`
asserts the library empty-state and the add button are present and usable.

**Manual steps:**
1. Fresh install (or `adb shell pm clear com.ghostify`).
2. Launch. Assert: no onboarding/first-run wizard; the Library screen opens
   directly with its empty state; the add button works.
3. Add a public Spotify playlist URL → metadata preview → Save → it appears in
   the library.
4. Kill and relaunch the app (recents-swipe) → the library and settings persist.
5. Start a download, then relaunch → download state (`status`/`file_path`)
   survives (WorkManager + Room resume).

**Pass:** the very first launch is fully usable with zero setup, and state
persists across restarts.

---

## T-179 — App update preserves the existing library (schema migration path)

**Automated slice already covered:** the database component owns the Room
migration unit test (T-074, seeded v1 → v2 rows survive); the CI upgrade gate
(T-181 §3) installs vN then `adb install -r` vN+1 and re-checks the library.

**Manual steps (two APKs, consecutive versionCode):**
1. Install vN, add a real playlist, download at least one track, play it.
2. Build and `adb install -r` vN+1 (which contains a schema bump + migration).
3. Launch. Assert: all playlists/songs/settings rows are present; a previously
   downloaded track still plays; download-status icons are correct.
4. Confirm no data-loss dialog, no crash, no re-download of an already
   downloaded track (the `spotdl` sidecar + `file_path` check in §6.2 skips it).
5. Roll forward once more (vN+1 → vN+2) to prove chained migrations.

**Pass:** the library is byte-for-byte preserved across the upgrade; the app
boots on the new schema; already-downloaded files are not re-downloaded.

---

## Sign-off

| Test | Component ownership | Device-only? | Automated slice |
|---|---|---|---|
| T-174 | release | yes (visual prompt) | `NotificationPermissionFlowTest` |
| T-176 | release | yes (locale walk) | `LocalizationCheckTest` + Lint |
| T-177 | release + ui | yes (real devices) | `ResponsiveLayoutTest` |
| T-178 | release + ui | yes | `InstallUpgradeSmokeTest` |
| T-179 | release + database | yes | CI upgrade gate + DB migration test |

# Ghostify — Release Readiness: CI Check List

Runs in CI on every push to a release branch and on the nightly matrix. JVM-only
gates run on plain Linux; instrumentation gates need emulators/devices.

## 0. CI image requirements

- JDK 21 (matches `org.gradle.java.home`; AGP 8.x requires 17+).
- Android SDK: `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`,
  `emulator`, and the system images below.
- Python **3.11** on PATH (`python3.11`) — Chaquopy's `buildPython` must match
  `chaquopy.version = "3.11"` (pinned to keep `armeabi-v7a` support; 3.12+ is
  64-bit only).
- Emulator hosts need KVM (see §3).

## 1. Static gates (no device) — MUST be green

```bash
# Lint: catches HardcodedText (T-176), manifest issues, R8-related warnings.
./gradlew :app:lintRelease

# JVM unit tests (this component's static gates):
#   LocalizationCheckTest   -> T-176 automated slice
#   ReleaseContractTest     -> manifest/proguard/dark-theme invariants
./gradlew :app:testDebugUnitTest

# Release build with R8 + resource shrink. Failing here (or producing a mapping
# that lost a Chaquopy class) blocks release. Artifacts to archive:
#   app/build/outputs/mapping/release/mapping.txt
#   app/build/outputs/mapping/release/usage.txt
./gradlew :app:assembleRelease

# Debug APKs for the install/upgrade matrix below.
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

ProGuard verification step (manual in CI): confirm the minified release APK
still contains Chaquopy's interpreter classes and the native libs:

```bash
# Native libs must be packaged (spotdl's ffmpeg + Chaquopy's libpython).
unzip -l app/build/outputs/apk/release/app-release.apk | grep -E "libpython|ffmpeg|spotdl"
# Obfuscated classes must still include the kept bridge packages.
# (Requires dexdump from build-tools; presence is guaranteed by the keeps +
#  T-180's runtime import test, which is the real assertion.)
```

## 2. Instrumented matrix (device farm or local emulators)

Run the shared instrumentation tests on every target:

| Matrix slot | Device / AVD                    | Tests exercised | Command |
|---|---|---|---|
| minSdk 26    | AVD API 26 (x86_64), 360dp      | T-172, T-175, T-177, T-181 | `connectedDebugAndroidTest` |
| latest API   | AVD API 36 (x86_64)             | T-173, T-175, T-177, T-181 | `connectedDebugAndroidTest` |
| tablet       | AVD API 36, 10"+ landscape      | T-177 (ResponsiveLayoutTest) | `connectedDebugAndroidTest` |
| release      | AVD API 36 (arm64 or x86_64)    | T-180 (androidTestRelease only) | `connectedReleaseAndroidTest` |

```bash
# One device connected: runs the whole shared suite.
./gradlew :app:connectedDebugAndroidTest
# Release-only suite (T-180). R8/minify runs first, then installs the minified
# APK and asserts Chaquopy + spotdl imports survive.
./gradlew :app:connectedReleaseAndroidTest
```

Device orchestration (Firebase Test Lab or emulator script) should append:

```bash
# For the API-26 slot, assert the run actually executed on API 26:
adb shell getprop ro.build.version.sdk            # expect 26
# T-175 dark-mode leg is covered inside ThemeRenderTest (cmd uimode night flip).
# T-177 automated slice is ResponsiveLayoutTest (360dp + 1280dp viewports).
```

Note: `MinSdkCompatibilityTest`, `ThemeRenderTest`, `ResponsiveLayoutTest` and
`InstallUpgradeSmokeTest` are **device-only** and cannot run on a host without
an Android runtime. All four are in `src/androidTest` (shared source set).

## 3. T-181 — install/upgrade gate (scripted, device-only)

This is the authoritative `adb install` check. Build two APKs with consecutive
`versionCode`s (the app's `versionCode` bumps with each release; the database
component owns the v1→v2 Room migration that this step exercises).

```bash
# 1. Build vN (e.g. versionCode = 2) and install clean.
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 2. First-run smoke: launch, see the empty library, no onboarding wizard.
adb shell am start -n com.ghostify/.ui.MainActivity
# 3. Seed the library so upgrade preservation is observable (via the UI, or the
#    database component's test fixture if used in the same APK).
# 4. Build vN+1 (versionCode = 3) and upgrade install over it.
adb install -r app/build/outputs/apk/debug/app-debug.apk     # must exit 0
# 5. Relaunch and assert: no crash + seeded library rows are still present.
adb shell am start -n com.ghostify/.ui.MainActivity
adb shell run-as com.ghostify sh -c 'ls databases/ && cat databases/ghostify.db >/dev/null && echo DB-OK'
# 6. Assert the install/upgrade instrumentation suite still passes on the
#    upgraded device (InstallUpgradeSmokeTest.appData_survivesProcessDeath).
./gradlew :app:connectedDebugAndroidTest
```

Exit-code assertions on steps 1, 4 and 6 are the CI gate. A vN→vN+1 upgrade
that drops the DB (schema bump without a migration) is caught by step 5/6.

## 4. Localization gate (T-176)

- Lint `HardcodedText` = error (`abortOnError`), enforced in §1.
- `LocalizationCheckTest` scans `src/main` Compose/XML for literals.
- On the locale matrix (device farm): run the app with device locale
  `fr`, `es`, `ar` (RTL) and assert no English fallback in the strings that
  ship in `values-<locale>` (see MANUAL-CHECKLIST T-176).
- Adding a locale requires updating `res/xml/locales_config.xml` — the
  `ReleaseContractTest` asserts the attribute exists.

## 5. Theme / rendering gate (T-175, T-177)

- `ThemeRenderTest` runs on every instrumentation slot (both themes, plus the
  `cmd uimode night` flip).
- `ResponsiveLayoutTest` runs at 360dp and 1280dp viewport widths on the phone
  and tablet slots.
- A screenshot sweep (Firebase Robo or `screencap` after each render test) is
  recommended as the final visual confirmation of the manual items.

## 6. Artifacts to archive on every release CI run

- `mapping.txt` / `usage.txt` (for de-obfuscating release crashes — keep with
  the signed APK/AAB, it is required to triage R8 bugs later).
- JUnit XML + HTML reports from all connected tests.
- Screenshots (theme + 360dp/tablet).

## 7. Merge/release gate summary (what "release-ready" means)

```
Lint clean (HardcodedText=error) ............ T-176 static
:app:testDebugUnitTest ....................... static contract gates
:app:assembleRelease ......................... R8+shrink builds, mapping archived
connectedDebugAndroidTest @ API26 ............ T-172, T-175, T-177, T-181
connectedDebugAndroidTest @ API36 ............ T-173, T-175, T-177, T-181
connectedDebugAndroidTest @ tablet 36 ........ T-177
connectedReleaseAndroidTest @ API36 .......... T-180
adb install + adb install -r + re-run suite .. T-181 upgrade path
```

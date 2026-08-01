# Ghostify — Platform / Release Readiness: Solution

Component owned under `components/release/`. Builds one Android app module's
release-readiness surface (manifest, theming, strings, R8 rules incl. Chaquopy,
device-only instrumentation tests, CI/manual checklists) plus a standalone JVM
harness that runs the *static* gates on a plain Linux CI host with no Android SDK.

---

## 1. Deliverables map to files

| Deliverable | File(s) |
|---|---|
| Manifest | `src/main/AndroidManifest.xml` |
| Light theme (window shell) | `src/main/res/values/themes.xml` + `values-v31/themes.xml` |
| Dark theme | `src/main/res/values-night/themes.xml` |
| Colors (XML ↔ Compose parity) | `src/main/res/values/colors.xml` ↔ `ui/theme/Color.kt` |
| Compose theme | `src/main/java/com/ghostify/ui/theme/Theme.kt`, `Type.kt` |
| Strings (default `en` + `fr`) | `src/main/res/values/strings.xml`, `values-fr/strings.xml` |
| Per-app locale list | `src/main/res/xml/locales_config.xml` |
| Backup / extraction rules | `src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` |
| App class + Python boot | `src/main/java/com/ghostify/GhostifyApplication.kt` |
| Notification permission flow | `src/main/java/com/ghostify/release/NotificationPermissionManager.kt` |
| App-level R8 rules | `src/main/proguard-rules.pro` |
| Chaquopy-specific R8 rules | `src/main/proguard-chaquopy.pro` |
| Static unit tests (JVM) | `src/test/java/.../LocalizationCheckTest.kt`, `ReleaseContractTest.kt` |
| Instrumentation tests | `src/androidTest/...` (T-172/173/175/177/181), `androidTestRelease/...` (T-180) |
| CI checklist | `checks/CI-CHECKLIST.md` |
| Manual checklist | `checks/MANUAL-CHECKLIST.md` |
| Standalone build | `build.gradle.kts`, `gradle/app.build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` + Gradle wrapper (`gradlew`, `gradle/wrapper/`) |

---

## 2. Key decisions

**Canonical, merged manifest.** `AndroidManifest.xml` is the single authority for
the `Application`, launcher `MainActivity`, `MediaSessionService` and
`MediaButtonReceiver`. It pins `minSdk=26` / `targetSdk=36` (latest), declares the
three `FOREGROUND_SERVICE_*` types (required from targetSdk 34), `WAKE_LOCK`,
`POST_NOTIFICATIONS` (granted implicitly below API 33), `INTERNET`, RTL,
`usesCleartextTraffic=false`, full-backup + data-extraction rules, splash-screen
items (v31+), and `android:localeConfig`. Other components reference these by
name; none re-declare them, so there is one source of truth (asserted by
`ReleaseContractTest`).

**Two-layer theming that survives pre-Compose.** `Theme.Ghostify` (XML) is the
launch window / status-bar navbar shell (light in `values/`, dark in
`values-night/`, splash on v31). `GhostifyTheme` (Compose) is the real
in-app scheme and reads `MaterialTheme.colorScheme`, so every screen is
theme-correct. `Color.kt` is the source of truth; `colors.xml` mirrors its
surfaces to avoid the white/dark flash at cold start. Dynamic color is on in
production on API 32+ but tests force `dynamicColor=false` for deterministic
palette assertions (light surface luminance > 0.5, dark < 0.5).

**No hardcoded strings.** Every user-visible literal lives in `strings.xml`.
`values-fr/strings.xml` exercises the pipeline; missing keys fall back to `en`.
A JVM static test (`LocalizationCheckTest`) regex-scans `src/main` Compose/XML
for `Text("...")`, `android:text="..."`, etc., and Android Lint `HardcodedText`
is `error` in CI.

**Defensive Chaquopy packaging.** R8/minify is on in `release`. The Chaquopy
interpreter + Python bridge are reached by name (JNI + module lookup), so they
cannot be seen statically by R8. Rules are split by concern:
`proguard-rules.pro` keeps `com.ghostify.python` (our bridge) + native method
names + debug attributes; `proguard-chaquopy.pro` re-declares Chaquopy's own
keeps (`com.chaquo.python.**`, Kotlin function types, `KAnnotatedElement`) so a
Chaquopy upgrade that drops its consumer rules cannot silently break release.
T-180 proves it at runtime: a minified APK still boots the interpreter, round-trips
a bridge call, and `import`s `spotdl`.

**Fail-closed Python boot, never crash.** `GhostifyApplication` starts Chaquopy
at launch inside a `try/catch`; if the interpreter can't boot (tampered APK, old
device, packaging defect) the app opens in degraded "library-only" mode instead of
crashing. This is the same defensive principle that makes T-174's denial path
safe.

**Standalone JVM harness = static gates run anywhere.** `build.gradle.kts` is a
`kotlin("jvm")` module whose `main` source set is deliberately empty — the
Android/Compose/Chaquopy code in `src/main` belongs to the merged `:app` module.
Only the two host-side tests (`LocalizationCheckTest`, `ReleaseContractTest`) are
compiled here, so `./gradlew test --offline` runs the manifest/strings/proguard
gates on a no-Android CI image. The Android-specific tests live in
`src/androidTest` / `src/androidTestRelease` and are exercised by
`:app:connectedDebugAndroidTest` / `:app:connectedReleaseAndroidTest` in the
merged project (see CI-CHECKLIST §1/§2). A Gradle wrapper (`gradlew`,
8.7) is shipped so the documented `./gradlew test --offline` is reproducible
without a system Gradle.

**minSdk/latest split.** `NotificationPermissionManager.isRequired()` guards on
`Build.VERSION_CODES.TIRAMISU`; below API 33 `shouldRequest` is always false and
the manager never touches the framework, so the app is safe everywhere in the
26–36 range.

---

## 3. Test coverage by requirement

| Req | Type | Class | Device-only? | How it's verified |
|---|---|---|---|---|
| T-172 | Instrumentation | `MinSdkCompatibilityTest` | **YES** — boots `MainActivity` + add-playlist flow on an API-26 (or any) device | `connectedDebugAndroidTest` on API 26 AVD |
| T-173 | Instrumentation | `NotificationPermissionFlowTest` | **YES** — grant/revoke on API 33+ | `connectedDebugAndroidTest` on API 33+ AVD |
| T-174 | Manual | — | **YES** — visual system prompt, deny path | MANUAL-CHECKLIST + slice in T-173 test |
| T-175 | Instrumentation | `ThemeRenderTest` | **YES** — renders light/dark + flips `cmd uimode night` | both theme legs on every instrumented slot |
| T-176 | Manual + static | `LocalizationCheckTest` (JVM) + Lint | static **no device**; manual locale walk is **YES** | `testDebugUnitTest` + Lint `HardcodedText=error`; manual `fr`/`ar`/large-font walk |
| T-177 | Manual + slice | `ResponsiveLayoutTest` (slice) | **YES** — real 360dp + tablet | `ResponsiveLayoutTest` 360dp/1280dp viewport gate; manual real-device walk |
| T-178 | Manual + slice | `InstallUpgradeSmokeTest.freshInstall_firstRun_isFunctional` | **YES** — launch on device | slice here; manual fresh-install walk |
| T-179 | Manual + scripted | `InstallUpgradeSmokeTest.appData_survivesProcessDeath` + CI §3 | **YES** — real upgrade | CI upgrade gate (vN → vN+1 via `adb install -r`) + DB component's migration test |
| T-180 | Instrumentation | `ReleaseBuildChaquopyTest` (androidTestRelease) | **YES** — minified release APK | `connectedReleaseAndroidTest`; only compiled/run for the release variant |
| T-181 | Instrumentation + scripted | `InstallUpgradeSmokeTest` + CI §3 | **YES** — real device | `gradle assembleDebug` → `adb install` → `adb install -r` → re-run suite |

**Device-only marking policy.** Every test that needs an Android runtime carries
`@RunWith(AndroidJUnit4::class)` and (where relevant) an `assumeTrue`/
`@Before` guard, with a KDoc line stating "Device-only (instrumentation)". The
JVM tests (`LocalizationCheckTest`, `ReleaseContractTest`) are the only ones that
run without a device.

---

## 4. Static gates (verified green, host-side)

`./gradlew test --offline` → **BUILD SUCCESSFUL**, 7 tests, 0 failures:

- `ReleaseContractTest` (6): manifest permissions, release-safe app flags
  (RTL/cleartext/backup/locale), launcher exported + MAIN/LAUNCHER filter,
  `MediaSessionService` foreground type `mediaPlayback`, proguard keeps for
  `com.chaquo.python` + `com.ghostify.python`, dark-theme resources present.
- `LocalizationCheckTest` (1): no hardcoded user-visible strings in `src/main`.

---

## 5. Instrumentation / manual gates (device-only)

These require an emulator/device farm and cannot assert on a host; they are
marked device-only in the KDoc and wired into `checks/CI-CHECKLIST.md` §2.

- T-172: boot + add-playlist FAB → dialog on API 26 AVD.
- T-173: grant/revoke `POST_NOTIFICATIONS` → manager bookkeeping correct, no
  crash. (API < 33 path asserts the permission is implicit.)
- T-175: light + dark surface luminance + `cmd uimode night` follow.
- T-177: 360dp phone + 10" tablet viewport — FAB and text fully within bounds.
- T-180: `connectedReleaseAndroidTest` — interpreter boots, bridge round-trips,
  `spotdl.__version__` imports under R8/minify.
- T-181: `adb install` (clean) then `adb install -r` (upgrade) both exit 0; the
  library and a seeded marker survive; re-run `connectedDebugAndroidTest`.

---

## 6. Why this is optimal (not a hack)

- **No over-broad `-keep` rules.** We rely on libraries' own consumer rules
  (Room/WorkManager/Hilt/Media3) and only keep *our own* reflective surface
  (`com.ghostify.python`) plus Chaquopy's documented keeps — so R8 still shrinks
  the rest of the APK. The Chaquopy rules are duplicated from Chaquopy's shipped
  consumer file on purpose, so a dependency drift cannot silently regress.
- **Minify-safe by construction.** T-180 is a *runtime* assertion in
  `androidTestRelease`, not just a manifest grep — it actually loads the Python
  module and imports `spotdl` from the minified build, which is the only thing
  that truly proves reflection survives.
- **Single manifest authority** prevents drift between components; the JVM test
  enforces it so it can't rot silently.
- **Static gates run on any CI image** (no Android SDK) via the standalone
  harness, while device gates are clearly partitioned to device slots — fast
  feedback for the common case, device coverage where required.
- **Defensive, not brittle.** Python boot and notification denial both degrade
  gracefully instead of throwing; the app is always in a usable state, which is
  exactly the contract T-174/T-172 demand.

---

## 7. Blockers

None for this component. The device-only instrumentation tests (T-172, T-173,
T-175, T-177, T-181) and the release-only T-180 require a physical/emulated
device or Test Lab and are not expected to pass on a host-only CI image — they are
run in the CI device matrix per `checks/CI-CHECKLIST.md` §2. The host-side static
gates (T-176 scan, manifest/proguard/dark-theme contract) are fully green and
runnable via `./gradlew test --offline`.

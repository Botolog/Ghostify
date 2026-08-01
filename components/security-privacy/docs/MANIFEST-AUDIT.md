# Manifest audit — app-data isolation (T-169)

T-169 = "app data not exported to other apps." Enforced in two halves.

## Static half (local, no device)

`ManifestAudit` parses every `AndroidManifest.xml` in the repo with an
XXE-hardened `DocumentBuilderFactory` and reports:

| Rule | What | T-169 |
|------|------|-------|
| `EXPORTED_COMPONENT` | any `activity`/`service`/`receiver`/`provider` with `android:exported="true"` that is not the launcher activity or an allowlisted OS hook | ✓ |
| `PROVIDER` | any `<provider>` (export surface we don't need — Room runs in-process) | ✓ |
| `STORAGE_PERMISSION` | `READ/WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_*` | ✓ |
| `CLEARTEXT_MANIFEST` | `android:usesCleartextTraffic="true"` | T-171 |
| `DEBUGGABLE` | `android:debuggable="true"` | hardening |

**Allowlist** (`EXPORTED_ALLOWLIST`): only `androidx.media3.session.MediaButtonReceiver`
is exported to the OS — it is a system MediaSession hook, not an app-export surface.
The launcher activity is exported by design (`MAIN`/`LAUNCHER`).
Everything else is `android:exported="false"`.

The same rules are emitted by `SecurityScan` / `security_scan.py` (`EXPORTED_COMPONENT`,
`STORAGE_PERMISSION`, `CLEARTEXT_MANIFEST`, `DEBUGGABLE`, `PROVIDER`) so the repo-wide
scan fails the build on any regression.

**XML parser hardening:** `FEATURE_SECURE_PROCESSING`,
`disallow-doctype-decl=true`, external-general/parameter-entities disabled,
`expandEntityReferences=false` — the audit tool must not be an XXE vector.

## Device half (instrumentation, needs merged app + device)

`AppDataIsolationInstrumentedTest` (src/androidTest) runs on
`:app:connectedDebugAndroidTest` and proves at runtime that:
1. `PackageManager` reports no exported component beyond the allowlist,
2. no broad storage permission is granted (app-scoped storage only),
3. a file written to `getExternalFilesDir(null)` is not world-writable.

This is **device-only** (T-169 instrumentation per TESTS.md) and is not executed
in the local JVM harness; the static half above is the local gate.

## Run locally

```
# Python gate (no toolchain) — scans the whole repo, audits every manifest
python3 components/security-privacy/scripts/security_scan.py /root/ghostify

# Full gate — python scan + the JVM unit tests (daemon disabled in gradle.properties)
bash components/security-privacy/scripts/security-scan.sh /root/ghostify

# JUnit: ManifestAuditTest.repoManifestsAreClean, allowsLauncherAndMediaButtonReceiver,
#         flagsBroadStoragePermissions, flagsExportedComponentsWithoutAllowlist,
#         flagsCleartextAndDebuggable, rejectsExportedProvider
# JUnit: SecurityScanTest.noErrorFindingsAcrossTheRepository
```

Current repo manifests audited: `release`, `python-runtime/build-config`,
`player-core/android/app`, `database/src/androidTest`, all in `components/security-privacy`.
All clean (the app manifest is `usesCleartextTraffic="false"`, no broad storage
perms, no providers, launcher+MediaButtonReceiver only).

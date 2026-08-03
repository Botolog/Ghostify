# Ghostify — build & test cheat sheet

Everything is run from the **`app/`** toolchain (not `android/`). The build machine
is **arm64 (aarch64)**, has **no attached device / emulator**, and Gradle must be
run with `--no-daemon`. Work happens on the branch **`fix/spotdl-android-runtime`**
and is merged into `master` when runtime-validated.

> Repeated command: everything beneath uses the Gradle project at `app/` and the
> module is `:app`. The built APK lands at
> `app/app/build/outputs/apk/debug/app-debug.apk`.

---

## 1. Environment rules (never "fix" these)

Kept in `app/gradle.properties` on purpose:

- `kotlin.compiler.execution.strategy=in-process` — the Kotlin daemon cannot start
  on this constrained aarch64 box.
- `android.aapt2FromMavenOverride=/opt/aapt2/aapt2` — Google publishes no arm64
  aapt2; `/opt/aapt2/aapt2` is a wrapper that runs the x86_64 `aapt2.bin` under
  `qemu-x86_64-static -L /tmp/x86root`.

Always pass `--no-daemon` to Gradle. If a Gradle/incremental state looks wrong,
remove `app/app/build/` and rebuild from scratch (see §7).

Python on the **build machine** (used for Chaquopy pip resolution + local Python
testing): a 3.11 interpreter must be on `PATH` (Chaquopy's `buildPython`).

---

## 2. Compile the debug APK

```bash
cd /root/ghostify/app
./gradlew :app:assembleDebug --no-daemon
```

On success the APK is `app/app/build/outputs/apk/debug/app-debug.apk`.

Notes:

- **Never truncate build errors.** If a task fails, read the full stack trace /
  log. Run with `--stacktrace` (and optionally `--info`) when a failure isn't
  obvious.
- The first build (or after a clean) runs Chaquopy's `pip install` for the vendored
  deps and is slow; later builds are incremental.
- The `chaquopy` block installs the vendored packages from `app/`:
  - `./python-rapidfuzz` — pure-Python difflib fallback for `rapidfuzz>=3`.
  - `./python-curl-cffi` — pure-Python requests shim satisfying `curl_cffi.requests`.
  - `./python-spotapi`, `./python-spotipyfree` — anonymous Spotify client chain.
  - `./python-spotdl` — vendored spotdl **4.5.2** (web-UI deps stripped).
  - `yt-dlp` — pulled from PyPI.
- Keep this vendored chain intact; do **not** switch to spotdl 3.9.6 or to an
  official Spotify client.

---

## 3. Running a clean (~full) build

When the APK is stale, ambiguous, or builds behave oddly:

```bash
cd /root/ghostify/app
rm -rf app/build
./gradlew :app:assembleDebug --no-daemon
```

This reinstalls all Python requirements. Expect it to take several minutes.

---

## 4. Local Python testing without a device

The Python bridge (`app/app/src/main/python/ghostify_dl.py`) is designed to run
identically under plain CPython and under Chaquopy, so most logic + the anonymous
Spotify fetcher can be validated on the host with the Chaquopy-built interpreter.

Interpreter with the vendored packages installed:

```bash
PY=/root/ghostify/app/app/build/python/env/debug/bin/python
$PY /tmp/gy_patch_test/fetch_test.py <playlist_id>
```

Convenience scripts used during dev (in `/tmp/gy_patch_test/`):

| script | purpose |
|--------|---------|
| `fetch_test.py` | normal metadata fetch (default playlist, prints requests/time) |
| `fetch_notimeout.py` | fetch with forced per-request timeout |
| `hash_test.py` | spotapi part-hash discovery / stale-hash recovery |
| `e2e_test.py` | full fetch incl. YouTube resolution |
| `shim_test.py` | verify curl_cffi shim injects a default timeout |

They are throwaway — recreate them as needed rather than treating them as code.

> What these **cannot** validate: Chaquopy's Java↔Python marshalling
> (`dict → java.util.Map`), which only happens across the real bridge. See §7
> for why that is verified statically instead of via an emulator here.

---

## 5. JVM / JUnit tests

- `testImplementation("junit:junit:4.13.2")` and kotlinx-coroutines-test are wired
  in `app/app/build.gradle.kts`.
- **Chaquopy 17 does not run the Python interpreter in local JVM unit tests**, so
  `./gradlew :app:testDebugUnitTest` can only test non-Python logic.
- Component test suites live in `components/*/tests/`. To run a component's JVM
  tests, run Gradle from that component's own `gradlew` (they are separate Gradle
  builds, not part of the app module).

Example (component-level, where applicable — the exact task name is
`./gradlew test`):

```bash
cd /root/ghostify/components/<component>
./gradlew test --no-daemon
```

---

## 6. Git workflow

Regular commits, in English, on the feature branch:

```bash
git -C /root/ghostify add -A
git -C /root/ghostify commit -m "<scope>: <summary>"
```

Style (imperative, short one-liners — see the existing `git log`):

```
fix: accept PyObject in track hooks so Chaquopy stops rejecting Python dicts
fix: bound every spotapi request timeout and persist discovered hashes
build: vendor spotdl 4.5.2 and strip web-UI deps for Android
```

Merge into `main` only when the end user has validated the APK on a real device.

---

## 7. Verifying the shipped APK contains a code change (no device needed)

The APK combines several `classesN.dex` files; regressions between "the source
says fixed" and "the installed build is fixed" are caught by dumping the actual
DEX method signatures.

`dexdump`/`objcopy` from the SDK are broken on this host (they are x86_64 and the
machine is aarch64 — `find` the classes with the raw tool instead). A pure-Python
DEX method-signature dumper is maintained at
`/tmp/dex_check/dexparse.py`:

```bash
cd /tmp/dex_check
for f in classes*.dex; do
  unzip -o -q /root/ghostify/app/app/build/outputs/apk/debug/app-debug.apk "classes*.dex" -d /tmp/dex_check
done
python3 dexparse.py classes13.dex "Lcom/ghostify/trackdownload/TrackDownloadBridge\$HookAdapter;"
```

You should see (no `java.util.Map` anywhere):

```
onDownloadStart(Lcom/chaquo/python/PyObject;):V
onDownloadComplete(Lcom/chaquo/python/PyObject;):V
onProgress(I,Ljava/lang/String;):V
```

### Why a `dict → java.util.Map` TypeError is impossible in the current build

Chaquopy's `conversion.pxi` `p2j` only raises

> `Cannot convert {type}.__name__} object to {sig}`
>   (⇒ `Cannot convert dict object to java.util.Map`)

when a Python object is passed as a **Java parameter whose type is exactly**
`Ljava/util/Map;` (the dict is neither a str/int/float, nor
assignable to that specific object type — it falls off the end of `p2j`).
When the parameter is `Lcom/chaquo/python/PyObject;`, line 238 happily converts
**any** Python object (including a `dict`) via `p2j_pyobject_ref`. So:

- If the APK's `HookAdapter` hooks are typed `PyObject` (see dump above) the
  download hook path **cannot** raise that TypeError anymore.
- The fetch path (`fetch_playlist`) never passes a dict into a Java method at all
  it returns a dict to Kotlin, which converts it via `toJava(Map)` on the Java
  side (a different, working conversion).

Therefore, a device that still shows `Cannot convert dict object to java.util.Map`
is running a **stale APK** (installed before commit `79e9268`). Force a clean
reinstall (§3 then install the fresh APK) and confirm the app version/commit
before assuming a code-path problem.

---

## 8. Deployment / device testing

There is no adb/emulator in this environment (qemu adb won't start; binfmt_misc
is off). The loop is:

1. Build the APK here (§2).
2. Ship `app-debug.apk` to the end user on a physical device.
3. End user installs and tests; report logcat (`python.stderr`, `python.stdout`)
   and the exact app version.
4. Iterate. Commit on the branch; merge only after a device-pass.

**When asking the user to retest after a fix, bump `versionCode`/`versionName` in
`app/app/build.gradle.kts` so an old install cannot be mistaken for the new one.**
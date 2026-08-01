# HTTPS enforcement (T-171)

Ghostify talks to exactly two network endpoints — Spotify's public web API
(metadata only, project §7/§8) and YouTube (audio resolution, via spotdl/yt-dlp).
Neither ever uses cleartext HTTP. HTTPS is enforced in **three independent
layers** so that no single bypass can silently leak traffic to `http://`.

## Layer 1 — manifest default-deny

`android/app/src/main/AndroidManifest.xml` declares:

```xml
<application android:usesCleartextTraffic="false" ... >
```

(Verified statically by `ManifestAudit` → `CLEARTEXT_MANIFEST` and by the repo
scan `SecurityScan` → `R_CLEARTEXT_MANIFEST`.) With this flag, the Android
network stack refuses plain-`http://` sockets before any data leaves the
device, regardless of what the app code asks for.

## Layer 2 — runtime gateway (`HttpPolicy`)

Because a user *pastes* a playlist URL, the dangerous cases are built at
runtime and no static pattern can see them. Every network entrypoint routes
through `HttpPolicy.requireHttps(url)` (or a client that calls it) before any
socket is opened:

* `requireHttps(url)` — throws `HttpsRequiredException` for any non-`https://`
  scheme (`http://`, `ftp://`, no scheme, …) and returns the URL only if it is
  HTTPS. A pasted `http://open.spotify.com/...` is rejected in-app, never sent.
* `isHttps(url)` — scheme predicate for guards/early validation.
* `stripUserInfo(url)` — drops any `user:pass@` embedded in a URL so
  pasted credentials cannot be retained or logged.

spotdl (`ghostify_dl.py`) and yt-dlp run *inside* the Chaquopy interpreter;
they inherit the app's network stack (and therefore `usesCleartextTraffic=false`),
and the Kotlin `PythonBridge` validates any URL handed to Python with
`requireHttps` first. YouTube URLs resolve to `https://www.youtube.com`, and
spotdl's `requests` calls target `https://api/spotify.com`.

## Layer 3 — static scan

`SecurityScan` (Kotlin) and its stdlib-only mirror `scripts/security_scan.py`
flag any `http://` literal in shipped source as `CLEARTEXT_HTTP` (T-171), after
allowing only known-safe schemes/domains (e.g. `schemas.android.com` XML
namespace, `localhost` in tests, `example.com` placeholders). Any real `http://`
network target fails the build and the CI gate.

## Tests (local, no device)

`HttpPolicyTest` (JVM) — all pass:
- accepts `https://`, `HTTPS://`, youtu.be
- rejects `http://`, `ftp://`, malformed, empty
- `requireHttps` throws `HttpsRequiredException` on cleartext
- `requireHttps` returns the normalized HTTPS URL
- `stripUserInfo` removes embedded credentials
- a `FakeHttpClient` wrapper refuses to fetch `http://`

`SecurityScanTest` → `no error findings across the repository()` covers
`CLEARTEXT_HTTP` + `CLEARTEXT_MANIFEST` repo-wide (pass).

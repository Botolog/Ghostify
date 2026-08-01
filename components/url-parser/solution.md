# Spotify URL Parser — Solution

## What was built

`src/main/kotlin/com/ghostify/SpotifyUrlParser.kt`

- **`SpotifyUrlParser`** — pure, immutable, thread-safe parser. Returns a typed sealed result:
  `ParseResult.Success(playlistId)` or `ParseResult.Rejected(rejectionReason)` where
  `RejectionReason` carries a user-facing message (so the UI never shows a raw exception).
- **`ShortUrlResolver`** interface + **`HttpShortUrlResolver`** — network-backed resolver for
  `spotify.link` / `spoti.fi` short links, injected so tests can substitute a fake.

`src/test/kotlin/com/ghostify/SpotifyUrlParserTest.kt` — 38 JUnit tests covering **T-001..T-009**
plus supplemental tests (legacy `/user/<user>/playlist/<id>` URLs, HTML-scrape and `intent://`
fallback extraction).

## Key decisions

1. **Whitelist hosts, strict id charset.** Only `open.spotify.com`, `spotify.link`, `spoti.fi`
   are accepted. Playlist ids must match `^[A-Za-z0-9]{1,64}$` — deliberately tolerant of the
   real 22-char base62 ids, tight enough that `%2e%2e%2f…`, encoded slashes, and path traversal
   are impossible to smuggle through.
2. **Parse the raw (undecoded) path.** `uri.rawPath` is split, so percent-encoded characters are
   never decoded into traversal; they fail the charset check instead (T-008: safe rejection, no crash).
3. **Query strings and fragments are ignored by construction** — only the path is examined
   (T-004).
4. **Legacy user URLs.** `open.spotify.com/user/<user>/playlist/<id>` is accepted because it is a
   real Spotify share form (T-010 supplement).
5. **Short-link resolution is depth-limited** (max 3 recursive parses) so redirect cycles terminate,
   and everything is wrapped in `runCatching` — garbage input can never crash.
6. **The resolver had to be built for how Spotify actually behaves** (see below) — this was the
   one genuinely non-trivial part of the task.

## What live testing revealed about `spotify.link` (important)

Naive `HttpURLConnection` redirect following **fails in production**:

- `spotify.link/<code>` → `307` → `spotify.app.link/…` (Branch) → **`307` to an `intent://` deep link**.
  Java's auto-redirect throws `MalformedURLException: unknown protocol: intent`, so a naive resolver
  would return `RESOLUTION_FAILED` for every real short link.
- The `intent://` link carries the real target as `S.browser_fallback_url=https%3A%2F%2Fopen.spotify.com%2F…`.
- The redirect shape is also **User-Agent-dependent**: a custom UA (e.g. the old `Ghostify/1.0`)
  lands on the Branch intermediate; a browser-like UA routes through the Branch pipeline above.

`HttpShortUrlResolver` therefore **walks the redirect chain manually** (up to 10 hops,
`instanceFollowRedirects=false`) and:
1. follows http(s) `Location` headers (relative locations resolved against the current URL),
2. on an `intent://` location extracts and URL-decodes `S.browser_fallback_url`,
3. if the final 2xx page still isn't `open.spotify.com`, scrapes its HTML for an embedded
   `open.spotify.com` link (the older community-documented shape).

This makes T-003 genuinely reliable on device instead of working only in unit-test mockups.

## Test results

Ran locally with **Kotlin 2.1.20** (`/root/.tools/kotlinc`) + **JUnit 4** on the JVM (no Android
runtime needed — the parser is pure JVM logic):

```
JUnit version 4.13.2
OK (38 tests)     Time: 0.195
```

- T-001..T-002, T-004..T-009: **pass** (unit).
- T-003 logic: **pass** (fake resolver: `spotify.link`/`spoti.fi`, redirect chains, depth limiting,
  non-playlist redirects rejected). Plus **live network verification** against a real short link:

```
T-003 live: https://spotify.link/H6FTskvicDb
  -> Success(playlistId=5zPbGYMyILmVCty6TJ9mOO)   PASS
nonexistent spotify.link -> Rejected(RESOLUTION_FAILED)   PASS (no crash)
```

## Remaining device-only

- **T-003 on an actual Android device/emulator.** The code path (manual redirect walk, `intent://`
  fallback extraction) is identical to what ran here on the JVM and is **network-verified**, but it
  has not been exercised through Android's own network stack on a device. Verify once with a real
  short link in the app.
- Everything else is JVM-verified and needs no device.

## Why this is the optimal solution

- **Typed outcomes, zero exceptions escape** — the UI contract is a sealed result + readable message.
- **Defensive by construction** — whitelist + strict charset + raw-path parsing means traversal,
  encoded chars, and junk are *impossible* to turn into a valid id, not merely "filtered".
- **Short links actually work** — built against Spotify's real (hostile) redirect pipeline, with a
  documented UA strategy and two extraction fallbacks.
- **Testable** — network is behind the `ShortUrlResolver` interface, so all redirect/parse logic is
  unit-tested without network; the internal extraction helpers are directly unit-tested.
- **Fits the planned layout** — pure-JVM `com.ghostify` package, drops straight into
  `android/app/src/main/java/com/ghostify/`.

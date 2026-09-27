# tools/

Host-side developer tools. Nothing here ships in the APK and nothing here is
imported by the app.

## `jev_selection_demo.py` — original vs. JEV/OpenRouter video pick

Compares, for one Spotify track, the YouTube video the app would have picked
before the advisory JEV selector existed with the one JEV would pick now. Both
sides come from the live app code:

| side | code path |
|------|-----------|
| original | `ghostify_dl._first_result_video_id` (first provider result with a video id) — the value `_select_yt_id` keeps when JEV declines |
| JEV | `jev_selector.build_shortlist` (deterministic shortlist) + `jev_selector.decide` (OpenRouter Decisions API), reached through `ghostify_dl.compare_yt_selection` |
| metadata | `spotdl.types.song.Song.from_url` with the app's shared `SpotifyClient` (`ghostify_dl._ensure_spotify_client`) |
| candidates | `ghostify_dl.search_yt_candidates` — YouTube Music first, plain YouTube as fallback, same query as the download path |

### Usage

```bash
cd <repo root>                     # the directory that contains app/ and tools/
python tools/jev_selection_demo.py https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b
```

| flag | meaning |
|------|---------|
| `--limit N` | candidates per provider (default 10) |
| `--timeout S` | per-track budget for the YouTube search and the JEV call (default 8) |
| `--offline` / `--no-jev` | force the deterministic fallback; never calls OpenRouter |
| `--download` | actually download the chosen video (compare-only by default) |
| `--output-dir DIR` | destination for `--download` (default `.`) |
| `--no-color` | plain output even on a terminal |
| `-v` / `--verbose` | dump the JEV request before it is sent, then the per-option confidences of the live answer, and show `ghostify_dl` / `jev` debug logs |

The report prints the song metadata, the provider and query, the candidate and
shortlist counts, both chosen video ids/URLs, whether they agree, the JEV
confidence and reason, and which id the app would actually use.

### `-v`: the JEV request

`-v` prints the request that is about to be sent, in a block of its own ahead
of the report — the HTTP method, the endpoint, the headers and the complete
pretty-printed JSON body, so payload shape can be checked without a proxy:

```text
Verbose - JEV request (about to be sent)
------------------------------------------------------------------------
  method        : POST
  endpoint      : https://openrouter.ai/api/alpha/decisions
  timeout       : 4.0s
  headers       :
    Authorization  : Bearer <redacted>
    Content-Type   : application/json
  request body  :
    {
      "model": "typesafe/jev-1.13",
      ...
    }
------------------------------------------------------------------------
```

The dump comes from a wrapper around the very transport the app would use, so
it cannot drift from the real request, and it only ever *prints*: the payload,
the decision and the exit code are the same with and without the flag. The
plain report is byte-identical either way — the block is added in front of it.

- **The key is never printed.** `Authorization` (and any other credential-named
  header) keeps its name and scheme and loses its value
  (`Bearer <redacted>`); a `sk-`/`or-v1-`/`Bearer`-shaped value is scrubbed
  again anywhere else in the block, so a stray key in the body would not
  survive either. Response bodies are never dumped.
- **A run with nothing to send says so** instead of staying silent, naming the
  reason: no key, `GHOSTIFY_JEV_ENABLED=0`, `--offline`, or a shortlist with
  nothing to decide.

### `-v`: the per-option confidences

When the live answer carries per-option data, a second block closes the verbose
output — one row per shortlisted candidate with the confidence the model gave
it, highest first, and the option the app actually used marked `*`:

```text
Verbose - JEV option confidence (live answer)
------------------------------------------------------------------------
  model         : typesafe/jev-1.13
  options       : 3 in the shortlist
  answer conf   : 90.0%
  chosen        : a0000000002 - the option the app used
       video id       conf  candidate (title - channel)
  * marks the option JEV chose
  * 1  a0000000002   60.0%  Song 2 - Artist
    2  a0000000001   30.0%  Song 1 - Artist
    3  a0000000000   20.0%  Song 0 - Artist
------------------------------------------------------------------------
```

- **Nothing is re-requested and no second decision is made.** The numbers are
  read out of the very response the wrapped sender returned, the shortlist is
  the one the request was built from, and the titles are the candidates that
  request already named. `answer conf` is the confidence of the answer as a
  whole; the `conf` column is the per-option probability behind each row.
- **The shortlist is the allow-list.** A probability for an id JEV was never
  offered is ignored and counted in a note, and a choice the selector refused
  is named in a note without ever being marked.
- **Nothing clean fails the run.** A probability that is missing, a string, a
  boolean, a NaN or a non-number reads `n/a`, a value outside `0..1` is shown
  exactly as reported, and each case is summarised in a `note:` line. An answer
  with no per-option data at all — or no answer, no key, `--offline`, an
  unambiguous shortlist — adds no block and no bytes to the plain report.
- **No response body is printed.** Only shortlisted video ids, the reported
  numbers and candidate titles the request already carried; the block is
  `redact_secrets`-scrubbed like the request dump, and a credential-shaped
  title loses its value.

### Reading the JEV status

The last line states what the JEV side actually did, and why:

| status | meaning |
|--------|---------|
| `inactive - OPENROUTER_API_KEY is not set` | no key; the deterministic pick is used, and the two `export` lines below it enable JEV |
| `forced offline (--offline); …` | `--offline` forced the deterministic pick |
| `configured but disabled (GHOSTIFY_JEV_ENABLED=0) - …` | a key is set but the flag is `0` |
| `enabled (OPENROUTER_API_KEY set) - JEV picked …` | accepted; the effective URL is JEV's pick |
| `enabled (OPENROUTER_API_KEY set) - answer rejected: <reason> (<label>) […]` | refused; the deterministic pick stays effective |
| `enabled (OPENROUTER_API_KEY set) - request failed (HTTP 401, provider code auth_error)` | the OpenRouter call failed; the status and the short provider code are reported, never the body |

Rejection labels are `no_answer`, `answer_type`, `unknown_id`, `low_confidence`
and `probability_disagreement`; the bracketed detail holds only numbers
(`[shortlist=3]`, `[confidence=0.21 < 0.60]`, `[p=0.20, best p=0.80]`). A long
reason is trimmed on a word boundary in the status line — the `JEV reason` field
above always shows it in full — and the effective fallback URL is always shown.

### Colour

Headings, field labels, the two decision columns, the statuses and the URLs are
coloured when stdout is a terminal. Colour is off when stdout is redirected or
piped, when `NO_COLOR` is set (https://no-color.org), when `TERM=dumb`, or with
`--no-color`. Escapes only ever wrap a whole label or value — never sit inside
one — so a URL stays copy-pasteable and `python tools/jev_selection_demo.py … |
less` shows exactly the plain report.

### Safety

- **No OpenRouter call without a key.** With `OPENROUTER_API_KEY` unset, JEV is
  inactive and the report says so, showing the deterministic result and how to
  enable it (`export OPENROUTER_API_KEY=<your key>`; see `.env.example`).
  `GHOSTIFY_JEV_ENABLED=0` / `--offline` force the same fallback.
- **The key is never printed** — not in the report, not in the `--verbose`
  logs, not in the `-v` request dump (the auth header is redacted there too)
  and not in the "no key" hint (which prints a placeholder only). Provider error
  bodies, `Authorization` headers and raw exception messages are never rendered
  either: a failed call reports the HTTP status and a short provider code only.
- **No downloads unless `--download`** is passed.
- Graceful failures with distinct exit codes: `2` bad usage/URL, `3` missing
  dependency, `4` Spotify metadata failure, `5` YouTube provider failure, `6` no
  candidates. `--help` works even without the optional dependencies, because
  `ghostify_dl`/`spotdl` are imported lazily and `sys.path` is bootstrapped to
  the vendored `app/app/python-*` packages.

### Tests

```bash
python -m pytest app/app/src/test/python/unit/test_jev_selection_demo.py -q
```

Offline only: URL parsing, formatting, no-key/forced-fallback behaviour, the
JEV rejection/HTTP status reporting, colour enable/disable, the `-v` request
dump (endpoint/body shown, `Authorization` and key-shaped values redacted,
nothing shown when no request is made), the `-v` per-option confidence table
(all options, sorting, the chosen marker, `n/a` for missing and malformed
probabilities, byte-identical plain report) and mocked candidate comparisons.
No network, no OpenRouter.

"""Score tuner — desktop-only TUI to edit Spotify + YT candidates and rescore live.

Usage:
    python tools/score_tuner.py <spotify-track-url> [--limit N --timeout S]
    python tools/score_tuner.py <spotify-track-url> --no-tui
    python tools/score_tuner.py --stub --no-tui          # offline smoke test
    python tools/score_tuner.py --stub --no-tui -v       # verbose smoke test
    python tools/score_tuner.py --help

``-v`` / ``--verbose`` shows the requests and the real query used: the pooled
``create_song_title`` query sent to every provider, the per-provider params
(YTM ``filter``/``limit``/``ignore_spelling``, YT ``ytsearch10:``), provider
order + per-provider result counts, and the underlying
``YTMusic.search`` / ``YoutubeDL.extract_info`` calls with secrets redacted.
In curses mode the pane toggles with ``v``; in ``--no-tui`` mode the block
prints ahead of the score table. The dump comes from a transport wrapper
around the very ``get_results`` the app uses, so it can only ever *show*
the request, never change it.

Layout: panel 1 = original Spotify Song fields (name, artists, album_name,
duration, isrc, explicit); panels 2..N = YT/YT-Music candidate Result fields
(name, artists/author, album, duration, views, channel/author, url, verified,
source). On wide terminals the song and candidate panels sit side by side;
on narrow terminals they stack — the score table is always below.

TUI library choice (desktop-only):
    * Prefer ``textual`` when importable, else ``urwid``, else stdlib
      ``curses``. This checkout has neither textual nor urwid installed, so
      the tool uses stdlib ``curses`` — zero new dependencies, no pip install
      needed. Optional richer UI later: ``pip install textual`` (desktop only).
    * NEVER add any TUI dependency to ``app/build.gradle(.kts)`` Chaquopy
      ``pip`` — the app bundle must stay lean. This file lives in ``tools/``
      (host-only, never imported by the app).
    * ``--no-tui`` plain mode prints the same song/candidate/score table to
      stdout without curses (useful for pipes, CI, and the stub smoke test).

Controls (curses mode):
    Up/Down or Tab/Shift-Tab .... move focus across all editable fields
    Enter ........................ edit focused field (EDIT mode)
    In EDIT mode: type to change, Backspace deletes, Left/Right moves cursor,
                  Enter/Esc leaves EDIT mode (rescores on every keystroke)
    [ / ] or PgUp/PgDn ........... switch candidate (also , / .)
    a ............................ add blank custom candidate
    d ............................ duplicate current candidate
    x ............................ remove current candidate
    r ............................ reset song + candidates to fetched values
    s ............................ toggle auto-sort by total score (default on)
    m ............................ toggle mouse on/off (default off/disabled;
                                   when on: wheel scrolls score view only,
                                   clicks do nothing; mousemask(0) when off)
    v ............................ toggle verbose query/request pane (-v content)
    Mouse (disabled by default): when enabled with 'm', wheel up/down
           scrolls the score-table viewport itself (selection unchanged);
           click-to-focus / click-to-switch are removed (clicks ignored).
    Colors honor NO_COLOR / TERM=dumb (mono fallback); --no-tui stays plain.
    h ............................ help overlay (any key closes)
    q or Ctrl-C ................... quit

Real logic (never cloned):
    * Song built via live ``spotdl.types.song.Song.from_url`` (then mutated
      copies via ``dataclasses.replace``); candidates are the live objects
      returned by ``ghostify_dl.search_yt_candidates`` (pooled YTM+YT,
      deduped), mutated as copies.
    * Final score per candidate comes from live
      ``spotdl.utils.matching.order_results`` run on the pooled edited list
      (single pass, pure CPU). No scoring formula is copied here.
    * Breakdown rows call the same live helpers: ``calc_set_match`` /
      ``calc_set_score`` (set/title), ``calc_album_set_match`` (album),
      ``calc_time_match`` (duration), ``check_forbidden_words`` +
      ``FORBIDDEN_WORD_PENALTY`` (forbidden -5), ``ENABLE_UNRELATED_WORDS``
      flag (unrelated off), channel +10/-15 detection via live ``slugify``
      (same condition as ``order_results``), YTM +5 via live
      ``_is_youtube_music_source`` + ``YTM_SOURCE_BONUS``, views via cached
      ``Result.views`` only (live ``get_best_matches`` shows the top-8 views
      contention without network).
    * No per-keystroke network: only the initial ``Song.from_url`` +
      ``search_yt_candidates`` touch the network. Every keystroke rescores
      with ``order_results`` only (no ``get_best_result``/``get_views`` —
      views lookups would need network, so the TUI shows cached views and
      notes the live ``get_best_result`` +0..+15 views weighting separately).

Safety: compare/tune only — never downloads, never writes sidecars/files.
Only ``Song.from_url`` (Spotify metadata) and ``search_yt_candidates``
(YouTube search) run; ``TrackDownloader``/``search_and_download`` are never
imported or called.
"""

from __future__ import annotations

import argparse
import copy
import os
import sys
from dataclasses import replace as _dc_replace
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

TOOLS_DIR = Path(__file__).resolve().parent
APP_PYTHON_DIR = TOOLS_DIR.parent / "app" / "app" / "src" / "main" / "python"
VENDORED_DIRS = (
    TOOLS_DIR.parent / "app" / "app" / "python-spotdl",
    TOOLS_DIR.parent / "app" / "app" / "python-spotapi",
    TOOLS_DIR.parent / "app" / "app" / "python-spotipyfree",
    TOOLS_DIR.parent / "app" / "app" / "python-rapidfuzz",
)

for _directory in (APP_PYTHON_DIR, *VENDORED_DIRS):
    _path = str(_directory)
    if _directory.is_dir() and _path not in sys.path:
        sys.path.insert(0, _path)

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_DEPENDENCY = 3
EXIT_METADATA = 4
EXIT_PROVIDER = 5
EXIT_NO_CANDIDATES = 6

DEFAULT_LIMIT = 100
DEFAULT_TIMEOUT = 8.0

VERBOSE_RULE = "-" * 72
VERBOSE_SEARCH_HEADING = "Verbose - YouTube search (real queries + requests)"
REDACTED = "<redacted>"

SENSITIVE_KEY_FRAGMENTS = (
    "auth",
    "api-key",
    "api_key",
    "apikey",
    "cookie",
    "secret",
    "token",
)

import re as _re

SECRET_VALUE_PATTERNS = (
    _re.compile(r"(?i)\b(bearer\s+)[^\s,;\"'}\]]+"),
    _re.compile(r"\bsk-[A-Za-z0-9._-]{6,}"),
    _re.compile(r"\bor-v1-[A-Za-z0-9._-]{6,}"),
)


def is_sensitive_key(name: Any) -> bool:
    """True when a param/header *name* is one whose value must never be shown."""
    lowered = str(name).lower()
    return any(fragment in lowered for fragment in SENSITIVE_KEY_FRAGMENTS)


def redact_secrets(text: Any) -> str:
    """*text* with every credential-shaped value replaced by ``REDACTED``.

    Same style as ``tools/jev_selection_demo.py``: ``Bearer <token>`` keeps
    the scheme and loses the token, bare ``sk-``/``or-v1-`` keys lose
    everything. Only key-shaped runs are touched, so queries, urls and
    video ids survive verbatim.
    """

    def _swap(match: "_re.Match") -> str:
        prefix = match.group(1) if match.lastindex else ""
        return prefix + REDACTED

    scrubbed = "" if text is None else str(text)
    for pattern in SECRET_VALUE_PATTERNS:
        scrubbed = pattern.sub(_swap, scrubbed)
    return scrubbed


def safe_param_value(name: Any, value: Any) -> str:
    """*value* for display, redacted when *name* looks sensitive."""
    text = "" if value is None else str(value)
    if is_sensitive_key(name):
        # Keep the shape (e.g. a cookie path exists) but hide the secret.
        return REDACTED
    return redact_secrets(text)


def preview_edited_query(song_edits: Dict[str, str]) -> str:
    """Preview pooled query for the *edited* song fields (display only).

    Uses the live ``create_song_title`` the fetch used, so the preview can
    never drift from the real query builder; falls back to ``"title artists"``
    when the live helper is unavailable. Never touches the network.
    """
    name = str((song_edits or {}).get("name", "") or "").strip()
    artists = parse_artists_csv((song_edits or {}).get("artists", ""))
    try:
        from spotdl.utils.formatter import create_song_title
    except Exception:
        tail = " ".join(artists)
        return ("%s %s" % (name, tail)).strip() or "-"
    try:
        return str(create_song_title(name, artists, for_lyrics=False) or "-")
    except Exception:
        tail = " ".join(artists)
        return ("%s %s" % (name, tail)).strip() or "-"


def format_verbose_search(
    query: Optional[str],
    limit: int,
    timeout: float,
    provider: Optional[str],
    calls: List[Dict[str, Any]],
    provider_counts: Dict[str, Any],
    total: int,
    edited_preview: Optional[str] = None,
) -> str:
    """Render the ``-v`` block: real queries, params, counts, requests.

    *query* is the pooled ``create_song_title`` string the fetch really sent
    (captured from the wrapped ``get_results`` args, not recomputed).
    *calls* are the underlying ``YTMusic.search`` / ``YoutubeDL.extract_info``
    / ``get_results`` invocations in provider order, display only.
    """
    lines: List[str] = [VERBOSE_SEARCH_HEADING, VERBOSE_RULE]
    lines.append("  query         : %s" % redact_secrets(query or "-"))
    if edited_preview and edited_preview != (query or ""):
        lines.append("  edited preview: %s" % redact_secrets(edited_preview))
    elif edited_preview:
        lines.append("  edited preview: (same as query)")
    lines.append("  limit         : %s" % limit)
    try:
        lines.append("  timeout       : %.1fs" % float(timeout))
    except Exception:
        lines.append("  timeout       : %s" % timeout)
    lines.append("  provider order: youtube-music:songs -> youtube-music:videos -> youtube")
    lines.append("  provider      : %s" % (provider or "-"))
    if provider_counts:
        parts = []
        for key in ("youtube-music:songs", "youtube-music:videos", "youtube"):
            if key in provider_counts:
                parts.append("%s=%s" % (key, provider_counts[key]))
        for key, val in provider_counts.items():
            if key not in ("youtube-music:songs", "youtube-music:videos", "youtube"):
                parts.append("%s=%s" % (key, val))
        lines.append("  per-provider  : %s" % (", ".join(parts) or "-"))
    else:
        lines.append("  per-provider  : - (no calls captured)")
    lines.append("  pooled total  : %s (deduped by url)" % total)
    lines.append("  requests      :")
    if not calls:
        lines.append("    - (no underlying calls captured; stub or fetch failed before search)")
    else:
        for entry in calls:
            method = str(entry.get("method") or "-")
            endpoint = str(entry.get("endpoint") or "-")
            params = entry.get("params") or {}
            count = entry.get("count")
            if isinstance(params, dict):
                param_s = ", ".join(
                    "%s=%s" % (k, safe_param_value(k, v)) for k, v in params.items()
                )
            else:
                param_s = redact_secrets(params)
            if count is None:
                lines.append("    %s %s [%s]" % (method, redact_secrets(endpoint), param_s))
            else:
                lines.append(
                    "    %s %s [%s] -> %s results" % (method, redact_secrets(endpoint), param_s, count)
                )
            note = entry.get("note")
            if note:
                lines.append("      note: %s" % redact_secrets(note))
    lines.append("  notes         : wrapper only displays args; never mutates them;")
    lines.append("                  no per-keystroke network (initial fetch only);")
    lines.append("                  get_views is never called by the tuner (cached views).")
    lines.append(VERBOSE_RULE)
    lines.append("")
    return redact_secrets("\n".join(lines))


class VerboseSearch:
    """The ``-v`` sink: captures real pooled queries + requests for display.

    Wraps the very ``get_results`` / ``YTMusic.search`` /
    ``YoutubeDL.extract_info`` / ``get_views`` callables the live
    ``ghostify_dl.search_yt_candidates`` uses, logs their args + result
    counts, then delegates unchanged. Display-only: never mutates args,
    never reorders providers, never retries. A logging failure is swallowed
    so ``-v`` cannot change candidates or exit codes. Same redaction style
    as ``tools/jev_selection_demo.py`` (``VerboseJev``).
    """

    def __init__(self, limit: int = DEFAULT_LIMIT, timeout: float = DEFAULT_TIMEOUT) -> None:
        self.limit = int(limit)
        self.timeout = float(timeout)
        self.query: Optional[str] = None
        self.calls: List[Dict[str, Any]] = []
        self.provider_counts: Dict[str, Any] = {}
        self._ghostify: Any = None
        self._orig_get_yt: Any = None
        self._orig_get_fallback: Any = None
        self._orig_extract_info: Any = None
        self._orig_get_views: Any = None

    # -- install / uninstall -------------------------------------------
    def install(self, ghostify_dl: Any) -> None:
        """Patch provider factories + transports to log args (display only)."""
        self._ghostify = ghostify_dl
        try:
            self._orig_get_yt = getattr(ghostify_dl, "_get_yt_provider", None)
            self._orig_get_fallback = getattr(ghostify_dl, "_get_youtube_fallback_provider", None)
        except Exception:
            pass
        trace = self

        def wrapped_get_yt() -> Any:
            assert trace._orig_get_yt is not None
            prov = trace._orig_get_yt()
            return trace._wrap_ytm_provider(prov)

        def wrapped_get_fallback() -> Any:
            assert trace._orig_get_fallback is not None
            prov = trace._orig_get_fallback()
            return trace._wrap_yt_provider(prov)

        try:
            if callable(self._orig_get_yt):
                ghostify_dl._get_yt_provider = wrapped_get_yt  # type: ignore[attr-defined]
            if callable(self._orig_get_fallback):
                ghostify_dl._get_youtube_fallback_provider = wrapped_get_fallback  # type: ignore[attr-defined]
        except Exception:
            pass
        # Underlying YT transport: ytsearch10:... via YoutubeDL.extract_info.
        try:
            from yt_dlp import YoutubeDL as _YDL

            if getattr(_YDL.extract_info, "_verbose_wrapped", False) is not True:
                trace._orig_extract_info = _YDL.extract_info

                def logged_extract_info(self_ydl: Any, url: Any, *a: Any, **k: Any) -> Any:
                    surl = "" if url is None else str(url)
                    if surl.startswith("ytsearch"):
                        trace.calls.append(
                            {
                                "method": "YoutubeDL.extract_info",
                                "endpoint": surl.split(":", 1)[0] + ":<query>",
                                "params": {
                                    "query": surl.split(":", 1)[1] if ":" in surl else surl,
                                    "download": k.get("download", False) if k else False,
                                    "extract_flat": "in_playlist",
                                    "skip_download": True,
                                },
                                "count": None,
                                "note": "plain-YouTube fallback (yt-dlp ytsearch)",
                            }
                        )
                    assert trace._orig_extract_info is not None
                    return trace._orig_extract_info(self_ydl, url, *a, **k)

                logged_extract_info._verbose_wrapped = True  # type: ignore[attr-defined]
                _YDL.extract_info = logged_extract_info  # type: ignore[method-assign]
        except Exception:
            pass
        # get_views: tuner never calls it; log if anything ever does.
        try:
            from spotdl.providers.audio.base import AudioProvider as _Base

            if getattr(_Base.get_views, "_verbose_wrapped", False) is not True:
                trace._orig_get_views = _Base.get_views

                def logged_get_views(self_prov: Any, url: Any) -> Any:
                    trace.calls.append(
                        {
                            "method": "AudioProvider.get_views",
                            "endpoint": str(url or "-"),
                            "params": {},
                            "count": None,
                            "note": "views lookup (tuner itself never calls this)",
                        }
                    )
                    assert trace._orig_get_views is not None
                    return trace._orig_get_views(self_prov, url)

                logged_get_views._verbose_wrapped = True  # type: ignore[attr-defined]
                _Base.get_views = logged_get_views  # type: ignore[method-assign]
        except Exception:
            pass

    def uninstall(self) -> None:
        """Restore patched factories (best effort; transports stay wrapped)."""
        try:
            if self._ghostify is not None:
                if callable(self._orig_get_yt):
                    self._ghostify._get_yt_provider = self._orig_get_yt  # type: ignore[attr-defined]
                if callable(self._orig_get_fallback):
                    self._ghostify._get_youtube_fallback_provider = self._orig_get_fallback  # type: ignore[attr-defined]
        except Exception:
            pass

    # -- provider wrappers ---------------------------------------------
    def _ensure_client_wrapped(self, prov: Any) -> None:
        """Wrap ``prov.client.search`` once to capture YTM args for display."""
        try:
            client = getattr(prov, "client", None)
            search = getattr(client, "search", None)
            if not callable(search):
                return
            if getattr(search, "_verbose_wrapped", False) is True:
                return
            trace = self

            def logged_search(query: Any, *a: Any, **k: Any) -> Any:
                params: Dict[str, Any] = {}
                # Positional filter/scope support: YTMusic.search(query, filter, scope, limit, ignore_spelling)
                try:
                    if a and len(a) >= 1 and "filter" not in k:
                        params["filter"] = a[0]
                    if a and len(a) >= 2 and "scope" not in k:
                        params["scope"] = a[1]
                    if a and len(a) >= 3 and "limit" not in k:
                        params["limit"] = a[2]
                    if a and len(a) >= 4 and "ignore_spelling" not in k:
                        params["ignore_spelling"] = a[3]
                except Exception:
                    pass
                params.update({"query": query})
                for key in ("filter", "scope", "limit", "ignore_spelling"):
                    if key in k:
                        params[key] = k[key]
                trace.calls.append(
                    {
                        "method": "YTMusic.search",
                        "endpoint": "youtube-music-api",
                        "params": params,
                        "count": None,
                    }
                )
                return search(query, *a, **k)

            logged_search._verbose_wrapped = True  # type: ignore[attr-defined]
            client.search = logged_search  # type: ignore[attr-defined]
        except Exception:
            pass

    def _wrap_ytm_provider(self, prov: Any) -> Any:
        """Wrap a YTM provider's ``get_results`` to log query + counts."""
        try:
            if getattr(getattr(prov, "get_results", None), "_verbose_wrapped", False) is True:
                self._ensure_client_wrapped(prov)
                return prov
            orig = prov.get_results
            trace = self
            trace._ensure_client_wrapped(prov)

            def logged_get_results(search_term: str, *a: Any, **k: Any) -> Any:
                if trace.query is None and search_term:
                    trace.query = str(search_term)
                filt = k.get("filter", "?")
                try:
                    results = orig(search_term, *a, **k)
                except Exception:
                    trace.calls.append(
                        {
                            "method": "YouTubeMusic.get_results",
                            "endpoint": "filter=%s" % filt,
                            "params": {"query": search_term, **k},
                            "count": "error",
                        }
                    )
                    raise
                label = "youtube-music:%s" % filt if filt != "?" else "youtube-music"
                try:
                    trace.provider_counts[label] = len(results or [])
                except Exception:
                    trace.provider_counts[label] = "?"
                trace.calls.append(
                    {
                        "method": "YouTubeMusic.get_results",
                        "endpoint": "filter=%s" % filt,
                        "params": {"query": search_term, **k},
                        "count": len(results or []),
                    }
                )
                return results

            logged_get_results._verbose_wrapped = True  # type: ignore[attr-defined]
            prov.get_results = logged_get_results  # type: ignore[attr-defined]
            return prov
        except Exception:
            return prov

    def _wrap_yt_provider(self, prov: Any) -> Any:
        """Wrap plain-YouTube ``get_results`` to log ytsearch query + count."""
        try:
            if getattr(getattr(prov, "get_results", None), "_verbose_wrapped", False) is True:
                return prov
            orig = prov.get_results
            trace = self

            def logged_get_results(search_term: str, *a: Any, **k: Any) -> Any:
                if trace.query is None and search_term:
                    trace.query = str(search_term)
                try:
                    results = orig(search_term, *a, **k)
                except Exception:
                    trace.calls.append(
                        {
                            "method": "YouTube.get_results",
                            "endpoint": "ytsearch10:<query>",
                            "params": {"query": search_term, "limit": trace.limit},
                            "count": "error",
                        }
                    )
                    raise
                try:
                    trace.provider_counts["youtube"] = len(results or [])
                except Exception:
                    trace.provider_counts["youtube"] = "?"
                trace.calls.append(
                    {
                        "method": "YouTube.get_results",
                        "endpoint": "ytsearch10:<query>",
                        "params": {"query": search_term, "limit": trace.limit},
                        "count": len(results or []),
                        "note": "yt-dlp ytsearch10 (limit param ignored upstream; always 10)",
                    }
                )
                return results

            logged_get_results._verbose_wrapped = True  # type: ignore[attr-defined]
            prov.get_results = logged_get_results  # type: ignore[attr-defined]
            return prov
        except Exception:
            return prov

    def format_block(
        self,
        provider: Optional[str],
        total: int,
        edited_preview: Optional[str] = None,
    ) -> str:
        """Render this trace as a ``-v`` block (redacted, display only)."""
        return format_verbose_search(
            self.query or "-",
            self.limit,
            self.timeout,
            provider,
            list(self.calls),
            dict(self.provider_counts),
            int(total),
            edited_preview=edited_preview,
        )


class TunerError(Exception):
    """A tuner failure with an exit code."""

    def __init__(self, message: str, exit_code: int = EXIT_USAGE) -> None:
        super().__init__(message)
        self.exit_code = exit_code


# ---------------------------------------------------------------------------
# Lazy live imports (keeps --help importable without deps).
# ---------------------------------------------------------------------------

def import_ghostify_dl() -> Any:
    """Import the live bridge module or raise TunerError."""
    try:
        import ghostify_dl  # noqa: WPS433 (deliberate lazy import)
    except ImportError as exc:
        raise TunerError(
            "ghostify_dl is not importable (%s). Run from a checkout of the "
            "app, or set PYTHONPATH to %s." % (type(exc).__name__, APP_PYTHON_DIR),
            EXIT_DEPENDENCY,
        ) from exc
    return ghostify_dl


def import_song_result() -> Tuple[Any, Any]:
    """Import live Song/Result types or raise TunerError."""
    try:
        from spotdl.types.result import Result
        from spotdl.types.song import Song
    except ImportError as exc:
        raise TunerError(
            "spotdl is not importable (%s); expected vendored package at %s."
            % (type(exc).__name__, VENDORED_DIRS[0]),
            EXIT_DEPENDENCY,
        ) from exc
    return Song, Result


def import_matching() -> Any:
    """Import live spotdl.utils.matching or raise TunerError."""
    try:
        import spotdl.utils.matching as matching
    except ImportError as exc:
        raise TunerError(
            "spotdl.utils.matching is not importable (%s)." % type(exc).__name__,
            EXIT_DEPENDENCY,
        ) from exc
    return matching


def import_slugify() -> Any:
    """Import live slugify from formatter (channel-bonus display)."""
    try:
        from spotdl.utils.formatter import slugify
    except ImportError as exc:
        raise TunerError(
            "spotdl.utils.formatter is not importable (%s)." % type(exc).__name__,
            EXIT_DEPENDENCY,
        ) from exc
    return slugify


# ---------------------------------------------------------------------------
# Field descriptors.
# ---------------------------------------------------------------------------

# (key, label, hint)
SONG_FIELDS: List[Tuple[str, str, str]] = [
    ("name", "title", "Spotify track title"),
    ("artists", "artists", "comma-separated"),
    ("album_name", "album", "Spotify album"),
    ("duration", "duration s", "seconds, int"),
    ("isrc", "isrc", "e.g. USUG11904206"),
    ("explicit", "explicit", "true/false"),
]

# (key, label, hint)
CAND_FIELDS: List[Tuple[str, str, str]] = [
    ("name", "title", "result title"),
    ("artists", "artists", "comma-separated; empty=use channel"),
    ("author", "channel", "uploader/author"),
    ("album", "album", "result album or empty"),
    ("duration", "duration s", "seconds, float ok"),
    ("views", "views", "int or empty"),
    ("url", "url", "watch/music url"),
    ("verified", "verified", "true/false"),
    ("source", "source", "YouTubeMusic/YouTube"),
]


# ---------------------------------------------------------------------------
# Parse/format helpers (stdlib only; scoring itself stays in live code).
# ---------------------------------------------------------------------------

def parse_artists_csv(text: Any) -> List[str]:
    """Split comma-separated artists, dropping empties."""
    if text is None:
        return []
    return [p.strip() for p in str(text).split(",") if p.strip()]


def parse_bool(text: Any, default: bool = False) -> bool:
    """Parse true/false/1/0/yes/no (case-insensitive); fallback *default*."""
    if isinstance(text, bool):
        return text
    s = str(text or "").strip().lower()
    if s in ("1", "true", "t", "yes", "y", "on"):
        return True
    if s in ("0", "false", "f", "no", "n", "off", ""):
        return False if s != "" else default
    return default


def parse_float(text: Any, default: float = 0.0) -> float:
    try:
        return float(str(text).strip())
    except (TypeError, ValueError, AttributeError):
        return default


def parse_int(text: Any, default: int = 0) -> int:
    try:
        return int(round(float(str(text).strip())))
    except (TypeError, ValueError, AttributeError):
        return default


def parse_views(text: Any) -> Optional[int]:
    s = str(text or "").strip().replace(",", "").replace("_", "")
    if not s:
        return None
    try:
        v = int(float(s))
    except (TypeError, ValueError):
        return None
    return max(0, v)


def fmt_views(value: Any) -> str:
    if value is None:
        return ""
    try:
        return str(int(value))
    except (TypeError, ValueError):
        return ""


def fmt_duration(value: Any) -> str:
    if value is None or value == "":
        return ""
    try:
        f = float(value)
    except (TypeError, ValueError):
        return str(value)
    if f == int(f):
        return str(int(f))
    return str(f)


# ---------------------------------------------------------------------------
# Stub data (offline smoke test; still uses live Song/Result types).
# ---------------------------------------------------------------------------

def build_stub_song() -> Any:
    """Build a stub Spotify Song with live types (no network)."""
    Song, _Result = import_song_result()
    return Song.from_missing_data(
        name="Blinding Lights",
        artist="The Weeknd",
        artists=["The Weeknd"],
        song_id="0VjIjW4GlUZAMYd2vXMi3b",
        duration=202,
        url="https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b",
        genres=[],
        disc_number=1,
        disc_count=1,
        album_name="After Hours",
        album_artist="The Weeknd",
        year=2020,
        date="2020-03-20",
        track_number=1,
        tracks_count=14,
        explicit=False,
        publisher="XO",
        isrc="USUG11904206",
        cover_url=None,
        copyright_text=None,
        album_id="",
    )


def build_stub_results() -> List[Any]:
    """Build 3 stub candidates with live Result types (no network)."""
    _Song, Result = import_song_result()
    return [
        Result(
            source="YouTubeMusic",
            url="https://music.youtube.com/watch?v=a0000000001",
            verified=True,
            name="Blinding Lights",
            duration=202.0,
            author="The Weeknd",
            result_id="a0000000001",
            artists=("The Weeknd",),
            views=500000000,
            explicit=False,
            album="After Hours",
        ),
        Result(
            source="YouTube",
            url="https://www.youtube.com/watch?v=b0000000002",
            verified=False,
            name="Blinding Lights (slowed + reverb)",
            duration=210.0,
            author="random uploads",
            result_id="b0000000002",
            artists=None,
            views=1234,
            explicit=False,
            album=None,
        ),
        Result(
            source="YouTube",
            url="https://www.youtube.com/watch?v=c0000000003",
            verified=False,
            name="Cooking Tutorial - Pasta Night",
            duration=600.0,
            author="chef channel",
            result_id="c0000000003",
            artists=None,
            views=999,
            explicit=False,
            album=None,
        ),
    ]


# ---------------------------------------------------------------------------
# Edit-dict bridge: live objects <-> editable strings.
# ---------------------------------------------------------------------------

def song_to_edits(song: Any) -> Dict[str, str]:
    """Live Song -> editable string dict."""
    artists = getattr(song, "artists", None) or []
    return {
        "name": str(getattr(song, "name", None) or ""),
        "artists": ", ".join(str(a) for a in artists),
        "album_name": str(getattr(song, "album_name", None) or ""),
        "duration": fmt_duration(getattr(song, "duration", None) or 0),
        "isrc": str(getattr(song, "isrc", None) or ""),
        "explicit": "true" if bool(getattr(song, "explicit", False)) else "false",
    }


def result_to_edits(result: Any) -> Dict[str, str]:
    """Live Result -> editable string dict."""
    artists = getattr(result, "artists", None)
    if artists:
        artists_s = ", ".join(str(a) for a in list(artists))
    else:
        artists_s = ""
    return {
        "name": str(getattr(result, "name", None) or ""),
        "artists": artists_s,
        "author": str(getattr(result, "author", None) or ""),
        "album": str(getattr(result, "album", None) or ""),
        "duration": fmt_duration(getattr(result, "duration", None) or 0),
        "views": fmt_views(getattr(result, "views", None)),
        "url": str(getattr(result, "url", None) or ""),
        "verified": "true" if bool(getattr(result, "verified", False)) else "false",
        "source": str(getattr(result, "source", None) or ""),
    }


def edits_to_song(base: Any, edits: Dict[str, str]) -> Any:
    """Rebuild a live Song copy from edited strings (mutated copy)."""
    artists = parse_artists_csv(edits.get("artists", ""))
    if not artists:
        artists = list(getattr(base, "artists", None) or [])
    duration = parse_int(edits.get("duration", ""), default=int(getattr(base, "duration", 0) or 0))
    isrc_raw = str(edits.get("isrc", "") or "").strip()
    try:
        return _dc_replace(
            base,
            name=str(edits.get("name", "") or ""),
            artists=artists,
            artist=artists[0] if artists else getattr(base, "artist", ""),
            album_name=str(edits.get("album_name", "") or ""),
            duration=duration,
            isrc=isrc_raw or None,
            explicit=parse_bool(edits.get("explicit", ""), default=bool(getattr(base, "explicit", False))),
        )
    except Exception:
        # Fallback: shallow copy + attribute set (never touches scoring).
        dup = copy.copy(base)
        dup.name = str(edits.get("name", "") or "")
        dup.artists = artists
        dup.artist = artists[0] if artists else getattr(base, "artist", "")
        dup.album_name = str(edits.get("album_name", "") or "")
        dup.duration = duration
        dup.isrc = isrc_raw or None
        dup.explicit = parse_bool(edits.get("explicit", ""), default=False)
        return dup


def edits_to_result(base: Any, edits: Dict[str, str]) -> Any:
    """Rebuild a live Result copy from edited strings (mutated copy)."""
    artists_csv = parse_artists_csv(edits.get("artists", ""))
    artists: Optional[Tuple[str, ...]] = tuple(artists_csv) if artists_csv else None
    album_raw = str(edits.get("album", "") or "").strip()
    url = str(edits.get("url", "") or "").strip() or getattr(base, "url", "")
    try:
        return _dc_replace(
            base,
            name=str(edits.get("name", "") or ""),
            artists=artists,
            author=str(edits.get("author", "") or ""),
            album=album_raw or None,
            duration=parse_float(edits.get("duration", ""), default=float(getattr(base, "duration", 0) or 0)),
            views=parse_views(edits.get("views", "")),
            url=url,
            verified=parse_bool(edits.get("verified", ""), default=bool(getattr(base, "verified", False))),
            source=str(edits.get("source", "") or "").strip() or getattr(base, "source", ""),
        )
    except Exception:
        # Frozen dataclass replace should succeed; copy fallback for safety.
        dup = copy.copy(base)
        for key, val in {
            "name": str(edits.get("name", "") or ""),
            "artists": artists,
            "author": str(edits.get("author", "") or ""),
            "album": album_raw or None,
            "duration": parse_float(edits.get("duration", ""), default=0.0),
            "views": parse_views(edits.get("views", "")),
            "url": url,
            "verified": parse_bool(edits.get("verified", ""), default=False),
            "source": str(edits.get("source", "") or ""),
        }.items():
            try:
                object.__setattr__(dup, key, val)
            except Exception:
                pass
        return dup


def blank_result_edits() -> Dict[str, str]:
    """Editable dict for a blank custom candidate."""
    return {
        "name": "",
        "artists": "",
        "author": "",
        "album": "",
        "duration": "0",
        "views": "",
        "url": "https://www.youtube.com/watch?v=custom000001",
        "verified": "false",
        "source": "YouTube",
    }


def blank_result_base() -> Any:
    """Live blank Result backing a custom candidate (live type, no network)."""
    _Song, Result = import_song_result()
    return Result(
        source="YouTube",
        url="https://www.youtube.com/watch?v=custom000001",
        verified=False,
        name="",
        duration=0.0,
        author="",
        result_id="custom000001",
        artists=None,
        views=None,
        explicit=None,
        album=None,
    )


# ---------------------------------------------------------------------------
# Live scoring: final via order_results, breakdown via live helpers.
# ---------------------------------------------------------------------------

def score_pool_live(song_live: Any, results_live: List[Any]) -> Dict[Any, float]:
    """Score pooled candidates with live order_results (pure CPU, no network)."""
    matching = import_matching()
    try:
        scored = matching.order_results(list(results_live or []), song_live, None)
    except Exception:
        return {}
    return dict(scored or {})


def breakdown_live(song_live: Any, result_live: Any) -> Dict[str, Any]:
    """Per-candidate breakdown using live helpers only (display, not final).

    Final remains ``score_pool_live`` (live ``order_results``). Each row here
    calls the same live function/constant the scorer uses, so the display can
    never drift from the code that decides.
    """
    matching = import_matching()
    slugify = import_slugify()
    out: Dict[str, Any] = {}
    try:
        out["set_score"] = float(matching.calc_set_match(song_live, result_live))
    except Exception:
        out["set_score"] = 0.0
    try:
        out["album_score"] = float(matching.calc_album_set_match(song_live, result_live))
    except Exception:
        out["album_score"] = 0.0
    try:
        out["time_score"] = float(matching.calc_time_match(song_live, result_live))
    except Exception:
        out["time_score"] = 0.0
    try:
        has_fw, words = matching.check_forbidden_words(song_live, result_live)
        out["forbidden"] = list(words or [])
        out["forbidden_hit"] = bool(has_fw)
    except Exception:
        out["forbidden"] = []
        out["forbidden_hit"] = False
    try:
        penalty = int(getattr(matching, "FORBIDDEN_WORD_PENALTY", 5))
    except Exception:
        penalty = 5
    out["forbidden_penalty"] = -penalty * len(out["forbidden"]) if out["forbidden_hit"] else 0
    try:
        out["common_word"] = bool(matching.check_common_word(song_live, result_live))
    except Exception:
        out["common_word"] = True
    # Channel +10/-15: same condition as live order_results (display only).
    try:
        author = getattr(result_live, "author", None)
        artists = getattr(song_live, "artists", None) or []
        if author and artists:
            slug_author = slugify(author).replace("-", "")
            bonus = 0
            detail = "no artist in channel (-15)"
            for artist in artists:
                slug_artist = slugify(artist).replace("-", "")
                if slug_artist and (slug_artist in slug_author or slug_author in slug_artist):
                    bonus = 10
                    detail = "channel ~ artist (+10)"
                    break
            else:
                bonus = -15
            out["channel_bonus"] = bonus
            out["channel_detail"] = detail
        else:
            out["channel_bonus"] = 0
            out["channel_detail"] = "no channel/artists (0)"
    except Exception:
        out["channel_bonus"] = 0
        out["channel_detail"] = "n/a"
    # YTM +5: live detector + live constant.
    try:
        detector = getattr(matching, "_is_youtube_music_source", None)
        if callable(detector):
            is_ytm = bool(detector(result_live))
        else:
            is_ytm = getattr(result_live, "source", None) == "YouTubeMusic" or (
                "music.youtube.com" in str(getattr(result_live, "url", "") or "")
            )
        out["is_ytm"] = is_ytm
        out["ytm_bonus"] = int(getattr(matching, "YTM_SOURCE_BONUS", 5)) if is_ytm else 0
    except Exception:
        out["is_ytm"] = False
        out["ytm_bonus"] = 0
    try:
        out["unrelated_enabled"] = bool(getattr(matching, "ENABLE_UNRELATED_WORDS_PENALTY", False))
    except Exception:
        out["unrelated_enabled"] = False
    out["unrelated_penalty"] = 0  # step 4 is off in live code; shown, never applied
    out["views"] = getattr(result_live, "views", None)
    return out


def views_contenders(scored: Dict[Any, float]) -> List[Any]:
    """Top-8 views contenders via live get_best_matches (no network)."""
    if not scored:
        return []
    try:
        matching = import_matching()
        best = matching.get_best_matches(dict(scored), 8)
        return [r for r, _s in best]
    except Exception:
        return []


# ---------------------------------------------------------------------------
# Plain (--no-tui) rendering.
# ---------------------------------------------------------------------------

def _truncate(text: Any, width: int) -> str:
    s = "-" if text is None or text == "" else " ".join(str(text).split())
    if width > 0 and len(s) > width:
        s = s[: width - 1] + "…"
    return s


def format_no_tui(
    song_base: Any,
    song_edits: Dict[str, str],
    cand_bases: List[Any],
    cand_edits: List[Dict[str, str]],
    provider: Optional[str] = None,
    query: Optional[str] = None,
) -> str:
    """Render song + candidates + live scores as a plain ASCII table."""
    song_live = edits_to_song(song_base, song_edits)
    results_live = [edits_to_result(b, e) for b, e in zip(cand_bases, cand_edits)]
    scored = score_pool_live(song_live, results_live)
    # Map live result object -> score (order_results keys are the same objects).
    totals: List[Optional[float]] = []
    for res in results_live:
        val: Optional[float] = None
        for key, score in scored.items():
            if key is res or key == res:
                val = float(score)
                break
        totals.append(val)
    breakdowns = [breakdown_live(song_live, res) for res in results_live]
    winner = -1
    best = -1.0
    for i, val in enumerate(totals):
        if val is not None and val > best:
            best = val
            winner = i
    orig_total = totals[0] if totals else None

    lines: List[str] = []
    lines.append("Ghostify score tuner (--no-tui plain mode)")
    lines.append("=" * 72)
    lines.append("")
    lines.append("Song (panel 1: Spotify original, editable in TUI)")
    lines.append("  title     : %s" % _truncate(song_edits.get("name", ""), 60))
    lines.append("  artists   : %s" % _truncate(song_edits.get("artists", ""), 60))
    lines.append("  album     : %s" % _truncate(song_edits.get("album_name", ""), 60))
    lines.append("  duration s: %s" % _truncate(song_edits.get("duration", ""), 60))
    lines.append("  isrc      : %s" % _truncate(song_edits.get("isrc", ""), 60))
    lines.append("  explicit  : %s" % _truncate(song_edits.get("explicit", ""), 60))
    lines.append("  provider  : %s" % (provider or "-"))
    lines.append("  query     : %s" % _truncate(query or "-", 60))
    lines.append("")
    lines.append("Candidates (panels 2..N: YT/YT-Music, editable in TUI)")
    header = "  | %-3s | %-5s | %-6s | %-5s | %-5s | %-5s | %-4s | %-4s | %-3s | %-5s | %s" % (
        "idx", "total", "set", "album", "time", "forb", "chan", "ytm", "ytm?",
        "views", "title [winner *, filtered F]",
    )
    lines.append(header)
    lines.append("  +" + "-" * (len(header) - 3) + "+")
    for i, (edits, bd, total) in enumerate(zip(cand_edits, breakdowns, totals)):
        mark = "*" if i == winner else " "
        filt = "" if total is not None else "F"
        total_s = ("%.1f" % total) if total is not None else "filt"
        views_s = str(edits.get("views", "") or "-")
        title = _truncate(edits.get("name", ""), 34)
        lines.append(
            "  | %-3s | %-5s | %-6.1f | %-5.1f | %-5.1f | %-5d | %-4d | %-4d | %-3s | %-5s | %s%s%s"
            % (
                i, total_s, float(bd.get("set_score", 0.0)),
                float(bd.get("album_score", 0.0)), float(bd.get("time_score", 0.0)),
                int(bd.get("forbidden_penalty", 0)), int(bd.get("channel_bonus", 0)),
                int(bd.get("ytm_bonus", 0)),
                "Y" if bd.get("is_ytm") else "n",
                _truncate(views_s, 5), mark, filt, (" " + title) if title else "",
            )
        )
    lines.append("")
    if winner >= 0 and totals[winner] is not None:
        lines.append("  winner: [%d] %s (%.1f)" % (
            winner, _truncate(cand_edits[winner].get("name", ""), 50), float(totals[winner] or 0.0)))
        if orig_total is not None and winner != 0:
            lines.append("  delta winner vs [0] original: %+.1f" % (float(totals[winner] or 0.0) - float(orig_total)))
        elif orig_total is not None:
            lines.append("  delta winner vs [0] original: +0.0 (winner is original)")
        for i, total in enumerate(totals):
            if total is None or i == winner:
                continue
            lines.append("  delta [%d] vs winner: %+.1f" % (i, float(total) - float(totals[winner] or 0.0)))
    else:
        lines.append("  winner: none (live order_results filtered every candidate out)")
    lines.append("")
    lines.append("Breakdown per candidate (live helpers; final = live order_results)")
    for i, (edits, bd, total) in enumerate(zip(cand_edits, breakdowns, totals)):
        fw = ",".join(bd.get("forbidden", []) or []) or "-"
        lines.append(
            "  [%d] set=%.1f album=%.1f time=%.1f forb=%s(%d) chan=%+d ytm=%+d "
            "unrelated=off views=%s common=%s total=%s src=%s verified=%s" % (
                i, float(bd.get("set_score", 0.0)), float(bd.get("album_score", 0.0)),
                float(bd.get("time_score", 0.0)), fw, int(bd.get("forbidden_penalty", 0)),
                int(bd.get("channel_bonus", 0)), int(bd.get("ytm_bonus", 0)),
                edits.get("views", "") or "-", "y" if bd.get("common_word") else "n",
                ("%.1f" % total) if total is not None else "filtered",
                edits.get("source", "") or "-", edits.get("verified", "") or "-",
            )
        )
        lines.append("       url: %s" % _truncate(edits.get("url", ""), 64))
    lines.append("")
    lines.append("Notes: forbidden -5/word (live FORBIDDEN_WORD_PENALTY); channel +10/-15;")
    lines.append("YTM +5 (live YTM_SOURCE_BONUS); unrelated penalty off (live flag);")
    lines.append("views bonus +0..+15 lives in get_best_result among top-8 only")
    lines.append("(cached Result.views shown; no per-keystroke network).")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Colors + sort-view helpers (curses only; --no-tui stays plain ASCII).
# ---------------------------------------------------------------------------

def _colors_enabled() -> bool:
    """True unless NO_COLOR is set or TERM=dumb (mono fallback)."""
    if "NO_COLOR" in os.environ:
        return False
    if os.environ.get("TERM", "") == "dumb":
        return False
    return True


def _init_curses_colors(curses_mod: Any) -> Dict[str, int]:
    """Init curses color pairs; return {name: attr} or {} for mono.

    Pairs: header, orig, winner, filtered, focused, help, dpos, dneg.
    Never raises; callers fall back to A_BOLD/A_REVERSE/A_DIM.
    """
    try:
        if not _colors_enabled():
            return {}
        if not curses_mod.has_colors():
            return {}
        curses_mod.start_color()
        try:
            curses_mod.use_default_colors()
        except Exception:
            pass
        # (name, fg, bg)
        defs = [
            ("header", curses_mod.COLOR_WHITE, curses_mod.COLOR_BLUE),
            ("orig", curses_mod.COLOR_CYAN, -1),
            ("winner", curses_mod.COLOR_BLACK, curses_mod.COLOR_YELLOW),
            ("filtered", curses_mod.COLOR_RED, -1),
            ("focused", curses_mod.COLOR_BLACK, curses_mod.COLOR_CYAN),
            ("help", curses_mod.COLOR_BLACK, curses_mod.COLOR_WHITE),
            ("dpos", curses_mod.COLOR_GREEN, -1),
            ("dneg", curses_mod.COLOR_RED, -1),
        ]
        attrs: Dict[str, int] = {}
        for i, (name, fg, bg) in enumerate(defs, start=1):
            try:
                curses_mod.init_pair(i, fg, bg)
            except Exception:
                continue
            try:
                attrs[name] = curses_mod.color_pair(i)
            except Exception:
                continue
        # Emphasis bits (keep distinct even on mono-capable terms).
        try:
            if "header" in attrs:
                attrs["header"] |= curses_mod.A_BOLD
            if "winner" in attrs:
                attrs["winner"] |= curses_mod.A_BOLD
            if "focused" in attrs:
                attrs["focused"] |= curses_mod.A_REVERSE | curses_mod.A_BOLD
            if "help" in attrs:
                attrs["help"] |= curses_mod.A_BOLD
            if "dpos" in attrs:
                attrs["dpos"] |= curses_mod.A_BOLD
            if "dneg" in attrs:
                attrs["dneg"] |= curses_mod.A_BOLD
        except Exception:
            pass
        return attrs
    except Exception:
        return {}


def _ca(attrs: Dict[str, int], name: str, fallback: int) -> int:
    """Color attr lookup with mono fallback."""
    try:
        return int(attrs.get(name, fallback))
    except Exception:
        return fallback


def _sorted_view_indices(totals: List[Optional[float]]) -> List[int]:
    """Underlying indices sorted desc by total (filtered None last, stable)."""
    def key(i: int) -> Tuple[int, float, int]:
        v = totals[i] if i < len(totals) else None
        present = 1 if v is not None else 0
        return (present, float(v) if v is not None else -1e18, -i)

    idx = list(range(len(totals)))
    # Stable descending: sort by key reversed via sorted() reverse.
    idx.sort(key=key, reverse=True)
    return idx


def _view_order(totals: List[Optional[float]], auto_sort: bool) -> List[int]:
    """Display order: score-sorted when auto_sort else underlying order."""
    if auto_sort:
        return _sorted_view_indices(totals)
    return list(range(len(totals)))


def _rank_map(totals: List[Optional[float]]) -> Dict[int, int]:
    """Map underlying idx -> 1-based rank in score-sorted order."""
    order = _sorted_view_indices(totals)
    return {idx: pos + 1 for pos, idx in enumerate(order)}


# ---------------------------------------------------------------------------
# Curses TUI (stdlib only).
# ---------------------------------------------------------------------------

def _tui_state(
    song_base: Any,
    cand_bases: List[Any],
    verbose_trace: Optional["VerboseSearch"] = None,
    verbose_show: bool = False,
) -> Dict[str, Any]:
    fetched = [result_to_edits(r) for r in cand_bases]
    n = len(cand_bases)
    return {
        "song_base": song_base,
        "song_edits": song_to_edits(song_base),
        "song_orig": song_to_edits(song_base),
        "cand_bases": list(cand_bases),
        "cand_edits": [dict(e) for e in fetched],
        "cand_orig": [dict(e) for e in fetched],
        "fetch_bases": list(cand_bases),
        "fetch_edits": [dict(e) for e in fetched],
        "uids": list(range(n)),
        "fetch_uids": list(range(n)),
        "uid_next": n,
        "cand_idx": 0,
        "focus": 0,  # 0..len(SONG_FIELDS)+len(CAND_FIELDS)-1
        "editing": False,
        "cursor": 0,
        "help": False,
        "auto_sort": True,
        "mouse": False,  # disabled by default for safety; 'm' enables wheel-only scroll
        "score_scroll": 0,  # viewport offset into score view; wheel scrolls, never selects
        "msg": "",
        "verbose_trace": verbose_trace,
        "verbose_show": bool(verbose_show),
    }


def _focus_sections(state: Dict[str, Any]) -> Tuple[str, int]:
    n_song = len(SONG_FIELDS)
    f = int(state["focus"])
    if f < n_song:
        return "song", f
    return "cand", f - n_song


def _current_edit_value(state: Dict[str, Any]) -> str:
    section, idx = _focus_sections(state)
    if section == "song":
        key = SONG_FIELDS[idx][0]
        return str(state["song_edits"].get(key, ""))
    key = CAND_FIELDS[idx][0]
    edits = state["cand_edits"][state["cand_idx"]]
    return str(edits.get(key, ""))


def _set_current_edit_value(state: Dict[str, Any], value: str) -> None:
    section, idx = _focus_sections(state)
    if section == "song":
        key = SONG_FIELDS[idx][0]
        state["song_edits"][key] = value
    else:
        key = CAND_FIELDS[idx][0]
        state["cand_edits"][state["cand_idx"]][key] = value


def _rescore(state: Dict[str, Any]) -> Tuple[List[Optional[float]], List[Dict[str, Any]], int]:
    song_live = edits_to_song(state["song_base"], state["song_edits"])
    results_live = [
        edits_to_result(b, e) for b, e in zip(state["cand_bases"], state["cand_edits"])
    ]
    scored = score_pool_live(song_live, results_live)
    totals: List[Optional[float]] = []
    for res in results_live:
        val: Optional[float] = None
        for key, score in scored.items():
            if key is res or key == res:
                val = float(score)
                break
        totals.append(val)
    breakdowns = [breakdown_live(song_live, res) for res in results_live]
    winner = -1
    best = -1.0
    for i, val in enumerate(totals):
        if val is not None and val > best:
            best = val
            winner = i
    return totals, breakdowns, winner


def run_curses_ui(
    stdscr: Any,
    song_base: Any,
    cand_bases: List[Any],
    provider: Optional[str],
    query: Optional[str],
    verbose_trace: Optional["VerboseSearch"] = None,
) -> None:
    """Curses main loop (stdlib only; rescore on every keystroke, no network).

    When *verbose_trace* is given the bottom pane (toggle ``v``) shows the
    real pooled query + per-provider params/counts + underlying
    ``YTMusic.search`` / ``YoutubeDL.extract_info`` calls captured by the
    wrapper, with the edited-song query preview updating live.
    """
    import curses

    state = _tui_state(song_base, cand_bases, verbose_trace, bool(verbose_trace is not None))
    if state["cursor"] == 0:
        state["cursor"] = len(_current_edit_value(state))
    curses.curs_set(0)
    stdscr.nodelay(False)
    stdscr.keypad(True)
    # Colors (mono fallback when NO_COLOR / TERM=dumb / no has_colors).
    _attrs = _init_curses_colors(curses)
    # Mouse: disabled by default for safety (mousemask(0), KEY_MOUSE ignored).
    # 'm' enables wheel-only view scrolling; clicks are never acted on.
    _mouse_supported = False
    _WHEEL_STEP = 3  # score-viewport rows per wheel tick

    def _wheel_mask() -> int:
        try:
            b4 = int(getattr(curses, "BUTTON4_PRESSED", 0) or 0)
            b5 = int(getattr(curses, "BUTTON5_PRESSED", 0) or 0)
            if b4 | b5:
                return int(b4 | b5)
        except Exception:
            pass
        return int(getattr(curses, "ALL_MOUSE_EVENTS", 0x7FFFFFFF))

    def _mouse_apply(enabled: bool) -> None:
        # enabled=True: wheel-only mask; enabled=False: mousemask(0).
        try:
            curses.mousemask(_wheel_mask() if enabled else 0)
        except Exception:
            pass

    try:
        curses.mousemask(0)
        try:
            curses.mouseinterval(0)
        except Exception:
            pass
        _mouse_supported = True
    except Exception:
        _mouse_supported = False
        state["mouse"] = False
    state["mouse"] = False  # default off even when supported; 'm' to enable
    # No clickable layout: click-to-focus / click-to-switch removed.
    # Wheel scrolls the score viewport only (see KEY_MOUSE handler).

    def n_cands() -> int:
        return len(state["cand_edits"])

    def focus_count() -> int:
        return len(SONG_FIELDS) + len(CAND_FIELDS)

    while True:
        totals, breakdowns, winner = _rescore(state)
        # Sorted view: underlying lists stay stable; only display order sorts.
        # Selection (cand_idx) is an underlying index so it survives resort.
        auto_sort = bool(state.get("auto_sort", True))
        view = _view_order(totals, auto_sort)
        rank_of = _rank_map(totals)
        stdscr.erase()
        rows, cols = stdscr.getmaxyx()
        # Header.
        title = "ghostify score tuner — live order_results (q quit, h help, r reset, v verbose, --no-tui for plain)"
        stdscr.addnstr(0, 0, title, cols - 1, _ca(_attrs, "header", curses.A_BOLD))
        sort_s = "on" if auto_sort else "off"
        mouse_s = "on" if state.get("mouse") and _mouse_supported else "off"
        verb_s = "on" if state.get("verbose_show") and state.get("verbose_trace") is not None else "off"
        meta = "candidates %d | showing [%d/%d] | sort:%s mouse:%s verb:%s | provider %s | query %s" % (
            n_cands(), state["cand_idx"] + 1 if n_cands() else 0, n_cands(),
            sort_s, mouse_s, verb_s,
            provider or "-", (query or "-")[:40],
        )
        if rows > 1:
            stdscr.addnstr(1, 0, meta, cols - 1, curses.A_DIM)
        row = 3
        wide = cols >= 120
        # Panels.
        if wide:
            # Side-by-side headings.
            if row < rows - 1:
                stdscr.addnstr(row, 0, "PANEL 1: Spotify original", min(cols - 1, 50), _ca(_attrs, "orig", curses.A_BOLD))
                stdscr.addnstr(row, 60, "PANEL %d: candidate [%d/%d]" % (
                    state["cand_idx"] + 2, state["cand_idx"] + 1, n_cands()), cols - 61, _ca(_attrs, "header", curses.A_BOLD))
            row += 1
            for i, (key, label, _hint) in enumerate(SONG_FIELDS):
                if row + i >= rows - 1:
                    break
                val = str(state["song_edits"].get(key, ""))
                line = "%-10s [%s]" % (label, val)
                is_focus = (state["focus"] == i and not state["help"])
                if is_focus:
                    attr = _ca(_attrs, "focused", curses.A_REVERSE)
                else:
                    attr = _ca(_attrs, "orig", curses.A_NORMAL)
                stdscr.addnstr(row + i, 0, line[:58], 58, attr)
            for j, (key, label, _hint) in enumerate(CAND_FIELDS):
                r = row + j
                if r >= rows - 1:
                    break
                fi = len(SONG_FIELDS) + j
                edits = state["cand_edits"][state["cand_idx"]] if n_cands() else {}
                val = str(edits.get(key, ""))
                line = "%-10s [%s]" % (label, val)
                is_focus = (state["focus"] == fi and not state["help"])
                attr = _ca(_attrs, "focused", curses.A_REVERSE) if is_focus else curses.A_NORMAL
                stdscr.addnstr(r, 60, line[: max(0, cols - 61)], cols - 61, attr)
            row += max(len(SONG_FIELDS), len(CAND_FIELDS)) + 1
        else:
            if row < rows - 1:
                stdscr.addnstr(row, 0, "PANEL 1: Spotify original (song fields)", cols - 1, _ca(_attrs, "orig", curses.A_BOLD))
            row += 1
            for i, (key, label, _hint) in enumerate(SONG_FIELDS):
                if row >= rows - 1:
                    break
                val = str(state["song_edits"].get(key, ""))
                line = "  %-10s [%s]" % (label, val)
                is_focus = (state["focus"] == i and not state["help"])
                attr = _ca(_attrs, "focused", curses.A_REVERSE) if is_focus else _ca(_attrs, "orig", curses.A_NORMAL)
                stdscr.addnstr(row, 0, line[: cols - 1], cols - 1, attr)
                row += 1
            if row < rows - 1:
                stdscr.addnstr(row, 0, "PANEL %d: candidate [%d/%d] (a add, d dup, x del, [ ] switch)" % (
                    state["cand_idx"] + 2, state["cand_idx"] + 1, n_cands()), cols - 1, _ca(_attrs, "header", curses.A_BOLD))
            row += 1
            for j, (key, label, _hint) in enumerate(CAND_FIELDS):
                if row >= rows - 1:
                    break
                fi = len(SONG_FIELDS) + j
                edits = state["cand_edits"][state["cand_idx"]] if n_cands() else {}
                val = str(edits.get(key, ""))
                line = "  %-10s [%s]" % (label, val)
                is_focus = (state["focus"] == fi and not state["help"])
                attr = _ca(_attrs, "focused", curses.A_REVERSE) if is_focus else curses.A_NORMAL
                stdscr.addnstr(row, 0, line[: cols - 1], cols - 1, attr)
                row += 1
        # Scores.
        if row < rows - 1:
            sort_label = "sorted #1.. (s to unsort)" if auto_sort else "unsorted (s to sort)"
            stdscr.addnstr(row, 0, "SCORES %s (live order_results; * winner; F filtered; d winner-vs-orig)" % sort_label, cols - 1, _ca(_attrs, "header", curses.A_BOLD))
            row += 1
        orig_total = totals[0] if totals else None
        # Scroll-on-view: wheel adjusts score_scroll (viewport offset), never selection.
        # Clamp to visible capacity so the viewport always shows valid rows.
        try:
            _avail = max(1, (rows - 2) - row)
        except Exception:
            _avail = 1
        try:
            _max_scroll = max(0, len(view) - max(1, _avail))
            _cur = int(state.get("score_scroll", 0) or 0)
            if _cur < 0:
                _cur = 0
            if _cur > _max_scroll:
                _cur = _max_scroll
            state["score_scroll"] = _cur
        except Exception:
            state["score_scroll"] = 0
        _start = int(state.get("score_scroll", 0) or 0)
        for i in view[_start:]:
            if row >= rows - 2:
                break
            if i >= len(state["cand_edits"]):
                continue
            edits = state["cand_edits"][i]
            bd = breakdowns[i] if i < len(breakdowns) else {}
            total = totals[i] if i < len(totals) else None
            mark = "*" if i == winner else " "
            filt = "F" if total is None else " "
            total_s = ("%.1f" % total) if total is not None else "filt"
            sel = ">" if i == state["cand_idx"] else " "
            rank = rank_of.get(i, 0)
            delta_s = ""
            delta_v = 0.0
            has_delta = False
            if total is not None and orig_total is not None and i != 0:
                delta_v = float(total) - float(orig_total)
                delta_s = " d%+.1f" % delta_v
                has_delta = True
            base = "%s%s%s#%-2d[%d] total=%s set=%.0f alb=%.0f t=%.0f forb=%d ch=%+d ytm=%+d vw=%s %s" % (
                sel, mark, filt, rank, i, total_s, float(bd.get("set_score", 0.0)),
                float(bd.get("album_score", 0.0)), float(bd.get("time_score", 0.0)),
                int(bd.get("forbidden_penalty", 0)), int(bd.get("channel_bonus", 0)),
                int(bd.get("ytm_bonus", 0)), edits.get("views", "") or "-",
                (edits.get("name", "") or "")[:24],
            )
            # Row style: winner > filtered > selected > normal.
            if i == winner:
                attr = _ca(_attrs, "winner", curses.A_BOLD)
            elif total is None:
                attr = _ca(_attrs, "filtered", curses.A_DIM)
            else:
                attr = curses.A_NORMAL
            if i == state["cand_idx"]:
                attr |= curses.A_UNDERLINE
            stdscr.addnstr(row, 0, base[: cols - 1], cols - 1, attr)
            # Delta suffix in pos/neg color (overlay, keeps keyboard flow intact).
            if has_delta and len(base) < cols - 1:
                dattr = _ca(_attrs, "dpos", curses.A_BOLD) if delta_v >= 0 else _ca(_attrs, "dneg", curses.A_BOLD)
                try:
                    stdscr.addnstr(row, min(len(base), cols - 1), delta_s[: max(0, cols - 1 - len(base))], max(0, cols - 1 - len(base)), dattr)
                except Exception:
                    pass
            row += 1
        # Selected breakdown line.
        if n_cands() and row < rows - 2:
            i = state["cand_idx"]
            bd = breakdowns[i]
            fw = ",".join(bd.get("forbidden", []) or []) or "-"
            line = "sel[%d] forb=%s ch=%s ytm=%s unrelated=off views=%s url=%s" % (
                i, fw, bd.get("channel_detail", ""), "YTM+5" if bd.get("ytm_bonus") else "ytm+0",
                state["cand_edits"][i].get("views", "") or "-",
                (state["cand_edits"][i].get("url", "") or "")[:40],
            )
            stdscr.addnstr(row, 0, line[: cols - 1], cols - 1, curses.A_DIM)
            row += 1
        # Verbose pane (toggle 'v'; live edited-query preview, no network).
        _vtrace = state.get("verbose_trace")
        _vshow = bool(state.get("verbose_show")) and _vtrace is not None
        if _vtrace is not None and _vshow and row < rows - 2:
            try:
                _edited_preview = preview_edited_query(state.get("song_edits", {}))
            except Exception:
                _edited_preview = "-"
            try:
                _vblock = _vtrace.format_block(provider, n_cands(), edited_preview=_edited_preview)
            except Exception:
                _vblock = "verbose unavailable"
            _vlines = str(_vblock).splitlines()
            if row < rows - 2:
                stdscr.addnstr(row, 0, "VERBOSE (-v, v to hide; queries static per fetch, preview live)"[: cols - 1], cols - 1, _ca(_attrs, "header", curses.A_BOLD))
                row += 1
            for _vline in _vlines:
                if row >= rows - 1:
                    break
                try:
                    stdscr.addnstr(row, 0, ("  " + _vline)[: cols - 1], cols - 1, curses.A_DIM)
                except Exception:
                    pass
                row += 1
        # Status / edit mode.
        if rows >= 2:
            section, _idx = _focus_sections(state)
            sort_s2 = "on" if auto_sort else "off"
            mouse_s2 = "on" if state.get("mouse") and _mouse_supported else "off"
            verb_s2 = "on" if state.get("verbose_show") and state.get("verbose_trace") is not None else "off"
            if state["editing"]:
                status = "EDIT %s: type, Backspace del, Left/Right cursor, Enter/Esc done | %s" % (
                    section, state["msg"] or "")
            else:
                status = "NAV: Tab/Up/Down move, Enter edit, [ ] switch, a/d/x add/dup/del, r reset, s sort:%s, m mouse:%s, v verb:%s, h help, q quit | %s" % (
                    sort_s2, mouse_s2, verb_s2, state["msg"] or "")
            stdscr.addnstr(rows - 1, 0, status[: cols - 1], cols - 1, curses.A_REVERSE)
        # Help overlay.
        if state["help"] and rows > 14 and cols > 50:
            h, w = min(rows - 4, 22), min(cols - 6, 70)
            top, left = 2, max(0, (cols - w) // 2)
            win = curses.newwin(h, w, top, left)
            try:
                win.bkgd(" ", _ca(_attrs, "help", curses.A_NORMAL))
            except Exception:
                pass
            win.box()
            help_lines = [
                "score tuner help (any key closes)",
                "",
                "Tab/Up/Down: move focus",
                "Enter: edit / done",
                "[ ] or PgUp/PgDn: switch candidate",
                "a add blank, d duplicate, x remove",
                "r reset to fetched values",
                "s toggle auto-sort by score (default on)",
                "m toggle mouse off/on (default off)",
                "v toggle verbose query/request pane",
                "mouse off: mousemask(0), wheel ignored;",
                "  mouse on: wheel scrolls score view,",
                "  clicks do nothing, sel unchanged",
                "q / Ctrl-C quit",
                "",
                "Scores = live order_results;",
                "breakdown = live calc helpers;",
                "no network per keystroke.",
            ]
            for li, text in enumerate(help_lines[: h - 2]):
                try:
                    win.addnstr(li + 1, 2, text[: w - 4], w - 4, _ca(_attrs, "help", curses.A_NORMAL))
                except Exception:
                    pass
            win.refresh()
            stdscr.refresh()
            stdscr.getch()
            state["help"] = False
            continue
        stdscr.refresh()
        # Position cursor when editing.
        if state["editing"]:
            try:
                curses.curs_set(1)
            except Exception:
                pass
        else:
            try:
                curses.curs_set(0)
            except Exception:
                pass
        key = stdscr.getch()
        state["msg"] = ""
        # Global quit.
        if key in (ord("q"), ord("Q")) and not state["editing"]:
            break
        if key == 3:  # Ctrl-C
            break
        if key == curses.KEY_RESIZE:
            continue
        # Help toggle.
        if key in (ord("h"), ord("H"), ord("?")) and not state["editing"]:
            state["help"] = True
            continue
        # Reset.
        if key in (ord("r"), ord("R")) and not state["editing"]:
            state["song_edits"] = dict(state["song_orig"])
            state["cand_bases"] = list(state["fetch_bases"])
            state["cand_edits"] = [dict(e) for e in state["fetch_edits"]]
            state["cand_orig"] = [dict(e) for e in state["fetch_edits"]]
            state["uids"] = list(state.get("fetch_uids", list(range(len(state["cand_bases"])))))
            state["cand_idx"] = 0
            state["score_scroll"] = 0
            state["msg"] = "reset to fetched values"
            continue
        # Mouse: wheel-only view scroll; clicks removed entirely.
        # Disabled (default): mousemask(0) + KEY_MOUSE ignored.
        # Enabled ('m'): wheel scrolls score viewport, selection unchanged.
        if key == curses.KEY_MOUSE:
            if not state.get("mouse") or not _mouse_supported or state.get("editing"):
                continue
            try:
                _mid, _mx, _my, _mz, _bstate = curses.getmouse()
            except Exception:
                continue
            try:
                _b4 = getattr(curses, "BUTTON4_PRESSED", 0)
                _b5 = getattr(curses, "BUTTON5_PRESSED", 0)
                _scroll_up = bool(_bstate & _b4) if _b4 else False
                _scroll_dn = bool(_bstate & _b5) if _b5 else False
                # Fallback wheel bits on some builds (BUTTON4/5 map to high bits).
                if not _scroll_up and _bstate & 0x00080000:
                    _scroll_up = True
                if not _scroll_dn and _bstate & 0x00100000:
                    _scroll_dn = True
                if _scroll_up or _scroll_dn:
                    # Scroll-on-view: shift score viewport, never cand_idx/focus.
                    try:
                        _avail2 = max(1, (rows - 2) - row)
                    except Exception:
                        _avail2 = 1
                    try:
                        _max2 = max(0, len(view) - max(1, _avail2))
                    except Exception:
                        _max2 = max(0, len(view) - 1)
                    try:
                        _cur2 = int(state.get("score_scroll", 0) or 0)
                    except Exception:
                        _cur2 = 0
                    _step2 = -int(_WHEEL_STEP) if _scroll_up else int(_WHEEL_STEP)
                    _cur2 = max(0, min(_max2, _cur2 + _step2))
                    state["score_scroll"] = _cur2
                    state["msg"] = "view scrolled to %d/%d (sel unchanged)" % (_cur2, _max2)
                # Clicks (BUTTON1_* etc) intentionally ignored: no focus/switch.
            except Exception:
                pass
            continue
        # Candidate add/dup/remove/switch (NAV mode only).
        if not state["editing"]:
            if key in (ord("s"), ord("S")):
                state["auto_sort"] = not bool(state.get("auto_sort", True))
                state["msg"] = "auto-sort %s" % ("on" if state["auto_sort"] else "off")
                continue
            if key in (ord("m"), ord("M")):
                if _mouse_supported:
                    state["mouse"] = not bool(state.get("mouse", False))
                    _mouse_apply(bool(state["mouse"]))
                    if state["mouse"]:
                        state["msg"] = "mouse on (wheel scrolls view only, clicks ignored)"
                    else:
                        state["msg"] = "mouse off (mousemask(0), wheel ignored)"
                else:
                    state["msg"] = "mouse unsupported"
                continue
            if key in (ord("v"), ord("V")):
                if state.get("verbose_trace") is None:
                    state["msg"] = "verbose off (re-run with -v to capture requests)"
                else:
                    state["verbose_show"] = not bool(state.get("verbose_show"))
                    state["msg"] = "verbose %s" % ("on" if state["verbose_show"] else "off")
                continue
            if key in (ord("a"), ord("A")):
                _nu = int(state.get("uid_next", len(state["cand_edits"])))
                state["cand_bases"].append(blank_result_base())
                state["cand_edits"].append(blank_result_edits())
                state["uids"].append(_nu)
                state["uid_next"] = _nu + 1
                state["cand_idx"] = len(state["cand_edits"]) - 1
                state["msg"] = "added blank candidate"
                continue
            if key in (ord("d"), ord("D")):
                if n_cands():
                    _nu = int(state.get("uid_next", len(state["cand_edits"])))
                    state["cand_bases"].append(state["cand_bases"][state["cand_idx"]])
                    state["cand_edits"].append(dict(state["cand_edits"][state["cand_idx"]]))
                    state["uids"].append(_nu)
                    state["uid_next"] = _nu + 1
                    state["cand_idx"] = len(state["cand_edits"]) - 1
                    state["msg"] = "duplicated candidate"
                continue
            if key in (ord("x"), ord("X")):
                if n_cands() > 1:
                    # Preserve selection by uid: underlying objects stay stable,
                    # only the view sorts; deleting keeps selection on next item.
                    del state["cand_bases"][state["cand_idx"]]
                    del state["cand_edits"][state["cand_idx"]]
                    try:
                        del state["uids"][state["cand_idx"]]
                    except Exception:
                        pass
                    state["cand_idx"] = max(0, min(state["cand_idx"], len(state["cand_edits"]) - 1))
                    state["msg"] = "removed candidate"
                else:
                    state["msg"] = "need >=1 candidate"
                continue
            if key in (ord("["), ord(","), curses.KEY_PPAGE):
                if n_cands():
                    try:
                        _pos = view.index(state["cand_idx"])
                        state["cand_idx"] = view[(_pos - 1) % len(view)]
                    except ValueError:
                        state["cand_idx"] = (state["cand_idx"] - 1) % n_cands()
                continue
            if key in (ord("]"), ord("."), curses.KEY_NPAGE):
                if n_cands():
                    try:
                        _pos = view.index(state["cand_idx"])
                        state["cand_idx"] = view[(_pos + 1) % len(view)]
                    except ValueError:
                        state["cand_idx"] = (state["cand_idx"] + 1) % n_cands()
                continue
            # Focus moves.
            if key in (curses.KEY_UP, curses.KEY_BTAB):
                state["focus"] = (state["focus"] - 1) % focus_count()
                state["cursor"] = len(_current_edit_value(state))
                # Keep candidate focus valid when no candidates (should not happen).
                continue
            if key in (curses.KEY_DOWN, ord("\t")):
                state["focus"] = (state["focus"] + 1) % focus_count()
                state["cursor"] = len(_current_edit_value(state))
                continue
            if key in (curses.KEY_LEFT, curses.KEY_RIGHT):
                # NAV Left/Right switches candidate in display order.
                if n_cands():
                    step = -1 if key == curses.KEY_LEFT else 1
                    try:
                        _pos = view.index(state["cand_idx"])
                        state["cand_idx"] = view[(_pos + step) % len(view)]
                    except ValueError:
                        state["cand_idx"] = (state["cand_idx"] + step) % n_cands()
                continue
            if key in (curses.KEY_ENTER, ord("\n"), ord("\r")):
                state["editing"] = True
                state["cursor"] = len(_current_edit_value(state))
                continue
            continue
        # EDIT mode keys.
        if key in (27, curses.KEY_ENTER, ord("\n"), ord("\r")):  # Esc / Enter done
            state["editing"] = False
            continue
        if key in (curses.KEY_BACKSPACE, 127, 8):
            val = _current_edit_value(state)
            pos = max(0, min(state["cursor"], len(val)))
            if pos > 0:
                _set_current_edit_value(state, val[: pos - 1] + val[pos:])
                state["cursor"] = pos - 1
            continue
        if key == curses.KEY_DC:  # Delete
            val = _current_edit_value(state)
            pos = max(0, min(state["cursor"], len(val)))
            _set_current_edit_value(state, val[:pos] + val[pos + 1:])
            continue
        if key == curses.KEY_LEFT:
            state["cursor"] = max(0, state["cursor"] - 1)
            continue
        if key == curses.KEY_RIGHT:
            state["cursor"] = min(len(_current_edit_value(state)), state["cursor"] + 1)
            continue
        if key == curses.KEY_HOME:
            state["cursor"] = 0
            continue
        if key == curses.KEY_END:
            state["cursor"] = len(_current_edit_value(state))
            continue
        if 32 <= key <= 126:  # printable ASCII
            val = _current_edit_value(state)
            pos = max(0, min(state["cursor"], len(val)))
            _set_current_edit_value(state, val[:pos] + chr(key) + val[pos:])
            state["cursor"] = pos + 1
            continue
        # Ignore other special keys in EDIT mode.


# ---------------------------------------------------------------------------
# Live fetch.
# ---------------------------------------------------------------------------

def load_live(
    song_url: str,
    limit: int,
    timeout: float,
    verbose: Optional["VerboseSearch"] = None,
) -> Tuple[Any, List[Any], Optional[str], Optional[str]]:
    """Resolve Song.from_url + pooled search_yt_candidates (network once).

    When *verbose* is given its transport wrapper is installed first, so the
    real pooled query + per-provider ``get_results`` args + underlying
    ``YTMusic.search`` / ``YoutubeDL.extract_info`` calls are captured for
    display only (never mutated), then uninstalled. Queries are static per
    song fetch; the TUI preview of edited queries is computed separately.
    """
    ghostify_dl = import_ghostify_dl()
    Song, _Result = import_song_result()
    try:
        ghostify_dl._ensure_spotify_client()
    except Exception as exc:
        raise TunerError(
            "Could not init Spotify client (%s: %s)." % (type(exc).__name__, exc),
            EXIT_METADATA,
        ) from exc
    try:
        song = Song.from_url(song_url)
    except Exception as exc:
        raise TunerError(
            "Could not resolve Spotify metadata for %s (%s: %s)."
            % (song_url, type(exc).__name__, exc),
            EXIT_METADATA,
        ) from exc
    if verbose is not None:
        try:
            verbose.install(ghostify_dl)
        except Exception:
            pass
    try:
        report = ghostify_dl.search_yt_candidates(song, limit=limit, per_track_timeout=timeout)
    except Exception as exc:
        raise TunerError(
            "YouTube provider failure (%s: %s)." % (type(exc).__name__, exc),
            EXIT_PROVIDER,
        ) from exc
    finally:
        if verbose is not None:
            try:
                verbose.uninstall()
            except Exception:
                pass
    results = list((report or {}).get("results", None) or [])
    # Real query captured from the wrapper when available; fall back to the
    # report dict (same live value the fetch built via create_song_title).
    if verbose is not None:
        try:
            if not verbose.query:
                verbose.query = (report or {}).get("query")
        except Exception:
            pass
    if not results:
        raise TunerError(
            "No YouTube candidates for %s (provider=%s query=%s)." % (
                song_url, (report or {}).get("provider"), (report or {}).get("query")),
            EXIT_NO_CANDIDATES,
        )
    return song, results, (report or {}).get("provider"), (report or {}).get("query")


# ---------------------------------------------------------------------------
# CLI.
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    """Build the CLI parser (no live imports, so --help always works)."""
    parser = argparse.ArgumentParser(
        prog="score_tuner.py",
        description=(
            "Desktop-only TUI: edit Spotify song + YT/YT-Music candidates side "
            "by side; comparison score recalculates live via spotdl "
            "order_results. TUI uses stdlib curses (no new deps; optional "
            "'pip install textual' for a future rich UI, never added to "
            "app/build.gradle Chaquopy pip). --no-tui prints a plain table."
        ),
    )
    parser.add_argument("url", nargs="?", default=None, help="Spotify track URL (required unless --stub)")
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT, help="candidates per provider (default %(default)s)")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help="per-track YT search budget seconds (default %(default)s)")
    parser.add_argument("--no-tui", action="store_true", help="plain ASCII table to stdout (no curses)")
    parser.add_argument("--stub", action="store_true", help="offline stubbed song+candidates (no network; smoke test)")
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help=(
            "show requests + real query used: pooled create_song_title query, "
            "YTM filter/limit/ignore_spelling + ytsearch10 params, provider "
            "order/counts, YTMusic.search / YoutubeDL.extract_info calls "
            "(redacted); TUI toggles pane with 'v', --no-tui prints a block"
        ),
    )
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    """CLI entry point. Returns a process exit code."""
    parser = build_parser()
    args = parser.parse_args(argv)
    limit = max(1, int(args.limit))
    timeout = max(0.0, float(args.timeout))
    verbose_trace: Optional[VerboseSearch] = VerboseSearch(limit, timeout) if args.verbose else None
    if verbose_trace is not None:
        import logging as _logging

        try:
            _logging.basicConfig(level=_logging.DEBUG, format="%(name)s: %(message)s")
            _logging.getLogger("spotdl").setLevel(_logging.WARNING)
        except Exception:
            pass

    if args.stub:
        try:
            song_base = build_stub_song()
            cand_bases = build_stub_results()
            provider: Optional[str] = "stub"
            query: Optional[str] = "stub query"
            if verbose_trace is not None:
                # Stub never touches the network: synthesize the same shape
                # the live wrapper would have captured (query + counts).
                try:
                    from spotdl.utils.formatter import create_song_title as _cst

                    verbose_trace.query = str(
                        _cst(
                            getattr(song_base, "name", ""),
                            list(getattr(song_base, "artists", None) or []),
                            for_lyrics=False,
                        )
                    )
                except Exception:
                    verbose_trace.query = query
                verbose_trace.provider_counts = {"stub": len(cand_bases)}
                verbose_trace.calls = [
                    {
                        "method": "stub",
                        "endpoint": "offline (no network)",
                        "params": {"query": verbose_trace.query, "limit": limit},
                        "count": len(cand_bases),
                        "note": "--stub: no YTMusic.search / ytsearch ran",
                    }
                ]
        except TunerError as exc:
            print("score_tuner: %s" % exc, file=sys.stderr)
            return exc.exit_code
    else:
        if not args.url:
            parser.print_usage(sys.stderr)
            print("score_tuner: expected a Spotify track URL (or pass --stub).", file=sys.stderr)
            return EXIT_USAGE
        ghostify_dl = import_ghostify_dl()
        track_id = None
        try:
            track_id = ghostify_dl.extract_spotify_id(args.url.strip())
        except Exception:
            track_id = None
        if not track_id:
            print(
                "score_tuner: not a Spotify track URL: %r\nExpected https://open.spotify.com/track/<22-char id>."
                % args.url,
                file=sys.stderr,
            )
            return EXIT_USAGE
        canonical = "https://open.spotify.com/track/%s" % track_id
        try:
            song_base, cand_bases, provider, query = load_live(canonical, limit, timeout, verbose_trace)
        except TunerError as exc:
            print("score_tuner: %s" % exc, file=sys.stderr)
            return exc.exit_code

    if args.no_tui:
        song_edits = song_to_edits(song_base)
        cand_edits = [result_to_edits(r) for r in cand_bases]
        if verbose_trace is not None:
            if not verbose_trace.query:
                verbose_trace.query = query
            print(
                verbose_trace.format_block(
                    provider,
                    len(cand_bases),
                    edited_preview=preview_edited_query(song_edits),
                )
            )
        print(format_no_tui(song_base, song_edits, cand_bases, cand_edits, provider, query))
        return EXIT_OK

    # Curses TUI (desktop-only stdlib; fails cleanly without a TTY).
    try:
        import curses  # noqa: WPS433 (lazy: --help/--no-tui work without a tty)
    except ImportError as exc:
        print("score_tuner: curses is unavailable (%s); use --no-tui." % type(exc).__name__, file=sys.stderr)
        return EXIT_DEPENDENCY
    if not sys.stdin.isatty() and not sys.stdout.isatty():
        print("score_tuner: no TTY detected; use --no-tui for plain output.", file=sys.stderr)
        return EXIT_USAGE
    try:
        import functools

        if verbose_trace is not None and not verbose_trace.query:
            verbose_trace.query = query
        entry = functools.partial(
            run_curses_ui,
            song_base=song_base,
            cand_bases=cand_bases,
            provider=provider,
            query=query,
            verbose_trace=verbose_trace,
        )
        curses.wrapper(entry)
    except KeyboardInterrupt:
        pass
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())

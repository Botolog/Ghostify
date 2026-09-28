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
    i ............................ explain popup: how total was calculated
                                   (centered, bordered, Up/Down/PgUp/PgDn/wheel
                                   scrolls view, q/Esc/i closes; clicks off)
    Mouse (disabled by default): when enabled with 'm', wheel up/down
           scrolls the score-table viewport itself (selection unchanged);
           when the explain popup is open the wheel scrolls the popup;
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
      ``calc_duration_bonus`` (duration 10-delta), ``check_forbidden_words`` +
      ``FORBIDDEN_WORD_PENALTY`` (forbidden -5), ``ENABLE_UNRELATED_WORDS``
      flag (unrelated off), channel +10/-15 detection via live ``slugify``
      (same condition as ``order_results``), YTM +5 via live
      ``_is_youtube_music_source`` + ``YTM_SOURCE_BONUS``, VEVO +3 via live
      ``VEVO_BONUS``, views via cached ``Result.views`` only (live
      ``is_views_disqualified`` / ``calc_views_penalty`` + live
      ``get_best_matches`` shows the top-8 views contention without network).
    * ``i`` explain popup + ``--explain [idx]`` reuse the same live helpers
      (``build_spotify_set`` / ``build_result_set`` / ``calc_set_score``,
      ``build_album_word_set``, ``calc_duration_bonus``,
      ``check_common_word``, views helpers, ``slugify``) — never cloned
      formulas — to walk base → forb → channel → VEVO → album → explicit →
      duration → YTM → views penalty (+ top-8 +0..15 note) = total.
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

from ghostify_consts import (
    ALBUM_BLEND_HIGH_INCLUSIVE as _ALBUM_HIGH,
    ALBUM_BLEND_LOW_EXCLUSIVE as _ALBUM_LOW,
    ALBUM_STRONG_SET_THRESHOLD as _ALBUM_STRONG,
    ARTISTS_MATCH_THRESHOLD as _ARTISTS_THRESH,
    CHANNEL_MATCH_BONUS as _CHANNEL_BONUS,
    CHANNEL_MISMATCH_PENALTY as _CHANNEL_PENALTY,
    DEFAULT_PER_TRACK_TIMEOUT as _CONST_TIMEOUT,
    DURATION_BASE_BONUS as _DURATION_BASE,
    EXPLICIT_MISMATCH_PENALTY as _EXPLICIT_PEN,
    FORBIDDEN_WORD_PENALTY as _FORBIDDEN_PEN,
    NAME_MATCH_THRESHOLD as _NAME_THRESH,
    SCORE_TUNER_DEFAULT_LIMIT as _TUNER_LIMIT,
    SET_EXACT_BONUS as _SET_EXACT,
    SET_JACCARD_WEIGHT as _SET_JW,
    SET_RECALL_WEIGHT as _SET_RW,
    SET_SCORE_SCALE as _SET_SCALE,
    VEVO_BONUS as _VEVO_BONUS,
    VIEWS_DQ_THRESHOLD as _VIEWS_DQ,
    VIEWS_HIGH_PENALTY as _VIEWS_HIGH_PEN,
    VIEWS_HIGH_THRESHOLD as _VIEWS_HIGH,
    VIEWS_MID_PENALTY as _VIEWS_MID_PEN,
    VIEWS_MID_THRESHOLD as _VIEWS_MID,
    VIEWS_TOP_N as _VIEWS_TOP_N,
    YTM_SOURCE_BONUS as _YTM_BONUS,
)

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_DEPENDENCY = 3
EXIT_METADATA = 4
EXIT_PROVIDER = 5
EXIT_NO_CANDIDATES = 6

DEFAULT_LIMIT = _TUNER_LIMIT
DEFAULT_TIMEOUT = _CONST_TIMEOUT

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
        penalty = int(getattr(matching, "FORBIDDEN_WORD_PENALTY", _FORBIDDEN_PEN))
    except Exception:
        penalty = int(_FORBIDDEN_PEN)
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
            detail = "no artist in channel (-%d)" % int(_CHANNEL_PENALTY)
            for artist in artists:
                slug_artist = slugify(artist).replace("-", "")
                if slug_artist and (slug_artist in slug_author or slug_author in slug_artist):
                    bonus = int(_CHANNEL_BONUS)
                    detail = "channel ~ artist (+%d)" % int(_CHANNEL_BONUS)
                    break
            else:
                bonus = -int(_CHANNEL_PENALTY)
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
        out["ytm_bonus"] = int(getattr(matching, "YTM_SOURCE_BONUS", _YTM_BONUS)) if is_ytm else 0
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


def build_explain_live(
    song_live: Any,
    result_live: Any,
    total: Optional[float] = None,
    scored: Optional[Dict[Any, float]] = None,
) -> Dict[str, Any]:
    """Full step-by-step walk for the ``i`` popup / ``--explain`` (display only).

    Final stays ``score_pool_live`` (live ``order_results``). Every number
    below comes from the same live helper/constant the scorer uses, so the
    walk can never drift: ``build_spotify_set`` / ``build_result_set`` /
    ``calc_set_score`` / ``calc_set_match``, ``check_forbidden_words`` +
    ``FORBIDDEN_WORD_PENALTY``, ``slugify`` (channel, same condition),
    ``build_album_word_set`` / ``calc_album_set_match`` (+ blend guard),
    ``calc_duration_bonus``, ``_is_youtube_music_source`` +
    ``YTM_SOURCE_BONUS``, ``VEVO_BONUS``, ``check_common_word``,
    ``is_views_disqualified`` / ``calc_views_penalty`` + ``VIEWS_*``,
    ``get_best_matches`` (top-8 contention, no network).
    Recall/Jaccard/delta math is display-only arithmetic on those live sets;
    the blended score itself is the live ``calc_set_score`` value.
    """
    matching = import_matching()
    slugify = import_slugify()
    d: Dict[str, Any] = {}
    # -- sets (live sets, display recall/Jaccard, live blended score) --
    try:
        spot_set = set(matching.build_spotify_set(song_live) or set())
    except Exception:
        spot_set = set()
    try:
        res_set = set(matching.build_result_set(result_live) or set())
    except Exception:
        res_set = set()
    d["spot_set"] = sorted(spot_set)
    d["res_set"] = sorted(res_set)
    inter = spot_set & res_set
    union = spot_set | res_set
    d["inter"] = sorted(inter)
    d["n_spot"] = len(spot_set)
    d["n_res"] = len(res_set)
    d["n_inter"] = len(inter)
    d["n_union"] = len(union)
    try:
        recall = (len(inter) / len(spot_set)) if spot_set else 0.0
    except Exception:
        recall = 0.0
    try:
        jaccard = (len(inter) / len(union)) if union else 0.0
    except Exception:
        jaccard = 0.0
    d["recall"] = float(recall)
    d["jaccard"] = float(jaccard)
    try:
        base_raw = (_SET_RW * recall + _SET_JW * jaccard) * _SET_SCALE if inter else 0.0
    except Exception:
        base_raw = 0.0
    d["base_raw"] = float(base_raw)
    exact = bool(spot_set and res_set and spot_set == res_set)
    d["exact_equal"] = exact
    try:
        set_score = float(matching.calc_set_match(song_live, result_live))
    except Exception:
        set_score = 0.0
    d["set_score"] = float(set_score)
    # Exact bonus lives inside calc_set_score (capped); show it.
    d["exact_bonus"] = float(_SET_EXACT) if exact else 0.0
    d["artists_match"] = float(set_score)
    d["name_initial"] = float(set_score)
    # -- forbidden (live penalty each) --
    try:
        has_fw, fw_words = matching.check_forbidden_words(song_live, result_live)
        fw_list = list(fw_words or [])
    except Exception:
        has_fw, fw_list = False, []
    try:
        fw_pen_each = int(getattr(matching, "FORBIDDEN_WORD_PENALTY", _FORBIDDEN_PEN))
    except Exception:
        fw_pen_each = int(_FORBIDDEN_PEN)
    d["forbidden"] = fw_list
    d["forbidden_hit"] = bool(has_fw)
    d["forbidden_each"] = int(fw_pen_each)
    d["forbidden_penalty"] = int(-fw_pen_each * len(fw_list)) if has_fw else 0
    try:
        d["name_match"] = float(d["name_initial"] + d["forbidden_penalty"])
    except Exception:
        d["name_match"] = float(d["name_initial"])
    # -- common word (live filter) --
    try:
        d["common_word"] = bool(matching.check_common_word(song_live, result_live))
    except Exception:
        d["common_word"] = True
    # -- channel +10/-15 (same slug condition as order_results) --
    try:
        author = getattr(result_live, "author", None)
        artists = list(getattr(song_live, "artists", None) or [])
        if author and artists:
            slug_author = slugify(author).replace("-", "")
            hit_artist: Optional[str] = None
            slug_author_s = slug_author
            slug_map: List[Tuple[str, str]] = []
            for a in artists:
                try:
                    sa = slugify(a).replace("-", "")
                except Exception:
                    sa = ""
                slug_map.append((str(a), sa))
                if sa and (sa in slug_author_s or slug_author_s in sa):
                    hit_artist = str(a)
                    break
            if hit_artist is not None:
                d["channel_bonus"] = int(_CHANNEL_BONUS)
                d["channel_hit"] = True
                d["channel_artist"] = hit_artist
                d["channel_detail"] = "channel ~ artist '%s' (+%d)" % (hit_artist, int(_CHANNEL_BONUS))
            else:
                d["channel_bonus"] = -int(_CHANNEL_PENALTY)
                d["channel_hit"] = False
                d["channel_artist"] = None
                d["channel_detail"] = "no artist in channel (-%d)" % int(_CHANNEL_PENALTY)
            d["slug_author"] = slug_author_s
            d["slug_artists"] = [sa for _a, sa in slug_map]
        else:
            d["channel_bonus"] = 0
            d["channel_hit"] = False
            d["channel_artist"] = None
            d["channel_detail"] = "no channel/artists (0)"
            d["slug_author"] = ""
            d["slug_artists"] = []
    except Exception:
        d["channel_bonus"] = 0
        d["channel_hit"] = False
        d["channel_artist"] = None
        d["channel_detail"] = "n/a"
        d["slug_author"] = ""
        d["slug_artists"] = []
    # -- VEVO bonus (raw title has vevo and verified or channel-hit) --
    try:
        vevo_each = int(getattr(matching, "VEVO_BONUS", _VEVO_BONUS))
    except Exception:
        vevo_each = int(_VEVO_BONUS)
    try:
        raw_name = str(getattr(result_live, "name", "") or "")
        has_vevo_word = "vevo" in raw_name.lower()
    except Exception:
        raw_name, has_vevo_word = "", False
    try:
        verified = bool(getattr(result_live, "verified", False))
    except Exception:
        verified = False
    d["verified"] = verified
    d["has_vevo_word"] = bool(has_vevo_word)
    if has_vevo_word and (verified or d.get("channel_hit")):
        d["vevo_bonus"] = int(vevo_each)
        d["vevo_reason"] = "title has 'vevo' + %s (+%d)" % (
            "verified" if verified else "channel-hit", vevo_each)
    elif has_vevo_word:
        d["vevo_bonus"] = 0
        d["vevo_reason"] = "title has 'vevo' but unverified + no channel-hit (+0)"
    else:
        d["vevo_bonus"] = 0
        d["vevo_reason"] = "no 'vevo' in title (+0)"
    d["vevo_each"] = int(vevo_each)
    # -- album sets + blend guard (live) --
    try:
        song_album = getattr(song_live, "album_name", None)
    except Exception:
        song_album = None
    try:
        res_album = getattr(result_live, "album", None)
    except Exception:
        res_album = None
    d["song_album"] = "" if song_album is None else str(song_album)
    d["res_album"] = "" if res_album is None else str(res_album)
    try:
        song_album_set = set(matching.build_album_word_set(song_album) or set())
    except Exception:
        song_album_set = set()
    try:
        res_album_set = set(matching.build_album_word_set(res_album) or set())
    except Exception:
        res_album_set = set()
    d["song_album_set"] = sorted(song_album_set)
    d["res_album_set"] = sorted(res_album_set)
    try:
        album_match = float(matching.calc_album_set_match(song_live, result_live))
    except Exception:
        album_match = 0.0
    d["album_match"] = float(album_match)
    try:
        isrc_search = bool(getattr(result_live, "isrc_search", False))
    except Exception:
        isrc_search = False
    d["isrc_search"] = bool(isrc_search)
    # Guard exactly as order_results: verified + not isrc + album + low<alb<=high + set<strong.
    blend_ok = bool(
        verified and (not isrc_search) and res_album
        and (_ALBUM_LOW < album_match <= _ALBUM_HIGH) and (set_score < _ALBUM_STRONG)
    )
    d["album_blend"] = bool(blend_ok)
    if blend_ok:
        d["album_reason"] = "verified + album %.1f in (0,%d] + set %.1f<%d → blend" % (
            album_match, int(_ALBUM_HIGH), set_score, int(_ALBUM_STRONG))
    else:
        bits: List[str] = []
        bits.append("verified=%s" % ("y" if verified else "n"))
        bits.append("isrc_search=%s" % ("y" if isrc_search else "n"))
        bits.append("album=%s" % ("y" if res_album else "n"))
        bits.append("album_match=%.1f%s" % (
            album_match, " in (0,%d]" % int(_ALBUM_HIGH) if (_ALBUM_LOW < album_match <= _ALBUM_HIGH) else " (skip: 0 or >%d)" % int(_ALBUM_HIGH)))
        bits.append("set=%.1f%s" % (
            set_score, "<%d" % int(_ALBUM_STRONG) if set_score < _ALBUM_STRONG else ">=%d (edition, skip)" % int(_ALBUM_STRONG)))
        d["album_reason"] = "skip blend: " + ", ".join(bits)
    # -- explicit penalty (live constant) --
    try:
        song_exp = getattr(song_live, "explicit", None)
        res_exp = getattr(result_live, "explicit", None)
    except Exception:
        song_exp, res_exp = None, None
    d["song_explicit"] = song_exp
    d["res_explicit"] = res_exp
    if (res_exp is not None and song_exp is not None) and (res_exp != song_exp):
        d["explicit_penalty"] = -int(_EXPLICIT_PEN)
        d["explicit_reason"] = "explicit %s vs %s mismatch (-%d)" % (song_exp, res_exp, int(_EXPLICIT_PEN))
    else:
        d["explicit_penalty"] = 0
        if res_exp is None or song_exp is None:
            d["explicit_reason"] = "explicit unknown on one side (+0)"
        else:
            d["explicit_reason"] = "explicit both %s (+0)" % (song_exp,)
    # -- duration 10-delta (live) --
    try:
        song_secs = float(getattr(song_live, "duration", 0) or 0)
    except (TypeError, ValueError):
        song_secs = 0.0
    try:
        video_secs = float(getattr(result_live, "duration", 0) or 0)
    except (TypeError, ValueError):
        video_secs = 0.0
    d["song_secs"] = float(song_secs)
    d["video_secs"] = float(video_secs)
    try:
        delta = abs(video_secs - song_secs) if (song_secs > 0 and video_secs > 0) else 0.0
    except Exception:
        delta = 0.0
    d["delta"] = float(delta)
    try:
        dur_bonus = float(matching.calc_duration_bonus(song_live, result_live))
    except Exception:
        dur_bonus = 0.0
    d["duration_bonus"] = float(dur_bonus)
    if song_secs <= 0 or video_secs <= 0:
        d["duration_reason"] = "missing duration on one side (+0, not a penalty)"
    else:
        d["duration_reason"] = "%g - %.1fs delta = %+.1f" % (float(_DURATION_BASE), delta, dur_bonus)
    # -- YTM bonus (live detector + constant) --
    try:
        detector = getattr(matching, "_is_youtube_music_source", None)
        is_ytm = bool(detector(result_live)) if callable(detector) else False
    except Exception:
        is_ytm = False
    try:
        ytm_each = int(getattr(matching, "YTM_SOURCE_BONUS", _YTM_BONUS))
    except Exception:
        ytm_each = int(_YTM_BONUS)
    d["is_ytm"] = bool(is_ytm)
    d["ytm_each"] = int(ytm_each)
    d["ytm_bonus"] = int(ytm_each) if is_ytm else 0
    try:
        src = str(getattr(result_live, "source", "") or "")
        url = str(getattr(result_live, "url", "") or "")
    except Exception:
        src, url = "", ""
    d["source"] = src
    d["url"] = url
    if is_ytm:
        why = "source=%s" % (src or "?")
        if "music.youtube.com" in url:
            why += " + music.youtube.com url"
        d["ytm_reason"] = "%s → YTM (+%d)" % (why, ytm_each)
    else:
        d["ytm_reason"] = "source=%s (plain YouTube) (+0)" % (src or "?")
    # -- unrelated (off, live flag) --
    try:
        d["unrelated_enabled"] = bool(getattr(matching, "ENABLE_UNRELATED_WORDS_PENALTY", False))
    except Exception:
        d["unrelated_enabled"] = False
    d["unrelated_penalty"] = 0
    # -- views: DQ / -20 / -10 / +0 tiers (live) + top-8 note --
    views_raw = getattr(result_live, "views", None)
    d["views_raw"] = views_raw
    try:
        d["views_dq"] = bool(matching.is_views_disqualified(views_raw))
    except Exception:
        d["views_dq"] = False
    try:
        d["views_penalty"] = float(matching.calc_views_penalty(views_raw))
    except Exception:
        d["views_penalty"] = 0.0
    try:
        dq_thr = int(getattr(matching, "VIEWS_DQ_THRESHOLD", _VIEWS_DQ))
        mid_thr = int(getattr(matching, "VIEWS_MID_THRESHOLD", _VIEWS_MID))
        high_thr = int(getattr(matching, "VIEWS_HIGH_THRESHOLD", _VIEWS_HIGH))
    except Exception:
        dq_thr, mid_thr, high_thr = int(_VIEWS_DQ), int(_VIEWS_MID), int(_VIEWS_HIGH)
    d["views_thr"] = (dq_thr, mid_thr, high_thr)
    # Normalize for display (None/0 => unknown).
    try:
        norm_fn = getattr(matching, "_normalize_views", None)
        norm = norm_fn(views_raw) if callable(norm_fn) else None
    except Exception:
        norm = None
    d["views_norm"] = norm
    if d["views_dq"]:
        d["views_reason"] = "%s views < %s → disqualified (dropped)" % (
            _fmt_int(views_raw), _fmt_int(dq_thr))
    elif norm is None:
        d["views_reason"] = "views unknown (None/0) → -%d (never DQ, fresh uploads survive)" % int(_VIEWS_MID_PEN)
    elif norm < mid_thr:
        d["views_reason"] = "%s views < %s → -%d" % (_fmt_int(norm), _fmt_int(mid_thr), int(_VIEWS_MID_PEN))
    elif norm < high_thr:
        d["views_reason"] = "%s views < %s → -%d" % (_fmt_int(norm), _fmt_int(high_thr), int(_VIEWS_HIGH_PEN))
    else:
        d["views_reason"] = "%s views ≥ %s → +0" % (_fmt_int(norm), _fmt_int(high_thr))
    # Top-N views contention (live get_best_matches, no network).
    d["in_top8"] = False
    d["top8_note"] = "not in top-%d → no views weighting (+0)" % int(_VIEWS_TOP_N)
    try:
        if scored:
            cont = views_contenders(dict(scored))
            for cand in cont:
                if cand is result_live or cand == result_live:
                    d["in_top8"] = True
                    break
            if d["in_top8"]:
                d["top8_note"] = "in top-%d → get_best_result may add +0..+15 by relative views (not in total)" % int(_VIEWS_TOP_N)
            else:
                d["top8_note"] = "outside top-%d → no +0..+15 views weighting (+0)" % int(_VIEWS_TOP_N)
        else:
            d["top8_note"] = "top-%d unknown here (pool needed); live pick adds +0..+15 among top-%d only" % (int(_VIEWS_TOP_N), int(_VIEWS_TOP_N))
    except Exception:
        pass
    # -- thresholds (live rules) --
    d["name_thresh"] = float(d["name_match"]) > float(_NAME_THRESH)
    try:
        src_is_slider = (getattr(result_live, "source", None) == "slider.kz")
    except Exception:
        src_is_slider = False
    d["artists_thresh"] = bool(float(d["artists_match"]) >= float(_ARTISTS_THRESH) or src_is_slider)
    d["slider_bypass"] = bool(src_is_slider)
    # -- final walk in live order_results order (additive steps commute, --
    # -- album blend does not, so keep real order to match the total) --
    walk: List[Tuple[str, float]] = []
    avg0 = (float(d["artists_match"]) + float(d["name_match"])) / 2.0
    walk.append(("base (artists+name)/2", float(avg0)))
    cur = float(avg0)
    cur += float(d["channel_bonus"])
    walk.append(("+ channel", float(cur)))
    cur += float(d["vevo_bonus"])
    walk.append(("+ VEVO", float(cur)))
    if d["album_blend"]:
        cur = (cur + float(d["album_match"])) / 2.0
        walk.append(("blend w/ album", float(cur)))
    else:
        walk.append(("album skipped", float(cur)))
    cur += float(d["explicit_penalty"])
    walk.append(("+ explicit", float(cur)))
    cur += float(d["duration_bonus"])
    walk.append(("+ duration", float(cur)))
    cur += float(d["ytm_bonus"])
    walk.append(("+ YTM", float(cur)))
    # Views penalty applies only when not DQ (DQ drops the row instead).
    if not d["views_dq"]:
        cur += float(d["views_penalty"])
        walk.append(("+ views penalty", float(cur)))
    else:
        walk.append(("views DQ (dropped)", float(cur)))
    d["walk"] = walk
    d["walk_total"] = float(cur)
    d["total"] = None if total is None else float(total)
    # Filter reason when total is None (live order_results drops the row).
    filt: Optional[str] = None
    if total is None:
        if not d["common_word"]:
            filt = "no common word (live check_common_word)"
        elif not d["name_thresh"]:
            filt = "name %.1f ≤ %d (live name filter)" % (float(d["name_match"]), int(_NAME_THRESH))
        elif not d["artists_thresh"]:
            filt = "artists %.1f < %d (live artists filter)" % (float(d["artists_match"]), int(_ARTISTS_THRESH))
        elif d["views_dq"]:
            filt = "views DQ (<%s)" % _fmt_int(dq_thr)
        else:
            filt = "filtered by live order_results"
    d["filter_reason"] = filt
    # DQ rescue: DQ'd but still has a total → all-DQ pool survivor.
    d["dq_rescued"] = bool(d["views_dq"] and total is not None)
    return d


def _fmt_int(value: Any) -> str:
    try:
        if value is None:
            return "-"
        return "%d" % int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return str(value)


def explain_colored_lines(
    d: Dict[str, Any],
    idx: Optional[int] = None,
    rank: Optional[int] = None,
    winner: bool = False,
) -> List[Tuple[str, str]]:
    """Render ``build_explain_live`` as (text, kind) rows for TUI/plain.

    Kinds: head (cyan), pass/bonus (green), fail/penalty (red),
    total/winner (yellow), dim (filtered), normal. Plain mode joins texts.
    """
    L: List[Tuple[str, str]] = []
    title_bits = []
    if idx is not None:
        title_bits.append("[%d]" % idx)
    if rank is not None:
        title_bits.append("#%d" % rank)
    title_bits.append("how the total was calculated")
    if winner:
        title_bits.append("★ winner")
    L.append((" ".join(title_bits), "head"))
    L.append(("", "normal"))
    # Sets.
    spot_s = ", ".join(d.get("spot_set", []) or []) or "-"
    res_s = ", ".join(d.get("res_set", []) or []) or "-"
    inter_s = ", ".join(d.get("inter", []) or []) or "-"
    L.append(("TITLE + ARTISTS → WORD SETS (stopwords dropped, live sets)", "head"))
    L.append(("  Spotify words: %s" % _truncate(spot_s, 110), "normal"))
    L.append(("  Result words : %s" % _truncate(res_s, 110), "normal"))
    L.append(("  Shared words : %s" % _truncate(inter_s, 110),
               "pass" if d.get("n_inter") else "fail"))
    n_inter = int(d.get("n_inter", 0) or 0)
    n_spot = int(d.get("n_spot", 0) or 0)
    n_union = int(d.get("n_union", 0) or 0)
    rec = float(d.get("recall", 0.0))
    jac = float(d.get("jaccard", 0.0))
    base_raw = float(d.get("base_raw", 0.0))
    set_sc = float(d.get("set_score", 0.0))
    if n_spot and n_union:
        L.append((
            "  You share %d/%d words (recall %.1f%%) with %d unique total (Jaccard %.1f%%)"
            % (n_inter, n_spot, rec * 100.0, n_union, jac * 100.0), "normal"))
        L.append((
            "  Base = (%.1f×recall + %.1f×Jaccard)×%.0f = %.1f%s → %.1f"
            % (float(_SET_RW), float(_SET_JW), float(_SET_SCALE), base_raw, " +%g exact" % float(_SET_EXACT) if d.get("exact_equal") else "",
               set_sc), "pass" if set_sc >= float(_ARTISTS_THRESH) else ("fail" if set_sc <= float(_NAME_THRESH) else "normal")))
        if d.get("exact_equal"):
            L.append(("  Exact same word set → +%g bonus (capped at %.0f). Nice, official-looking!"
                       % (float(_SET_EXACT), float(_SET_SCALE)), "bonus"))
        else:
            L.append(("  Extra/missing words lower Jaccard — covers & remixes rank lower.", "dim"))
    else:
        L.append(("  Empty word set on one side → set score 0.0 (nothing to compare).", "fail"))
    L.append(("", "normal"))
    # Forbidden.
    L.append(("FORBIDDEN WORDS (−%d each, live list)" % int(d.get("forbidden_each", _FORBIDDEN_PEN)), "head"))
    fw = d.get("forbidden", []) or []
    if fw:
        L.append(("  Found '%s' not in the Spotify title → %+d"
                   % (", ".join(fw), int(d.get("forbidden_penalty", 0))), "penalty"))
        L.append(("  Plain English: this looks like a remix/slowed/live variant.", "dim"))
    else:
        L.append(("  None found → +0. Clean title.", "pass"))
    L.append(("  Name score = set %.1f %+.0f = %.1f"
               % (float(d.get("name_initial", 0.0)),
                  float(d.get("forbidden_penalty", 0)),
                  float(d.get("name_match", 0.0))), "normal"))
    L.append(("", "normal"))
    # Channel.
    L.append(("CHANNEL check (+%d official / −%d random upload)" % (int(_CHANNEL_BONUS), int(_CHANNEL_PENALTY)), "head"))
    slug_a = str(d.get("slug_author", "") or "-")
    if d.get("channel_bonus") == int(_CHANNEL_BONUS):
        L.append(("  Uploader matches '%s' (slug '%s') → +%d"
                   % (d.get("channel_artist", "?"), slug_a, int(_CHANNEL_BONUS)), "bonus"))
        L.append(("  Plain English: uploaded by the artist. Trusted.", "pass"))
    elif d.get("channel_bonus") == -int(_CHANNEL_PENALTY):
        L.append(("  Uploader slug '%s' matches no artist → −%d" % (slug_a, int(_CHANNEL_PENALTY)), "penalty"))
        L.append(("  Plain English: random upload — pushed down, not removed.", "dim"))
    else:
        L.append(("  %s → +0" % d.get("channel_detail", ""), "dim"))
    L.append(("", "normal"))
    # VEVO.
    L.append(("VEVO official (+%d)" % int(d.get("vevo_each", _VEVO_BONUS)), "head"))
    vb = int(d.get("vevo_bonus", 0) or 0)
    L.append(("  %s" % d.get("vevo_reason", ""), "bonus" if vb else "dim"))
    L.append(("", "normal"))
    # Album.
    L.append(("ALBUM blend (verified + weak-album rescue)", "head"))
    L.append(("  Spotify album: '%s' → [%s]"
               % (_truncate(d.get("song_album", ""), 44) or "-",
                  ", ".join(d.get("song_album_set", []) or []) or "-"), "normal"))
    L.append(("  Result album : '%s' → [%s]"
               % (_truncate(d.get("res_album", ""), 44) or "-",
                  ", ".join(d.get("res_album_set", []) or []) or "-"), "normal"))
    L.append(("  Album score %.1f; %s"
               % (float(d.get("album_match", 0.0)), d.get("album_reason", "")),
               "bonus" if d.get("album_blend") else "dim"))
    if d.get("album_blend"):
        L.append(("  Plain English: verified but album looks off → average halved toward album.", "dim"))
    L.append(("", "normal"))
    # Explicit + duration.
    L.append(("EXPLICIT + DURATION", "head"))
    ep = int(d.get("explicit_penalty", 0) or 0)
    L.append(("  %s" % d.get("explicit_reason", ""), "penalty" if ep else "pass"))
    L.append(("  Song %.0fs vs video %.0fs (delta %.1fs): %s"
               % (float(d.get("song_secs", 0.0)), float(d.get("video_secs", 0.0)),
                  float(d.get("delta", 0.0)), d.get("duration_reason", "")),
               "bonus" if float(d.get("duration_bonus", 0.0)) > 0
               else ("penalty" if float(d.get("duration_bonus", 0.0)) < 0 else "dim")))
    L.append(("  Plain English: spot-on length → +%g; each extra second off loses 1." % float(_DURATION_BASE), "dim"))
    L.append(("", "normal"))
    # YTM.
    L.append(("YOUTUBE MUSIC source (+%d)" % int(d.get("ytm_each", _YTM_BONUS)), "head"))
    yb = int(d.get("ytm_bonus", 0) or 0)
    L.append(("  %s" % d.get("ytm_reason", ""), "bonus" if yb else "dim"))
    L.append(("", "normal"))
    # Views.
    L.append(("VIEWS (absolute penalty + relative top-%d note)" % int(_VIEWS_TOP_N), "head"))
    L.append(("  %s" % d.get("views_reason", ""), "penalty"
               if (d.get("views_dq") or float(d.get("views_penalty", 0.0)) < 0) else "pass"))
    if d.get("dq_rescued"):
        L.append(("  All candidates were <%s: this best-DQ row was rescued (keeps pre-views score)." % _fmt_int(_VIEWS_DQ),
                   "bonus"))
    L.append(("  %s" % d.get("top8_note", ""),
               "bonus" if d.get("in_top8") else "dim"))
    L.append(("  Plain English: tiny view counts get −%d/−%d; <%s is dropped outright." % (int(_VIEWS_HIGH_PEN), int(_VIEWS_MID_PEN), _fmt_int(_VIEWS_DQ)), "dim"))
    L.append(("  Unrelated-words penalty is OFF in live code (+0 always).", "dim"))
    L.append(("", "normal"))
    # Thresholds.
    L.append(("FILTERS (live order_results gates)", "head"))
    cw = bool(d.get("common_word"))
    L.append(("  common word in title: %s" % ("pass ✓" if cw else "FAIL ✗ (dropped)"),
               "pass" if cw else "fail"))
    nm = float(d.get("name_match", 0.0))
    nt = bool(d.get("name_thresh"))
    L.append(("  name %.1f %s %d: %s" % (nm, ">", int(_NAME_THRESH), "pass ✓" if nt else "FAIL ✗ (dropped)"),
               "pass" if nt else "fail"))
    am = float(d.get("artists_match", 0.0))
    at = bool(d.get("artists_thresh"))
    if d.get("slider_bypass"):
        L.append(("  artists %.1f <%d but source=slider.kz → bypass, pass ✓" % (am, int(_ARTISTS_THRESH)), "pass"))
    else:
        L.append(("  artists %.1f %s %d: %s" % (am, "≥", int(_ARTISTS_THRESH), "pass ✓" if at else "FAIL ✗ (dropped)"),
                   "pass" if at else "fail"))
    L.append(("  duration: no hard filter (far lengths just earn a big negative bonus).", "dim"))
    L.append(("", "normal"))
    # Final walk.
    L.append(("FINAL SUM (open-ended — can exceed 100)", "head"))
    for label, val in (d.get("walk", []) or []):
        L.append(("  %-18s = %+.1f" % (label, float(val)), "normal"))
    tot = d.get("total")
    if tot is None:
        L.append(("  FILTERED OUT — %s" % (d.get("filter_reason", "dropped") or "dropped"), "fail"))
        L.append(("  Plain English: this candidate never reaches the ranking.", "dim"))
    else:
        L.append(("  TOTAL = %.1f%s" % (float(tot), "  ★ winner" if winner else ""),
                   "total"))
        L.append(("  Plain English: base covers title+artists; bonuses stack past 100.", "dim"))
    return L


def format_explain_plain(
    song_live: Any,
    result_live: Any,
    total: Optional[float] = None,
    scored: Optional[Dict[Any, float]] = None,
    idx: Optional[int] = None,
    rank: Optional[int] = None,
    winner: bool = False,
) -> str:
    """Plain-text twin of the ``i`` popup (``--explain``, no colors)."""
    d = build_explain_live(song_live, result_live, total, scored)
    lines = [t for t, _k in explain_colored_lines(d, idx, rank, winner)]
    head = "Explain candidate%s (plain, live helpers)" % (
        " [%d]" % idx if idx is not None else "")
    rule = "-" * 72
    out = [head, rule] + lines + [rule]
    return "\n".join(out)


def views_contenders(scored: Dict[Any, float]) -> List[Any]:
    """Top-N views contenders via live get_best_matches (no network)."""
    if not scored:
        return []
    try:
        matching = import_matching()
        best = matching.get_best_matches(dict(scored), int(_VIEWS_TOP_N))
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


def _responsive_popup_geometry(
    rows: int,
    cols: int,
    content_len: int,
    width_ratio: float = 0.9,
    height_ratio: float = 0.85,
    min_w: int = 40,
    min_h: int = 12,
) -> Tuple[int, int, int, int]:
    """Responsive popup geometry: width=int(COLS*0.9), centered.

    Height is proportional (int(LINES*height_ratio)) but content-clamped
    (shrinks to content when shorter, scrolls when longer). Recomputed
    every frame from current COLS/LINES so wider terminals get wider
    popups and KEY_RESIZE reflows automatically. Tiny screens fall back
    to full-screen minus border.
    """
    try:
        rows_i = max(1, int(rows))
    except Exception:
        rows_i = 24
    try:
        cols_i = max(1, int(cols))
    except Exception:
        cols_i = 80
    try:
        w_target = int(cols_i * float(width_ratio))
    except Exception:
        w_target = max(1, cols_i - 2)
    # Fit inside screen (tiny-screen fallback: full-screen minus border).
    w_clamped = min(max(1, cols_i - 2), w_target)
    try:
        min_allowed = min(max(1, cols_i - 2), int(min_w))
    except Exception:
        min_allowed = 1
    w = max(min_allowed, w_clamped)
    w = min(max(1, cols_i - 2), w)
    try:
        h_target = int(rows_i * float(height_ratio))
    except Exception:
        h_target = max(1, rows_i - 2)
    try:
        need = int(content_len) + 3  # box top/bottom + footer
    except Exception:
        need = h_target
    if need <= 0:
        need = h_target
    h_wanted = min(h_target, need)
    try:
        min_h_allowed = min(max(1, rows_i - 2), int(min_h))
    except Exception:
        min_h_allowed = 1
    h = max(min_h_allowed, h_wanted)
    h = min(max(1, rows_i - 2), h)
    top = max(0, (rows_i - h) // 2)
    left = max(0, (cols_i - w) // 2)
    return h, w, top, left


def _wrap_popup_rows(
    rows_list: List[Tuple[str, str]], width: int
) -> List[Tuple[str, str]]:
    """Wrap (text, kind) rows to *width*, preserving kind per wrapped line."""
    try:
        w = max(1, int(width))
    except Exception:
        w = 1
    wrapped: List[Tuple[str, str]] = []
    for txt, kind in rows_list or []:
        s = "" if txt is None else str(txt)
        if s == "":
            wrapped.append(("", kind))
            continue
        try:
            import textwrap as _tw

            parts = _tw.wrap(
                s, width=w, break_long_words=True, break_on_hyphens=False
            ) or [""]
        except Exception:
            parts = [s[i : i + w] for i in range(0, len(s), w)] or [""]
        for part in parts:
            wrapped.append((part, kind))
    return wrapped


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

    Pairs: header, orig, winner, filtered, focused, help, dpos, dneg,
    exhead (cyan), expos (green), exneg (red), extot (yellow).
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
            ("exhead", curses_mod.COLOR_CYAN, -1),
            ("expos", curses_mod.COLOR_GREEN, -1),
            ("exneg", curses_mod.COLOR_RED, -1),
            ("extot", curses_mod.COLOR_BLACK, curses_mod.COLOR_YELLOW),
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
            if "exhead" in attrs:
                attrs["exhead"] |= curses_mod.A_BOLD
            if "expos" in attrs:
                attrs["expos"] |= curses_mod.A_BOLD
            if "exneg" in attrs:
                attrs["exneg"] |= curses_mod.A_BOLD
            if "extot" in attrs:
                attrs["extot"] |= curses_mod.A_BOLD
        except Exception:
            pass
        return attrs
    except Exception:
        return {}


def _explain_attr(attrs: Dict[str, int], kind: str, curses_mod: Any) -> int:
    """Map explain line kind to a curses attr (mono-safe fallbacks)."""
    try:
        if kind == "head":
            return _ca(attrs, "exhead", curses_mod.A_BOLD)
        if kind in ("pass", "bonus"):
            return _ca(attrs, "expos", curses_mod.A_BOLD)
        if kind in ("fail", "penalty"):
            return _ca(attrs, "exneg", curses_mod.A_BOLD)
        if kind in ("total", "winner"):
            return _ca(attrs, "extot", curses_mod.A_BOLD | curses_mod.A_REVERSE)
        if kind == "dim":
            return curses_mod.A_DIM
    except Exception:
        pass
    try:
        return curses_mod.A_NORMAL
    except Exception:
        return 0


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
        "explain": False,  # 'i' popup open?
        "explain_idx": 0,  # underlying candidate the popup explains
        "explain_scroll": 0,  # popup viewport offset (wheel/Up/Down/PgUp/PgDn)
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
        title = "ghostify score tuner — live order_results (q quit, h help, i explain, r reset, v verbose)"
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
                is_focus = (state["focus"] == i and not state["help"] and not state.get("explain"))
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
                is_focus = (state["focus"] == fi and not state["help"] and not state.get("explain"))
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
                is_focus = (state["focus"] == i and not state["help"] and not state.get("explain"))
                attr = _ca(_attrs, "focused", curses.A_REVERSE) if is_focus else _ca(_attrs, "orig", curses.A_NORMAL)
                stdscr.addnstr(row, 0, line[: cols - 1], cols - 1, attr)
                row += 1
            if row < rows - 1:
                stdscr.addnstr(row, 0, "PANEL %d: candidate [%d/%d] (a add, d dup, x del, [ ] switch, i explain)" % (
                    state["cand_idx"] + 2, state["cand_idx"] + 1, n_cands()), cols - 1, _ca(_attrs, "header", curses.A_BOLD))
            row += 1
            for j, (key, label, _hint) in enumerate(CAND_FIELDS):
                if row >= rows - 1:
                    break
                fi = len(SONG_FIELDS) + j
                edits = state["cand_edits"][state["cand_idx"]] if n_cands() else {}
                val = str(edits.get(key, ""))
                line = "  %-10s [%s]" % (label, val)
                is_focus = (state["focus"] == fi and not state["help"] and not state.get("explain"))
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
            if state.get("explain"):
                status = "EXPLAIN sel[%d]: Up/Down/PgUp/PgDn/wheel scroll, q/Esc/i close | %s" % (
                    int(state.get("explain_idx", state["cand_idx"])), state["msg"] or "")
            elif state["editing"]:
                status = "EDIT %s: type, Backspace del, Left/Right cursor, Enter/Esc done | %s" % (
                    section, state["msg"] or "")
            else:
                status = "NAV: Tab/Up/Down move, Enter edit, [ ] switch, a/d/x add/dup/del, r reset, s sort:%s, m mouse:%s, v verb:%s, i explain, h help, q quit | %s" % (
                    sort_s2, mouse_s2, verb_s2, state["msg"] or "")
            stdscr.addnstr(rows - 1, 0, status[: cols - 1], cols - 1, curses.A_REVERSE)
        # Explain popup (modeless overlay, scrollable; rendered every frame).
        if state.get("explain") and rows > 10 and cols > 40 and n_cands():
            try:
                _ex_idx = int(state.get("explain_idx", state["cand_idx"]))
            except Exception:
                _ex_idx = int(state["cand_idx"])
            _ex_idx = max(0, min(_ex_idx, n_cands() - 1))
            try:
                _song_live = edits_to_song(state["song_base"], state["song_edits"])
                _res_live_list = [
                    edits_to_result(b, e)
                    for b, e in zip(state["cand_bases"], state["cand_edits"])
                ]
                _scored = score_pool_live(_song_live, _res_live_list)
                # Map pooled totals by identity/equality (same as _rescore).
                _ex_totals: List[Optional[float]] = []
                for _r in _res_live_list:
                    _v: Optional[float] = None
                    for _k, _s in _scored.items():
                        if _k is _r or _k == _r:
                            _v = float(_s)
                            break
                    _ex_totals.append(_v)
                _ex_total = _ex_totals[_ex_idx] if _ex_idx < len(_ex_totals) else None
                _ex_rank = rank_of.get(_ex_idx, 0)
                _ex_winner = (_ex_idx == winner)
                _ex_detail = build_explain_live(
                    _song_live, _res_live_list[_ex_idx], _ex_total, _scored)
                _ex_rows = explain_colored_lines(
                    _ex_detail, _ex_idx, _ex_rank, _ex_winner)
            except Exception:
                _ex_rows = [("explain unavailable (live helpers failed)", "fail")]
                _ex_detail = {}
            # Responsive: width=int(COLS*0.9), height=int(LINES*0.85)
            # content-clamped, centered; recomputed every frame (so wider
            # terminal => wider popup, KEY_RESIZE reflows on next frame).
            _ew_target = int(cols * 0.9)
            _ew = min(max(1, cols - 2), _ew_target)
            _ew = max(min(max(1, cols - 2), 40), _ew)
            _ew = min(max(1, cols - 2), _ew)
            _inner_w = max(1, _ew - 4)
            try:
                _ex_wrapped = _wrap_popup_rows(_ex_rows, _inner_w)
            except Exception:
                _ex_wrapped = list(_ex_rows)
            _eh, _ew, _etop, _eleft = _responsive_popup_geometry(
                rows, cols, len(_ex_wrapped),
                width_ratio=0.9, height_ratio=0.85,
                min_w=40, min_h=12,
            )
            # Re-wrap to final width (geometry clamps tiny screens).
            _inner_w = max(1, _ew - 4)
            try:
                _ex_wrapped = _wrap_popup_rows(_ex_rows, _inner_w)
                # Recompute height once wrapped width is final (content-clamped).
                _eh, _ew, _etop, _eleft = _responsive_popup_geometry(
                    rows, cols, len(_ex_wrapped),
                    width_ratio=0.9, height_ratio=0.85,
                    min_w=40, min_h=12,
                )
                _inner_w = max(1, _ew - 4)
            except Exception:
                pass
            try:
                _ewin = curses.newwin(_eh, _ew, _etop, _eleft)
            except Exception:
                _ewin = None
            if _ewin is not None:
                try:
                    _ewin.box()
                except Exception:
                    pass
                _cap = " i explain sel[%d] #%d%s (Up/Down/PgUp/PgDn/wheel scroll, q/Esc/i close) " % (
                    _ex_idx, _ex_rank, " ★" if _ex_winner else "")
                try:
                    _ewin.addnstr(0, 2, _cap[: max(0, _ew - 4)], _ew - 4,
                                   _ca(_attrs, "exhead", curses.A_BOLD))
                except Exception:
                    pass
                _inner_h = max(1, _eh - 3)
                try:
                    _max_ex = max(0, len(_ex_wrapped) - max(1, _inner_h))
                    _cur_ex = int(state.get("explain_scroll", 0) or 0)
                    _cur_ex = max(0, min(_max_ex, _cur_ex))
                    state["explain_scroll"] = _cur_ex
                except Exception:
                    _cur_ex, _max_ex = 0, 0
                    state["explain_scroll"] = 0
                for _li, (_txt, _kind) in enumerate(_ex_wrapped[_cur_ex: _cur_ex + _inner_h]):
                    try:
                        _ewin.addnstr(1 + _li, 2, _txt[: _inner_w], _inner_w,
                                       _explain_attr(_attrs, _kind, curses))
                    except Exception:
                        pass
                _foot = " line %d-%d/%d " % (
                    _cur_ex + 1, min(len(_ex_wrapped), _cur_ex + _inner_h), len(_ex_wrapped))
                try:
                    _ewin.addnstr(_eh - 1, max(0, _ew - len(_foot) - 2), _foot,
                                   len(_foot), curses.A_DIM)
                except Exception:
                    pass
                try:
                    # Base first, popup last: stdscr.refresh() after
                    # _ewin.refresh() would paint over the popup (the 'i'
                    # flicker: one frame visible, then erased before getch).
                    stdscr.noutrefresh()
                except Exception:
                    pass
                try:
                    _ewin.noutrefresh()
                except Exception:
                    pass
                try:
                    curses.doupdate()
                except Exception:
                    try:
                        stdscr.refresh()
                    except Exception:
                        pass
                    try:
                        _ewin.refresh()
                    except Exception:
                        pass
        # Help overlay (responsive: same 90% width rule as explain popup).
        if state["help"] and rows > 14 and cols > 50:
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
                "i explain popup: how total was scored",
                "  (Up/Down/PgUp/PgDn/wheel scroll,",
                "   q/Esc/i closes; clicks do nothing)",
                "mouse off: mousemask(0), wheel ignored;",
                "  mouse on: wheel scrolls score view,",
                "  popup open: wheel scrolls popup,",
                "  clicks do nothing, sel unchanged",
                "q / Ctrl-C quit",
                "",
                "Scores = live order_results;",
                "breakdown = live calc helpers;",
                "explain = live step-by-step walk;",
                "no network per keystroke.",
            ]
            # Width=int(COLS*0.9) first (for wrapping), then height from
            # wrapped content; centered; recomputed each open + KEY_RESIZE.
            _hw_target = int(cols * 0.9)
            _hw = min(max(1, cols - 2), _hw_target)
            _hw = max(min(max(1, cols - 2), 40), _hw)
            _hw = min(max(1, cols - 2), _hw)
            _hinner_w = max(1, _hw - 4)
            try:
                _hwrapped: List[str] = []
                import textwrap as _htw

                for _hline in help_lines:
                    if _hline == "":
                        _hwrapped.append("")
                        continue
                    _parts = _htw.wrap(
                        _hline, width=_hinner_w,
                        break_long_words=True, break_on_hyphens=False,
                    ) or [""]
                    _hwrapped.extend(_parts)
            except Exception:
                _hwrapped = list(help_lines)
            h, w, top, left = _responsive_popup_geometry(
                rows, cols, len(_hwrapped),
                width_ratio=0.9, height_ratio=0.85,
                min_w=40, min_h=12,
            )
            _hinner_w = max(1, w - 4)
            # Re-wrap once final width is known (tiny-screen clamp).
            try:
                import textwrap as _htw2

                _hwrapped2: List[str] = []
                for _hline in help_lines:
                    if _hline == "":
                        _hwrapped2.append("")
                        continue
                    _parts2 = _htw2.wrap(
                        _hline, width=_hinner_w,
                        break_long_words=True, break_on_hyphens=False,
                    ) or [""]
                    _hwrapped2.extend(_parts2)
                _hwrapped = _hwrapped2
                h, w, top, left = _responsive_popup_geometry(
                    rows, cols, len(_hwrapped),
                    width_ratio=0.9, height_ratio=0.85,
                    min_w=40, min_h=12,
                )
                _hinner_w = max(1, w - 4)
            except Exception:
                pass
            win = curses.newwin(h, w, top, left)
            try:
                win.bkgd(" ", _ca(_attrs, "help", curses.A_NORMAL))
            except Exception:
                pass
            win.box()
            for li, text in enumerate(_hwrapped[: h - 2]):
                try:
                    win.addnstr(li + 1, 2, text[: w - 4], w - 4, _ca(_attrs, "help", curses.A_NORMAL))
                except Exception:
                    pass
            # Base first, overlay last: win.refresh() before stdscr.refresh()
            # would erase the overlay (same flicker bug as the 'i' popup).
            try:
                stdscr.noutrefresh()
            except Exception:
                pass
            try:
                win.noutrefresh()
            except Exception:
                pass
            try:
                curses.doupdate()
            except Exception:
                try:
                    stdscr.refresh()
                except Exception:
                    pass
                try:
                    win.refresh()
                except Exception:
                    pass
            try:
                # Consume typeahead so the opening 'h' can't instantly close.
                curses.flushinp()
            except Exception:
                pass
            stdscr.getch()
            state["help"] = False
            continue
        if not state.get("explain"):
            # No popup: paint the base UI normally.
            stdscr.refresh()
        # When the explain popup is open the base + popup were already
        # flushed above via noutrefresh/doupdate (popup on top). Refreshing
        # stdscr here would paint over the popup again (the 'i' flicker).
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
        if key == 3:  # Ctrl-C always quits
            break
        if key == curses.KEY_RESIZE:
            continue
        # Explain popup modal: wheel/scroll + close only (clicks off).
        if state.get("explain") and not state.get("editing") and not state.get("help"):
            if key in (ord("q"), ord("Q"), 27, ord("i"), ord("I")):
                state["explain"] = False
                state["msg"] = "explain closed"
                continue
            if key in (curses.KEY_UP,):
                try:
                    state["explain_scroll"] = max(0, int(state.get("explain_scroll", 0) or 0) - 1)
                except Exception:
                    state["explain_scroll"] = 0
                continue
            if key in (curses.KEY_DOWN,):
                try:
                    state["explain_scroll"] = int(state.get("explain_scroll", 0) or 0) + 1
                except Exception:
                    state["explain_scroll"] = 0
                continue
            if key in (curses.KEY_PPAGE,):
                try:
                    state["explain_scroll"] = max(0, int(state.get("explain_scroll", 0) or 0) - 10)
                except Exception:
                    state["explain_scroll"] = 0
                continue
            if key in (curses.KEY_NPAGE,):
                try:
                    state["explain_scroll"] = int(state.get("explain_scroll", 0) or 0) + 10
                except Exception:
                    state["explain_scroll"] = 0
                continue
            if key in (curses.KEY_HOME,):
                state["explain_scroll"] = 0
                continue
            if key in (curses.KEY_END,):
                state["explain_scroll"] = 10 ** 9  # clamped on next frame
                continue
            if key == curses.KEY_MOUSE:
                if not state.get("mouse") or not _mouse_supported:
                    continue
                try:
                    _mid, _mx, _my, _mz, _bstate = curses.getmouse()
                except Exception:
                    continue
                try:
                    _b4 = getattr(curses, "BUTTON4_PRESSED", 0)
                    _b5 = getattr(curses, "BUTTON5_PRESSED", 0)
                    _su = bool(_bstate & _b4) if _b4 else False
                    _sd = bool(_bstate & _b5) if _b5 else False
                    if not _su and _bstate & 0x00080000:
                        _su = True
                    if not _sd and _bstate & 0x00100000:
                        _sd = True
                    if _su or _sd:
                        try:
                            _ce = int(state.get("explain_scroll", 0) or 0)
                        except Exception:
                            _ce = 0
                        _ce += -int(_WHEEL_STEP) if _su else int(_WHEEL_STEP)
                        state["explain_scroll"] = max(0, _ce)
                        state["msg"] = "explain scrolled (sel unchanged)"
                    # Clicks ignored even in popup.
                except Exception:
                    pass
                continue
            # 'm' still toggles mouse while popup is open; rest ignored.
            if key in (ord("m"), ord("M")):
                if _mouse_supported:
                    state["mouse"] = not bool(state.get("mouse", False))
                    _mouse_apply(bool(state["mouse"]))
                    state["msg"] = "mouse %s" % ("on" if state["mouse"] else "off")
                else:
                    state["msg"] = "mouse unsupported"
                continue
            continue
        # Global quit.
        if key in (ord("q"), ord("Q")) and not state["editing"]:
            break
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
            state["explain_scroll"] = 0
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
            if key in (ord("i"), ord("I")):
                if n_cands():
                    state["explain"] = True
                    state["explain_idx"] = int(state["cand_idx"])
                    state["explain_scroll"] = 0
                    state["msg"] = "explain sel[%d] (Up/Down/PgUp/PgDn/wheel scroll, q/Esc/i close)" % int(
                        state["cand_idx"])
                    try:
                        # Drop typeahead (key repeat / pasted 'ii' / leftover
                        # Esc-sequence bytes) so a buffered 'i' can't close
                        # the popup on the very next frame. Closing needs a
                        # second distinct press. Blocking getch already set
                        # (nodelay(False) at startup); keep it blocking here.
                        curses.flushinp()
                    except Exception:
                        pass
                    try:
                        stdscr.nodelay(False)
                    except Exception:
                        pass
                else:
                    state["msg"] = "no candidates to explain"
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
        "--explain",
        nargs="?",
        const=0,
        default=None,
        type=int,
        metavar="IDX",
        help="plain-text twin of the 'i' popup: how total for candidate IDX was calculated (default 0; pairs with --no-tui/--stub, forces plain output)",
    )
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

    # --explain [idx]: plain-text twin of the 'i' popup (forces plain, no curses).
    if args.explain is not None:
        try:
            ex_idx = int(args.explain)
        except (TypeError, ValueError):
            print("score_tuner: --explain needs an integer index.", file=sys.stderr)
            return EXIT_USAGE
        if ex_idx < 0 or ex_idx >= len(cand_bases):
            print("score_tuner: --explain IDX %d out of range [0..%d]." % (
                ex_idx, len(cand_bases) - 1), file=sys.stderr)
            return EXIT_USAGE
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
        try:
            song_live = edits_to_song(song_base, song_edits)
            results_live = [edits_to_result(b, e) for b, e in zip(cand_bases, cand_edits)]
            scored = score_pool_live(song_live, results_live)
            totals: List[Optional[float]] = []
            for _r in results_live:
                _v: Optional[float] = None
                for _k, _s in scored.items():
                    if _k is _r or _k == _r:
                        _v = float(_s)
                        break
                totals.append(_v)
            rank_of = _rank_map(totals)
            winner = -1
            best = -1.0
            for _i, _v in enumerate(totals):
                if _v is not None and _v > best:
                    best, winner = _v, _i
            try:
                print(format_explain_plain(
                    song_live, results_live[ex_idx], totals[ex_idx], scored,
                    ex_idx, rank_of.get(ex_idx, 0), ex_idx == winner))
            except BrokenPipeError:
                # `... | head` closed the pipe early; not a tuner failure.
                try:
                    sys.stdout.flush()
                except Exception:
                    pass
                return EXIT_OK
        except TunerError as exc:
            print("score_tuner: %s" % exc, file=sys.stderr)
            return exc.exit_code
        return EXIT_OK
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

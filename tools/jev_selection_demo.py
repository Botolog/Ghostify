"""Compare the original YouTube pick, the app-download pick and the JEV pick.

    python tools/jev_selection_demo.py <spotify-track-url>
    python tools/jev_selection_demo.py <spotify-playlist-url> [--max N] [--summary-only]

    python tools/jev_selection_demo.py https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b
    python tools/jev_selection_demo.py https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M --max 5
    python tools/jev_selection_demo.py "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M" --summary-only
    python tools/jev_selection_demo.py https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M --download --output-dir ./out

All three decisions come from the live app code, never from a toy
re-implementation:

* **original** — ``ghostify_dl._first_result_video_id``: the first provider
  result that carries a video id, i.e. the historical decision. This is what
  ``ghostify_dl._select_yt_id`` returns whenever JEV declines to answer.
* **app-download** — ``ghostify_dl.preview_app_selection``: the exact URL
  choice ``TrackDownloader.download`` would make on device. The Song is
  rebuilt from ``meta`` the same way the app does (``build_app_meta`` mirrors
  Kotlin ``buildMetaPayload``, then the shared ``_build_song_from_meta`` used
  by both the download path and the preview), and the already-fetched
  candidates are scored with the live spotdl scoring (``order_results`` +
  ``get_best_result``) — dry-run only, so nothing is downloaded and no
  sidecar or file is written. A future app change to the Song build, the
  providers or the scoring propagates here automatically because nothing is
  copied.
* **JEV** — ``ghostify_dl.compare_yt_selection``, which drives the live
  ``jev_selector`` shortlist + OpenRouter Decisions API call and reports both
  outcomes side by side.

Metadata comes from the project's own spotdl path (``Song.from_url`` with the
shared ``SpotifyClient``), candidates from ``ghostify_dl.search_yt_candidates``
(YouTube Music first, plain YouTube as fallback).

Safety: compare-only by default — nothing is downloaded unless ``--download``
is passed, and no OpenRouter request is made unless ``OPENROUTER_API_KEY`` is
present. ``--offline`` forces the deterministic fallback. The API key is never
printed, and neither are auth headers.

``-v`` / ``--verbose`` dumps the JEV request itself just before it goes out —
method, endpoint, redacted headers and the complete JSON body — as a block of
its own ahead of the report, and turns on the ``ghostify_dl`` / jev debug logs.
The dump is produced by a transport wrapper around the very sender the app
would use, so it can only ever *show* the request, never change it. The key is
redacted (``Authorization: Bearer <redacted>``) and a run without a key says so
instead of showing a request.

The same ``-v`` output closes with a second block whenever the live answer
carried per-option data: every shortlisted candidate, the confidence the model
gave it as a percentage, highest first, with the chosen option marked. The
numbers are read out of the very response the wrapped sender returned — nothing
is re-requested and no second decision is made — and the table is rebuilt from
the same shortlist and the same results the request was built from. A response
without per-option data, or one whose values are missing or malformed, degrades
to no block at all or to a plain ``n/a`` plus a note, never to a failure. No
response body is printed: only shortlisted video ids, the reported numbers and
the candidate titles that were already part of the request.

Output: ANSI colour is used only when stdout is a terminal and ``NO_COLOR`` is
unset; ``--no-color`` turns it off explicitly. Escapes always wrap a whole
label, heading or value, never sit inside one, so a URL stays copy-pasteable and
piped output stays byte-identical to the plain report.

Playlist mode: pass a playlist URL (``open.spotify.com/playlist/<id>`` or
``spotify:playlist:<id>``) as the positional arg; the type is auto-detected via
the live ``ghostify_dl.extract_spotify_id`` / ``ghostify_dl._extract_playlist_id``
(no Spotify logic is duplicated here). Tracks are resolved via the live
``ghostify_dl.fetch_playlist`` (``resolve_yt=False``), then each song runs the
exact same per-song flow as single mode (``load_song`` / ``search_yt_candidates``
/ ``collect_report`` with the same ``--limit`` / ``--timeout`` / ``--offline`` /
``-v``), one by one sequentially. Per-song output keeps the detailed 3-column
report with a header like ``=== [i/N] title - artists ===``; ``--summary-only``
prints only the total stats. ``--max`` / ``--limit-tracks N`` caps the run to the
first N tracks for quick runs. With ``--download`` each effective pick is fetched
into ``--output-dir`` (or skipped with a message when there is no pick).

Playlist summary (stdlib-only ASCII tables, same style as the report; colour only
when TTY and ``strip_ansi(color) == plain``): N total, succeeded/failed, agree vs
disagree for original-vs-app / original-vs-JEV / app-vs-JEV, no-candidates /
provider / metadata / skipped counts, provider breakdown (youtube-music vs
youtube), JEV outcome breakdown (chose/fallback/inactive/error), and the lists of
mismatched tracks (spotify_id + title + the 3 video ids).

Exit codes: 0 ok, 2 bad usage/URL, 3 missing dependency, 4 Spotify metadata
failure, 5 YouTube provider failure, 6 no candidates. Single-track mode returns
the per-run code directly. Playlist mode returns 0 when at least one track
succeeded; only when all tracks failed it returns a failure code (5 if any
provider error, else 6 if any no-candidates, else 4); a playlist-fetch failure
itself is 4, an empty playlist is 6. ``--download`` errors are reported per song
but do not change the playlist comparison exit code.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import sys
from contextlib import contextmanager
from pathlib import Path
from typing import (
    Any,
    Callable,
    Dict,
    FrozenSet,
    Iterator,
    List,
    Optional,
    Sequence,
    Tuple,
)

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

from ghostify_consts import JEV_DEMO_DEFAULT_LIMIT as _DEMO_LIMIT
from ghostify_consts import DEFAULT_PER_TRACK_TIMEOUT as _DEMO_TIMEOUT

EXIT_OK = 0
EXIT_USAGE = 2
EXIT_DEPENDENCY = 3
EXIT_METADATA = 4
EXIT_PROVIDER = 5
EXIT_NO_CANDIDATES = 6

DEFAULT_LIMIT = _DEMO_LIMIT
DEFAULT_TIMEOUT = _DEMO_TIMEOUT
COLUMN_WIDTH = 36
MAX_FIELD_WIDTH = 100
OPTION_LABEL_WIDTH = 44
# New fixed-width table layout (stdlib only, no new dependencies).
# Kept small enough that every rendered line stays <= 120 chars (see tests).
DECISION_STUB_WIDTH = 12
DECISION_COL_WIDTH = 28
KV_KEY_WIDTH = 14
KV_VALUE_WIDTH = 60
PANEL_WIDTH = 72

RESET = "\033[0m"
STYLES = {
    "heading": "\033[1;36m",
    "rule": "\033[90m",
    "label": "\033[90m",
    "original": "\033[34m",
    "app": "\033[32m",
    "jev": "\033[35m",
    "success": "\033[32m",
    "fallback": "\033[33m",
    "error": "\033[31m",
    "url": "\033[4;36m",
}
ANSI_PATTERN = re.compile(r"\033\[[0-9;]*m")

OUTCOME_CHOSE = "chose"
ERROR_OUTCOMES = frozenset(
    {"request_failed", "selector_failed", "selector_unavailable"}
)

HTTP_METHOD = "POST"
VERBOSE_RULE = "-" * 72
VERBOSE_REQUEST_HEADING = "Verbose - JEV request (about to be sent)"
VERBOSE_SKIPPED_HEADING = "Verbose - no JEV request was sent"
VERBOSE_OPTIONS_HEADING = "Verbose - JEV option confidence (live answer)"
REDACTED = "<redacted>"
NOT_AVAILABLE = "n/a"
CHOSEN_MARKER = "*"
DEFAULT_QUESTION_ID = "recording"

NO_REQUEST_OUTCOMES = frozenset(
    {
        "no_candidates",
        "unambiguous",
        "busy",
        "inactive",
        "selector_unavailable",
        "selector_failed",
    }
)

NO_REQUEST_TEXT = {
    "no_candidates": "no candidate carried a video id; nothing to ask about",
    "unambiguous": "only one candidate shortlisted; nothing to decide",
    "busy": "the concurrency gate was busy; the request was skipped",
    "inactive": "JEV is inactive; no request was sent",
    "selector_unavailable": "jev_selector is unavailable; no request was built",
    "selector_failed": "the selector failed before the request was built",
}

SENSITIVE_HEADER_FRAGMENTS = (
    "auth",
    "api-key",
    "api_key",
    "apikey",
    "cookie",
    "secret",
    "token",
)

SECRET_VALUE_PATTERNS = (
    re.compile(r"(?i)\b(bearer\s+)[^\s,;\"'}\]]+"),
    re.compile(r"\bsk-[A-Za-z0-9._-]{6,}"),
    re.compile(r"\bor-v1-[A-Za-z0-9._-]{6,}"),
)


class Palette:
    """Tiny ANSI palette; a disabled instance returns every text unchanged.

    Escapes wrap a whole piece of text, so stripping them (see
    :func:`strip_ansi`) gives back exactly the plain report. That is the whole
    contract: colour is presentation only and never reaches a url, a video id
    or any other machine-readable value.
    """

    def __init__(self, enabled: bool = False) -> None:
        self.enabled = bool(enabled)

    def paint(self, style: str, text: Any) -> str:
        rendered = "" if text is None else str(text)
        if not self.enabled or not rendered:
            return rendered
        code = STYLES.get(style)
        if not code:
            return rendered
        return code + rendered + RESET


PLAIN = Palette(False)


def strip_ansi(text: str) -> str:
    """*text* without any ANSI escape."""
    return ANSI_PATTERN.sub("", text)


def use_color(no_color: bool = False, stream: Any = None) -> bool:
    """True when ANSI colour may be written to *stream* (default stdout).

    Honours ``--no-color``, the `NO_COLOR convention
    <https://no-color.org/>`_ (set and non-empty) and a ``TERM=dumb`` terminal.
    Anything that is not a TTY - a file, a pipe, a CI log - stays plain.
    """
    if no_color:
        return False
    if os.environ.get("NO_COLOR", "") != "":
        return False
    if os.environ.get("TERM", "") == "dumb":
        return False
    target = sys.stdout if stream is None else stream
    try:
        return bool(target.isatty())
    except Exception:  # noqa: BLE001 - an odd stream is simply not colourable
        return False


def outcome_style(report: Dict[str, Any]) -> str:
    """Palette style for *report*'s JEV status: success, fallback or error.

    Mirrors the outcome labels of ``jev_selector`` without importing it, so the
    tool stays importable without the app's optional dependencies.
    """
    if report.get("selector_error"):
        return "error"
    outcome = str(report.get("outcome") or "")
    if outcome in ERROR_OUTCOMES:
        return "error"
    if outcome == OUTCOME_CHOSE and report.get("jev_video_id"):
        return "success"
    return "fallback"


class DemoError(Exception):
    """A demo failure with an exit code and a human-readable message."""

    def __init__(self, message: str, exit_code: int = EXIT_USAGE) -> None:
        super().__init__(message)
        self.exit_code = exit_code


def import_ghostify_dl() -> Any:
    """Import the live bridge module, or fail with an actionable message."""
    try:
        import ghostify_dl  # noqa: WPS433 (deliberate lazy import)
    except ImportError as exc:
        raise DemoError(
            "ghostify_dl is not importable (%s).\n"
            "Run the demo from a checkout of the app, or set PYTHONPATH to "
            "%s." % (type(exc).__name__, APP_PYTHON_DIR),
            EXIT_DEPENDENCY,
        ) from exc
    return ghostify_dl


def normalize_spotify_track_url(raw: str) -> Tuple[str, str]:
    """Validate *raw* and return ``(spotify_id, canonical_url)``."""
    if not isinstance(raw, str) or not raw.strip():
        raise DemoError("Expected a Spotify track URL, got an empty value.")
    ghostify_dl = import_ghostify_dl()
    candidate = raw.strip()
    spotify_id = ghostify_dl.extract_spotify_id(candidate)
    if not spotify_id:
        raise DemoError(
            "Not a Spotify track URL: %r\nExpected something like "
            "https://open.spotify.com/track/<22-char id> (a playlist URL will "
            "not work — pass a single track)." % candidate
        )
    return spotify_id, "https://open.spotify.com/track/%s" % spotify_id


def normalize_spotify_track_url(raw: str) -> Tuple[str, str]:
    """Validate *raw* and return ``(spotify_id, canonical_url)``."""
    if not isinstance(raw, str) or not raw.strip():
        raise DemoError("Expected a Spotify track URL, got an empty value.")
    ghostify_dl = import_ghostify_dl()
    candidate = raw.strip()
    spotify_id = ghostify_dl.extract_spotify_id(candidate)
    if not spotify_id:
        raise DemoError(
            "Not a Spotify track URL: %r\nExpected something like "
            "https://open.spotify.com/track/<22-char id> (a playlist URL will "
            "not work — pass a single track)." % candidate
        )
    return spotify_id, "https://open.spotify.com/track/%s" % spotify_id


def normalize_spotify_playlist_url(raw: str) -> Tuple[str, str]:
    """Validate *raw* as a playlist and return ``(playlist_id, canonical_url)``.

    Uses the live ``ghostify_dl._extract_playlist_id`` so URL/URI shapes stay in
    the app code, never in a copy. Accepts ``open.spotify.com/playlist/<id>``,
    ``spotify:playlist:<id>`` and (as the live code does) a bare playlist id.
    """
    if not isinstance(raw, str) or not raw.strip():
        raise DemoError("Expected a Spotify playlist URL, got an empty value.")
    ghostify_dl = import_ghostify_dl()
    candidate = raw.strip()
    try:
        playlist_id = ghostify_dl._extract_playlist_id(candidate)
    except Exception as exc:  # noqa: BLE001 - live GhostifyError -> usage
        raise DemoError(
            "Not a Spotify playlist URL: %r\nExpected something like "
            "https://open.spotify.com/playlist/<id> (or spotify:playlist:<id>)."
            % candidate
        ) from exc
    return playlist_id, "https://open.spotify.com/playlist/%s" % playlist_id


def normalize_spotify_input(raw: str) -> Tuple[str, str, str]:
    """Auto-detect track vs playlist, return ``(kind, spotify_id, canonical_url)``.

    ``kind`` is ``"track"`` or ``"playlist"``. Track detection uses the live
    ``ghostify_dl.extract_spotify_id``; playlist detection uses the live
    ``ghostify_dl._extract_playlist_id``. No Spotify URL logic is duplicated
    here.
    """
    if not isinstance(raw, str) or not raw.strip():
        raise DemoError("Expected a Spotify track or playlist URL, got an empty value.")
    ghostify_dl = import_ghostify_dl()
    candidate = raw.strip()
    track_id = ghostify_dl.extract_spotify_id(candidate)
    if track_id:
        return "track", track_id, "https://open.spotify.com/track/%s" % track_id
    try:
        playlist_id = ghostify_dl._extract_playlist_id(candidate)
    except Exception as exc:  # noqa: BLE001 - neither track nor playlist
        raise DemoError(
            "Not a Spotify track or playlist URL: %r\nExpected "
            "https://open.spotify.com/track/<22-char id> or "
            "https://open.spotify.com/playlist/<id> "
            "(spotify:track:/spotify:playlist: URIs also work)." % candidate
        ) from exc
    return "playlist", playlist_id, "https://open.spotify.com/playlist/%s" % playlist_id


def load_playlist(ghostify_dl: Any, playlist_id: str) -> Dict[str, Any]:
    """Resolve *playlist_id* via the live ``ghostify_dl.fetch_playlist``.

    Calls with ``resolve_yt=False``: the demo runs its own per-song
    ``search_yt_candidates`` (same as single mode), so the fetch only resolves
    Spotify metadata. Failures surface as :class:`DemoError` with
    ``EXIT_METADATA``.
    """
    fetch = getattr(ghostify_dl, "fetch_playlist", None)
    if not callable(fetch):
        raise DemoError(
            "ghostify_dl.fetch_playlist is unavailable.",
            EXIT_DEPENDENCY,
        )
    try:
        return fetch(playlist_id, {"resolve_yt": False})
    except DemoError:
        raise
    except Exception as exc:  # noqa: BLE001 - GhostifyError etc -> metadata
        raise DemoError(
            "Could not resolve Spotify playlist %s (%s: %s)."
            % (playlist_id, type(exc).__name__, exc),
            EXIT_METADATA,
        ) from exc


def jev_bucket(report: Dict[str, Any]) -> str:
    """JEV outcome bucket for playlist stats: chose/fallback/inactive/error."""
    outcome = str(report.get("outcome") or "")
    if outcome == OUTCOME_CHOSE and report.get("jev_video_id"):
        return "chose"
    if outcome == "inactive":
        return "inactive"
    if outcome in ERROR_OUTCOMES:
        return "error"
    return "fallback"


def summarize_playlist(
    reports: Sequence[Dict[str, Any]],
    failures: Sequence[Dict[str, Any]],
) -> Dict[str, Any]:
    """Aggregate per-song *reports*/*failures* into playlist totals.

    ``reports`` are successful :func:`collect_report` dicts; ``failures`` are
    ``{"spotify_id","title","artists","kind","reason"}`` dicts where ``kind`` is
    one of ``no_candidates`` / ``provider`` / ``metadata`` / ``skipped`` /
    ``error``. Agreement follows the single report semantics: original-vs-JEV
    ``agree`` includes the kept-deterministic case (no advice), app-vs-JEV is
    only comparable when both picks exist (otherwise ``n/a``).
    """
    total = len(list(reports or [])) + len(list(failures or []))
    succeeded = len(list(reports or []))
    failed = len(list(failures or []))
    no_candidates = 0
    provider_errors = 0
    metadata_errors = 0
    skipped = 0
    other_errors = 0
    for failure in failures or ():
        kind = str((failure or {}).get("kind") or "")
        if kind == "no_candidates":
            no_candidates += 1
        elif kind == "provider":
            provider_errors += 1
        elif kind == "metadata":
            metadata_errors += 1
        elif kind == "skipped":
            skipped += 1
        else:
            other_errors += 1
    orig_app: Dict[str, int] = {"agree": 0, "disagree": 0, "na": 0}
    orig_jev: Dict[str, int] = {"agree": 0, "disagree": 0, "na": 0}
    app_jev: Dict[str, int] = {"agree": 0, "disagree": 0, "na": 0}
    providers: Dict[str, int] = {}
    jev: Dict[str, int] = {"chose": 0, "fallback": 0, "inactive": 0, "error": 0}
    for report in reports or ():
        if not isinstance(report, dict):
            continue
        orig = report.get("original_video_id")
        app = report.get("app_video_id")
        jev_id = report.get("jev_video_id")
        if not orig or not app:
            orig_app["na"] += 1
        elif app == orig:
            orig_app["agree"] += 1
        else:
            orig_app["disagree"] += 1
        if not orig:
            orig_jev["na"] += 1
        elif report.get("changed"):
            orig_jev["disagree"] += 1
        elif report.get("agree"):
            orig_jev["agree"] += 1
        else:
            orig_jev["na"] += 1
        if not app or not jev_id:
            app_jev["na"] += 1
        elif app == jev_id:
            app_jev["agree"] += 1
        else:
            app_jev["disagree"] += 1
        provider = report.get("provider") or "unknown"
        providers[str(provider)] = providers.get(str(provider), 0) + 1
        bucket = jev_bucket(report)
        jev[bucket] = jev.get(bucket, 0) + 1
    return {
        "total": total,
        "succeeded": succeeded,
        "failed": failed,
        "no_candidates": no_candidates,
        "provider_errors": provider_errors,
        "metadata_errors": metadata_errors,
        "skipped": skipped,
        "other_errors": other_errors,
        "orig_app": orig_app,
        "orig_jev": orig_jev,
        "app_jev": app_jev,
        "providers": providers,
        "jev": jev,
    }


def format_playlist_header(
    index: int, total: int, title: Any, artists: Any, palette: Palette = PLAIN
) -> str:
    """Per-song ``=== [i/N] title - artists ===`` header (painted as heading)."""
    name = _clean_text(title) or "-"
    art = _clean_text(artists) or "-"
    middle = _truncate_plain("%s - %s" % (name, art), 100)
    plain = "=== [%d/%d] %s ===" % (int(index), int(total), middle)
    if not palette.enabled:
        return plain
    return palette.paint("heading", plain)


def _mismatch_rows(
    reports: Sequence[Dict[str, Any]], key: str
) -> List[List[Any]]:
    """Rows for one mismatch list: spotify_id / title / original / app / jev."""
    rows: List[List[Any]] = []
    for report in reports or ():
        if not isinstance(report, dict):
            continue
        orig = report.get("original_video_id")
        app = report.get("app_video_id")
        jev_id = report.get("jev_video_id")
        if key == "orig_app":
            if not orig or not app or app == orig:
                continue
        elif key == "orig_jev":
            if not report.get("changed"):
                continue
        elif key == "app_jev":
            if not app or not jev_id or app == jev_id:
                continue
        else:
            continue
        title = _clean_text(report.get("title")) or "-"
        artists = _clean_text(report.get("artists")) or ""
        label = title if not artists else "%s - %s" % (title, artists)
        rows.append(
            [
                report.get("spotify_id") or "-",
                label,
                orig or "-",
                app or "-",
                jev_id or "-",
            ]
        )
    return rows


def format_playlist_summary(
    summary: Dict[str, Any],
    reports: Sequence[Dict[str, Any]] = (),
    failures: Sequence[Dict[str, Any]] = (),
    playlist_meta: Optional[Dict[str, Any]] = None,
    palette: Palette = PLAIN,
) -> str:
    """Render playlist totals as stdlib-only ASCII tables (colour-safe).

    Plain mode is pure ASCII; coloured mode paints headings, labels, borders and
    statuses only, so ``strip_ansi(colored) == plain`` holds character for
    character. Every line stays <= 120 chars: values are truncated on a word
    boundary inside tables, full ids stay in the mismatch rows (11-char video
    ids, 22-char Spotify ids).
    """
    summary = dict(summary or {})
    reports = list(reports or [])
    paint = palette.paint
    lines: List[str] = []
    add = lines.append
    add(paint("heading", "Playlist summary"))
    add(paint("rule", "=" * 72))
    add("")
    if isinstance(playlist_meta, dict):
        name = _clean_text(playlist_meta.get("name"))
        owner = _clean_text(playlist_meta.get("owner"))
        if name:
            add(_field("playlist", name, palette))
        if owner:
            add(_field("owner", owner, palette))
        add(_field("tracks", summary.get("total", len(reports)), palette))
        add("")
    add(paint("heading", "Total"))
    total_items = [
        ("total", summary.get("total", 0)),
        ("succeeded", summary.get("succeeded", 0)),
        ("failed", summary.get("failed", 0)),
        ("no candidates", summary.get("no_candidates", 0)),
        ("provider errors", summary.get("provider_errors", 0)),
        ("metadata errors", summary.get("metadata_errors", 0)),
        ("skipped", summary.get("skipped", 0)),
    ]
    if summary.get("other_errors"):
        total_items.append(("other errors", summary.get("other_errors", 0)))
    lines.extend(_kv_table(total_items, palette))
    add("")
    add(paint("heading", "Agreement (agree vs disagree)"))
    agree_widths = [18, 10, 10, 10]
    lines.append(_table_border(agree_widths, palette))
    lines.append(
        _table_row(
            ["comparison", "agree", "disagree", "n/a"],
            agree_widths,
            palette,
            styles=["label", None, None, None],
        )
    )
    lines.append(_table_border(agree_widths, palette))
    for label, bucket in (
        ("original vs app", summary.get("orig_app", {})),
        ("original vs JEV", summary.get("orig_jev", {})),
        ("app vs JEV", summary.get("app_jev", {})),
    ):
        bucket = dict(bucket or {})
        lines.append(
            _table_row(
                [label, bucket.get("agree", 0), bucket.get("disagree", 0), bucket.get("na", 0)],
                agree_widths,
                palette,
                styles=["label", None, None, None],
            )
        )
    lines.append(_table_border(agree_widths, palette))
    add("")
    add(paint("heading", "Providers"))
    providers = dict(summary.get("providers", {}) or {})
    if not providers:
        providers = {"unknown": 0}
    lines.extend(
        _kv_table(
            [(key, providers[key]) for key in sorted(providers)],
            palette,
        )
    )
    add("")
    add(paint("heading", "JEV outcomes"))
    jev = dict(summary.get("jev", {}) or {})
    lines.extend(
        _kv_table(
            [
                ("chose", jev.get("chose", 0)),
                ("fallback", jev.get("fallback", 0)),
                ("inactive", jev.get("inactive", 0)),
                ("error", jev.get("error", 0)),
            ],
            palette,
        )
    )
    add("")
    add(paint("heading", "Mismatches (spotify_id + title + the 3 video ids)"))
    mismatch_widths = [22, 28, 11, 11, 11]
    mismatch_header = ["spotify id", "title", "original", "app", "jev"]
    for label, key in (
        ("original vs app", "orig_app"),
        ("original vs JEV", "orig_jev"),
        ("app vs JEV", "app_jev"),
    ):
        add(paint("label", "  %s:" % label))
        rows = _mismatch_rows(reports, key)
        if not rows:
            add("  none")
            continue
        lines.append(_table_border(mismatch_widths, palette))
        lines.append(
            _table_row(mismatch_header, mismatch_widths, palette, styles=["label"] * 5)
        )
        lines.append(_table_border(mismatch_widths, palette))
        for row in rows:
            lines.append(_table_row(row, mismatch_widths, palette))
        lines.append(_table_border(mismatch_widths, palette))
    return "\n".join(lines)


def load_song(ghostify_dl: Any, url: str) -> Any:
    """Resolve *url* into a spotdl ``Song`` using the app's own client path."""
    try:
        from spotdl.types.song import Song
    except ImportError as exc:
        raise DemoError(
            "spotdl is not importable (%s); the live python-spotdl package is "
            "expected at %s." % (type(exc).__name__, VENDORED_DIRS[0]),
            EXIT_DEPENDENCY,
        ) from exc

    try:
        ghostify_dl._ensure_spotify_client()
        return Song.from_url(url)
    except Exception as exc:  # noqa: BLE001 - surfaced as a demo error
        raise DemoError(
            "Could not resolve Spotify metadata for %s (%s: %s).\n"
            "Check the URL and the network; Ghostify only reads public "
            "Spotify data."
            % (url, type(exc).__name__, exc),
            EXIT_METADATA,
        ) from exc


@contextmanager
def forced_deterministic() -> Iterator[None]:
    """Temporarily disable JEV via its own env switch (restores afterwards)."""
    name = "GHOSTIFY_JEV_ENABLED"
    previous = os.environ.get(name)
    os.environ[name] = "0"
    try:
        yield
    finally:
        if previous is None:
            os.environ.pop(name, None)
        else:
            os.environ[name] = previous


def format_duration(seconds: Optional[float]) -> str:
    """``215`` -> ``3:35``; unknown lengths render as ``-``."""
    if not seconds:
        return "-"
    total = int(round(float(seconds)))
    if total < 0:
        return "-"
    return "%d:%02d" % (total // 60, total % 60)


def _cell(value: Any, width: int = COLUMN_WIDTH) -> str:
    text = "-" if value is None or value == "" else str(value)
    if len(text) > width:
        text = text[: width - 1] + "…"
    return text.ljust(width)


def _field(
    label: str,
    value: Any,
    palette: Palette = PLAIN,
    value_style: Optional[str] = None,
    width: int = MAX_FIELD_WIDTH,
) -> str:
    text = "-" if value is None or value == "" else str(value)
    text = " ".join(text.split())
    if width > 0 and len(text) > width:
        text = text[: width - 1] + "…"
    if value_style:
        text = palette.paint(value_style, text)
    return "  %s: %s" % (palette.paint("label", "%-14s" % label), text)


def _row(
    label: str,
    original: Any,
    app: Any,
    jev: Any,
    palette: Palette = PLAIN,
    jev_style: Optional[str] = None,
    app_style: Optional[str] = None,
) -> str:
    jev_text = _cell(jev).rstrip()
    jev_cell = palette.paint(jev_style, jev_text) if jev_style else jev_text
    app_text = _cell(app).rstrip()
    app_cell = palette.paint(app_style, app_text) if app_style else app_text
    return (
        "  %s %s %s %s"
        % (
            palette.paint("label", "%-12s" % label),
            _cell(original),
            app_cell,
            jev_cell,
        )
    ).rstrip()


def _confidence_cell(value: Any) -> str:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return "n/a"
    return "%.2f" % value


def _clean_text(value: Any) -> str:
    """*value* as one whitespace-normalised line, or ``""`` when there is none."""
    if value is None:
        return ""
    return " ".join(str(value).split())


def _probability(value: Any) -> Optional[float]:
    """*value* as a probability, or ``None`` when it is not one.

    Mirrors ``jev_selector._to_float`` so the display can never disagree with
    the validator that accepted the answer: a boolean, a NaN, a non-numeric
    string or a missing value is a *missing* probability, never a quiet ``0``.
    """
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number != number:
        return None
    return number


def _percent(value: Optional[float]) -> str:
    """A probability as a percentage; a missing one reads :data:`NOT_AVAILABLE`."""
    if value is None:
        return NOT_AVAILABLE
    return "%.1f%%" % (float(value) * 100.0)


def selector_question_id() -> str:
    """The id the live selector asks its choice question under.

    Read from ``jev_selector`` when it is importable so the verbose output
    follows the selector rather than a copy of its constant, and falls back to
    the value it currently uses.
    """
    try:
        import jev_selector
    except ImportError:
        return DEFAULT_QUESTION_ID
    return (
        _clean_text(getattr(jev_selector, "QUESTION_ID", DEFAULT_QUESTION_ID))
        or DEFAULT_QUESTION_ID
    )


def option_confidences(
    response: Any, question_id: Optional[str] = None
) -> Optional[Dict[str, Any]]:
    """The per-option confidences a live JEV *response* carried, or ``None``.

    ``None`` means the response held no per-option data at all, so a caller can
    stay silent instead of printing an empty table. ``probabilities`` is kept
    exactly as it arrived - malformed values included, so the renderer can show
    them as :data:`NOT_AVAILABLE` instead of hiding them - and nothing else from
    the body is kept: no text, no metadata, no credentials.
    """
    if not isinstance(response, dict):
        return None
    answers = response.get("answers")
    if not isinstance(answers, dict):
        return None
    answer = answers.get(question_id or selector_question_id())
    if not isinstance(answer, dict):
        return None
    probabilities = answer.get("probabilities")
    if not isinstance(probabilities, dict) or not probabilities:
        return None
    return {
        "probabilities": probabilities,
        "choice": _clean_text(answer.get("choice")) or None,
        "confidence": _probability(answer.get("confidence")),
    }


def _header_row(palette: Palette) -> str:
    return (
        "  %-12s %s %s %s"
        % (
            "",
            palette.paint("original", _cell("original (deterministic)")),
            palette.paint("app", _cell("app-download (spotdl)")),
            palette.paint("jev", "JEV / OpenRouter"),
        )
    ).rstrip()


def _truncate_plain(value: Any, width: int) -> str:
    """One-line *value* cut to *width* with an ellipsis, never padded."""
    text = "-" if value is None or value == "" else " ".join(str(value).split())
    if width > 0 and len(text) > width:
        text = text[: width - 1] + "…"
    return text


def _pad_plain(value: Any, width: int) -> str:
    """*value* truncated to *width* and padded with spaces (plain, no ANSI)."""
    return _truncate_plain(value, width).ljust(width)


def _paint_padded(
    palette: Palette, style: Optional[str], value: Any, width: int
) -> str:
    """Paint *value* then pad with plain spaces so strip_ansi stays identical.

    The ANSI codes wrap only the truncated text; the padding is plain. That
    keeps ``strip_ansi(colored) == plain`` character for character while the
    columns still line up, and a URL/value is never split by an escape. In
    plain mode this is just :func:`_pad_plain`.
    """
    truncated = _truncate_plain(value, width)
    if not style or not palette.enabled:
        return truncated.ljust(width)
    padding = " " * max(0, width - len(truncated))
    return palette.paint(style, truncated) + padding


def _table_border(widths: Sequence[int], palette: Palette = PLAIN) -> str:
    """An ASCII border like ``+---+`` for columns *widths* (indent + paint)."""
    plain = "  +" + "+".join("-" * (width + 2) for width in widths) + "+"
    if not palette.enabled:
        return plain
    return palette.paint("rule", plain)


def _table_row(
    cells: Sequence[Any],
    widths: Sequence[int],
    palette: Palette = PLAIN,
    styles: Optional[Sequence[Optional[str]]] = None,
) -> str:
    """One ASCII table row with ``|`` separators, padded and optionally painted.

    Every cell is truncated then padded to its width before any painting (see
    :func:`_paint_padded`), so the plain and coloured renders differ only by
    ANSI escapes. Plain mode stays clean ASCII without any escapes.
    """
    parts: List[str] = []
    for index, width in enumerate(widths):
        cell = cells[index] if index < len(cells) else ""
        style = styles[index] if styles and index < len(styles) else None
        parts.append(_paint_padded(palette, style, cell, width))
    return "  | " + " | ".join(parts) + " |"


def _panel_rule(palette: Palette = PLAIN, width: int = PANEL_WIDTH) -> str:
    """Top/bottom rule framing a panel of :func:`_field` lines (ASCII only)."""
    plain = "  +" + "-" * width + "+"
    if not palette.enabled:
        return plain
    return palette.paint("rule", plain)


def _kv_table(
    items: Sequence[Tuple[str, Any]],
    palette: Palette = PLAIN,
    key_width: int = KV_KEY_WIDTH,
    value_width: int = KV_VALUE_WIDTH,
) -> List[str]:
    """A two-column key-value table (keys painted as labels, values plain).

    Used for the Candidates section, which carries no URLs, so every value may
    sit inside a bordered table without harming copy-paste. Plain mode is pure
    ASCII; coloured mode paints keys and borders only.
    """
    widths = [key_width, value_width]
    lines = [_table_border(widths, palette)]
    for key, value in items:
        lines.append(
            _table_row(
                [key, value],
                widths,
                palette,
                styles=["label", None],
            )
        )
    lines.append(_table_border(widths, palette))
    return lines


def _decision_table(
    report: Dict[str, Any], palette: Palette = PLAIN
) -> Tuple[List[str], bool]:
    """The true 3-column Decision table plus whether an effective pick is marked.

    Columns are original / app-download / JEV with headers
    ``original (deterministic)`` / ``app-download (spotdl)`` /
    ``JEV / OpenRouter``. Rows are video id, rule, model, confidence, url and
    reason (truncated previews) plus a ``same as orig`` agreement row. The
    column whose video id equals the effective pick carries a ``*`` and the
    success style in both plain (``*``) and colour (green ``*``); stripping the
    colour gives the plain table back exactly. Full URLs/reasons stay below the
    table as copy-pasteable :func:`_field` lines, so nothing is dropped.
    """
    stub = DECISION_STUB_WIDTH
    col = DECISION_COL_WIDTH
    widths = [stub, col, col, col]

    original_id = report.get("original_video_id")
    app_id = report.get("app_video_id")
    jev_id = report.get("jev_video_id")
    effective_id = report.get("effective_video_id")

    def _vid_marked(value: Any) -> Tuple[str, Optional[str]]:
        text = "-" if value is None or value == "" else str(value)
        if (
            effective_id
            and str(value or "") == str(effective_id)
            and text != "-"
        ):
            return "%s *" % text, "success"
        return text, None

    original_cell, original_style = _vid_marked(original_id)
    app_cell, app_style = _vid_marked(app_id)
    jev_cell, jev_style = _vid_marked(jev_id)
    marked = bool(original_style or app_style or jev_style)

    if report.get("original_video_id") is None:
        app_same: Any = "n/a"
        jev_same: Any = "n/a"
        app_same_style: Optional[str] = "fallback"
        jev_same_style: Optional[str] = "fallback"
    else:
        if app_id is None:
            app_same, app_same_style = "n/a", "fallback"
        elif app_id == original_id:
            app_same, app_same_style = "yes", "success"
        else:
            app_same, app_same_style = "NO", "success"
        if jev_id is None:
            # No advice keeps the deterministic pick: not a match, not a miss.
            jev_same, jev_same_style = "n/a (kept)", "fallback"
        elif jev_id == original_id:
            jev_same, jev_same_style = "yes", "success"
        else:
            jev_same, jev_same_style = "NO", "success"

    header = ["field", "original (deterministic)", "app-download (spotdl)", "JEV / OpenRouter"]
    header_styles: List[Optional[str]] = ["label", "original", "app", "jev"]
    rule_text = (
        "shortlist choice"
        if report.get("jev_video_id")
        else "no advice (deterministic kept)"
    )

    rows: List[Tuple[List[Any], List[Optional[str]]]] = [
        (
            ["video id", original_cell, app_cell, jev_cell],
            ["label", original_style, app_style, jev_style],
        ),
        (
            [
                "rule",
                "first result with a video id",
                "spotdl search best match",
                rule_text,
            ],
            [
                "label",
                None,
                "success" if report.get("app_video_id") else "fallback",
                "success" if report.get("jev_video_id") else "fallback",
            ],
        ),
        (
            [
                "model",
                "n/a",
                "n/a (spotdl scoring)",
                report.get("model") if report.get("jev_enabled") else "not consulted",
            ],
            ["label", None, None, None],
        ),
        (
            [
                "confidence",
                "n/a",
                "n/a",
                _confidence_cell(report.get("confidence")),
            ],
            ["label", None, None, None],
        ),
        (
            [
                "url",
                report.get("original_url") or "-",
                report.get("app_url") or "n/a (no app match)",
                report.get("jev_url") or "n/a (no advice)",
            ],
            ["label", "url" if report.get("original_url") else None,
             "url" if report.get("app_url") else "fallback",
             "url" if report.get("jev_url") else "fallback"],
        ),
        (
            [
                "reason",
                "deterministic first pick",
                report.get("app_reason") or "-",
                report.get("reason") or "-",
            ],
            ["label", None, None, None],
        ),
        (
            ["same as orig", "-", app_same, jev_same],
            ["label", None, app_same_style, jev_same_style],
        ),
    ]

    lines = [_table_border(widths, palette)]
    lines.append(_table_row(header, widths, palette, styles=header_styles))
    lines.append(_table_border(widths, palette))
    for cells, styles in rows:
        # Keep long reasons readable on a word boundary inside the table; the
        # full text stays in the JEV reason field below.
        if cells[0] == "reason":
            cells = [
                cells[0],
                _summarize(cells[1], col),
                _summarize(cells[2], col),
                _summarize(cells[3], col),
            ]
        lines.append(_table_row(cells, widths, palette, styles=styles))
    lines.append(_table_border(widths, palette))
    return lines, marked


def _summarize(text: Any, limit: int) -> str:
    """One-line *text* trimmed to *limit* characters on a word boundary."""
    collapsed = " ".join(str(text or "").split())
    if len(collapsed) <= limit:
        return collapsed
    return "%s…" % collapsed[:limit].rsplit(" ", 1)[0]


def _status_lines(report: Dict[str, Any], palette: Palette) -> List[str]:
    """The trailing JEV status block: what happened, and what to do about it.

    With a key present the status carries the selector's own reason - which now
    names the rejection reason or the sanitised HTTP status - so
    ``enabled (OPENROUTER_API_KEY set, rejected)`` becomes something an operator
    can act on, while the effective fallback url above stays visible. A reason
    too long for one line is trimmed on a word boundary here; the full text is
    always on the ``JEV reason`` field just above.
    """
    style = outcome_style(report)
    if report.get("forced_offline"):
        return [
            _field(
                "JEV status",
                "forced offline (--offline); deterministic result kept",
                palette,
                "fallback",
            )
        ]
    if report.get("api_key_present"):
        prefix = (
            "enabled (OPENROUTER_API_KEY set)"
            if report.get("jev_enabled")
            else "configured but disabled (GHOSTIFY_JEV_ENABLED=0)"
        )
        detail = report.get("reason") or report.get("outcome") or "no answer"
        status = "%s - %s" % (
            prefix,
            _summarize(detail, MAX_FIELD_WIDTH - len(prefix) - 3),
        )
        return [_field("JEV status", status, palette, style)]
    return [
        _field(
            "JEV status", "inactive - OPENROUTER_API_KEY is not set", palette, "fallback"
        ),
        "  The JEV decision falls back to the deterministic result. To enable it:",
        "    export OPENROUTER_API_KEY=<your key>   # see .env.example",
        "    export GHOSTIFY_JEV_ENABLED=1           # optional, implied by the key",
    ]


def format_report(report: Dict[str, Any], color: bool = False) -> str:
    """Render the side-by-side comparison report.

    ``color=False`` (the default) is plain text, which is what a pipe, a file or
    a test sees. With ``color=True`` headings, labels, the three decision
    columns, the statuses and the urls are styled; :func:`strip_ansi` of the
    result is still the plain report, character for character.
    """
    palette = Palette(color)
    paint = palette.paint
    lines: List[str] = []
    add = lines.append
    status_style = outcome_style(report)

    add(paint("heading", "Ghostify - YouTube selection: original vs app vs JEV/OpenRouter"))
    add(paint("rule", "=" * 72))
    add("")

    # Song as a neat panel: a framed block of key-value lines. The inner lines
    # stay :func:`_field` rows (copy-pasteable, line-final URLs) while the
    # top/bottom ASCII rules give the panel shape. Plain mode is pure ASCII.
    add(paint("heading", "Song"))
    add(_panel_rule(palette))
    add(_field("title", report.get("title"), palette))
    add(_field("artists", report.get("artists"), palette))
    add(_field("album", report.get("album"), palette))
    add(_field("duration", format_duration(report.get("duration_s")), palette))
    add(_field("spotify id", report.get("spotify_id"), palette))
    add(_field("spotify url", report.get("spotify_url"), palette, "url"))
    add(_panel_rule(palette))
    add("")

    # Candidates as a key-value table (no URLs here, so side borders are safe).
    add(paint("heading", "Candidates"))
    shortlisted_text = "%s (%s)" % (
        report.get("shortlist_count") if report.get("shortlist_count") is not None else "-",
        "ambiguous" if report.get("ambiguous") else "unambiguous",
    )
    shortlist_ids_text = ", ".join(report.get("shortlist_ids") or []) or "-"
    lines.extend(
        _kv_table(
            [
                ("provider", report.get("provider")),
                ("search query", report.get("query")),
                ("returned", report.get("candidate_count")),
                ("shortlisted", shortlisted_text),
                ("shortlist ids", shortlist_ids_text),
            ],
            palette,
        )
    )
    add("")

    # Decision as a true 3-column table with headers original/app-download/JEV.
    add(paint("heading", "Decision"))
    decision_lines, marked = _decision_table(report, palette)
    lines.extend(decision_lines)
    if marked:
        # No word "effective" here on purpose: tests split on the verdict's
        # "effective" field, and the full word lives there with the video id.
        star = paint("success", "*") if palette.enabled else "*"
        add("  %s marks the pick --download would fetch" % star)
    add("")
    # Full copy-pasteable URLs/reasons stay below the truncated table preview,
    # so the table stays aligned while nothing is dropped.
    add(_field("original url", report.get("original_url"), palette, "url"))
    add(
        _field(
            "app url",
            report.get("app_url") or "n/a (no app match)",
            palette,
            "url" if report.get("app_url") else "fallback",
        )
    )
    add(_field("app reason", report.get("app_reason"), palette))
    add(
        _field(
            "JEV url",
            report.get("jev_url") or "n/a (no advice)",
            palette,
            "url" if report.get("jev_url") else "fallback",
        )
    )
    add(_field("JEV reason", report.get("reason"), palette, status_style))
    add("")

    if report.get("original_video_id") is None:
        agreement = "no original candidate - nothing to compare"
        agreement_style = "fallback"
    elif report.get("changed"):
        agreement = "NO - JEV overrides the original pick"
        agreement_style = "success"
    else:
        agreement = "yes - both decisions pick the same video"
        agreement_style = "success"
    add(_field("agree", agreement, palette, agreement_style))
    if report.get("app_video_id") is None:
        app_agreement = "n/a - app scoring found no match"
        app_agreement_style = "fallback"
    elif report.get("app_video_id") == report.get("original_video_id"):
        app_agreement = "yes - app-download picks the original video"
        app_agreement_style = "success"
    else:
        app_agreement = "NO - app-download overrides the original pick"
        app_agreement_style = "success"
    add(_field("app agree", app_agreement, palette, app_agreement_style))
    add(_field("outcome", report.get("outcome"), palette, status_style))
    add(
        _field(
            "effective",
            "%s  <- JEV decision (what --download fetches)"
            % (report.get("effective_video_id") or "none (the app would skip the track)"),
            palette,
            status_style,
        )
    )
    if report.get("effective_url"):
        add(_field("effective url", report.get("effective_url"), palette, "url"))
    add(
        _field(
            "app effective",
            "%s  <- what device download would use (spotdl search)"
            % (report.get("app_video_id") or "none (the app would skip the track)"),
            palette,
            "success" if report.get("app_video_id") else "fallback",
        )
    )
    add("")

    lines.extend(_status_lines(report, palette))
    return "\n".join(lines)


def is_sensitive_header(name: Any) -> bool:
    """True when a header *name* is one whose value must never be shown."""
    lowered = str(name).lower()
    return any(fragment in lowered for fragment in SENSITIVE_HEADER_FRAGMENTS)


def redact_secrets(text: Any) -> str:
    """*text* with every credential-shaped value replaced by :data:`REDACTED`.

    Defence in depth, applied to the whole verbose block once it is rendered:
    ``Bearer <token>`` keeps the scheme and loses the token, and a bare
    ``sk-``/``or-v1-`` key loses everything. Only key-shaped runs are touched, so
    candidate text, urls and video ids survive verbatim - which is the point of
    the dump.
    """

    def _swap(match: "re.Match") -> str:
        prefix = match.group(1) if match.lastindex else ""
        return prefix + REDACTED

    scrubbed = "" if text is None else str(text)
    for pattern in SECRET_VALUE_PATTERNS:
        scrubbed = pattern.sub(_swap, scrubbed)
    return scrubbed


def safe_headers(headers: Any) -> Dict[str, str]:
    """*headers* as text, with every sensitive value replaced by :data:`REDACTED`.

    The name and the auth scheme stay (``Authorization: Bearer <redacted>``) so
    the dump still shows that a credential *is* being sent, and nothing else
    about it.
    """
    try:
        items = dict(headers or {})
    except (TypeError, ValueError):
        return {}
    out: Dict[str, str] = {}
    for name, value in items.items():
        text = "" if value is None else str(value)
        if is_sensitive_header(name):
            scheme = text.split(" ", 1)[0] if " " in text else ""
            text = "%s %s" % (scheme, REDACTED) if scheme else REDACTED
        out[str(name)] = redact_secrets(text)
    return out


def format_request_body(payload: Any) -> List[str]:
    """*payload* pretty-printed as JSON lines, exactly as it will be sent."""
    try:
        text = json.dumps(payload, indent=2, ensure_ascii=False, default=str)
    except (TypeError, ValueError):
        text = "" if payload is None else str(payload)
    return text.splitlines() or ["(empty body)"]


def format_verbose_request(
    method: str,
    endpoint: Any,
    headers: Any,
    payload: Any,
    timeout: Optional[float] = None,
    palette: Palette = PLAIN,
) -> str:
    """Render the request that is about to be sent: method, url, headers, body.

    The body is the complete payload, not a summary - payload shape is exactly
    what this dump exists for. Nothing here is shortened, not even a long custom
    endpoint. Credentials are removed twice: per header in
    :func:`safe_headers`, and once more over the finished block in
    :func:`redact_secrets`. The block is closed by a rule and a blank line so
    the report that follows stays visually separate.
    """
    lines: List[str] = [
        palette.paint("heading", VERBOSE_REQUEST_HEADING),
        palette.paint("rule", VERBOSE_RULE),
        _field("method", method, palette, width=0),
        _field("endpoint", endpoint, palette, "url", width=0),
    ]
    if timeout:
        lines.append(_field("timeout", "%.1fs" % float(timeout), palette))
    lines.append(palette.paint("label", "  %-14s:" % "headers"))
    for name, value in safe_headers(headers).items():
        lines.append("    %s: %s" % (palette.paint("label", "%-15s" % name), value))
    lines.append(palette.paint("label", "  %-14s:" % "request body"))
    lines.extend("    %s" % line for line in format_request_body(payload))
    lines.append(palette.paint("rule", VERBOSE_RULE))
    lines.append("")
    return redact_secrets("\n".join(lines))


def no_request_state(report: Dict[str, Any]) -> str:
    """Why this run sent no JEV request, in one line an operator can act on."""
    outcome = str(report.get("outcome") or "")
    if report.get("forced_offline"):
        return "--offline forced the deterministic pick; no OpenRouter call"
    if outcome == "no_candidates":
        return NO_REQUEST_TEXT["no_candidates"]
    if not report.get("api_key_present"):
        return "OPENROUTER_API_KEY is not set; JEV is inactive"
    if not report.get("jev_enabled"):
        return "configured but disabled (GHOSTIFY_JEV_ENABLED=0)"
    if outcome in NO_REQUEST_OUTCOMES:
        return NO_REQUEST_TEXT.get(outcome, "no request was sent")
    return "outcome %s; no request was sent" % (outcome or "unknown")


def format_verbose_skipped(
    report: Dict[str, Any], palette: Palette = PLAIN
) -> str:
    """Render the ``-v`` block for a run that made no request at all.

    Without it ``-v`` would be silent exactly when something is wrong (no key,
    ``GHOSTIFY_JEV_ENABLED=0``, ``--offline``, an unambiguous shortlist), which
    is the state an operator is most likely debugging.
    """
    endpoint = report.get("endpoint")
    lines: List[str] = [
        palette.paint("heading", VERBOSE_SKIPPED_HEADING),
        palette.paint("rule", VERBOSE_RULE),
        _field("state", no_request_state(report), palette, "fallback"),
        _field("method", "%s (not sent)" % HTTP_METHOD, palette),
        _field("endpoint", endpoint, palette, "url" if endpoint else None, width=0),
        _field("model", report.get("model"), palette),
        _field("request body", "none - the provider was never called", palette),
        palette.paint("rule", VERBOSE_RULE),
        "  Re-run with OPENROUTER_API_KEY set to have -v dump the request.",
        "",
    ]
    return redact_secrets("\n".join(lines))


def _result_video_id(ghostify_dl: Any, result: Any) -> str:
    """The video id a provider *result* points at, or ``""``.

    ``result_id`` first, then the same url extraction the app's own
    deterministic first-result rule uses, so the ids line up with the
    shortlist's without re-deriving anything.
    """
    for name in ("result_id", "video_id", "videoId"):
        text = _clean_text(getattr(result, name, None))
        if text:
            return text
    return _clean_text(ghostify_dl._extract_video_id(getattr(result, "url", None)))


def _result_channel(result: Any) -> str:
    """The channel (or artist) a provider *result* carries, or ``""``."""
    for name in ("author", "channel", "channel_name", "uploader"):
        text = _clean_text(getattr(result, name, None))
        if text:
            return text
    artists = getattr(result, "artists", None)
    if isinstance(artists, str):
        return _clean_text(artists)
    if isinstance(artists, (list, tuple)) and artists:
        return _clean_text(artists[0])
    return ""


def shortlist_candidates(
    ghostify_dl: Any, results: Sequence[Any], shortlist_ids: Sequence[Any]
) -> List[Dict[str, str]]:
    """Title/channel per shortlisted video id, in the shortlist's own order.

    Read from the very results the shortlist was built from, and only for ids
    the shortlist holds, so a provider result that never reached JEV cannot show
    up in the verbose output. Purely a lookup: no search, no re-ranking, no
    second decision, and nothing the request did not already carry.
    """
    order: List[str] = []
    for raw in shortlist_ids or ():
        video_id = _clean_text(raw)
        if video_id and video_id not in order:
            order.append(video_id)
    if not order:
        return []
    wanted = set(order)
    found: Dict[str, Dict[str, str]] = {}
    for result in results or ():
        video_id = _result_video_id(ghostify_dl, result)
        if not video_id or video_id in found or video_id not in wanted:
            continue
        found[video_id] = {
            "video_id": video_id,
            "title": _clean_text(
                getattr(result, "name", None) or getattr(result, "title", None)
            ),
            "channel": _result_channel(result),
        }
    return [found[video_id] for video_id in order if video_id in found]


def _candidate_label(entry: Dict[str, Any]) -> str:
    title = _clean_text(entry.get("title"))
    channel = _clean_text(entry.get("channel"))
    if title and channel:
        return "%s - %s" % (title, channel)
    return title or channel


def _shortlist_entries(report: Dict[str, Any]) -> List[Tuple[str, str]]:
    """``(video_id, "title - channel")`` per shortlisted option, in order.

    The shortlist is the allow-list: an option only appears here when JEV was
    actually allowed to choose it, and its label is the candidate the request
    already named. Ids with no matching result keep an empty label, which the
    renderer reports rather than invents.
    """
    labels: Dict[str, str] = {}
    for entry in report.get("shortlist_candidates") or ():
        if isinstance(entry, dict):
            video_id = _clean_text(entry.get("video_id"))
            if video_id:
                labels[video_id] = _candidate_label(entry)
    entries: List[Tuple[str, str]] = []
    seen = set()
    for raw in report.get("shortlist_ids") or ():
        video_id = _clean_text(raw)
        if not video_id or video_id in seen:
            continue
        seen.add(video_id)
        entries.append((video_id, labels.get(video_id, "")))
    return entries


def _plural(count: int, singular: str, plural: Optional[str] = None) -> str:
    """``1 value`` / ``2 values`` - the suffix a count needs in a note."""
    return singular if count == 1 else (plural or "%ss" % singular)


def _option_notes(
    data: Dict[str, Any],
    stats: Dict[str, int],
    chosen: str,
    shortlist_ids: FrozenSet[str],
) -> List[str]:
    """The notes explaining what the table could not show as a number.

    One line per thing that did not come out clean, so an operator reading a
    table of ``n/a`` values can tell a genuinely missing probability from a
    malformed one, a probability for an option that was never on the shortlist,
    a value outside 0..1 or a candidate the shortlist carried no title for.
    """
    notes: List[str] = []
    if stats["unreadable"]:
        notes.append(
            "%d of %d options had no readable probability and read %s above"
            % (stats["unreadable"], stats["total"], NOT_AVAILABLE)
        )
    if stats["ignored"]:
        notes.append(
            "%d reported %s did not name a shortlisted option and %s ignored"
            % (
                stats["ignored"],
                _plural(stats["ignored"], "probability", "probabilities"),
                "was" if stats["ignored"] == 1 else "were",
            )
        )
    if stats["out_of_range"]:
        notes.append(
            "%d %s outside 0..1 %s shown exactly as reported"
            % (
                stats["out_of_range"],
                _plural(stats["out_of_range"], "value"),
                "is" if stats["out_of_range"] == 1 else "are",
            )
        )
    if stats["unlabelled"]:
        notes.append(
            "%d %s had no title or channel in the shortlist"
            % (
                stats["unlabelled"],
                _plural(stats["unlabelled"], "option"),
            )
        )
    if data["choice"] in shortlist_ids and not chosen:
        notes.append(
            "the answer named %s, which the selector did not accept" % data["choice"]
        )
    return notes


def format_verbose_options(
    response: Any,
    report: Dict[str, Any],
    palette: Palette = PLAIN,
    question_id: Optional[str] = None,
) -> str:
    """Render every shortlisted option with the confidence the model gave it.

    ``""`` when the live answer carried no per-option data, so ``-v`` stays as
    quiet as before for an answer without probabilities. The table shows the
    option's video id, its reported confidence as a percentage and the
    title/channel of the candidate that id belongs to, highest confidence
    first, with the option the app ended up using marked. Missing or malformed
    values read :data:`NOT_AVAILABLE` and are counted in a note rather than
    failing the run, and the response body itself is never printed.
    """
    data = option_confidences(response, question_id)
    if data is None:
        return ""
    entries = _shortlist_entries(report)
    if not entries:
        return ""
    probabilities = data["probabilities"]
    shortlist_ids = frozenset(video_id for video_id, _ in entries)
    chosen = _clean_text(report.get("jev_video_id"))
    if chosen not in shortlist_ids:
        chosen = ""

    rows: List[Dict[str, Any]] = []
    unreadable = 0
    out_of_range = 0
    unlabelled = 0
    for video_id, label in entries:
        value = _probability(probabilities.get(video_id))
        if value is None:
            unreadable += 1
        elif not 0.0 <= value <= 1.0:
            out_of_range += 1
        if not label:
            unlabelled += 1
        rows.append(
            {
                "video_id": video_id,
                "value": value,
                "label": _summarize(label, OPTION_LABEL_WIDTH)
                if label
                else "%s (no title in the shortlist)" % NOT_AVAILABLE,
                "chosen": video_id == chosen,
            }
        )
    ranked = sorted(
        rows, key=lambda row: (row["value"] is None, -(row["value"] or 0.0))
    )
    stats = {
        "total": len(rows),
        "unreadable": unreadable,
        "out_of_range": out_of_range,
        "unlabelled": unlabelled,
        "ignored": len(
            [key for key in probabilities if _clean_text(key) not in shortlist_ids]
        ),
    }

    lines: List[str] = [
        palette.paint("heading", VERBOSE_OPTIONS_HEADING),
        palette.paint("rule", VERBOSE_RULE),
        _field(
            "model",
            report.get("model") if report.get("jev_enabled") else "not consulted",
            palette,
        ),
        _field("options", "%d in the shortlist" % len(rows), palette),
        _field("answer conf", _percent(data["confidence"]), palette),
        _field(
            "chosen",
            "%s - the option the app used" % chosen
            if chosen
            else "none - the deterministic result was kept",
            palette,
            "success" if chosen else "fallback",
        ),
        palette.paint(
            "label",
            "  %s %-2s %-11s %7s  %s"
            % (" ", "", "video id", "conf", "candidate (title - channel)"),
        ),
    ]
    if any(row["chosen"] for row in ranked):
        lines.append(
            "  %s marks the option JEV chose"
            % palette.paint("success", CHOSEN_MARKER)
        )
    for rank, row in enumerate(ranked, start=1):
        lines.append(
            "  %s %-2d %-11s %7s  %s"
            % (
                palette.paint("success", CHOSEN_MARKER) if row["chosen"] else " ",
                rank,
                row["video_id"],
                _percent(row["value"]),
                row["label"],
            )
        )
    for note in _option_notes(data, stats, chosen, shortlist_ids):
        lines.append(_field("note", note, palette))
    lines.append(palette.paint("rule", VERBOSE_RULE))
    lines.append("")
    return redact_secrets("\n".join(lines))



def collect_report(
    ghostify_dl: Any,
    song: Any,
    results: Sequence[Any],
    search: Dict[str, Any],
    spotify_id: str,
    spotify_url: str,
    per_track_timeout: float,
    offline: bool = False,
    transport: Any = None,
    verbose: Optional["VerboseJev"] = None,
) -> Dict[str, Any]:
    """Run all three decisions through the live code and merge into one dict.

    ``offline`` disables JEV through its own env switch, so the comparison shows
    the deterministic path without any OpenRouter request. The app-download
    preview (``ghostify_dl.preview_app_selection``) always runs: it is spotdl
    scoring, not JEV, so ``--offline`` never disables it — it only needs the
    YouTube search results already in hand, never a download and never a file
    write.

    ``verbose`` (a :class:`VerboseJev`) is the ``-v`` sink: it wraps ``transport``
    so every request is dumped before it is sent, and closes with a "nothing was
    sent" block when the run made no request at all plus the option-confidence
    table when the live answer carried per-option data. It only prints: the
    payload, the endpoint, the headers and the timeout are the ones the selector
    really uses, and the wrapped sender is called unchanged.
    """
    sender = verbose.transport(transport) if verbose is not None else transport
    if offline:
        with forced_deterministic():
            report = dict(
                ghostify_dl.compare_yt_selection(
                    song, results, per_track_timeout, sender
                )
            )
    else:
        report = dict(
            ghostify_dl.compare_yt_selection(
                song, results, per_track_timeout, sender
            )
        )
    try:
        build_meta = getattr(ghostify_dl, "build_app_meta", None)
        preview_fn = getattr(ghostify_dl, "preview_app_selection", None)
        if callable(preview_fn):
            meta = build_meta(song) if callable(build_meta) else None
            # The device passes the stored song.ytId (None for Spotify tracks
            # today); the demo has no stored ytId, so None mirrors the device.
            preview = preview_fn(
                song,
                meta,
                None,
                spotify_url,
                per_track_timeout,
                None,
                list(results or []),
            )
        else:
            preview = {}
    except Exception as exc:  # noqa: BLE001 - preview must never break the report
        preview = {"app_reason": "app preview failed (%s)" % type(exc).__name__}
    if not isinstance(preview, dict):
        preview = {"app_reason": "app preview returned no data"}
    artists = ", ".join(
        str(artist) for artist in (getattr(song, "artists", None) or [])
    )
    report.update(
        {
            "title": getattr(song, "name", None),
            "artists": artists,
            "album": getattr(song, "album_name", None),
            "duration_s": getattr(song, "duration", None),
            "spotify_id": spotify_id,
            "spotify_url": spotify_url,
            "provider": search.get("provider"),
            "query": search.get("query"),
            "forced_offline": offline,
            "app_video_id": preview.get("app_video_id"),
            "app_url": preview.get("app_url"),
            "app_provider": preview.get("app_provider") or search.get("provider"),
            "app_query": preview.get("app_query") or search.get("query"),
            "app_reason": preview.get("app_reason"),
        }
    )
    report["shortlist_candidates"] = shortlist_candidates(
        ghostify_dl, results, report.get("shortlist_ids")
    )
    if verbose is not None:
        verbose.summarize(report)
    return report


class VerboseJev:
    """The ``-v`` sink: dumps every JEV request before it leaves the process.

    The dump comes from a transport wrapper rather than from a second payload
    build, so what is shown is by construction the request the selector would
    send - a wrapper cannot drift from it. The wrapper then calls the sender it
    wraps (the live ``jev_selector`` post by default) with the same arguments,
    keeps the response it got back - only to read the per-option confidences out
    of it once the run is over, never to validate anything a second time - and
    a dump that fails to render is swallowed rather than turned into a request
    failure, so ``-v`` cannot change a decision or an exit code.
    """

    def __init__(self, palette: Palette = PLAIN, stream: Any = None) -> None:
        self.palette = palette
        self.stream = stream
        self.blocks: List[str] = []
        self.responses: List[Any] = []

    def _emit(self, block: str) -> None:
        self.blocks.append(block)
        if self.stream is None:
            print(block)
        else:
            print(block, file=self.stream)

    def transport(self, inner: Optional[Callable] = None) -> Callable:
        """A sender that dumps the request, then delegates to *inner*.

        *inner* keeps its place as the argument the selector would have used;
        with none, the selector's own ``_post_json`` runs, i.e. the real
        OpenRouter call under the app's own key handling. Whatever comes back
        is remembered for :meth:`summarize` and handed to the selector
        untouched.
        """

        def transport(url: Any, payload: Any, headers: Any, timeout: Any) -> Any:
            try:
                self._emit(
                    format_verbose_request(
                        HTTP_METHOD, url, headers, payload, timeout, self.palette
                    )
                )
            except Exception:  # noqa: BLE001 - a failed dump must stay silent
                pass
            if inner is not None:
                response = inner(url, payload, headers, timeout)
            else:
                response = selector_transport()(url, payload, headers, timeout)
            self.responses.append(response)
            return response

        return transport

    def summarize(self, report: Dict[str, Any]) -> None:
        """Close ``-v``: the "no request was sent" block, then the option table.

        The first block appears only when the run sent nothing at all, the second
        only when a live answer carried per-option data, so neither adds output
        where there is nothing to show. A render that fails is swallowed, exactly
        like the request dump, and cannot affect the decision.
        """
        try:
            if not self.blocks:
                self._emit(format_verbose_skipped(report, self.palette))
            for response in self.responses:
                block = format_verbose_options(response, report, self.palette)
                if block:
                    self._emit(block)
        except Exception:  # noqa: BLE001 - a failed dump must stay silent
            pass


def _unreachable_transport(url: Any, payload: Any, headers: Any, timeout: Any) -> Any:
    raise RuntimeError("jev_selector is not importable; no request was sent")


def selector_transport() -> Callable:
    """The selector's own POST function - the sender production would use."""
    try:
        import jev_selector
    except ImportError:
        return _unreachable_transport
    return jev_selector._post_json


def download_chosen(
    ghostify_dl: Any, report: Dict[str, Any], output_dir: str, palette: Palette = PLAIN
) -> int:
    """Download the video the effective decision picked (only with ``--download``)."""
    video_id = report.get("effective_video_id")
    if not video_id:
        print("Nothing to download: no candidate carried a video id.")
        return EXIT_OK
    print("Downloading %s into %s ..." % (video_id, output_dir))
    downloader = ghostify_dl.make_downloader(output_dir)
    result = downloader.download(report["spotify_url"], yt_id=video_id)
    print(
        "Download %s: %s"
        % (result.get("status"), result.get("output_path") or result.get("error"))
    )
    return EXIT_OK if result.get("status") in ("COMPLETED", "SKIPPED") else EXIT_PROVIDER


def run_playlist(
    ghostify_dl: Any,
    playlist_id: str,
    playlist_url: str,
    limit: int,
    timeout: float,
    offline: bool,
    download: bool,
    output_dir: str,
    limit_tracks: Optional[int],
    summary_only: bool,
    verbose: Optional["VerboseJev"],
    palette: Palette,
    color: bool,
) -> int:
    """Run the per-song flow for every track in *playlist_id*, sequentially.

    Resolves tracks via the live ``ghostify_dl.fetch_playlist`` (``resolve_yt``
    off), then for each song runs the exact same ``load_song`` /
    ``search_yt_candidates`` / ``collect_report`` flow as single mode with the
    same ``limit`` / ``timeout`` / ``offline`` / ``verbose``. Per-song output
    keeps the detailed 3-column report with a ``=== [i/N] title - artists ===``
    header; the end holds the playlist summary. With ``summary_only`` only the
    summary is printed (verbose dumps are suppressed). With ``download`` each
    effective pick is fetched into ``output_dir`` (or skipped with a message).

    Compare-only default has no download/sidecar side effects: ``make_downloader``
    is only reached when ``download`` is true.

    Returns 0 when at least one track succeeded, else 5/6/4 (provider if any
    provider error, else no-candidates if any, else metadata).
    """
    try:
        playlist_data = load_playlist(ghostify_dl, playlist_id)
    except DemoError as error:
        print(palette.paint("error", "error: %s" % error), file=sys.stderr)
        return error.exit_code
    entries = playlist_data.get("tracks") if isinstance(playlist_data, dict) else None
    if not isinstance(entries, list):
        entries = []
    if limit_tracks is not None:
        try:
            capped = int(limit_tracks)
        except (TypeError, ValueError):
            print(
                palette.paint("error", "error: --max/--limit-tracks must be an integer"),
                file=sys.stderr,
            )
            return EXIT_USAGE
        if capped <= 0:
            print(
                palette.paint("error", "error: --max/--limit-tracks must be > 0"),
                file=sys.stderr,
            )
            return EXIT_USAGE
        entries = entries[:capped]
    total = len(entries)
    if total == 0:
        print(
            palette.paint(
                "error",
                "error: playlist %s is empty or has no tracks. Nothing to compare."
                % playlist_url,
            ),
            file=sys.stderr,
        )
        empty_summary = summarize_playlist([], [])
        print(
            format_playlist_summary(
                empty_summary, [], [], playlist_data if isinstance(playlist_data, dict) else None, palette
            )
        )
        return EXIT_NO_CANDIDATES
    reports: List[Dict[str, Any]] = []
    failures: List[Dict[str, Any]] = []
    for index, entry in enumerate(entries, start=1):
        meta = entry if isinstance(entry, dict) else {}
        raw_id = meta.get("spotify_id")
        spotify_id = _clean_text(raw_id)
        title = _clean_text(meta.get("title")) or "untitled"
        artists = _clean_text(meta.get("artists")) or "-"
        header = format_playlist_header(index, total, title, artists, palette)
        if not spotify_id:
            failures.append(
                {
                    "spotify_id": "-",
                    "title": title,
                    "artists": artists,
                    "kind": "skipped",
                    "reason": "playlist entry has no spotify_id; skipped",
                }
            )
            if not summary_only:
                print("")
                print(header)
                print(
                    palette.paint(
                        "error",
                        "skipped [%d/%d] %s - %s: no spotify_id in playlist entry"
                        % (index, total, title, artists),
                    ),
                    file=sys.stderr,
                )
            continue
        track_url = "https://open.spotify.com/track/%s" % spotify_id
        try:
            song = load_song(ghostify_dl, track_url)
        except DemoError as error:
            kind = "metadata" if error.exit_code == EXIT_METADATA else "error"
            failures.append(
                {
                    "spotify_id": spotify_id,
                    "title": title,
                    "artists": artists,
                    "kind": kind,
                    "reason": str(error),
                }
            )
            if not summary_only:
                print("")
                print(header)
                print(palette.paint("error", "error [%d/%d]: %s" % (index, total, error)), file=sys.stderr)
            continue
        try:
            search = ghostify_dl.search_yt_candidates(
                song, limit=limit, per_track_timeout=timeout
            )
        except Exception as exc:  # noqa: BLE001 - counted, never aborts the list
            failures.append(
                {
                    "spotify_id": spotify_id,
                    "title": getattr(song, "name", title),
                    "artists": ", ".join(getattr(song, "artists", None) or []) or artists,
                    "kind": "provider",
                    "reason": "YouTube search failed (%s)" % type(exc).__name__,
                }
            )
            if not summary_only:
                print("")
                print(header)
                print(
                    palette.paint(
                        "error",
                        "error [%d/%d]: YouTube search failed (%s)"
                        % (index, total, type(exc).__name__),
                    ),
                    file=sys.stderr,
                )
            continue
        if not isinstance(search, dict) or not search.get("results"):
            error_name = search.get("error") if isinstance(search, dict) else None
            if error_name:
                failures.append(
                    {
                        "spotify_id": spotify_id,
                        "title": getattr(song, "name", title),
                        "artists": ", ".join(getattr(song, "artists", None) or []) or artists,
                        "kind": "provider",
                        "reason": "YouTube search failed (%s)" % error_name,
                    }
                )
                if not summary_only:
                    print("")
                    print(header)
                    print(
                        palette.paint(
                            "error",
                            "error [%d/%d]: YouTube search failed (%s) for %s"
                            % (index, total, error_name, getattr(song, "name", title)),
                        ),
                        file=sys.stderr,
                    )
            else:
                failures.append(
                    {
                        "spotify_id": spotify_id,
                        "title": getattr(song, "name", title),
                        "artists": ", ".join(getattr(song, "artists", None) or []) or artists,
                        "kind": "no_candidates",
                        "reason": "YouTube returned no candidates",
                    }
                )
                if not summary_only:
                    print("")
                    print(header)
                    print(
                        palette.paint(
                            "error",
                            "no candidates [%d/%d] for %s - %s"
                            % (
                                index,
                                total,
                                getattr(song, "name", title),
                                ", ".join(getattr(song, "artists", None) or []),
                            ),
                        ),
                        file=sys.stderr,
                    )
            continue
        try:
            report = collect_report(
                ghostify_dl,
                song,
                search["results"],
                search,
                spotify_id,
                track_url,
                timeout,
                offline=offline,
                verbose=verbose,
            )
        except Exception as exc:  # noqa: BLE001 - counted, never aborts the list
            failures.append(
                {
                    "spotify_id": spotify_id,
                    "title": getattr(song, "name", title),
                    "artists": ", ".join(getattr(song, "artists", None) or []) or artists,
                    "kind": "error",
                    "reason": "comparison failed (%s: %s)" % (type(exc).__name__, exc),
                }
            )
            if not summary_only:
                print("")
                print(header)
                print(
                    palette.paint(
                        "error",
                        "error [%d/%d]: comparison failed (%s)"
                        % (index, total, type(exc).__name__),
                    ),
                    file=sys.stderr,
                )
            continue
        reports.append(report)
        if not summary_only:
            print("")
            print(header)
            print(format_report(report, color=color))
        if download:
            try:
                download_chosen(ghostify_dl, report, output_dir, palette)
            except Exception as exc:  # noqa: BLE001 - reported, never aborts
                print(
                    palette.paint(
                        "error",
                        "download failed [%d/%d] (%s: %s)"
                        % (index, total, type(exc).__name__, exc),
                    ),
                    file=sys.stderr,
                )
    summary = summarize_playlist(reports, failures)
    if isinstance(playlist_data, dict):
        # Keep fetched size for context when --max capped the run.
        summary["_available"] = len(playlist_data.get("tracks") or [])
    text = format_playlist_summary(
        summary, reports, failures, playlist_data if isinstance(playlist_data, dict) else None, palette
    )
    if not summary_only:
        print("")
    print(text)
    if summary.get("succeeded", 0) > 0:
        return EXIT_OK
    if summary.get("provider_errors", 0) > 0:
        return EXIT_PROVIDER
    if summary.get("no_candidates", 0) > 0:
        return EXIT_NO_CANDIDATES
    if summary.get("metadata_errors", 0) > 0:
        return EXIT_METADATA
    if summary.get("skipped", 0) > 0:
        return EXIT_NO_CANDIDATES
    return EXIT_PROVIDER


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="jev_selection_demo.py",
        description=(
            "Compare the original YouTube selection, the app-download "
            "selection and the JEV/OpenRouter decision for one Spotify track "
            "or a whole Spotify playlist, using the live Ghostify code."
        ),
        epilog=(
            "Compare-only by default: nothing is downloaded and no OpenRouter "
            "request is made unless OPENROUTER_API_KEY is set. The key is never "
            "printed. Examples: "
            "jev_selection_demo.py https://open.spotify.com/track/<id> ; "
            "jev_selection_demo.py https://open.spotify.com/playlist/<id> --max 5 ; "
            "jev_selection_demo.py spotify:playlist:<id> --summary-only . "
            "Playlist exit codes mirror single-track codes (0 ok, 2 usage, "
            "3 dep, 4 metadata, 5 provider, 6 no candidates): 0 when at least "
            "one track succeeded, otherwise 5/6/4 when all failed."
        ),
    )
    parser.add_argument(
        "spotify_url",
        help=(
            "Spotify track or playlist URL, e.g. "
            "https://open.spotify.com/track/<id> or "
            "https://open.spotify.com/playlist/<id> "
            "(spotify:track:/spotify:playlist: URIs also work)"
        ),
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=DEFAULT_LIMIT,
        help="candidates to request per provider (default: %(default)s)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT,
        help=(
            "per-track budget in seconds for the YouTube search and the JEV "
            "call (default: %(default)s)"
        ),
    )
    parser.add_argument(
        "--offline",
        "--no-jev",
        dest="offline",
        action="store_true",
        help="force the deterministic fallback; never call OpenRouter",
    )
    parser.add_argument(
        "--download",
        action="store_true",
        help="actually download the chosen video (off by default: compare only)",
    )
    parser.add_argument(
        "--output-dir",
        default=".",
        help="destination for --download (default: %(default)s)",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help=(
            "dump the JEV request before it is sent - method, endpoint, "
            "redacted headers and the full body - then the per-option "
            "confidences of the live answer, and show ghostify_dl / jev "
            "debug logs; the API key is never printed"
        ),
    )
    parser.add_argument(
        "--max",
        "--limit-tracks",
        dest="limit_tracks",
        type=int,
        default=None,
        metavar="N",
        help=(
            "playlist only: compare only the first N tracks (quick runs); "
            "single-track mode ignores it"
        ),
    )
    parser.add_argument(
        "--summary-only",
        dest="summary_only",
        action="store_true",
        help=(
            "playlist only: print only the total stats without per-song detail "
            "(verbose dumps are suppressed); single-track mode ignores it"
        ),
    )
    parser.add_argument(
        "--no-color",
        dest="no_color",
        action="store_true",
        help=(
            "plain output even on a terminal; colour is already off when stdout "
            "is not a TTY or NO_COLOR is set"
        ),
    )
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    if args.verbose:
        logging.basicConfig(level=logging.DEBUG, format="%(name)s: %(message)s")
        logging.getLogger("spotdl").setLevel(logging.WARNING)
    color = use_color(args.no_color)
    palette = Palette(color)
    verbose = VerboseJev(palette) if args.verbose else None

    try:
        kind, spotify_id, spotify_url = normalize_spotify_input(args.spotify_url)
    except DemoError as error:
        print(palette.paint("error", "error: %s" % error), file=sys.stderr)
        return error.exit_code
    try:
        ghostify_dl = import_ghostify_dl()
    except DemoError as error:
        print(palette.paint("error", "error: %s" % error), file=sys.stderr)
        return error.exit_code

    if kind == "playlist":
        # --summary-only suppresses per-song detail (and verbose dumps); the
        # summary itself is always printed. --max caps the run.
        summary_only = bool(getattr(args, "summary_only", False))
        limit_tracks = getattr(args, "limit_tracks", None)
        playlist_verbose = None if summary_only else verbose
        return run_playlist(
            ghostify_dl,
            spotify_id,
            spotify_url,
            args.limit,
            args.timeout,
            args.offline,
            args.download,
            args.output_dir,
            limit_tracks,
            summary_only,
            playlist_verbose,
            palette,
            color,
        )

    try:
        song = load_song(ghostify_dl, spotify_url)
        search = ghostify_dl.search_yt_candidates(
            song, limit=args.limit, per_track_timeout=args.timeout
        )
        if not search["results"]:
            if search.get("error"):
                raise DemoError(
                    "YouTube search failed (%s) for %s.\nThis is a network or "
                    "provider problem, not a JEV problem; no decision was made."
                    % (search["error"], song.name),
                    EXIT_PROVIDER,
                )
            raise DemoError(
                "YouTube returned no candidates for %s - %s. Nothing to compare."
                % (song.name, ", ".join(getattr(song, "artists", None) or [])),
                EXIT_NO_CANDIDATES,
            )
        report = collect_report(
            ghostify_dl,
            song,
            search["results"],
            search,
            spotify_id,
            spotify_url,
            args.timeout,
            offline=args.offline,
            verbose=verbose,
        )
    except DemoError as error:
        print(Palette(color).paint("error", "error: %s" % error), file=sys.stderr)
        return error.exit_code

    print(format_report(report, color=color))
    if args.download:
        try:
            return download_chosen(ghostify_dl, report, args.output_dir, Palette(color))
        except Exception as exc:  # noqa: BLE001 - report, never traceback-dump
            print(
                Palette(color).paint("error", "download failed (%s: %s)" % (type(exc).__name__, exc)),
                file=sys.stderr,
            )
            return EXIT_PROVIDER
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())

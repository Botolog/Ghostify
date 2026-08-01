"""ghostify_dl — Ghostify Python bridge module (metadata fetch + track download).

Lives in Chaquopy's ``src/main/python`` directory and is imported in-process by
the Kotlin bridges (``PlaylistMetadataBridge`` / ``TrackDownloadBridge``) via
``Python.getInstance().getModule("ghostify_dl")``.

Merged from three component deliverables:
 * python-runtime  — interpreter boot probes + PATH prepending for ffmpeg;
 * metadata-fetch  — ``fetch_playlist`` (public Spotify playlist metadata);
 * track-download  — ``TrackDownloader`` (single-track spotdl downloads).

Design notes
------------
* ``spotdl`` and its dependencies are imported lazily and cached: interpreter
  boot stays fast and a broken spotdl install never prevents the app from
  booting (T-026).
* ``prepend_path`` extends ``os.environ["PATH"]`` so the bundled static
  ``ffmpeg`` (see ``FfmpegLocator``) is found by the ``subprocess`` calls
  spotdl makes (T-025).
* Every failure is surfaced as a typed exception whose ``str()`` is
  ``"<CODE>: <message>"`` (``GhostifyError`` for fetches,
  ``TrackDownloadError`` for downloads) so the Kotlin bridges can recover a
  stable machine-readable token from the Chaquopy exception text.
* The module is designed to run identically under Chaquopy on Android and under
  plain CPython (the component test-suites in ``tests/python`` exercise this
  exact file). A :class:`TrackDownloader` instance is *not* thread-safe: create
  one per worker and drive it from a single thread. The Kotlin wrapper enforces
  this by serialising all calls on a single lock.
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import threading
import time
import unicodedata
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple, Union

logger = logging.getLogger("ghostify_dl")

_MODULES: Dict[str, Any] = {}


def _import(name):
    """Import *name* once and cache the module object."""
    module = _MODULES.get(name)
    if module is None:
        module = __import__(name, fromlist=["*"])
        _MODULES[name] = module
    return module


# ---------------------------------------------------------------------------
# Interpreter boot helpers (from python-runtime).
# ---------------------------------------------------------------------------


def prepend_path(directory):
    """Prepend *directory* to ``os.environ["PATH"]`` (idempotent).

    The bundled static ffmpeg binary is copied to ``filesDir/ffmpeg`` and that
    directory is added to PATH here, so spotdl's ``subprocess`` / ``shutil.which``
    resolves ``ffmpeg`` by name (T-025).
    """
    directory = os.path.abspath(directory)
    current = os.environ.get("PATH", "")
    parts = [p for p in current.split(os.pathsep) if p]
    if directory not in parts:
        os.environ["PATH"] = directory + os.pathsep + current
    return {"path": os.environ["PATH"], "ffmpeg": _which_ffmpeg()}


def boot_probe():
    """Interpreter self-check used by the boot tests (T-022..T-024)."""
    import platform

    return {
        "python_version": platform.python_version(),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "pid": os.getpid(),
        "thread": threading.current_thread().name,
        "home": os.environ.get("HOME", ""),
        "has_ffmpeg": _which_ffmpeg() is not None,
    }


def _which_ffmpeg():
    return shutil.which("ffmpeg")


def import_spotdl():
    """Import spotdl; raises on failure. Cached across calls (T-026)."""
    module = _import("spotdl")
    return {
        "module": "spotdl",
        "version": getattr(module, "__version__", "unknown"),
    }


def ffmpeg_probe():
    """Run a trivial ffmpeg conversion and report the output size (T-025).

    Requires the bundled static ffmpeg to include libmp3lame (the codec spotdl
    uses for MP3 output); validated in the Phase-0 spike.
    """
    import subprocess
    import tempfile

    ffmpeg = _which_ffmpeg()
    if ffmpeg is None:
        raise RuntimeError("ffmpeg not found on PATH")
    out_dir = tempfile.mkdtemp(prefix="ghostify_ffmpeg_")
    out_path = os.path.join(out_dir, "tone.mp3")
    command = [
        ffmpeg,
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "sine=frequency=440:duration=1",
        "-c:a",
        "libmp3lame",
        "-b:a",
        "128k",
        out_path,
    ]
    proc = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=30,
    )
    if proc.returncode != 0:
        detail = proc.stderr.decode("utf-8", "replace") if proc.stderr else ""
        raise RuntimeError("ffmpeg probe failed rc=%d: %s" % (proc.returncode, detail))
    return {
        "converted": True,
        "bytes": os.path.getsize(out_path),
        "ffmpeg": ffmpeg,
    }


# ---------------------------------------------------------------------------
# Typed error model (metadata fetch) — stable wire contract with the Kotlin
# PlaylistMetadataBridge.
# ---------------------------------------------------------------------------

ERR_NO_NETWORK = "NO_NETWORK"
ERR_RATE_LIMITED = "RATE_LIMITED"
ERR_PRIVATE = "PRIVATE"
ERR_TIMEOUT = "TIMEOUT"
ERR_NOT_FOUND = "NOT_FOUND"
ERR_UNKNOWN = "UNKNOWN"

_ERROR_CODES = frozenset(
    {
        ERR_NO_NETWORK,
        ERR_RATE_LIMITED,
        ERR_PRIVATE,
        ERR_TIMEOUT,
        ERR_NOT_FOUND,
        ERR_UNKNOWN,
    }
)

_PUBLIC_ONLY_MESSAGE = "This playlist is private. Only public playlists are supported."


class GhostifyError(Exception):
    """Typed bridge failure. ``str()`` is ``"<CODE>: <message>"``."""

    def __init__(self, code: str, message: str) -> None:
        if code not in _ERROR_CODES:
            raise ValueError(f"Unknown error code: {code}")
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message

    def __str__(self) -> str:  # pragma: no cover - trivial
        return f"{self.code}: {self.message}"


# ---------------------------------------------------------------------------
# Typed error model (track download) — stable wire contract with the Kotlin
# TrackDownloadBridge.
# ---------------------------------------------------------------------------


class ErrorKind:
    """Stable machine-readable failure kinds the Kotlin side maps to messages."""

    NO_TRACK = "NO_TRACK"
    METADATA_FAILED = "METADATA_FAILED"
    SEARCH_FAILED = "SEARCH_FAILED"
    AUDIO_UNAVAILABLE = "AUDIO_UNAVAILABLE"
    CONVERSION_FAILED = "CONVERSION_FAILED"
    TAGGING_FAILED = "TAGGING_FAILED"
    VALIDATION_FAILED = "VALIDATION_FAILED"
    IO = "IO"
    INTERRUPTED = "INTERRUPTED"
    DEPENDENCY = "DEPENDENCY"
    UNKNOWN = "UNKNOWN"


class TrackDownloadError(Exception):
    """Raised for every download failure with a stable, machine-readable kind."""

    def __init__(self, kind: str, message: str):
        self.kind = kind
        self.message = message
        super().__init__(f"{kind}: {message}")

    def to_dict(self) -> Dict[str, Any]:
        return {"error_type": self.kind, "error": self.message}


# ---------------------------------------------------------------------------
# Defaults (metadata fetch).
# ---------------------------------------------------------------------------

DEFAULT_TIMEOUT = 180.0
DEFAULT_RESOLVE_YT = True
DEFAULT_PER_TRACK_YT_TIMEOUT = 8.0
YT_CONCURRENCY = 4

_PLAYLIST_ID_PATTERN = re.compile(r"^[A-Za-z0-9]{1,64}$")
_PLAYLIST_REF_PATTERN = re.compile(
    r"(?:open\.spotify\.com/playlist/|spotify:playlist:)([A-Za-z0-9]{1,64})"
)
_VIDEO_ID_PATTERN = re.compile(r"[?&]v=([A-Za-z0-9_-]{11})")

# ---------------------------------------------------------------------------
# Module-level state (guarded).
# ---------------------------------------------------------------------------

_client_lock = threading.Lock()
_client_initialized = False
_fetch_lock = threading.Lock()
_yt_provider_lock = threading.Lock()
_yt_provider = None  # type: Optional[Any]
_worker_local = threading.local()


# ---------------------------------------------------------------------------
# Shared Spotify client.
# ---------------------------------------------------------------------------
#
# spotdl's SpotifyClient refuses to run more than once per process, yet a
# long-lived app legitimately builds several downloaders and issues fetches.
# The whole module therefore initialises the shared client exactly once.

_spotify_client_ready = False


def _ensure_spotify_client(
    client_id: Optional[str] = None, client_secret: Optional[str] = None
) -> None:
    """Initialise spotdl's global Spotify client exactly once, if needed.

    Public Spotify metadata endpoints need no credentials (v1 "public playlists
    only" model). Safe to call from any number of downloaders and from the
    playlist-fetch path.
    """
    global _spotify_client_ready
    if _spotify_client_ready:
        return
    try:
        from spotdl.utils.spotify import SpotifyClient, SpotifyError

        try:
            SpotifyClient()
        except SpotifyError:
            try:
                SpotifyClient.init(
                    client_id=client_id, client_secret=client_secret, no_cache=True
                )
            except SpotifyError:
                # Lost an init race with a concurrent component; the client must
                # exist now, otherwise re-raise below.
                SpotifyClient()
        _spotify_client_ready = True
    except ImportError:
        # Fall back to spotdl's public `SpotifyClient` entry point.
        from spotdl import SpotifyClient

        SpotifyClient.init(
            client_id=client_id, client_secret=client_secret, no_cache=True
        )
        _spotify_client_ready = True


# ===========================================================================
# METADATA FETCH  (fetch_playlist)
# ===========================================================================

# ---------------------------------------------------------------------------
# Wall-clock deadline helper.
# ---------------------------------------------------------------------------


def _call_with_deadline(timeout: Optional[float], func: Callable, *args: Any) -> Any:
    """Run ``func`` with a hard wall-clock deadline.

    On expiry a typed GhostifyError(TIMEOUT) is raised immediately; the worker
    thread keeps running in the background (Python cannot cancel in-flight
    sockets/requests) but its result is discarded. The worker is a daemon
    thread so it never blocks interpreter shutdown.
    """
    if timeout is None or timeout <= 0:
        return func(*args)

    box: Dict[str, Any] = {}
    done = threading.Event()

    def runner() -> None:
        try:
            box["result"] = func(*args)
        except BaseException as exc:  # noqa: BLE001 - capture every error type
            box["error"] = exc
        finally:
            done.set()

    thread = threading.Thread(target=runner, name="ghostify-dl-worker", daemon=True)
    thread.start()
    done.wait(timeout)
    if not done.is_set():
        raise GhostifyError(
            ERR_TIMEOUT, f"Playlist fetch timed out after {timeout:.0f}s."
        )
    if "error" in box:
        raise box["error"]  # type: ignore[misc]
    return box["result"]


# ---------------------------------------------------------------------------
# Public entry point.
# ---------------------------------------------------------------------------


def fetch_playlist(
    spotify_id: str, options: Optional[Dict[str, Any]] = None
) -> Dict[str, Any]:
    """Fetch full metadata for a public Spotify playlist.

    Args:
        spotify_id: A Spotify playlist id, a ``open.spotify.com/playlist/...``
            URL, or a ``spotify:playlist:...`` URI (defensive convenience -
            the app normally passes a bare id).
        options: Optional dict with keys ``timeout`` (float, seconds),
            ``resolve_yt`` (bool), ``per_track_yt_timeout`` (float, seconds),
            ``client_id`` / ``client_secret`` (ignored by the anonymous
            client, accepted for forward compatibility).

    Returns:
        The serializable result dict described in the module docstring.

    Raises:
        GhostifyError: with a stable ``code`` - never a raw exception.
    """
    opts = _normalize_options(options)
    playlist_id = _extract_playlist_id(spotify_id)
    _ensure_spotify_client(opts["client_id"], opts["client_secret"])

    try:
        with _fetch_lock:
            return _call_with_deadline(
                opts["timeout"],
                _fetch_impl,
                playlist_id,
                opts["resolve_yt"],
                opts["per_track_yt_timeout"],
            )
    except GhostifyError:
        raise
    except Exception as exc:  # noqa: BLE001 - must classify every failure
        raise _classify_error(exc) from exc


def _normalize_options(options: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    opts = dict(options or {})
    timeout = opts.get("timeout", DEFAULT_TIMEOUT)
    per_track = opts.get("per_track_yt_timeout", DEFAULT_PER_TRACK_YT_TIMEOUT)
    try:
        timeout = float(timeout) if timeout is not None else None
        per_track = float(per_track)
    except (TypeError, ValueError):
        raise GhostifyError(ERR_UNKNOWN, "Invalid bridge options.") from None
    return {
        "timeout": timeout,
        "resolve_yt": bool(opts.get("resolve_yt", DEFAULT_RESOLVE_YT)),
        "per_track_yt_timeout": per_track,
        "client_id": str(opts.get("client_id") or ""),
        "client_secret": str(opts.get("client_secret") or ""),
    }


def _extract_playlist_id(spotify_id: Any) -> str:
    if not isinstance(spotify_id, str):
        raise GhostifyError(ERR_NOT_FOUND, "Playlist id must be a string.")
    value = spotify_id.strip()
    match = _PLAYLIST_REF_PATTERN.search(value)
    if match:
        return match.group(1)
    if _PLAYLIST_ID_PATTERN.match(value):
        return value
    raise GhostifyError(ERR_NOT_FOUND, "Invalid playlist id.")


def _fetch_impl(
    playlist_id: str,
    resolve_yt: bool,
    per_track_yt_timeout: float,
) -> Dict[str, Any]:
    from spotdl.types.playlist import Playlist

    url = f"https://open.spotify.com/playlist/{playlist_id}"
    try:
        metadata, songs = Playlist.get_metadata(url)
    except KeyError as exc:
        # Anonymous endpoint returned a GenericError payload (private/deleted
        # playlist); spotdl's formatter reduces it to a bare KeyError. Diagnose
        # once to give the user an accurate typed error.
        diagnosed = _diagnose_playlist(playlist_id)
        if diagnosed is not None:
            raise diagnosed from exc
        raise GhostifyError(
            ERR_NOT_FOUND,
            "Playlist could not be fetched. It may be private or deleted.",
        ) from exc

    # Best-effort album/cover enrichment (T-012): spotdl 4.5.x anonymous track
    # payloads omit album + cover entirely, so fill them from the paginated
    # GraphQL source. A failure yields {} and tracks simply keep empty strings.
    enrichment = _fetch_album_enrichment(playlist_id)

    yt_ids = _resolve_yt_ids(songs, resolve_yt, per_track_yt_timeout)

    tracks = [
        _song_to_dict(song, position, yt_ids[position], enrichment)
        for position, song in enumerate(songs)
    ]
    return {
        "name": _clean(metadata.get("name")),
        "owner": _clean(metadata.get("author_name")),
        "cover_url": _clean(metadata.get("cover_url")) or None,
        "description": _clean(metadata.get("description")) or None,
        "track_count": len(tracks),
        "tracks": tracks,
    }


def _song_to_dict(
    song: Any,
    position: int,
    yt_id: Optional[str],
    enrichment: Dict[str, Any],
) -> Dict[str, Any]:
    song_id = _clean(getattr(song, "song_id", None)) or ""
    album, cover = enrichment.get(song_id, (None, None))
    artists = getattr(song, "artists", None) or []
    duration_s = getattr(song, "duration", 0) or 0
    return {
        "position": int(position),
        "spotify_id": song_id,
        "title": _clean(getattr(song, "name", None)) or "",
        "artists": ", ".join(a for a in (_clean(a) for a in artists) if a),
        "album": _clean(getattr(song, "album_name", None)) or album or "",
        "duration_ms": int(duration_s) * 1000,
        "cover_url": _clean(getattr(song, "cover_url", None)) or cover or None,
        "yt_id": yt_id,
    }


def _clean(value: Any) -> Optional[str]:
    if value is None:
        return None
    value = str(value).strip()
    return value or None


def _fetch_album_enrichment(playlist_id: str) -> Dict[str, Any]:
    """Map ``spotify_id -> (album_name, album_cover_url)`` for the playlist."""
    try:
        from spotapi import PublicPlaylist

        enriched: Dict[str, Any] = {}
        playlist = PublicPlaylist(playlist_id)
        for content in playlist.paginate_playlist():
            for item in content.get("items", ()) or ():
                track = _graphql_track_data(item)
                uri = track.get("uri") or ""
                if not uri.startswith("spotify:track:"):
                    continue
                song_id = uri.split(":")[-1]
                album = track.get("albumOfTrack") or {}
                sources = (album.get("coverArt") or {}).get("sources") or []
                cover = None
                if sources:
                    cover = max(
                        sources,
                        key=lambda s: (s.get("height") or 0) * (s.get("width") or 0),
                    ).get("url")
                if song_id:
                    enriched[song_id] = (_clean(album.get("name")), cover or None)
        return enriched
    except Exception as exc:  # noqa: BLE001 - enrichment must never fail the fetch
        logger.debug("Album enrichment failed for %s: %s", playlist_id, exc)
        return {}


def _graphql_track_data(item: Any) -> Dict[str, Any]:
    for key in ("itemV2", "itemV3"):
        wrapper = item.get(key) if isinstance(item, dict) else None
        data = wrapper.get("data") if isinstance(wrapper, dict) else None
        if isinstance(data, dict) and data.get("__typename") == "Track":
            return data
    return {}


def _resolve_yt_ids(songs: Any, resolve_yt: bool, per_track_timeout: float) -> list:
    """Resolve YouTube ids for every song (bounded-parallel, T-020)."""
    count = len(songs) if isinstance(songs, (list, tuple)) else 0
    if count == 0 or not resolve_yt or per_track_timeout <= 0:
        return [None] * count

    results: Dict[int, Optional[str]] = {}
    done = threading.Event()
    remaining = {"n": count}
    lock = threading.Lock()

    def run(index: int, song: Any) -> None:
        results[index] = _resolve_yt_id(song, per_track_timeout)
        with lock:
            remaining["n"] -= 1
            if remaining["n"] == 0:
                done.set()

    threads = [
        threading.Thread(target=run, args=(i, s), name=f"ghostify-yt-{i}", daemon=True)
        for i, s in enumerate(songs)
    ]
    for i in range(0, count, YT_CONCURRENCY):
        for thread in threads[i : i + YT_CONCURRENCY]:
            thread.start()

    done.wait()
    return [results.get(i) for i in range(count)]


def _get_yt_provider() -> Any:
    """Thread-local YouTubeMusic provider (session not safe to share)."""
    provider = getattr(_worker_local, "provider", None)
    if provider is not None:
        return provider
    from spotdl.providers.audio.ytmusic import YouTubeMusic

    provider = YouTubeMusic()
    _worker_local.provider = provider
    return provider


def _resolve_yt_id(song: Any, per_track_timeout: float) -> Optional[str]:
    """Resolve a YouTube id for ``song`` or return None on any failure."""
    if per_track_timeout <= 0:
        return None
    provider = _get_yt_provider()

    def _search() -> Optional[str]:
        from spotdl.utils.formatter import create_song_title

        query = create_song_title(song.name, song.artists or [])
        results = provider.get_results(
            query, filter="songs", ignore_spelling=True, limit=10
        )
        for result in results or ():
            video_id = _extract_video_id(getattr(result, "url", None))
            if video_id:
                return video_id
        return None

    try:
        return _call_with_deadline(per_track_timeout, _search)
    except Exception:  # noqa: BLE001 - any per-track failure -> yt_id None
        logger.debug(
            "YouTube resolution failed for song %s", getattr(song, "song_id", "?")
        )
        return None


def _extract_video_id(url: Any) -> Optional[str]:
    if not url:
        return None
    match = _VIDEO_ID_PATTERN.search(str(url))
    return match.group(1) if match else None


def _classify_error(exc: BaseException) -> GhostifyError:
    """Map an arbitrary exception to a typed GhostifyError."""
    texts = _collect_error_texts(exc)
    joined = " | ".join(texts).lower()

    if _matches(
        joined, (r"429", r"rate limit", r"too many request", r"quota", r"retry-after")
    ):
        return GhostifyError(
            ERR_RATE_LIMITED,
            "Spotify is rate-limiting requests. Wait a moment and try again.",
        )
    if _matches(
        joined,
        (
            r"private",
            r"403",
            r"forbidden",
            r"permission",
            r"not authorized",
            r"access denied",
            r"collaborator",
            r"privacy",
        ),
    ):
        return GhostifyError(ERR_PRIVATE, _PUBLIC_ONLY_MESSAGE)
    if _matches(
        joined,
        (
            r"404",
            r"not found",
            r"does not exist",
            r"deleted",
            r"wrong playlist",
            r"invalid playlist",
            r"bad request",
        ),
    ):
        return GhostifyError(
            ERR_NOT_FOUND,
            "Playlist not found. It may have been deleted or the id is invalid.",
        )
    if _is_read_timeout(exc) or _matches(joined, (r"read timed out", r"read timeout")):
        return GhostifyError(ERR_TIMEOUT, "The Spotify request timed out. Try again.")
    if _is_connection_error(exc) or _matches(
        joined,
        (
            r"connection",
            r"connect timeout",
            r"resolve",
            r"name or service not known",
            r"temporary failure in name resolution",
            r"network is unreachable",
            r"failed to establish a new connection",
        ),
    ):
        return GhostifyError(
            ERR_NO_NETWORK, "Network unavailable. Check your connection and try again."
        )
    return GhostifyError(ERR_UNKNOWN, _first_text(texts) or "Unknown bridge error.")


def _classify_from_text(text: str) -> GhostifyError:
    """Classify a raw GraphQL error message from the anonymous Spotify API."""
    lowered = (text or "").lower()
    if _matches(lowered, (r"429", r"rate limit", r"too many request", r"quota")):
        return GhostifyError(
            ERR_RATE_LIMITED,
            "Spotify is rate-limiting requests. Wait a moment and try again.",
        )
    if _matches(
        lowered, (r"private", r"403", r"forbidden", r"permission", r"collaborator")
    ):
        return GhostifyError(ERR_PRIVATE, _PUBLIC_ONLY_MESSAGE)
    if _matches(
        lowered,
        (
            r"404",
            r"not found",
            r"does not exist",
            r"deleted",
            r"invalid playlist",
            r"wrong playlist",
            r"400",
            r"bad request",
        ),
    ):
        return GhostifyError(
            ERR_NOT_FOUND,
            "Playlist not found. It may have been deleted or the id is invalid.",
        )
    return GhostifyError(ERR_UNKNOWN, text or "Playlist could not be fetched.")


def _collect_error_texts(exc: BaseException) -> list:
    texts: list = []
    seen = set()
    current = exc
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        message = str(current)
        if message and message not in texts:
            texts.append(message)
        error_attr = getattr(current, "error", None)
        if isinstance(error_attr, str) and error_attr and error_attr not in texts:
            texts.append(error_attr)
        current = current.__cause__ or current.__context__
    return texts


def _matches(text: str, patterns) -> bool:
    return any(re.search(pattern, text) is not None for pattern in patterns)


def _first_text(texts: list) -> str:
    return texts[0] if texts else ""


def _is_read_timeout(exc: BaseException) -> bool:
    try:
        from requests import exceptions as requests_exceptions
    except Exception:  # noqa: BLE001 - spotdl always ships requests
        return False
    return _chain_contains(
        exc, (requests_exceptions.ReadTimeout, requests_exceptions.Timeout)
    )


def _is_connection_error(exc: BaseException) -> bool:
    try:
        from requests import exceptions as requests_exceptions
    except Exception:  # noqa: BLE001
        return False
    return _chain_contains(
        exc,
        (
            requests_exceptions.ConnectionError,
            requests_exceptions.ConnectTimeout,
        ),
    )


def _chain_contains(exc: BaseException, types: tuple) -> bool:
    current = exc
    seen = set()
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        if isinstance(current, types):
            return True
        current = current.__cause__ or current.__context__
    return False


def _extract_graphql_message(payload: Any) -> str:
    """Safely pull a human-readable message from a GraphQL payload."""
    message = (payload or {}).get("message") if isinstance(payload, dict) else None
    if isinstance(message, str):
        return message
    if isinstance(message, dict):
        inner = message.get("message") or message.get("text")
        if isinstance(inner, str):
            return inner
    return ""


def _diagnose_playlist(playlist_id: str) -> Optional[GhostifyError]:
    """Read the raw anonymous-API payload to distinguish private vs deleted."""
    try:
        from spotapi import PublicPlaylist

        raw = PublicPlaylist(playlist_id).get_playlist_info()
        playlist_v2 = (raw or {}).get("data", {}).get("playlistV2", {})
        typename = playlist_v2.get("__typename")
        message = _extract_graphql_message(playlist_v2)
        if typename == "NotFound":
            return GhostifyError(
                ERR_NOT_FOUND,
                "Playlist not found. It may have been deleted or the id is invalid.",
            )
        if typename == "GenericError" and message:
            return _classify_from_text(message)
        return GhostifyError(ERR_UNKNOWN, "Playlist metadata could not be parsed.")
    except GhostifyError:
        raise
    except Exception as exc:  # noqa: BLE001 - probe failed (e.g. network)
        logger.debug("Playlist diagnostic probe failed: %s", exc)
        return None


# ===========================================================================
# TRACK DOWNLOAD  (TrackDownloader)
# ===========================================================================

DEFAULT_TEMPLATE = "{artists} - {title}"
ALLOWED_BITRATES = (128, 192, 320)

SIDECAR_EXT = ".spotdl"
SIDECAR_SCHEMA = 1

DEFAULT_TEMP_SWEEP_AGE = 3600.0  # seconds
DEFAULT_DURATION_TOLERANCE = 15.0  # seconds


def extract_spotify_id(url: Any) -> Optional[str]:
    """Return the 22-char Spotify track id for a track URL/URI, else None."""
    if not isinstance(url, str):
        return None
    match = re.search(
        r"(?:open\.spotify\.com/track/|spotify:track[:/])([A-Za-z0-9]{22})", url
    )
    return match.group(1) if match else None


def _is_spotify_url(url: str) -> bool:
    return "open.spotify.com" in url or url.startswith("spotify:")


def sanitize_filename(name: str, separator: str = "-") -> str:
    """Return a filesystem-safe file *name* (no path separators)."""
    if not isinstance(name, str):
        raise TypeError(f"sanitize_filename expects str, got {type(name).__name__}")
    out = unicodedata.normalize("NFC", name)
    out = out.replace("/", separator).replace("\\", separator)
    out = "".join(char for char in out if char not in "?*|<>")
    out = out.replace('"', "'").replace(":", "-")
    out = re.sub(r"\s{2,}", " ", out).strip().rstrip(".")
    if not out or all(char in separator + " " for char in out):
        return "_"
    return out


def _normalize_bitrate(bitrate: Union[int, str, None]) -> str:
    """Normalise a bitrate (128/192/320, with or without a 'k' suffix) to '128k'."""
    if bitrate is None:
        bitrate = ALLOWED_BITRATES[-1]
    if isinstance(bitrate, int):
        bitrate = str(bitrate)
    if isinstance(bitrate, str):
        candidate = bitrate.strip().lower()
        if candidate.endswith("k"):
            candidate = candidate[:-1]
        if candidate.isdigit() and int(candidate) in ALLOWED_BITRATES:
            return f"{candidate}k"
    raise ValueError(
        f"Unsupported bitrate {bitrate!r}; expected one of {ALLOWED_BITRATES} kbps"
    )


def _normalize_template(template: Any) -> str:
    """Sanitise an output template: never let an extension end up doubled."""
    if not isinstance(template, str) or not template.strip():
        return DEFAULT_TEMPLATE
    template = template.strip().replace("\\", "/")
    if template.endswith(".{output-ext}"):
        return template
    for ext in (".mp3", ".m4a", ".flac", ".opus", ".wav"):
        if template.endswith(ext):
            template = template[: -len(ext)]
    return template


def _iter_sidecars(root: Path):
    """Yield every ``*.spotdl`` sidecar file under *root* (recursively)."""
    try:
        with os.scandir(root) as iterator:
            for entry in iterator:
                try:
                    if entry.is_dir(follow_symlinks=False):
                        yield from _iter_sidecars(Path(entry.path))
                    elif entry.name.endswith(SIDECAR_EXT):
                        yield Path(entry.path)
                except OSError:
                    continue
    except OSError:
        return


_KNOWN_EXCEPTION_KINDS = {
    "QueryError": ErrorKind.NO_TRACK,
    "SongError": ErrorKind.NO_TRACK,
    "SongNotFoundError": ErrorKind.SEARCH_FAILED,
    "SpotifyError": ErrorKind.METADATA_FAILED,
    "DownloaderError": ErrorKind.SEARCH_FAILED,
    "FFmpegError": ErrorKind.CONVERSION_FAILED,
    "MetadataError": ErrorKind.TAGGING_FAILED,
}


def _kind_for(exc: BaseException) -> str:
    """Map an exception to a stable ErrorKind."""
    if isinstance(exc, TrackDownloadError):
        return exc.kind
    if isinstance(exc, KeyboardInterrupt):
        return ErrorKind.INTERRUPTED
    if isinstance(exc, OSError):
        return ErrorKind.IO
    return _KNOWN_EXCEPTION_KINDS.get(type(exc).__name__, ErrorKind.UNKNOWN)


def _exception_class_name(message: str) -> str:
    match = re.search(r"-\s*([A-Za-z_][A-Za-z0-9_.]*Error)\s*:", message)
    return match.group(1) if match else ""


def _kind_from_error_text(message: str) -> str:
    """Derive a stable kind from a spotdl error string (``url - Error: ...``)."""
    kind = _KNOWN_EXCEPTION_KINDS.get(_exception_class_name(message))
    if kind is not None:
        return kind
    lowered = message.lower()
    if "yt-dlp failed" in lowered or "no match" in lowered:
        return ErrorKind.SEARCH_FAILED
    if "no longer exists" in lowered or "couldn't get metadata" in lowered:
        return ErrorKind.METADATA_FAILED
    if "ffmpeg" in lowered and "error" in lowered:
        return ErrorKind.CONVERSION_FAILED
    return ErrorKind.UNKNOWN


# Candidate method names for each hook, so the same API works with plain Python
# callables and with Java/Kotlin objects passed through Chaquopy.
_START_NAMES = ("on_download_start", "onDownloadStart", "onStart")
_PROGRESS_NAMES = ("on_progress", "onProgress")
_COMPLETE_NAMES = ("on_download_complete", "onDownloadComplete", "onComplete")


def _as_callable(hook: Any, method_names: Tuple[str, ...]) -> Optional[Callable]:
    if hook is None:
        return None
    if callable(hook):
        return hook
    for name in method_names:
        method = getattr(hook, name, None)
        if method is not None and callable(method):
            return method
    raise TrackDownloadError(
        ErrorKind.IO,
        f"Progress hook {hook!r} exposes none of the methods {method_names}",
    )


def _fire(hook: Optional[Callable], *args: Any) -> None:
    """Invoke a hook, never letting it break the download."""
    if hook is None:
        return
    try:
        hook(*args)
    except Exception:  # noqa: BLE001 - hooks are external; log and continue
        logger.exception("Ignoring failure of progress hook %r", hook)


class TrackDownloader:
    """Downloads one track at a time through spotdl's programmatic API.

    Not thread-safe: create one instance per worker and use it from a single
    thread (the Kotlin wrapper serialises all calls).
    """

    def __init__(
        self,
        output_dir: Union[str, os.PathLike],
        bitrate: Union[int, str] = ALLOWED_BITRATES[-1],
        output_template: str = DEFAULT_TEMPLATE,
        ffmpeg: str = "ffmpeg",
        audio_providers: Optional[List[str]] = None,
        lyrics_providers: Optional[List[str]] = None,
        max_filename_length: int = 255,
        temp_sweep_age: float = DEFAULT_TEMP_SWEEP_AGE,
        duration_tolerance: float = DEFAULT_DURATION_TOLERANCE,
    ) -> None:
        from mutagen.mp3 import MP3  # noqa: F401  (validated at import, used in _validate)
        from spotdl.download.downloader import Downloader
        from spotdl.utils.config import get_temp_path

        self.output_dir = Path(output_dir).expanduser().resolve()
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.bitrate = _normalize_bitrate(bitrate)
        self.output_template = _normalize_template(output_template)
        self.max_filename_length = max_filename_length
        self.temp_sweep_age = temp_sweep_age
        self.duration_tolerance = duration_tolerance
        self.ffmpeg = ffmpeg

        if ffmpeg and shutil.which(ffmpeg) is None and not Path(ffmpeg).is_file():
            raise TrackDownloadError(
                ErrorKind.DEPENDENCY,
                f"ffmpeg not found on PATH ('{ffmpeg}'); the bundled binary must be "
                "exposed, e.g. by prepending its directory to PATH",
            )

        settings: Dict[str, Any] = {
            "output": self._template_joined(),
            "format": "mp3",
            "bitrate": self.bitrate,
            "overwrite": "skip",
            "scan_for_songs": False,
            "audio_providers": audio_providers or ["youtube-music", "youtube"],
            "lyrics_providers": lyrics_providers or ["synced"],
            "ffmpeg": ffmpeg,
            "threads": 1,
            "filter_results": True,
            "simple_tui": True,  # no Rich TUI — headless Android
            "print_errors": False,
            "log_level": "WARNING",
            "generate_lrc": False,
            "sponsor_block": False,
            "create_skip_file": False,
            "respect_skip_file": False,
            "restrict": None,
            "max_filename_length": max_filename_length,
        }
        try:
            _ensure_spotify_client()
            self._downloader = Downloader(settings)
        except Exception as exc:  # noqa: BLE001 - typed regardless of cause
            raise TrackDownloadError(
                _kind_for(exc), f"Could not initialise spotdl downloader: {exc}"
            ) from exc

        # Bridge spotdl's per-event tracker into our own on_progress hook.
        self._downloader.progress_handler.update_callback = self._on_spotdl_progress

        self._temp_dir = Path(get_temp_path())
        self._sidecar_index: Optional[Dict[str, Dict[str, Any]]] = None
        self._active_progress: Optional[Callable] = None

        # Hard-kill orphans (a prior download that never got to clean up).
        self.cleanup_temp()

    def _search(self, url: str) -> List[Any]:
        """Resolve *url* into fully-populated Song objects (Spotify metadata)."""
        from spotdl.utils.search import parse_query

        return parse_query(
            query=[url],
            threads=self._downloader.settings["threads"],
            use_ytm_data=self._downloader.settings["ytm_data"],
            playlist_numbering=self._downloader.settings["playlist_numbering"],
            album_type=self._downloader.settings["album_type"],
            playlist_retain_track_cover=self._downloader.settings[
                "playlist_retain_track_cover"
            ],
        )

    def _template_joined(self) -> str:
        template = self.output_template
        if not template.endswith(".{output-ext}"):
            for ext in (".mp3", ".m4a", ".flac", ".opus", ".wav"):
                if template.endswith(ext):
                    template = template[: -len(ext)]
                    break
            template += ".{output-ext}"
        return str(self.output_dir / template)

    def _output_path_for_song(self, song: Any) -> Path:
        """Compute the exact path spotdl will write for *song*."""
        from spotdl.utils.formatter import create_file_name

        try:
            return create_file_name(
                song=song,
                template=self._template_joined(),
                file_extension="mp3",
                restrict=None,
                file_name_length=self.max_filename_length,
            )
        except ValueError:
            title = sanitize_filename((song.name or "song")[:60])
            artist = sanitize_filename((song.artist or "artist")[:40])
            return self.output_dir / f"{artist} - {title}.mp3"

    def _build_sidecar_index(self) -> Dict[str, Dict[str, Any]]:
        index: Dict[str, Dict[str, Any]] = {}
        for sidecar in _iter_sidecars(self.output_dir):
            try:
                info = json.loads(sidecar.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            entry = {"path": sidecar, "file": self.output_dir / info.get("file", "")}
            if info.get("spotify_id"):
                index.setdefault(info["spotify_id"], entry)
            if info.get("url"):
                index.setdefault(info["url"], entry)
        return index

    def _find_sidecar(
        self, spotify_id: Optional[str], url: str
    ) -> Optional[Tuple[Path, Path]]:
        """Return ``(mp3, sidecar)`` for an already-downloaded track, else None."""
        if self._sidecar_index is None:
            self._sidecar_index = self._build_sidecar_index()
        index = self._sidecar_index

        def lookup() -> Optional[Tuple[Path, Path]]:
            for key in (spotify_id, url):
                if not key:
                    continue
                entry = index.get(key)
                if entry is not None and entry["file"].exists():
                    return entry["file"], entry["path"]
            return None

        hit = lookup()
        if hit is not None:
            return hit
        # Possible stale index (sidecar deleted externally). Rebuild once.
        if any(not e["file"].exists() for e in index.values()):
            self._sidecar_index = index = self._build_sidecar_index()
            hit = lookup()
            if hit is not None:
                return hit
        return None

    def _write_sidecar(self, song: Any, mp3: Path) -> None:
        info: Dict[str, Any] = {
            "schema": SIDECAR_SCHEMA,
            "spotify_id": song.song_id,
            "url": song.url,
            "title": song.name,
            "artists": list(song.artists or []),
            "album": song.album_name,
            "bitrate": self.bitrate,
            "downloaded_at": int(time.time()),
            "file": mp3.name,
        }
        sidecar = mp3.with_name(mp3.name + SIDECAR_EXT)
        tmp = sidecar.with_name("." + sidecar.name + ".tmp")
        tmp.write_text(json.dumps(info, ensure_ascii=True, indent=2), encoding="utf-8")
        os.replace(tmp, sidecar)
        self._sidecar_index = None  # see the fresh entry next time

    def _snapshot_temp(self) -> set:
        if not self._temp_dir.is_dir():
            return set()
        try:
            return set(self._temp_dir.iterdir())
        except OSError:
            return set()

    def _cleanup_attempt(self, before: set) -> None:
        """Remove temp files created by the attempt that just finished."""
        if not self._temp_dir.is_dir():
            return
        try:
            for path in self._temp_dir.iterdir():
                if path not in before:
                    try:
                        path.unlink()
                    except OSError:
                        pass
        except OSError:
            pass

    def cleanup_temp(self, age_seconds: Optional[float] = None) -> int:
        """Delete orphaned temp files left by killed/interrupted downloads."""
        threshold = time.time() - (
            age_seconds if age_seconds is not None else self.temp_sweep_age
        )
        removed = 0
        if not self._temp_dir.is_dir():
            return 0
        for path in self._temp_dir.iterdir():
            try:
                if path.is_file() and path.stat().st_mtime < threshold:
                    path.unlink()
                    removed += 1
            except OSError:
                continue
        return removed

    def _on_spotdl_progress(self, tracker: Any, message: str) -> None:
        """Relay spotdl's per-event tracker into the caller's on_progress hook."""
        hook = self._active_progress
        if hook is None:
            return
        try:
            percent = min(100, max(0, int(getattr(tracker, "progress", 0) or 0)))
        except (TypeError, ValueError):
            percent = 0
        _fire(hook, percent, message or "")

    def download(
        self,
        url: str,
        on_start: Any = None,
        on_complete: Any = None,
        on_progress: Any = None,
    ) -> Dict[str, Any]:
        """Download one track (see component TESTS.md T-030..T-041)."""
        if not isinstance(url, str) or not url.strip():
            raise TrackDownloadError(ErrorKind.NO_TRACK, "Empty track URL")
        url = url.strip()

        if _is_spotify_url(url) and extract_spotify_id(url) is None:
            raise TrackDownloadError(
                ErrorKind.NO_TRACK,
                f"Expected a single Spotify track URL/URI, got: {url}",
            )

        on_start = _as_callable(on_start, _START_NAMES)
        on_complete = _as_callable(on_complete, _COMPLETE_NAMES)
        self._active_progress = _as_callable(on_progress, _PROGRESS_NAMES)

        def result(
            status: str,
            output_path: Optional[str] = None,
            song: Optional[Any] = None,
            track: Optional[Dict[str, Any]] = None,
            error: Optional[str] = None,
            kind: Optional[str] = None,
        ) -> Dict[str, Any]:
            payload: Dict[str, Any] = {"status": status, "url": url}
            payload.update(_track_dict(song) if song is not None else (track or {}))
            payload["output_path"] = output_path
            payload["bitrate"] = self.bitrate
            if output_path and os.path.exists(output_path):
                try:
                    payload["file_size"] = os.path.getsize(output_path)
                except OSError:
                    payload["file_size"] = None
            else:
                payload["file_size"] = None
            payload["error_type"] = kind
            payload["error"] = error
            return payload

        # 1) Sidecar fast path — zero network.
        spotify_id = extract_spotify_id(url)
        hit = self._find_sidecar(spotify_id, url)
        if hit is not None:
            mp3, sidecar = hit
            try:
                info = json.loads(sidecar.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                info = {}
            _fire(on_start, info or {"url": url})
            _fire(on_complete, result("SKIPPED", str(mp3), track=info))
            return result("SKIPPED", str(mp3), track=info)

        # 2) Resolve metadata (network — Spotify only).
        try:
            songs = self._search(url)
        except Exception as exc:  # noqa: BLE001 - typed regardless of cause
            raise TrackDownloadError(
                _kind_for(exc), f"Could not resolve track metadata for {url}: {exc}"
            ) from exc
        if not songs:
            raise TrackDownloadError(ErrorKind.NO_TRACK, f"No track found for: {url}")
        song = songs[0]

        # 3) Canonical identity re-check now that we know the real URL.
        hit = self._find_sidecar(song.song_id, song.url)
        if hit is not None:
            mp3, sidecar = hit
            if mp3.exists():
                try:
                    info = json.loads(sidecar.read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    info = {}
                _fire(on_start, _track_dict(song))
                _fire(on_complete, result("SKIPPED", str(mp3), song=song, track=info))
                return result("SKIPPED", str(mp3), song=song, track=info)

        expected = self._output_path_for_song(song)
        existed_before = expected.exists()

        _fire(self._active_progress, 0, "Preparing")
        _fire(on_start, _track_dict(song))

        before = self._snapshot_temp()
        error_count = len(self._downloader.errors)
        path: Optional[Path] = None
        try:
            _song, path = self._downloader.search_and_download(song)
        except BaseException as exc:  # noqa: BLE001 - KeyboardInterrupt etc.
            self._cleanup_attempt(before)
            if isinstance(exc, TrackDownloadError):
                raise
            raise TrackDownloadError(_kind_for(exc), str(exc)) from exc
        finally:
            self._active_progress = None

        if path is None:
            self._cleanup_attempt(before)
            new_errors = self._downloader.errors[error_count:]
            message = new_errors[-1] if new_errors else "spotdl returned no result"
            kind = _kind_from_error_text(message)
            self._remove_failed_artifact(expected, existed_before)
            raise TrackDownloadError(kind, message)

        path = Path(path)

        # 5) Integrity validation (parses as MP3, tagged, plausible duration).
        warnings = self._validate(path, song)
        logger.info("Downloaded %s with warnings: %s", path, warnings or "none")

        # 6) Persist the sidecar so the next request skips without network.
        self._write_sidecar(song, path)

        # 7) Download complete.
        _fire(on_complete, result("DOWNLOADED", str(path), song=song))
        return result("DOWNLOADED", str(path), song=song)

    def _remove_failed_artifact(
        self, path: Optional[Path], existed_before: bool
    ) -> None:
        """Remove a corrupt/partial output file without touching a pre-existing one."""
        if path is None:
            return
        try:
            if not path.exists():
                return
            if existed_before and path.stat().st_size > 0:
                return  # a real, pre-existing file — leave it alone
            path.unlink()
            sidecar = path.with_name(path.name + SIDECAR_EXT)
            if sidecar.exists():
                sidecar.unlink()
        except OSError:
            pass

    def _validate(self, path: Path, song: Optional[Any]) -> List[str]:
        """Return validation warnings, raising VALIDATION_FAILED on fatal issues."""
        from mutagen.mp3 import MP3

        try:
            audio = MP3(str(path))
        except Exception as exc:  # noqa: BLE001 - not a readable MP3
            self._remove_failed_artifact(path, existed_before=False)
            raise TrackDownloadError(
                ErrorKind.VALIDATION_FAILED,
                f"Downloaded file is not a readable MP3: {exc}",
            ) from exc

        if audio.info is None or audio.info.length <= 0:
            self._remove_failed_artifact(path, existed_before=False)
            raise TrackDownloadError(
                ErrorKind.VALIDATION_FAILED, "Downloaded file has no audio stream"
            )

        if song is not None and getattr(song, "duration", None):
            expected_seconds = float(song.duration)
            if abs(audio.info.length - expected_seconds) > self.duration_tolerance:
                self._remove_failed_artifact(path, existed_before=False)
                raise TrackDownloadError(
                    ErrorKind.VALIDATION_FAILED,
                    f"Truncated download: file plays {audio.info.length:.1f}s but "
                    f"track is {expected_seconds:.1f}s",
                )
            try:
                actual_bytes = path.stat().st_size
                expected_bytes = int(
                    expected_seconds * int(self.bitrate[:-1]) * 1000 / 8
                )
                if expected_bytes > 0 and actual_bytes < expected_bytes * 0.6:
                    self._remove_failed_artifact(path, existed_before=False)
                    raise TrackDownloadError(
                        ErrorKind.VALIDATION_FAILED,
                        f"Truncated download: file is {actual_bytes} bytes but "
                        f"a {expected_seconds:.0f}s track at {self.bitrate} "
                        f"should be about {expected_bytes} bytes",
                    )
            except OSError:
                pass

        tags = audio.tags
        warnings: List[str] = []
        if tags is None:
            self._remove_failed_artifact(path, existed_before=False)
            raise TrackDownloadError(
                ErrorKind.VALIDATION_FAILED, "Downloaded MP3 has no ID3 tags"
            )
        if not tags.get("TIT2"):
            self._remove_failed_artifact(path, existed_before=False)
            raise TrackDownloadError(
                ErrorKind.VALIDATION_FAILED, "Downloaded MP3 is missing its title tag"
            )
        if not tags.get("TPE1"):
            self._remove_failed_artifact(path, existed_before=False)
            raise TrackDownloadError(
                ErrorKind.VALIDATION_FAILED, "Downloaded MP3 is missing its artist tag"
            )
        if not tags.get("TALB"):
            warnings.append("album tag missing")
        if not any(key.startswith("APIC") for key in tags.keys()):
            if song is not None and getattr(song, "cover_url", None):
                warnings.append("cover art missing")
        return warnings

    def expected_output_path(self, url: str) -> Optional[str]:
        """Return the path this track would be written to, or None if unknown."""
        if not isinstance(url, str) or not url.strip():
            return None
        hit = self._find_sidecar(extract_spotify_id(url), url.strip())
        if hit is not None:
            return str(hit[0])
        try:
            songs = self._search(url.strip())
        except Exception:  # noqa: BLE001 - best effort, caller may retry
            return None
        if not songs:
            return None
        return str(self._output_path_for_song(songs[0]))


def _track_dict(song: Any) -> Dict[str, Any]:
    return {
        "url": song.url,
        "spotify_id": song.song_id,
        "title": song.name,
        "artists": ", ".join(song.artists or []),
        "album": song.album_name,
        "duration_ms": (song.duration * 1000) if song.duration else None,
    }


# --------------------------------------------------------------------------- #
# Module-level convenience API (what Kotlin/Chaquopy calls).
# --------------------------------------------------------------------------- #


def make_downloader(
    output_dir: Union[str, os.PathLike],
    bitrate: Union[int, str] = ALLOWED_BITRATES[-1],
    output_template: str = DEFAULT_TEMPLATE,
    ffmpeg: str = "ffmpeg",
    audio_providers: Optional[List[str]] = None,
    lyrics_providers: Optional[List[str]] = None,
    **kwargs: Any,
) -> TrackDownloader:
    """Build a :class:`TrackDownloader` (positional args for the Kotlin bridge)."""
    return TrackDownloader(
        output_dir=output_dir,
        bitrate=bitrate,
        output_template=output_template,
        ffmpeg=ffmpeg,
        audio_providers=audio_providers,
        lyrics_providers=lyrics_providers,
        **kwargs,
    )


def download(
    downloader: TrackDownloader,
    url: str,
    on_start: Any = None,
    on_complete: Any = None,
    on_progress: Any = None,
) -> Dict[str, Any]:
    """Download one track via an existing :class:`TrackDownloader`."""
    if not isinstance(downloader, TrackDownloader):
        raise TypeError(
            "downloader must be a TrackDownloader (create it with make_downloader)"
        )
    return downloader.download(
        url, on_start=on_start, on_complete=on_complete, on_progress=on_progress
    )


def expected_output_path(downloader: TrackDownloader, url: str) -> Optional[str]:
    """Return the path *url* would be written to (see TrackDownloader)."""
    if not isinstance(downloader, TrackDownloader):
        raise TypeError(
            "downloader must be a TrackDownloader (create it with make_downloader)"
        )
    return downloader.expected_output_path(url)


def cleanup_temp(
    downloader: TrackDownloader, age_seconds: Optional[float] = None
) -> int:
    """Delete orphaned temp files left by interrupted downloads."""
    if not isinstance(downloader, TrackDownloader):
        raise TypeError(
            "downloader must be a TrackDownloader (create it with make_downloader)"
        )
    return downloader.cleanup_temp(age_seconds)

"""ghostify_dl.py - Ghostify Spotify playlist metadata bridge (spotdl wrapper).

Fetches the full metadata of a public Spotify playlist through spotdl's
``SpotifyClient`` (anonymous client - no account, no credentials) and returns a
JSON-safe, ordered, duplicate-preserving representation:

    {
        "name":         str,
        "owner":        str,
        "cover_url":    str | None,
        "description":  str | None,
        "track_count":  int,
        "tracks": [
            {
                "position":     int,   # 0-based index in the playlist
                "spotify_id":   str,
                "title":        str,
                "artists":      str,   # "Artist A, Artist B"
                "album":        str,
                "duration_ms":  int,
                "cover_url":    str | None,
                "yt_id":        str | None,  # None when unresolved/not found
            }, ...
        ],
    }

Error contract
--------------
Every failure is surfaced as a :class:`GhostifyError` whose ``str()`` is
``"<CODE>: <human message>"`` with ``CODE`` in
``NO_NETWORK | RATE_LIMITED | PRIVATE | TIMEOUT | NOT_FOUND | UNKNOWN``.
The Kotlin bridge parses that leading token, so Python never leaks a raw,
untyped exception across the Chaquopy boundary (T-021).

Design notes
------------
* The whole fetch runs inside a caller thread guarded by a wall-clock
  deadline (daemon worker thread). spotdl's HTTP stack does not expose reliable
  per-request timeouts, so the deadline is the authoritative "abort within N
  seconds" mechanism (T-019).
* When the anonymous Spotify endpoint reports an unresolvable playlist
  (private or deleted), spotdl's formatter collapses the GraphQL error into a
  bare ``KeyError``. We issue a single diagnostic probe to read the raw
  GraphQL error message and classify it as PRIVATE or NOT_FOUND (T-015, T-018).
* spotdl 4.5.x's anonymous *track* endpoint returns empty album/cover objects
  (``album: {}``). Because the room schema (PROJECT.md §4) and T-012 require
  ``album`` and per-track ``cover_url``, a best-effort enrichment pass pulls the
  paginated GraphQL payload once (via spotapi, which spotdl's anonymous client
  wraps anyway) and fills the missing fields. Enrichment failures degrade to
  empty strings - never a whole-fetch failure.
* YouTube-id resolution is best-effort and bounded-parallel: a missing match,
  a timeout, or any per-track failure yields ``yt_id=None`` for that track only
  - never a whole fetch failure (T-020).
* All spotdl imports are deferred so that importing this module is cheap
  (Chaquopy interpreter boot does not stall on the full spotdl dependency
  tree).
"""

from __future__ import annotations

import logging
import re
import threading
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Callable, Dict, Optional

logger = logging.getLogger("ghostify_dl")

# ---------------------------------------------------------------------------
# Typed error model (stable wire contract with the Kotlin bridge).
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
# Defaults.
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


# ---------------------------------------------------------------------------
# Option handling.
# ---------------------------------------------------------------------------


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


# ---------------------------------------------------------------------------
# Input handling.
# ---------------------------------------------------------------------------


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


# ---------------------------------------------------------------------------
# Client bootstrap (lazy, idempotent).
# ---------------------------------------------------------------------------


def _ensure_spotify_client(client_id: str, client_secret: str) -> None:
    global _client_initialized
    if _client_initialized:
        return
    with _client_lock:
        if _client_initialized:
            return
        # The default (anonymous) Spotify client requires no credentials;
        # empty strings are accepted and forwarded as (ignored) options.
        from spotdl import SpotifyClient

        SpotifyClient.init(
            client_id=client_id,
            client_secret=client_secret,
            no_cache=True,
        )
        _client_initialized = True


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
# Core fetch.
# ---------------------------------------------------------------------------


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


# ---------------------------------------------------------------------------
# Album/cover enrichment (spotdl 4.5.x anonymous track payload omits them).
# ---------------------------------------------------------------------------


def _fetch_album_enrichment(playlist_id: str) -> Dict[str, Any]:
    """Map ``spotify_id -> (album_name, album_cover_url)`` for the playlist.

    Pulls the same GraphQL data spotdl's anonymous client is built on
    (``spotapi.paginate_playlist``) so a large playlist needs only a handful of
    requests. Strictly best-effort: any failure returns ``{}`` and never raises.
    """
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


# ---------------------------------------------------------------------------
# YouTube-id resolution (best-effort, per-track fault isolation, T-020).
# ---------------------------------------------------------------------------


def _resolve_yt_ids(songs: Any, resolve_yt: bool, per_track_timeout: float) -> list:
    """Resolve YouTube ids for every song.

    Runs bounded-parallel across the track list (one provider per worker so
    the shared YTMusic session is never touched from two threads), so large
    playlists finish in a fraction of the wall-clock time a serial loop would
    take. Every per-track failure yields ``None`` for that track only.
    """
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

    # Wait for completion; if the outer deadline already fired, our caller
    # thread was detached and we just let the daemon workers finish in the
    # background. Never block indefinitely here.
    done.wait()

    return [results.get(i) for i in range(count)]


def _get_yt_provider() -> Any:
    """Thread-local YouTubeMusic provider.

    The outer module-level singleton is kept for the serial case; the
    concurrent path uses one provider per worker thread because ytmusicapi's
    session object is not safe to share across threads.
    """
    provider = getattr(_worker_local, "provider", None)
    if provider is not None:
        return provider
    from spotdl.providers.audio.ytmusic import YouTubeMusic

    provider = YouTubeMusic()
    _worker_local.provider = provider
    return provider


def _resolve_yt_id(song: Any, per_track_timeout: float) -> Optional[str]:
    """Resolve a YouTube id for ``song`` or return None on any failure.

    Mirrors spotdl's default audio provider (YouTube Music,
    ``filter="songs"``). The top relevance result is taken. Every failure mode
    (no match, timeout, provider error) yields ``None`` so a single track can
    never sink the whole fetch.
    """
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


# ---------------------------------------------------------------------------
# Error classification.
# ---------------------------------------------------------------------------


def _classify_error(exc: BaseException) -> GhostifyError:
    """Map an arbitrary exception to a typed GhostifyError.

    Signals are gathered from the whole exception chain plus the ``error``
    attribute that spotapi attaches to its parent exceptions.
    """
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
    """Safely pull a human-readable message from a GraphQL payload.

    The anonymous API may place the message on ``playlistV2.message`` (a str or
    a mapping such as ``{"message": "...", "code": ...}``). Handle both shapes
    defensively.
    """
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

"""Ghostify — single-track download bridge.

Wraps spotdl's *programmatic* API (not its CLI) so that one track URL can be
downloaded to a tagged MP3 with embedded cover art, at the configured bitrate,
under the configured output template ``{artists} - {title}``, with:

* **Deterministic skip behaviour.** After every successful download a JSON
  sidecar (``<output>.spotdl``, following spotdl's own ``.skip`` convention)
  records the canonical identity of the track. On a later request for the same
  identity the wrapper returns ``SKIPPED`` *without any network traffic*;
  spotdl's ``overwrite="skip"`` is kept as a second layer of defence for files
  that predate the sidecar.
* **Temp-file cleanup.** spotdl renders every download through a shared temp
  directory (``get_temp_path``). We sweep stale (orphaned) files at
  construction and remove exactly the files created by each attempt once it
  finishes, so an interrupted download never leaves junk behind and always
  retries cleanly.
* **Progress hooks.** ``on_start`` / ``on_progress`` / ``on_complete`` fire for
  every track — including the skip path. Each hook may be a plain Python
  callable or a Java/Kotlin object exposed through Chaquopy (its method is
  resolved by name). A raising hook never fails the download.
* **Post-download integrity validation.** The file must parse as MP3 audio,
  carry title/artist ID3 tags and have a duration consistent with the source
  metadata (catches truncation / corruption). Album tag and cover art are
  validated and reported as warnings rather than hard failures, because a track
  whose source metadata lacks them is still a valid download.
* **Typed failures.** Every failure surfaces as :class:`TrackDownloadError`
  with a stable ``kind`` (e.g. ``"SEARCH_FAILED"``) that the Kotlin layer maps
  to user-facing messages — never a raw exception text crawl.

The module is designed to run identically under Chaquopy on Android and under
plain CPython (the local test-suite in ``tests/python`` exercises this exact
file). A :class:`TrackDownloader` instance is *not* thread-safe: create one per
worker and drive it from a single thread. The Kotlin wrapper enforces this by
running all calls on a single-thread dispatcher.
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import time
import unicodedata
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple, Union

from mutagen.mp3 import MP3
from spotdl.download.downloader import Downloader
from spotdl.types.song import Song
from spotdl.utils.config import get_temp_path
from spotdl.utils.formatter import create_file_name
from spotdl.utils.search import parse_query
from spotdl.utils.spotify import SpotifyClient, SpotifyError

__all__ = [
    "TrackDownloader",
    "TrackDownloadError",
    "make_downloader",
    "download",
    "expected_output_path",
    "sanitize_filename",
    "cleanup_temp",
    "extract_spotify_id",
    "DEFAULT_TEMPLATE",
    "ALLOWED_BITRATES",
]

_logger = logging.getLogger("ghostify_dl")

DEFAULT_TEMPLATE = "{artists} - {title}"
ALLOWED_BITRATES = (128, 192, 320)

# Sidecar: a JSON metadata file named ``<output>.spotdl`` sitting next to the
# MP3 it describes. This is the "already downloaded" marker the component relies
# on (T-035), written atomically so a crash can never leave a half-written one.
SIDECAR_EXT = ".spotdl"
SIDECAR_SCHEMA = 1

# Orphans in spotdl's temp dir older than this are safe to delete — a download
# in progress is never this old, so the sweep cannot race a concurrent worker.
DEFAULT_TEMP_SWEEP_AGE = 3600.0  # seconds

# Allowable mismatch between the downloaded file's duration and the track's
# Spotify duration before we declare the file truncated (T-041).
DEFAULT_DURATION_TOLERANCE = 15.0  # seconds


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
        # ``kind`` first, then ``message``: ``str(exc)`` is "<kind>: <message>"
        # so the Kotlin bridge can recover the stable `ErrorKind` token from the
        # Chaquopy exception text (it prefixes the Python exception type name),
        # mirroring `ghostify_dl.GhostifyError`'s "<CODE>: <msg>" contract.
        # ``self.message`` keeps the *raw* human message for [to_dict] and tests.
        self.kind = kind
        self.message = message
        super().__init__(f"{kind}: {message}")

    def to_dict(self) -> Dict[str, Any]:
        return {"error_type": self.kind, "error": self.message}


# --------------------------------------------------------------------------- #
# Small pure helpers (unit-testable without network)
# --------------------------------------------------------------------------- #


def extract_spotify_id(url: Any) -> Optional[str]:
    """Return the 22-char Spotify track id for a track URL/URI, else None."""
    if not isinstance(url, str):
        return None
    match = re.search(
        r"(?:open\.spotify\.com/track/|spotify:track[:/])([A-Za-z0-9]{22})", url
    )
    return match.group(1) if match else None


def _normalize_spotify_url(url: str) -> str:
    """Convert a ``spotify:track:`` URI to an HTTPS URL.

    spotdl 4.5.2's ``parse_query`` resolves wrong metadata when given the
    ``spotify:track:`` URI format (it silently falls back to unrelated songs).
    The ``https://open.spotify.com/track/`` format works correctly.
    """
    spotify_id = extract_spotify_id(url)
    if spotify_id and url.startswith("spotify:"):
        return f"https://open.spotify.com/track/{spotify_id}"
    return url


def _is_spotify_url(url: str) -> bool:
    return "open.spotify.com" in url or url.startswith("spotify:")


def sanitize_filename(name: str, separator: str = "-") -> str:
    """Return a filesystem-safe file *name* (no path separators).

    Rules mirror spotdl's own sanitisation so that filename computation stays
    consistent between this module and spotdl itself:

    * ``/`` and ``\\`` are replaced with *separator* (they are separators);
    * ``? * | < >`` are removed;
    * ``"`` becomes ``'`` and ``:`` becomes ``-``;
    * runs of 2+ spaces collapse to a single space, edges are trimmed;
    * trailing dots/spaces are removed (Windows-style hygiene);
    * NFC normalised first so multi-codepoint glyphs never fragment on disk.
    """
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


# --------------------------------------------------------------------------- #
# Failure mapping
# --------------------------------------------------------------------------- #

_KNOWN_EXCEPTION_KINDS = {
    "QueryError": ErrorKind.NO_TRACK,
    "SongError": ErrorKind.NO_TRACK,
    "SongNotFoundError": ErrorKind.SEARCH_FAILED,
    "SpotifyError": ErrorKind.METADATA_FAILED,
    "DownloaderError": ErrorKind.SEARCH_FAILED,
    "AudioProviderError": ErrorKind.AUDIO_UNAVAILABLE,
    "LookupError": ErrorKind.SEARCH_FAILED,
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
    # yt-dlp / YouTube download errors: video unavailable, age-restricted,
    # geo-blocked, 403 forbidden, or a missing JS runtime (Deno/Node.js).
    if (
        "yt-dlp download error" in lowered
        or "http error 403" in lowered
        or "this video is not available" in lowered
        or "age-restricted" in lowered
        or "sign in to confirm" in lowered
        or "region-blocked" in lowered
        or "no supported javascript runtime" in lowered
        or "require deno" in lowered
    ):
        return ErrorKind.AUDIO_UNAVAILABLE
    return ErrorKind.UNKNOWN


# --------------------------------------------------------------------------- #
# Hook plumbing
# --------------------------------------------------------------------------- #

# Candidate method names for each hook, so the same API works with plain Python
# callables and with Java/Kotlin objects passed through Chaquopy.
_START_NAMES = ("on_download_start", "onDownloadStart", "onStart")
_PROGRESS_NAMES = ("on_progress", "onProgress")
_COMPLETE_NAMES = ("on_download_complete", "onDownloadComplete", "onComplete")


def _as_callable(hook: Any, method_names: Tuple[str, ...]) -> Optional[Callable]:
    if hook is None:
        return None
    # Prefer an explicitly-named callback method. For a Chaquopy Java/Kotlin
    # proxy object `callable(hook)` is True (it implements __call__ for SAM
    # dispatch), but calling the object directly fails unless it is a functional
    # interface — our multi-method HookAdapter is not. Resolving the named
    # method dispatches to the concrete Java method instead.
    for name in method_names:
        method = getattr(hook, name, None)
        if method is not None and callable(method):
            return method
    if callable(hook):
        return hook
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
        _logger.exception("Ignoring failure of progress hook %r", hook)


# --------------------------------------------------------------------------- #
# Shared Spotify client
# --------------------------------------------------------------------------- #
#
# spotdl's SpotifyClient.init() refuses to run more than once per process, yet
# a long-lived app legitimately builds several downloaders (per playlist, after
# a settings change, ...). The component therefore constructs spotdl's
# ``Downloader`` directly and initialises the shared client exactly once.

_spotify_client_ready = False


def _ensure_spotify_client() -> None:
    """Initialise spotdl's global Spotify client exactly once, if needed.

    Public Spotify metadata endpoints need no credentials (this is the app's v1
    "public playlists only" model). Safe to call from any number of downloaders
    and from other Ghostify components that share the client.
    """
    global _spotify_client_ready
    if _spotify_client_ready:
        return
    try:
        SpotifyClient()
    except SpotifyError:
        try:
            SpotifyClient.init(client_id=None, client_secret=None)
        except SpotifyError:
            # Lost an init race with a concurrent component; the client must
            # exist now, otherwise re-raise below.
            SpotifyClient()
    _spotify_client_ready = True


# --------------------------------------------------------------------------- #
# TrackDownloader
# --------------------------------------------------------------------------- #


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
        yt_dlp_args: Optional[str] = None,
        max_filename_length: int = 255,
        temp_sweep_age: float = DEFAULT_TEMP_SWEEP_AGE,
        duration_tolerance: float = DEFAULT_DURATION_TOLERANCE,
    ) -> None:
        self.output_dir = Path(output_dir).expanduser().resolve()
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.bitrate = _normalize_bitrate(bitrate)
        self.output_template = _normalize_template(output_template)
        self.max_filename_length = max_filename_length
        self.temp_sweep_age = temp_sweep_age
        self.duration_tolerance = duration_tolerance
        self.ffmpeg = ffmpeg

        # Fail fast with a typed error if ffmpeg will not be resolvable.
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
            "yt_dlp_args": yt_dlp_args
            or "--extractor-args youtube:player_client=android",
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
        # One shared index of every sidecar under the output dir; built lazily
        # and invalidated on our own writes. Entries are re-verified against the
        # filesystem on use, so external deletions never produce a false skip.
        self._sidecar_index: Optional[Dict[str, Dict[str, Any]]] = None
        self._active_progress: Optional[Callable] = None

        # Hard-kill orphans (a prior download that never got to clean up).
        self.cleanup_temp()

    # -- configuration ------------------------------------------------------ #

    def _search(self, url: str) -> List[Song]:
        """Resolve *url* into fully-populated Song objects (Spotify metadata)."""
        return parse_query(
            query=[_normalize_spotify_url(url)],
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

    def _output_path_for_song(self, song: Song) -> Path:
        """Compute the exact path spotdl will write for *song*.

        Uses spotdl's own formatter with the same arguments spotdl uses
        internally, so the result matches the file on disk. If even spotdl's
        short fallback refuses the name (pathologically long artist/title), a
        deterministic, safely-truncated fallback is returned instead of letting
        the download abort with a raw ValueError.
        """
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

    # -- sidecar index ------------------------------------------------------ #

    def _build_sidecar_index(self) -> Dict[str, Dict[str, Any]]:
        index: Dict[str, Dict[str, Any]] = {}
        for sidecar in _iter_sidecars(self.output_dir):
            try:
                info = json.loads(sidecar.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                # Corrupt/stale sidecar: ignore it. spotdl's own file-exists
                # check still protects the file underneath.
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
        """Return ``(mp3, sidecar)`` for an already-downloaded track, else None.

        Uses the cached index; if the MP3 referenced by a hit is gone the entry
        is stale and the index is rebuilt once before re-checking.
        """
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

    def _write_sidecar(self, song: Song, mp3: Path) -> None:
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

    # -- temp management ---------------------------------------------------- #

    def _snapshot_temp(self) -> set:
        if not self._temp_dir.is_dir():
            return set()
        try:
            return set(self._temp_dir.iterdir())
        except OSError:
            return set()

    def _cleanup_attempt(self, before: set) -> None:
        """Remove temp files created by the attempt that just finished.

        Only files that did not exist before the attempt are touched, so in the
        component's serialised single-track usage nothing else can be affected.
        """
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
        """Delete orphaned temp files left by killed/interrupted downloads.

        Files younger than ``age_seconds`` (default ``temp_sweep_age``) are kept:
        they may belong to an in-flight download. Returns the number removed.
        """
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

    # -- progress ------------------------------------------------------------ #

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

    # -- main entry point ---------------------------------------------------- #

    def download(
        self,
        url: str,
        on_start: Any = None,
        on_complete: Any = None,
        on_progress: Any = None,
    ) -> Dict[str, Any]:
        """Download one track.

        :param url: a Spotify track URL/URI, or a bare search query.
        :param on_start: callable(result-ish dict) fired before downloading.
        :param on_complete: callable(result dict) fired when finished/skipped.
        :param on_progress: callable(percent, message) fired on spotdl events.

        Returns a dict with ``status`` ``"DOWNLOADED"`` | ``"SKIPPED"`` and the
        output path, or raises :class:`TrackDownloadError`.
        """
        if not isinstance(url, str) or not url.strip():
            raise TrackDownloadError(ErrorKind.NO_TRACK, "Empty track URL")
        url = url.strip()

        # Only accept single-track inputs. A playlist/album URL is a caller bug,
        # not something this component should silently half-handle.
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
            song: Optional[Song] = None,
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

        # 4) Download starting.
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
        _logger.info("Downloaded %s with warnings: %s", path, warnings or "none")

        # 6) Persist the sidecar so the next request skips without network.
        self._write_sidecar(song, path)

        # 7) Download complete.
        _fire(on_complete, result("DOWNLOADED", str(path), song=song))
        return result("DOWNLOADED", str(path), song=song)

    # -- validation ---------------------------------------------------------- #

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

    def _validate(self, path: Path, song: Optional[Song]) -> List[str]:
        """Return validation warnings, raising VALIDATION_FAILED on fatal issues."""
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

        # Truncation detection (best effort — two independent signals):
        #  * duration vs the track's Spotify duration, and
        #  * file size vs the size a full CBR MP3 at the configured bitrate
        #    should have (catches files whose Xing header hides the real length).
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

    # -- helpers ------------------------------------------------------------- #

    def expected_output_path(self, url: str) -> Optional[str]:
        """Return the path this track would be written to, or None if unknown.

        Uses the sidecar index first (no network); falls back to resolving the
        metadata and applying spotdl's filename formatter.
        """
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


def _track_dict(song: Song) -> Dict[str, Any]:
    return {
        "url": song.url,
        "spotify_id": song.song_id,
        "title": song.name,
        "artists": ", ".join(song.artists or []),
        "album": song.album_name,
        "duration_ms": (song.duration * 1000) if song.duration else None,
    }


# --------------------------------------------------------------------------- #
# Module-level convenience API (what Kotlin/Chaquopy calls)
# --------------------------------------------------------------------------- #


def make_downloader(
    output_dir: Union[str, os.PathLike],
    bitrate: Union[int, str] = ALLOWED_BITRATES[-1],
    output_template: str = DEFAULT_TEMPLATE,
    ffmpeg: str = "ffmpeg",
    audio_providers: Optional[List[str]] = None,
    lyrics_providers: Optional[List[str]] = None,
    yt_dlp_args: Optional[str] = None,
    **kwargs: Any,
) -> TrackDownloader:
    """Build a :class:`TrackDownloader`.

    Exposes the most common options as explicit parameters (in this order) so the
    Kotlin bridge can pass them *positionally* through Chaquopy — Chaquopy's
    keyword-argument support is awkward for cross-module calls, so positional
    marshalling is the most robust contract. Any remaining constructor options can
    still be supplied as keywords via ``**kwargs``.
    """
    return TrackDownloader(
        output_dir=output_dir,
        bitrate=bitrate,
        output_template=output_template,
        ffmpeg=ffmpeg,
        audio_providers=audio_providers,
        lyrics_providers=lyrics_providers,
        yt_dlp_args=yt_dlp_args,
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

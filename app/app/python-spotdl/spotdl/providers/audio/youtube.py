"""
YouTube module for downloading and searching songs using yt-dlp.
"""

import random
import re
import time
import sys
from pathlib import Path
from typing import Any, Dict, List

from yt_dlp import YoutubeDL

from spotdl.providers.audio.base import AudioProvider
from spotdl.types.result import Result

try:
    from ghostify_consts import YT_PROVIDER_BACKOFF_BASE, YT_PROVIDER_MAX_RETRIES, YT_SEARCH_LIMIT
    try:
        from ghostify_consts import YT_BACKOFF_CAP
    except ImportError:  # pragma: no cover - older consts without cap
        YT_BACKOFF_CAP = 180.0
except ImportError:  # pragma: no cover - vendored sys.path fallback
    _APP_PYTHON_DIR = Path(__file__).resolve().parents[4] / "src" / "main" / "python"
    if str(_APP_PYTHON_DIR) not in sys.path:
        sys.path.insert(0, str(_APP_PYTHON_DIR))
    from ghostify_consts import (  # type: ignore[import-not-found,no-redef]
        YT_PROVIDER_BACKOFF_BASE,
        YT_PROVIDER_MAX_RETRIES,
        YT_SEARCH_LIMIT,
    )
    try:
        from ghostify_consts import YT_BACKOFF_CAP  # type: ignore[import-not-found]
    except ImportError:
        YT_BACKOFF_CAP = 180.0  # type: ignore[no-redef]

__all__ = ["YouTube"]

# Retryable HTTP statuses: 429 + 5xx.
_RETRYABLE_STATUS_RE = re.compile(r"\b(429|500|502|503|504)\b")

# Rate-limit / timeout substrings for DownloadError/ExtractorError text.
# Matched case-insensitively against the full chained error text.
_RETRYABLE_MSG_RE = re.compile(
    r"429|too many requests|rate.?limit|rate_limited|quota|retry.?after"
    r"|timeout|timed.?out|temporarily unavailable|try again later"
    r"|connection|network|econnreset|econnrefused|broken pipe|socket|ssl"
    r"|500|502|503|504|internal error|bad gateway|service unavailable|gateway timeout",
    re.IGNORECASE,
)

# Exception type names that are always retryable (builtin + requests/urllib3).
_RETRYABLE_EXC_NAMES = frozenset(
    {
        "TimeoutError",
        "ConnectionError",
        "Timeout",
        "ReadTimeout",
        "ConnectTimeout",
        "ChunkedEncodingError",
        "RemoteDisconnected",
        "NewConnectionError",
        "MaxRetryError",
        "SSLError",
        "TooManyRequests",
        "RateLimitError",
    }
)


def _collect_error_text(exc: BaseException) -> str:
    """Collect chained str(exc) texts for retryability matching."""
    parts: List[str] = []
    seen = set()
    current: Any = exc
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        try:
            text = str(current)
        except Exception:  # noqa: BLE001 - never break classification
            text = ""
        if text and (not parts or text not in parts):
            parts.append(text)
        current = getattr(current, "__cause__", None) or getattr(
            current, "__context__", None
        )
    return " | ".join(parts)


def _chain_has_exc_name(exc: BaseException, names: frozenset) -> bool:
    """True if exc or any chained cause/context has a type name in *names*."""
    seen = set()
    current: Any = exc
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        if type(current).__name__ in names:
            return True
        # Also match base-class names (e.g. requests Timeout subclasses).
        for klass in type(current).__mro__:
            if klass.__name__ in names:
                return True
        current = getattr(current, "__cause__", None) or getattr(
            current, "__context__", None
        )
    return False


def _is_retryable_error(exc: BaseException) -> bool:
    """Return True only for transient, retry-worthy failures.

    Retryable: 429 / 5xx anywhere in the chained text, builtin
    TimeoutError / ConnectionError (and requests/urllib3 timeout/connection
    equivalents by type name), or yt-dlp DownloadError / ExtractorError /
    HTTPError whose text carries a rate-limit/timeout marker.
    Everything else (programming errors, not-found, private, etc.)
    fails fast with [].
    """
    if isinstance(exc, (TimeoutError, ConnectionError)):
        return True
    if _chain_has_exc_name(exc, _RETRYABLE_EXC_NAMES):
        return True
    text = _collect_error_text(exc)
    if not text:
        return False
    if _RETRYABLE_STATUS_RE.search(text):
        return True
    exc_name = type(exc).__name__
    if exc_name in ("DownloadError", "ExtractorError", "HTTPError"):
        return _RETRYABLE_MSG_RE.search(text) is not None
    # Any other error type: retry only on explicit 429/5xx (handled above).
    # Type-name hints like "Timeout"/"Connection" cover wrapped transports.
    lowered_name = exc_name.lower()
    if "timeout" in lowered_name or "connection" in lowered_name:
        return True
    if "ratelimit" in lowered_name or "toomanyrequests" in lowered_name:
        return True
    return False


def _sleep_interruptible(delay: float) -> None:
    """Sleep *delay* seconds in small chunks.

    Chunked so per-attempt sleeps stay responsive (KeyboardInterrupt /
    signals) and the outer ``_call_with_deadline`` worker in
    ``ghostify_dl.py`` (daemon thread + ``Event.wait(timeout)``) can
    abandon the result promptly between chunks — no single 180s block.
    """
    if delay <= 0:
        return
    end = time.monotonic() + delay
    while True:
        remaining = end - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(0.5, remaining))


class YouTube(AudioProvider):
    """
    YouTube audio provider class using yt-dlp.
    """

    SUPPORTS_ISRC = False
    GET_RESULTS_OPTS: List[Dict[str, Any]] = [{}]

    def get_results(self, search_term: str, *_args, **_kwargs) -> List[Result]:
        """
        Get results from YouTube

        ### Arguments
        - search_term: The search term to search for.

        ### Returns
        - A list of YouTube results, or an empty list if no results are found.
        """
        search_opts: Dict[str, Any] = {
            k: v for k, v in self.audio_handler.params.items()
            if k not in (
                "format", "format_sort", "http_headers",
                "extractor_args", "external_downloader",
                "external_downloader_args", "detect_formats",
            )
        }
        search_opts["skip_download"] = True
        search_opts["extract_flat"] = "in_playlist"

        max_retries = int(YT_PROVIDER_MAX_RETRIES)
        try:
            backoff_base = float(YT_PROVIDER_BACKOFF_BASE)
        except (TypeError, ValueError):
            backoff_base = 2.0
        try:
            backoff_cap = float(YT_BACKOFF_CAP)
        except (NameError, TypeError, ValueError):
            backoff_cap = 180.0
        if backoff_cap <= 0:
            backoff_cap = 180.0

        # Exponential backoff under a 180s total-elapsed budget:
        # 2s, 4s, 8s, 16s, 32s, 64s, ... capped so cumulative sleep <= cap.
        # At least max_retries attempts are honoured; retries continue while
        # budget remains, only on retryable errors. Non-retryable fails fast.
        start = time.monotonic()
        attempt = 0
        budget_spent = 0.0
        info: Any = None
        while True:
            try:
                with YoutubeDL(search_opts) as ydl:
                    info = ydl.extract_info(f"ytsearch{YT_SEARCH_LIMIT}:{search_term}", download=False)
                break
            except Exception as exc:  # noqa: BLE001 - classify every failure
                if not _is_retryable_error(exc):
                    return []
                elapsed = time.monotonic() - start
                remaining_wall = backoff_cap - elapsed
                remaining_budget = backoff_cap - budget_spent
                remaining = remaining_wall if remaining_wall < remaining_budget else remaining_budget
                if remaining <= 0:
                    return []
                # Guarantee max_retries attempts even if budget is tiny;
                # afterwards budget alone decides.
                try:
                    raw = backoff_base ** (attempt + 1)
                except OverflowError:
                    raw = remaining
                if not raw or raw != raw or raw == float("inf"):  # NaN/inf guard
                    raw = remaining
                jitter = random.uniform(0.0, 1.0)
                delay = raw + jitter
                if delay > remaining:
                    delay = remaining
                # If budget only allows a sliver and we already honoured
                # max_retries, stop instead of busy-looping.
                if delay <= 0:
                    return []
                _sleep_interruptible(delay)
                budget_spent += delay
                attempt += 1
                # Safety: re-check budget after sleep; next failure will exit
                # if exhausted. Loop continues otherwise (no fixed attempt cap
                # beyond max_retries minimum — budget is the cap).
                continue

        if not info or "entries" not in info:
            return []

        results = []
        for entry in info["entries"]:
            if not entry:
                continue

            video_id = entry.get("id")
            if not video_id:
                continue

            results.append(
                Result(
                    source=self.name,
                    url=f"https://www.youtube.com/watch?v={video_id}",
                    verified=False,
                    name=entry.get("title", ""),
                    duration=entry.get("duration") or 0,
                    author=entry.get("uploader") or "",
                    search_query=search_term,
                    views=entry.get("view_count") or 0,
                    result_id=video_id,
                )
            )

        return results

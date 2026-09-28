"""
YTMusic module for downloading and searching songs.
"""

import logging
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Union

from ytmusicapi import YTMusic

from spotdl.providers.audio.base import ISRC_REGEX, AudioProvider
from spotdl.types.result import Result
from spotdl.utils.formatter import parse_duration

try:
    from ghostify_consts import YTM_SEARCH_ATTEMPTS, YTM_SONGS_LIMIT, YTM_VIDEOS_LIMIT
except ImportError:  # pragma: no cover - vendored sys.path fallback
    _APP_PYTHON_DIR = Path(__file__).resolve().parents[4] / "src" / "main" / "python"
    if str(_APP_PYTHON_DIR) not in sys.path:
        sys.path.insert(0, str(_APP_PYTHON_DIR))
    from ghostify_consts import (  # type: ignore[import-not-found,no-redef]
        YTM_SEARCH_ATTEMPTS,
        YTM_SONGS_LIMIT,
        YTM_VIDEOS_LIMIT,
    )

__all__ = ["YouTubeMusic", "parse_ytm_views"]

logger = logging.getLogger(__name__)


_YTM_VIEWS_MULTIPLIERS = {
    "": 1,
    "k": 1_000,
    "m": 1_000_000,
    "b": 1_000_000_000,
    "t": 1_000_000_000_000,
}


def parse_ytm_views(value: Optional[Union[str, int, float]]) -> int:
    """Parse a YouTube Music ``views`` string into an int.

    YTM returns abbreviated counts like ``'3.6B'``, ``'937K'``,
    ``'880M'``, ``'1B'``, ``'45K'`` (also plain ints, ``None``,
    lowercase, commas, spaces, non-breaking spaces). Unparseable /
    missing values fall back to ``0`` — documented to match
    ``youtube.py``'s ``entry.get("view_count") or 0`` convention so
    callers can treat ``views`` as always-int and ``get_best_result``
    prefers cached values over slow yt-dlp ``get_views`` lookups.

    ### Arguments
    - value: raw ``views`` value from the YTM API.

    ### Returns
    - int view count (``0`` when missing/unparseable).
    """

    if value is None:
        return 0
    if isinstance(value, bool):
        return 0
    if isinstance(value, int):
        return max(0, value)
    if isinstance(value, float):
        try:
            if value != value or value in (float("inf"), float("-inf")):
                return 0
        except Exception:  # noqa: BLE001 - defensive
            return 0
        return max(0, int(value))

    text = str(value).strip()
    if not text:
        return 0
    # Non-breaking / narrow spaces glue number and magnitude in some
    # locales ("1,7\\xa0Mrd."). Normalise to a regular space first.
    text = text.replace("\u00a0", " ").replace("\u202f", " ").strip()
    if not text:
        return 0

    lowered = text.lower()
    # Locale full-words: German "Mrd." (Milliarde) means billion; force
    # the B multiplier so "1,7 Mrd." does not parse as 1.7M.
    force_multiplier: Optional[int] = None
    if "mrd" in lowered or "billi" in lowered or "milliarde" in lowered:
        force_multiplier = _YTM_VIEWS_MULTIPLIERS["b"]
    elif "mill" in lowered:
        force_multiplier = _YTM_VIEWS_MULTIPLIERS["m"]
    elif "thou" in lowered:
        force_multiplier = _YTM_VIEWS_MULTIPLIERS["k"]
    elif "trill" in lowered:
        force_multiplier = _YTM_VIEWS_MULTIPLIERS["t"]

    work = text
    # Comma handling: US thousands ("1,234,567", "1,234.5K") -> strip
    # commas; single-comma decimal ("1,7M", "3,6B") -> dot.
    if "," in work:
        if "." not in work and work.count(",") == 1 and re.search(
            r"\d,\d\s*[kmbtKMBT]?\b", work
        ):
            work = work.replace(",", ".")
        else:
            work = work.replace(",", "")

    match = re.search(r"(\d+(?:\.\d+)?)\s*([kmbtKMBT]?)", work)
    if not match:
        # Plain integer with spaces ("1 234 567")?
        digits = re.sub(r"\D", "", work)
        if digits:
            try:
                return max(0, int(digits))
            except ValueError:
                return 0
        return 0

    try:
        number = float(match.group(1))
    except ValueError:
        return 0
    suffix = (match.group(2) or "").lower()
    multiplier = (
        force_multiplier
        if force_multiplier is not None
        else _YTM_VIEWS_MULTIPLIERS.get(suffix, 1)
    )
    try:
        return max(0, int(number * multiplier))
    except (OverflowError, ValueError):
        return 0


class YouTubeMusic(AudioProvider):
    """
    YouTube Music audio provider class
    """

    SUPPORTS_ISRC = True
    SEARCH_ATTEMPTS = YTM_SEARCH_ATTEMPTS
    GET_RESULTS_OPTS: List[Dict[str, Any]] = [
        {"filter": "songs", "ignore_spelling": True, "limit": YTM_SONGS_LIMIT},
        {"filter": "videos", "ignore_spelling": True, "limit": YTM_VIDEOS_LIMIT},
    ]

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        """
        Initialize the YouTube Music API

        ### Arguments
        - args: Arguments passed to the `AudioProvider` class.
        - kwargs: Keyword arguments passed to the `AudioProvider` class.
        """

        super().__init__(*args, **kwargs)

        self.client = self._create_client()

    @staticmethod
    def _create_client() -> YTMusic:
        """
        Create a YTMusic API client.
        """

        return YTMusic()

    def get_results(
        self, search_term: str, log_search_failures: bool = True, **kwargs
    ) -> List[Result]:
        """
        Get results from YouTube Music API and simplify them

        ### Arguments
        - search_term: The search term to search for.
        - log_search_failures: Whether to log when a search returns no usable results.
        - kwargs: other keyword arguments passed to the `YTMusic.search` method.

        ### Returns
        - A list of simplified results (dicts)
        """

        is_isrc_result = ISRC_REGEX.search(search_term) is not None
        # if is_isrc_result:
        #     print("FORCEFULLY SETTING FILTER TO SONGS")
        #     kwargs["filter"] = "songs"

        for attempt in range(self.SEARCH_ATTEMPTS):
            search_results = self.client.search(search_term, **kwargs)

            # Simplify results
            results = []
            for result in search_results:
                if (
                    result is None
                    or result.get("videoId") is None
                    or result.get("artists") in [[], None]
                ):
                    continue

                results.append(
                    Result(
                        source=self.name,
                        url=(
                            f'https://{"music" if result["resultType"] == "song" else "www"}'
                            f".youtube.com/watch?v={result['videoId']}"
                        ),
                        verified=result.get("resultType") == "song",
                        name=result["title"],
                        result_id=result["videoId"],
                        author=result["artists"][0]["name"],
                        artists=tuple(map(lambda a: a["name"], result["artists"])),
                        duration=parse_duration(result.get("duration")),
                        isrc_search=is_isrc_result,
                        search_query=search_term,
                        explicit=result.get("isExplicit"),
                        album=(
                            result.get("album", {}).get("name")
                            if result.get("album")
                            else None
                        ),
                        # YTM carries abbreviated counts ("3.6B", "937K",
                        # "880M", ...) on both songs and videos branches;
                        # parse at map time so get_best_result uses cached
                        # ints instead of slow yt-dlp get_views lookups.
                        views=parse_ytm_views(result.get("views")),
                    )
                )

            if results:
                return results

            if attempt == self.SEARCH_ATTEMPTS - 1:
                if not log_search_failures:
                    return []

                logger.info(
                    "YouTube Music returned no usable results for %s after %s attempts",
                    search_term,
                    self.SEARCH_ATTEMPTS,
                )
                return []

            if log_search_failures:
                logger.debug(
                    "YouTube Music returned no usable results for %s on attempt %s/%s, "
                    "retrying with a new client",
                    search_term,
                    attempt + 1,
                    self.SEARCH_ATTEMPTS,
                )
            self.client = self._create_client()

        return []

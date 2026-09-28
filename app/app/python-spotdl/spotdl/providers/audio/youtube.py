"""
YouTube module for downloading and searching songs using yt-dlp.
"""

import time
import sys
from pathlib import Path
from typing import Any, Dict, List

from yt_dlp import YoutubeDL

from spotdl.providers.audio.base import AudioProvider
from spotdl.types.result import Result

try:
    from ghostify_consts import YT_PROVIDER_BACKOFF_BASE, YT_PROVIDER_MAX_RETRIES, YT_SEARCH_LIMIT
except ImportError:  # pragma: no cover - vendored sys.path fallback
    _APP_PYTHON_DIR = Path(__file__).resolve().parents[4] / "src" / "main" / "python"
    if str(_APP_PYTHON_DIR) not in sys.path:
        sys.path.insert(0, str(_APP_PYTHON_DIR))
    from ghostify_consts import (  # type: ignore[import-not-found,no-redef]
        YT_PROVIDER_BACKOFF_BASE,
        YT_PROVIDER_MAX_RETRIES,
        YT_SEARCH_LIMIT,
    )

__all__ = ["YouTube"]


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

        max_retries = YT_PROVIDER_MAX_RETRIES
        for attempt in range(max_retries):
            try:
                with YoutubeDL(search_opts) as ydl:
                    info = ydl.extract_info(f"ytsearch{YT_SEARCH_LIMIT}:{search_term}", download=False)
                break
            except Exception:
                if attempt < max_retries - 1:
                    time.sleep(YT_PROVIDER_BACKOFF_BASE ** (attempt + 1))
                    continue
                return []

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

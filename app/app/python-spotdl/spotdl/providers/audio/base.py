"""
Base audio provider module.
"""

import logging
import re
import shlex
from typing import Any, Dict, List, Optional, Tuple

from yt_dlp import YoutubeDL

from spotdl.types.result import Result
from spotdl.types.song import Song
from spotdl.utils.config import get_temp_path
from spotdl.utils.deno import get_local_deno_yt_dlp_options, warn_if_deno_missing
from spotdl.utils.formatter import (
    args_to_ytdlp_options,
    create_search_query,
    create_song_title,
)
from spotdl.utils.matching import get_best_matches, order_results

__all__ = ["AudioProviderError", "AudioProvider", "ISRC_REGEX", "YTDLLogger"]

logger = logging.getLogger(__name__)


class AudioProviderError(Exception):
    """
    Base class for all exceptions related to audio searching/downloading.
    """


class YTDLLogger:
    """
    Custom YT-dlp logger.
    """

    def debug(self, msg):
        """
        YTDL uses this to print debug messages.
        """

        pass  # pylint: disable=W0107

    def warning(self, msg):
        """
        YTDL uses this to print warnings.
        """

        pass  # pylint: disable=W0107

    def error(self, msg):
        """
        YTDL uses this to print errors.
        """

        # yt-dlp routes deprecation notices (e.g. old Python versions) through
        # the error channel; they are not download failures
        if "Deprecated Feature" in msg:
            logger.debug(msg)
            return

        raise AudioProviderError(msg)


ISRC_REGEX = re.compile(r"^[A-Z]{2}-?\w{3}-?\d{2}-?\d{5}$")


class AudioProvider:
    """
    Base class for all other providers. Provides some common functionality.
    Handles the yt-dlp audio handler.
    """

    SUPPORTS_ISRC: bool
    GET_RESULTS_OPTS: List[Dict[str, Any]]

    def __init__(
        self,
        output_format: str = "mp3",
        cookie_file: Optional[str] = None,
        search_query: Optional[str] = None,
        filter_results: bool = True,
        yt_dlp_args: Optional[str] = None,
    ) -> None:
        """
        Base class for audio providers.

        ### Arguments
        - output_directory: The directory to save the downloaded songs to.
        - output_format: The format to save the downloaded songs in.
        - cookie_file: The path to a file containing cookies to be used by YTDL.
        - search_query: The query to use when searching for songs.
        - filter_results: Whether to filter results.
        """

        self.output_format = output_format
        self.cookie_file = cookie_file
        self.search_query = search_query
        self.filter_results = filter_results

        if self.output_format == "m4a":
            ytdl_format = "bestaudio[ext=m4a]/bestaudio"
        elif self.output_format == "opus":
            ytdl_format = "bestaudio[ext=webm]/bestaudio"
        else:
            ytdl_format = "bestaudio"

        yt_dlp_options = {
            "format": ytdl_format,
            "quiet": True,
            "no_warnings": True,
            "encoding": "UTF-8",
            "logger": YTDLLogger(),
            "cookiefile": self.cookie_file,
            "outtmpl": str((get_temp_path() / "%(id)s.%(ext)s").resolve()),
            "retries": 5,
            "extractor_args": {},
        }

        yt_dlp_options.update(get_local_deno_yt_dlp_options())

        if yt_dlp_args:
            yt_dlp_options = args_to_ytdlp_options(
                shlex.split(yt_dlp_args), yt_dlp_options
            )

        self.audio_handler = YoutubeDL(yt_dlp_options)

    def get_results(self, search_term: str, **kwargs) -> List[Result]:
        """
        Get results from audio provider.

        ### Arguments
        - search_term: The search term to use.
        - kwargs: Additional arguments.

        ### Returns
        - A list of results.
        """

        raise NotImplementedError

    def get_views(self, url: str) -> int:
        """
        Get the number of views for a video.

        ### Arguments
        - url: The url of the video.

        ### Returns
        - The number of views.
        """

        data = self.get_download_metadata(url)

        return data["view_count"]

    def search(self, song: Song, only_verified: bool = False) -> Optional[str]:
        """
        Search for a song and return best match.

        ### Arguments
        - song: The song to search for.

        ### Returns
        - The url of the best match or None if no match was found.
        """

        # Create initial search query
        search_query = create_song_title(song.name, song.artists, for_lyrics=False).lower()
        if self.search_query:
            search_query = create_search_query(
                song, self.search_query, False, None, True
            )

        logger.debug("[%s] Searching for %s", song.song_id, search_query)

        isrc_urls: List[str] = []

        # search for song using isrc if it's available
        if song.isrc and self.SUPPORTS_ISRC and not self.search_query:
            isrc_results = self.get_results(song.isrc)

            if only_verified:
                isrc_results = [result for result in isrc_results if result.verified]
                logger.debug(
                    "[%s] Filtered to %s verified ISRC results",
                    song.song_id,
                    len(isrc_results),
                )

            isrc_urls = [result.url for result in isrc_results]
            logger.debug(
                "[%s] Found %s results for ISRC %s",
                song.song_id,
                len(isrc_results),
                song.isrc,
            )

            if len(isrc_results) == 1 and isrc_results[0].verified:
                # If we only have one verified result, return it
                # What's the chance of it being wrong?
                logger.debug(
                    "[%s] Returning only ISRC result %s",
                    song.song_id,
                    isrc_results[0].url,
                )

                return isrc_results[0].url

            if len(isrc_results) > 0:
                sorted_isrc_results = order_results(
                    isrc_results, song, self.search_query
                )

                # get the best result, if the score is above 80 return it
                best_isrc_results = sorted(
                    sorted_isrc_results.items(), key=lambda x: x[1], reverse=True
                )

                logger.debug(
                    "[%s] Filtered to %s ISRC results",
                    song.song_id,
                    len(best_isrc_results),
                )

                if len(best_isrc_results) > 0:
                    best_isrc = best_isrc_results[0]
                    if best_isrc[1] > 80.0:
                        logger.debug(
                            "[%s] Best ISRC result is %s with score %s",
                            song.song_id,
                            best_isrc[0].url,
                            best_isrc[1],
                        )

                        return best_isrc[0].url

        # Pooled search: gather ALL candidates across every entry in
        # GET_RESULTS_OPTS FIRST (YTM: songs limit 50 + videos limit 50;
        # YT: single ytsearch10 entry), then run order_results ONCE on the
        # combined list. The old per-OPT early-return (verified + score>=80)
        # is removed so a later OPT can still win.
        all_candidates: List[Result] = []
        seen_urls: set = set()
        for options in self.GET_RESULTS_OPTS:
            search_results = self.get_results(search_query, **options)

            if only_verified:
                search_results = [
                    result for result in search_results if result.verified
                ]

            logger.debug(
                "[%s] Found %s results for search query %s with options %s",
                song.song_id,
                len(search_results),
                search_query,
                options,
            )

            # Check if any of the search results is in the
            # first isrc results, since they are not hashable we have to check
            # by name
            isrc_result = next(
                (result for result in search_results if result.url in isrc_urls),
                None,
            )

            if isrc_result:
                logger.debug(
                    "[%s] Best ISRC result is %s", song.song_id, isrc_result.url
                )

                return isrc_result.url

            for result in search_results:
                url = getattr(result, "url", None)
                if url and url not in seen_urls:
                    seen_urls.add(url)
                    all_candidates.append(result)
                elif not url:
                    all_candidates.append(result)

        logger.debug(
            "[%s] Have to filter results: %s", song.song_id, self.filter_results
        )
        logger.debug(
            "[%s] Pooled %s candidates across %s option sets",
            song.song_id,
            len(all_candidates),
            len(self.GET_RESULTS_OPTS),
        )

        if not all_candidates:
            logger.debug("[%s] No results found", song.song_id)
            return None

        if self.filter_results:
            # Single scoring pass over the pooled candidates.
            results: Dict[Result, float] = order_results(
                all_candidates, song, self.search_query
            )
        else:
            results = {all_candidates[0]: 100.0}

        logger.debug("[%s] Filtered to %s results", song.song_id, len(results))

        # No matches found
        if not results:
            logger.debug("[%s] No results found", song.song_id)
            return None

        # get the result with highest score
        best_result, best_score = self.get_best_result(results)
        logger.debug(
            "[%s] Returning pooled best result %s with score %s",
            song.song_id,
            best_result.url,
            best_score,
        )

        return best_result.url

    def get_best_result(self, results: Dict[Result, float]) -> Tuple[Result, float]:
        """
        Get the best match from the results
        using views and average match

        ### Arguments
        - results: A dictionary of results and their scores

        ### Returns
        - The best match URL and its score
        """

        best_results = get_best_matches(results, 8)

        # If we have only one result, return it
        if len(best_results) == 1:
            return best_results[0][0], best_results[0][1]

        # Initial best result based on the average match
        best_result = best_results[0]

        # If the best result has a score higher than 80%
        # and it's a isrc search, return it
        if best_result[1] > 80 and best_result[0].isrc_search:
            return best_result[0], best_result[1]

        # If we have more than one result,
        # return the one with the highest score
        # and most views
        if len(best_results) > 1:
            views: List[int] = []
            for best_result in best_results:
                # Cached ints (YTM parsed at map time, YT view_count or 0)
                # are used directly so no yt-dlp lookup happens; only
                # uncached (None) results hit the network, each guarded so
                # one slow/failing lookup degrades to 0 instead of aborting
                # the whole view-weighting.
                cached = best_result[0].views
                if cached is not None:
                    try:
                        views.append(max(0, int(cached)))
                    except (TypeError, ValueError):
                        views.append(0)
                else:
                    try:
                        views.append(self.get_views(best_result[0].url))
                    except Exception:  # noqa: BLE001 - per-candidate fallback
                        logger.debug(
                            "get_views failed for %s, falling back to 0",
                            getattr(best_result[0], "url", None),
                        )
                        views.append(0)

            highest_views = max(views)
            lowest_views = min(views)

            if highest_views in (0, lowest_views):
                return best_result[0], best_result[1]

            weighted_results: List[Tuple[Result, float]] = []
            for index, best_result in enumerate(best_results):
                result_views = views[index]
                views_score = (
                    (result_views - lowest_views) / (highest_views - lowest_views)
                ) * 15
                # Open-ended score (no 100 cap): view weighting stacks
                # additively so a high-view + high-match result may exceed 100.
                score = best_result[1] + views_score
                weighted_results.append((best_result[0], score))

            # Return the result with the highest score
            return max(weighted_results, key=lambda x: x[1])

        return best_result[0], best_result[1]

    def get_download_metadata(self, url: str, download: bool = False) -> Dict:
        """
        Get metadata for a download using yt-dlp.

        ### Arguments
        - url: The url to get metadata for.

        ### Returns
        - A dictionary containing the metadata.
        """

        try:
            data = self.audio_handler.extract_info(url, download=download)

            if data:
                return data
        except Exception as exception:
            if download:
                warn_if_deno_missing()

            logger.debug(exception)
            raise AudioProviderError(f"YT-DLP download error - {url}") from exception

        raise AudioProviderError(f"No metadata found for the provided url {url}")

    @property
    def name(self) -> str:
        """
        Get the name of the provider.

        ### Returns
        - The name of the provider.
        """

        return self.__class__.__name__

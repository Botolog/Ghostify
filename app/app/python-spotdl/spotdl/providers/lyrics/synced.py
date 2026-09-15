"""
Synced lyrics provider using the syncedlyrics library
"""

from typing import Dict, List, Optional

import requests
import syncedlyrics

from spotdl.providers.lyrics.base import LyricsProvider

__all__ = ["Synced"]


class Synced(LyricsProvider):
    """
    Lyrics provider for synced lyrics using the syncedlyrics library
    Currently supported websites: Deezer, NetEase
    """

    def get_results(self, name: str, artists: List[str], **kwargs) -> Dict[str, str]:
        """
        Returns the results for the given song.

        ### Arguments
        - name: The name of the song.
        - artists: The artists of the song.
        - kwargs: Additional arguments.

        ### Returns
        - A dictionary with the results. (The key is the title and the value is the url.)
        """

        raise NotImplementedError

    def extract_lyrics(self, url: str, **kwargs) -> Optional[str]:
        """
        Extracts the lyrics from the given url.

        ### Arguments
        - url: The url to extract the lyrics from.
        - kwargs: Additional arguments.

        ### Returns
        - The lyrics of the song or None if no lyrics were found.
        """

        raise NotImplementedError

    @staticmethod
    def _normalize_lrc(text: str) -> str:
        """Normalize LRC lyrics to a consistent format.

        - Strips metadata lines (作词, 作曲, etc.)
        - Normalizes timestamps to [MM:SS.xx] (2 decimal places)
        - Removes empty lines
        """
        import re
        lines = text.strip().splitlines()
        out = []
        for line in lines:
            line = line.strip()
            if not line:
                continue
            # Strip metadata lines (Chinese credits, etc.)
            if re.match(r"^\[\d{2}:\d{2}\.\d+\]\s*(作词|作曲|作词\s*:|作曲\s*:|Lyrics by|Music by|编曲|混音|母带|制作|录音)", line, re.IGNORECASE):
                continue
            # Normalize timestamp: [MM:SS.xxx] -> [MM:SS.xx]
            m = re.match(r"^\[(\d{2}:\d{2})\.(\d+)\](.*)", line)
            if m:
                mmss = m.group(1)
                ms = m.group(2).ljust(2, "0")[:2]
                content = m.group(3)
                line = f"[{mmss}.{ms}]{content}"
            out.append(line)
        return "\n".join(out)

    def get_lyrics(self, name: str, artists: List[str], **kwargs) -> Optional[str]:
        """
        Try to get lyrics using syncedlyrics

        ### Arguments
        - name: The name of the song.
        - artists: The artists of the song.
        - kwargs: Additional arguments.

        ### Returns
        - The lyrics of the song or None if no lyrics were found.
        """

        try:
            lyrics = syncedlyrics.search(
                f"{name} - {artists[0]}",
                synced_only=not kwargs.get("allow_plain_format", True),
                providers=["Lrclib", "NetEase", "Megalobiz", "Genius"],
            )
            if not lyrics:
                return None
            return self._normalize_lrc(lyrics)
        except requests.exceptions.SSLError:
            # Max retries reached
            return None
        except TypeError:
            # Error at syncedlyrics.providers.musixmatch L89 -
            #   Because `body` is occasionally an empty list instead of a dictionary.
            # We get this error when allow_plain_format is set to True,
            #   and there are no synced lyrics present
            # Because its empty, we know there are no lyrics
            return None

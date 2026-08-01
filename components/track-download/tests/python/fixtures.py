"""Shared fixtures for the offline ghostify_dl test-suite.

Generates a tiny, real, tagged MP3 with ffmpeg (already a spotdl dependency /
bundled on device) so integrity validation can be exercised for real without a
network connection.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

# Make the component importable regardless of where tests are launched from.
_SRC = Path(__file__).resolve().parents[2] / "src" / "main" / "python"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

import ghostify_dl  # noqa: E402,F401  (re-exported for convenience)

TINY_SONG = {
    "name": "Tiny Track",
    "artists": ["Tiny Artist"],
    "artist": "Tiny Artist",
    "genres": [],
    "disc_number": 1,
    "disc_count": 1,
    "album_name": "Tiny Album",
    "album_artist": "Tiny Artist",
    "duration": 2,
    "year": 2024,
    "date": "2024-01-01",
    "track_number": 1,
    "tracks_count": 1,
    "song_id": "T" * 22,
    "explicit": False,
    "publisher": "Tiny Publisher",
    "url": "https://open.spotify.com/track/" + "T" * 22,
    "isrc": "US1234567890",
    "cover_url": None,
    "copyright_text": None,
}


def make_tagged_mp3(
    dest: Path, title: str = "Tiny Track", artist: str = "Tiny Artist",
    duration: int = 2,
) -> Path:
    """Render a real tagged MP3 (sine wave) to *dest* and return it."""
    if shutil.which("ffmpeg") is None:
        raise RuntimeError("ffmpeg is required for this test")
    subprocess.run(
        [
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", f"sine=frequency=440:duration={duration}",
            "-b:a", "128k",
            # No Xing/Info frame: length is then estimated from the byte count,
            # which lets the wrapper's truncation check work deterministically.
            "-write_xing", "0",
            "-metadata", f"title={title}",
            "-metadata", f"artist={artist}",
            "-metadata", f"album=Tiny Album",
            str(dest),
        ],
        check=True,
    )
    return dest


def temp_output_dir() -> Path:
    return Path(tempfile.mkdtemp(prefix="ghostify_test_"))

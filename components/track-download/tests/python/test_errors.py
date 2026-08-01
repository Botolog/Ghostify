"""Typed-failure mapping and constructor guards.

Pins the stable ``ErrorKind`` mapping (so the Kotlin layer never has to parse
exception text) and the ``make_downloader`` validation.

Run:  python -m unittest discover -s tests/python -p "test_errors.py"
"""

from __future__ import annotations

import unittest

import fixtures  # noqa: F401  (bootstraps sys.path)

import ghostify_dl as g
from ghostify_dl import TrackDownloadError, make_downloader


class ErrorMappingTest(unittest.TestCase):
    def test_kind_from_error_text(self):
        self.assertEqual(
            g._kind_from_error_text("url - FFmpegError: boom"),
            g.ErrorKind.CONVERSION_FAILED,
        )
        self.assertEqual(
            g._kind_from_error_text("url - MetadataError: boom"),
            g.ErrorKind.TAGGING_FAILED,
        )
        self.assertEqual(
            g._kind_from_error_text("url - DownloaderError: no match found"),
            g.ErrorKind.SEARCH_FAILED,
        )
        self.assertEqual(
            g._kind_from_error_text("url - QueryError: invalid"),
            g.ErrorKind.NO_TRACK,
        )
        self.assertEqual(
            g._kind_from_error_text("url - SomethingWentWrong: yt-dlp failed"),
            g.ErrorKind.SEARCH_FAILED,
        )
        self.assertEqual(
            g._kind_from_error_text("url - Generic: odd"), g.ErrorKind.UNKNOWN
        )

    def test_kind_for_exceptions(self):
        self.assertEqual(g._kind_for(OSError("x")), g.ErrorKind.IO)
        self.assertEqual(
            g._kind_for(KeyboardInterrupt()), g.ErrorKind.INTERRUPTED
        )
        self.assertEqual(
            g._kind_for(TrackDownloadError(g.ErrorKind.NO_TRACK, "x")),
            g.ErrorKind.NO_TRACK,
        )

    def test_error_to_dict(self):
        err = TrackDownloadError(g.ErrorKind.SEARCH_FAILED, "nothing found")
        self.assertEqual(
            err.to_dict(), {"error_type": "SEARCH_FAILED", "error": "nothing found"}
        )

    def test_make_downloader_validates_bitrate(self):
        with self.assertRaises(ValueError):
            make_downloader(fixtures.temp_output_dir(), bitrate=256)

    def test_make_downloader_validates_ffmpeg(self):
        with self.assertRaises(TrackDownloadError) as ctx:
            make_downloader(
                fixtures.temp_output_dir(),
                bitrate=128,
                ffmpeg="definitely-not-a-real-ffmpeg-binary",
            )
        self.assertEqual(ctx.exception.kind, g.ErrorKind.DEPENDENCY)

    def test_extract_spotify_id(self):
        self.assertEqual(
            g.extract_spotify_id("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=x"),
            "4cOdK2wGLETKBW3PvgPWqT",
        )
        self.assertEqual(
            g.extract_spotify_id("spotify:track:4cOdK2wGLETKBW3PvgPWqT"),
            "4cOdK2wGLETKBW3PvgPWqT",
        )
        self.assertIsNone(g.extract_spotify_id("https://open.spotify.com/playlist/abc"))
        self.assertIsNone(g.extract_spotify_id(123))

    def test_download_rejects_playlist_url(self):
        td = make_downloader(fixtures.temp_output_dir(), bitrate=128)
        with self.assertRaises(TrackDownloadError) as ctx:
            td.download("https://open.spotify.com/playlist/37i9dQZF1DX")
        self.assertEqual(ctx.exception.kind, g.ErrorKind.NO_TRACK)

    def test_download_rejects_empty_url(self):
        td = make_downloader(fixtures.temp_output_dir(), bitrate=128)
        with self.assertRaises(TrackDownloadError) as ctx:
            td.download("   ")
        self.assertEqual(ctx.exception.kind, g.ErrorKind.NO_TRACK)


if __name__ == "__main__":
    unittest.main()

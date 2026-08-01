"""T-030 / T-041 (locally-verifiable portions): integrity validation.

The on-device parts assert the file decodes as audio and is not truncated. This
unit test exercises the wrapper's real validation code against actual MP3 files
(rendered by ffmpeg): a good file passes, a truncated file and a tag-less file
fail, and a missing album/cover only warns.

Run:  python -m unittest discover -s tests/python -p "test_validation.py"
"""

from __future__ import annotations

import os
import unittest

import fixtures  # noqa: F401  (bootstraps sys.path)

from ghostify_dl import TrackDownloadError, TrackDownloader
from spotdl.types.song import Song


class ValidationTest(unittest.TestCase):
    def setUp(self):
        self.dir = fixtures.temp_output_dir()
        self.td = TrackDownloader(self.dir, bitrate=128)
        self.song = Song.from_dict(dict(fixtures.TINY_SONG, duration=2))

    def test_valid_tagged_mp3_passes(self):
        mp3 = fixtures.make_tagged_mp3(self.dir / "ok.mp3", duration=2)
        warnings = self.td._validate(mp3, self.song)
        self.assertEqual(warnings, [])

    def test_missing_album_only_warns(self):
        mp3 = fixtures.make_tagged_mp3(self.dir / "noalbum.mp3", duration=2)
        # Strip the album tag to exercise the warn-only branch.
        from mutagen.id3 import ID3, TALB

        tags = ID3(str(mp3))
        tags.delall("TALB")
        tags.save(str(mp3))
        warnings = self.td._validate(mp3, self.song)
        self.assertIn("album tag missing", warnings)

    def test_truncated_file_fails(self):
        long_song = Song.from_dict(dict(fixtures.TINY_SONG, duration=20))
        mp3 = fixtures.make_tagged_mp3(self.dir / "trunc.mp3", duration=20)
        size = mp3.stat().st_size
        with open(mp3, "r+b") as fh:
            fh.truncate(size // 10)  # keep the header, lose the body
        with self.assertRaises(TrackDownloadError) as ctx:
            self.td._validate(mp3, long_song)
        self.assertEqual(ctx.exception.kind, "VALIDATION_FAILED")
        self.assertIn("Truncated", ctx.exception.message)
        self.assertFalse(mp3.exists())  # corrupt artifact cleaned up

    def test_not_audio_fails(self):
        junk = self.dir / "junk.mp3"
        junk.write_bytes(b"this is definitely not an mp3 file at all")
        with self.assertRaises(TrackDownloadError) as ctx:
            self.td._validate(junk, self.song)
        self.assertEqual(ctx.exception.kind, "VALIDATION_FAILED")
        self.assertFalse(junk.exists())

    def test_empty_file_fails(self):
        empty = self.dir / "empty.mp3"
        empty.write_bytes(b"")
        with self.assertRaises(TrackDownloadError) as ctx:
            self.td._validate(empty, self.song)
        self.assertEqual(ctx.exception.kind, "VALIDATION_FAILED")

    def test_duration_mismatch_beyond_tolerance_fails(self):
        mp3 = fixtures.make_tagged_mp3(self.dir / "dur.mp3", duration=10)
        wrong_song = Song.from_dict(dict(fixtures.TINY_SONG, duration=200))
        with self.assertRaises(TrackDownloadError) as ctx:
            self.td._validate(mp3, wrong_song)
        self.assertEqual(ctx.exception.kind, "VALIDATION_FAILED")
        self.assertIn("Truncated", ctx.exception.message)


if __name__ == "__main__":
    unittest.main()

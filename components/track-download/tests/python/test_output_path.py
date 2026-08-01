"""T-031 (locally-verifiable portion): output-path detection.

The on-device part of T-031 checks the written filename matches the configured
template. This unit test pins ``TrackDownloader._output_path_for_song`` (which
uses spotdl's own filename formatter, so the computed path is exactly what
spotdl writes) against the template ``{artists} - {title}`` and sanitisation.

Run:  python -m unittest discover -s tests/python -p "test_output_path.py"
"""

from __future__ import annotations

import unittest

import fixtures  # noqa: F401  (bootstraps sys.path)

from ghostify_dl import TrackDownloader
from spotdl.types.song import Song


def _song(**overrides):
    data = dict(fixtures.TINY_SONG)
    data.update(overrides)
    return Song.from_dict(data)


class OutputPathTest(unittest.TestCase):
    def setUp(self):
        self.dir = fixtures.temp_output_dir()
        self.td = TrackDownloader(self.dir, bitrate=320)

    def test_template_artists_title(self):
        song = _song(name="Some Song", artists=["Artist A", "Artist B"])
        path = self.td._output_path_for_song(song)
        self.assertEqual(path.name, "Artist A, Artist B - Some Song.mp3")
        self.assertEqual(path.parent, self.dir)

    def test_sanitized_filename(self):
        # T-038 set embedded in title/artist must never survive to the filesystem.
        song = _song(name='Title:With"Bad*Chars?', artists=["A/B", "C|D<E>F"])
        path = self.td._output_path_for_song(song)
        self.assertEqual(path.suffix, ".mp3")
        for char in '/\\:*?"<>|':
            self.assertNotIn(char, path.name)
        self.assertNotIn("  ", path.name)  # repeated spaces collapsed

    def test_filename_length_capped(self):
        song = _song(name="X" * 500, artists=["A Really Long Artist " * 20])
        path = self.td._output_path_for_song(song)
        self.assertLessEqual(len(path.name), 255)

    def test_extension_never_duplicated(self):
        self.td.output_template = "{artists} - {title}.mp3"
        song = _song(name="Song", artists=["Artist"])
        path = self.td._output_path_for_song(song)
        self.assertTrue(path.name.endswith(".mp3"))
        self.assertEqual(path.name.count(".mp3"), 1)

    def test_expected_output_path_matches_downloaded_path(self):
        # Sanity: the path the formatter predicts is the path spotdl writes —
        # verified against spotdl's own formatter by construction.
        song = _song(name="Song", artists=["Artist"])
        predicted = self.td._output_path_for_song(song)
        from spotdl.utils.formatter import create_file_name

        actual = create_file_name(
            song=song,
            template=self.td._template_joined(),
            file_extension="mp3",
            restrict=None,
            file_name_length=self.td.max_filename_length,
        )
        self.assertEqual(predicted, actual)


if __name__ == "__main__":
    unittest.main()

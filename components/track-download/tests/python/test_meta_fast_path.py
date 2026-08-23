"""Bridge-passed metadata fast path: skip the Spotify re-fetch at download time.

Kotlin already stores title/artists/album/duration/cover from fetch time and
passes them through the bridge as ``meta``. These tests prove that:

* a usable ``meta`` builds the Song locally and NEVER touches ``_search``
  (no Spotify network), with ``download_url`` still pinned from ``yt_id``;
* string artists are split on "; ";
* absent or malformed ``meta`` silently falls back to the legacy search path.

Run:  python -m unittest discover -s tests/python -p "test_meta_fast_path.py"
"""

from __future__ import annotations

import unittest

import fixtures  # noqa: F401  (bootstraps sys.path)

import ghostify_dl
from ghostify_dl import TrackDownloader
from spotdl.types.song import Song

META = {
    "name": "Meta Track",
    "artists": ["Meta Artist"],
    "album": "Meta Album",
    "duration_sec": 2.0,
    "image_url": "https://example.test/cover.jpg",
}

LEGACY_URL = "https://open.spotify.com/track/" + "M" * 22


class MetaFastPathTest(unittest.TestCase):
    def setUp(self):
        self.dir = fixtures.temp_output_dir()
        self.td = TrackDownloader(self.dir, bitrate=128)
        self.url = LEGACY_URL

    # -- helpers ----------------------------------------------------------

    def _fake_download_pipeline(self, song: Song):
        """Pre-render the predicted MP3 and stub the network layers.

        Returns ``(received, calls)`` where *received* captures the song handed
        to spotdl's ``search_and_download`` so field assertions are possible.
        """
        mp3 = self.td._output_path_for_song(song)
        fixtures.make_tagged_mp3(
            mp3, title=song.name, artist=song.artist, duration=max(2, song.duration)
        )
        received = {}

        def fake_search_and_download(received_song):
            received["song"] = received_song
            return received_song, mp3

        self.td._downloader.search_and_download = fake_search_and_download  # type: ignore[method-assign]
        return received

    def _stub_legacy_search(self, calls):
        legacy = Song.from_dict(dict(fixtures.TINY_SONG, duration=2))
        self._fake_download_pipeline(legacy)

        def fake_search(_url):
            calls.append(_url)
            return [legacy]

        self.td._search = fake_search  # type: ignore[method-assign]

    # -- fast path ---------------------------------------------------------

    def test_meta_fast_path_skips_spotify_search(self):
        def no_search(*_args, **_kwargs):
            raise AssertionError("_search must not run when meta is usable")

        self.td._search = no_search  # type: ignore[method-assign]

        song = self.td._song_from_meta(META, self.url)
        self.assertIsNotNone(song)
        received = self._fake_download_pipeline(song)

        result = self.td.download(
            self.url, yt_id="yt12345", meta=dict(META)
        )

        self.assertEqual(result["status"], "DOWNLOADED")
        self.assertEqual(result["title"], "Meta Track")
        self.assertEqual(result["spotify_id"], "M" * 22)
        built = received["song"]
        self.assertEqual(built.name, "Meta Track")
        self.assertEqual(built.artists, ["Meta Artist"])
        self.assertEqual(built.artist, "Meta Artist")
        self.assertEqual(built.album_name, "Meta Album")
        self.assertEqual(built.duration, 2)  # seconds
        self.assertEqual(built.cover_url, "https://example.test/cover.jpg")
        self.assertEqual(built.url, self.url)
        self.assertEqual(
            built.download_url, "https://www.youtube.com/watch?v=yt12345"
        )

    def test_meta_artists_string_splits_on_semicolon_separator(self):
        meta = dict(META, artists="Alpha One; Beta Two")
        song = self.td._song_from_meta(meta, self.url)
        self.assertIsNotNone(song)
        self.assertEqual(song.artists, ["Alpha One", "Beta Two"])
        self.assertEqual(song.artist, "Alpha One")

    def test_meta_without_yt_id_leaves_download_url_unset(self):
        song = self.td._song_from_meta(META, self.url)
        self.assertIsNone(song.download_url)

    # -- legacy fallback ----------------------------------------------------

    def test_meta_none_falls_back_to_legacy_search(self):
        calls = []
        self._stub_legacy_search(calls)
        result = self.td.download(self.url, meta=None)
        self.assertEqual(result["status"], "DOWNLOADED")
        self.assertEqual(len(calls), 1)

    def test_malformed_meta_falls_back_to_legacy_search(self):
        bad_metas = [
            {},
            {"album": "x"},
            {"name": "   ", "artists": ["A"]},
            {"name": 7, "artists": ["A"]},
            {"name": "X", "artists": []},
            {"name": "X", "artists": ["   "]},
            {"name": "X", "artists": 42},
            {"name": "X", "artists": [["A"]]},
            {"name": "X", "artists": ";; ;"},
            "not-a-dict",
        ]
        for bad in bad_metas:
            with self.subTest(meta=bad):
                calls = []
                self.setUp()
                try:
                    self._stub_legacy_search(calls)
                    result = self.td.download(self.url, meta=bad)
                    self.assertEqual(result["status"], "DOWNLOADED")
                    self.assertEqual(len(calls), 1)
                finally:
                    self.tearDown()

    # -- helper hardening ---------------------------------------------------

    def test_song_from_meta_tolerates_bad_optional_fields(self):
        meta = {
            "name": "Meta Track",
            "artists": ["A"],
            "album": 99,
            "duration_sec": "garbage",
            "image_url": "",
        }
        song = self.td._song_from_meta(meta, self.url)
        self.assertIsNotNone(song)
        self.assertEqual(song.album_name, "")
        self.assertEqual(song.duration, 0)
        self.assertIsNone(song.cover_url)

    # -- bridge wrapper ------------------------------------------------------

    def test_module_level_download_forwards_meta(self):
        def no_search(*_args, **_kwargs):
            raise AssertionError("_search must not run when meta is usable")

        self.td._search = no_search  # type: ignore[method-assign]
        song = self.td._song_from_meta(META, self.url)
        received = self._fake_download_pipeline(song)
        result = ghostify_dl.download(
            self.td, self.url, yt_id="yt9876", meta=dict(META)
        )
        self.assertEqual(result["status"], "DOWNLOADED")
        self.assertEqual(
            received["song"].download_url,
            "https://www.youtube.com/watch?v=yt9876",
        )


if __name__ == "__main__":
    unittest.main()

"""T-035 (locally-verifiable portion): skip-existing via the .spotdl sidecar.

The on-device part of T-035 downloads a track twice and asserts the second run
does no network work. The wrapper guarantees that by keeping a JSON sidecar
(``<output>.spotdl``) per downloaded file; this unit test pins the sidecar
contract itself and proves ``download()`` short-circuits to ``SKIPPED`` without
touching spotdl at all.

Run:  python -m unittest discover -s tests/python -p "test_sidecar.py"
"""

from __future__ import annotations

import json
import os
import unittest

import fixtures  # noqa: F401  (bootstraps sys.path)

from ghostify_dl import TrackDownloader
from spotdl.types.song import Song


class SidecarTest(unittest.TestCase):
    def setUp(self):
        self.dir = fixtures.temp_output_dir()
        self.td = TrackDownloader(self.dir, bitrate=192)
        self.url = "https://open.spotify.com/track/" + "S" * 22
        self.song = Song.from_dict(dict(fixtures.TINY_SONG, song_id="S" * 22, url=self.url))

    def _seed(self, mp3_name="Seed Song.mp3"):
        mp3 = fixtures.make_tagged_mp3(self.dir / mp3_name, title="Seed Song")
        self.td._write_sidecar(self.song, mp3)
        return mp3

    def test_sidecar_written_next_to_mp3(self):
        mp3 = self._seed()
        sidecar = mp3.with_name(mp3.name + ".spotdl")
        self.assertTrue(sidecar.exists())
        info = json.loads(sidecar.read_text(encoding="utf-8"))
        self.assertEqual(info["spotify_id"], "S" * 22)
        self.assertEqual(info["url"], self.url)
        self.assertEqual(info["file"], mp3.name)

    def test_find_sidecar_returns_hit(self):
        mp3 = self._seed()
        hit = self.td._find_sidecar("S" * 22, self.url)
        self.assertIsNotNone(hit)
        self.assertEqual(hit[0], mp3)

    def test_find_sidecar_hit_by_url_without_id(self):
        mp3 = self._seed()
        hit = self.td._find_sidecar(None, self.url)
        self.assertIsNotNone(hit)
        self.assertEqual(hit[0], mp3)

    def test_stale_sidecar_with_missing_mp3_does_not_hit(self):
        mp3 = self._seed()
        mp3.unlink()  # external deletion (e.g. re-sync cleanup)
        # Index is cached; the existence check must catch it and rebuild.
        self.assertIsNone(self.td._find_sidecar("S" * 22, self.url))

    def test_corrupt_sidecar_is_ignored(self):
        mp3 = self.dir / "Corrupt Song.mp3"
        fixtures.make_tagged_mp3(mp3, title="Corrupt Song")
        sidecar = mp3.with_name(mp3.name + ".spotdl")
        sidecar.write_text("{not json", encoding="utf-8")
        self.assertIsNone(self.td._find_sidecar("S" * 22, self.url))

    def test_download_skips_without_network_when_sidecar_present(self):
        self._seed()
        # If download() ever resolves metadata, fail loudly.
        def boom(*_args, **_kwargs):
            raise AssertionError("metadata resolution must not run on a skip")

        self.td._search = boom  # type: ignore[method-assign]
        result = self.td.download(self.url)
        self.assertEqual(result["status"], "SKIPPED")
        self.assertTrue(result["output_path"].endswith(".mp3"))
        self.assertEqual(result["spotify_id"], "S" * 22)

    def test_download_skips_does_not_change_mtime(self):
        mp3 = self._seed()
        mtime_before = mp3.stat().st_mtime_ns
        result = self.td.download(self.url)
        self.assertEqual(result["status"], "SKIPPED")
        self.assertEqual(mp3.stat().st_mtime_ns, mtime_before)


if __name__ == "__main__":
    unittest.main()

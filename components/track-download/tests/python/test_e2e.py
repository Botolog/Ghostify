"""Real-network end-to-end check of the single-track download wrapper.

This is NOT part of the offline unit suite. It requires network access and
installs nothing new (spotdl + ffmpeg are already dependencies). Run with:

    python -m unittest tests/python/test_e2e.py -v        (or)
    python tests/python/test_e2e.py

It mirrors the on-device integration tests (T-030..T-035, T-038, T-040, T-041)
against a real public track. On Android CI these checks run as instrumentation
tests; here they are the closest verifiable equivalent.
"""

from __future__ import annotations

import os
import shutil
import unittest
from pathlib import Path

import fixtures  # noqa: F401  (bootstraps sys.path)

from ghostify_dl import TrackDownloadError, TrackDownloader

# Rick Astley - Never Gonna Give You Up (public, stable, widely mirrored).
PUBLIC_TRACK_URL = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
EXPECTED_ARTISTS = "Rick Astley"
EXPECTED_TITLE = "Never Gonna Give You Up"


class RealDownloadE2ETest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = fixtures.temp_output_dir()
        cls.td = TrackDownloader(cls.dir, bitrate=128)

    def tearDown(self):
        # Let each test start from a clean output dir; sidecars included.
        for p in self.dir.iterdir():
            try:
                p.unlink()
            except OSError:
                pass
        self.td._sidecar_index = None

    def test_01_download_writes_non_empty_audio(self):  # T-030 / T-041
        result = self.td.download(PUBLIC_TRACK_URL)
        self.assertEqual(result["status"], "DOWNLOADED", result)
        path = Path(result["output_path"])
        self.assertTrue(path.exists())
        self.assertGreater(path.stat().st_size, 0)
        self.assertGreaterEqual(result["file_size"], 0)
        # Decodes as audio + not truncated is validated inside the wrapper.

    def test_02_filename_matches_template(self):  # T-031
        result = self.td.download(PUBLIC_TRACK_URL)
        name = Path(result["output_path"]).name
        self.assertEqual(name, f"{EXPECTED_ARTISTS} - {EXPECTED_TITLE}.mp3")

    def test_03_embedded_tags(self):  # T-032
        result = self.td.download(PUBLIC_TRACK_URL)
        from mutagen.id3 import ID3

        tags = ID3(result["output_path"])
        self.assertEqual(str(tags["TIT2"]), EXPECTED_TITLE)
        self.assertEqual(str(tags["TPE1"]), EXPECTED_ARTISTS)
        self.assertTrue(tags.get("TALB"))

    def test_04_embedded_cover(self):  # T-033
        result = self.td.download(PUBLIC_TRACK_URL)
        from mutagen.id3 import ID3

        tags = ID3(result["output_path"])
        apic = [k for k in tags.keys() if k.startswith("APIC")]
        self.assertTrue(apic, "expected embedded cover art (APIC frame)")

    def test_05_bitrate_honored(self):  # T-034
        result = self.td.download(PUBLIC_TRACK_URL)
        if shutil.which("ffprobe"):
            import json
            import subprocess

            probe = subprocess.run(
                [
                    "ffprobe", "-v", "error", "-select_streams", "a:0",
                    "-show_entries", "stream=bit_rate",
                    "-of", "json", result["output_path"],
                ],
                check=True, capture_output=True, text=True,
            )
            bit_rate = json.loads(probe.stdout)["streams"][0]["bit_rate"]
            self.assertAlmostEqual(int(bit_rate) / 1000, 128, delta=2)

    def test_06_skip_no_redownload(self):  # T-035
        result1 = self.td.download(PUBLIC_TRACK_URL)
        mp3 = Path(result1["output_path"])
        self.assertTrue(mp3.with_name(mp3.name + ".spotdl").exists())
        mtime_before = mp3.stat().st_mtime_ns

        def boom(*_args, **_kwargs):
            raise AssertionError("no metadata/network work allowed on a skip")

        self.td._search = boom  # type: ignore[method-assign]
        result2 = self.td.download(PUBLIC_TRACK_URL)
        self.assertEqual(result2["status"], "SKIPPED")
        self.assertEqual(result2["output_path"], result1["output_path"])
        self.assertEqual(mp3.stat().st_mtime_ns, mtime_before)

    def test_07_invalid_url_fails_typed(self):  # T-036 analogue
        with self.assertRaises(TrackDownloadError) as ctx:
            self.td.download("https://open.spotify.com/playlist/37i9dQZF1DX0abcdefghij")
        self.assertEqual(ctx.exception.kind, "NO_TRACK")

    def test_08_filename_sanitized(self):  # T-038 analogue
        # The wrapper's sanitisation contract is pinned offline; here we just
        # confirm the real download produced a name free of invalid characters.
        result = self.td.download(PUBLIC_TRACK_URL)
        for char in '/\\:*?"<>|':
            self.assertNotIn(char, Path(result["output_path"]).name)

    def test_09_hooks_fire_start_complete(self):  # T-040
        events = []

        def on_start(track):
            events.append(("start", track.get("title")))

        def on_complete(result):
            events.append(("complete", result.get("status")))

        def on_progress(percent, message):
            events.append(("progress", percent))

        self.td.download(
            PUBLIC_TRACK_URL, on_start=on_start, on_complete=on_complete,
            on_progress=on_progress,
        )
        self.assertEqual(events[0][0], "start")
        self.assertIn(("complete", "DOWNLOADED"), events)
        self.assertTrue(any(e[0] == "progress" for e in events))

    def test_10_expected_output_path_matches(self):  # T-150 cross-check
        result = self.td.download(PUBLIC_TRACK_URL)
        self.assertEqual(self.td.expected_output_path(PUBLIC_TRACK_URL),
                         result["output_path"])


if __name__ == "__main__":
    unittest.main(verbosity=2)

"""T-040 (locally-verifiable portion): progress hooks fire start + complete.

The on-device part of T-040 asserts the start and complete hooks fire for a real
download. This unit test proves the hook lifecycle end-to-end on the download
path (with spotdl's network layer stubbed out) and on the skip path, which must
also fire start + complete.

Run:  python -m unittest discover -s tests/python -p "test_hooks.py"
"""

from __future__ import annotations

import unittest

import fixtures  # noqa: F401  (bootstraps sys.path)

from ghostify_dl import TrackDownloader
from spotdl.types.song import Song


class _RecordingHook:
    def __init__(self):
        self.events = []

    def on_download_start(self, track):
        self.events.append(("start", track.get("title")))

    def on_progress(self, percent, message):
        self.events.append(("progress", percent, message))

    def on_download_complete(self, result):
        self.events.append(("complete", result.get("status"), result.get("output_path")))


class HooksTest(unittest.TestCase):
    def setUp(self):
        self.dir = fixtures.temp_output_dir()
        self.td = TrackDownloader(self.dir, bitrate=128)
        self.song = Song.from_dict(dict(fixtures.TINY_SONG, duration=2))

    def _stub_download(self):
        """Stub the network + spotdl steps to 'download' a real tiny MP3 at the
        predicted path, so hooks can be observed without touching the network."""
        mp3 = self.td._output_path_for_song(self.song)
        fixtures.make_tagged_mp3(mp3, title=self.song.name, artist=self.song.artist,
                                 duration=2)

        def fake_search(_url):
            return [self.song]

        def fake_search_and_download(_song):
            return _song, mp3

        self.td._search = fake_search  # type: ignore[method-assign]
        self.td._downloader.search_and_download = fake_search_and_download  # type: ignore[method-assign]
        return mp3

    def test_hooks_fire_start_progress_complete_on_download(self):
        hook = _RecordingHook()
        self._stub_download()
        result = self.td.download(
            self.song.url, on_start=hook, on_progress=hook, on_complete=hook
        )
        self.assertEqual(result["status"], "DOWNLOADED")
        titles = [e[1] for e in hook.events if e[0] == "start"]
        self.assertEqual(titles, ["Tiny Track"])
        completions = [e for e in hook.events if e[0] == "complete"]
        self.assertEqual(len(completions), 1)
        self.assertEqual(completions[0][1], "DOWNLOADED")
        self.assertTrue(completions[0][2].endswith(".mp3"))
        progress = [e for e in hook.events if e[0] == "progress"]
        self.assertGreaterEqual(len(progress), 1)

    def test_hooks_fire_start_complete_on_skip(self):
        hook = _RecordingHook()
        mp3 = fixtures.make_tagged_mp3(self.dir / "Tiny Track.mp3", title="Tiny Track")
        self.td._write_sidecar(self.song, mp3)

        def boom(*_args, **_kwargs):
            raise AssertionError("no network work allowed on the skip path")

        self.td._search = boom  # type: ignore[method-assign]
        result = self.td.download(self.song.url, on_start=hook, on_complete=hook)
        self.assertEqual(result["status"], "SKIPPED")
        titles = [e[1] for e in hook.events if e[0] == "start"]
        self.assertEqual(titles, ["Tiny Track"])
        completions = [e for e in hook.events if e[0] == "complete"]
        self.assertEqual(len(completions), 1)
        self.assertEqual(completions[0][1], "SKIPPED")

    def test_hook_exceptions_do_not_fail_download(self):
        class _Boom:
            def on_download_start(self, track):
                raise RuntimeError("hook exploded")

        self._stub_download()
        result = self.td.download(self.song.url, on_start=_Boom())
        self.assertEqual(result["status"], "DOWNLOADED")

    def test_java_style_hook_method_names_resolved(self):
        # Chaquopy passes Java/Kotlin objects with camelCase methods.
        class _JavaHook:
            def __init__(self):
                self.started = 0
                self.completed = 0

            def onDownloadStart(self, track):  # noqa: N802
                self.started += 1

            def onDownloadComplete(self, result):  # noqa: N802
                self.completed += 1

        hook = _JavaHook()
        self._stub_download()
        self.td.download(self.song.url, on_start=hook, on_complete=hook)
        self.assertEqual((hook.started, hook.completed), (1, 1))


if __name__ == "__main__":
    unittest.main()

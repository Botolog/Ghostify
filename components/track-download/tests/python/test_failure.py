"""T-036 / T-039 (locally-verifiable portions): typed failures + cleanup.

T-036 — a track that cannot be resolved on YouTube (audio unavailable /
region-blocked) must surface as a *typed* failure and must **not** leave a
partial/corrupt MP3 on disk.

T-039 — an audio-extraction failure must fail *only* that track (a single
TrackDownloader serves one track, so "other tracks unaffected" is verified by
asserting the failure is contained to this call: no shared state is mutated and
the downloader is reusable afterwards).

These are exercised here with spotdl's network step stubbed out, so they do not
need a device or network. The live behaviour is covered by the on-device
instrumentation tests (see ``solution.md``).

Also pins the Python→ErrorKind mapping matrix (the type-name-based branch of
``_kind_for``) using realistic spotdl exception names, which the on-device
path depends on.

Run:  python -m unittest discover -s tests/python -p "test_failure.py"
"""

from __future__ import annotations

import unittest

import fixtures  # noqa: F401  (bootstraps sys.path)

from ghostify_dl import (
    ErrorKind,
    TrackDownloadError,
    TrackDownloader,
    download as module_download,
    expected_output_path as module_expected_output_path,
    make_downloader as module_make_downloader,
    cleanup_temp as module_cleanup_temp,
    _kind_for,
)
from spotdl.types.song import Song


# Realistic spotdl exception names so the type-name → ErrorKind mapping is
# exercised exactly as it would be on a real download.
class DownloaderError(Exception):
    pass


class FFmpegError(Exception):
    pass


class SongError(Exception):
    pass


def _song():
    return Song.from_dict(dict(fixtures.TINY_SONG, song_id="F" * 22))


class FailureTest(unittest.TestCase):
    def setUp(self):
        self.dir = fixtures.temp_output_dir()
        self.td = TrackDownloader(self.dir, bitrate=128)
        # Isolate spotdl's temp dir so we can assert on cleanup without touching
        # the real one.
        self.temp = fixtures.temp_output_dir()
        self.td._temp_dir = self.temp
        self.song = _song()
        self.url = self.song.url

    # -- T-036 / T-039: stubbed extraction failure ----------------------------- #

    def _stub_search_only(self, allow_unknown: bool = False):
        """Resolve metadata without network; never touches spotdl's download."""

        def fake_search(url):
            if allow_unknown or url == self.url:
                return [self.song]
            raise ValueError("no track")

        self.td._search = fake_search  # type: ignore[method-assign]

    def test_kind_mapping_matrix(self):
        self.assertEqual(_kind_for(DownloaderError("x")), ErrorKind.SEARCH_FAILED)
        self.assertEqual(_kind_for(FFmpegError("x")), ErrorKind.CONVERSION_FAILED)
        self.assertEqual(_kind_for(SongError("x")), ErrorKind.NO_TRACK)
        self.assertEqual(_kind_for(OSError("x")), ErrorKind.IO)
        self.assertEqual(_kind_for(KeyboardInterrupt()), ErrorKind.INTERRUPTED)

    def test_extraction_failure_is_typed_and_leaves_no_artifact(self):
        self._stub_search_only()
        expected = self.td._output_path_for_song(self.song)

        def boom(_song):  # spotdl.search_and_download raises
            # Simulate spotdl leaving a partial temp file behind.
            (self.temp / "orphan.webm").write_bytes(b"partial")
            raise DownloaderError("yt-dlp failed")

        self.td._downloader.search_and_download = boom  # type: ignore[method-assign]

        with self.assertRaises(TrackDownloadError) as ctx:
            self.td.download(self.url)
        self.assertEqual(ctx.exception.kind, ErrorKind.SEARCH_FAILED)
        # No corrupt/partial MP3 at the canonical output path.
        self.assertFalse(expected.exists())
        # Temp file created during the attempt is cleaned up.
        self.assertFalse((self.temp / "orphan.webm").exists())
        # Sidecar never written on failure.
        self.assertFalse(expected.with_name(expected.name + ".spotdl").exists())

    def test_extraction_failure_is_contained_and_downloader_is_reusable(self):
        """T-039: a failed track must not corrupt the downloader for the next one."""
        self._stub_search_only()

        def boom(_song):
            raise DownloaderError("yt-dlp failed")

        self.td._downloader.search_and_download = boom  # type: ignore[method-assign]
        with self.assertRaises(TrackDownloadError):
            self.td.download(self.url)

        # Second call: stub a real tiny MP3 at the predicted path and confirm the
        # downloader is still fully functional (no leaked state).
        mp3 = self.td._output_path_for_song(self.song)
        fixtures.make_tagged_mp3(mp3, title=self.song.name, duration=2)

        def succeed(_song):
            return _song, mp3

        self.td._downloader.search_and_download = succeed  # type: ignore[method-assign]
        result = self.td.download(self.url)
        self.assertEqual(result["status"], "DOWNLOADED")
        self.assertEqual(result["output_path"], str(mp3))

    def test_audio_unavailable_fails_typed(self):
        self._stub_search_only()
        # Region-blocked / not available on YouTube: spotdl raises a SongError.
        self.td._downloader.search_and_download = lambda _song: (_ for _ in ()).throw(  # type: ignore[method-assign]
            SongError("Song not available in your region")
        )
        with self.assertRaises(TrackDownloadError) as ctx:
            self.td.download(self.url)
        self.assertEqual(ctx.exception.kind, ErrorKind.NO_TRACK)
        self.assertFalse(self.td._output_path_for_song(self.song).exists())

    # -- Module-level positional API contract (what the Kotlin bridge calls) -- #

    def test_module_level_api_is_callable_positionally(self):
        """The Kotlin bridge invokes make_downloader/download/expected_output_path/
        cleanup_temp positionally; this pins that contract without touching the
        network."""
        downloader = module_make_downloader(
            self.dir, 128, "{artists} - {title}", "ffmpeg"
        )
        self.assertIsInstance(downloader, TrackDownloader)

        self._stub_search_only_on(downloader)
        mp3 = downloader._output_path_for_song(self.song)
        fixtures.make_tagged_mp3(mp3, title=self.song.name, duration=2)
        downloader._downloader.search_and_download = lambda _song: (_song, mp3)  # type: ignore[method-assign]  # noqa: E501

        result = module_download(
            downloader,
            self.url,
            None,  # on_start
            None,  # on_complete
            None,  # on_progress
        )
        self.assertEqual(result["status"], "DOWNLOADED")
        self.assertEqual(result["output_path"], str(mp3))
        self.assertEqual(result["bitrate"], "128k")

        # expected_output_path resolves via the sidecar index (no network after 1st run).
        self.assertEqual(module_expected_output_path(downloader, self.url), str(mp3))
        self.assertIsNone(
            module_expected_output_path(downloader, "not-a-real-url-ever")
        )

        # cleanup_temp returns an int count.
        self.assertIsInstance(module_cleanup_temp(downloader), int)

    def _stub_search_only_on(self, downloader):
        def fake_search(url):
            if url == self.url:
                return [self.song]
            raise ValueError("no track")

        downloader._search = fake_search  # type: ignore[method-assign]


if __name__ == "__main__":
    unittest.main()

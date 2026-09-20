"""Full-chain integration test: Spotify playlist -> fetch metadata -> download -> verify file count.

Requires: network, ffmpeg on PATH, spotdl + yt-dlp installed.
Run with: pytest src/test/python/ -m integration -v
"""

import concurrent.futures
import glob
import shutil
import threading
import time

import pytest

import ghostify_dl

PLAYLIST_URL = "https://open.spotify.com/playlist/2a7gq4yQrXk2M7au3Ekkys"

PARALLEL_WORKERS = 7

pytestmark = pytest.mark.integration


@pytest.fixture()
def output_dir(tmp_path):
    d = tmp_path / "downloads"
    d.mkdir()
    yield d
    shutil.rmtree(d, ignore_errors=True)


@pytest.fixture()
def ffmpeg_available():
    if not shutil.which("ffmpeg"):
        pytest.skip("ffmpeg not found on PATH")


def _download_one(output_dir, track, delay=0.0):
    if delay > 0:
        time.sleep(delay)

    spotify_id = track.get("spotify_id", "")
    if not spotify_id:
        return {"status": "NO_ID", "error": "missing spotify_id"}

    track_url = f"https://open.spotify.com/track/{spotify_id}"
    yt_id = track.get("yt_id")
    duration_ms = track.get("duration_ms", 0) or 0
    meta = {
        "name": track.get("title", ""),
        "artists": track.get("artists", ""),
        "album": track.get("album", ""),
        "duration_sec": duration_ms / 1000.0,
        "image_url": track.get("cover_url"),
    }

    dl = ghostify_dl.make_downloader(
        output_dir=str(output_dir),
        bitrate=128,
        audio_providers=["youtube-music", "youtube"],
        lyrics_providers=[],
    )

    return ghostify_dl.download(dl, track_url, yt_id=yt_id, meta=meta)


@pytest.mark.skip(reason="Temporarily disabled — takes 15+ min, run manually")
class TestSpotifyPlaylistDownload:

    @pytest.mark.slow
    @pytest.mark.integration
    def test_download_count_matches_metadata(self, output_dir, ffmpeg_available):
        metadata = ghostify_dl.fetch_playlist(
            PLAYLIST_URL, options={"resolve_yt": False}
        )

        assert "track_count" in metadata
        assert "tracks" in metadata
        assert metadata["track_count"] > 0
        assert len(metadata["tracks"]) == metadata["track_count"]

        tracks = metadata["tracks"]
        track_count = metadata["track_count"]

        downloaded = 0
        failed = 0
        lock = threading.Lock()

        def _run(idx, track):
            nonlocal downloaded, failed
            stagger = ((idx - 1) % PARALLEL_WORKERS) * 1.0
            try:
                result = _download_one(output_dir, track, delay=stagger)
                status = result.get("status", "UNKNOWN")
                with lock:
                    if status in ("DOWNLOADED", "SKIPPED"):
                        downloaded += 1
                    else:
                        failed += 1
                        print(f"  [{idx}/{track_count}] FAILED {track.get('title', '?')}: {status} {result.get('error', '')}")
            except Exception as exc:
                with lock:
                    failed += 1
                    print(f"  [{idx}/{track_count}] EXCEPTION {track.get('title', '?')}: {exc}")

            with lock:
                print(f"  [{idx}/{track_count}] downloaded={downloaded} failed={failed}")

        with concurrent.futures.ThreadPoolExecutor(max_workers=PARALLEL_WORKERS) as pool:
            futures = {
                pool.submit(_run, idx, track): idx
                for idx, track in enumerate(tracks, 1)
            }
            for future in concurrent.futures.as_completed(futures):
                future.result()

        mp3_files = glob.glob(str(output_dir / "**" / "*.mp3"), recursive=True)
        files_on_disk = len(mp3_files)

        assert files_on_disk == downloaded, (
            f"MP3 file count ({files_on_disk}) != successful downloads ({downloaded})"
        )
        assert downloaded == track_count, (
            f"Expected all {track_count} tracks downloaded, got {downloaded} "
            f"({failed} failed)"
        )
        assert failed == 0, f"{failed} tracks failed to download"

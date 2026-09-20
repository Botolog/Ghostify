"""Search-only test: fetch playlist with resolve_yt=True -> verify every track got a yt_id.

This tests the PRODUCTION search path (ghostify_dl.fetch_playlist with resolve_yt=True)
which uses YouTubeMusic internally to resolve YouTube IDs for each Spotify track.

Requires: network
Run with: pytest src/test/python/ -m integration -v -k test_search
"""

import sys
import threading
import time
from unittest.mock import patch

import pytest

import ghostify_dl

PLAYLIST_URL = "https://open.spotify.com/playlist/2a7gq4yQrXk2M7au3Ekkys"

pytestmark = pytest.mark.integration


def _log(msg):
    print(msg, flush=True)


class TestSpotifySearch:

    @pytest.mark.slow
    @pytest.mark.integration
    def test_search_returns_url_for_all_tracks(self):
        _log("Fetching playlist + resolving YouTube IDs...\n")

        done_count = {"n": 0}
        total = {"n": 187}
        lock = threading.Lock()
        start = time.time()

        def _progress_bar():
            bar_len = 30
            while True:
                with lock:
                    done = done_count["n"]
                t = total["n"]
                pct = done / t if t else 0
                filled = int(bar_len * pct)
                bar = "█" * filled + "░" * (bar_len - filled)
                elapsed = time.time() - start
                if done > 0 and done < t:
                    eta = elapsed / done * (t - done)
                    eta_str = f"{int(eta//60)}m{int(eta%60):02d}s"
                elif done >= t:
                    eta_str = "done!"
                else:
                    eta_str = "?"
                sys.stdout.write(
                    f"\r  [{bar}] {done}/{t} ({pct*100:.0f}%) "
                    f"| {elapsed:.0f}s elapsed | {eta_str}   "
                )
                sys.stdout.flush()
                if done >= t:
                    sys.stdout.write("\n")
                    sys.stdout.flush()
                    break
                time.sleep(0.3)

        _original_resolve = ghostify_dl._resolve_yt_id

        def _tracked_resolve(song, per_track_timeout):
            result = _original_resolve(song, per_track_timeout)
            with lock:
                done_count["n"] += 1
            return result

        bar_thread = threading.Thread(target=_progress_bar, daemon=True)
        bar_thread.start()

        with patch.object(ghostify_dl, "_resolve_yt_id", side_effect=_tracked_resolve):
            metadata = ghostify_dl.fetch_playlist(
                PLAYLIST_URL,
                options={"resolve_yt": True, "per_track_yt_timeout": 15.0},
            )

        done_count["n"] = total["n"]
        bar_thread.join(timeout=2)

        assert "track_count" in metadata
        assert "tracks" in metadata
        assert metadata["track_count"] > 0
        assert len(metadata["tracks"]) == metadata["track_count"]

        tracks = metadata["tracks"]
        track_count = metadata["track_count"]

        found = 0
        missing = []
        for idx, track in enumerate(tracks):
            title = track.get("title", "?")
            artists = track.get("artists", "?")
            yt_id = track.get("yt_id")
            if yt_id:
                found += 1
            else:
                missing.append((idx, title, artists))

        _log("")
        _log(f"{'='*60}")
        _log(f"  {found}/{track_count} tracks resolved")

        if missing:
            _log(f"  {len(missing)} FAILED:")
            for idx, title, artists in missing:
                _log(f"    [{idx+1}] {artists} - {title}")
            _log(f"{'='*60}")
            pytest.fail(f"{len(missing)}/{track_count} tracks missing yt_id")
        else:
            _log(f"  ALL OK")
            _log(f"{'='*60}")

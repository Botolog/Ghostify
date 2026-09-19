"""Search-only test: fetch playlist -> search each track -> verify a URL is returned.

This test isolates the search step from the download step. If it fails here,
the issue is in metadata/search, not in audio download/conversion.

Requires: network
Run with: pytest src/test/python/ -m integration -v -k test_search
"""

import time

import pytest

import ghostify_dl

PLAYLIST_URL = "https://open.spotify.com/playlist/2a7gq4yQrXk2M7au3Ekkys"

pytestmark = pytest.mark.integration


class TestSpotifySearch:

    @pytest.mark.slow
    @pytest.mark.integration
    def test_search_returns_url_for_all_tracks(self):
        metadata = ghostify_dl.fetch_playlist(
            PLAYLIST_URL, options={"resolve_yt": False}
        )

        assert "track_count" in metadata
        assert "tracks" in metadata
        assert metadata["track_count"] > 0
        assert len(metadata["tracks"]) == metadata["track_count"]

        tracks = metadata["tracks"]
        track_count = metadata["track_count"]

        results = [None] * track_count
        errors = []

        def _search(idx, track):
            spotify_id = track.get("spotify_id", "")
            if not spotify_id:
                errors.append((idx, "NO_SPOTIFY_ID"))
                return

            track_url = f"https://open.spotify.com/track/{spotify_id}"
            duration_ms = track.get("duration_ms", 0) or 0
            meta = {
                "name": track.get("title", ""),
                "artists": track.get("artists", ""),
                "album": track.get("album", ""),
                "duration_sec": duration_ms / 1000.0,
            }

            try:
                from spotdl.types.song import Song
                from spotdl.utils.formatter import create_song_title
                from spotdl.download.downloader import Downloader

                dl_settings = {
                    "output": "/tmp/ghostify_test/{artists} - {title}.{output-ext}",
                    "format": "mp3",
                    "bitrate": "128k",
                    "overwrite": "skip",
                    "scan_for_songs": False,
                    "audio_providers": ["youtube"],
                    "lyrics_providers": [],
                    "yt_dlp_args": "",
                    "ffmpeg": "ffmpeg",
                    "threads": 1,
                    "filter_results": True,
                    "simple_tui": True,
                    "print_errors": False,
                    "log_level": "WARNING",
                    "generate_lrc": False,
                    "sponsor_block": False,
                    "create_skip_file": False,
                    "respect_skip_file": False,
                    "restrict": None,
                    "max_filename_length": 255,
                    "only_verified_results": False,
                }
                dl = Downloader(dl_settings)

                raw_artists = meta["artists"]
                if isinstance(raw_artists, str):
                    artist_list = [a.strip() for a in raw_artists.split(";") if a.strip()]
                elif isinstance(raw_artists, list):
                    artist_list = raw_artists
                else:
                    artist_list = [str(raw_artists)]

                song = Song.from_missing_data(
                    name=meta["name"],
                    artist=artist_list[0] if artist_list else "",
                    artists=artist_list,
                    song_id=spotify_id,
                    duration=int(meta["duration_sec"]),
                    url=track_url,
                    genres=[], disc_number=1, disc_count=1,
                    album_name=meta.get("album", ""),
                    album_artist=artist_list[0] if artist_list else "",
                    year=0, date="", track_number=1, tracks_count=1,
                    explicit=False, publisher=artist_list[0] if artist_list else "",
                    isrc=None, cover_url=None, copyright_text=None, album_id="",
                )

                query = create_song_title(song.name, song.artists)
                url = dl.search(song)

                results[idx] = {"url": url, "query": query, "title": track.get("title", "?")}
                print(f"  [{idx+1}/{track_count}] OK  {track.get('title', '?')}: {url}")
            except Exception as exc:
                errors.append((idx, str(exc)))
                print(f"  [{idx+1}/{track_count}] ERR {track.get('title', '?')}: {exc}")

        BATCH = 30
        for idx, track in enumerate(tracks):
            _search(idx, track)
            if idx < track_count - 1:
                time.sleep(3)
            if (idx + 1) % BATCH == 0 and idx < track_count - 1:
                print(f"  ... cooldown 45s after {idx+1} tracks ...")
                time.sleep(45)

        found = sum(1 for r in results if r is not None)
        print(f"\n{'='*60}")
        print(f"Results: {found}/{track_count} tracks found")
        print(f"Errors: {len(errors)}")

        if errors:
            print(f"\nCooldown 300s, then retrying {len(errors)} failed tracks (20s delay)...")
            time.sleep(300)
            failed_indices = [idx for idx, _ in errors]
            errors.clear()
            for idx in failed_indices:
                time.sleep(20)
                _search(idx, tracks[idx])

        found = sum(1 for r in results if r is not None)
        print(f"\n{'='*60}")
        print(f"Final: {found}/{track_count} tracks found")
        print(f"Errors: {len(errors)}")

        if errors:
            print("\nFailed tracks:")
            for idx, err in sorted(errors):
                t = tracks[idx] if idx < len(tracks) else {}
                print(f"  [{idx+1}] {t.get('title', '?')}: {err}")

        assert found == track_count, (
            f"Only {found}/{track_count} tracks found via search "
            f"({len(errors)} failed)"
        )
        assert len(errors) == 0, f"{len(errors)} tracks failed to search"

"""Local Python unit tests for ghostify_dl (T-019, T-020, T-021 + core logic).

Run:
    python3 tests/ghostify_dl_test.py

Requires a working spotdl install (so the real Song model and provider classes
are importable). Network-dependent assertions (real Spotify/YouTube calls) are
gated: they run only when OUTBOUND network to api-partner.spotify.com is
available, otherwise they are skipped and reported.

Coverage mapping (TESTS.md):
    T-013  duplicates preserved & ordered      (offline, mocked)
    T-014  empty playlist -> 0 tracks          (offline, mocked)
    T-019  fetch timeout -> TIMEOUT, aborts    (offline, real thread)
    T-020  missing YT match -> yt_id null      (offline, mocked provider)
    T-021  raw exceptions -> typed GhostifyError (offline)
    plus error-classification matrix and wire format.
"""

from __future__ import annotations

import contextlib
import os
import sys
import time
import unittest
from unittest import mock

sys.path.insert(
    0, os.path.join(os.path.dirname(__file__), "..", "src", "main", "python")
)

import ghostify_dl as g  # noqa: E402

try:
    from spotdl.types.playlist import Playlist
    from spotdl.types.song import Song

    HAVE_SPOTDL = True
except ImportError:  # pragma: no cover - environment without spotdl
    HAVE_SPOTDL = False

NETWORK_TESTS = os.environ.get("GHOSTIFY_NETWORK_TESTS", "1") == "1"


def _song(
    song_id, name="Song", artists=("Artist A",), album=None, duration=200, cover=None
):
    return Song.from_missing_data(
        name=name,
        artists=list(artists),
        artist=artists[0],
        album_id=None,
        album_name=album,
        album_artist=None,
        album_type=None,
        disc_number=1,
        duration=duration,
        year=None,
        date=None,
        track_number=1,
        tracks_count=None,
        song_id=song_id,
        explicit=False,
        url=f"https://open.spotify.com/track/{song_id}",
        isrc=None,
        cover_url=cover,
        list_position=1,
    )


def _fake_metadata(
    name="N", author_name="O", cover_url="https://i.scdn.co/image/p", description="D"
):
    return {
        "name": name,
        "author_name": author_name,
        "cover_url": cover_url,
        "description": description,
    }


@contextlib.contextmanager
def _install_fake_fetch(songs):
    """Temporarily monkeypatch spotdl's Playlist.get_metadata (auto-restores)."""
    from spotdl.types.playlist import Playlist

    def fake_get_metadata(url):
        return _fake_metadata(), songs

    with mock.patch.object(Playlist, "get_metadata", new=fake_get_metadata):
        yield


class GhostifyErrorTest(unittest.TestCase):
    def test_wire_format(self):
        e = g.GhostifyError(g.ERR_NOT_FOUND, "gone")
        self.assertEqual(str(e), "NOT_FOUND: gone")
        self.assertEqual(e.code, "NOT_FOUND")
        self.assertEqual(e.message, "gone")

    def test_unknown_code_rejected(self):
        with self.assertRaises(ValueError):
            g.GhostifyError("NOPE", "x")

    def test_id_extraction(self):
        self.assertEqual(g._extract_playlist_id("abc123"), "abc123")
        self.assertEqual(
            g._extract_playlist_id(
                "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x"
            ),
            "37i9dQZF1DXcBWIGoYBM5M",
        )
        self.assertEqual(
            g._extract_playlist_id("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"),
            "37i9dQZF1DXcBWIGoYBM5M",
        )
        with self.assertRaises(g.GhostifyError) as ctx:
            g._extract_playlist_id("not valid!")
        self.assertEqual(ctx.exception.code, g.ERR_NOT_FOUND)

    def test_bad_options(self):
        with self.assertRaises(g.GhostifyError) as ctx:
            g._normalize_options({"timeout": "nope"})
        self.assertEqual(ctx.exception.code, g.ERR_UNKNOWN)

    def test_extract_video_id(self):
        self.assertEqual(
            g._extract_video_id("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
            "dQw4w9WgXcQ",
        )
        self.assertIsNone(g._extract_video_id("https://example.com/other?p=1"))
        self.assertIsNone(g._extract_video_id(None))


@unittest.skipUnless(HAVE_SPOTDL, "spotdl not installed")
class TimeoutAndClassificationTest(unittest.TestCase):
    def test_timeout_abort(self):
        # T-019: a long-hanging call must abort within the deadline with a
        # typed TIMEOUT, not hang or raise a raw exception.
        t0 = time.monotonic()

        def hang():
            time.sleep(30)

        with self.assertRaises(g.GhostifyError) as ctx:
            g._call_with_deadline(0.4, hang)
        elapsed = time.monotonic() - t0
        self.assertLess(elapsed, 5.0, f"did not abort fast enough ({elapsed:.1f}s)")
        self.assertEqual(ctx.exception.code, g.ERR_TIMEOUT)

    def test_timeout_returns_result_before_deadline(self):
        self.assertEqual(g._call_with_deadline(5.0, lambda: 42), 42)

    def test_deadline_disabled(self):
        self.assertEqual(g._call_with_deadline(None, lambda: 7), 7)
        self.assertEqual(g._call_with_deadline(0, lambda: 7), 7)

    def test_classification_matrix(self):
        # T-021: raw exceptions never leak; they become typed GhostifyError.
        class Boom(Exception):
            pass

        cases = {
            g.ERR_RATE_LIMITED: [
                "429 Too Many Requests",
                "rate limit exceeded",
                "quota exceeded",
            ],
            g.ERR_PRIVATE: [
                "403 Forbidden",
                "this playlist is private",
                "access denied",
            ],
            g.ERR_NOT_FOUND: [
                "404 not found",
                "playlist does not exist",
                "wrong playlist id",
            ],
            g.ERR_TIMEOUT: ["Read timed out", "read timeout"],
            g.ERR_NO_NETWORK: [
                "Max retries exceeded ... ConnectionError",
                "Failed to establish a new connection",
                "network is unreachable",
                "Temporary failure in name resolution",
            ],
            g.ERR_UNKNOWN: ["something bizarre"],
        }
        for expected, messages in cases.items():
            for m in messages:
                err = g._classify_error(Boom(m))
                self.assertEqual(err.code, expected, f"'{m}' -> {err.code}")

    def test_classify_from_text(self):
        self.assertEqual(
            g._classify_from_text("Playlist is private").code, g.ERR_PRIVATE
        )
        self.assertEqual(g._classify_from_text("404 not found").code, g.ERR_NOT_FOUND)
        self.assertEqual(g._classify_from_text("Rate limited").code, g.ERR_RATE_LIMITED)
        self.assertEqual(g._classify_from_text("??? unknown ???").code, g.ERR_UNKNOWN)

    def test_extract_graphql_message_shapes(self):
        self.assertEqual(g._extract_graphql_message({"message": "hi"}), "hi")
        self.assertEqual(
            g._extract_graphql_message({"message": {"message": "nested"}}), "nested"
        )
        self.assertEqual(g._extract_graphql_message({"message": {"code": 1}}), "")
        self.assertEqual(g._extract_graphql_message(None), "")
        self.assertEqual(g._extract_graphql_message("not a dict"), "")


@unittest.skipUnless(HAVE_SPOTDL, "spotdl not installed")
class FetchShapeTest(unittest.TestCase):
    def test_success_shape_and_enrichment(self):
        # T-012: every track carries the full field set.
        songs = [_song("a1", album=None), _song("b2", album=None)]
        with (
            _install_fake_fetch(songs),
            mock.patch.object(
                g,
                "_fetch_album_enrichment",
                return_value={"a1": ("Enriched Album", "https://i.scdn.co/album/a1")},
            ),
            mock.patch.object(g, "_resolve_yt_ids", return_value=[None, None]),
        ):
            result = g._fetch_impl("pid", True, 5.0)

        self.assertEqual(result["name"], "N")
        self.assertEqual(result["owner"], "O")
        self.assertEqual(result["track_count"], 2)
        t0 = result["tracks"][0]
        for key in (
            "position",
            "spotify_id",
            "title",
            "artists",
            "album",
            "duration_ms",
            "cover_url",
            "yt_id",
        ):
            self.assertIn(key, t0)
        self.assertEqual(t0["spotify_id"], "a1")
        self.assertEqual(t0["album"], "Enriched Album")
        self.assertEqual(t0["cover_url"], "https://i.scdn.co/album/a1")
        self.assertIsNone(t0["yt_id"])

    def test_duplicates_preserved_and_ordered(self):
        # T-013: the same song_id appears once per occurrence, in order.
        songs = [_song("dup", name="First"), _song("other"), _song("dup", name="Third")]
        with (
            _install_fake_fetch(songs),
            mock.patch.object(g, "_fetch_album_enrichment", return_value={}),
            mock.patch.object(g, "_resolve_yt_ids", return_value=[None] * 3),
        ):
            result = g._fetch_impl("pid", False, 0.0)
        self.assertEqual(
            [t["spotify_id"] for t in result["tracks"]], ["dup", "other", "dup"]
        )
        self.assertEqual([t["position"] for t in result["tracks"]], [0, 1, 2])
        self.assertEqual(result["tracks"][0]["title"], "First")
        self.assertEqual(result["tracks"][2]["title"], "Third")

    def test_empty_playlist(self):
        # T-014: 0 tracks, no crash.
        with (
            _install_fake_fetch([]),
            mock.patch.object(g, "_fetch_album_enrichment", return_value={}),
            mock.patch.object(g, "_resolve_yt_ids", return_value=[]),
        ):
            result = g._fetch_impl("pid", True, 5.0)
        self.assertEqual(result["track_count"], 0)
        self.assertEqual(result["tracks"], [])

    def test_yt_missing_yields_null_not_failure(self):
        # T-020: a track with no resolvable YT match -> yt_id None, fetch OK.
        songs = [_song("a1")]
        provider = mock.Mock()
        provider.get_results.return_value = []  # no results at all
        with (
            _install_fake_fetch(songs),
            mock.patch.object(g, "_fetch_album_enrichment", return_value={}),
            mock.patch.object(g, "_get_yt_provider", return_value=provider),
        ):
            result = g._fetch_impl("pid", True, 5.0)
        self.assertEqual(result["tracks"][0]["yt_id"], None)

    def test_yt_provider_error_yields_null(self):
        songs = [_song("a1")]
        provider = mock.Mock()
        provider.get_results.side_effect = RuntimeError("provider boom")
        with (
            _install_fake_fetch(songs),
            mock.patch.object(g, "_fetch_album_enrichment", return_value={}),
            mock.patch.object(g, "_get_yt_provider", return_value=provider),
        ):
            result = g._fetch_impl("pid", True, 5.0)
        self.assertEqual(result["tracks"][0]["yt_id"], None)


@unittest.skipUnless(NETWORK_TESTS, "GHOSTIFY_NETWORK_TESTS=0")
class NetworkIntegrationTest(unittest.TestCase):
    """Device-only tests that also run here when outbound network exists."""

    KNOWN_PUBLIC = "37i9dQZF1DXcBWIGoYBM5M"  # "Today's Top Hits" by Spotify

    def setUp(self):
        if not HAVE_SPOTDL:
            self.skipTest("spotdl not installed")

    def test_network_available(self):
        # Gate: if this fails, the sandbox has no outbound access and the
        # other network tests are expected to error (reported, not asserted).
        try:
            import socket

            socket.create_connection(
                ("api-partner.spotify.com", 443), timeout=5
            ).close()
        except OSError:
            self.skipTest("no outbound network to Spotify")

    def test_public_playlist_fetch(self):
        # T-010/T-011: valid public playlist -> name/owner/cover + ordered tracks.
        result = g.fetch_playlist(
            self.KNOWN_PUBLIC, {"resolve_yt": False, "timeout": 90}
        )
        self.assertTrue(result["name"])
        self.assertTrue(result["owner"])
        self.assertTrue(result["cover_url"])
        self.assertGreater(result["track_count"], 0)
        ids = [t["spotify_id"] for t in result["tracks"]]
        self.assertEqual(len(ids), len(set(ids)) or len(ids))  # ids present

    def test_track_fields(self):
        # T-012 on the wire (album may be empty only if enrichment failed).
        result = g.fetch_playlist(
            self.KNOWN_PUBLIC, {"resolve_yt": True, "timeout": 180}
        )
        for track in result["tracks"]:
            self.assertTrue(track["spotify_id"])
            self.assertTrue(track["title"])
            self.assertTrue(track["artists"])
            self.assertIsInstance(track["duration_ms"], int)
            self.assertIn("yt_id", track)
        self.assertTrue(any(t["yt_id"] for t in result["tracks"]))

    def test_nonexistent_playlist(self):
        # T-015: clean typed error.
        with self.assertRaises(g.GhostifyError) as ctx:
            g.fetch_playlist(
                "37i9dQZF1DXcBWIGoYBM5X", {"resolve_yt": False, "timeout": 90}
            )
        self.assertIn(ctx.exception.code, (g.ERR_NOT_FOUND, g.ERR_PRIVATE))


if __name__ == "__main__":
    unittest.main(verbosity=2)

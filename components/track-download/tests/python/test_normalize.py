"""T-URI: spotify:track: URI normalisation for spotdl 4.5.2 workaround.

spotdl 4.5.2's ``parse_query`` resolves wrong metadata when given the
``spotify:track:`` URI format — it silently falls back to unrelated songs.
The workaround converts ``spotify:track:`` URIs to HTTPS URLs before
calling ``parse_query``.

Run:  python -m unittest discover -s tests/python -p "test_normalize.py"
"""

from __future__ import annotations

import unittest

import fixtures  # noqa: F401  (bootstraps sys.path)

from ghostify_dl import extract_spotify_id, _normalize_spotify_url


class NormalizeSpotifyUrlTest(unittest.TestCase):
    def test_spotify_track_uri_converted_to_https(self):
        uri = "spotify:track:4h9wh7iOZ0GGn8QVp4RAOB"
        result = _normalize_spotify_url(uri)
        self.assertEqual(
            result, "https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB"
        )

    def test_https_url_unchanged(self):
        url = "https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB"
        result = _normalize_spotify_url(url)
        self.assertEqual(result, url)

    def test_http_url_unchanged(self):
        url = "http://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB"
        result = _normalize_spotify_url(url)
        self.assertEqual(result, url)

    def test_non_track_uri_unchanged(self):
        # playlist URI — not a track, left as-is
        uri = "spotify:playlist:2a7gq4yQrXk2M7au3Ekkys"
        result = _normalize_spotify_url(uri)
        self.assertEqual(result, uri)

    def test_bare_id_unchanged(self):
        # bare 22-char id is not a recognised URL/URI format
        bare = "4h9wh7iOZ0GGn8QVp4RAOB"
        result = _normalize_spotify_url(bare)
        self.assertEqual(result, bare)

    def test_non_string_unchanged(self):
        # defensive: non-string input passes through
        result = _normalize_spotify_url("not-a-url")
        self.assertEqual(result, "not-a-url")

    def test_extract_spotify_id_from_uri(self):
        uri = "spotify:track:4h9wh7iOZ0GGn8QVp4RAOB"
        self.assertEqual(extract_spotify_id(uri), "4h9wh7iOZ0GGn8QVp4RAOB")

    def test_extract_spotify_id_from_url(self):
        url = "https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB"
        self.assertEqual(extract_spotify_id(url), "4h9wh7iOZ0GGn8QVp4RAOB")

    def test_extract_spotify_id_from_bare(self):
        bare = "4h9wh7iOZ0GGn8QVp4RAOB"
        self.assertIsNone(extract_spotify_id(bare))


if __name__ == "__main__":
    unittest.main()

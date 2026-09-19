"""Tests for Spotify URL parsing and normalization."""

import pytest
from ghostify_dl import extract_spotify_id, _extract_playlist_id, _normalize_spotify_url, _is_spotify_url, GhostifyError


class TestExtractSpotifyId:
    def test_standard_track_url(self):
        url = "https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b"
        assert extract_spotify_id(url) == "0VjIjW4GlUZAMYd2vXMi3b"

    def test_track_uri(self):
        assert extract_spotify_id("spotify:track:0VjIjW4GlUZAMYd2vXMi3b") == "0VjIjW4GlUZAMYd2vXMi3b"

    def test_track_uri_with_slash(self):
        assert extract_spotify_id("spotify:track/0VjIjW4GlUZAMYd2vXMi3b") == "0VjIjW4GlUZAMYd2vXMi3b"

    def test_url_with_query_params(self):
        url = "https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b?si=abc"
        assert extract_spotify_id(url) == "0VjIjW4GlUZAMYd2vXMi3b"

    def test_playlist_url_returns_none(self):
        url = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        assert extract_spotify_id(url) is None

    def test_album_url_returns_none(self):
        url = "https://open.spotify.com/album/6JL0Z8CjHMIvek8P0xuogd"
        assert extract_spotify_id(url) is None

    def test_empty_string(self):
        assert extract_spotify_id("") is None

    def test_non_string(self):
        assert extract_spotify_id(None) is None
        assert extract_spotify_id(123) is None

    def test_non_spotify_url(self):
        assert extract_spotify_id("https://youtube.com/watch?v=abc") is None

    def test_truncated_id(self):
        assert extract_spotify_id("https://open.spotify.com/track/0VjIjW4") is None


class TestExtractPlaylistId:
    def test_bare_id(self):
        assert _extract_playlist_id("37i9dQZF1DXcBWIGoYBM5M") == "37i9dQZF1DXcBWIGoYBM5M"

    def test_full_url(self):
        assert _extract_playlist_id("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M") == "37i9dQZF1DXcBWIGoYBM5M"

    def test_spotify_uri(self):
        assert _extract_playlist_id("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M") == "37i9dQZF1DXcBWIGoYBM5M"

    def test_non_string_raises(self):
        with pytest.raises(GhostifyError):
            _extract_playlist_id(None)

    def test_whitespace_stripped(self):
        assert _extract_playlist_id("  37i9dQZF1DXcBWIGoYBM5M  ") == "37i9dQZF1DXcBWIGoYBM5M"


class TestNormalizeSpotifyUrl:
    def test_track_uri_to_url(self):
        result = _normalize_spotify_url("spotify:track:0VjIjW4GlUZAMYd2vXMi3b")
        assert result == "https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b"

    def test_already_url(self):
        url = "https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b"
        assert _normalize_spotify_url(url) == url

    def test_playlist_uri_unchanged(self):
        uri = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"
        assert _normalize_spotify_url(uri) == uri

    def test_non_spotify_unchanged(self):
        url = "https://youtube.com/watch?v=abc"
        assert _normalize_spotify_url(url) == url


class TestIsSpotifyUrl:
    def test_track_url(self):
        assert _is_spotify_url("https://open.spotify.com/track/abc") is True

    def test_track_uri(self):
        assert _is_spotify_url("spotify:track:abc") is True

    def test_non_spotify(self):
        assert _is_spotify_url("https://youtube.com/watch?v=abc") is False

    def test_non_string_raises(self):
        with pytest.raises(TypeError):
            _is_spotify_url(None)

"""Tests for YouTube URL parsing."""

from ghostify_dl import _is_youtube_playlist_url, _is_youtube_video_url, _extract_video_id


class TestIsYoutubePlaylistUrl:
    def test_standard_playlist(self):
        assert _is_youtube_playlist_url("https://www.youtube.com/playlist?list=PLabc") is True

    def test_short_playlist(self):
        assert _is_youtube_playlist_url("https://youtu.be/playlist?list=PLabc") is True

    def test_video_url(self):
        assert _is_youtube_playlist_url("https://www.youtube.com/watch?v=abc") is False

    def test_non_string(self):
        assert _is_youtube_playlist_url(None) is False
        assert _is_youtube_playlist_url(123) is False

    def test_non_youtube(self):
        assert _is_youtube_playlist_url("https://vimeo.com/123") is False


class TestIsYoutubeVideoUrl:
    def test_standard_video(self):
        assert _is_youtube_video_url("https://www.youtube.com/watch?v=dQw4w9WgXcQ") is True

    def test_short_url_with_params(self):
        assert _is_youtube_video_url("https://youtu.be/dQw4w9WgXcQ?si=abc") is True

    def test_short_url_without_params(self):
        assert _is_youtube_video_url("https://youtu.be/dQw4w9WgXcQ") is False

    def test_playlist_excluded(self):
        assert _is_youtube_video_url("https://www.youtube.com/playlist?list=PLabc") is False

    def test_non_string(self):
        assert _is_youtube_video_url(None) is False

    def test_non_youtube(self):
        assert _is_youtube_video_url("https://vimeo.com/123") is False


class TestExtractVideoId:
    def test_standard_url(self):
        assert _extract_video_id("https://www.youtube.com/watch?v=dQw4w9WgXcQ") == "dQw4w9WgXcQ"

    def test_url_with_extra_params(self):
        assert _extract_video_id("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc") == "dQw4w9WgXcQ"

    def test_none(self):
        assert _extract_video_id(None) is None

    def test_empty_string(self):
        assert _extract_video_id("") is None

    def test_no_v_param(self):
        assert _extract_video_id("https://www.youtube.com/embed/dQw4w9WgXcQ") is None

    def test_non_string(self):
        assert _extract_video_id(123) is None

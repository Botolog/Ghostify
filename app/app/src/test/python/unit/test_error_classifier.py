"""Tests for error classification helpers."""

from ghostify_dl import _classify_from_text, _kind_from_error_text, _exception_class_name, GhostifyError


class TestClassifyFromText:
    def test_rate_limit(self):
        err = _classify_from_text("429 rate limit exceeded")
        assert err.code == "RATE_LIMITED"

    def test_rate_limit_keyword(self):
        err = _classify_from_text("You are being rate limited")
        assert err.code == "RATE_LIMITED"

    def test_private(self):
        err = _classify_from_text("This playlist is private")
        assert err.code == "PRIVATE"

    def test_forbidden(self):
        err = _classify_from_text("403 Forbidden")
        assert err.code == "PRIVATE"

    def test_not_found(self):
        err = _classify_from_text("404 Not Found")
        assert err.code == "NOT_FOUND"

    def test_deleted(self):
        err = _classify_from_text("This playlist has been deleted")
        assert err.code == "NOT_FOUND"

    def test_unknown(self):
        err = _classify_from_text("something weird happened")
        assert err.code == "UNKNOWN"

    def test_empty_string(self):
        err = _classify_from_text("")
        assert err.code == "UNKNOWN"

    def test_case_insensitive(self):
        err = _classify_from_text("RATE LIMIT hit")
        assert err.code == "RATE_LIMITED"

    def test_returns_ghostify_error(self):
        err = _classify_from_text("not found")
        assert isinstance(err, GhostifyError)


class TestKindFromErrorText:
    def test_yt_dlp_failed(self):
        assert _kind_from_error_text("yt-dlp failed to download") == "SEARCH_FAILED"

    def test_no_longer_exists(self):
        assert _kind_from_error_text("video no longer exists") == "METADATA_FAILED"

    def test_ffmpeg_error(self):
        assert _kind_from_error_text("ffmpeg error during conversion") == "CONVERSION_FAILED"

    def test_age_restricted(self):
        assert _kind_from_error_text("video is age-restricted") == "AUDIO_UNAVAILABLE"

    def test_unknown_text(self):
        assert _kind_from_error_text("completely random text") == "UNKNOWN"

    def test_empty(self):
        assert _kind_from_error_text("") == "UNKNOWN"


class TestExceptionClassName:
    def test_standard_spotdl_format(self):
        assert _exception_class_name("url - QueryError: not found") == "QueryError"

    def test_no_match(self):
        assert _exception_class_name("just a plain error message") == ""

    def test_nested_class(self):
        result = _exception_class_name("url - module.submodule.CustomError: msg")
        assert result == "module.submodule.CustomError"

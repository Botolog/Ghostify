"""Tests for GhostifyError, TrackDownloadError, and ErrorKind."""

import pytest
from ghostify_dl import GhostifyError, TrackDownloadError, ErrorKind


class TestGhostifyError:
    def test_str_format(self):
        err = GhostifyError("NOT_FOUND", "playlist not found")
        assert str(err) == "NOT_FOUND: playlist not found"

    def test_code_and_message_attributes(self):
        err = GhostifyError("TIMEOUT", "took too long")
        assert err.code == "TIMEOUT"
        assert err.message == "took too long"

    def test_is_exception(self):
        with pytest.raises(GhostifyError):
            raise GhostifyError("UNKNOWN", "boom")

    @pytest.mark.parametrize("code", [
        "NO_NETWORK", "RATE_LIMITED", "PRIVATE", "TIMEOUT", "NOT_FOUND", "UNKNOWN",
    ])
    def test_valid_codes(self, code):
        err = GhostifyError(code, "msg")
        assert err.code == code

    def test_invalid_code_raises(self):
        with pytest.raises(ValueError, match="Unknown error code"):
            GhostifyError("INVALID_CODE", "msg")

    def test_catch_as_exception(self):
        try:
            raise GhostifyError("PRIVATE", "secret")
        except Exception as e:
            assert isinstance(e, GhostifyError)
            assert e.code == "PRIVATE"


class TestTrackDownloadError:
    def test_str_format(self):
        err = TrackDownloadError("SEARCH_FAILED", "no results")
        assert str(err) == "SEARCH_FAILED: no results"

    def test_to_dict(self):
        err = TrackDownloadError("AUDIO_UNAVAILABLE", "blocked")
        d = err.to_dict()
        assert d == {"error_type": "AUDIO_UNAVAILABLE", "error": "blocked"}

    def test_kind_and_message(self):
        err = TrackDownloadError("CONVERSION_FAILED", "ffmpeg error")
        assert err.kind == "CONVERSION_FAILED"
        assert err.message == "ffmpeg error"

    @pytest.mark.parametrize("kind", [
        "NO_TRACK", "METADATA_FAILED", "SEARCH_FAILED", "AUDIO_UNAVAILABLE",
        "CONVERSION_FAILED", "TAGGING_FAILED", "VALIDATION_FAILED",
        "IO", "INTERRUPTED", "DEPENDENCY", "UNKNOWN",
    ])
    def test_valid_kinds(self, kind):
        err = TrackDownloadError(kind, "msg")
        assert err.kind == kind


class TestErrorKind:
    def test_all_attributes_are_strings(self):
        attrs = [
            "NO_TRACK", "METADATA_FAILED", "SEARCH_FAILED", "AUDIO_UNAVAILABLE",
            "CONVERSION_FAILED", "TAGGING_FAILED", "VALIDATION_FAILED",
            "IO", "INTERRUPTED", "DEPENDENCY", "UNKNOWN",
        ]
        for attr in attrs:
            value = getattr(ErrorKind, attr)
            assert isinstance(value, str)
            assert value == attr

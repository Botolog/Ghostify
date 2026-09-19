"""Tests for normalization helpers."""

import pytest
from ghostify_dl import _normalize_bitrate, _normalize_template, _normalize_options, _clean, GhostifyError


class TestNormalizeBitrate:
    def test_none_returns_default(self):
        assert _normalize_bitrate(None) == "320k"

    def test_int_128(self):
        assert _normalize_bitrate(128) == "128k"

    def test_int_192(self):
        assert _normalize_bitrate(192) == "192k"

    def test_int_320(self):
        assert _normalize_bitrate(320) == "320k"

    def test_string_with_k(self):
        assert _normalize_bitrate("192k") == "192k"

    def test_string_without_k(self):
        assert _normalize_bitrate("128") == "128k"

    def test_case_insensitive(self):
        assert _normalize_bitrate("128K") == "128k"

    def test_stripped(self):
        assert _normalize_bitrate("  192k  ") == "192k"

    def test_invalid_value(self):
        with pytest.raises(ValueError):
            _normalize_bitrate("256k")

    def test_non_numeric(self):
        with pytest.raises(ValueError):
            _normalize_bitrate("abc")


class TestNormalizeTemplate:
    def test_empty_returns_default(self):
        assert _normalize_template("") == "{artists} - {title}"

    def test_none_returns_default(self):
        assert _normalize_template(None) == "{artists} - {title}"

    def test_strips_mp3_extension(self):
        result = _normalize_template("my_song.mp3")
        assert result == "my_song"

    def test_preserves_output_ext(self):
        result = _normalize_template("my_song.{output-ext}")
        assert result == "my_song.{output-ext}"

    def test_backslash_to_forward_slash(self):
        result = _normalize_template("path\\to\\file")
        assert "\\" not in result

    def test_non_string_returns_default(self):
        assert _normalize_template(123) == "{artists} - {title}"


class TestNormalizeOptions:
    def test_none_returns_defaults(self):
        opts = _normalize_options(None)
        assert "timeout" in opts
        assert "resolve_yt" in opts

    def test_empty_dict_returns_defaults(self):
        opts = _normalize_options({})
        assert opts["timeout"] is not None

    def test_partial_override(self):
        opts = _normalize_options({"timeout": 30})
        assert opts["timeout"] == 30

    def test_invalid_timeout_raises(self):
        with pytest.raises(GhostifyError):
            _normalize_options({"timeout": "not_a_number"})


class TestClean:
    def test_none(self):
        assert _clean(None) is None

    def test_empty_string(self):
        assert _clean("") is None

    def test_whitespace_only(self):
        assert _clean("   ") is None

    def test_strips_whitespace(self):
        assert _clean("  hello  ") == "hello"

    def test_non_string(self):
        assert _clean(42) == "42"

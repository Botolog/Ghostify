"""Tests for miscellaneous helper functions."""

from ghostify_dl import _matches, _first_text, _collect_error_texts, _chain_contains, _kind_for, TrackDownloadError, ErrorKind


class TestMatches:
    def test_empty_patterns(self):
        assert _matches("hello", []) is False

    def test_matching_pattern(self):
        assert _matches("hello world", ["hello"]) is True

    def test_no_match(self):
        assert _matches("hello", ["xyz"]) is False

    def test_multiple_patterns_first_matches(self):
        assert _matches("hello", ["hello", "world"]) is True

    def test_multiple_patterns_second_matches(self):
        assert _matches("world", ["hello", "world"]) is True


class TestFirstText:
    def test_empty_list(self):
        assert _first_text([]) == ""

    def test_single_element(self):
        assert _first_text(["hello"]) == "hello"

    def test_multiple_elements(self):
        assert _first_text(["first", "second"]) == "first"


class TestCollectErrorTexts:
    def test_simple_exception(self):
        exc = ValueError("simple error")
        texts = _collect_error_texts(exc)
        assert "simple error" in texts

    def test_chained_exceptions(self):
        try:
            try:
                raise ValueError("inner")
            except ValueError as inner:
                raise RuntimeError("outer") from inner
        except RuntimeError as e:
            texts = _collect_error_texts(e)
            assert "outer" in texts
            assert "inner" in texts

    def test_deduplication(self):
        exc = ValueError("same message")
        texts = _collect_error_texts(exc)
        assert texts.count("same message") == 1


class TestChainContains:
    def test_no_match(self):
        exc = ValueError("nope")
        assert _chain_contains(exc, (TypeError,)) is False

    def test_direct_match(self):
        exc = TypeError("match")
        assert _chain_contains(exc, (TypeError,)) is True

    def test_chained_match(self):
        try:
            try:
                raise TypeError("inner")
            except TypeError:
                raise RuntimeError("outer")
        except RuntimeError as e:
            assert _chain_contains(e, (TypeError,)) is True

    def test_empty_types(self):
        exc = ValueError("test")
        assert _chain_contains(exc, ()) is False


class TestKindFor:
    def test_track_download_error(self):
        err = TrackDownloadError(ErrorKind.SEARCH_FAILED, "msg")
        assert _kind_for(err) == ErrorKind.SEARCH_FAILED

    def test_keyboard_interrupt(self):
        assert _kind_for(KeyboardInterrupt()) == "INTERRUPTED"

    def test_os_error(self):
        assert _kind_for(OSError("disk full")) == "IO"

    def test_unknown_exception(self):
        kind = _kind_for(ValueError("random"))
        assert isinstance(kind, str)

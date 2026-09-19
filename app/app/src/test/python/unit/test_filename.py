"""Tests for sanitize_filename."""

from ghostify_dl import sanitize_filename


class TestSanitizeFilename:
    def test_empty_string(self):
        assert sanitize_filename("") == "_"

    def test_only_separators(self):
        assert sanitize_filename("---") == "_"

    def test_only_spaces(self):
        assert sanitize_filename("   ") == "_"

    def test_only_dangerous_chars(self):
        assert sanitize_filename("?*|<>") == "_"

    def test_normal_filename(self):
        assert sanitize_filename("Hello World") == "Hello World"

    def test_colon_replaced(self):
        result = sanitize_filename("Artist: Song")
        assert ":" not in result
        assert "-" in result

    def test_forward_slash_replaced(self):
        result = sanitize_filename("path/to/file")
        assert "/" not in result

    def test_backslash_replaced(self):
        result = sanitize_filename("path\\to\\file")
        assert "\\" not in result

    def test_double_quotes_replaced_with_single(self):
        result = sanitize_filename('He said "hello"')
        assert '"' not in result
        assert "'" in result

    def test_trailing_dots_stripped(self):
        result = sanitize_filename("file...")
        assert not result.endswith(".")

    def test_trailing_spaces_stripped(self):
        result = sanitize_filename("file   ")
        assert result == "file"

    def test_custom_separator(self):
        result = sanitize_filename("path/to/file", separator="_")
        assert "/" not in result
        assert "_" in result

    def test_unicode_nfc_normalization(self):
        import unicodedata
        composed = unicodedata.normalize("NFC", "caf\u00e9")
        decomposed = unicodedata.normalize("NFC", "cafe\u0301")
        assert sanitize_filename(composed) == sanitize_filename(decomposed)

    def test_preserves_clean_name(self):
        assert sanitize_filename("track-01.mp3") == "track-01.mp3"

    def test_multiple_dangerous_chars(self):
        result = sanitize_filename("a/b:c\\d*e?f\"g<h>i|j")
        for ch in "/\\:*?\"<>|":
            assert ch not in result

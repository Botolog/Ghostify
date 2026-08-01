"""T-038 (locally-verifiable portion): filename sanitisation.

The on-device part of T-038 downloads a track whose title contains invalid
characters and asserts the file is still written with a safe name. spotdl
performs that sanitisation itself (its own ``sanitize_string``, which we reuse
for output-path detection); this unit test pins the wrapper's standalone
``sanitize_filename`` contract for every invalid character the plan lists.

Run:  python -m unittest discover -s tests/python -p "test_sanitize.py"
"""

from __future__ import annotations

import unittest

import fixtures  # noqa: F401  (bootstraps sys.path)

from ghostify_dl import sanitize_filename


class SanitizeFilenameTest(unittest.TestCase):
    def test_removes_all_invalid_characters(self):
        # T-038 set: / \ : * ? " < > |
        dirty = 'ac/dc"live"  \\backup:one*two?<this>|that'
        clean = sanitize_filename(dirty)
        for char in '/\\:*?"<>|':
            self.assertNotIn(char, clean)

    def test_never_returns_path_separators(self):
        for name in ["a/b", "a\\b", "/etc/passwd", "../../etc", "C:\\\\evil"]:
            self.assertNotIn("/", sanitize_filename(name))
            self.assertNotIn("\\", sanitize_filename(name))

    def test_collapses_repeated_spaces(self):
        self.assertNotIn("  ", sanitize_filename("Artist  with    spaces"))

    def test_strips_trailing_dots_and_spaces(self):
        self.assertEqual(sanitize_filename("Name...  "), "Name")

    def test_replaces_double_quote_with_apostrophe(self):
        self.assertIn("'", sanitize_filename('He said "hi"'))

    def test_empty_result_becomes_placeholder(self):
        self.assertEqual(sanitize_filename("???"), "_")
        self.assertEqual(sanitize_filename("///"), "_")

    def test_accepts_unicode_and_normalises(self):
        # Combining sequence normalised to NFC: precomposed char kept intact.
        self.assertEqual(sanitize_filename("caf\u0065\u0301"), "caf\u00e9")

    def test_rejects_non_strings(self):
        with self.assertRaises(TypeError):
            sanitize_filename(None)  # type: ignore[arg-type]
        with self.assertRaises(TypeError):
            sanitize_filename(42)  # type: ignore[arg-type]


if __name__ == "__main__":
    unittest.main()

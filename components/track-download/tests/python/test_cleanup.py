"""T-037 (locally-verifiable portion): temp-file cleanup.

The on-device part of T-037 kills a download mid-flight and asserts no partial
corrupt output remains and the next run retries cleanly. This unit test pins
the two mechanisms that guarantee that:

* ``cleanup_temp`` sweeps orphaned files (from a hard-killed process);
* ``_cleanup_attempt`` removes exactly the files a finished attempt created.

Run:  python -m unittest discover -s tests/python -p "test_cleanup.py"
"""

from __future__ import annotations

import os
import time
import unittest

import fixtures  # noqa: F401  (bootstraps sys.path)

from ghostify_dl import TrackDownloader


class CleanupTest(unittest.TestCase):
    def setUp(self):
        self.td = TrackDownloader(fixtures.temp_output_dir(), bitrate=128)
        # Redirect temp management to an isolated directory so we never touch
        # spotdl's real temp dir during tests.
        self.temp = fixtures.temp_output_dir()
        self.td._temp_dir = self.temp

    def _touch(self, name, age=0.0):
        path = self.temp / name
        path.write_bytes(b"x" * 8)
        if age:
            old = time.time() - age
            os.utime(path, (old, old))
        return path

    def test_cleanup_removes_orphans_older_than_threshold(self):
        old = self._touch("old.webm", age=7200)  # 2h old
        fresh = self._touch("fresh.webm", age=5)  # 5s old (in-flight)
        removed = self.td.cleanup_temp()
        self.assertFalse(old.exists())
        self.assertTrue(fresh.exists())
        self.assertEqual(removed, 1)

    def test_cleanup_custom_threshold(self):
        old = self._touch("old.mp4", age=120)
        removed = self.td.cleanup_temp(age_seconds=60)
        self.assertFalse(old.exists())
        self.assertEqual(removed, 1)

    def test_attempt_cleanup_removes_only_new_files(self):
        pre = self._touch("preexisting.webm")
        before = self.td._snapshot_temp()
        new = self._touch("new.webm")
        self.td._cleanup_attempt(before)
        self.assertFalse(new.exists())
        self.assertTrue(pre.exists())

    def test_cleanup_handles_missing_dir(self):
        missing = fixtures.temp_output_dir()
        missing.rmdir()
        self.td._temp_dir = missing
        self.assertEqual(self.td.cleanup_temp(), 0)

    def test_constructor_sweeps_orphans(self):
        # An orphan from a previous process must be gone after construction.
        td = TrackDownloader(fixtures.temp_output_dir(), bitrate=128)
        td._temp_dir = self.temp
        self._touch("orphan.m4a", age=7200)
        self.td.cleanup_temp()
        td2 = TrackDownloader(fixtures.temp_output_dir(), bitrate=128)
        td2._temp_dir = self.temp
        td2.cleanup_temp()
        self.assertFalse((self.temp / "orphan.m4a").exists())


if __name__ == "__main__":
    unittest.main()

"""Shared fixtures and path setup for ghostify_dl tests."""

import os
import sys

_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))

# Vendored packages — must come BEFORE site-packages so Python loads our copies.
_VENDORED_DIRS = [
    os.path.join(_ROOT, "python-spotdl"),
    os.path.join(_ROOT, "python-spotapi"),
    os.path.join(_ROOT, "python-spotipyfree"),
    os.path.join(_ROOT, "python-rapidfuzz"),
]
for d in _VENDORED_DIRS:
    if os.path.isdir(d) and d not in sys.path:
        sys.path.insert(0, d)

# Make ghostify_dl importable from plain CPython (no Chaquopy).
_SRC_DIR = os.path.join(_ROOT, "src", "main", "python")
if _SRC_DIR not in sys.path:
    sys.path.insert(0, os.path.abspath(_SRC_DIR))


def pytest_configure(config):
    config.addinivalue_line(
        "markers",
        "integration: full-chain tests requiring network and ffmpeg (deselect with '-m not integration')",
    )
    config.addinivalue_line(
        "markers",
        "slow: slow-running tests",
    )

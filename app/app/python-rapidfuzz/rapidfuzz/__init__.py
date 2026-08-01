"""
rapidfuzz pure-Python shim for Android (Chaquopy).

Chaquopy's package mirror ships no ``rapidfuzz`` wheel (it is a C++ extension),
so this module provides the small API surface spotdl touches, implemented with
the stdlib ``difflib``. Scores match rapidfuzz's 0..100 convention; the exact
numeric values differ slightly from the C++ original, which spotdl tolerates
(matching is heuristic and threshold-based).
"""

from rapidfuzz import fuzz, process, utils

__all__ = ["fuzz", "process", "utils"]

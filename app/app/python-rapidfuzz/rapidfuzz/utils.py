"""
Helpers mirroring ``rapidfuzz.utils`` (difflib-based).
"""

import re

__all__ = ["default_process"]

_NON_ALNUM = re.compile(r"[^a-z0-9]+")


def default_process(s):
    """Lowercase and replace runs of non-alphanumerics with a space."""
    if not isinstance(s, str):
        s = str(s)
    return _NON_ALNUM.sub(" ", s).strip()

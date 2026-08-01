"""
Fuzzy string matching functions, mirroring ``rapidfuzz.fuzz`` (difflib-based).
"""

import re
from difflib import SequenceMatcher

__all__ = [
    "ratio",
    "partial_ratio",
    "token_sort_ratio",
    "token_set_ratio",
    "partial_token_sort_ratio",
    "partial_token_set_ratio",
    "QRatio",
    "WRatio",
]

_NON_ALNUM = re.compile(r"[^a-z0-9]+")


def _norm(value):
    if not isinstance(value, str):
        value = str(value)
    return value


def ratio(s1, s2):
    """Normalized similarity ratio, 0..100."""
    s1, s2 = _norm(s1), _norm(s2)
    if not s1 and not s2:
        return 100.0
    if not s1 or not s2:
        return 0.0
    return SequenceMatcher(None, s1, s2).ratio() * 100.0


def _best_partial_ratio(s1, s2):
    """Match the shorter string against every window of the longer one."""
    if s1 == s2:
        return 100.0
    shorter, longer = (s1, s2) if len(s1) <= len(s2) else (s2, s1)
    if not shorter:
        return 0.0
    best = 0.0
    window = len(shorter)
    for start in range(len(longer) - window + 1):
        best = max(
            best, SequenceMatcher(None, shorter, longer[start : start + window]).ratio()
        )
    return best * 100.0


def partial_ratio(s1, s2):
    s1, s2 = _norm(s1), _norm(s2)
    if not s1 and not s2:
        return 100.0
    return _best_partial_ratio(s1, s2)


def _tokens(s):
    return _NON_ALNUM.sub(" ", _norm(s).lower()).split()


def token_sort_ratio(s1, s2, partial=False):
    a = " ".join(sorted(_tokens(s1)))
    b = " ".join(sorted(_tokens(s2)))
    return partial_ratio(a, b) if partial else ratio(a, b)


def token_set_ratio(s1, s2, partial=False):
    a, b = _tokens(s1), _tokens(s2)
    intersection = " ".join(sorted(set(a) & set(b)))
    if not intersection:
        return token_sort_ratio(s1, s2, partial=partial)
    same = " ".join(sorted(set(a) & set(b)))
    only_a = " ".join(sorted(set(a) - set(b)))
    only_b = " ".join(sorted(set(b) - set(a)))
    return max(
        ratio(same, only_a + " " + same),
        ratio(same, only_b + " " + same),
    )


def partial_token_sort_ratio(s1, s2):
    return token_sort_ratio(s1, s2, partial=True)


def partial_token_set_ratio(s1, s2):
    return token_set_ratio(s1, s2, partial=True)


def QRatio(s1, s2):
    """Ratio after whitespace normalization."""
    a = _NON_ALNUM.sub(" ", _norm(s1).strip().lower())
    b = _NON_ALNUM.sub(" ", _norm(s2).strip().lower())
    return ratio(a, b)


def WRatio(s1, s2):
    """
    Weighted ratio: prefers token_set over token_sort, boosts partial matches,
    weighted so exact/very-close matches reach ~100.
    """
    s1, s2 = _norm(s1), _norm(s2)
    if not s1 and not s2:
        return 100.0
    unscaled = token_set_ratio(s1, s2)
    if unscaled >= 95:
        return max(100.0, unscaled)
    return max(unscaled, partial_token_sort_ratio(s1, s2), partial_ratio(s1, s2) * 0.9)

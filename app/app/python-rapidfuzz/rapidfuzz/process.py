"""
Rapid matching helpers mirroring ``rapidfuzz.process`` (difflib-based).
"""

from rapidfuzz import fuzz as _fuzz

__all__ = ["extractOne", "extract", "extract_iter"]


def _normalize_choices(choices):
    if isinstance(choices, dict):
        return list(choices.keys()), choices
    return list(choices), None


def extractOne(
    query,
    choices,
    scorer=_fuzz.WRatio,
    processor=None,
    score_cutoff=0,
    score_hint=None,
):
    """
    Return ``(choice, score, index)`` for the single best match above
    ``score_cutoff``, or ``None`` if none qualify.
    """
    best = None
    best_score = score_cutoff
    for index, choice in enumerate(choices):
        candidate = choice if processor is None else processor(choice)
        score = scorer(query, candidate)
        if score > best_score:
            best = (choice, score, index)
            best_score = score
    return best


def extract(
    query,
    choices,
    scorer=_fuzz.WRatio,
    processor=None,
    limit=5,
    score_cutoff=0,
):
    """Return up to ``limit`` ``(choice, score, index)`` tuples, best first."""
    results = []
    for index, choice in enumerate(choices):
        candidate = choice if processor is None else processor(choice)
        score = scorer(query, candidate)
        if score >= score_cutoff:
            results.append((choice, score, index))
    results.sort(key=lambda item: item[1], reverse=True)
    return results[:limit]


def extract_iter(query, choices, scorer=_fuzz.WRatio, processor=None, score_cutoff=0):
    """Lazy generator of ``(choice, score, index)`` tuples, best first."""
    results = extract(
        query,
        choices,
        scorer=scorer,
        processor=processor,
        score_cutoff=score_cutoff,
        limit=None,
    )
    for item in results:
        yield item

"""Advisory OpenRouter/JEV selector for the YouTube video-per-song decision.

INTENTIONALLY DISABLED: the JEV runtime gate below forces every decision
to ``inactive`` and every transport to refuse, so no OpenRouter request can
be made from production or from the demo tooling. The module stays in-tree
for its deterministic helpers (shortlist, payload, validation) and for the
demo display, but ``decide``/``select_video_id`` never reach the network.
Re-enabling requires an intentional code change here AND in
``ghostify_dl``; setting ``OPENROUTER_API_KEY``/``GHOSTIFY_JEV_ENABLED``
alone must not re-enable it.

``ghostify_dl._resolve_yt_id`` historically returned the *first* search result
that carried a video id. That is a sane default but it cannot tell an official
recording from a cover, a live bootleg or a sped-up edit, which is the most
common cause of "the wrong song got downloaded". This module adds an
*advisory* second opinion: JEV (TypeSafe's decision model) is asked which
shortlisted video is the canonical recording and its answer is accepted only
when it is provably safe.

Design rules (all of them are production-safety requirements, not style):

* **Advisory only.** Every failure mode - no key, disabled flag, network
  error, timeout, HTTP error, malformed body, unparsable answer, hallucinated
  id, low confidence - resolves to ``None``, and the caller keeps the
  deterministic candidate it would have picked anyway. Nothing here can break
  a download.
* **No behaviour change without a key.** The deterministic fallback is computed
  by the caller from the raw provider result order, exactly as before, and is
  never recomputed from the shortlist. The shortlist only decides *what JEV is
  allowed to see*.
* **A model can never invent a video id.** JEV answers a ``choice`` question
  whose ``criteria`` keys *are* the shortlisted video ids, and the returned
  choice is rejected unless it is a member of that set.
* **Bounded.** At most ``timeout`` seconds per attempt (default 4s, clamped by
  the caller's per-track budget), at most ``max_candidates`` criteria (default
  6), and a non-blocking concurrency gate so a large playlist cannot fan out
  unbounded parallel calls. The request stays small by shortlisting few
  candidates, never by cutting a title or a channel name short: both are sent
  verbatim, because the distinctions JEV has to make ("(Official Video)",
  "Live at ...", a remix marker) usually live at the end of a long title.
* **No duplicated payload.** The candidates are sent exactly once, as the
  ``criteria`` of the choice question, keyed by video id; ``state`` carries only
  the minimal song context (title, artists, length). Criteria values are
  compact description strings - title, channel, length, official flag - and
  never carry a url, an album or a provider name, so no candidate metadata - and
  no candidate text - is ever repeated inside ``state``.
* **Secret hygiene.** The key is read from ``os.environ`` only, is excluded
  from ``JevConfig.__repr__``, never reaches a log record, and never leaves
  this module. Logs carry candidate *counts*, outcome labels, rejection labels,
  sanitised scalars (an integer HTTP status, a short provider error code, a
  float confidence) and exception class names only - never headers, request
  bodies, candidate text or ``str(exc)``.
* **Every rejection is named.** A refused answer is not one anonymous "rejected":
  the reason distinguishes a missing/unexpected ``answers`` object, a non-choice
  answer, a returned id that is not in the shortlist, a confidence below the
  threshold and probabilities that disagree with the choice. The explanation is
  assembled from this module's own labels and from numbers the response carried,
  never from response text.

Configuration (environment; every knob is optional):

=========================================  ==================================
``OPENROUTER_API_KEY``                     Secret. Absent/empty disables JEV.
``GHOSTIFY_JEV_ENABLED``                   ``0``/``1``; default: enabled iff a
                                           key is present.
``GHOSTIFY_JEV_MODEL``                     Default ``typesafe/jev-1.13``.
``GHOSTIFY_JEV_ENDPOINT``                  Default the OpenRouter Decisions
                                           alpha path.
``GHOSTIFY_JEV_TIMEOUT``                   Seconds, default 4, clamped 0.1..30.
``GHOSTIFY_JEV_MAX_CANDIDATES``            Default 6, clamped 2..25.
``GHOSTIFY_JEV_MIN_CONFIDENCE``            Default 0.6, clamped 0..1.
=========================================  ==================================
"""

from __future__ import annotations

import logging
import os
import re
import threading
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, FrozenSet, List, Optional, Sequence, Tuple

__all__ = [
    "Candidate",
    "Decision",
    "JevConfig",
    "JevTransportError",
    "OUTCOME_BUSY",
    "OUTCOME_CHOSE",
    "OUTCOME_INACTIVE",
    "OUTCOME_NO_CANDIDATES",
    "OUTCOME_REQUEST_FAILED",
    "OUTCOME_REJECTED",
    "OUTCOME_UNAMBIGUOUS",
    "REJECTION_ANSWER_TYPE",
    "REJECTION_LOW_CONFIDENCE",
    "REJECTION_NO_ANSWER",
    "REJECTION_PROBABILITIES",
    "REJECTION_UNKNOWN_ID",
    "Shortlist",
    "build_payload",
    "build_shortlist",
    "decide",
    "parse_choice",
    "select_video_id",
]

logger = logging.getLogger("ghostify_dl.jev")

# ---------------------------------------------------------------------------
# JEV runtime gate (intentionally disabled).
# ---------------------------------------------------------------------------
# Master switch: False means no OpenRouter request may be made. ``decide``
# returns ``inactive`` without touching ``transport``, ``JevConfig.from_env``
# forces ``enabled`` to False regardless of the environment, and
# ``_post_json`` refuses outright. This covers production
# (``ghostify_dl._select_yt_id``/``compare_yt_selection``) and the demo
# tooling (which only reaches JEV through those entry points or through
# ``_post_json``).
_JEV_RUNTIME_ENABLED = False


def _is_jev_runtime_enabled() -> bool:
    """Master JEV gate — always False while JEV is intentionally disabled."""
    return False


# ---------------------------------------------------------------------------
# Constants.
# ---------------------------------------------------------------------------

ENV_API_KEY = "OPENROUTER_API_KEY"
ENV_ENABLED = "GHOSTIFY_JEV_ENABLED"
ENV_MODEL = "GHOSTIFY_JEV_MODEL"
ENV_ENDPOINT = "GHOSTIFY_JEV_ENDPOINT"
ENV_TIMEOUT = "GHOSTIFY_JEV_TIMEOUT"
ENV_MAX_CANDIDATES = "GHOSTIFY_JEV_MAX_CANDIDATES"
ENV_MIN_CONFIDENCE = "GHOSTIFY_JEV_MIN_CONFIDENCE"

DEFAULT_MODEL = "typesafe/jev-1.13"
DEFAULT_ENDPOINT = "https://openrouter.ai/api/alpha/decisions"
DEFAULT_TIMEOUT = 4.0
DEFAULT_MAX_CANDIDATES = 6
DEFAULT_MIN_CONFIDENCE = 0.6
DEFAULT_DURATION_TOLERANCE = 90.0

MIN_CANDIDATES_FOR_CHOICE = 2
MIN_TIMEOUT = 0.1
MAX_TIMEOUT = 30.0
MIN_CANDIDATE_LIMIT = 2
MAX_CANDIDATE_LIMIT = 25
QUESTION_ID = "recording"

OUTCOME_NO_CANDIDATES = "no_candidates"
OUTCOME_INACTIVE = "inactive"
OUTCOME_UNAMBIGUOUS = "unambiguous"
OUTCOME_BUSY = "busy"
OUTCOME_REQUEST_FAILED = "request_failed"
OUTCOME_REJECTED = "rejected"
OUTCOME_CHOSE = "chose"

REJECTION_NO_ANSWER = "no_answer"
REJECTION_ANSWER_TYPE = "answer_type"
REJECTION_UNKNOWN_ID = "unknown_id"
REJECTION_LOW_CONFIDENCE = "low_confidence"
REJECTION_PROBABILITIES = "probability_disagreement"

_REJECTION_TEXT = {
    REJECTION_NO_ANSWER: "answer rejected: the response carried no usable answer",
    REJECTION_ANSWER_TYPE: "answer rejected: the answer is not a choice",
    REJECTION_UNKNOWN_ID: "answer rejected: the returned id is not in the shortlist",
    REJECTION_LOW_CONFIDENCE: "answer rejected: confidence below the threshold",
    REJECTION_PROBABILITIES: "answer rejected: the probabilities disagree with the choice",
}

_JEV_CONCURRENCY = 4
_JEV_SEMAPHORE = threading.Semaphore(_JEV_CONCURRENCY)

_TRUTHY = frozenset({"1", "true", "yes", "on", "enable", "enabled"})
_FALSY = frozenset({"0", "false", "no", "off", "disable", "disabled"})

_YOUTUBE_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{11}$")
_VIDEO_ID_URL_PATTERNS = (
    re.compile(r"[?&]v=([A-Za-z0-9_-]{11})"),
    re.compile(r"youtu\.be/([A-Za-z0-9_-]{11})"),
    re.compile(r"/embed/([A-Za-z0-9_-]{11})"),
)

_SAFE_TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9_.-]{1,40}$")
_SECRET_FRAGMENTS = ("sk-", "or-v1-", "bearer", "authorization")

_INSTRUCTIONS = (
    "Pick the one candidate that is the recording of the requested song and of "
    "the requested version, as a listener would expect it: ideally the official "
    "audio published by the artist or their label. Reject unrelated songs, "
    "covers by other artists, live bootlegs, commentary and lyric videos, and "
    "fan uploads, whenever an official upload is among the candidates."
)


class JevTransportError(RuntimeError):
    """Raised for a non-2xx response or an unparsable body.

    Carries only what is safe to show: an integer HTTP status and a short
    provider error code, both re-validated on assignment. The response body and
    the request headers are never stored, and :attr:`detail` never falls back to
    ``str(self)``, so the exception can be logged or reported as-is.
    """

    def __init__(
        self, message: str = "", status: Any = None, code: Any = None
    ) -> None:
        super().__init__(message)
        self.status = _safe_status(status)
        self.code = _safe_token(code)

    @property
    def detail(self) -> str:
        """``HTTP 401, provider code auth_error`` - scalars only, or ``""``."""
        if self.status is None:
            return ""
        text = "HTTP %d" % self.status
        if self.code:
            text += ", provider code %s" % self.code
        return text


# ---------------------------------------------------------------------------
# Small value helpers.
# ---------------------------------------------------------------------------


def _clean_str(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def _parse_bool(value: Any, default: bool) -> bool:
    text = _clean_str(value).lower()
    if not text:
        return default
    if text in _TRUTHY:
        return True
    if text in _FALSY:
        return False
    return default


def _to_float(value: Any) -> Optional[float]:
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if number != number:  # NaN
        return None
    return number


def _clamped_float(
    value: Any, default: float, low: float, high: float
) -> float:
    number = _to_float(value)
    if number is None:
        return default
    return max(low, min(high, number))


def _one_line(value: Any) -> str:
    """Collapse *value* onto a single whitespace-normalised line, in full.

    Deliberately not a truncation: a candidate title or channel name is sent
    exactly as the provider reported it, so JEV sees the parts that identify the
    recording (an "(Official Video)" suffix, a channel name, a version marker).
    Only newlines/tabs/repeated spaces are normalised, so the text cannot break
    the one-line criteria format.
    """
    return " ".join(_clean_str(value).split())


def _attr(source: Any, *names: str) -> Any:
    for name in names:
        value = getattr(source, name, None)
        if value is None and isinstance(source, dict):
            value = source.get(name)
        if value is not None:
            return value
    return None


def _safe_token(value: Any) -> Optional[str]:
    """*value* as a short identifier, or ``None`` when it is unsafe to show.

    Survives a short run of ASCII letters, digits, ``_``, ``-`` and ``.`` and
    nothing else, so a provider sentence, a json fragment, a url, a header or a
    credential-shaped string cannot be smuggled into a log line or an operator
    report. A candidate title never comes near this: model and provider text is
    only ever *classified* here, never quoted.
    """
    text = _clean_str(value)
    if not _SAFE_TOKEN_PATTERN.match(text):
        return None
    lowered = text.lower()
    if any(fragment in lowered for fragment in _SECRET_FRAGMENTS):
        return None
    return text


def _safe_status(value: Any) -> Optional[int]:
    """*value* as a plausible HTTP status code, or ``None``."""
    number = _to_float(value)
    if number is None or not 100.0 <= number <= 599.0:
        return None
    return int(number) if number == int(number) else None


def _is_video_id(value: Any) -> Optional[str]:
    text = _clean_str(value)
    return text if _YOUTUBE_ID_PATTERN.match(text) else None


def _video_id_of(result: Any) -> Optional[str]:
    """Best-effort video id for a spotdl ``Result`` (or any url-bearing object)."""
    direct = _is_video_id(_attr(result, "result_id", "video_id", "videoId"))
    if direct:
        return direct
    url = _attr(result, "url", "webpage_url")
    for pattern in _VIDEO_ID_URL_PATTERNS:
        match = pattern.search(_clean_str(url))
        if match:
            return match.group(1)
    return None


# ---------------------------------------------------------------------------
# Configuration.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class JevConfig:
    """Immutable selector configuration. The key is never rendered."""

    api_key: Optional[str] = field(default=None, repr=False, compare=False)
    enabled: bool = False
    model: str = DEFAULT_MODEL
    endpoint: str = DEFAULT_ENDPOINT
    timeout: float = DEFAULT_TIMEOUT
    max_candidates: int = DEFAULT_MAX_CANDIDATES
    min_confidence: float = DEFAULT_MIN_CONFIDENCE
    duration_tolerance: float = DEFAULT_DURATION_TOLERANCE

    @classmethod
    def from_env(
        cls,
        env: Optional[Dict[str, str]] = None,
        timeout_cap: Optional[float] = None,
    ) -> "JevConfig":
        """Build a config from *env* (defaults to ``os.environ``).

        JEV is intentionally disabled: ``enabled`` is always False,
        regardless of ``OPENROUTER_API_KEY``/``GHOSTIFY_JEV_ENABLED``. Other
        knobs are still parsed so shortlist/display behaviour stays
        deterministic. Never raises.
        """
        source: Any = os.environ if env is None else env
        try:
            api_key = _clean_str(source.get(ENV_API_KEY)) or None
        except Exception:  # noqa: BLE001 - a hostile env must not break callers
            api_key = None

        def _get(name: str) -> Any:
            try:
                return source.get(name)
            except Exception:  # noqa: BLE001
                return None

        # Intentionally disabled: ignore GHOSTIFY_JEV_ENABLED entirely.
        # (Previously: enabled = _parse_bool(_get(ENV_ENABLED), default=bool(api_key)))
        model = _clean_str(_get(ENV_MODEL)) or DEFAULT_MODEL
        endpoint = _clean_str(_get(ENV_ENDPOINT)) or DEFAULT_ENDPOINT
        timeout = _clamped_float(
            _get(ENV_TIMEOUT), DEFAULT_TIMEOUT, MIN_TIMEOUT, MAX_TIMEOUT
        )
        cap = _to_float(timeout_cap)
        if cap is not None and cap > 0:
            timeout = min(timeout, max(MIN_TIMEOUT, cap))
        max_candidates = int(
            _clamped_float(
                _get(ENV_MAX_CANDIDATES),
                float(DEFAULT_MAX_CANDIDATES),
                float(MIN_CANDIDATE_LIMIT),
                float(MAX_CANDIDATE_LIMIT),
            )
        )
        min_confidence = _clamped_float(
            _get(ENV_MIN_CONFIDENCE), DEFAULT_MIN_CONFIDENCE, 0.0, 1.0
        )
        # Intentionally disabled: never enable via environment. The key is
        # still parsed (secret hygiene/tests) but the selector stays off.
        enabled = False
        return cls(
            api_key=api_key,
            enabled=enabled,
            model=model,
            endpoint=endpoint,
            timeout=timeout,
            max_candidates=max_candidates,
            min_confidence=min_confidence,
            duration_tolerance=DEFAULT_DURATION_TOLERANCE,
        )


# ---------------------------------------------------------------------------
# Candidates and the deterministic shortlist.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Candidate:
    """One shortlisted video, reduced to the fields the decision needs."""

    video_id: str
    title: str = ""
    artist: str = ""
    album: str = ""
    duration: Optional[float] = None
    source: str = ""
    verified: bool = False
    rank: int = 0
    score: float = 0.0
    raw: Any = field(default=None, repr=False, compare=False)

    def describe(self) -> str:
        """Compact criteria text: full title, full channel, length, flag.

        Nothing is truncated: the whole title and the whole channel name reach
        the model. The description stays compact by carrying only the fields
        JEV can act on - a url, an album or a provider name would be noise, and
        a url must never leave the module.
        """
        title = _one_line(self.title) if self.title else "(untitled)"
        text = '"%s"' % title
        if self.artist:
            text += " by %s" % _one_line(self.artist)
        if self.duration:
            text += " (%ds)" % int(self.duration)
        if self.verified:
            text += " [official listing]"
        return _one_line(text)


@dataclass(frozen=True)
class Shortlist:
    """Candidates JEV may choose from, plus the deterministic fallback id."""

    candidates: Tuple[Candidate, ...] = ()
    fallback_id: Optional[str] = None

    def __len__(self) -> int:
        return len(self.candidates)

    def __bool__(self) -> bool:
        return bool(self.candidates)

    def __iter__(self):
        return iter(self.candidates)

    @property
    def ids(self) -> FrozenSet[str]:
        return frozenset(candidate.video_id for candidate in self.candidates)

    def is_ambiguous(
        self, minimum: int = MIN_CANDIDATES_FOR_CHOICE
    ) -> bool:
        """True when there is a real choice to make."""
        return len(self.ids) >= max(2, minimum)


def _candidate_from(result: Any, video_id: str, rank: int) -> Candidate:
    artists = _attr(result, "artists") or ()
    if isinstance(artists, str):
        artist = artists
    else:
        artist = _clean_str(_attr(result, "author")) or _clean_str(
            artists[0] if artists else ""
        )
    return Candidate(
        video_id=video_id,
        title=_clean_str(_attr(result, "name", "title")),
        artist=artist,
        album=_clean_str(_attr(result, "album", "album_name")),
        duration=_to_float(_attr(result, "duration")),
        source=_clean_str(_attr(result, "source")),
        verified=bool(_attr(result, "verified")),
        rank=rank,
        raw=result,
    )


def _duration_matches(
    candidate: Candidate, song: Any, tolerance: float
) -> bool:
    """Duration sanity: keep anything close to the song, or of unknown length."""
    expected = _to_float(_attr(song, "duration"))
    if not expected or expected <= 0:
        return True
    actual = candidate.duration
    if not actual or actual <= 0:
        return True
    return abs(expected - actual) <= tolerance


def _score_with_spotdl(
    candidates: Sequence[Candidate], song: Any
) -> Optional[Dict[str, float]]:
    """Reuse spotdl's own match score when it is importable.

    This keeps the shortlist ordered by the same scoring the download path
    already trusts, and is strictly optional: any failure returns ``None`` and
    the caller falls back to provider order.
    """
    if song is None or any(candidate.raw is None for candidate in candidates):
        return None
    try:
        from spotdl.utils.matching import order_results
    except Exception:  # noqa: BLE001 - spotdl absent or partially vendored
        return None
    try:
        scored = order_results(
            [candidate.raw for candidate in candidates], song  # type: ignore[misc]
        )
    except Exception:  # noqa: BLE001 - never let scoring break resolution
        return None
    if not isinstance(scored, dict):
        return None
    out: Dict[str, float] = {}
    for result, score in scored.items():
        video_id = _video_id_of(result)
        if video_id is None:
            continue
        number = _to_float(score)
        out[video_id] = number if number is not None else 0.0
    return out or None


def build_shortlist(
    results: Any, song: Any, config: Optional[JevConfig] = None
) -> Shortlist:
    """Build the deterministic candidate set JEV is allowed to choose from.

    Deterministic by construction: provider order fixes the tie-break, spotdl's
    own match score (when available) fixes the ranking, and a duration sanity
    filter drops the obvious outliers. The returned ``fallback_id`` is always
    the *first* usable result in raw provider order - the id the pre-JEV code
    would have returned - so callers can keep today's behaviour verbatim.
    """
    config = config or JevConfig()
    candidates: List[Candidate] = []
    seen: set = set()
    for rank, result in enumerate(results or ()):
        video_id = _video_id_of(result)
        if video_id is None or video_id in seen:
            continue
        seen.add(video_id)
        candidates.append(_candidate_from(result, video_id, rank))

    if not candidates:
        return Shortlist((), None)

    fallback_id = candidates[0].video_id

    scores = _score_with_spotdl(candidates, song)
    if scores is None:
        ordered = list(candidates)
    else:
        # -1.0 keeps candidates spotdl could not score below every scored one;
        # `rank` is the stable tie-break, so the order is fully deterministic.
        ordered = sorted(
            candidates,
            key=lambda candidate: (
                -scores.get(candidate.video_id, -1.0),
                candidate.rank,
            ),
        )

    pool = [
        candidate
        for candidate in ordered
        if _duration_matches(candidate, song, config.duration_tolerance)
    ]
    if not pool:
        pool = ordered
    return Shortlist(tuple(pool[: config.max_candidates]), fallback_id)


# ---------------------------------------------------------------------------
# Request / response handling.
# ---------------------------------------------------------------------------


def _requested_song(song: Any) -> Dict[str, Any]:
    """Minimal song context: full title, full artist names, length.

    Same rule as the candidate descriptions - untruncated. The state stays
    small because it holds three fields, not because any of them is cut short.
    """
    artists = _attr(song, "artists") or ()
    if isinstance(artists, str):
        artists = [artists]
    names = [_one_line(artist) for artist in artists]
    names = [name for name in names if name]
    return {
        "title": _one_line(_attr(song, "name", "title")),
        "artists": names,
        "length_seconds": _to_float(_attr(song, "duration")),
    }


def build_payload(
    song: Any, candidates: Sequence[Candidate], model: str = DEFAULT_MODEL
) -> Dict[str, Any]:
    """Assemble the Decisions request; ``criteria`` keys are the video ids.

    The shortlist is carried once, as ``questions.<id>.criteria``; ``state``
    holds only the minimal song context, so nothing is duplicated. Criteria
    keys are re-validated here, so only ids that could be accepted by
    :func:`parse_choice` can ever reach the model.
    """
    criteria: Dict[str, str] = {}
    for candidate in candidates:
        video_id = _is_video_id(candidate.video_id)
        if video_id is None or video_id in criteria:
            continue
        criteria[video_id] = candidate.describe()
    return {
        "model": model,
        "state": {
            "requested_song": _requested_song(song),
        },
        "questions": {
            QUESTION_ID: {
                "type": "choice",
                "instructions": _INSTRUCTIONS,
                "criteria": criteria,
            }
        },
    }


def _headers(config: JevConfig) -> Dict[str, str]:
    return {
        "Authorization": "Bearer %s" % (config.api_key or ""),
        "Content-Type": "application/json",
    }


def _provider_error_code(response: Any) -> Optional[str]:
    """The provider's own error code for a failed response, as a safe token.

    Reads the *shape* of the error body only - a ``code``/``type`` field, never
    a message - and only after :func:`_safe_token` accepted it, so neither the
    API key nor a provider sentence can reach a log record or a report. A body
    that is missing, unparsable or unsafe simply yields ``None`` and the status
    alone is reported.
    """
    try:
        body = response.json()
    except Exception:  # noqa: BLE001 - a non-json error body carries no code
        return None
    if not isinstance(body, dict):
        return None
    error = body.get("error")
    if isinstance(error, dict):
        values: Tuple[Any, ...] = (error.get("code"), error.get("type"))
    elif isinstance(error, str):
        values = (error,)
    else:
        values = ()
    for value in values:
        token = _safe_token(value)
        if token:
            return token
    return None


def _post_json(
    url: str, payload: Dict[str, Any], headers: Dict[str, str], timeout: float
) -> Any:
    """Refuse to POST: JEV is intentionally disabled, so no OpenRouter request
    may be made — including from demo/verbose tooling that wraps this sender.

    Defense-in-depth alongside the ``decide``/``ghostify_dl`` gates: raises
    without importing ``requests`` and without touching the network.
    """
    raise JevTransportError("JEV disabled by runtime kill-switch")


def _check_answer(
    response: Any,
    allowed_ids: FrozenSet[str],
    min_confidence: float = DEFAULT_MIN_CONFIDENCE,
) -> Tuple[Optional[str], Optional[str], Tuple[str, ...]]:
    """Validate a Decisions response: ``(video_id, rejection_label, scalars)``.

    The single place that decides whether an answer is usable. On success the
    validated id is returned; otherwise ``None`` plus one of the
    ``REJECTION_*`` labels and a tuple of *self-authored* detail strings built
    from numbers and validated tokens only (a shortlist count, a confidence, a
    probability, an answer type). Model text is classified, never quoted, so the
    caller can explain the refusal without leaking a candidate, a header or a
    response body.
    """
    if not isinstance(response, dict) or not allowed_ids:
        return None, REJECTION_NO_ANSWER, ()
    answers = response.get("answers")
    if not isinstance(answers, dict):
        return None, REJECTION_NO_ANSWER, ()
    answer = answers.get(QUESTION_ID)
    if not isinstance(answer, dict):
        return None, REJECTION_NO_ANSWER, ("no %s answer" % QUESTION_ID,)

    answer_type = _clean_str(answer.get("type"))
    if answer_type and answer_type != "choice":
        return (
            None,
            REJECTION_ANSWER_TYPE,
            ("type=%s" % (_safe_token(answer_type) or "other"),),
        )

    choice = _clean_str(answer.get("choice"))
    if choice not in allowed_ids:
        return None, REJECTION_UNKNOWN_ID, ("shortlist=%d" % len(allowed_ids),)

    confidence = _to_float(answer.get("confidence"))
    if confidence is not None and confidence < min_confidence:
        return (
            None,
            REJECTION_LOW_CONFIDENCE,
            ("confidence=%.2f < %.2f" % (confidence, min_confidence),),
        )

    probabilities = answer.get("probabilities")
    if isinstance(probabilities, dict) and probabilities:
        numeric = {
            key: value
            for key, value in probabilities.items()
            if _to_float(value) is not None
        }
        if numeric:
            best = max(
                numeric,
                key=lambda key: (_to_float(numeric[key]) or 0.0, key == choice),
            )
            if best != choice:
                return (
                    None,
                    REJECTION_PROBABILITIES,
                    (
                        "p=%.2f, best p=%.2f"
                        % (
                            _to_float(numeric.get(choice)) or 0.0,
                            _to_float(numeric[best]) or 0.0,
                        ),
                    ),
                )
            probability = _to_float(numeric.get(choice)) or 0.0
            if probability < min_confidence:
                return (
                    None,
                    REJECTION_LOW_CONFIDENCE,
                    ("probability=%.2f < %.2f" % (probability, min_confidence),),
                )
    return choice, None, ()


def parse_choice(
    response: Any,
    allowed_ids: FrozenSet[str],
    min_confidence: float = DEFAULT_MIN_CONFIDENCE,
) -> Optional[str]:
    """Validate a Decisions response and return a shortlisted video id.

    Rejects everything that is not a well-formed ``choice`` answer naming a
    member of *allowed_ids* with sufficient confidence. A model-invented id can
    therefore never reach the download path. :func:`decide` uses the same
    validation through :func:`_check_answer` to also learn *why* an answer was
    refused.
    """
    return _check_answer(response, allowed_ids, min_confidence)[0]


# ---------------------------------------------------------------------------
# Public entry point.
# ---------------------------------------------------------------------------


Transport = Callable[[str, Dict[str, Any], Dict[str, str], float], Any]


@dataclass(frozen=True)
class Decision:
    """The outcome of one advisory decision, with a human-readable reason.

    ``video_id`` is the only field the download path needs; the remaining
    fields exist so an operator-facing report (see ``tools/jev_selection_demo.py``)
    can explain *why* the deterministic candidate was kept, without the
    selector ever having to re-validate anything a second time.
    """

    video_id: Optional[str] = None
    outcome: str = OUTCOME_NO_CANDIDATES
    reason: str = ""
    confidence: Optional[float] = None
    candidates_considered: int = 0

    @property
    def accepted(self) -> bool:
        """True when the model named a validated candidate."""
        return self.video_id is not None


def _rejection_reason(code: Optional[str], details: Sequence[str] = ()) -> str:
    """Explain a refused answer from this module's labels and scalars.

    ``"answer rejected: the returned id is not in the shortlist (unknown_id)
    [shortlist=3]"``. Every part comes from :data:`_REJECTION_TEXT`, a
    ``REJECTION_*`` label or a detail string the selector built from numbers and
    validated tokens - never from the response body.
    """
    text = _REJECTION_TEXT.get(code or "", _REJECTION_TEXT[REJECTION_NO_ANSWER])
    if code:
        text = "%s (%s)" % (text, code)
    scalars = "; ".join(part for part in details if part)
    if scalars:
        text = "%s [%s]" % (text, scalars)
    return text


def _failure_detail(exc: BaseException) -> str:
    """A safe one-line description of a request failure.

    Never ``str(exc)``: a ``requests``/``urllib3`` message can echo the
    ``Authorization`` header, the key or a response body. Only the exception
    class name and - for this module's own transport error - the integer status
    plus the short provider code are used, and all of them are scalars this
    module produced itself.
    """
    if isinstance(exc, JevTransportError):
        detail = exc.detail
        if detail:
            return detail
    return type(exc).__name__


def _answer_confidence(response: Any) -> Optional[float]:
    """Read the confidence the model reported, if any.

    Reporting only: :func:`parse_choice` remains the single place that decides
    whether a confidence is good enough.
    """
    if not isinstance(response, dict):
        return None
    answers = response.get("answers")
    if not isinstance(answers, dict):
        return None
    answer = answers.get(QUESTION_ID)
    if not isinstance(answer, dict):
        return None
    return _to_float(answer.get("confidence"))


def decide(
    song: Any,
    shortlist: Shortlist,
    config: Optional[JevConfig] = None,
    transport: Optional[Transport] = None,
) -> Decision:
    """Ask JEV which shortlisted video is the right one and explain the result.

    JEV is intentionally disabled: always returns ``inactive`` without
    calling ``transport`` and without any network I/O, so callers keep the
    deterministic candidate. Never raises.
    """
    config = config or JevConfig()
    considered = len(shortlist.candidates)
    if not _is_jev_runtime_enabled():
        logger.debug(
            "JEV disabled by runtime kill-switch; keeping deterministic candidate"
        )
        return Decision(
            outcome=OUTCOME_INACTIVE,
            reason="JEV disabled by runtime kill-switch "
            "(GHOSTIFY_JEV_ENABLED forced off); deterministic candidate kept",
            candidates_considered=considered,
        )
    if not shortlist.candidates:
        return Decision(
            outcome=OUTCOME_NO_CANDIDATES, reason="no shortlisted candidates"
        )
    if not config.enabled or not config.api_key:
        logger.debug(
            "JEV inactive; keeping deterministic candidate (%d shortlisted)",
            considered,
        )
        return Decision(
            outcome=OUTCOME_INACTIVE,
            reason="JEV disabled (no %s or %s=0)" % (ENV_API_KEY, ENV_ENABLED),
            candidates_considered=considered,
        )
    if not shortlist.is_ambiguous():
        logger.debug(
            "JEV skipped; single shortlisted candidate for an unambiguous song"
        )
        return Decision(
            outcome=OUTCOME_UNAMBIGUOUS,
            reason="only one candidate shortlisted; nothing to decide",
            candidates_considered=considered,
        )
    if not _JEV_SEMAPHORE.acquire(blocking=False):
        logger.debug("JEV busy; keeping deterministic candidate")
        return Decision(
            outcome=OUTCOME_BUSY,
            reason="concurrency gate busy; deterministic candidate kept",
            candidates_considered=considered,
        )

    try:
        payload = build_payload(song, shortlist.candidates, config.model)
        sender = transport or _post_json
        try:
            response = sender(
                config.endpoint, payload, _headers(config), config.timeout
            )
        except Exception as exc:  # noqa: BLE001 - advisory must never propagate
            detail = _failure_detail(exc)
            logger.debug(
                "JEV request failed (%s); keeping deterministic candidate",
                detail,
            )
            return Decision(
                outcome=OUTCOME_REQUEST_FAILED,
                reason="request failed (%s)" % detail,
                candidates_considered=considered,
            )

        chosen, rejection, details = _check_answer(
            response, shortlist.ids, config.min_confidence
        )
        if chosen is None:
            logger.debug(
                "JEV answer rejected (%s, %d shortlisted); keeping deterministic "
                "candidate",
                rejection or OUTCOME_REJECTED,
                considered,
            )
            return Decision(
                outcome=OUTCOME_REJECTED,
                reason=_rejection_reason(rejection, details),
                candidates_considered=considered,
            )

        logger.debug(
            "JEV chose one of %d shortlisted videos (match=%s)",
            considered,
            "top" if chosen == shortlist.fallback_id else "alternate",
        )
        return Decision(
            video_id=chosen,
            outcome=OUTCOME_CHOSE,
            reason="JEV picked %s of %d shortlisted videos"
            % ("the top result" if chosen == shortlist.fallback_id else "an alternate", considered),
            confidence=_answer_confidence(response),
            candidates_considered=considered,
        )
    except Exception as exc:  # noqa: BLE001 - last-resort safety net
        logger.debug(
            "JEV selection skipped (%s); keeping deterministic candidate",
            type(exc).__name__,
        )
        return Decision(
            outcome=OUTCOME_REQUEST_FAILED,
            reason="selection skipped (%s)" % type(exc).__name__,
            candidates_considered=considered,
        )
    finally:
        _JEV_SEMAPHORE.release()


def select_video_id(
    song: Any,
    shortlist: Shortlist,
    config: Optional[JevConfig] = None,
    transport: Optional[Transport] = None,
) -> Optional[str]:
    """Ask JEV which shortlisted video is the right one, or return ``None``.

    JEV is intentionally disabled: always returns ``None`` without calling
    ``transport``. ``None`` means "no advice" and the caller must keep its
    deterministic candidate. This function never raises.
    """
    return decide(song, shortlist, config, transport).video_id


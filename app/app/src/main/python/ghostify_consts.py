"""Ghostify central constants — single source of truth for scoring/tuning.

Location: ``app/app/src/main/python/ghostify_consts.py`` (Cha­quopy
``src/main/python`` — packaged automatically with the app, no Gradle
change needed). Import as::

    from ghostify_consts import YTM_SOURCE_BONUS, VIEWS_DQ_THRESHOLD, ...

Vendored ``spotdl`` (``app/app/python-spotdl``) imports this module via
a ``try/except`` + ``sys.path`` fallback (see ``spotdl/utils/matching.py``)
because vendored packages do not have the app dir on ``sys.path`` by
default. Tools (``tools/score_tuner.py``, ``tools/jev_selection_demo.py``)
already prepend ``APP_PYTHON_DIR`` to ``sys.path``, so a plain import works.

Original values are preserved byte-for-byte; every constant documents the
previous literal location. Scores in stub tests (124/113/112 etc.) are
unchanged.
"""

from __future__ import annotations

# ---------------------------------------------------------------------------
# Set-overlap scorer (matching.calc_set_score)
# Original: matching.py  calc_set_score  ``(0.6 * recall + 0.4 * jaccard) * 100``
# ---------------------------------------------------------------------------
SET_RECALL_WEIGHT = 0.6
SET_JACCARD_WEIGHT = 0.4
SET_EXACT_BONUS = 5.0
SET_SCORE_CAP = 100.0
SET_SCORE_SCALE = 100.0

# Spotify query set: words(title) ∪ words(artists[:3]).
# Original: matching.build_spotify_set  ``(song.artists or [])[:3]``
SPOTIFY_ARTISTS_SLICE = 3

# ---------------------------------------------------------------------------
# Stoplist + forbidden words
# Original: matching.SET_STOPWORDS / FORBIDDEN_WORDS / FORBIDDEN_WORD_PENALTY
# ---------------------------------------------------------------------------
SET_STOPWORDS = frozenset(
    {
        "ft",
        "feat",
        "featuring",
        "with",
        "x",
        "vs",
        "vevo",
        "footnotes",
        "official",
        "video",
        "audio",
        "lyrics",
        "lyric",
        "visualizer",
        "music",
        "mv",
        "hd",
        "hq",
        "4k",
    }
)

FORBIDDEN_WORDS = [
    "bassboosted",
    "remix",
    "remastered",
    "remaster",
    "reverb",
    "bassboost",
    "live",
    "acoustic",
    "8daudio",
    "concert",
    "acapella",
    "slowed",
    "instrumental",
]

FORBIDDEN_WORD_PENALTY = 5

# Step 4 (unrelated/clickbait words): disabled — penalty forced to 0.
# Original: matching.ENABLE_UNRELATED_WORDS_PENALTY = False
ENABLE_UNRELATED_WORDS_PENALTY = False
# Kept for a future re-enable (matching._penalty_unrelated_words):
UNRELATED_CONTENT_TYPE_PENALTY = 20
UNRELATED_OTHER_WORD_PENALTY = 3
UNRELATED_PENALTY_CAP = 30

# ---------------------------------------------------------------------------
# Source / channel / explicit bonuses & penalties (order_results)
# ---------------------------------------------------------------------------
# Original: matching.YTM_SOURCE_BONUS = 5
YTM_SOURCE_BONUS = 5
# Original: matching.VEVO_BONUS = 3
VEVO_BONUS = 3
# Original: order_results  ``average_match += 10`` / ``average_match -= 15``
CHANNEL_MATCH_BONUS = 10
CHANNEL_MISMATCH_PENALTY = 15
# Original: order_results  ``average_match -= 5`` on explicit mismatch
EXPLICIT_MISMATCH_PENALTY = 5

# ---------------------------------------------------------------------------
# Album blend guard (verified + low-album case)
# Original: ``0 < album_match <= 80 and set_score < 85``
# ---------------------------------------------------------------------------
ALBUM_BLEND_LOW_EXCLUSIVE = 0
ALBUM_BLEND_HIGH_INCLUSIVE = 80
ALBUM_STRONG_SET_THRESHOLD = 85

# ---------------------------------------------------------------------------
# Duration bonus (additive, open-ended)
# Original: matching.calc_duration_bonus  ``10.0 - abs(video - song)``
# ---------------------------------------------------------------------------
DURATION_BASE_BONUS = 10.0

# Legacy exp-decay scorer (kept for compat/display only):
# Original: matching.calc_time_match  ``exp(-0.05 * diff) * 100``
TIME_DECAY_RATE = 0.05
TIME_SCORE_SCALE = 100.0

# ---------------------------------------------------------------------------
# Name / artists gates (order_results filters)
# Original: ``if name_match <= 55`` / ``if artists_match < 70``
# ---------------------------------------------------------------------------
NAME_MATCH_THRESHOLD = 55
ARTISTS_MATCH_THRESHOLD = 55

# Fixup thresholds (compat helpers, kept for display/tests):
# Original: artists_match_fixup1  ``> 50`` / ``<= 70`` (x2)
ARTIST_FIXUP_GOOD_ENOUGH = 50
ARTIST_FIXUP_LOW_THRESHOLD = 70
# Original: artists_match_fixup2  ``> 70``, ``+= 5``, ``<= 70``
ARTIST_FIXUP2_HIGH = 70
ARTIST_FIXUP2_BONUS = 5
# Original: artists_match_fixup3  ``> 70``, ``>= 80``, ``min(score, 100)``
ARTIST_FIXUP3_HIGH = 70
ARTIST_FIXUP3_BLEND_THRESHOLD = 80
# Original: calc_main_artist_match  ``< 50``
MAIN_ARTIST_LOW_THRESHOLD = 50

# ---------------------------------------------------------------------------
# Views policy (absolute thresholds, additive, open-ended)
# Original: VIEWS_DQ 1000 / MID 10k-20 / HIGH 50k-10
# ---------------------------------------------------------------------------
VIEWS_DQ_THRESHOLD = 1000
VIEWS_MID_THRESHOLD = 10_000
VIEWS_HIGH_THRESHOLD = 50_000
VIEWS_MID_PENALTY = 20
VIEWS_HIGH_PENALTY = 10

# Relative views contention (get_best_result among top-N):
# Original: base.get_best_result  ``get_best_matches(results, 8)`` + ``* 15``
VIEWS_TOP_N = 8
VIEWS_WEIGHT_CAP = 15

# ISRC fast path (base.AudioProvider.search):
# Original: ``if best_isrc[1] > 80.0``
ISRC_SCORE_THRESHOLD = 80.0

# ---------------------------------------------------------------------------
# Search limits / provider opts
# ---------------------------------------------------------------------------
# Original: ytmusic.GET_RESULTS_OPTS  songs 50 + videos 50
YTM_SONGS_LIMIT = 50
YTM_VIDEOS_LIMIT = 50
# Original: youtube ytsearch10  (limit 10)
YT_SEARCH_LIMIT = 10
# Original: ghostify_dl.search_yt_candidates / _resolve_yt_id  limit=10
GHOSTIFY_SEARCH_LIMIT = 10
# Original: ghostify_dl._fallback_search_yt_id  limit=5
FALLBACK_SEARCH_LIMIT = 5
# Original: score_tuner DEFAULT_LIMIT 100 (tool display default)
SCORE_TUNER_DEFAULT_LIMIT = 100
# Original: jev_selection_demo DEFAULT_LIMIT 10
JEV_DEMO_DEFAULT_LIMIT = 10

# ---------------------------------------------------------------------------
# Timeouts / retries / concurrency
# ---------------------------------------------------------------------------
# Original: ghostify_dl.DEFAULT_TIMEOUT = 180.0
DEFAULT_TIMEOUT = 180.0
# Original: ghostify_dl.DEFAULT_PER_TRACK_YT_TIMEOUT = 8.0
PER_TRACK_YT_TIMEOUT = 8.0
# Original: ghostify_dl.search_yt_candidates / jev demo / score tuner  8.0
DEFAULT_PER_TRACK_TIMEOUT = 8.0
# Original: ghostify_dl.ffmpeg_probe  timeout=30
FFMPEG_PROBE_TIMEOUT = 30
# Original: ghostify_dl.fetch_lyrics  TimeoutSession (8, 10)
LYRICS_CONNECT_TIMEOUT = 8
LYRICS_READ_TIMEOUT = 10
# Original: ghostify_dl._resolve_yt_id  yt_retries = 3, sleep 1.5*(attempt+1)
YT_RESOLVE_RETRIES = 3
YT_RETRY_BACKOFF_BASE = 1.5
# Original: youtube.YouTube.get_results  max_retries = 3, sleep 2**(attempt+1)
YT_PROVIDER_MAX_RETRIES = 3
YT_PROVIDER_BACKOFF_BASE = 2
# Original: ytmusic.SEARCH_ATTEMPTS = 3
YTM_SEARCH_ATTEMPTS = 3
# Original: base.AudioProvider yt-dlp retries = 5
YTDL_RETRIES = 5
# Original: ghostify_dl._score_candidates per-candidate views budget
#   min(3.0, max(0.5, per_track / n))
VIEWS_LOOKUP_MAX_BUDGET = 3.0
VIEWS_LOOKUP_MIN_BUDGET = 0.5
# Original: ghostify_dl.YT_CONCURRENCY = 10
YT_CONCURRENCY = 10
# Original: ghostify_dl.DEFAULT_TEMP_SWEEP_AGE = 3600.0
TEMP_SWEEP_AGE = 3600.0
# Original: ghostify_dl.DEFAULT_DURATION_TOLERANCE = 90.0
# (post-download _validate hard gate; jev shortlist sanity uses the same)
DURATION_TOLERANCE = 90.0

# ---------------------------------------------------------------------------
# JEV selector defaults (mirror of jev_selector.py originals)
# Original: DEFAULT_MODEL typesafe/jev-1.13, ENDPOINT ..., TIMEOUT 4.0,
#   MAX_CANDIDATES 6, MIN_CONFIDENCE 0.6, DURATION_TOL 90.0,
#   CONCURRENCY 4, MIN_FOR_CHOICE 2, TIMEOUT 0.1..30, CAND 2..25
# ---------------------------------------------------------------------------
JEV_DEFAULT_MODEL = "typesafe/jev-1.13"
JEV_DEFAULT_ENDPOINT = "https://openrouter.ai/api/alpha/decisions"
JEV_DEFAULT_TIMEOUT = 4.0
JEV_DEFAULT_MAX_CANDIDATES = 6
JEV_DEFAULT_MIN_CONFIDENCE = 0.6
JEV_DEFAULT_DURATION_TOLERANCE = 90.0
JEV_CONCURRENCY = 4
JEV_MIN_CANDIDATES_FOR_CHOICE = 2
JEV_MIN_TIMEOUT = 0.1
JEV_MAX_TIMEOUT = 30.0
JEV_MIN_CANDIDATE_LIMIT = 2
JEV_MAX_CANDIDATE_LIMIT = 25
JEV_QUESTION_ID = "recording"

__all__ = [
    "SET_RECALL_WEIGHT",
    "SET_JACCARD_WEIGHT",
    "SET_EXACT_BONUS",
    "SET_SCORE_CAP",
    "SET_SCORE_SCALE",
    "SPOTIFY_ARTISTS_SLICE",
    "SET_STOPWORDS",
    "FORBIDDEN_WORDS",
    "FORBIDDEN_WORD_PENALTY",
    "ENABLE_UNRELATED_WORDS_PENALTY",
    "UNRELATED_CONTENT_TYPE_PENALTY",
    "UNRELATED_OTHER_WORD_PENALTY",
    "UNRELATED_PENALTY_CAP",
    "YTM_SOURCE_BONUS",
    "VEVO_BONUS",
    "CHANNEL_MATCH_BONUS",
    "CHANNEL_MISMATCH_PENALTY",
    "EXPLICIT_MISMATCH_PENALTY",
    "ALBUM_BLEND_LOW_EXCLUSIVE",
    "ALBUM_BLEND_HIGH_INCLUSIVE",
    "ALBUM_STRONG_SET_THRESHOLD",
    "DURATION_BASE_BONUS",
    "TIME_DECAY_RATE",
    "TIME_SCORE_SCALE",
    "NAME_MATCH_THRESHOLD",
    "ARTISTS_MATCH_THRESHOLD",
    "ARTIST_FIXUP_GOOD_ENOUGH",
    "ARTIST_FIXUP_LOW_THRESHOLD",
    "ARTIST_FIXUP2_HIGH",
    "ARTIST_FIXUP2_BONUS",
    "ARTIST_FIXUP3_HIGH",
    "ARTIST_FIXUP3_BLEND_THRESHOLD",
    "MAIN_ARTIST_LOW_THRESHOLD",
    "VIEWS_DQ_THRESHOLD",
    "VIEWS_MID_THRESHOLD",
    "VIEWS_HIGH_THRESHOLD",
    "VIEWS_MID_PENALTY",
    "VIEWS_HIGH_PENALTY",
    "VIEWS_TOP_N",
    "VIEWS_WEIGHT_CAP",
    "ISRC_SCORE_THRESHOLD",
    "YTM_SONGS_LIMIT",
    "YTM_VIDEOS_LIMIT",
    "YT_SEARCH_LIMIT",
    "GHOSTIFY_SEARCH_LIMIT",
    "FALLBACK_SEARCH_LIMIT",
    "SCORE_TUNER_DEFAULT_LIMIT",
    "JEV_DEMO_DEFAULT_LIMIT",
    "DEFAULT_TIMEOUT",
    "PER_TRACK_YT_TIMEOUT",
    "DEFAULT_PER_TRACK_TIMEOUT",
    "FFMPEG_PROBE_TIMEOUT",
    "LYRICS_CONNECT_TIMEOUT",
    "LYRICS_READ_TIMEOUT",
    "YT_RESOLVE_RETRIES",
    "YT_RETRY_BACKOFF_BASE",
    "YT_PROVIDER_MAX_RETRIES",
    "YT_PROVIDER_BACKOFF_BASE",
    "YTM_SEARCH_ATTEMPTS",
    "YTDL_RETRIES",
    "VIEWS_LOOKUP_MAX_BUDGET",
    "VIEWS_LOOKUP_MIN_BUDGET",
    "YT_CONCURRENCY",
    "TEMP_SWEEP_AGE",
    "DURATION_TOLERANCE",
    "JEV_DEFAULT_MODEL",
    "JEV_DEFAULT_ENDPOINT",
    "JEV_DEFAULT_TIMEOUT",
    "JEV_DEFAULT_MAX_CANDIDATES",
    "JEV_DEFAULT_MIN_CONFIDENCE",
    "JEV_DEFAULT_DURATION_TOLERANCE",
    "JEV_CONCURRENCY",
    "JEV_MIN_CANDIDATES_FOR_CHOICE",
    "JEV_MIN_TIMEOUT",
    "JEV_MAX_TIMEOUT",
    "JEV_MIN_CANDIDATE_LIMIT",
    "JEV_MAX_CANDIDATE_LIMIT",
    "JEV_QUESTION_ID",
]

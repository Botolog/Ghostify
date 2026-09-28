"""
Module for all things matching related
"""

import logging
import re
import sys
from itertools import product, zip_longest
from math import exp
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

from spotdl.types.result import Result
from spotdl.types.song import Song
from spotdl.utils.formatter import (
    create_search_query,
    create_song_title,
    ratio,
    slugify,
)
from spotdl.utils.logging import MATCH

# Central constants (single source of truth). Vendored spotdl does not have
# the app python dir on sys.path by default, so fall back to adding the
# sibling src/main/python directory before importing.
try:
    from ghostify_consts import (  # type: ignore[import-not-found]
        ALBUM_BLEND_HIGH_INCLUSIVE,
        ALBUM_BLEND_LOW_EXCLUSIVE,
        ALBUM_STRONG_SET_THRESHOLD,
        ARTIST_FIXUP2_BONUS,
        ARTIST_FIXUP2_HIGH,
        ARTIST_FIXUP3_BLEND_THRESHOLD,
        ARTIST_FIXUP3_HIGH,
        ARTIST_FIXUP_GOOD_ENOUGH,
        ARTIST_FIXUP_LOW_THRESHOLD,
        CHANNEL_MATCH_BONUS,
        CHANNEL_MISMATCH_PENALTY,
        DURATION_BASE_BONUS,
        ENABLE_UNRELATED_WORDS_PENALTY,
        EXPLICIT_MISMATCH_PENALTY,
        FORBIDDEN_WORD_PENALTY,
        FORBIDDEN_WORDS,
        MAIN_ARTIST_LOW_THRESHOLD,
        NAME_MATCH_THRESHOLD,
        ARTISTS_MATCH_THRESHOLD,
        SET_EXACT_BONUS,
        SET_JACCARD_WEIGHT,
        SET_RECALL_WEIGHT,
        SET_SCORE_CAP,
        SET_SCORE_SCALE,
        SET_STOPWORDS,
        SPOTIFY_ARTISTS_SLICE,
        TIME_DECAY_RATE,
        TIME_SCORE_SCALE,
        UNRELATED_CONTENT_TYPE_PENALTY,
        UNRELATED_OTHER_WORD_PENALTY,
        UNRELATED_PENALTY_CAP,
        VEVO_BONUS,
        VIEWS_DQ_THRESHOLD,
        VIEWS_HIGH_PENALTY,
        VIEWS_HIGH_THRESHOLD,
        VIEWS_MID_PENALTY,
        VIEWS_MID_THRESHOLD,
        YTM_SOURCE_BONUS,
    )
except ImportError:  # pragma: no cover - vendored sys.path fallback
    _APP_PYTHON_DIR = Path(__file__).resolve().parents[3] / "src" / "main" / "python"
    if str(_APP_PYTHON_DIR) not in sys.path:
        sys.path.insert(0, str(_APP_PYTHON_DIR))
    from ghostify_consts import (  # type: ignore[import-not-found,no-redef]
        ALBUM_BLEND_HIGH_INCLUSIVE,
        ALBUM_BLEND_LOW_EXCLUSIVE,
        ALBUM_STRONG_SET_THRESHOLD,
        ARTIST_FIXUP2_BONUS,
        ARTIST_FIXUP2_HIGH,
        ARTIST_FIXUP3_BLEND_THRESHOLD,
        ARTIST_FIXUP3_HIGH,
        ARTIST_FIXUP_GOOD_ENOUGH,
        ARTIST_FIXUP_LOW_THRESHOLD,
        CHANNEL_MATCH_BONUS,
        CHANNEL_MISMATCH_PENALTY,
        DURATION_BASE_BONUS,
        ENABLE_UNRELATED_WORDS_PENALTY,
        EXPLICIT_MISMATCH_PENALTY,
        FORBIDDEN_WORD_PENALTY,
        FORBIDDEN_WORDS,
        MAIN_ARTIST_LOW_THRESHOLD,
        NAME_MATCH_THRESHOLD,
        ARTISTS_MATCH_THRESHOLD,
        SET_EXACT_BONUS,
        SET_JACCARD_WEIGHT,
        SET_RECALL_WEIGHT,
        SET_SCORE_CAP,
        SET_SCORE_SCALE,
        SET_STOPWORDS,
        SPOTIFY_ARTISTS_SLICE,
        TIME_DECAY_RATE,
        TIME_SCORE_SCALE,
        UNRELATED_CONTENT_TYPE_PENALTY,
        UNRELATED_OTHER_WORD_PENALTY,
        UNRELATED_PENALTY_CAP,
        VEVO_BONUS,
        VIEWS_DQ_THRESHOLD,
        VIEWS_HIGH_PENALTY,
        VIEWS_HIGH_THRESHOLD,
        VIEWS_MID_PENALTY,
        VIEWS_MID_THRESHOLD,
        YTM_SOURCE_BONUS,
    )

__all__ = [
    "FORBIDDEN_WORDS",
    "SET_STOPWORDS",
    "FORBIDDEN_WORD_PENALTY",
    "YTM_SOURCE_BONUS",
    "VEVO_BONUS",
    "ENABLE_UNRELATED_WORDS_PENALTY",
    "normalize_to_words",
    "build_spotify_set",
    "build_result_set",
    "build_album_word_set",
    "calc_set_score",
    "calc_set_match",
    "calc_album_set_match",
    "fill_string",
    "create_clean_string",
    "sort_string",
    "based_sort",
    "check_common_word",
    "check_forbidden_words",
    "create_match_strings",
    "get_best_matches",
    "calc_main_artist_match",
    "calc_artists_match",
    "artists_match_fixup1",
    "artists_match_fixup2",
    "artists_match_fixup3",
    "calc_name_match",
    "calc_time_match",
    "calc_duration_bonus",
    "calc_album_match",
    "calc_views_penalty",
    "is_views_disqualified",
    "VIEWS_DQ_THRESHOLD",
    "VIEWS_MID_THRESHOLD",
    "VIEWS_HIGH_THRESHOLD",
    "VIEWS_MID_PENALTY",
    "VIEWS_HIGH_PENALTY",
]

logger = logging.getLogger(__name__)

# Canonical values live in ghostify_consts (imported above); the names below
# are re-exported here for backwards compatibility (matching.FORBIDDEN_WORDS
# etc. keep working). See ghostify_consts.py for original-value comments.


def normalize_to_words(text: Optional[str]) -> Set[str]:
    """
    Normalize text to a word set: lowercase, strip every symbol/punctuation
    to spaces, split into words, drop stoplist tokens.

    ### Arguments
    - text: text to normalize (None/empty yields empty set)

    ### Returns
    - set of word tokens
    """

    if not text:
        return set()
    lowered = str(text).lower()
    # Every non-word char (plus underscore) becomes a space: strips
    # symbols/punctuation including ".", "-", "()", "[]" etc.
    cleaned = re.sub(r"[\W_]+", " ", lowered, flags=re.UNICODE)
    words = set()
    for token in cleaned.split():
        token = token.strip()
        if not token or token in SET_STOPWORDS:
            continue
        words.add(token)
    return words


def build_spotify_set(song: Song) -> Set[str]:
    """
    Build the Spotify query word set: words(title) ∪ words(artists[:3]).

    ### Arguments
    - song: song to build the set for

    ### Returns
    - word set
    """

    out: Set[str] = set()
    out.update(normalize_to_words(song.name))
    for artist in (song.artists or [])[:SPOTIFY_ARTISTS_SLICE]:
        out.update(normalize_to_words(artist))
    return out


def build_result_set(result: Result) -> Set[str]:
    """
    Build the result word set the same way: words(name) ∪ words(artists).

    Falls back to the uploader/author words when the provider supplies no
    artist list (plain YouTube ytsearch results carry only ``author``), so
    YT and YTM candidates stay comparable in the pooled ranking.

    ### Arguments
    - result: result to build the set for

    ### Returns
    - word set
    """

    out: Set[str] = set()
    out.update(normalize_to_words(result.name))
    artists = list(result.artists or [])
    if artists:
        for artist in artists:
            out.update(normalize_to_words(artist))
    elif getattr(result, "author", None):
        out.update(normalize_to_words(result.author))
    return out


def build_album_word_set(album_name: Optional[str]) -> Set[str]:
    """
    Build an album word set with the same normalization.

    ### Arguments
    - album_name: album name (None/empty yields empty set)

    ### Returns
    - word set
    """

    return normalize_to_words(album_name)


def calc_set_score(spotify_set: Set[str], result_set: Set[str]) -> float:
    """
    Set-overlap score: recall blended with Jaccard, plus exact-match bonus.

    score = (0.6 * recall + 0.4 * jaccard) * 100, where
      recall  = |inter| / |spotify_set|
      jaccard = |inter| / |union|
    Exact-match bonus: +5 only when the two sets are exactly equal
    (no extra/missing words), capped at 100. Official uploads (exact or
    near-exact, high Jaccard) therefore strictly outrank covers/sped-up
    versions (same recall but lower Jaccard from extra "cover"/"sped"/"up"
    tokens). The final order_results total is open-ended (bonuses stack
    past 100), but this individual sub-score stays 0-100.

    ### Arguments
    - spotify_set: query word set
    - result_set: candidate word set

    ### Returns
    - score 0.0 to 100.0
    """

    if not spotify_set or not result_set:
        return 0.0
    inter = spotify_set & result_set
    if not inter:
        return 0.0
    union = spotify_set | result_set
    recall = len(inter) / len(spotify_set)
    jaccard = len(inter) / len(union) if union else 0.0
    score = (SET_RECALL_WEIGHT * recall + SET_JACCARD_WEIGHT * jaccard) * SET_SCORE_SCALE
    if spotify_set == result_set:
        score += SET_EXACT_BONUS
    return min(score, SET_SCORE_CAP)


def calc_set_match(song: Song, result: Result) -> float:
    """
    Combined set score for a song/result pair (steps 1+2 replacement).

    ### Arguments
    - song: song to match
    - result: result to match

    ### Returns
    - set overlap score 0.0 to 100.0
    """

    return calc_set_score(build_spotify_set(song), build_result_set(result))


def calc_album_set_match(song: Song, result: Result) -> float:
    """
    Album-only set score, blended in order_results exactly as before.

    ### Arguments
    - song: song to match
    - result: result to match

    ### Returns
    - album set score 0.0 to 100.0 (0.0 when either side is missing)
    """

    song_album = getattr(song, "album_name", None)
    result_album = getattr(result, "album", None)
    if not song_album or not result_album:
        return 0.0
    return calc_set_score(
        build_album_word_set(song_album), build_album_word_set(result_album)
    )


def debug(song_id: str, result_id: str, message: str) -> None:
    """
    Log a message with MATCH level

    ### Arguments
        - message: message to log
    """

    logger.log(MATCH, "[%s|%s] %s", song_id, result_id, message)


# Words that appear in music video titles as standard descriptors and
# should NOT be penalized. vevo/footnotes are included here as well so a
# future step-4 re-enable treats them as standard descriptors (not
# clickbait), consistent with SET_STOPWORDS which strips the same subset
# for set scoring.
_STANDARD_TITLE_WORDS = frozenset(
    {
        "official",
        "video",
        "audio",
        "ft",
        "feat",
        "featuring",
        "the",
        "of",
        "and",
        "a",
        "an",
        "album",
        "version",
        "single",
        "remastered",
        "remaster",
        "hd",
        "hq",
        "music",
        "song",
        "mv",
        "visualizer",
        "lyric",
        "lyrics",
        "4k",
        "8k",
        "cover",
        "vevo",
        "footnotes",
    }
)

# Words that strongly indicate non-song content (making-of, reaction, etc.)
# These are aggressively penalized in addition to forbidden words.
_CONTENT_TYPE_WORDS = frozenset(
    {
        "making",
        "reaction",
        "react",
        "compilation",
        "mixtape",
        "mashup",
        "mash",
        "highlights",
        "behind",
        "scenes",
        "bts",
        "btsm",
        "interview",
        "review",
        "unboxing",
        "tutorial",
        "howto",
        "tutorial",
        "challenge",
        "trend",
        "tiktok",
        "tik",
        "tok",
    }
)


def _penalty_unrelated_words(song: Song, result: Result) -> float:
    """Return a penalty (-points) based on words in the result title that
    do not appear in the song name or artist names but indicate non-song
    content (e.g. 'Making of', 'Reaction').

    Step 4 is currently DISABLED (ENABLE_UNRELATED_WORDS_PENALTY=False):
    always returns 0.0 while keeping the code path for a future re-enable.
    """
    if not ENABLE_UNRELATED_WORDS_PENALTY:
        return 0.0
    result_words = set(slugify(result.name).replace("-", " ").split())
    song_words = set()
    song_words.update(slugify(song.name).replace("-", " ").split())
    for artist in song.artists or []:
        slug = slugify(artist)
        song_words.update(slug.replace("-", " ").split())
        song_words.add(slug.replace("-", ""))
    if song.artist:
        slug = slugify(song.artist)
        song_words.update(slug.replace("-", " ").split())
        song_words.add(slug.replace("-", ""))

    penalty = 0.0
    for word in result_words:
        if not word or word in song_words or word in _STANDARD_TITLE_WORDS:
            continue
        if word in _CONTENT_TYPE_WORDS:
            penalty += UNRELATED_CONTENT_TYPE_PENALTY
        else:
            penalty += UNRELATED_OTHER_WORD_PENALTY
    return min(penalty, UNRELATED_PENALTY_CAP)


def fill_string(strings: List[str], main_string: str, string_to_check: str) -> str:
    """
    Create a string with strings from `strings` list
    if they are not yet present in main_string
    but are present in string_to_check

    ### Arguments
    - strings: strings to check
    - main_string: string to add strings to
    - string_to_check: string to check if strings are present in

    ### Returns
    - string with strings from `strings` list
    """

    final_str = main_string
    test_str = final_str.replace("-", "")
    simple_test_str = string_to_check.replace("-", "")
    for string in strings:
        slug_str = slugify(string).replace("-", "")

        if slug_str in simple_test_str and slug_str not in test_str:
            final_str += f"-{slug_str}"
            test_str += slug_str

    return final_str


def create_clean_string(
    words: List[str], string: str, sort: bool = False, join_str: str = "-"
) -> str:
    """
    Create a string with strings from `words` list
    if they are not yet present in `string`

    ### Arguments
    - words: strings to check
    - string: string to check if strings are present in
    - sort: sort strings in list
    - join_str: string to join strings with

    ### Returns
    - string with strings from `words` list
    """

    string = slugify(string).replace("-", "")

    final = []
    for word in words:
        word = slugify(word).replace("-", "")

        if word in string:
            continue

        final.append(word)

    if sort:
        return sort_string(final, join_str)

    return f"{join_str}".join(final)


def sort_string(strings: List[str], join_str: str) -> str:
    """
    Sort strings in list and join them with `join` string

    ### Arguments
    - strings: strings to sort
    - join: string to join strings with

    ### Returns
    - joined sorted string
    """

    final_str = strings
    final_str.sort()

    return f"{join_str}".join(final_str)


def based_sort(strings: List[str], based_on: List[str]) -> Tuple[List[str], List[str]]:
    """
    Sort strings in list based on the order of strings in `based_on` list

    ### Arguments
    - strings: strings to sort
    - based_on: strings to sort `strings` list based on

    ### Returns
    - sorted list of strings
    """

    strings.sort()
    based_on.sort()

    list_map = {value: index for index, value in enumerate(based_on)}

    strings = sorted(
        strings,
        key=lambda x: list_map.get(x, -1),
        reverse=True,
    )

    based_on.reverse()

    return strings, based_on


def check_common_word(song: Song, result: Result) -> bool:
    """
    Check if a word is present in a sentence

    ### Arguments
    - song: song to match
    - result: result to match

    ### Returns
    - True if word is present in sentence, False otherwise
    """

    sentence_words = slugify(song.name).split("-")
    to_check = slugify(result.name).replace("-", "")

    for word in sentence_words:
        if word != "" and word in to_check:
            return True

    return False


def check_forbidden_words(song: Song, result: Result) -> Tuple[bool, List[str]]:
    """
    Check if a forbidden word is present in the result name

    ### Arguments
    - song: song to match
    - result: result to match

    ### Returns
    - True if forbidden word is present in result name, False otherwise
    """

    song_name = slugify(song.name).replace("-", "")
    to_check = slugify(result.name).replace("-", "")

    words = []
    for word in FORBIDDEN_WORDS:
        if word in to_check and word not in song_name:
            words.append(word)

    return len(words) > 0, words


def create_match_strings(
    song: Song, result: Result, search_query: Optional[str] = None
) -> Tuple[str, str]:
    """
    Create strings based on song and result to match
    fill strings with missing artists

    ### Arguments
    - song: song to match
    - result: result to match

    ### Returns
    - tuple of strings to match
    """

    slug_song_name = slugify(song.name)
    slug_song_title = slugify(
        create_song_title(song.name, song.artists, for_lyrics=False)
        if not search_query
        else create_search_query(song, search_query, False, None, True)
    )

    test_str1 = slugify(result.name)
    test_str2 = slug_song_name if result.verified else slug_song_title

    # Fill strings with missing artists
    test_str1 = fill_string(song.artists, test_str1, test_str2)
    test_str2 = fill_string(song.artists, test_str2, test_str1)

    # Sort both strings and then join them
    test_list1, test_list2 = based_sort(test_str1.split("-"), test_str2.split("-"))
    test_str1, test_str2 = "-".join(test_list1), "-".join(test_list2)

    return test_str1, test_str2


def get_best_matches(
    results: Dict[Result, float], score_threshold: float
) -> List[Tuple[Result, float]]:
    """
    Get best matches from a list of results

    ### Arguments
    - results: list of results to match
    - score_threshold: threshold to match results

    ### Returns
    - list of best matches
    """

    result_items = list(results.items())

    # Sort results by highest score
    sorted_results = sorted(result_items, key=lambda x: x[1], reverse=True)

    best_score = sorted_results[0][1]

    return [
        result
        for result in sorted_results
        if (best_score - result[1]) <= score_threshold
    ]


def calc_main_artist_match(song: Song, result: Result) -> float:
    """
    Calculate how well the song's main artist matches the result artists.

    ### Arguments
    - song: song to match
    - result: result to match

    ### Returns
    - artist match percentage (0.0 to 100.0)
    """

    main_artist_match = 0.0

    # Result has no artists, return 0.0
    if not result.artists:
        return main_artist_match

    song_artists, result_artists = (
        list(map(slugify, song.artists)),
        list(map(slugify, result.artists)),
    )
    sorted_song_artists, sorted_result_artists = based_sort(
        song_artists, result_artists
    )

    debug(song.song_id, result.result_id, f"Song artists: {sorted_song_artists}")
    debug(song.song_id, result.result_id, f"Result artists: {sorted_result_artists}")

    slug_song_main_artist = slugify(song.artists[0])
    slug_result_main_artist = sorted_result_artists[0]

    # Result has only one artist, but song has multiple artists
    # we can assume that other artists are in the main artist name
    if len(song.artists) > 1 and len(result.artists) == 1:
        for artist in map(slugify, song.artists[1:]):
            artist = sort_string(slugify(artist).split("-"), "-")

            res_main_artist = sort_string(slug_result_main_artist.split("-"), "-")

            if artist in res_main_artist:
                main_artist_match += SET_SCORE_SCALE / len(song.artists)

        return main_artist_match

    # Match main result artist with main song artist
    main_artist_match = ratio(slug_song_main_artist, slug_result_main_artist)

    debug(
        song.song_id, result.result_id, f"First main artist match: {main_artist_match}"
    )

    # Use second artist from the sorted list to
    # calculate the match if the first artist match is too low
    if main_artist_match < MAIN_ARTIST_LOW_THRESHOLD and len(song_artists) > 1:
        for song_artist, result_artist in product(
            song_artists[:2], sorted_result_artists[:2]
        ):
            new_artist_match = ratio(song_artist, result_artist)
            debug(
                song.song_id,
                result.result_id,
                f"Matched {song_artist} with {result_artist}: {new_artist_match}",
            )

            main_artist_match = max(main_artist_match, new_artist_match)

    return main_artist_match


def calc_artists_match(song: Song, result: Result) -> float:
    """
    Check if all artists are present in list of artists

    Kept for compatibility: now delegates to the simpler set-based scorer
    (steps 1+2 replacement). See calc_set_match.

    ### Arguments
    - song: song to match
    - result: result to match

    ### Returns
    - artists match percentage
    """

    score = calc_set_match(song, result)
    debug(song.song_id, result.result_id, f"Set artists match (compat): {score}")
    return score


def artists_match_fixup1(song: Song, result: Result, score: float) -> float:
    """
    Multiple fixes to the artists score to improve
    the accuracy, especially when the result's artist
    metadata is incomplete (e.g. only the main artist).

    ### Arguments
    - song: song to match
    - result: result to match
    - score: current score

    ### Returns
    - new score
    """

    # Don't fix if the score is already good enough
    if score > ARTIST_FIXUP_GOOD_ENOUGH:
        return score

    # If we didn't find any artist match,
    # we fallback to channel name match
    result_artist_str = (
        ", ".join(result.artists) if result.artists else (result.author or "")
    )
    channel_name_match = ratio(
        slugify(song.artist),
        slugify(result_artist_str),
    )

    score = max(score, channel_name_match)

    # If artist match is still too low,
    # we fallback to matching all song artist names
    # with the result's title
    if score <= ARTIST_FIXUP_LOW_THRESHOLD:
        artist_title_match = 0.0
        result_name = slugify(result.name).replace("-", "")
        for artist in song.artists:
            slug_artist = slugify(artist).replace("-", "")

            if slug_artist in result_name:
                artist_title_match += 1.0

        artist_title_match = (artist_title_match / len(song.artists)) * SET_SCORE_SCALE

        score = max(score, artist_title_match)

    # If artist match is still too low,
    # we fallback to matching all song artist names
    # with the result's artists
    if score <= ARTIST_FIXUP_LOW_THRESHOLD:
        # Song artists: ['charlie-moncler', 'fukaj', 'mata', 'pedro']
        # Result artists: ['fukaj-mata-charlie-moncler-und-pedro']

        # For artist_list1
        artist_list1 = []
        for artist in song.artists:
            artist_list1.extend(slugify(artist).split("-"))

        # For artist_list2
        artist_list2 = []
        if result.artists:
            for artist in result.artists:
                artist_list2.extend(slugify(artist).split("-"))

        artist_tuple1 = tuple(artist_list1)
        artist_tuple2 = tuple(artist_list2)

        artist_title_match = ratio(artist_tuple1, artist_tuple2)

        score = max(score, artist_title_match)

    return score


def artists_match_fixup2(
    song: Song, result: Result, score: float, search_query: Optional[str] = None
) -> float:
    """
    Multiple fixes to the artists score for
    verified results to improve the accuracy

    ### Arguments
    - song: song to match
    - result: result to match
    - score: current score

    ### Returns
    - new score
    """

    if score > ARTIST_FIXUP2_HIGH or not result.verified:
        # Don't fixup the score
        # if the artist match is already high
        # or if the result is not verified
        return score

    # Slugify some variables
    slug_song_name = slugify(song.name)
    slug_result_name = slugify(result.name)

    # # Check if the main artist is simlar
    has_main_artist = (score / (2 if len(song.artists) > 1 else 1)) > ARTIST_FIXUP_GOOD_ENOUGH

    _, match_str2 = create_match_strings(song, result, search_query)

    # Check if other song artists are in the result name
    # if they are, we increase the artist match
    # (main artist is already checked, so we skip it)
    artists_to_check = song.artists[int(has_main_artist) :]
    for artist in artists_to_check:
        artist = slugify(artist).replace("-", "")
        if artist in match_str2.replace("-", ""):
            score += ARTIST_FIXUP2_BONUS

    # if the artist match is still too low,
    # we fallback to matching all song artist names
    # with the result's artists
    if score <= ARTIST_FIXUP_LOW_THRESHOLD:
        # Artists from song/result name without the song/result name words
        artist_list1 = create_clean_string(song.artists, slug_song_name, True)
        artist_list2 = create_clean_string(
            list(result.artists) if result.artists else [result.author],
            slug_result_name,
            True,
        )

        artist_title_match = ratio(artist_list1, artist_list2)

        score = max(score, artist_title_match)

    return score


def artists_match_fixup3(song: Song, result: Result, score: float) -> float:
    """
    Calculate match percentage based result's name
    and song's title if the result has exactly one artist
    and the song has more than one artist

    ### Arguments
    - song: song to match
    - result: result to match
    - score: current score

    ### Returns
    - new score
    """

    if (
        score > ARTIST_FIXUP3_HIGH
        or not result.artists
        or len(result.artists) > 1
        or len(song.artists) == 1
    ):
        # Don't fixup the score
        # if the score is already high
        # or if the result has more than one artist
        # or if the song has only one artist
        return score

    artists_score_fixup = ratio(
        slugify(result.name),
        slugify(create_song_title(song.name, [song.artist], for_lyrics=False)),
    )

    if artists_score_fixup >= ARTIST_FIXUP3_BLEND_THRESHOLD:
        score = (score + artists_score_fixup) / 2

    # Make sure that the score is not higher than 100
    score = min(score, SET_SCORE_CAP)

    return score


def calc_name_match(
    song: Song, result: Result, search_query: Optional[str] = None
) -> float:
    """
    Calculate name match percentage

    Kept for compatibility: now delegates to the simpler set-based scorer
    (steps 1+2 replacement). See calc_set_match. search_query is unused.

    ### Arguments
    - song: song to match
    - result: result to match

    ### Returns
    - name match percentage
    """

    score = calc_set_match(song, result)
    debug(song.song_id, result.result_id, f"Set name match (compat): {score}")
    return score


def calc_time_match(song: Song, result: Result) -> float:
    """
    Calculate time difference between song and result (legacy 0-100 scorer).

    Kept for compatibility (external callers e.g. score_tuner display).
    order_results no longer uses this for the final score — it uses
    calc_duration_bonus (additive, open-ended) instead.

    ### Arguments
    - song: song to match
    - result: result to match

    ### Returns
    - time match 0.0 to 100.0 (exp decay)
    """

    time_diff = abs(song.duration - result.duration)
    score = exp(-TIME_DECAY_RATE * time_diff)
    return score * TIME_SCORE_SCALE


def calc_duration_bonus(song: Song, result: Result) -> float:
    """
    Additive duration bonus: closer video length to original = more points.

    bonus = 10 - abs(video_secs - song_secs):
      spot-on (delta 0) => +10, delta 5 => +5, delta 10 => 0, delta 20 => -10.
    Missing/zero duration on either side yields 0 bonus (not a penalty),
    so candidates without duration metadata are neither boosted nor buried.

    This is an additive bonus on the open-ended final score, not a 0-100
    match to be averaged in.

    ### Arguments
    - song: song to match
    - result: result to match

    ### Returns
    - duration bonus (open-ended, typically +10 down to negative)
    """

    try:
        song_secs = float(getattr(song, "duration", 0) or 0)
    except (TypeError, ValueError):
        return 0.0
    try:
        video_secs = float(getattr(result, "duration", 0) or 0)
    except (TypeError, ValueError):
        return 0.0
    if song_secs <= 0 or video_secs <= 0:
        return 0.0
    return DURATION_BASE_BONUS - abs(video_secs - song_secs)


def calc_album_match(song: Song, result: Result) -> float:
    """
    Calculate album match percentage via album word sets.

    Scored separately and only blended in order_results as before
    (verified + low album case).

    ### Arguments
    - song: song to match
    - result: result to match

    ### Returns
    - album match percentage
    """

    return calc_album_set_match(song, result)


def _normalize_views(views) -> Optional[int]:
    """
    Normalize a raw ``Result.views`` value to an int, or None when unknown.

    Unknown covers: None (uncached, would need a get_views lookup),
    0 (missing/unparseable after the parse fix: YTM -> 0, YT missing -> 0),
    bools, negatives, and non-numeric strings. Callers treat unknown as
    -20 (not DQ) so fresh official uploads with missing counts survive.

    ### Arguments
    - views: raw views value from the result.

    ### Returns
    - int view count (> 0) or None when unknown.
    """

    if views is None or isinstance(views, bool):
        return None
    try:
        number = int(views)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None
    if number <= 0:
        return None
    return number


def is_views_disqualified(views) -> bool:
    """
    Check if a view count disqualifies the candidate (< 1,000).

    Unknown views (None/0/unparseable, see _normalize_views) return False:
    they are penalized (-20) rather than dropped, so fresh official uploads
    with missing counts are never filtered out by this rule.

    ### Arguments
    - views: raw ``Result.views`` value.

    ### Returns
    - True when the candidate must be dropped (< VIEWS_DQ_THRESHOLD).
    """

    normalized = _normalize_views(views)
    if normalized is None:
        return False
    return normalized < VIEWS_DQ_THRESHOLD


def calc_views_penalty(views) -> float:
    """
    Additive low-views penalty (open-ended score, negative or zero).

    Buckets:
      unknown (None/0/unparseable) => -20 (not DQ; fresh/missing counts).
      < 1,000  => 0 here (caller drops the candidate via
        is_views_disqualified; penalty is unused for DQ'd rows).
      < 10,000 => -20.
      < 50,000 => -10.
      >= 50,000 => 0.

    ### Arguments
    - views: raw ``Result.views`` value.

    ### Returns
    - penalty to ADD to the score (0.0, -10.0, or -20.0).
    """

    normalized = _normalize_views(views)
    if normalized is None:
        return float(-VIEWS_MID_PENALTY)
    if normalized < VIEWS_DQ_THRESHOLD:
        return 0.0
    if normalized < VIEWS_MID_THRESHOLD:
        return float(-VIEWS_MID_PENALTY)
    if normalized < VIEWS_HIGH_THRESHOLD:
        return float(-VIEWS_HIGH_PENALTY)
    return 0.0


def _is_youtube_music_source(result: Result) -> bool:
    """
    Check if a result comes from YouTube Music (vs plain YouTube).

    Primary signal is Result.source == "YouTubeMusic" (set from the provider
    class name in ytmusic.py / youtube.py); music.youtube.com URL is the
    fallback so YTM song URLs are still recognised even if source is lost.
    YTM videos carry www.youtube.com URLs but keep source "YouTubeMusic",
    so they are still distinguished from plain-YT ytsearch results.

    ### Arguments
    - result: result to check

    ### Returns
    - True when the result originates from YouTube Music
    """

    if getattr(result, "source", None) == "YouTubeMusic":
        return True
    url = getattr(result, "url", "") or ""
    return "music.youtube.com" in str(url)


def order_results(
    results: List[Result],
    song: Song,
    search_query: Optional[str] = None,
) -> Dict[Result, float]:
    """
    Order results.

    Scoring model: open-ended score (NOT a 0-100 %). Sub-scores
    (set/artist/name/album) stay 0-100, but the final total stacks
    additive bonuses on top and may exceed 100:
      base = (artists_match + name_match) / 2  (after -5/forbidden-word)
      +10 channel match / -15 non-official channel (additive, no cap)
      +3 vevo-official bonus when raw title has "vevo" and
        (verified or channel-hit) (additive, no cap)
      album blend for verified + low-album case, guarded: only when
        0 < album_match <= 80 and set_score < 85 (skip on 0 to avoid
        halving a correct single-vs-deluxe match; skip on strong
        title/artist >= 85 which is an edition mismatch, not wrong song)
      explicit mismatch -5 (additive)
      duration bonus = 10 - abs(video_secs - song_secs) (additive;
        missing/0 duration => 0, not a penalty)
      YTM source +5 (additive, no cap)
      views: <1,000 disqualified (dropped like the name<=55 rule);
        <10,000 -20; <50,000 -10; >=50,000 +0; unknown (None/0) -20
        not DQ (additive, no cap; applied after YTM, before final).
    Duration filtering is intentionally NOT done here — far durations
    simply earn a large negative bonus and rank lower. Post-download
    _validate (90 s tolerance) is unchanged and remains the hard gate.
    Views DQ fallback: if every candidate is <1k, the best DQ'd candidate
    (highest pre-views score) is rescued so the dict is not empty; see
    VIEWS_* constants for the documented policy.

    ### Arguments
    - results: The results to order.
    - song: The song to order for.
    - search_query: The search query.

    ### Returns
    - The ordered results.
    """

    # Assign an overall avg match value to each result
    links_with_match_value = {}
    # Disqualified-on-views survivors: (result, pre-views score). Used only
    # when every candidate is <1k, to avoid an empty dict / SEARCH_FAILED
    # storm on obscure tracks. See VIEWS_* policy above.
    _views_dq_fallback: List[Tuple[Result, float]] = []

    # Iterate over all results
    for result in results:
        debug(
            song.song_id,
            result.result_id,
            f"Calculating match value for {result.url} - {result.json}",
        )

        # skip results that have no common words in their name
        if not check_common_word(song, result):
            debug(
                song.song_id, result.result_id, "Skipping result due to no common words"
            )

            continue

        # Steps 1+2 (replaced): simpler set-based scoring.
        # spotify_set = words(title) ∪ words(artists[:3]); result_set built
        # the same way; score = recall/Jaccard blend + exact-match bonus.
        # One score drives both artists_match and name_match (compat with
        # downstream averaging/thresholds). Legacy fuzzy helpers
        # (calc_main_artist_match, calc_artists_match + fixups,
        # calc_name_match) are kept for compat but no longer used here.
        set_score = calc_set_match(song, result)
        debug(song.song_id, result.result_id, f"Set match: {set_score}")

        artists_match = set_score
        debug(song.song_id, result.result_id, f"Final artists match: {artists_match}")

        # Calculate name match (same set score; forbidden penalty applied below)
        name_match = set_score
        debug(song.song_id, result.result_id, f"Initial name match: {name_match}")

        # Check if result contains forbidden words
        contains_fwords, found_fwords = check_forbidden_words(song, result)
        if contains_fwords:
            # Softened penalty (step 3): -5 per word (was -30).
            for _ in found_fwords:
                name_match -= FORBIDDEN_WORD_PENALTY

        debug(
            song.song_id,
            result.result_id,
            f"Contains forbidden words: {contains_fwords}, {found_fwords}",
        )
        debug(song.song_id, result.result_id, f"Final name match: {name_match}")

        # Step 4 (disabled): unrelated/clickbait words penalty gated off.
        # _penalty_unrelated_words returns 0.0 when
        # ENABLE_UNRELATED_WORDS_PENALTY is False; code path kept.
        if ENABLE_UNRELATED_WORDS_PENALTY:
            name_match -= _penalty_unrelated_words(song, result)
        else:
            debug(
                song.song_id,
                result.result_id,
                "Skipping unrelated-words penalty (disabled, step 4 off)",
            )

        # Calculate album match
        album_match = calc_album_match(song, result)
        debug(song.song_id, result.result_id, f"Final album match: {album_match}")

        # Additive duration bonus (open-ended): 10 - delta_seconds.
        # Missing/zero duration => 0 bonus (not a penalty). Replaces the
        # old exp()-based 0-100 time_match blending (calc_time_match is
        # kept for compat/display only).
        duration_bonus = calc_duration_bonus(song, result)
        debug(song.song_id, result.result_id, f"Duration bonus: {duration_bonus}")

        # Ignore results with name match lower than threshold
        if name_match <= NAME_MATCH_THRESHOLD:
            debug(
                song.song_id,
                result.result_id,
                "Skipping result due to name match lower than 55%",
            )
            continue

        # Ignore results with artists match lower than threshold
        if artists_match < ARTISTS_MATCH_THRESHOLD and result.source != "slider.kz":
            debug(
                song.song_id,
                result.result_id,
                "Skipping result due to artists match lower than 70%",
            )
            continue

        # Calculate total match
        average_match = (artists_match + name_match) / 2
        debug(song.song_id, result.result_id, f"Average match: {average_match}")

        # Channel/author bonus: if the uploader matches a song artist,
        # boost the score; if not, penalize to deprioritize random uploads.
        # Additive on the open-ended total (no 100 cap, no floor).
        channel_hit = False
        if result.author and song.artists:
            slug_author = slugify(result.author).replace("-", "")
            for artist in song.artists:
                slug_artist = slugify(artist).replace("-", "")
                if slug_artist and (
                    slug_artist in slug_author or slug_author in slug_artist
                ):
                    average_match += CHANNEL_MATCH_BONUS
                    channel_hit = True
                    debug(
                        song.song_id,
                        result.result_id,
                        f"Channel-author match bonus: +10 ({result.author} ~ {artist})",
                    )
                    break
            else:
                # No artist match — penalize non-official uploads
                average_match -= CHANNEL_MISMATCH_PENALTY
                debug(
                    song.song_id,
                    result.result_id,
                    f"Non-official channel penalty: -15 (author={result.author})",
                )

        # Vevo-official bonus: +3 when the raw title carries "vevo" and the
        # uploader is trusted (verified or channel-hit above). Uses the raw
        # name (not the filtered set) so SET_STOPWORDS filtering cannot
        # break official detection.
        if "vevo" in (result.name or "").lower() and (
            result.verified or channel_hit
        ):
            average_match += VEVO_BONUS
            debug(
                song.song_id,
                result.result_id,
                f"Vevo-official bonus: +{VEVO_BONUS} ({result.name})",
            )

        if (
            result.verified
            and not result.isrc_search
            and result.album
            and ALBUM_BLEND_LOW_EXCLUSIVE < album_match <= ALBUM_BLEND_HIGH_INCLUSIVE
            and set_score < ALBUM_STRONG_SET_THRESHOLD
        ):
            # Album blend (verified + low-album case, as before) with two
            # guards:
            # - skip when album_match == 0 (missing/mismatch): halving on 0
            #   would bury a correct single-vs-deluxe match.
            # - skip when set_score >= 85 (strong title/artist): the track is
            #   already identified; a low album score is a deluxe/edition
            #   mismatch, not a wrong song.
            # Goal: correct I Ain't Worried keeps 110 +9 +5 = 124 and beats
            # the acoustic variant (~113.8) instead of being halved.
            # we are almost certain that this is the correct result
            # so we add the album match to the average match
            average_match = (average_match + album_match) / 2
            debug(
                song.song_id,
                result.result_id,
                f"Average match /w album match: {average_match}",
            )

        if (result.explicit is not None and song.explicit is not None) and (
            result.explicit != song.explicit
        ):
            debug(
                song.song_id,
                result.result_id,
                "Lowering average match due to explicit mismatch",
            )
            average_match -= EXPLICIT_MISMATCH_PENALTY

        # Duration: additive bonus, not a 0-100 average blend.
        # No duration-based filtering here — a far duration simply earns a
        # large negative bonus (e.g. delta 20 => -10) and ranks lower.
        average_match += duration_bonus
        debug(
            song.song_id,
            result.result_id,
            f"Average match /w duration bonus: {average_match}",
        )

        debug(song.song_id, result.result_id, f"Final average match: {average_match}")

        # YouTube Music source bonus: +5 for YTM candidates so they outrank
        # equal-scoring plain-YouTube candidates in the pooled YT+YTM ranking.
        # Applied once here (single choke point); get_best_result must not
        # re-apply it. Additive on the open-ended total (no 100 cap).
        if _is_youtube_music_source(result):
            average_match += YTM_SOURCE_BONUS
            debug(
                song.song_id,
                result.result_id,
                f"YouTube Music source bonus: +{YTM_SOURCE_BONUS} ({result.source})",
            )

        # Low view-count policy (absolute, additive, open-ended; applied
        # after YTM/duration/channel, before final). <1k is disqualified
        # (dropped like the name<=55 rule); <10k -20; <50k -10; >=50k +0.
        # Unknown (None/0/unparseable) is -20, never DQ, so fresh official
        # uploads with missing counts survive (see VIEWS_* docs).
        if is_views_disqualified(getattr(result, "views", None)):
            debug(
                song.song_id,
                result.result_id,
                f"Skipping result due to low views <{VIEWS_DQ_THRESHOLD} "
                f"(views={getattr(result, 'views', None)!r})",
            )
            _views_dq_fallback.append((result, average_match))
            continue
        views_penalty = calc_views_penalty(getattr(result, "views", None))
        if views_penalty:
            average_match += views_penalty
            debug(
                song.song_id,
                result.result_id,
                f"Low-views penalty: {views_penalty:+g} "
                f"(views={getattr(result, 'views', None)!r})",
            )

        debug(song.song_id, result.result_id, f"Final average match: {average_match}")

        # the results along with the avg Match
        links_with_match_value[result] = average_match

    if not links_with_match_value and _views_dq_fallback:
        # All candidates were <1k: rescue the best DQ'd one (highest
        # pre-views score) with its pre-views score, to avoid an empty dict
        # and a SEARCH_FAILED storm on obscure/low-view tracks.
        best_dq = max(_views_dq_fallback, key=lambda item: item[1])
        debug(
            getattr(song, "song_id", "?"),
            best_dq[0].result_id,
            f"All candidates <{VIEWS_DQ_THRESHOLD} views: rescuing best "
            f"(views={getattr(best_dq[0], 'views', None)!r}, "
            f"score={best_dq[1]}) to avoid empty result",
        )
        links_with_match_value[best_dq[0]] = best_dq[1]

    return links_with_match_value

"""View-count coverage: YTM abbreviated views + YT view_count mapping.

Goal: prove ``Result.views`` is populated (int >= 0, never None) on >95% of
results on BOTH platforms:

* YTM — ``spotdl.providers.audio.ytmusic.parse_ytm_views`` parses the
  abbreviated counts (``"3.6B"``, ``"937K"``, ...) at map time.
* YT — ``spotdl.providers.audio.youtube`` maps ``entry.get("view_count") or 0``.

Counts math (provider caps, documented honestly):

* YTM honors ``limit``: ``songs limit=50`` + ``videos limit=50`` = ~100 per
  query. ``QUERIES_FAST`` (5 queries) therefore targets ~500 YTM results.
* YT ignores ``limit`` (hardcoded ``ytsearch10`` in ``youtube.py``), so one
  query yields at most 10 results: ``QUERIES_FAST`` yields ~50 YT results.
  Full 500-result YT coverage needs ~50 queries — set
  ``GHOSTIFY_VIEWS_FULL=1`` to use ``QUERIES_FULL_YT`` (50 queries, ~500
  results, slow: several minutes). Default (fast) mode asserts coverage on
  the pooled ~50 YT results plus absolute minimums, which statistically
  validates the same mapping line.
* ``GHOSTIFY_VIEWS_QUERY_CAP=N`` caps queries per provider (quick dry run;
  absolute minimums scale proportionally).

Thresholds: >=95% of pooled results per provider must carry
``isinstance(views, int) and views >= 0`` (5% allowance for live / deleted /
private / upcoming entries). Absolute minimums guard against empty pools.

Side effects: none — only ``get_results`` / ``search_yt_candidates`` are
called. No downloads, no sidecars, no playlist fetches.

Requires: network (skips otherwise).
Run with: ``pytest app/app/src/test/python/integration -k views -v``
Full YT sweep: ``GHOSTIFY_VIEWS_FULL=1 pytest app/app/src/test/python/integration -k views -v``
Offline only: ``pytest app/app/src/test/python/integration/test_views_coverage.py -k parse -m "not integration" -v``
"""

from __future__ import annotations

import math
import os
import socket

import pytest

from spotdl.providers.audio.ytmusic import parse_ytm_views

import ghostify_dl

# ---------------------------------------------------------------------------
# Config: queries, limits, thresholds.
# ---------------------------------------------------------------------------

# 5 canonical queries. YTM: 5 x (50 songs + 50 videos) ~= 500 results.
# YT (fast): 5 x 10 ~= 50 results (ytsearch10 cap, see module docstring).
QUERIES_FAST = [
    "The Weeknd Blinding Lights",
    "Vicetone Nevada",
    "OneRepublic I Ain't Worried",
    "Ed Sheeran Shape of You",
    "Queen Bohemian Rhapsody",
]

# 45 extra queries so FULL mode reaches 50 YT queries x 10 ~= 500 results.
QUERIES_EXTRA = [
    "Adele Hello",
    "Billie Eilish Bad Guy",
    "Dua Lipa Levitating",
    "The Weeknd Starboy",
    "Imagine Dragons Believer",
    "Maroon 5 Sugar",
    "Luis Fonsi Despacito",
    "Ed Sheeran Perfect",
    "Bruno Mars Uptown Funk",
    "Daft Punk Get Lucky",
    "Adele Rolling in the Deep",
    "Coldplay Viva la Vida",
    "Linkin Park Numb",
    "Eminem Lose Yourself",
    "Rihanna Diamonds",
    "Beyonce Halo",
    "Taylor Swift Shake It Off",
    "Taylor Swift Blank Space",
    "Katy Perry Roar",
    "Lady Gaga Poker Face",
    "Justin Bieber Sorry",
    "Justin Bieber Baby",
    "Shawn Mendes Senorita",
    "Camila Cabello Havana",
    "Post Malone Circles",
    "Post Malone Sunflower",
    "Travis Scott Goosebumps",
    "Drake God's Plan",
    "Kendrick Lamar Humble",
    "Sia Chandelier",
    "Ariana Grande 7 rings",
    "Ariana Grande Thank U Next",
    "Miley Cyrus Flowers",
    "Harry Styles As It Was",
    "Olivia Rodrigo Drivers License",
    "Billie Eilish Ocean Eyes",
    "Lana Del Rey Summertime Sadness",
    "Arctic Monkeys Do I Wanna Know",
    "Tones and I Dance Monkey",
    "Lewis Capaldi Someone You Loved",
    "Sam Smith Stay With Me",
    "John Legend All of Me",
    "Pharrell Williams Happy",
    "Gotye Somebody That I Used To Know",
    "Psy Gangnam Style",
]

QUERIES_FULL_YT = QUERIES_FAST + QUERIES_EXTRA
assert len(QUERIES_FULL_YT) == 50

YTM_LIMIT_SONGS = 50  # matches YouTubeMusic.GET_RESULTS_OPTS production caps
YTM_LIMIT_VIDEOS = 50
COVERAGE_THRESHOLD = 0.95  # >=95% of pooled results per provider

MIN_YTM_FAST = 200  # pooled YTM minimum in fast mode (target ~500)
MIN_YT_FAST = 20  # pooled YT minimum in fast mode (target ~50)
MIN_YTM_FULL = 400  # pooled YTM minimum in full mode
MIN_YT_FULL = 400  # pooled YT minimum in full mode (target ~500)

CONNECT_TIMEOUT = 5.0


# ---------------------------------------------------------------------------
# Helpers.
# ---------------------------------------------------------------------------


def _is_full_mode() -> bool:
    return os.environ.get("GHOSTIFY_VIEWS_FULL") == "1"


def _query_cap() -> int | None:
    raw = os.environ.get("GHOSTIFY_VIEWS_QUERY_CAP")
    if not raw:
        return None
    try:
        return max(1, int(raw))
    except ValueError:
        return None


def _scaled_minimum(configured: int, queries_run: int, queries_full: int) -> int:
    """Scale an absolute pool minimum when QUERY_CAP shortens a dry run."""
    if queries_run >= queries_full:
        return configured
    return max(1, -(-configured * queries_run // queries_full))


def _require_online() -> None:
    """Skip the live test when there is no network route."""
    try:
        socket.create_connection(("8.8.8.8", 53), timeout=CONNECT_TIMEOUT).close()
    except OSError:
        pytest.skip("no network route (views coverage needs live YTM/YT)")


def _views_ok(views) -> bool:
    """A result counts as covered when views is a real int >= 0 (never None)."""
    return isinstance(views, int) and not isinstance(views, bool) and views >= 0


def _check_pool(results, provider: str):
    """Return (total, ok, bad_sample) for one provider's pooled results."""
    total = len(results)
    bad = []
    ok = 0
    for result in results:
        views = getattr(result, "views", None)
        if _views_ok(views):
            ok += 1
        elif len(bad) < 5:
            bad.append((getattr(result, "url", "?"), repr(views)))
    return total, ok, bad


def _report_pool(provider: str, total: int, ok: int, bad) -> None:
    rate = ok / total if total else 0.0
    print(
        f"[{provider}] {ok}/{total} covered ({rate * 100:.1f}%), "
        f"threshold {COVERAGE_THRESHOLD * 100:.0f}%",
        flush=True,
    )
    for url, views in bad:
        print(f"  MISSING views={views} {url}", flush=True)


# ---------------------------------------------------------------------------
# Offline: parse_ytm_views unit tests (no network, always run).
# ---------------------------------------------------------------------------


class TestParseYtmViews:
    def test_billions(self):
        assert parse_ytm_views("3.6B") == 3_600_000_000

    def test_thousands(self):
        assert parse_ytm_views("937K") == 937_000

    def test_millions(self):
        assert parse_ytm_views("880M") == 880_000_000

    def test_small_thousands(self):
        assert parse_ytm_views("45K") == 45_000

    def test_bare_billion(self):
        assert parse_ytm_views("1B") == 1_000_000_000

    def test_decimal_millions(self):
        assert parse_ytm_views("1.7M") == 1_700_000

    def test_lowercase_suffix(self):
        assert parse_ytm_views("2.5k") == 2_500

    def test_trillions(self):
        assert parse_ytm_views("2.3T") == 2_300_000_000_000

    def test_us_thousands_separators(self):
        assert parse_ytm_views("1,234,567") == 1_234_567

    def test_single_comma_decimal(self):
        assert parse_ytm_views("1,7M") == 1_700_000
        assert parse_ytm_views("7,8K") == 7_800

    def test_trailing_word(self):
        assert parse_ytm_views("1.2M views") == 1_200_000
        assert parse_ytm_views("4.2B views") == 4_200_000_000

    def test_surrounding_spaces(self):
        assert parse_ytm_views("  12K  ") == 12_000

    def test_german_milliarden(self):
        assert parse_ytm_views("2 Mrd.") == 2_000_000_000
        assert parse_ytm_views("1.7 Mrd.") == 1_700_000_000

    def test_english_full_words(self):
        assert parse_ytm_views("5 million views") == 5_000_000
        assert parse_ytm_views("12 thousand") == 12_000

    def test_plain_ints(self):
        assert parse_ytm_views(12345) == 12345
        assert parse_ytm_views(100) == 100
        assert parse_ytm_views("0") == 0
        assert parse_ytm_views("0 views") == 0

    def test_missing_falls_back_to_zero(self):
        assert parse_ytm_views(None) == 0
        assert parse_ytm_views("") == 0
        assert parse_ytm_views("  ") == 0
        assert parse_ytm_views("abc") == 0

    def test_nonstandard_numbers_fall_back_to_zero(self):
        assert parse_ytm_views(True) == 0
        assert parse_ytm_views(-5) == 0
        assert parse_ytm_views(float("nan")) == 0
        assert parse_ytm_views(float("inf")) == 0

    def test_float_truncates(self):
        assert parse_ytm_views(12.7) == 12


# ---------------------------------------------------------------------------
# Live: pooled views coverage on both platforms (needs network).
# ---------------------------------------------------------------------------


class _FakeSong:
    """Minimal Song stand-in for ghostify_dl.search_yt_candidates."""

    def __init__(self, name="Blinding Lights", artists=("The Weeknd",)):
        self.name = name
        self.artists = list(artists)
        self.song_id = "spotify:track:viewscoverage"


@pytest.mark.integration
@pytest.mark.slow
class TestViewsCoverageLive:
    def test_ytm_views_coverage(self):
        """YTM songs+videos pools: >=95% carry int views >= 0."""
        _require_online()
        from spotdl.providers.audio.ytmusic import YouTubeMusic

        queries = list(QUERIES_FAST)
        cap = _query_cap()
        if cap:
            queries = queries[:cap]

        pooled = []
        errors = []
        provider = YouTubeMusic()
        for query in queries:
            for filt, limit in (("songs", YTM_LIMIT_SONGS), ("videos", YTM_LIMIT_VIDEOS)):
                try:
                    results = provider.get_results(
                        query, filter=filt, ignore_spelling=True, limit=limit
                    )
                except Exception as exc:  # noqa: BLE001 - collect, assert below
                    errors.append(f"{query}/{filt}: {type(exc).__name__}: {exc}")
                    continue
                pooled.extend(results or ())
            print(f"[YTM] {query}: pooled={len(pooled)}", flush=True)

        if not pooled:
            if errors:
                pytest.skip(f"all YTM calls failed ({len(errors)}); likely no egress")
            pytest.fail("YTM returned zero results for all queries")

        total, ok, bad = _check_pool(pooled, "YTM")
        _report_pool("YTM", total, ok, bad)
        if errors:
            print(f"[YTM] {len(errors)} query errors (tolerated):", flush=True)
            for err in errors[:5]:
                print(f"  {err}", flush=True)

        minimum = MIN_YTM_FULL if _is_full_mode() else MIN_YTM_FAST
        full_n = len(QUERIES_FAST)  # YTM always uses the fast query set
        minimum = _scaled_minimum(minimum, len(queries), full_n)
        assert total >= minimum, f"YTM pool too small: {total} < {minimum}"
        rate = ok / total
        assert rate >= COVERAGE_THRESHOLD, (
            f"YTM views coverage {rate * 100:.1f}% below "
            f"{COVERAGE_THRESHOLD * 100:.0f}% ({ok}/{total})"
        )

    def test_yt_views_coverage(self):
        """YT ytsearch pools: >=95% carry int views >= 0."""
        _require_online()
        from spotdl.providers.audio.youtube import YouTube

        queries = list(QUERIES_FULL_YT if _is_full_mode() else QUERIES_FAST)
        cap = _query_cap()
        if cap:
            queries = queries[:cap]

        pooled = []
        errors = []
        provider = YouTube()
        for query in queries:
            try:
                results = provider.get_results(query)
            except Exception as exc:  # noqa: BLE001 - collect, assert below
                errors.append(f"{query}: {type(exc).__name__}: {exc}")
                continue
            pooled.extend(results or ())
            print(f"[YT] {query}: pooled={len(pooled)}", flush=True)

        if not pooled:
            if errors:
                pytest.skip(f"all YT calls failed ({len(errors)}); likely no egress")
            pytest.fail("YT returned zero results for all queries")

        total, ok, bad = _check_pool(pooled, "YT")
        _report_pool("YT", total, ok, bad)
        if errors:
            print(f"[YT] {len(errors)} query errors (tolerated):", flush=True)
            for err in errors[:5]:
                print(f"  {err}", flush=True)

        minimum = MIN_YT_FULL if _is_full_mode() else MIN_YT_FAST
        full_n = len(QUERIES_FULL_YT if _is_full_mode() else QUERIES_FAST)
        minimum = _scaled_minimum(minimum, len(queries), full_n)
        assert total >= minimum, f"YT pool too small: {total} < {minimum}"
        rate = ok / total
        assert rate >= COVERAGE_THRESHOLD, (
            f"YT views coverage {rate * 100:.1f}% below "
            f"{COVERAGE_THRESHOLD * 100:.0f}% ({ok}/{total})"
        )

    def test_pooled_search_carries_views(self):
        """Production pooling path carries int views on its candidates."""
        _require_online()
        report = ghostify_dl.search_yt_candidates(
            _FakeSong(), limit=10, per_track_timeout=60.0
        )
        results = report.get("results") or []
        if not results:
            pytest.skip(f"pooled search returned nothing (error={report.get('error')})")
        total, ok, bad = _check_pool(results, "pooled")
        print(f"[pooled] provider={report.get('provider')}", flush=True)
        _report_pool("pooled", total, ok, bad)
        assert total > 0
        rate = ok / total
        assert rate >= COVERAGE_THRESHOLD, (
            f"pooled views coverage {rate * 100:.1f}% below "
            f"{COVERAGE_THRESHOLD * 100:.0f}% ({ok}/{total})"
        )
        assert not math.isnan(rate)

"""Offline tests for tools/jev_selection_demo.py.

No test here touches the network: candidates, the OpenRouter answer and the
Spotify/YouTube resolution are all injected or stubbed, and the demo module
imports ``ghostify_dl``/``spotdl`` lazily so the tool stays importable without
the optional dependencies.
"""

import importlib.util
import io
import json
import logging
import os
import re
import sys

import pytest

import ghostify_dl
import jev_selector

FAKE_KEY = "or-test-do-not-use-000000000000"
TRACK_ID = "0VjIjW4GlUZAMYd2vXMi3b"
TRACK_URL = "https://open.spotify.com/track/%s" % TRACK_ID
KEY_SHAPED = "sk" + "-or-v1-" + "a1b2c3d4" + "e5f6g7h8"
LEGACY_KEY_SHAPED = "or-v1-" + "9f8e7d6c" + "5b4a3f2e"
RESPONSE_SENTINEL = "provider-response-sentinel-9d8c"


def _load_demo():
    """Import the tool from the repo checkout (``tools/`` is not a package)."""
    unit_dir = os.path.dirname(os.path.abspath(__file__))
    repo_root = unit_dir
    for _ in range(6):
        repo_root = os.path.dirname(repo_root)
    path = os.path.join(repo_root, "tools", "jev_selection_demo.py")
    if not os.path.isfile(path):
        pytest.skip("tools/jev_selection_demo.py is not in this checkout")
    spec = importlib.util.spec_from_file_location("jev_selection_demo", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules["jev_selection_demo"] = module
    try:
        spec.loader.exec_module(module)
    finally:
        sys.modules.pop("jev_selection_demo", None)
    return module


demo = _load_demo()


class FakeSong:
    def __init__(self, name="Song", artists=("Artist",), album_name="Album", duration=200.0):
        self.name = name
        self.artists = list(artists)
        self.artist = artists[0] if artists else ""
        self.album_name = album_name
        self.duration = duration
        self.song_id = TRACK_ID


class FakeResult:
    def __init__(self, video_id, name="", author="Artist", duration=200.0, verified=False):
        self.result_id = video_id
        self.name = name
        self.author = author
        self.artists = (author,) if author else ()
        self.duration = duration
        self.album = "Album"
        self.verified = verified
        self.source = "youtube-music"
        self.url = "https://music.youtube.com/watch?v=%s" % video_id


def make_results(count=3):
    return [
        FakeResult(
            "%s%010d" % ("a", index),
            name="Song %d" % index,
            verified=index == 0,
        )
        for index in range(count)
    ]


def choice_response(choice, confidence=0.9):
    return {
        "answers": {
            "recording": {"type": "choice", "choice": choice, "confidence": confidence}
        }
    }


def options_response(choice, probabilities=None, confidence=0.9, **extra):
    """A choice answer that also carries per-option probabilities."""
    answer = choice_response(choice, confidence=confidence)["answers"]["recording"]
    answer["probabilities"] = dict(probabilities or {})
    answer.update(extra)
    return {"answers": {"recording": answer}}


def spread(results, chosen=0, best=0.7, rest=0.2):
    """Probabilities the selector really accepts for ``results[chosen]``.

    ``best`` clears the default ``GHOSTIFY_JEV_MIN_CONFIDENCE`` of 0.6, so the
    answer is genuinely accepted rather than only shaped to look accepted.
    """
    return {
        result.result_id: (best if index == chosen else rest)
        for index, result in enumerate(results)
    }


def stub_transport(response):
    calls = []

    def transport(url, payload, headers, timeout):
        calls.append({"url": url, "headers": headers})
        return response

    transport.calls = calls
    return transport


def make_search(results=make_results(), provider="youtube-music", error=None):
    return {
        "results": results,
        "provider": provider,
        "query": "Artist - Song",
        "error": error,
    }


def make_report(results=None, **overrides):
    """A minimal report for the pure option-table renderer."""
    results = make_results(3) if results is None else results
    report = {
        "outcome": "chose",
        "model": jev_selector.DEFAULT_MODEL,
        "jev_enabled": True,
        "shortlist_ids": [result.result_id for result in results],
        "shortlist_candidates": [
            {"video_id": r.result_id, "title": r.name, "channel": r.author}
            for r in results
        ],
        "jev_video_id": results[-1].result_id,
        "confidence": 0.9,
    }
    report.update(overrides)
    return report


def options_from(out):
    """The option-confidence block of a ``-v`` run, or ``""`` when there is none."""
    index = out.find(demo.VERBOSE_OPTIONS_HEADING)
    if index < 0:
        return ""
    closing = out.find("\n" + demo.VERBOSE_RULE, index + len(demo.VERBOSE_OPTIONS_HEADING) + 1)
    if closing < 0:
        return out[index:]
    return out[index : closing + 1 + len(demo.VERBOSE_RULE)]


ROW_PATTERN = re.compile(r"\b([A-Za-z0-9_-]{11})\b\s+(\S+)\s\s(.*)$", re.MULTILINE)


def table_rows(block):
    """The rendered table as ``(video_id, conf, label)``, in the order shown."""
    return ROW_PATTERN.findall(block)


def chosen_rows(block):
    """The rendered table rows carrying the chosen marker, in order."""
    return [
        line
        for line in block.splitlines()
        if demo.CHOSEN_MARKER in line and table_rows(line + "\n")
    ]


@pytest.fixture(autouse=True)
def _isolate_env(monkeypatch):
    for name in (
        jev_selector.ENV_API_KEY,
        jev_selector.ENV_ENABLED,
        jev_selector.ENV_MODEL,
        jev_selector.ENV_ENDPOINT,
        jev_selector.ENV_TIMEOUT,
        jev_selector.ENV_MAX_CANDIDATES,
        jev_selector.ENV_MIN_CONFIDENCE,
    ):
        monkeypatch.delenv(name, raising=False)


def collect(results=None, search=None, offline=False, transport=None, verbose=None):
    return demo.collect_report(
        ghostify_dl,
        FakeSong(),
        make_results() if results is None else results,
        make_search() if search is None else search,
        TRACK_ID,
        TRACK_URL,
        8.0,
        offline=offline,
        transport=transport,
        verbose=verbose,
    )


def body_of(block):
    """The pretty-printed JSON body of a verbose block, parsed back."""
    lines = block.splitlines()
    start = next(
        index
        for index, line in enumerate(lines)
        if line.strip().startswith("request body")
    )
    body = []
    for line in lines[start + 1:]:
        if not line.strip():
            continue
        if not line.startswith("    "):
            break
        body.append(line[4:])
    return json.loads("\n".join(body))


def run_demo(
    monkeypatch,
    capsys,
    argv,
    results=None,
    sent=None,
    extra_response=None,
    response=None,
):
    """Run ``main`` with every live call site stubbed; nothing hits a network.

    ``sent`` collects the request the selector's own ``_post_json`` received, so
    a test can compare what ``-v`` printed with what was actually sent. A
    ``response`` replaces the default answer, so per-option data can be shaped
    exactly as a test needs it.
    """
    results = make_results(3) if results is None else results
    sent = [] if sent is None else sent

    def fake_post(url, payload, headers, timeout):
        sent.append(
            {"url": url, "payload": payload, "headers": headers, "timeout": timeout}
        )
        answer = (
            choice_response(results[-1].result_id, confidence=0.9)
            if response is None
            else response
        )
        if isinstance(answer, dict):
            answer["provider_note"] = extra_response
        return answer

    monkeypatch.setattr(jev_selector, "_post_json", fake_post)
    monkeypatch.setattr(demo, "load_song", lambda *a, **k: FakeSong())
    monkeypatch.setattr(
        ghostify_dl, "search_yt_candidates", lambda *a, **k: make_search(results)
    )
    code = demo.main(list(argv))
    return code, capsys.readouterr().out


def run_verbose(monkeypatch, capsys, argv, **kwargs):
    """Stdout of a ``-v`` run: the verbose block, then the untouched report."""
    return run_demo(monkeypatch, capsys, argv, **kwargs)[1]


class TestUrlParsing:
    def test_track_url_accepted(self):
        assert demo.normalize_spotify_track_url(TRACK_URL) == (TRACK_ID, TRACK_URL)

    def test_query_string_and_surrounding_space(self):
        raw = "  %s?si=abcdef  " % TRACK_URL
        assert demo.normalize_spotify_track_url(raw) == (TRACK_ID, TRACK_URL)

    def test_spotify_uri_normalised_to_https(self):
        spotify_id, url = demo.normalize_spotify_track_url("spotify:track:%s" % TRACK_ID)
        assert spotify_id == TRACK_ID
        assert url == TRACK_URL

    def test_short_id_rejected(self):
        with pytest.raises(demo.DemoError) as excinfo:
            demo.normalize_spotify_track_url("https://open.spotify.com/track/tooshort")
        assert excinfo.value.exit_code == demo.EXIT_USAGE

    def test_playlist_url_rejected(self):
        with pytest.raises(demo.DemoError) as excinfo:
            demo.normalize_spotify_track_url("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        assert "single track" in str(excinfo.value)

    def test_non_spotify_url_rejected(self):
        with pytest.raises(demo.DemoError):
            demo.normalize_spotify_track_url("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

    def test_empty_value_rejected(self):
        for value in ("", "   ", None, 42):
            with pytest.raises(demo.DemoError):
                demo.normalize_spotify_track_url(value)

    def test_error_message_never_contains_a_key(self):
        with pytest.raises(demo.DemoError) as excinfo:
            demo.normalize_spotify_track_url("nope")
        assert FAKE_KEY not in str(excinfo.value)


class TestDurationFormatting:
    @pytest.mark.parametrize(
        "seconds,expected",
        [
            (0, "-"),
            (None, "-"),
            (-5, "-"),
            (5, "0:05"),
            (60, "1:00"),
            (200, "3:20"),
            (215.4, "3:35"),
            (3600, "60:00"),
        ],
    )
    def test_format_duration(self, seconds, expected):
        assert demo.format_duration(seconds) == expected


class TestFallbackWithoutKey:
    def test_no_key_keeps_deterministic_result(self):
        report = collect()
        assert report["outcome"] == jev_selector.OUTCOME_INACTIVE
        assert report["jev_video_id"] is None
        assert report["original_video_id"] == make_results()[0].result_id
        assert report["effective_video_id"] == report["original_video_id"]
        assert report["agree"] is True
        assert report["changed"] is False
        assert report["jev_enabled"] is False
        assert report["api_key_present"] is False

    def test_no_key_matches_production_selection(self):
        results = make_results(4)
        report = collect(results=results)
        assert ghostify_dl._first_result_video_id(results) == report["original_video_id"]

    def test_no_key_report_explains_how_to_enable(self):
        text = demo.format_report(collect())
        assert "OPENROUTER_API_KEY" in text
        assert "export OPENROUTER_API_KEY" in text
        assert "falls back to the deterministic result" in text

    def test_disabled_flag_keeps_deterministic_result(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        monkeypatch.setenv(jev_selector.ENV_ENABLED, "0")
        report = collect()
        assert report["outcome"] == jev_selector.OUTCOME_INACTIVE
        assert report["effective_video_id"] == report["original_video_id"]

    def test_offline_flag_keeps_deterministic_result(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        report = collect(offline=True)
        assert report["outcome"] == jev_selector.OUTCOME_INACTIVE
        assert report["forced_offline"] is True
        assert "forced offline" in demo.format_report(report)

    def test_offline_helper_restores_environment(self):
        assert "GHOSTIFY_JEV_ENABLED" not in os.environ
        with demo.forced_deterministic():
            assert os.environ["GHOSTIFY_JEV_ENABLED"] == "0"
        assert "GHOSTIFY_JEV_ENABLED" not in os.environ

    def test_offline_helper_restores_previous_value(self, monkeypatch):
        monkeypatch.setenv("GHOSTIFY_JEV_ENABLED", "1")
        with demo.forced_deterministic():
            assert os.environ["GHOSTIFY_JEV_ENABLED"] == "0"
        assert os.environ["GHOSTIFY_JEV_ENABLED"] == "1"

    def test_no_candidates_reports_nothing_to_compare(self):
        report = collect(results=[], search=make_search(results=[]))
        assert report["original_video_id"] is None
        assert report["candidate_count"] == 0
        assert report["outcome"] == "no_candidates"
        text = demo.format_report(report)
        assert "no original candidate" in text
        assert "nothing to compare" in text


class TestCompareWithMockedCandidates:
    def test_jev_choice_overrides_original(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(4)
        target = results[3].result_id
        transport = stub_transport(choice_response(target, confidence=0.93))
        report = collect(results=results, search=make_search(results), transport=transport)
        assert report["outcome"] == jev_selector.OUTCOME_CHOSE
        assert report["jev_video_id"] == target
        assert report["changed"] is True
        assert report["agree"] is False
        assert report["effective_video_id"] == target
        assert report["confidence"] == pytest.approx(0.93)
        assert len(transport.calls) == 1

    def test_agreement_keeps_top_result(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        transport = stub_transport(choice_response(results[0].result_id, confidence=0.8))
        report = collect(results=results, search=make_search(results), transport=transport)
        assert report["jev_video_id"] == results[0].result_id
        assert report["agree"] is True
        assert report["changed"] is False
        assert report["effective_video_id"] == results[0].result_id
        assert "top result" in report["reason"]

    def test_hallucinated_id_falls_back(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        report = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(choice_response("zzzzzzzzzzz", 1.0)),
        )
        assert report["outcome"] == jev_selector.OUTCOME_REJECTED
        assert report["jev_video_id"] is None
        assert report["effective_video_id"] == results[0].result_id

    def test_low_confidence_falls_back(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        report = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(choice_response(results[2].result_id, 0.1)),
        )
        assert report["outcome"] == jev_selector.OUTCOME_REJECTED
        assert report["effective_video_id"] == results[0].result_id

    def test_transport_failure_falls_back(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)

        def boom(url, payload, headers, timeout):
            raise TimeoutError("read timed out")

        report = collect(results=results, search=make_search(results), transport=boom)
        assert report["outcome"] == jev_selector.OUTCOME_REQUEST_FAILED
        assert "TimeoutError" in report["reason"]
        assert report["effective_video_id"] == results[0].result_id

    def test_unambiguous_shortlist_is_not_queried(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(1)
        transport = stub_transport(choice_response(results[0].result_id))
        report = collect(results=results, search=make_search(results), transport=transport)
        assert report["outcome"] == jev_selector.OUTCOME_UNAMBIGUOUS
        assert transport.calls == []
        assert report["agree"] is True

    def test_shortlist_caps_candidates(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        monkeypatch.setenv(jev_selector.ENV_MAX_CANDIDATES, "2")
        results = make_results(6)
        report = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(choice_response(results[0].result_id)),
        )
        assert report["shortlist_count"] == 2
        assert report["candidate_count"] == 6
        assert report["shortlist_ids"] == [results[0].result_id, results[1].result_id]

    def test_effective_matches_production_selection(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(4)
        target = results[2].result_id
        report = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(choice_response(target, confidence=0.9)),
        )
        config = jev_selector.JevConfig.from_env(timeout_cap=8.0)
        shortlist = jev_selector.build_shortlist(results, FakeSong(), config)
        production = jev_selector.select_video_id(
            FakeSong(),
            shortlist,
            config,
            stub_transport(choice_response(target, confidence=0.9)),
        )
        assert production == target
        assert report["effective_video_id"] == production
        assert report["original_video_id"] == ghostify_dl._first_result_video_id(results)

    def test_report_never_contains_the_key(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        report = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(choice_response(results[1].result_id)),
        )
        assert FAKE_KEY not in demo.format_report(report)
        assert FAKE_KEY not in repr(report)


class TestReportFormatting:
    def test_contains_both_ids_and_agreement(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        report = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(choice_response(results[2].result_id, 0.77)),
        )
        text = demo.format_report(report)
        assert results[0].result_id in text
        assert results[2].result_id in text
        assert "0.77" in text
        assert "NO - JEV overrides the original pick" in text
        assert "typesafe/jev-1.13" in text
        assert report["effective_video_id"] in text.split("effective")[1]

    def test_sections_present(self):
        text = demo.format_report(collect())
        for heading in ("Song", "Candidates", "Decision", "original (deterministic)", "JEV / OpenRouter"):
            assert heading in text

    def test_long_values_do_not_break_rows(self):
        report = collect()
        report["title"] = "x" * 300
        report["artists"] = "y" * 300
        text = demo.format_report(report)
        for line in text.splitlines():
            assert len(line) <= 120

    def test_empty_report_does_not_raise(self):
        assert demo.format_report({})

    def test_confidence_formats_only_numbers(self):
        assert demo._confidence_cell(None) == "n/a"
        assert demo._confidence_cell("0.9") == "n/a"
        assert demo._confidence_cell(True) == "n/a"
        assert demo._confidence_cell(0.5) == "0.50"

    def test_duration_cell_used_in_report(self):
        assert "3:20" in demo.format_report(collect())


class TestParser:
    def test_defaults(self):
        args = demo.build_parser().parse_args([TRACK_URL])
        assert args.spotify_url == TRACK_URL
        assert args.limit == demo.DEFAULT_LIMIT
        assert args.timeout == demo.DEFAULT_TIMEOUT
        assert args.offline is False
        assert args.download is False
        assert args.output_dir == "."

    def test_offline_aliases(self):
        assert demo.build_parser().parse_args([TRACK_URL, "--offline"]).offline is True
        assert demo.build_parser().parse_args([TRACK_URL, "--no-jev"]).offline is True

    def test_download_flags(self):
        args = demo.build_parser().parse_args(
            [TRACK_URL, "--download", "--output-dir", "/tmp/out", "--limit", "3", "--timeout", "2.5"]
        )
        assert args.download is True
        assert args.output_dir == "/tmp/out"
        assert args.limit == 3
        assert args.timeout == pytest.approx(2.5)

    def test_missing_url_is_a_usage_error(self):
        with pytest.raises(SystemExit):
            demo.build_parser().parse_args([])


class TestMissingDependencies:
    def test_ghostify_dl_import_failure_is_reported(self, monkeypatch):
        monkeypatch.setitem(sys.modules, "ghostify_dl", None)
        with pytest.raises(demo.DemoError) as excinfo:
            demo.import_ghostify_dl()
        assert excinfo.value.exit_code == demo.EXIT_DEPENDENCY
        assert "PYTHONPATH" in str(excinfo.value)

    def test_spotdl_import_failure_is_reported(self, monkeypatch):
        monkeypatch.setitem(sys.modules, "spotdl.types.song", None)
        with pytest.raises(demo.DemoError) as excinfo:
            demo.load_song(ghostify_dl, TRACK_URL)
        assert excinfo.value.exit_code == demo.EXIT_DEPENDENCY
        assert "python-spotdl" in str(excinfo.value)

    def test_metadata_failure_names_the_cause(self, monkeypatch):
        def boom():
            raise ConnectionError("no route to host")

        monkeypatch.setattr(ghostify_dl, "_ensure_spotify_client", boom)
        with pytest.raises(demo.DemoError) as excinfo:
            demo.load_song(ghostify_dl, TRACK_URL)
        assert excinfo.value.exit_code == demo.EXIT_METADATA
        assert "ConnectionError" in str(excinfo.value)

    def test_help_does_not_need_optional_dependencies(self, monkeypatch, capsys):
        monkeypatch.setitem(sys.modules, "spotdl", None)
        monkeypatch.setitem(sys.modules, "spotdl.types.song", None)
        with pytest.raises(SystemExit) as excinfo:
            demo.build_parser().parse_args(["--help"])
        assert excinfo.value.code == 0
        assert "spotify_url" in capsys.readouterr().out


class TestMainGuards:
    def test_invalid_url_exits_with_usage_code(self, capsys):
        assert demo.main(["https://example.com/nope"]) == demo.EXIT_USAGE
        assert "error:" in capsys.readouterr().err

    def test_provider_failure_exit_code(self, monkeypatch, capsys):
        monkeypatch.setattr(demo, "load_song", lambda *a, **k: FakeSong())
        monkeypatch.setattr(
            ghostify_dl,
            "search_yt_candidates",
            lambda *a, **k: {"results": [], "provider": None, "query": "q", "error": "ConnectionError"},
        )
        assert demo.main([TRACK_URL]) == demo.EXIT_PROVIDER
        assert "ConnectionError" in capsys.readouterr().err

    def test_no_candidates_exit_code(self, monkeypatch, capsys):
        monkeypatch.setattr(demo, "load_song", lambda *a, **k: FakeSong())
        monkeypatch.setattr(
            ghostify_dl,
            "search_yt_candidates",
            lambda *a, **k: {"results": [], "provider": None, "query": "q", "error": None},
        )
        assert demo.main([TRACK_URL]) == demo.EXIT_NO_CANDIDATES
        assert "no candidates" in capsys.readouterr().err

    def test_metadata_failure_exit_code(self, monkeypatch, capsys):
        def boom(*args, **kwargs):
            raise demo.DemoError("Could not resolve Spotify metadata", demo.EXIT_METADATA)

        monkeypatch.setattr(demo, "load_song", boom)
        assert demo.main([TRACK_URL]) == demo.EXIT_METADATA
        assert "metadata" in capsys.readouterr().err

    def test_compare_only_run_prints_report(self, monkeypatch, capsys):
        results = make_results(3)
        monkeypatch.setattr(demo, "load_song", lambda *a, **k: FakeSong())
        monkeypatch.setattr(
            ghostify_dl,
            "search_yt_candidates",
            lambda *a, **k: make_search(results),
        )
        assert demo.main([TRACK_URL]) == demo.EXIT_OK
        out = capsys.readouterr().out
        assert "original (deterministic)" in out
        assert results[0].result_id in out
        assert "Downloading" not in out

    def test_offline_run_makes_no_jev_request(self, monkeypatch, capsys):
        results = make_results(3)
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        monkeypatch.setattr(demo, "load_song", lambda *a, **k: FakeSong())
        monkeypatch.setattr(
            ghostify_dl,
            "search_yt_candidates",
            lambda *a, **k: make_search(results),
        )

        def explode(*args, **kwargs):
            raise AssertionError("no OpenRouter call may happen with --offline")

        monkeypatch.setattr(jev_selector, "_post_json", explode)
        assert demo.main([TRACK_URL, "--offline"]) == demo.EXIT_OK
        out = capsys.readouterr().out
        assert "forced offline" in out
        assert FAKE_KEY not in out
        assert os.environ.get("GHOSTIFY_JEV_ENABLED") is None

    def test_download_flag_is_opt_in(self, monkeypatch, capsys):
        results = make_results(3)
        monkeypatch.setattr(demo, "load_song", lambda *a, **k: FakeSong())
        monkeypatch.setattr(
            ghostify_dl,
            "search_yt_candidates",
            lambda *a, **k: make_search(results),
        )
        calls = {}

        class FakeDownloader:
            def download(self, url, **kwargs):
                calls["url"] = url
                calls["yt_id"] = kwargs.get("yt_id")
                return {"status": "COMPLETED", "output_path": "/tmp/x.mp3"}

        monkeypatch.setattr(
            ghostify_dl, "make_downloader", lambda out: FakeDownloader()
        )
        assert demo.main([TRACK_URL, "--download"]) == demo.EXIT_OK
        assert calls["yt_id"] == results[0].result_id
        assert calls["url"] == TRACK_URL
        assert "Downloading" in capsys.readouterr().out


class TestActionableStatus:
    """The status explains what happened, without ever printing a secret."""

    def _report(self, monkeypatch, transport):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        return collect(results=results, search=make_search(results), transport=transport)

    def test_rejection_reason_is_actionable(self, monkeypatch):
        results = make_results(3)
        report = self._report(
            monkeypatch, stub_transport(choice_response(results[2].result_id, 0.1))
        )
        assert report["outcome"] == jev_selector.OUTCOME_REJECTED
        text = demo.format_report(report)
        assert "enabled (OPENROUTER_API_KEY set)" in text
        assert jev_selector.REJECTION_LOW_CONFIDENCE in text
        assert "confidence=0.10 < 0.60" in text
        assert report["effective_url"] in text
        assert FAKE_KEY not in text

    def test_hallucinated_id_is_actionable(self, monkeypatch):
        report = self._report(monkeypatch, stub_transport(choice_response("zzzzzzzzzzz", 1.0)))
        text = demo.format_report(report)
        assert jev_selector.REJECTION_UNKNOWN_ID in text
        assert "shortlist=3" in text
        assert report["effective_url"] in text

    def test_wrong_answer_type_is_actionable(self, monkeypatch):
        def transport(url, payload, headers, timeout):
            return {
                "answers": {
                    "recording": {"type": "noul", "choice": "a0000000000"}
                }
            }

        report = self._report(monkeypatch, transport)
        text = demo.format_report(report)
        assert jev_selector.REJECTION_ANSWER_TYPE in text
        assert "type=noul" in text

    def test_http_status_is_reported_sanitised(self, monkeypatch):
        def transport(url, payload, headers, timeout):
            raise jev_selector.JevTransportError(
                "Authorization: Bearer %s" % FAKE_KEY,
                status=429,
                code="rate_limit_error",
            )

        report = self._report(monkeypatch, transport)
        assert report["outcome"] == jev_selector.OUTCOME_REQUEST_FAILED
        text = demo.format_report(report)
        assert "HTTP 429" in text
        assert "rate_limit_error" in text
        assert "Authorization" not in text
        assert FAKE_KEY not in text
        assert report["effective_url"] in text

    def test_disabled_flag_is_not_reported_as_enabled(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        monkeypatch.setenv(jev_selector.ENV_ENABLED, "0")
        text = demo.format_report(collect())
        assert "configured but disabled (GHOSTIFY_JEV_ENABLED=0)" in text
        assert "enabled (OPENROUTER_API_KEY set)" not in text
        assert FAKE_KEY not in text

    def test_accepted_answer_status(self, monkeypatch):
        results = make_results(3)
        report = self._report(
            monkeypatch, stub_transport(choice_response(results[2].result_id, 0.93))
        )
        text = demo.format_report(report)
        assert report["outcome"] == jev_selector.OUTCOME_CHOSE
        assert "enabled (OPENROUTER_API_KEY set) - JEV picked an alternate" in text
        assert report["effective_url"] in text

    def test_status_line_stays_bounded(self, monkeypatch):
        report = self._report(
            monkeypatch,
            stub_transport(choice_response("a0000000002", 0.1)),
        )
        for line in demo.format_report(report).splitlines():
            assert len(line) <= 120

    def test_no_key_status_unchanged(self):
        text = demo.format_report(collect())
        assert "inactive - OPENROUTER_API_KEY is not set" in text
        assert "export OPENROUTER_API_KEY" in text


class FakeStream:
    def __init__(self, tty=True):
        self.tty = tty

    def isatty(self):
        return self.tty


class TestColorDetection:
    """Colour follows the terminal, ``NO_COLOR`` and ``--no-color``."""

    @pytest.fixture(autouse=True)
    def _terminal_env(self, monkeypatch):
        monkeypatch.delenv("NO_COLOR", raising=False)
        monkeypatch.delenv("TERM", raising=False)

    def test_tty_gets_color(self):
        assert demo.use_color(stream=FakeStream()) is True

    def test_redirected_output_stays_plain(self):
        assert demo.use_color(stream=FakeStream(tty=False)) is False

    def test_no_color_environment_wins(self, monkeypatch):
        monkeypatch.setenv("NO_COLOR", "1")
        assert demo.use_color(stream=FakeStream()) is False

    def test_empty_no_color_keeps_colour(self, monkeypatch):
        monkeypatch.setenv("NO_COLOR", "")
        assert demo.use_color(stream=FakeStream()) is True

    def test_dumb_terminal_stays_plain(self, monkeypatch):
        monkeypatch.setenv("TERM", "dumb")
        assert demo.use_color(stream=FakeStream()) is False

    def test_flag_wins_over_tty(self):
        assert demo.use_color(True, stream=FakeStream()) is False

    def test_broken_stream_stays_plain(self):
        assert demo.use_color(stream=object()) is False

    def test_defaults_to_stdout(self, capsys):
        assert demo.use_color() is False

    def test_flag_is_parsed(self):
        assert demo.build_parser().parse_args([TRACK_URL]).no_color is False
        assert demo.build_parser().parse_args([TRACK_URL, "--no-color"]).no_color is True


class TestColorOutput:
    """Colour is presentation only: strip it and the report is unchanged."""

    ESC = "\033["

    def _reports(self, monkeypatch):
        results = make_results(3)
        without_key = collect()
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        accepted = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(choice_response(results[2].result_id, 0.91)),
        )
        rejected = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(choice_response(results[2].result_id, 0.1)),
        )

        def failing(url, payload, headers, timeout):
            raise jev_selector.JevTransportError("", status=401, code="auth_error")

        failed = collect(
            results=results, search=make_search(results), transport=failing
        )
        return {
            "no key": without_key,
            "accepted": accepted,
            "rejected": rejected,
            "failed": failed,
            "empty": {},
        }

    def test_plain_by_default(self, monkeypatch):
        for report in self._reports(monkeypatch).values():
            assert self.ESC not in demo.format_report(report)

    def test_color_opt_in_adds_escapes(self, monkeypatch):
        for report in self._reports(monkeypatch).values():
            assert self.ESC in demo.format_report(report, color=True)

    def test_stripping_escapes_gives_the_plain_report(self, monkeypatch):
        for name, report in self._reports(monkeypatch).items():
            colored = demo.format_report(report, color=True)
            assert demo.strip_ansi(colored) == demo.format_report(report), name

    def test_headings_labels_columns_and_urls_are_styled(self, monkeypatch):
        report = self._reports(monkeypatch)["accepted"]
        text = demo.format_report(report, color=True)
        assert demo.STYLES["heading"] + "Song" + demo.RESET in text
        assert demo.STYLES["heading"] + "Decision" + demo.RESET in text
        assert demo.STYLES["label"] + "title         " + demo.RESET in text
        assert demo.STYLES["original"] in text
        assert demo.STYLES["jev"] + "JEV / OpenRouter" + demo.RESET in text
        assert (
            demo.STYLES["url"] + report["effective_url"] + demo.RESET in text
        ), "the effective url is wrapped, never split"

    @pytest.mark.parametrize(
        "name,style",
        [("accepted", "success"), ("rejected", "fallback"), ("failed", "error")],
    )
    def test_status_colour_follows_the_outcome(self, monkeypatch, name, style):
        report = self._reports(monkeypatch)[name]
        text = demo.format_report(report, color=True)
        status = [line for line in text.splitlines() if "JEV status" in line][0]
        assert demo.STYLES[style] in status
        assert demo.strip_ansi(status).split(": ", 1)[1]

    def test_outcome_style_mapping(self):
        assert demo.outcome_style({"outcome": "chose", "jev_video_id": "x"}) == "success"
        assert demo.outcome_style({"outcome": "chose", "jev_video_id": None}) == "fallback"
        assert demo.outcome_style({"outcome": "rejected"}) == "fallback"
        assert demo.outcome_style({"outcome": "inactive"}) == "fallback"
        assert demo.outcome_style({"outcome": "unambiguous"}) == "fallback"
        assert demo.outcome_style({"outcome": "request_failed"}) == "error"
        assert demo.outcome_style({"outcome": "selector_failed"}) == "error"
        assert demo.outcome_style({"outcome": "selector_unavailable"}) == "error"
        assert demo.outcome_style({"outcome": "chose", "selector_error": "X"}) == "error"
        assert demo.outcome_style({}) == "fallback"

    def test_escapes_never_split_a_url_or_a_value(self, monkeypatch):
        url_keys = ("spotify_url", "original_url", "jev_url", "effective_url")
        value_keys = url_keys + (
            "original_video_id",
            "jev_video_id",
            "effective_video_id",
            "provider",
            "model",
            "outcome",
        )
        for report in self._reports(monkeypatch).values():
            text = demo.format_report(report, color=True)
            for key in value_keys:
                value = report.get(key)
                if not value or value == "-":
                    continue
                if key == "model" and not report.get("jev_enabled"):
                    continue
                self._assert_contiguous(text, value, key)
                if key in url_keys:
                    self._assert_line_final(text, value, key)

    def _assert_contiguous(self, text, value, key):
        assert value in text, (key, value)

    def _assert_line_final(self, text, value, key):
        tail = text.split(value, 1)[1].split("\n", 1)[0]
        assert demo.strip_ansi(tail).strip() == "", "%s: %r" % (key, tail)

    def test_shortlist_ids_are_contiguous(self, monkeypatch):
        report = self._reports(monkeypatch)["accepted"]
        text = demo.format_report(report, color=True)
        self._assert_contiguous(text, ", ".join(report["shortlist_ids"]), "shortlist ids")

    def test_palette_is_inert_when_disabled(self):
        palette = demo.Palette(False)
        for style in demo.STYLES:
            assert palette.paint(style, "text") == "text"
        assert palette.paint("heading", None) == ""
        assert palette.paint("unknown-style", "text") == "text"

    def test_plain_palette_constant_is_disabled(self):
        assert demo.PLAIN.enabled is False
        assert demo.PLAIN.paint("error", "boom") == "boom"

    def test_strip_ansi_removes_every_style(self):
        text = "".join(
            "%svalue%s" % (code, demo.RESET) for code in demo.STYLES.values()
        )
        assert demo.strip_ansi(text) == "value" * len(demo.STYLES)


class TtyProxy:
    """A stdout that claims to be a terminal and forwards writes to *wrapped*."""

    def __init__(self, wrapped):
        self.wrapped = wrapped

    def isatty(self):
        return True

    def write(self, data):
        return self.wrapped.write(data)

    def flush(self):
        return self.wrapped.flush()


class TestMainColorOutput:
    """``main`` honours the terminal, ``NO_COLOR`` and ``--no-color``."""

    def _run(self, monkeypatch, results, argv):
        monkeypatch.setattr(demo, "load_song", lambda *a, **k: FakeSong())
        monkeypatch.setattr(
            ghostify_dl, "search_yt_candidates", lambda *a, **k: make_search(results)
        )
        return demo.main(argv)

    def test_piped_output_is_plain(self, monkeypatch, capsys):
        results = make_results(3)
        assert self._run(monkeypatch, results, [TRACK_URL]) == demo.EXIT_OK
        out = capsys.readouterr().out
        assert "\033[" not in out
        assert "original (deterministic)" in out

    def test_terminal_output_is_coloured(self, monkeypatch, capsys):
        results = make_results(3)
        monkeypatch.setattr(sys, "stdout", TtyProxy(sys.stdout))
        assert self._run(monkeypatch, results, [TRACK_URL]) == demo.EXIT_OK
        assert "\033[" in capsys.readouterr().out

    def test_no_color_flag_on_a_terminal(self, monkeypatch, capsys):
        results = make_results(3)
        monkeypatch.setattr(sys, "stdout", TtyProxy(sys.stdout))
        assert self._run(monkeypatch, results, [TRACK_URL, "--no-color"]) == demo.EXIT_OK
        assert "\033[" not in capsys.readouterr().out

    def test_no_color_env_on_a_terminal(self, monkeypatch, capsys):
        results = make_results(3)
        monkeypatch.setenv("NO_COLOR", "1")
        monkeypatch.setattr(sys, "stdout", TtyProxy(sys.stdout))
        assert self._run(monkeypatch, results, [TRACK_URL]) == demo.EXIT_OK
        assert "\033[" not in capsys.readouterr().out

    def test_colored_run_strips_back_to_the_plain_report(self, monkeypatch, capsys):
        results = make_results(3)
        assert self._run(monkeypatch, results, [TRACK_URL]) == demo.EXIT_OK
        plain = capsys.readouterr().out
        monkeypatch.setattr(sys, "stdout", TtyProxy(sys.stdout))
        assert self._run(monkeypatch, results, [TRACK_URL]) == demo.EXIT_OK
        colored = capsys.readouterr().out
        assert "\033[" in colored
        assert demo.strip_ansi(colored) == plain

    def test_colored_error_keeps_its_prefix_and_exit_code(self, monkeypatch, capsys):
        monkeypatch.setattr(sys, "stdout", TtyProxy(sys.stdout))
        assert demo.main(["https://example.com/nope"]) == demo.EXIT_USAGE
        err = capsys.readouterr().err
        assert "\033[" in err
        assert demo.strip_ansi(err).startswith("error: ")

    def test_plain_error_unchanged(self, capsys):
        assert demo.main(["https://example.com/nope"]) == demo.EXIT_USAGE
        assert capsys.readouterr().err.startswith("error: ")


class TestSecretHygiene:
    def test_no_key_literal_in_source(self):
        import inspect
        import re

        source = inspect.getsource(demo)
        assert "sk-or-" not in source
        assert not re.search(r"or-v1-[A-Za-z0-9]{8,}", source)
        assert "<your key>" in source

    def test_config_key_is_not_rendered(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        config = jev_selector.JevConfig.from_env()
        assert FAKE_KEY not in repr(config)
        report = ghostify_dl.compare_yt_selection(FakeSong(), make_results(), 8.0)
        assert report["api_key_present"] is True
        assert FAKE_KEY not in repr(report)

    def test_colour_does_not_change_the_secret_hygiene(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        report = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(choice_response(results[1].result_id, 0.9)),
        )
        for text in (demo.format_report(report), demo.format_report(report, color=True)):
            assert FAKE_KEY not in text
            assert "Bearer" not in text

    def test_verbose_does_not_change_the_secret_hygiene(self, monkeypatch, capsys):
        out = run_verbose(monkeypatch, capsys, [TRACK_URL, "-v"])
        assert FAKE_KEY not in out
        assert KEY_SHAPED not in out
        assert "Bearer %s" % FAKE_KEY not in out


class BrokenStream:
    def write(self, _data):
        raise OSError("stdout is gone")

    def flush(self):
        pass


class TestVerboseRedaction:
    """Redaction is by header name *and* by value shape, and is idempotent."""

    def test_sensitive_header_names(self):
        for name in (
            "Authorization",
            "authorization",
            "X-API-Key",
            "api_key",
            "Cookie",
            "X-Auth-Token",
            "X-Client-Secret",
        ):
            assert demo.is_sensitive_header(name) is True, name

    def test_harmless_header_names(self):
        for name in ("Content-Type", "Accept", "User-Agent", "HTTP-Referer"):
            assert demo.is_sensitive_header(name) is False, name

    def test_authorization_keeps_the_scheme_and_loses_the_token(self):
        safe = demo.safe_headers(
            {
                "Authorization": "Bearer %s" % FAKE_KEY,
                "Content-Type": "application/json",
            }
        )
        assert safe["Authorization"] == "Bearer %s" % demo.REDACTED
        assert safe["Content-Type"] == "application/json"
        assert FAKE_KEY not in repr(safe)

    def test_valueless_credential_header_is_fully_replaced(self):
        safe = demo.safe_headers({"X-Api-Key": KEY_SHAPED})
        assert safe["X-Api-Key"] == demo.REDACTED

    def test_credential_shaped_values_are_redacted(self):
        text = demo.redact_secrets(
            "a=%s b=%s c=Bearer %s keep=typesafe/jev-1.13" % (
                KEY_SHAPED,
                LEGACY_KEY_SHAPED,
                FAKE_KEY,
            )
        )
        assert KEY_SHAPED not in text
        assert LEGACY_KEY_SHAPED not in text
        assert FAKE_KEY not in text
        assert "typesafe/jev-1.13" in text
        assert text.count(demo.REDACTED) == 3

    def test_bearer_scheme_survives_redaction(self):
        assert demo.redact_secrets("Bearer %s" % FAKE_KEY) == (
            "Bearer %s" % demo.REDACTED
        )

    def test_redaction_is_idempotent(self):
        once = demo.redact_secrets("Bearer %s" % FAKE_KEY)
        assert demo.redact_secrets(once) == once

    def test_plain_payload_text_survives_untouched(self):
        payload = {
            "model": "typesafe/jev-1.13",
            "state": {"requested_song": {"title": "A Song", "artists": ["Or-Nation"]}},
        }
        assert demo.redact_secrets(json.dumps(payload)) == json.dumps(payload)

    @pytest.mark.parametrize("headers", [None, {}, "not-a-mapping"])
    def test_unusable_headers_do_not_raise(self, headers):
        assert demo.safe_headers(headers) == {}


class TestVerboseRequestDump:
    """``-v`` prints the request the selector really sends, and nothing more."""

    @pytest.fixture(autouse=True)
    def _restore_root_logging(self):
        root = logging.getLogger()
        handlers, level = list(root.handlers), root.level
        yield
        root.handlers, root.level = handlers, level

    def test_dumps_method_endpoint_headers_and_body(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        out = run_verbose(monkeypatch, capsys, [TRACK_URL, "-v"])
        assert demo.VERBOSE_REQUEST_HEADING in out
        assert "POST" in out
        assert jev_selector.DEFAULT_ENDPOINT in out
        assert "Authorization" in out
        assert "application/json" in out
        assert "request body" in out
        assert jev_selector.DEFAULT_MODEL in out
        assert '"criteria"' in out

    def test_body_is_the_complete_payload(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        sent = []
        out = run_verbose(monkeypatch, capsys, [TRACK_URL, "-v"], sent=sent)
        assert len(sent) == 1
        block = out.split(demo.VERBOSE_RULE)[1]
        body = body_of(block)
        assert body == sent[0]["payload"]
        assert body["model"] == jev_selector.DEFAULT_MODEL
        for result in make_results(3):
            assert result.result_id in body["questions"]["recording"]["criteria"]

    def test_payload_is_exactly_the_selector_payload(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        sent = []
        run_verbose(monkeypatch, capsys, [TRACK_URL, "-v"], sent=sent)
        request = sent[0]
        assert request["url"] == jev_selector.DEFAULT_ENDPOINT
        assert request["headers"]["Authorization"] == "Bearer %s" % FAKE_KEY
        assert request["payload"] == jev_selector.build_payload(
            FakeSong(),
            jev_selector.build_shortlist(
                make_results(3),
                FakeSong(),
                jev_selector.JevConfig.from_env(timeout_cap=8.0),
            ).candidates,
            jev_selector.DEFAULT_MODEL,
        )

    def test_timeout_is_shown(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        sent = []
        out = run_verbose(monkeypatch, capsys, [TRACK_URL, "-v"], sent=sent)
        assert "%.1fs" % sent[0]["timeout"] in out

    def test_authorization_is_redacted(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        out = run_verbose(monkeypatch, capsys, [TRACK_URL, "-v"])
        assert "Authorization" in out
        assert "Bearer %s" % demo.REDACTED in out
        assert FAKE_KEY not in out
        assert FAKE_KEY not in json.dumps(body_of(out.split(demo.VERBOSE_RULE)[1]))

    def test_response_body_is_never_dumped(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        out = run_verbose(
            monkeypatch, capsys, [TRACK_URL, "-v"], extra_response=RESPONSE_SENTINEL
        )
        assert RESPONSE_SENTINEL not in out
        assert "answers" not in body_of(out.split(demo.VERBOSE_RULE)[1])

    def test_block_precedes_and_separates_the_report(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        out = run_verbose(monkeypatch, capsys, [TRACK_URL, "-v"])
        assert out.index(demo.VERBOSE_REQUEST_HEADING) < out.index(
            "Ghostify - YouTube selection"
        )
        head = out.split(demo.VERBOSE_RULE, 1)[1]
        assert "method" in head
        assert "request body" in head
        report = out[out.index("Ghostify - YouTube selection"):]
        assert out[: out.index(report)].endswith("\n\n")
        assert demo.VERBOSE_OPTIONS_HEADING not in out

    def test_plain_output_is_byte_identical_without_the_flag(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        code, plain = run_demo(monkeypatch, capsys, [TRACK_URL])
        verbose_code, verbose = run_demo(monkeypatch, capsys, [TRACK_URL, "-v"])
        _, long_form = run_demo(monkeypatch, capsys, [TRACK_URL, "--verbose"])
        assert code == verbose_code == demo.EXIT_OK
        assert plain.startswith("Ghostify - YouTube selection")
        assert verbose.endswith(plain)
        assert plain in verbose
        assert long_form == verbose

    def test_verbose_does_not_change_the_decision(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(4)
        target = results[3].result_id
        response = choice_response(target, confidence=0.88)
        quiet = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(response),
        )
        log = demo.VerboseJev(stream=io.StringIO())
        loud = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(response),
            verbose=log,
        )
        assert quiet == loud
        assert loud["outcome"] == jev_selector.OUTCOME_CHOSE
        assert loud["effective_video_id"] == target
        assert len(log.blocks) == 1

    def test_verbose_still_uses_the_selector_sender(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        target = results[2].result_id
        report = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(choice_response(target, 0.9)),
            verbose=demo.VerboseJev(stream=io.StringIO()),
        )
        assert report["jev_video_id"] == target

    def test_broken_output_stream_cannot_change_the_decision(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        report = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(choice_response(results[2].result_id, 0.9)),
            verbose=demo.VerboseJev(stream=BrokenStream()),
        )
        assert report["outcome"] == jev_selector.OUTCOME_CHOSE
        assert report["effective_video_id"] == results[2].result_id

    def test_short_and_long_flag_agree(self):
        assert demo.build_parser().parse_args([TRACK_URL, "-v"]).verbose is True
        assert demo.build_parser().parse_args([TRACK_URL, "--verbose"]).verbose is True
        assert demo.build_parser().parse_args([TRACK_URL]).verbose is False

    def test_exit_codes_are_unchanged(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        assert demo.main(["https://example.com/nope", "-v"]) == demo.EXIT_USAGE
        assert run_demo(monkeypatch, capsys, [TRACK_URL, "-v"])[0] == demo.EXIT_OK
        monkeypatch.setattr(
            ghostify_dl,
            "search_yt_candidates",
            lambda *a, **k: make_search([], error="ConnectionError"),
        )
        monkeypatch.setattr(demo, "load_song", lambda *a, **k: FakeSong())
        assert demo.main([TRACK_URL, "-v"]) == demo.EXIT_PROVIDER

    def test_colour_strips_back_to_the_plain_block(self):
        headers = {"Authorization": "Bearer x"}
        plain = demo.format_verbose_request(
            "POST", jev_selector.DEFAULT_ENDPOINT, headers, {"a": 1}, 4.0
        )
        coloured = demo.format_verbose_request(
            "POST",
            jev_selector.DEFAULT_ENDPOINT,
            headers,
            {"a": 1},
            4.0,
            demo.Palette(True),
        )
        assert "\033[" in coloured
        assert demo.strip_ansi(coloured) == plain

    def test_unserialisable_payload_is_still_shown(self):
        block = demo.format_verbose_request(
            "POST", "https://example.com", {}, {"when": object()}
        )
        assert "request body" in block
        assert "object" in block

    def test_endpoint_and_body_survive_an_odd_endpoint(self, monkeypatch, capsys):
        endpoint = "https://example.test/api/alpha/decisions?trace=1&%s" % ("x" * 120)
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        monkeypatch.setenv(jev_selector.ENV_ENDPOINT, endpoint)
        out = run_verbose(monkeypatch, capsys, [TRACK_URL, "-v"])
        assert endpoint in out
        # The Decision table uses "…" for truncated URL/reason previews (full
        # values stay copy-pasteable below), so scope the no-truncation check
        # to the verbose request block before the report.
        verbose_part = out.split("Ghostify - YouTube selection")[0]
        assert "…" not in verbose_part


class TestVerboseWithoutARequest:
    """With nothing to send, ``-v`` says so instead of staying silent."""

    def _skipped(self, out):
        assert demo.VERBOSE_SKIPPED_HEADING in out
        assert demo.VERBOSE_REQUEST_HEADING not in out
        assert demo.VERBOSE_OPTIONS_HEADING not in out
        assert "none - the provider was never called" in out
        assert "POST (not sent)" in out
        assert jev_selector.DEFAULT_ENDPOINT in out

    def test_no_key_shows_the_inactive_state(self, monkeypatch, capsys):
        sent = []
        out = run_verbose(monkeypatch, capsys, [TRACK_URL, "-v"], sent=sent)
        self._skipped(out)
        assert "OPENROUTER_API_KEY is not set" in out
        assert sent == []

    def test_disabled_flag_shows_the_disabled_state(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        monkeypatch.setenv(jev_selector.ENV_ENABLED, "0")
        sent = []
        out = run_verbose(monkeypatch, capsys, [TRACK_URL, "-v"], sent=sent)
        self._skipped(out)
        assert "configured but disabled (GHOSTIFY_JEV_ENABLED=0)" in out
        assert sent == []
        assert FAKE_KEY not in out

    def test_offline_shows_the_forced_state(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        sent = []
        out = run_verbose(monkeypatch, capsys, [TRACK_URL, "-v", "--offline"], sent=sent)
        self._skipped(out)
        assert "--offline forced the deterministic pick" in out
        assert sent == []

    def test_unambiguous_shortlist_explains_itself(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        sent = []
        out = run_verbose(
            monkeypatch, capsys, [TRACK_URL, "-v"], results=make_results(1), sent=sent
        )
        self._skipped(out)
        assert "only one candidate shortlisted" in out
        assert sent == []

    def test_no_candidates_explains_itself(self):
        out = demo.format_verbose_skipped(
            {"outcome": "no_candidates", "endpoint": None, "model": None}
        )
        assert "no candidate carried a video id" in out
        assert jev_selector.DEFAULT_ENDPOINT not in out

    def test_unknown_outcome_still_renders(self):
        report = {"outcome": "weird", "api_key_present": True, "jev_enabled": True}
        assert "outcome weird" in demo.format_verbose_skipped(report)

    def test_no_key_report_and_exit_are_unchanged(self, monkeypatch, capsys):
        code, out = run_demo(monkeypatch, capsys, [TRACK_URL, "-v"])
        assert code == demo.EXIT_OK
        assert "JEV decision falls back to the deterministic result" in out

    def test_busy_and_selector_failures_are_named(self):
        for outcome, phrase in (
            ("busy", "concurrency gate"),
            ("selector_unavailable", "unavailable"),
            ("selector_failed", "failed"),
            ("inactive", "inactive"),
        ):
            report = {
                "outcome": outcome,
                "api_key_present": True,
                "jev_enabled": True,
            }
            assert phrase in demo.no_request_state(report), outcome


class TestOptionValueHelpers:
    """The number handling mirrors ``jev_selector._to_float``, on purpose."""

    def test_reads_the_confidence_the_selector_uses(self):
        assert jev_selector._to_float("0.62") == pytest.approx(0.62)
        assert demo._probability("0.62") == pytest.approx(0.62)

    @pytest.mark.parametrize(
        "value,expected",
        [
            (0.0, 0.0),
            (1, 1.0),
            ("0.5", 0.5),
            ("  0.25  ", 0.25),
            (0.62, 0.62),
            (None, None),
            ("", None),
            ("high", None),
            ([0.5], None),
            ({"p": 0.5}, None),
            (True, None),
            (False, None),
            (float("nan"), None),
        ],
    )
    def test_probability_rejects_what_the_selector_would(self, value, expected):
        assert demo._probability(value) == expected
        if expected is None:
            assert jev_selector._to_float(value) is None

    def test_percent_renders_one_decimal(self):
        assert demo._percent(0.626) == "62.6%"
        assert demo._percent(0) == "0.0%"
        assert demo._percent(1) == "100.0%"

    def test_missing_percentage_reads_na(self):
        assert demo._percent(None) == demo.NOT_AVAILABLE == "n/a"

    def test_question_id_comes_from_the_live_selector(self):
        assert demo.selector_question_id() == jev_selector.QUESTION_ID

    def test_question_id_falls_back_when_the_selector_is_absent(self, monkeypatch):
        import builtins

        real_import = builtins.__import__

        def blocked(name, *args, **kwargs):
            if name == "jev_selector":
                raise ImportError("no selector here")
            return real_import(name, *args, **kwargs)

        monkeypatch.setattr(builtins, "__import__", blocked)
        assert demo.selector_question_id() == demo.DEFAULT_QUESTION_ID


class TestOptionConfidenceExtraction:
    """Only the per-option numbers leave the response."""

    def test_reads_probabilities_choice_and_confidence(self):
        results = make_results(3)
        probabilities = {r.result_id: 0.5 for r in results}
        data = demo.option_confidences(
            options_response(results[1].result_id, probabilities, confidence=0.71)
        )
        assert data == {
            "probabilities": probabilities,
            "choice": results[1].result_id,
            "confidence": pytest.approx(0.71),
        }

    def test_keeps_malformed_values_instead_of_hiding_them(self):
        data = demo.option_confidences(
            options_response("a0000000000", {"a0000000000": "high", "a0000000001": 0.2})
        )
        assert data["probabilities"]["a0000000000"] == "high"

    def test_malformed_confidence_reads_as_missing(self):
        data = demo.option_confidences(
            options_response("a0000000000", {"a0000000000": 0.5}, confidence="high")
        )
        assert data["confidence"] is None

    def test_blank_choice_reads_as_missing(self):
        data = demo.option_confidences(
            options_response("   ", {"a0000000000": 0.5})
        )
        assert data["choice"] is None

    @pytest.mark.parametrize(
        "response",
        [
            None,
            "a string",
            42,
            [],
            {},
            {"answers": None},
            {"answers": []},
            {"answers": {}},
            {"answers": {"recording": None}},
            {"answers": {"recording": "choice"}},
            {"answers": {"other": {"probabilities": {"a": 0.5}}}},
            choice_response("a0000000000"),
            {"answers": {"recording": {"probabilities": {}}}},
            {"answers": {"recording": {"probabilities": []}}},
            {"answers": {"recording": {"probabilities": "0.5"}}},
            {"answers": {"recording": {"probabilities": None}}},
        ],
    )
    def test_no_per_option_data_reads_as_nothing_to_show(self, response):
        assert demo.option_confidences(response) is None

    def test_question_id_of_another_selector_is_honoured(self):
        response = {"answers": {"other": {"probabilities": {"a0000000000": 0.5}}}}
        assert demo.option_confidences(response) is None
        assert demo.option_confidences(response, "other") is not None


class TestShortlistCandidateLookup:
    """The table's titles come from the results the shortlist was built from."""

    def test_title_and_channel_per_shortlisted_id(self):
        results = make_results(3)
        report = collect(results=results, search=make_search(results))
        assert report["shortlist_candidates"] == [
            {
                "video_id": r.result_id,
                "title": "Song %d" % index,
                "channel": "Artist",
            }
            for index, r in enumerate(results)
        ]

    def test_ids_outside_the_shortlist_are_left_out(self):
        results = make_results(4)
        report = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(choice_response(results[0].result_id)),
        )
        shortlisted = set(report["shortlist_ids"])
        assert {c["video_id"] for c in report["shortlist_candidates"]} == shortlisted

    def test_follows_the_shortlist_order_not_the_provider_order(self):
        results = make_results(3)
        report = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(choice_response(results[0].result_id)),
        )
        assert [c["video_id"] for c in report["shortlist_candidates"]] == report[
            "shortlist_ids"
        ]

    def test_channel_falls_back_to_the_first_artist(self):
        result = make_results(1)[0]
        result.author = ""
        result.artists = ("First", "Second")
        assert demo._result_channel(result) == "First"

    def test_channel_uses_an_explicit_channel_field(self):
        result = make_results(1)[0]
        result.author = ""
        result.artists = ("First",)
        result.channel = "Real Channel"
        assert demo._result_channel(result) == "Real Channel"

    def test_no_channel_reads_as_missing(self):
        result = make_results(1)[0]
        result.author = ""
        result.artists = ()
        assert demo._result_channel(result) == ""

    def test_video_id_falls_back_to_the_url(self):
        result = make_results(1)[0]
        result.result_id = None
        assert demo._result_video_id(ghostify_dl, result) == "a0000000000"

    def test_no_candidates_look_up_anything(self):
        assert demo.shortlist_candidates(ghostify_dl, make_results(3), []) == []
        assert demo.shortlist_candidates(ghostify_dl, None, [None, "  "]) == []

    def test_an_unmatched_shortlisted_id_is_simply_absent(self):
        results = make_results(1)
        found = demo.shortlist_candidates(ghostify_dl, results, ["b0000000000"])
        assert found == []


class TestVerboseOptionTable:
    """``-v`` closes with every option and the confidence it was given."""

    def render(self, report, response, *args, **kwargs):
        return demo.format_verbose_options(response, report, *args, **kwargs)

    def test_every_option_is_shown_with_title_channel_and_percentage(self):
        results = make_results(3)
        probabilities = {results[0].result_id: 0.2, results[1].result_id: 0.5}
        block = self.render(
            make_report(results),
            options_response(results[2].result_id, probabilities),
        )
        assert table_rows(block) == [
            (results[1].result_id, "50.0%", "Song 1 - Artist"),
            (results[0].result_id, "20.0%", "Song 0 - Artist"),
            (results[2].result_id, "n/a", "Song 2 - Artist"),
        ]
        assert "3 in the shortlist" in block

    def test_answer_confidence_is_shown_once(self):
        results = make_results(2)
        block = self.render(
            make_report(results),
            options_response(results[0].result_id, {results[0].result_id: 0.5}, 0.71),
        )
        assert "answer conf   : 71.0%" in block

    def test_options_are_sorted_highest_first(self):
        results = make_results(4)
        probabilities = {
            results[0].result_id: 0.11,
            results[1].result_id: 0.72,
            results[2].result_id: 0.05,
            results[3].result_id: 0.44,
        }
        block = self.render(
            make_report(results),
            options_response(results[1].result_id, probabilities),
        )
        assert table_rows(block) == [
            (results[1].result_id, "72.0%", "Song 1 - Artist"),
            (results[3].result_id, "44.0%", "Song 3 - Artist"),
            (results[0].result_id, "11.0%", "Song 0 - Artist"),
            (results[2].result_id, "5.0%", "Song 2 - Artist"),
        ]

    def test_equal_confidences_keep_the_shortlist_order(self):
        results = make_results(3)
        probabilities = {r.result_id: 0.4 for r in results}
        block = self.render(
            make_report(results),
            options_response(results[0].result_id, probabilities),
        )
        assert [row[0] for row in table_rows(block)] == [
            r.result_id for r in results
        ]

    def test_missing_confidence_sorts_last(self):
        results = make_results(3)
        probabilities = {results[2].result_id: 0.05}
        block = self.render(
            make_report(results),
            options_response(results[0].result_id, probabilities),
        )
        assert [row[1] for row in table_rows(block)][-2:] == ["n/a", "n/a"]
        assert table_rows(block)[0] == (results[2].result_id, "5.0%", "Song 2 - Artist")

    def test_chosen_option_is_marked_and_named(self):
        results = make_results(3)
        target = results[2].result_id
        probabilities = {results[0].result_id: 0.2, results[2].result_id: 0.6}
        block = self.render(
            make_report(results, jev_video_id=target),
            options_response(target, probabilities),
        )
        marked = chosen_rows(block)
        assert len(marked) == 1
        legend = next(
            line
            for line in block.splitlines()
            if demo.CHOSEN_MARKER in line and not table_rows(line + "\n")
        )
        assert "marks the option JEV chose" in legend
        assert marked[0].split()[0] == demo.CHOSEN_MARKER
        assert marked[0].split()[1] == "1"
        assert target in marked[0]
        assert "chosen        : %s - the option the app used" % target in block
        for other in (results[0], results[1]):
            assert not any(
                demo.CHOSEN_MARKER in line and other.result_id in line
                for line in block.splitlines()
            )

    def test_only_the_chosen_row_carries_the_marker(self):
        results = make_results(3)
        target = results[0].result_id
        probabilities = {results[0].result_id: 0.9, results[1].result_id: 0.4}
        block = self.render(
            make_report(results, jev_video_id=target),
            options_response(target, probabilities),
        )
        assert len(chosen_rows(block)) == 1
        assert target in chosen_rows(block)[0]
        assert [row[0] for row in table_rows(block)][0] == target
        assert chosen_rows(block)[0].split()[1] == "1"

    def test_no_chosen_option_when_the_result_was_kept(self):
        results = make_results(2)
        block = self.render(
            make_report(results, jev_video_id=None, outcome="rejected"),
            options_response(results[1].result_id, {results[1].result_id: 0.6}, 0.1),
        )
        assert "chosen        : none - the deterministic result was kept" in block
        assert "marks the option JEV chose" not in block
        assert len(chosen_rows(block)) == 0

    def test_a_refused_shortlisted_choice_is_named_in_a_note(self):
        results = make_results(2)
        block = self.render(
            make_report(results, jev_video_id=None, outcome="rejected"),
            options_response(
                results[1].result_id, {results[1].result_id: 0.4}, 0.2
            ),
        )
        assert (
            "the answer named %s, which the selector did not accept" % results[1].result_id
            in block
        )

    def test_an_unvalidated_choice_is_never_quoted(self):
        results = make_results(2)
        block = self.render(
            make_report(results, jev_video_id=None),
            options_response("IGNORE-ALL-THIS", {results[0].result_id: 0.5}),
        )
        assert "IGNORE-ALL-THIS" not in block

    def test_missing_probability_reads_na_with_a_note(self):
        results = make_results(3)
        block = self.render(
            make_report(results),
            options_response(results[0].result_id, {results[0].result_id: 0.5}),
        )
        assert "2 of 3 options had no readable probability" in block
        assert "read n/a above" in block
        assert [row[1] for row in table_rows(block)] == ["50.0%", "n/a", "n/a"]

    @pytest.mark.parametrize(
        "value", ["high", "", None, True, False, [0.5], {"p": 0.5}, float("nan")]
    )
    def test_malformed_probability_reads_na_and_does_not_fail(self, value):
        results = make_results(2)
        block = self.render(
            make_report(results),
            options_response(
                results[0].result_id,
                {results[0].result_id: 0.7, results[1].result_id: value},
            ),
        )
        assert "70.0%" in block
        assert "1 of 2 options had no readable probability" in block
        assert demo.NOT_AVAILABLE in block

    def test_numeric_strings_are_read_like_the_selector_reads_them(self):
        results = make_results(2)
        block = self.render(
            make_report(results),
            options_response(
                results[0].result_id,
                {results[0].result_id: "0.75", results[1].result_id: 0.25},
            ),
        )
        assert "75.0%" in block
        assert "0 of 2 options had no readable probability" not in block

    def test_out_of_range_values_are_shown_as_reported_with_a_note(self):
        results = make_results(2)
        block = self.render(
            make_report(results),
            options_response(
                results[0].result_id,
                {results[0].result_id: 1.5, results[1].result_id: 0.25},
            ),
        )
        assert "150.0%" in block
        assert "1 value outside 0..1 is shown exactly as reported" in block

    def test_probability_for_a_non_shortlisted_id_is_ignored(self):
        results = make_results(2)
        block = self.render(
            make_report(results),
            options_response(
                results[0].result_id,
                {results[0].result_id: 0.5, "b0000000000": 0.9, "short": 0.4},
            ),
        )
        assert "b0000000000" not in block
        assert (
            "2 reported probabilities did not name a shortlisted option and were "
            "ignored" in block
        )

    def test_a_shortlisted_id_with_no_candidate_reads_na(self):
        results = make_results(2)
        report = make_report(results, shortlist_candidates=[])
        block = self.render(
            report,
            options_response(results[0].result_id, {results[0].result_id: 0.5}),
        )
        assert "n/a (no title in the shortlist)" in block
        assert "2 options had no title or channel in the shortlist" in block

    def test_a_candidate_without_a_channel_shows_only_its_title(self):
        results = make_results(1)
        report = make_report(results, shortlist_candidates=[
            {"video_id": results[0].result_id, "title": "Just A Title", "channel": ""}
        ])
        block = self.render(
            report, options_response(results[0].result_id, {results[0].result_id: 0.5})
        )
        assert "Just A Title" in block
        assert "no title in the shortlist" not in block

    def test_a_long_title_is_trimmed_on_a_word_boundary(self):
        results = make_results(1)
        report = make_report(results, shortlist_candidates=[
            {
                "video_id": results[0].result_id,
                "title": "word " * 60,
                "channel": "Channel",
            }
        ])
        block = self.render(
            report, options_response(results[0].result_id, {results[0].result_id: 0.5})
        )
        video_id, conf, label = table_rows(block)[0]
        assert label.endswith("…")
        assert not label.rstrip("…").endswith(" ")
        assert label.rstrip("…").split() == ["word"] * len(
            label.rstrip("…").split()
        )
        assert len(label) <= demo.OPTION_LABEL_WIDTH + 1
        assert video_id == results[0].result_id
        assert conf == "50.0%"

    def test_model_is_not_consulted_when_jev_was_off(self):
        results = make_results(2)
        block = self.render(
            make_report(results, jev_enabled=False),
            options_response(results[0].result_id, {results[0].result_id: 0.5}),
        )
        assert "model         : not consulted" in block

    def test_header_and_footer_frame_the_table(self):
        results = make_results(2)
        block = self.render(
            make_report(results),
            options_response(results[0].result_id, {results[0].result_id: 0.5}),
        )
        lines = block.splitlines()
        assert lines[0] == demo.VERBOSE_OPTIONS_HEADING
        assert lines[1] == demo.VERBOSE_RULE
        assert lines[-1] == demo.VERBOSE_RULE
        assert block.endswith("%s\n" % demo.VERBOSE_RULE)
        header = next(line for line in lines if "video id" in line)
        assert header.split() == [
            "video", "id", "conf", "candidate", "(title", "-", "channel)"
        ]

    def test_table_columns_line_up_under_the_header(self):
        results = make_results(2)
        block = self.render(
            make_report(results, jev_video_id=results[0].result_id),
            options_response(
                results[0].result_id,
                {results[0].result_id: 0.5, results[1].result_id: 1.0},
            ),
        )
        lines = block.splitlines()
        header = next(line for line in lines if "video id" in line)
        rows = [line for line in lines if table_rows(line + "\n")]
        assert len(rows) == 2
        for line in rows:
            video_id, conf, _label = table_rows(line + "\n")[0]
            assert line.index(video_id) == header.index("video id")
            assert line.index(conf) + len(conf) == header.index("conf") + 4
        assert header.index("candidate (title - channel)") == 28

    def test_a_duplicate_shortlist_id_is_listed_once(self):
        results = make_results(2)
        report = make_report(
            results,
            shortlist_ids=[results[0].result_id, results[0].result_id],
        )
        block = self.render(
            report,
            options_response(results[0].result_id, {results[0].result_id: 0.5}),
        )
        assert "options       : 1 in the shortlist" in block
        assert [row[0] for row in table_rows(block)] == [results[0].result_id]

    @pytest.mark.parametrize(
        "response",
        [
            None,
            "a string",
            {},
            {"answers": {}},
            choice_response("a0000000000"),
            options_response("a0000000000", {}),
            options_response("a0000000000", None),
        ],
    )
    def test_no_per_option_data_renders_no_block(self, response):
        assert self.render(make_report(), response) == ""

    def test_no_shortlist_renders_no_block(self):
        results = make_results(2)
        report = make_report(results, shortlist_ids=[], jev_video_id=None)
        assert (
            self.render(
                report, options_response(results[0].result_id, {results[0].result_id: 1})
            )
            == ""
        )

    def test_colour_strips_back_to_the_plain_block(self):
        results = make_results(3)
        probabilities = {results[0].result_id: 0.2, results[1].result_id: 0.7}
        report = make_report(results, jev_video_id=results[1].result_id)
        response = options_response(results[1].result_id, probabilities)
        plain = self.render(report, response)
        coloured = self.render(report, response, demo.Palette(True))
        assert "\033[" in coloured
        assert demo.strip_ansi(coloured) == plain

    def test_the_marker_is_the_only_painted_part_of_a_row(self):
        results = make_results(2)
        report = make_report(results, jev_video_id=results[0].result_id)
        response = options_response(results[0].result_id, {results[0].result_id: 0.5})
        coloured = self.render(report, response, demo.Palette(True))
        assert chosen_rows(coloured) == [
            "  %s 1  a0000000000   50.0%%  Song 0 - Artist"
            % demo.Palette(True).paint("success", demo.CHOSEN_MARKER)
        ]

    def test_a_credential_shaped_title_is_redacted(self):
        results = make_results(1)
        results[0].name = "Song %s" % KEY_SHAPED
        report = make_report(results, shortlist_candidates=[
            {"video_id": results[0].result_id, "title": results[0].name, "channel": ""}
        ])
        block = self.render(
            report, options_response(results[0].result_id, {results[0].result_id: 0.5})
        )
        assert KEY_SHAPED not in block
        assert demo.REDACTED in block

    def test_the_table_is_verbose_only(self):
        results = make_results(2)
        report = make_report(results)
        probabilities = {r.result_id: 0.5 for r in results}
        response = options_response(results[0].result_id, probabilities)
        assert demo.format_report(report) == demo.format_report(
            dict(report), color=False
        )
        assert demo.format_report(report).count("conf") <= 2
        assert "%" not in demo.format_report(report)


class TestVerboseOptionTableInARealRun:
    """The table is built from the live answer, through the live code path."""

    def test_every_option_is_shown_after_the_request_dump(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        probabilities = {
            results[0].result_id: 0.1,
            results[1].result_id: 0.35,
            results[2].result_id: 0.55,
        }
        out = run_verbose(
            monkeypatch,
            capsys,
            [TRACK_URL, "-v"],
            results=results,
            response=options_response(results[2].result_id, probabilities),
        )
        block = options_from(out)
        assert block
        assert out.index(demo.VERBOSE_REQUEST_HEADING) < out.index(
            demo.VERBOSE_OPTIONS_HEADING
        )
        for result in results:
            assert result.result_id in block
            assert "Song %s" % result.result_id[-1] in block
        assert "55.0%" in block
        assert "35.0%" in block
        assert "10.0%" in block

    def test_the_request_was_really_sent(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        sent = []
        out = run_verbose(
            monkeypatch,
            capsys,
            [TRACK_URL, "-v"],
            results=results,
            sent=sent,
            response=options_response(results[0].result_id, spread(results)),
        )
        assert len(sent) == 1
        assert body_of(out.split(demo.VERBOSE_RULE)[1]) == sent[0]["payload"]
        for result in results:
            assert result.result_id in sent[0]["payload"]["questions"]["recording"][
                "criteria"
            ]

    def test_the_report_still_comes_last_and_is_unchanged(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        response = options_response(
            results[2].result_id,
            {results[0].result_id: 0.2, results[1].result_id: 0.3, results[2].result_id: 0.6},
        )
        code, plain = run_demo(
            monkeypatch, capsys, [TRACK_URL], results=results, response=response
        )
        verbose_code, verbose = run_demo(
            monkeypatch, capsys, [TRACK_URL, "-v"], results=results, response=response
        )
        assert code == verbose_code == demo.EXIT_OK
        assert plain.startswith("Ghostify - YouTube selection")
        assert plain in verbose
        assert verbose.endswith(plain)
        assert verbose.count("Ghostify - YouTube selection") == 1
        assert options_from(verbose) != ""
        assert verbose[: verbose.index(demo.VERBOSE_OPTIONS_HEADING)].startswith(
            demo.VERBOSE_REQUEST_HEADING
        )
        assert verbose[verbose.index(demo.VERBOSE_OPTIONS_HEADING) :] == (
            options_from(verbose) + "\n\n" + plain
        )

    def test_the_short_and_long_flag_agree(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(2)
        response = options_response(
            results[0].result_id, {r.result_id: 0.6 for r in results}
        )
        short = run_verbose(
            monkeypatch, capsys, [TRACK_URL, "-v"], results=results, response=response
        )
        long = run_verbose(
            monkeypatch, capsys, [TRACK_URL, "--verbose"], results=results, response=response
        )
        assert short == long
        assert options_from(short) != ""

    def test_the_table_does_not_change_the_decision(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(4)
        target = results[3].result_id
        response = options_response(
            target, {results[0].result_id: 0.1, target: 0.6, results[1].result_id: 0.3}
        )
        quiet = collect(
            results=results, search=make_search(results), transport=stub_transport(response)
        )
        log = demo.VerboseJev(stream=io.StringIO())
        loud = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(response),
            verbose=log,
        )
        assert quiet == loud
        assert loud["outcome"] == jev_selector.OUTCOME_CHOSE
        assert loud["effective_video_id"] == target
        assert len(log.blocks) == 2
        assert log.responses == [response]

    def test_the_key_never_reaches_the_table(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(2)
        out = run_verbose(
            monkeypatch,
            capsys,
            [TRACK_URL, "-v"],
            results=results,
            response=options_response(results[0].result_id, spread(results)),
        )
        block = options_from(out)
        assert block
        assert FAKE_KEY not in out
        assert "Bearer" not in block
        assert "Authorization" not in block

    def test_no_response_body_is_printed(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(2)
        response = options_response(results[0].result_id, spread(results))
        response["provider_note"] = RESPONSE_SENTINEL
        response["answers"]["recording"]["commentary"] = RESPONSE_SENTINEL
        out = run_verbose(
            monkeypatch, capsys, [TRACK_URL, "-v"], results=results, response=response
        )
        assert options_from(out)
        assert RESPONSE_SENTINEL not in out

    def test_an_unusable_response_still_prints_the_report(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        code, out = run_demo(
            monkeypatch, capsys, [TRACK_URL, "-v"], response="not a response"
        )
        assert code == demo.EXIT_OK
        assert demo.VERBOSE_REQUEST_HEADING in out
        assert options_from(out) == ""
        assert out.count("Ghostify - YouTube selection") == 1
        assert "answer rejected: the response carried no usable answer" in out
        last = out.rstrip().splitlines()[-1]
        assert last.startswith("  JEV status")
        assert "answer rejected" in last

    def test_a_rejected_answer_still_lists_its_options(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        out = run_verbose(
            monkeypatch,
            capsys,
            [TRACK_URL, "-v"],
            results=results,
            response=options_response(results[2].result_id, spread(results, 2), 0.2),
        )
        block = options_from(out)
        assert "chosen        : none - the deterministic result was kept" in block
        assert "answer rejected" in out

    def test_no_table_when_nothing_was_sent(self, monkeypatch, capsys):
        out = run_verbose(monkeypatch, capsys, [TRACK_URL, "-v"])
        assert demo.VERBOSE_SKIPPED_HEADING in out
        assert options_from(out) == ""

    def test_no_table_for_an_unambiguous_shortlist(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        out = run_verbose(
            monkeypatch, capsys, [TRACK_URL, "-v"], results=make_results(1)
        )
        assert options_from(out) == ""

    def test_colour_strips_back_to_the_plain_run(self, monkeypatch, capsys):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(2)
        response = options_response(
            results[0].result_id,
            {results[0].result_id: 0.7, results[1].result_id: 0.2},
        )
        plain = run_verbose(
            monkeypatch, capsys, [TRACK_URL, "-v", "--no-color"],
            results=results, response=response,
        )
        monkeypatch.setattr(demo, "use_color", lambda *a, **k: True)
        coloured = run_verbose(
            monkeypatch, capsys, [TRACK_URL, "-v"], results=results, response=response
        )
        assert "\033[" in coloured
        assert options_from(coloured) != ""
        assert demo.strip_ansi(coloured) == plain

    def test_a_broken_stream_cannot_break_the_decision(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(2)
        report = collect(
            results=results,
            search=make_search(results),
            transport=stub_transport(
                options_response(
                    results[1].result_id,
                    {results[0].result_id: 0.2, results[1].result_id: 0.7},
                )
            ),
            verbose=demo.VerboseJev(stream=BrokenStream()),
        )
        assert report["outcome"] == jev_selector.OUTCOME_CHOSE
        assert report["effective_video_id"] == results[1].result_id

"""Offline tests for the advisory OpenRouter/JEV video selector.

No test here performs network I/O: the selector accepts an injectable
``transport`` callable, and the one test that exercises the default transport
substitutes a stub ``requests`` module in ``sys.modules``.
"""

import inspect
import json
import logging
import os
import sys
import textwrap

import pytest

import jev_selector
import ghostify_dl
from jev_selector import (
    JevConfig,
    Shortlist,
    build_payload,
    build_shortlist,
    decide,
    parse_choice,
    select_video_id,
)

FAKE_KEY = "or-test-do-not-use-000000000000"


class FakeSong:
    def __init__(self, name="Song", artists=("Artist",), album_name="Album", duration=200.0):
        self.name = name
        self.artists = list(artists)
        self.artist = artists[0] if artists else ""
        self.album_name = album_name
        self.duration = duration
        self.song_id = "spotify:track:abc123"


class FakeResult:
    def __init__(
        self,
        video_id,
        name="",
        author="",
        duration=None,
        album=None,
        verified=False,
        source="youtube-music",
        url=None,
    ):
        self.result_id = video_id
        self.name = name
        self.author = author
        self.artists = (author,) if author else ()
        self.duration = duration if duration is not None else 0
        self.album = album
        self.verified = verified
        self.source = source
        self.url = url if url is not None else "https://music.youtube.com/watch?v=%s" % video_id


def make_results(count=3, duration=200.0, prefix="a"):
    return [
        FakeResult(
            "%s%010d" % (prefix, index),
            name="Song %d" % index,
            author="Artist",
            duration=duration,
            album="Album",
            verified=index == 0,
        )
        for index in range(count)
    ]


def make_config(**overrides):
    base = {
        "api_key": FAKE_KEY,
        "enabled": True,
        "timeout": 4.0,
        "min_confidence": 0.6,
        "max_candidates": 8,
    }
    base.update(overrides)
    return JevConfig(**base)


def choice_response(choice, confidence=0.9, probabilities=None, qtype="choice"):
    answer = {"type": qtype, "choice": choice, "confidence": confidence}
    if probabilities is not None:
        answer["probabilities"] = probabilities
    return {"answers": {"recording": answer}, "id": "gen-dec-test"}


def recording_transport(response):
    calls = []

    def transport(url, payload, headers, timeout):
        calls.append({"url": url, "payload": payload, "headers": headers, "timeout": timeout})
        if isinstance(response, BaseException):
            raise response
        return response

    transport.calls = calls
    return transport


class TestVideoIdExtraction:
    def test_result_id_used(self):
        assert jev_selector._video_id_of(FakeResult("dQw4w9WgXcQ")) == "dQw4w9WgXcQ"

    def test_url_fallback(self):
        result = FakeResult("", url="https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1")
        assert jev_selector._video_id_of(result) == "dQw4w9WgXcQ"

    def test_short_url_fallback(self):
        result = FakeResult("", url="https://youtu.be/dQw4w9WgXcQ")
        assert jev_selector._video_id_of(result) == "dQw4w9WgXcQ"

    def test_non_video_id_rejected(self):
        assert jev_selector._video_id_of(FakeResult("tooshort")) is None

    def test_unusable_url_rejected(self):
        result = FakeResult("", url="https://example.com/not-a-video")
        assert jev_selector._video_id_of(result) is None

    def test_none_result(self):
        assert jev_selector._video_id_of(None) is None


class TestConfigFromEnv:
    def test_no_key_disables(self):
        config = JevConfig.from_env(env={})
        assert config.api_key is None
        assert config.enabled is False

    def test_blank_key_disables(self):
        config = JevConfig.from_env(env={"OPENROUTER_API_KEY": "   "})
        assert config.api_key is None
        assert config.enabled is False

    def test_key_enables_by_default(self):
        config = JevConfig.from_env(env={"OPENROUTER_API_KEY": FAKE_KEY})
        assert config.api_key == FAKE_KEY
        assert config.enabled is True

    def test_explicit_disable_overrides_key(self):
        config = JevConfig.from_env(
            env={"OPENROUTER_API_KEY": FAKE_KEY, "GHOSTIFY_JEV_ENABLED": "0"}
        )
        assert config.enabled is False

    @pytest.mark.parametrize(
        "raw,expected",
        [
            ("1", True),
            ("true", True),
            ("YES", True),
            ("on", True),
            ("0", False),
            ("off", False),
            ("nonsense", True),
        ],
    )
    def test_flag_parsing(self, raw, expected):
        env = {"OPENROUTER_API_KEY": FAKE_KEY, "GHOSTIFY_JEV_ENABLED": raw}
        assert JevConfig.from_env(env=env).enabled is expected

    def test_defaults(self):
        config = JevConfig.from_env(env={"OPENROUTER_API_KEY": FAKE_KEY})
        assert config.model == "typesafe/jev-1.13"
        assert config.endpoint == "https://openrouter.ai/api/alpha/decisions"
        assert config.timeout == pytest.approx(4.0)
        assert config.max_candidates == 6
        assert config.min_confidence == pytest.approx(0.6)

    def test_timeout_cap_applied(self):
        env = {"OPENROUTER_API_KEY": FAKE_KEY, "GHOSTIFY_JEV_TIMEOUT": "20"}
        assert JevConfig.from_env(env=env, timeout_cap=3.0).timeout == pytest.approx(3.0)

    def test_timeout_clamped(self):
        env = {"OPENROUTER_API_KEY": FAKE_KEY, "GHOSTIFY_JEV_TIMEOUT": "9999"}
        assert JevConfig.from_env(env=env).timeout == pytest.approx(30.0)
        env["GHOSTIFY_JEV_TIMEOUT"] = "0"
        assert JevConfig.from_env(env=env).timeout == pytest.approx(0.1)

    def test_candidate_limit_clamped(self):
        env = {"OPENROUTER_API_KEY": FAKE_KEY, "GHOSTIFY_JEV_MAX_CANDIDATES": "100"}
        assert JevConfig.from_env(env=env).max_candidates == 25
        env["GHOSTIFY_JEV_MAX_CANDIDATES"] = "1"
        assert JevConfig.from_env(env=env).max_candidates == 2
        env["GHOSTIFY_JEV_MAX_CANDIDATES"] = "5"
        assert JevConfig.from_env(env=env).max_candidates == 5

    def test_default_candidate_limit_is_six(self):
        assert jev_selector.DEFAULT_MAX_CANDIDATES == 6
        env = {"OPENROUTER_API_KEY": FAKE_KEY}
        assert JevConfig.from_env(env=env).max_candidates == 6
        assert JevConfig().max_candidates == 6

    def test_candidate_limit_above_default_still_honoured(self):
        env = {"OPENROUTER_API_KEY": FAKE_KEY, "GHOSTIFY_JEV_MAX_CANDIDATES": "8"}
        assert JevConfig.from_env(env=env).max_candidates == 8

    def test_confidence_clamped(self):
        env = {"OPENROUTER_API_KEY": FAKE_KEY, "GHOSTIFY_JEV_MIN_CONFIDENCE": "5"}
        assert JevConfig.from_env(env=env).min_confidence == pytest.approx(1.0)

    def test_garbage_values_fall_back(self):
        env = {
            "OPENROUTER_API_KEY": FAKE_KEY,
            "GHOSTIFY_JEV_TIMEOUT": "soon",
            "GHOSTIFY_JEV_MAX_CANDIDATES": "",
            "GHOSTIFY_JEV_MODEL": "",
        }
        config = JevConfig.from_env(env=env)
        assert config.timeout == pytest.approx(4.0)
        assert config.max_candidates == 6
        assert config.model == "typesafe/jev-1.13"

    def test_hostile_env_object_does_not_raise(self):
        class Hostile(dict):
            def get(self, *args, **kwargs):
                raise RuntimeError("nope")

        assert JevConfig.from_env(env=Hostile()).enabled is False

    def test_repr_hides_key(self):
        config = make_config()
        assert FAKE_KEY not in repr(config)
        assert FAKE_KEY not in str(config)

    def test_reads_process_env(self, monkeypatch):
        monkeypatch.setenv("OPENROUTER_API_KEY", FAKE_KEY)
        assert JevConfig.from_env().api_key == FAKE_KEY


class TestBuildShortlist:
    def test_empty_results(self):
        shortlist = build_shortlist([], FakeSong())
        assert len(shortlist) == 0
        assert shortlist.fallback_id is None
        assert not shortlist

    def test_none_results(self):
        assert build_shortlist(None, FakeSong()).fallback_id is None

    def test_order_follows_provider(self):
        results = make_results(4)
        shortlist = build_shortlist(results, FakeSong())
        assert [c.video_id for c in shortlist] == [r.result_id for r in results]

    def test_fallback_is_first_provider_result(self):
        results = make_results(3)
        assert build_shortlist(results, FakeSong()).fallback_id == results[0].result_id

    def test_duplicates_removed(self):
        first = FakeResult("aaaaaaaaaaa", name="One")
        dup = FakeResult("aaaaaaaaaaa", name="One again")
        other = FakeResult("bbbbbbbbbbb", name="Two")
        shortlist = build_shortlist([first, dup, other], FakeSong())
        assert [c.video_id for c in shortlist] == ["aaaaaaaaaaa", "bbbbbbbbbbb"]
        assert shortlist.candidates[0].title == "One"

    def test_results_without_ids_skipped(self):
        broken = FakeResult("bad", url="https://example.com/x")
        good = FakeResult("bbbbbbbbbbb")
        shortlist = build_shortlist([broken, good], FakeSong())
        assert [c.video_id for c in shortlist] == ["bbbbbbbbbbb"]
        assert shortlist.fallback_id == "bbbbbbbbbbb"

    def test_duration_outlier_excluded(self):
        on_album = FakeResult("aaaaaaaaaaa", name="Studio", duration=200.0)
        live = FakeResult("bbbbbbbbbbb", name="Live 90min", duration=5400.0)
        other = FakeResult("ccccccccccc", name="Studio take", duration=205.0)
        shortlist = build_shortlist([live, on_album, other], FakeSong(duration=200.0))
        assert [c.video_id for c in shortlist] == ["aaaaaaaaaaa", "ccccccccccc"]
        assert shortlist.fallback_id == "bbbbbbbbbbb"

    def test_duration_filter_never_empties_shortlist(self):
        results = [
            FakeResult("aaaaaaaaaaa", duration=10000.0),
            FakeResult("bbbbbbbbbbb", duration=11000.0),
        ]
        shortlist = build_shortlist(results, FakeSong(duration=200.0))
        assert [c.video_id for c in shortlist] == ["aaaaaaaaaaa", "bbbbbbbbbbb"]

    def test_unknown_duration_kept(self):
        results = [
            FakeResult("aaaaaaaaaaa", duration=0.0),
            FakeResult("bbbbbbbbbbb", duration=201.0),
        ]
        assert len(build_shortlist(results, FakeSong(duration=200.0))) == 2

    def test_song_without_duration_keeps_all(self):
        results = [
            FakeResult("aaaaaaaaaaa", duration=10000.0),
            FakeResult("bbbbbbbbbbb", duration=205.0),
        ]
        assert len(build_shortlist(results, FakeSong(duration=0))) == 2

    def test_max_candidates_caps_shortlist(self):
        results = make_results(12)
        shortlist = build_shortlist(results, FakeSong(), JevConfig(max_candidates=3))
        assert len(shortlist) == 3
        assert shortlist.fallback_id == results[0].result_id

    def test_default_cap_caps_shortlist_at_six(self):
        results = make_results(12)
        shortlist = build_shortlist(results, FakeSong())
        assert len(shortlist) == 6
        assert shortlist.fallback_id == results[0].result_id
        assert [c.video_id for c in shortlist] == [r.result_id for r in results[:6]]

    def test_cap_above_default_is_still_configurable(self):
        results = make_results(10)
        shortlist = build_shortlist(results, FakeSong(), JevConfig(max_candidates=9))
        assert len(shortlist) == 9

    def test_deterministic_across_calls(self):
        results = make_results(6)
        first = build_shortlist(results, FakeSong()).ids
        second = build_shortlist(results, FakeSong()).ids
        assert first == second

    def test_candidate_fields_populated(self):
        result = FakeResult(
            "aaaaaaaaaaa",
            name="Song (Official Video)",
            author="Artist",
            duration=200.0,
            album="Album",
            verified=True,
        )
        candidate = build_shortlist([result], FakeSong()).candidates[0]
        assert candidate.title == "Song (Official Video)"
        assert candidate.artist == "Artist"
        assert candidate.album == "Album"
        assert candidate.duration == pytest.approx(200.0)
        assert candidate.verified is True
        assert candidate.source == "youtube-music"

    def test_dict_results_supported(self):
        results = [{"result_id": "aaaaaaaaaaa", "title": "Song", "duration": 200.0}]
        shortlist = build_shortlist(results, FakeSong())
        assert shortlist.candidates[0].title == "Song"

    def test_ambiguity_detection(self):
        single = build_shortlist(make_results(1), FakeSong())
        assert single.is_ambiguous() is False
        assert build_shortlist(make_results(2), FakeSong()).is_ambiguous() is True


class TestBuildPayload:
    def _criteria(self, results=None, song=None, candidates=None):
        song = song or FakeSong()
        if candidates is None:
            candidates = build_shortlist(
                make_results(2) if results is None else results, song
            ).candidates
        payload = build_payload(song, candidates, jev_selector.DEFAULT_MODEL)
        return payload["questions"]["recording"]["criteria"]

    def test_criteria_keys_are_video_ids(self):
        shortlist = build_shortlist(make_results(3), FakeSong())
        payload = build_payload(FakeSong(), shortlist.candidates)
        question = payload["questions"]["recording"]
        assert question["type"] == "choice"
        assert set(question["criteria"]) == shortlist.ids

    def test_payload_model_and_state(self):
        shortlist = build_shortlist(make_results(2), FakeSong())
        payload = build_payload(FakeSong(), shortlist.candidates, "typesafe/jev-1.13")
        assert payload["model"] == "typesafe/jev-1.13"
        requested = payload["state"]["requested_song"]
        assert requested["title"] == "Song"
        assert requested["artists"] == ["Artist"]
        assert requested["length_seconds"] == pytest.approx(200.0)

    def test_state_does_not_duplicate_the_candidates(self):
        shortlist = build_shortlist(make_results(3), FakeSong())
        payload = build_payload(FakeSong(), shortlist.candidates)
        assert set(payload["state"]) == {"requested_song"}
        assert "candidates" not in payload["state"]
        blob = json.dumps(payload, sort_keys=True)
        for candidate in shortlist.candidates:
            assert blob.count(candidate.video_id) == 1
            assert blob.count(candidate.title) == 1

    def test_song_state_carries_no_album(self):
        shortlist = build_shortlist(make_results(2), FakeSong())
        payload = build_payload(FakeSong(), shortlist.candidates)
        requested = payload["state"]["requested_song"]
        assert set(requested) == {"title", "artists", "length_seconds"}
        assert "album" not in json.dumps(payload)

    def test_criteria_values_are_compact_descriptions(self):
        shortlist = build_shortlist(make_results(3), FakeSong())
        criteria = self._criteria(make_results(3))
        assert len(criteria) == 3
        for video_id, text in criteria.items():
            assert isinstance(text, str)
            candidate = next(c for c in shortlist.candidates if c.video_id == video_id)
            assert candidate.title in text
            assert candidate.artist in text
            assert "%ds" % int(candidate.duration) in text
            flag = " [official listing]" if candidate.verified else ""
            assert text == '"%s" by %s (%ds)%s' % (
                candidate.title,
                candidate.artist,
                int(candidate.duration),
                flag,
            )

    def test_criteria_drop_nonessential_candidate_fields(self):
        results = [
            FakeResult(
                "aaaaaaaaaaa", name="Song", author="Chan", duration=200.0, album="SecretAlbum"
            )
        ]
        text = self._criteria(results)["aaaaaaaaaaa"]
        assert "SecretAlbum" not in text
        assert "youtube-music" not in text

    def test_official_listing_is_surfaced_compactly(self):
        results = [FakeResult("aaaaaaaaaaa", name="Song", author="Chan", verified=True)]
        assert "[official listing]" in self._criteria(results)["aaaaaaaaaaa"]

    def test_long_titles_and_channels_are_sent_verbatim(self):
        long_title = "T" * 900
        long_channel = "C " * 449 + "C"
        results = [
            FakeResult("aaaaaaaaaaa", name=long_title, author=long_channel, duration=200.0),
            FakeResult("bbbbbbbbbbb", name="Other", author="Other", duration=200.0),
        ]
        text = self._criteria(results)["aaaaaaaaaaa"]
        assert len(long_title) == 900 and len(long_channel) == 899
        assert text == '"%s" by %s (200s)' % (long_title, long_channel)
        assert long_title in text
        assert long_channel in text
        assert "..." not in text
        assert text.count('"') == 2

    def test_title_and_channel_survive_whitespace_normalisation(self):
        results = [
            FakeResult(
                "aaaaaaaaaaa",
                name="Song\tname\nwith   spacing  (Official Video)",
                author="Channel \n with  spacing",
                duration=200.0,
            )
        ]
        text = self._criteria(results)["aaaaaaaaaaa"]
        assert text == (
            '"Song name with spacing (Official Video)" by Channel with spacing (200s)'
        )
        assert "\n" not in text and "\t" not in text

    def test_long_song_state_is_not_truncated(self):
        long_title = "S" * 900
        long_artist = "A " * 449 + "A"
        candidates = build_shortlist(make_results(1), FakeSong()).candidates
        payload = build_payload(
            FakeSong(name=long_title, artists=(long_artist,)), candidates
        )
        requested = payload["state"]["requested_song"]
        assert requested["title"] == long_title
        assert requested["artists"] == [long_artist]

    def test_no_truncation_constants_remain(self):
        for name in ("MAX_CRITERIA_TEXT", "MAX_TITLE_TEXT", "MAX_CHANNEL_TEXT"):
            assert not hasattr(jev_selector, name)

    def test_payload_is_much_smaller_than_a_candidate_dump(self):
        shortlist = build_shortlist(make_results(6), FakeSong())
        payload = build_payload(FakeSong(), shortlist.candidates)
        dump = {
            "state": {
                "candidates": [
                    {
                        "video_id": c.video_id,
                        "title": c.title,
                        "artist": c.artist,
                        "album": c.album,
                        "length_seconds": c.duration,
                        "source": c.source,
                        "official_listing": c.verified,
                    }
                    for c in shortlist.candidates
                ]
            }
        }
        assert len(json.dumps(payload)) < len(json.dumps(dump))

    def test_only_validated_video_ids_reach_the_criteria(self):
        candidates = [
            jev_selector.Candidate(video_id="aaaaaaaaaaa", title="Good"),
            jev_selector.Candidate(video_id="tooshort", title="Bad id"),
            jev_selector.Candidate(video_id="", title="Empty id"),
        ]
        criteria = self._criteria(candidates=candidates)
        assert set(criteria) == {"aaaaaaaaaaa"}

    def test_duplicate_ids_are_collapsed(self):
        candidates = [
            jev_selector.Candidate(video_id="aaaaaaaaaaa", title="First"),
            jev_selector.Candidate(video_id="aaaaaaaaaaa", title="Second"),
        ]
        criteria = self._criteria(candidates=candidates)
        assert list(criteria) == ["aaaaaaaaaaa"]
        assert "First" in criteria["aaaaaaaaaaa"]

    def test_answer_for_an_unsent_id_is_rejected(self):
        shortlist = build_shortlist(make_results(2), FakeSong())
        payload = build_payload(FakeSong(), shortlist.candidates)
        sent = frozenset(payload["questions"]["recording"]["criteria"])
        assert parse_choice(choice_response("zzzzzzzzzzz", confidence=1.0), sent) is None
        for video_id in sent:
            assert parse_choice(choice_response(video_id, confidence=0.9), sent) == video_id

    def test_instructions_mention_canonical_recording(self):
        shortlist = build_shortlist(make_results(2), FakeSong())
        payload = build_payload(FakeSong(), shortlist.candidates)
        assert "official" in payload["questions"]["recording"]["instructions"]


class TestParseChoice:
    IDS = frozenset({"aaaaaaaaaaa", "bbbbbbbbbbb"})

    def test_valid_choice(self):
        response = choice_response("bbbbbbbbbbb", confidence=0.91)
        assert parse_choice(response, self.IDS) == "bbbbbbbbbbb"

    def test_hallucinated_id_rejected(self):
        response = choice_response("zzzzzzzzzzz", confidence=1.0)
        assert parse_choice(response, self.IDS) is None

    def test_low_confidence_rejected(self):
        response = choice_response("bbbbbbbbbbb", confidence=0.2)
        assert parse_choice(response, self.IDS, 0.6) is None

    def test_confidence_boundary_accepted(self):
        response = choice_response("bbbbbbbbbbb", confidence=0.6)
        assert parse_choice(response, self.IDS, 0.6) == "bbbbbbbbbbb"

    def test_missing_confidence_accepted(self):
        response = {"answers": {"recording": {"type": "choice", "choice": "aaaaaaaaaaa"}}}
        assert parse_choice(response, self.IDS) == "aaaaaaaaaaa"

    def test_wrong_answer_type_rejected(self):
        response = choice_response("aaaaaaaaaaa", qtype="noul")
        assert parse_choice(response, self.IDS) is None

    def test_non_choice_value_rejected(self):
        response = {"answers": {"recording": {"type": "choice", "choice": {"a": 1}}}}
        assert parse_choice(response, self.IDS) is None

    @pytest.mark.parametrize(
        "response",
        [
            None,
            "not json at all",
            [],
            {},
            {"answers": None},
            {"answers": []},
            {"answers": {}},
            {"answers": {"recording": None}},
            {"answers": {"recording": "aaaaaaaaaaa"}},
            {"answers": {"other": {"type": "choice", "choice": "aaaaaaaaaaa"}}},
        ],
    )
    def test_malformed_responses_rejected(self, response):
        assert parse_choice(response, self.IDS) is None

    def test_empty_allowed_ids_rejects(self):
        assert parse_choice(choice_response("aaaaaaaaaaa"), frozenset()) is None

    def test_probabilities_must_agree_with_choice(self):
        response = choice_response(
            "aaaaaaaaaaa", confidence=0.9, probabilities={"aaaaaaaaaaa": 0.2, "bbbbbbbbbbb": 0.8}
        )
        assert parse_choice(response, self.IDS) is None

    def test_probability_below_threshold_rejected(self):
        response = choice_response(
            "aaaaaaaaaaa", confidence=0.9, probabilities={"aaaaaaaaaaa": 0.3, "bbbbbbbbbbb": 0.7}
        )
        assert parse_choice(response, self.IDS, 0.6) is None

    def test_probabilities_support_choice(self):
        response = choice_response(
            "bbbbbbbbbbb", confidence=0.9, probabilities={"aaaaaaaaaaa": 0.1, "bbbbbbbbbbb": 0.9}
        )
        assert parse_choice(response, self.IDS) == "bbbbbbbbbbb"

    def test_empty_probabilities_ignored(self):
        response = choice_response("aaaaaaaaaaa", confidence=0.9, probabilities={})
        assert parse_choice(response, self.IDS) == "aaaaaaaaaaa"

    def test_non_numeric_probabilities_ignored(self):
        response = choice_response(
            "aaaaaaaaaaa", confidence=0.9, probabilities={"aaaaaaaaaaa": "high", "bbbbbbbbbbb": None}
        )
        assert parse_choice(response, self.IDS) == "aaaaaaaaaaa"


class TestSelectVideoId:
    def _shortlist(self, count=3):
        return build_shortlist(make_results(count), FakeSong(), make_config())

    def test_valid_choice_returned(self):
        shortlist = self._shortlist()
        target = shortlist.candidates[-1].video_id
        transport = recording_transport(choice_response(target, confidence=0.95))
        assert select_video_id(FakeSong(), shortlist, make_config(), transport) == target

    def test_no_key_falls_back(self):
        shortlist = self._shortlist()
        transport = recording_transport(choice_response(shortlist.candidates[-1].video_id))
        config = make_config(api_key=None, enabled=False)
        assert select_video_id(FakeSong(), shortlist, config, transport) is None
        assert transport.calls == []

    def test_disabled_flag_falls_back(self):
        shortlist = self._shortlist()
        transport = recording_transport(choice_response(shortlist.candidates[-1].video_id))
        assert select_video_id(FakeSong(), shortlist, make_config(enabled=False), transport) is None
        assert transport.calls == []

    def test_hallucinated_id_falls_back(self):
        shortlist = self._shortlist()
        transport = recording_transport(choice_response("zzzzzzzzzzz", confidence=1.0))
        assert select_video_id(FakeSong(), shortlist, make_config(), transport) is None

    def test_low_confidence_falls_back(self):
        shortlist = self._shortlist()
        transport = recording_transport(
            choice_response(shortlist.candidates[-1].video_id, confidence=0.1)
        )
        assert select_video_id(FakeSong(), shortlist, make_config(), transport) is None

    def test_malformed_response_falls_back(self):
        shortlist = self._shortlist()
        transport = recording_transport({"unexpected": True})
        assert select_video_id(FakeSong(), shortlist, make_config(), transport) is None

    def test_single_candidate_skipped(self):
        shortlist = build_shortlist(make_results(1), FakeSong(), make_config())
        transport = recording_transport(choice_response(shortlist.candidates[0].video_id))
        assert select_video_id(FakeSong(), shortlist, make_config(), transport) is None
        assert transport.calls == []

    def test_empty_shortlist(self):
        transport = recording_transport(choice_response("aaaaaaaaaaa"))
        assert select_video_id(FakeSong(), Shortlist((), None), make_config(), transport) is None
        assert transport.calls == []

    @pytest.mark.parametrize(
        "error",
        [
            TimeoutError("timed out"),
            ConnectionError("no network"),
            OSError("socket error"),
            ValueError("bad json"),
            jev_selector.JevTransportError("HTTP 500"),
        ],
    )
    def test_transport_errors_fall_back(self, error):
        shortlist = self._shortlist()
        transport = recording_transport(error)
        assert select_video_id(FakeSong(), shortlist, make_config(), transport) is None

    def test_request_shape(self):
        shortlist = self._shortlist()
        transport = recording_transport(choice_response(shortlist.candidates[0].video_id))
        select_video_id(FakeSong(), shortlist, make_config(timeout=2.5), transport)
        call = transport.calls[0]
        assert call["url"] == "https://openrouter.ai/api/alpha/decisions"
        assert call["timeout"] == pytest.approx(2.5)
        assert call["headers"]["Content-Type"] == "application/json"
        assert call["headers"]["Authorization"] == "Bearer %s" % FAKE_KEY
        criteria = call["payload"]["questions"]["recording"]["criteria"]
        assert set(criteria) == shortlist.ids
        assert "candidates" not in call["payload"]["state"]
        assert set(call["payload"]["state"]) == {"requested_song"}

    def test_default_config_never_raises(self):
        shortlist = self._shortlist()
        transport = recording_transport(choice_response(shortlist.candidates[0].video_id))
        assert select_video_id(FakeSong(), shortlist, None, transport) is None


class TestDefaultTransport:
    def _install_stub(self, monkeypatch, response=None, status_code=200, raise_json=False):
        recorded = {}

        class StubResponse:
            def __init__(self):
                self.status_code = status_code

            def json(self):
                if raise_json:
                    raise ValueError("not json")
                return response

        class StubRequests:
            @staticmethod
            def post(url, json=None, headers=None, timeout=None):
                recorded.update(
                    {"url": url, "json": json, "headers": headers, "timeout": timeout}
                )
                return StubResponse()

        monkeypatch.setitem(sys.modules, "requests", StubRequests)
        return recorded

    def test_posts_and_parses(self, monkeypatch):
        shortlist = build_shortlist(make_results(2), FakeSong(), make_config())
        target = shortlist.candidates[-1].video_id
        recorded = self._install_stub(
            monkeypatch, response=choice_response(target, confidence=0.99)
        )
        chosen = select_video_id(FakeSong(), shortlist, make_config())
        assert chosen == target
        assert recorded["url"] == "https://openrouter.ai/api/alpha/decisions"
        assert recorded["timeout"] == pytest.approx(4.0)
        assert recorded["headers"]["Authorization"] == "Bearer %s" % FAKE_KEY

    def test_http_error_falls_back(self, monkeypatch):
        shortlist = build_shortlist(make_results(2), FakeSong(), make_config())
        self._install_stub(monkeypatch, response={}, status_code=503)
        assert select_video_id(FakeSong(), shortlist, make_config()) is None

    def test_unparsable_body_falls_back(self, monkeypatch):
        shortlist = build_shortlist(make_results(2), FakeSong(), make_config())
        self._install_stub(monkeypatch, raise_json=True)
        assert select_video_id(FakeSong(), shortlist, make_config()) is None


class TestSecretHygiene:
    def test_key_absent_from_logs(self, caplog):
        shortlist = build_shortlist(make_results(3), FakeSong(), make_config())
        with caplog.at_level(logging.DEBUG, logger="ghostify_dl.jev"):
            select_video_id(
                FakeSong(),
                shortlist,
                make_config(),
                recording_transport(choice_response("zzzzzzzzzzz", confidence=1.0)),
            )
            select_video_id(
                FakeSong(),
                shortlist,
                make_config(),
                recording_transport(choice_response("aaaaaaaaaaa", confidence=0.2)),
            )
            select_video_id(FakeSong(), shortlist, make_config(api_key=None), None)
        assert caplog.records, "expected selector debug output"
        assert FAKE_KEY not in caplog.text

    def test_key_absent_from_logs_on_transport_error(self, caplog):
        shortlist = build_shortlist(make_results(3), FakeSong(), make_config())
        with caplog.at_level(logging.DEBUG, logger="ghostify_dl.jev"):
            select_video_id(
                FakeSong(),
                shortlist,
                make_config(),
                recording_transport(ConnectionError("Bearer %s rejected" % FAKE_KEY)),
            )
        assert FAKE_KEY not in caplog.text

    def test_candidate_text_absent_from_logs(self, caplog):
        secret_title = "DoNotLeakThisTitle"
        results = [
            FakeResult("aaaaaaaaaaa", name=secret_title, duration=200.0),
            FakeResult("bbbbbbbbbbb", name="Other", duration=200.0),
        ]
        shortlist = build_shortlist(results, FakeSong(), make_config())
        with caplog.at_level(logging.DEBUG, logger="ghostify_dl.jev"):
            select_video_id(
                FakeSong(),
                shortlist,
                make_config(),
                recording_transport(choice_response("aaaaaaaaaaa", confidence=0.9)),
            )
        assert secret_title not in caplog.text
        assert "criteria" not in caplog.text

    def test_no_hardcoded_secret_in_source(self):
        source = textwrap.dedent(inspect.getsource(jev_selector))
        assert "sk-or-" not in source
        assert FAKE_KEY not in source
        assert "Bearer %s" in source  # the only interpolation, over config.api_key

    def test_env_example_has_no_value(self):
        path = _repo_file(".env.example")
        if path is None:
            pytest.skip("no .env.example in this checkout")
        lines = [
            line.strip()
            for line in open(path, encoding="utf-8").read().splitlines()
            if line.strip()
        ]
        assert lines == ["OPENROUTER_API_KEY="]

    def test_gitignore_covers_env(self):
        path = _repo_file(".gitignore")
        if path is None:
            pytest.skip("no .gitignore in this checkout")
        text = open(path, encoding="utf-8").read()
        assert ".env" in text
        assert "!.env.example" in text


def _repo_file(name):
    root = os.path.dirname(os.path.dirname(os.path.abspath(jev_selector.__file__)))
    directory = root
    for _ in range(8):
        candidate = os.path.join(directory, name)
        if os.path.isfile(candidate):
            return candidate
        parent = os.path.dirname(directory)
        if parent == directory:
            break
        directory = parent
    return None


class TestGhostifyDlHandoff:
    """``_select_yt_id`` keeps the pre-JEV behaviour unless JEV validates."""

    @pytest.fixture(autouse=True)
    def _isolate_env(self, monkeypatch):
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

    def _stub_requests(self, monkeypatch, response=None, status_code=200, raise_json=False):
        class StubResponse:
            def __init__(self):
                self.status_code = status_code

            def json(self):
                if raise_json:
                    raise ValueError("not json")
                return response

        class StubRequests:
            @staticmethod
            def post(url, json=None, headers=None, timeout=None):
                return StubResponse()

        monkeypatch.setitem(sys.modules, "requests", StubRequests)

    def test_no_key_returns_first_result(self, monkeypatch):
        results = make_results(4)
        self._stub_requests(monkeypatch, response=choice_response("bbbbbbbbbbb"))
        assert ghostify_dl._select_yt_id(FakeSong(), results, 8.0) == results[0].result_id

    def test_no_results_returns_none(self, monkeypatch):
        self._stub_requests(monkeypatch, response=choice_response("bbbbbbbbbbb"))
        assert ghostify_dl._select_yt_id(FakeSong(), [], 8.0) is None

    def test_disabled_flag_returns_first_result(self, monkeypatch):
        results = make_results(4)
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        monkeypatch.setenv(jev_selector.ENV_ENABLED, "0")
        self._stub_requests(monkeypatch, response=choice_response("bbbbbbbbbbb"))
        assert ghostify_dl._select_yt_id(FakeSong(), results, 8.0) == results[0].result_id

    def test_valid_choice_overrides_first_result(self, monkeypatch):
        results = make_results(4)
        target = results[3].result_id
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        self._stub_requests(
            monkeypatch, response=choice_response(target, confidence=0.95)
        )
        assert ghostify_dl._select_yt_id(FakeSong(), results, 8.0) == target

    def test_hallucinated_id_returns_first_result(self, monkeypatch):
        results = make_results(4)
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        self._stub_requests(monkeypatch, response=choice_response("zzzzzzzzzzz", confidence=1.0))
        assert ghostify_dl._select_yt_id(FakeSong(), results, 8.0) == results[0].result_id

    def test_low_confidence_returns_first_result(self, monkeypatch):
        results = make_results(4)
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        self._stub_requests(
            monkeypatch, response=choice_response(results[3].result_id, confidence=0.05)
        )
        assert ghostify_dl._select_yt_id(FakeSong(), results, 8.0) == results[0].result_id

    def test_network_error_returns_first_result(self, monkeypatch):
        results = make_results(4)
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)

        class Boom:
            @staticmethod
            def post(*args, **kwargs):
                raise TimeoutError("read timed out")

        monkeypatch.setitem(sys.modules, "requests", Boom)
        assert ghostify_dl._select_yt_id(FakeSong(), results, 8.0) == results[0].result_id

    def test_malformed_response_returns_first_result(self, monkeypatch):
        results = make_results(4)
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        self._stub_requests(monkeypatch, raise_json=True)
        assert ghostify_dl._select_yt_id(FakeSong(), results, 8.0) == results[0].result_id

    def test_unambiguous_single_result_returns_it(self, monkeypatch):
        results = make_results(1)
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        self._stub_requests(monkeypatch, response=choice_response("aaaaaaaaaaa"))
        assert ghostify_dl._select_yt_id(FakeSong(), results, 8.0) == results[0].result_id

    def test_selector_import_failure_returns_first_result(self, monkeypatch):
        results = make_results(4)
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        monkeypatch.setitem(sys.modules, "jev_selector", None)
        assert ghostify_dl._select_yt_id(FakeSong(), results, 8.0) == results[0].result_id

    def test_first_result_video_id_helper(self):
        assert ghostify_dl._first_result_video_id([]) is None
        assert ghostify_dl._first_result_video_id(None) is None
        results = make_results(3)
        assert ghostify_dl._first_result_video_id(results) == results[0].result_id

    def test_iterator_results_are_consumed_once(self, monkeypatch):
        results = make_results(4)
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        self._stub_requests(
            monkeypatch, response=choice_response(results[2].result_id, confidence=0.9)
        )
        chosen = ghostify_dl._select_yt_id(FakeSong(), iter(results), 8.0)
        assert chosen == results[2].result_id

    def test_resolve_yt_id_rejects_zero_budget(self):
        assert ghostify_dl._resolve_yt_id(FakeSong(), 0) is None
        assert ghostify_dl._resolve_yt_id(FakeSong(), -1) is None


class TestDecide:
    """``decide`` is ``select_video_id`` plus an explanation; same outcomes."""

    def _shortlist(self, count=3):
        return build_shortlist(make_results(count), FakeSong(), make_config())

    def test_returns_id_and_confidence(self):
        shortlist = self._shortlist()
        target = shortlist.candidates[-1].video_id
        decision = decide(
            FakeSong(), shortlist, make_config(), recording_transport(choice_response(target, 0.83))
        )
        assert decision.video_id == target
        assert decision.accepted is True
        assert decision.outcome == jev_selector.OUTCOME_CHOSE
        assert decision.confidence == pytest.approx(0.83)
        assert decision.candidates_considered == 3
        assert "alternate" in decision.reason

    def test_top_pick_reason_mentions_top_result(self):
        shortlist = self._shortlist()
        decision = decide(
            FakeSong(),
            shortlist,
            make_config(),
            recording_transport(choice_response(shortlist.fallback_id, 0.7)),
        )
        assert "top result" in decision.reason

    def test_no_key_outcome(self):
        decision = decide(
            FakeSong(), self._shortlist(), make_config(api_key=None, enabled=False)
        )
        assert decision.video_id is None
        assert decision.outcome == jev_selector.OUTCOME_INACTIVE
        assert "OPENROUTER_API_KEY" in decision.reason

    def test_empty_shortlist_outcome(self):
        decision = decide(
            FakeSong(), Shortlist((), None), make_config()
        )
        assert decision.outcome == jev_selector.OUTCOME_NO_CANDIDATES

    def test_unambiguous_outcome(self):
        shortlist = build_shortlist(make_results(1), FakeSong(), make_config())
        decision = decide(
            FakeSong(), shortlist, make_config()
        )
        assert decision.outcome == jev_selector.OUTCOME_UNAMBIGUOUS

    def test_rejected_outcome(self):
        decision = decide(
            FakeSong(),
            self._shortlist(),
            make_config(),
            recording_transport(choice_response("zzzzzzzzzzz", 1.0)),
        )
        assert decision.outcome == jev_selector.OUTCOME_REJECTED
        assert decision.video_id is None
        assert decision.confidence is None

    def test_request_failure_outcome_names_only_the_exception_class(self):
        decision = decide(
            FakeSong(),
            self._shortlist(),
            make_config(),
            recording_transport(TimeoutError("Bearer %s rejected" % FAKE_KEY)),
        )
        assert decision.outcome == jev_selector.OUTCOME_REQUEST_FAILED
        assert decision.reason == "request failed (TimeoutError)"
        assert FAKE_KEY not in decision.reason

    def test_missing_confidence_reports_none(self):
        shortlist = self._shortlist()
        response = {"answers": {"recording": {"type": "choice", "choice": shortlist.fallback_id}}}
        decision = decide(
            FakeSong(), shortlist, make_config(), recording_transport(response)
        )
        assert decision.accepted is True
        assert decision.confidence is None

    def test_select_video_id_matches_decide(self):
        shortlist = self._shortlist()
        target = shortlist.candidates[-1].video_id
        response = choice_response(target, 0.9)
        decided = decide(
            FakeSong(), shortlist, make_config(), recording_transport(response)
        )
        chosen = select_video_id(
            FakeSong(), shortlist, make_config(), recording_transport(response)
        )
        assert decided.video_id == chosen


class TestRejectionReasons:
    """``decide`` names *why* an answer was refused, from safe scalars only."""

    IDS = frozenset({"aaaaaaaaaaa", "bbbbbbbbbbb"})

    def _shortlist(self, results=None):
        return build_shortlist(
            make_results(3) if results is None else results, FakeSong(), make_config()
        )

    def _decide(self, response, results=None, config=None):
        shortlist = self._shortlist(results)
        return shortlist, decide(
            FakeSong(),
            shortlist,
            config or make_config(),
            recording_transport(response),
        )

    def _ids(self, results=None):
        return [candidate.video_id for candidate in self._shortlist(results)]

    @pytest.mark.parametrize(
        "response",
        [
            {},
            {"unexpected": True},
            {"answers": None},
            {"answers": []},
            {"answers": {}},
            {"answers": {"recording": None}},
            {"answers": {"recording": "aaaaaaaaaaa"}},
            {"answers": {"other": {"type": "choice", "choice": "aaaaaaaaaaa"}}},
        ],
    )
    def test_missing_answers_is_named(self, response):
        _, decision = self._decide(response)
        assert decision.outcome == jev_selector.OUTCOME_REJECTED
        assert decision.video_id is None
        assert decision.accepted is False
        assert jev_selector.REJECTION_NO_ANSWER in decision.reason
        assert parse_choice(response, self.IDS) is None

    def test_unexpected_question_is_named(self):
        _, decision = self._decide({"answers": {"other": {}}})
        assert jev_selector.REJECTION_NO_ANSWER in decision.reason
        assert "recording" in decision.reason

    def test_wrong_answer_type_is_named(self):
        first = self._ids()[0]
        _, decision = self._decide(choice_response(first, qtype="noul"))
        assert decision.outcome == jev_selector.OUTCOME_REJECTED
        assert jev_selector.REJECTION_ANSWER_TYPE in decision.reason
        assert "type=noul" in decision.reason

    def test_hostile_answer_type_is_classified_not_echoed(self):
        hostile = "T" * 300
        _, decision = self._decide(choice_response(self._ids()[0], qtype=hostile))
        assert jev_selector.REJECTION_ANSWER_TYPE in decision.reason
        assert hostile not in decision.reason
        assert "type=other" in decision.reason

    def test_id_outside_the_shortlist_is_named(self):
        _, decision = self._decide(choice_response("zzzzzzzzzzz", confidence=1.0))
        assert jev_selector.REJECTION_UNKNOWN_ID in decision.reason
        assert "shortlist=3" in decision.reason
        assert "zzzzzzzzzzz" not in decision.reason

    def test_low_confidence_is_named(self):
        _, decision = self._decide(choice_response(self._ids()[0], confidence=0.21))
        assert jev_selector.REJECTION_LOW_CONFIDENCE in decision.reason
        assert "confidence=0.21 < 0.60" in decision.reason

    def test_probability_disagreement_is_named(self):
        first, second = self._ids()[:2]
        response = choice_response(
            first, confidence=0.9, probabilities={first: 0.2, second: 0.8}
        )
        _, decision = self._decide(response)
        assert jev_selector.REJECTION_PROBABILITIES in decision.reason
        assert "p=0.20" in decision.reason
        assert "best p=0.80" in decision.reason

    def test_probability_below_threshold_counts_as_low_confidence(self):
        first, second = self._ids()[:2]
        response = choice_response(
            second, confidence=0.9, probabilities={first: 0.1, second: 0.3}
        )
        _, decision = self._decide(response)
        assert jev_selector.REJECTION_LOW_CONFIDENCE in decision.reason
        assert "probability=0.30 < 0.60" in decision.reason

    def test_every_refusal_gets_its_own_reason(self):
        first, second = self._ids()[:2]
        reasons = set()
        for response in (
            {"answers": {}},
            choice_response(first, qtype="noul"),
            choice_response("zzzzzzzzzzz", confidence=1.0),
            choice_response(first, confidence=0.21),
            choice_response(
                first, confidence=0.9, probabilities={first: 0.2, second: 0.8}
            ),
        ):
            _, decision = self._decide(response)
            assert decision.outcome == jev_selector.OUTCOME_REJECTED
            assert decision.video_id is None
            reasons.add(decision.reason)
        assert len(reasons) == 5

    def test_accepted_answer_carries_no_rejection_label(self):
        shortlist = build_shortlist(make_results(3), FakeSong(), make_config())
        decision = decide(
            FakeSong(),
            shortlist,
            make_config(),
            recording_transport(choice_response(shortlist.fallback_id, 0.9)),
        )
        assert decision.outcome == jev_selector.OUTCOME_CHOSE
        for label in (
            jev_selector.REJECTION_NO_ANSWER,
            jev_selector.REJECTION_ANSWER_TYPE,
            jev_selector.REJECTION_UNKNOWN_ID,
            jev_selector.REJECTION_LOW_CONFIDENCE,
            jev_selector.REJECTION_PROBABILITIES,
        ):
            assert label not in decision.reason

    def test_parse_choice_still_answers_id_or_nothing(self):
        assert parse_choice(choice_response("bbbbbbbbbbb", 0.9), self.IDS) == "bbbbbbbbbbb"
        for response in (
            {"answers": {}},
            choice_response("aaaaaaaaaaa", qtype="noul"),
            choice_response("zzzzzzzzzzz", 1.0),
            choice_response("bbbbbbbbbbb", 0.1),
            choice_response(
                "bbbbbbbbbbb",
                0.9,
                probabilities={"aaaaaaaaaaa": 0.9, "bbbbbbbbbbb": 0.1},
            ),
        ):
            assert parse_choice(response, self.IDS) is None

    def test_reason_and_logs_never_leak_candidate_text_or_key(self, caplog):
        secret_title = "DoNotLeakThisTitle"
        results = [
            FakeResult("aaaaaaaaaaa", name=secret_title, duration=200.0),
            FakeResult("bbbbbbbbbbb", name="Other", duration=200.0),
        ]
        shortlist = build_shortlist(results, FakeSong(), make_config())
        with caplog.at_level(logging.DEBUG, logger="ghostify_dl.jev"):
            decision = decide(
                FakeSong(),
                shortlist,
                make_config(),
                recording_transport(choice_response("aaaaaaaaaaa", 0.1)),
            )
        assert jev_selector.REJECTION_LOW_CONFIDENCE in decision.reason
        assert secret_title not in decision.reason
        assert FAKE_KEY not in decision.reason
        assert secret_title not in caplog.text
        assert FAKE_KEY not in caplog.text
        assert "criteria" not in caplog.text

    def test_rejection_labels_are_exported(self):
        for name in (
            "REJECTION_NO_ANSWER",
            "REJECTION_ANSWER_TYPE",
            "REJECTION_UNKNOWN_ID",
            "REJECTION_LOW_CONFIDENCE",
            "REJECTION_PROBABILITIES",
        ):
            assert name in jev_selector.__all__
            label = getattr(jev_selector, name)
            assert isinstance(label, str) and label.islower()


class TestSanitizedHttpErrors:
    """Request failures keep the status/provider code and drop the body."""

    SECRET_BODY = "No auth credentials found for Bearer %s" % FAKE_KEY

    def _stub(self, monkeypatch, status_code, body=None, raise_json=False):
        class StubResponse:
            def __init__(self):
                self.status_code = status_code

            def json(self):
                if raise_json:
                    raise ValueError(TestSanitizedHttpErrors.SECRET_BODY)
                return body

        class StubRequests:
            @staticmethod
            def post(url, json=None, headers=None, timeout=None):
                return StubResponse()

        monkeypatch.setitem(sys.modules, "requests", StubRequests)

    def _decision(self, monkeypatch, status_code, body=None, raise_json=False, caplog=None):
        shortlist = build_shortlist(make_results(3), FakeSong(), make_config())
        self._stub(monkeypatch, status_code, body, raise_json)
        if caplog is None:
            return decide(FakeSong(), shortlist, make_config())
        with caplog.at_level(logging.DEBUG, logger="ghostify_dl.jev"):
            return decide(FakeSong(), shortlist, make_config())

    @pytest.mark.parametrize("status", [401, 402, 429, 500, 503])
    def test_status_is_reported(self, monkeypatch, status):
        decision = self._decision(monkeypatch, status)
        assert decision.outcome == jev_selector.OUTCOME_REQUEST_FAILED
        assert decision.video_id is None
        assert decision.reason == "request failed (HTTP %d)" % status

    def test_provider_code_is_reported(self, monkeypatch):
        decision = self._decision(
            monkeypatch, 401, {"error": {"code": "auth_error", "message": self.SECRET_BODY}}
        )
        assert decision.reason == "request failed (HTTP 401, provider code auth_error)"

    def test_error_type_field_is_used_as_a_code(self, monkeypatch):
        decision = self._decision(monkeypatch, 429, {"error": {"type": "rate_limit_error"}})
        assert decision.reason == "request failed (HTTP 429, provider code rate_limit_error)"

    def test_string_error_is_used_as_a_code(self, monkeypatch):
        decision = self._decision(monkeypatch, 402, {"error": "insufficient_credits"})
        assert decision.reason == (
            "request failed (HTTP 402, provider code insufficient_credits)"
        )

    def test_body_message_is_never_quoted(self, monkeypatch, caplog):
        decision = self._decision(
            monkeypatch,
            401,
            {"error": {"code": "auth_error", "message": self.SECRET_BODY}},
            caplog=caplog,
        )
        assert FAKE_KEY not in decision.reason
        assert "No auth credentials" not in decision.reason
        assert self.SECRET_BODY not in decision.reason
        assert FAKE_KEY not in caplog.text
        assert self.SECRET_BODY not in caplog.text

    @pytest.mark.parametrize(
        "code",
        [
            "Bearer %s" % FAKE_KEY,
            "sk-or-v1-%s" % FAKE_KEY,
            "or-v1-%s" % FAKE_KEY,
            "key=%s" % FAKE_KEY,
            "c" * 120,
            "two words",
            "",
            None,
            ["auth_error"],
        ],
    )
    def test_unsafe_provider_codes_are_dropped(self, monkeypatch, code):
        decision = self._decision(monkeypatch, 401, {"error": {"code": code}})
        assert decision.outcome == jev_selector.OUTCOME_REQUEST_FAILED
        assert FAKE_KEY not in decision.reason
        assert decision.reason == "request failed (HTTP 401)"

    def test_unparsable_error_body_reports_the_status_only(self, monkeypatch):
        decision = self._decision(monkeypatch, 503, raise_json=True)
        assert decision.reason == "request failed (HTTP 503)"
        assert FAKE_KEY not in decision.reason

    def test_unparsable_success_body_names_only_the_exception_class(self, monkeypatch):
        decision = self._decision(monkeypatch, 200, raise_json=True)
        assert decision.reason == "request failed (ValueError)"
        assert FAKE_KEY not in decision.reason

    def test_missing_status_code_is_reported_as_a_transport_error(self, monkeypatch):
        shortlist = build_shortlist(make_results(3), FakeSong(), make_config())

        class NoStatus:
            def json(self):
                return {}

        class StubRequests:
            @staticmethod
            def post(url, json=None, headers=None, timeout=None):
                return NoStatus()

        monkeypatch.setitem(sys.modules, "requests", StubRequests)
        decision = decide(FakeSong(), shortlist, make_config())
        assert decision.outcome == jev_selector.OUTCOME_REQUEST_FAILED
        assert decision.video_id is None
        assert FAKE_KEY not in decision.reason

    @pytest.mark.parametrize(
        "error",
        [
            TimeoutError("read timed out"),
            ConnectionError("no route to host"),
            ValueError("bad json"),
        ],
    )
    def test_other_exceptions_name_only_their_class(self, error):
        shortlist = build_shortlist(make_results(3), FakeSong(), make_config())
        decision = decide(
            FakeSong(), shortlist, make_config(), recording_transport(error)
        )
        assert decision.outcome == jev_selector.OUTCOME_REQUEST_FAILED
        assert decision.reason == "request failed (%s)" % type(error).__name__

    def test_raw_exception_message_is_never_reported(self, caplog):
        shortlist = build_shortlist(make_results(3), FakeSong(), make_config())
        with caplog.at_level(logging.DEBUG, logger="ghostify_dl.jev"):
            decision = decide(
                FakeSong(),
                shortlist,
                make_config(),
                recording_transport(
                    ConnectionError("401 Client Error: %s" % FAKE_KEY)
                ),
            )
        assert decision.reason == "request failed (ConnectionError)"
        assert FAKE_KEY not in decision.reason
        assert "401 Client Error" not in decision.reason
        assert FAKE_KEY not in caplog.text

    def test_hand_raised_transport_error_uses_only_its_scalars(self):
        shortlist = build_shortlist(make_results(3), FakeSong(), make_config())
        error = jev_selector.JevTransportError(
            "Authorization: Bearer %s" % FAKE_KEY, status=401, code="auth_error"
        )
        decision = decide(
            FakeSong(), shortlist, make_config(), recording_transport(error)
        )
        assert decision.reason == "request failed (HTTP 401, provider code auth_error)"
        assert FAKE_KEY not in decision.reason
        assert "Authorization" not in decision.reason

    def test_transport_error_without_scalars_falls_back_to_the_class_name(self):
        shortlist = build_shortlist(make_results(3), FakeSong(), make_config())
        decision = decide(
            FakeSong(),
            shortlist,
            make_config(),
            recording_transport(jev_selector.JevTransportError("HTTP 401")),
        )
        assert decision.reason == "request failed (JevTransportError)"

    def test_select_video_id_still_fails_closed_on_http_errors(self, monkeypatch):
        shortlist = build_shortlist(make_results(3), FakeSong(), make_config())
        self._stub(monkeypatch, 402, {"error": {"code": "insufficient_credits"}})
        assert select_video_id(FakeSong(), shortlist, make_config()) is None

    @pytest.mark.parametrize(
        "value,expected",
        [
            (200, 200),
            ("401", 401),
            (404.0, 404),
            (99, None),
            (600, None),
            (200.5, None),
            ("nope", None),
            (True, None),
            (None, None),
            (float("inf"), None),
            (float("nan"), None),
        ],
    )
    def test_safe_status(self, value, expected):
        assert jev_selector._safe_status(value) == expected

    @pytest.mark.parametrize(
        "value,expected",
        [
            ("auth_error", "auth_error"),
            ("invalid_api_key", "invalid_api_key"),
            ("429", "429"),
            ("two words", None),
            ("auth.error-1", "auth.error-1"),
            ("c" * 40, "c" * 40),
            ("c" * 41, None),
            ("", None),
            (None, None),
            (42, "42"),
        ],
    )
    def test_safe_token(self, value, expected):
        assert jev_selector._safe_token(value) == expected

    def test_numeric_provider_code_is_reported(self, monkeypatch):
        decision = self._decision(monkeypatch, 402, {"error": {"code": 402}})
        assert decision.reason == "request failed (HTTP 402, provider code 402)"

    def test_transport_error_detail(self):
        error = jev_selector.JevTransportError(
            "", status="401", code="auth_error"
        )
        assert error.status == 401
        assert error.code == "auth_error"
        assert error.detail == "HTTP 401, provider code auth_error"
        assert jev_selector.JevTransportError("", status=200).detail == "HTTP 200"
        assert jev_selector.JevTransportError("anything").detail == ""

    def test_source_has_no_hardcoded_error_text(self):
        source = textwrap.dedent(inspect.getsource(jev_selector))
        assert FAKE_KEY not in source
        assert "error.message" not in source
        assert 'error.get("message")' not in source


class TestCompareYtSelection:
    """The read-only comparison entry point mirrors ``_select_yt_id``."""

    def test_no_key_reports_deterministic_decision(self):
        results = make_results(4)
        report = ghostify_dl.compare_yt_selection(FakeSong(), results, 8.0)
        assert report["original_video_id"] == results[0].result_id
        assert report["original_url"].endswith(results[0].result_id)
        assert report["jev_video_id"] is None
        assert report["effective_video_id"] == results[0].result_id
        assert report["agree"] is True
        assert report["changed"] is False
        assert report["outcome"] == jev_selector.OUTCOME_INACTIVE
        assert report["api_key_present"] is False
        assert report["candidate_count"] == 4
        assert report["shortlist_count"] == 4

    def test_empty_results(self):
        report = ghostify_dl.compare_yt_selection(FakeSong(), [], 8.0)
        assert report["original_video_id"] is None
        assert report["outcome"] == "no_candidates"
        assert report["shortlist_ids"] == []

    def test_results_without_ids(self):
        broken = [FakeResult("bad", url="https://example.com/x")]
        report = ghostify_dl.compare_yt_selection(FakeSong(), broken, 8.0)
        assert report["original_video_id"] is None
        assert report["candidate_count"] == 1

    def test_iterator_is_consumed_once(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        report = ghostify_dl.compare_yt_selection(
            FakeSong(),
            iter(results),
            8.0,
            transport=recording_transport(choice_response(results[0].result_id, 0.9)),
        )
        assert report["effective_video_id"] == results[0].result_id
        assert report["candidate_count"] == 3

    def test_transport_receives_key_but_report_does_not(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        target = results[2].result_id
        transport = recording_transport(choice_response(target, 0.88))
        report = ghostify_dl.compare_yt_selection(FakeSong(), results, 8.0, transport=transport)
        assert transport.calls[0]["headers"]["Authorization"] == "Bearer %s" % FAKE_KEY
        assert report["jev_video_id"] == target
        assert report["confidence"] == pytest.approx(0.88)
        assert FAKE_KEY not in repr(report)

    def test_effective_matches_select_yt_id(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(4)
        target = results[3].result_id
        response = choice_response(target, 0.95)
        report = ghostify_dl.compare_yt_selection(
            FakeSong(), results, 8.0, transport=recording_transport(response)
        )
        assert report["effective_video_id"] == target
        assert report["changed"] is True
        assert report["agree"] is False

    def test_transport_failure_keeps_deterministic(self, monkeypatch):
        monkeypatch.setenv(jev_selector.ENV_API_KEY, FAKE_KEY)
        results = make_results(3)
        report = ghostify_dl.compare_yt_selection(
            FakeSong(),
            results,
            8.0,
            transport=recording_transport(ConnectionError("no network")),
        )
        assert report["outcome"] == jev_selector.OUTCOME_REQUEST_FAILED
        assert report["effective_video_id"] == results[0].result_id

    def test_selector_import_failure_is_reported(self, monkeypatch):
        results = make_results(3)
        monkeypatch.setitem(sys.modules, "jev_selector", None)
        report = ghostify_dl.compare_yt_selection(FakeSong(), results, 8.0)
        assert report["outcome"] == "selector_unavailable"
        assert report["selector_error"]
        assert report["effective_video_id"] == results[0].result_id


class TestSearchYtCandidates:
    """The tooling candidate search never raises and prefers YouTube Music."""

    def _stub(self, monkeypatch, music=None, youtube=None):
        """Stub *both* providers so no test can reach the network.

        *music* / *youtube* are either a result list or an exception to raise.
        """

        def provider(outcome):
            class Provider:
                def get_results(self, query, **kwargs):
                    self.query = query
                    self.kwargs = kwargs
                    if isinstance(outcome, BaseException):
                        raise outcome
                    return outcome

            return Provider()

        monkeypatch.setattr(ghostify_dl, "_get_yt_provider", lambda: provider(music))
        monkeypatch.setattr(
            ghostify_dl, "_get_youtube_fallback_provider", lambda: provider(youtube)
        )

    def test_zero_budget_returns_nothing(self, monkeypatch):
        self._stub(monkeypatch, music=make_results(2))
        report = ghostify_dl.search_yt_candidates(FakeSong(), per_track_timeout=0)
        assert report == {
            "results": [],
            "provider": None,
            "query": None,
            "error": "TimeoutBudget",
        }

    def test_youtube_music_results_are_returned(self, monkeypatch):
        results = make_results(2)
        self._stub(monkeypatch, music=results)
        report = ghostify_dl.search_yt_candidates(FakeSong(), limit=5)
        assert report["results"] == results
        assert report["provider"] == "youtube-music"
        assert report["error"] is None
        assert report["query"] == "Artist - Song"

    def test_youtube_music_search_arguments(self, monkeypatch):
        seen = {}

        class Provider:
            def get_results(self, query, **kwargs):
                seen["query"] = query
                seen["kwargs"] = kwargs
                return make_results(1)

        monkeypatch.setattr(ghostify_dl, "_get_yt_provider", lambda: Provider())
        monkeypatch.setattr(
            ghostify_dl,
            "_get_youtube_fallback_provider",
            lambda: pytest.fail("plain YouTube must not be searched when YTMusic has results"),
        )
        ghostify_dl.search_yt_candidates(FakeSong(), limit=5)
        assert seen["query"] == "Artist - Song"
        assert seen["kwargs"] == {"filter": "songs", "ignore_spelling": True, "limit": 5}

    def test_falls_back_to_plain_youtube(self, monkeypatch):
        fallback = [FakeResult("zzzzzzzzzzz")]
        self._stub(monkeypatch, music=[], youtube=fallback)
        report = ghostify_dl.search_yt_candidates(FakeSong())
        assert report["results"] == fallback
        assert report["provider"] == "youtube"
        assert report["error"] is None

    def test_youtube_fallback_after_music_error(self, monkeypatch):
        fallback = [FakeResult("zzzzzzzzzzz")]
        self._stub(monkeypatch, music=TimeoutError("slow"), youtube=fallback)
        report = ghostify_dl.search_yt_candidates(FakeSong())
        assert report["results"] == fallback
        assert report["provider"] == "youtube"
        assert report["error"] is None

    def test_all_providers_fail(self, monkeypatch):
        self._stub(monkeypatch, music=TimeoutError("slow"), youtube=ConnectionError("down"))
        report = ghostify_dl.search_yt_candidates(FakeSong())
        assert report["results"] == []
        assert report["provider"] is None
        assert report["error"] == "ConnectionError"

    def test_empty_results_without_error(self, monkeypatch):
        self._stub(monkeypatch, music=[], youtube=[])
        report = ghostify_dl.search_yt_candidates(FakeSong())
        assert report["results"] == []
        assert report["provider"] is None
        assert report["error"] is None


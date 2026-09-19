"""Tests for GraphQL and metadata helpers."""

from ghostify_dl import _get_thumbnail_url, _graphql_track_data, _extract_graphql_message


class TestGetThumbnailUrl:
    def test_thumbnail_key(self):
        info = {"thumbnail": "https://example.com/thumb.jpg"}
        assert _get_thumbnail_url(info) == "https://example.com/thumb.jpg"

    def test_thumbnails_list(self):
        info = {"thumbnails": [
            {"url": "https://example.com/small.jpg", "width": 100, "height": 100},
            {"url": "https://example.com/large.jpg", "width": 500, "height": 500},
        ]}
        assert _get_thumbnail_url(info) == "https://example.com/large.jpg"

    def test_empty_dict(self):
        assert _get_thumbnail_url({}) is None

    def test_empty_thumbnails(self):
        assert _get_thumbnail_url({"thumbnails": []}) is None

    def test_thumbnail_takes_priority(self):
        info = {
            "thumbnail": "https://example.com/priority.jpg",
            "thumbnails": [{"url": "https://example.com/other.jpg", "width": 100, "height": 100}],
        }
        assert _get_thumbnail_url(info) == "https://example.com/priority.jpg"


class TestGraphqlTrackData:
    def test_item_v2_track(self):
        item = {"itemV2": {"data": {"__typename": "Track", "name": "Song"}}}
        data = _graphql_track_data(item)
        assert data.get("name") == "Song"

    def test_item_v3_track(self):
        item = {"itemV3": {"data": {"__typename": "Track", "name": "Song"}}}
        data = _graphql_track_data(item)
        assert data.get("name") == "Song"

    def test_non_track_returns_empty(self):
        item = {"itemV2": {"data": {"__typename": "Playlist"}}}
        assert _graphql_track_data(item) == {}

    def test_non_dict_returns_empty(self):
        assert _graphql_track_data("not a dict") == {}

    def test_missing_keys(self):
        assert _graphql_track_data({}) == {}


class TestExtractGraphqlMessage:
    def test_none(self):
        assert _extract_graphql_message(None) == ""

    def test_simple_message(self):
        assert _extract_graphql_message({"message": "hello"}) == "hello"

    def test_nested_message(self):
        assert _extract_graphql_message({"message": {"message": "inner"}}) == "inner"

    def test_text_field(self):
        assert _extract_graphql_message({"message": {"text": "inner"}}) == "inner"

    def test_non_dict(self):
        assert _extract_graphql_message("not a dict") == ""

    def test_non_string_message(self):
        assert _extract_graphql_message({"message": 123}) == ""

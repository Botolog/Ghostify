"""Tests for the Synced lyrics provider's _normalize_lrc method.

Uses a standalone copy of the function to avoid importing the full spotdl
package (which pulls in Spotify/async dependencies).
"""

import re


def _normalize_lrc(text: str) -> str:
    """Standalone copy of Synced._normalize_lrc for testing."""
    lines = text.strip().splitlines()
    out = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        if re.match(
            r"^\[\d{2}:\d{2}\.\d+\]\s*(作词|作曲|作词\s*:|作曲\s*:|Lyrics by|Music by|编曲|混音|母带|制作|录音)",
            line,
            re.IGNORECASE,
        ):
            continue
        m = re.match(r"^\[(\d{2}:\d{2})\.(\d+)\](.*)", line)
        if m:
            mmss = m.group(1)
            ms = m.group(2).ljust(2, "0")[:2]
            content = m.group(3)
            line = f"[{mmss}.{ms}]{content}"
        out.append(line)
    return "\n".join(out)


class TestNormalizeLrc:
    """Tests for _normalize_lrc."""

    def test_strips_chinese_lyricist_metadata(self):
        input_text = (
            "[00:00.000] 作词 : Adam Levine\n"
            "[00:01.000] 作曲 : Adam Levine\n"
            "[00:08.460]I'm hurting baby\n"
        )
        result = _normalize_lrc(input_text)
        assert "作词" not in result
        assert "作曲" not in result
        assert "I'm hurting baby" in result

    def test_strips_chinese_producer_metadata(self):
        input_text = (
            "[00:00.000] 编曲 : Someone\n"
            "[00:00.500] 混音 : Someone\n"
            "[00:01.000] 母带 : Someone\n"
            "[00:01.500] 制作 : Someone\n"
            "[00:02.000] 录音 : Someone\n"
            "[00:03.000] Real lyrics here\n"
        )
        result = _normalize_lrc(input_text)
        for meta in ["编曲", "混音", "母带", "制作", "录音"]:
            assert meta not in result
        assert "Real lyrics here" in result

    def test_strips_english_metadata(self):
        input_text = (
            "[00:00.00]Lyrics by John Doe\n"
            "[00:01.00]Music by Jane Doe\n"
            "[00:02.00]Actual lyrics\n"
        )
        result = _normalize_lrc(input_text)
        assert "Lyrics by" not in result
        assert "Music by" not in result
        assert "Actual lyrics" in result

    def test_normalizes_3_decimal_timestamps_to_2(self):
        input_text = (
            "[00:08.460]Line one\n"
            "[00:10.450]Line two\n"
            "[01:23.900]Line three\n"
        )
        result = _normalize_lrc(input_text)
        assert "[00:08.46]" in result
        assert "[00:10.45]" in result
        assert "[01:23.90]" in result
        assert "Line one" in result

    def test_preserves_2_decimal_timestamps(self):
        input_text = (
            "[00:07.74] I'm hurtin', baby\n"
            "[00:12.00] I need your lovin'\n"
        )
        result = _normalize_lrc(input_text)
        assert "[00:07.74]" in result
        assert "[00:12.00]" in result

    def test_pads_1_decimal_to_2(self):
        input_text = "[00:05.1]Single digit ms\n"
        result = _normalize_lrc(input_text)
        assert "[00:05.10]" in result

    def test_truncates_3_decimal_to_2(self):
        input_text = "[00:05.123]Three digit ms\n"
        result = _normalize_lrc(input_text)
        assert "[00:05.12]" in result

    def test_removes_empty_lines(self):
        input_text = (
            "[00:01.00]Line one\n"
            "\n"
            "   \n"
            "[00:02.00]Line two\n"
        )
        result = _normalize_lrc(input_text)
        lines = result.split("\n")
        assert all(line.strip() for line in lines)
        assert len(lines) == 2

    def test_empty_input(self):
        assert _normalize_lrc("") == ""
        assert _normalize_lrc("   ") == ""
        assert _normalize_lrc("\n\n\n") == ""

    def test_only_metadata_returns_empty(self):
        input_text = (
            "[00:00.000] 作词 : Someone\n"
            "[00:01.000] 作曲 : Someone\n"
        )
        result = _normalize_lrc(input_text)
        assert result == ""

    def test_preserves_lyrics_content(self):
        input_text = (
            "[00:01.00] First line of lyrics\n"
            "[00:05.50] Second line with (parentheses)\n"
            "[00:10.00] Third line with 'quotes'\n"
        )
        result = _normalize_lrc(input_text)
        assert "First line of lyrics" in result
        assert "Second line with (parentheses)" in result
        assert "Third line with 'quotes'" in result

    def test_mixed_metadata_and_lyrics(self):
        input_text = (
            "[00:00.000] 作词 : Writer\n"
            "[00:01.000] 作曲 : Composer\n"
            "[00:05.00]Real lyric line\n"
            "[00:06.000] 编曲 : Arranger\n"
            "[00:10.00]Another lyric\n"
        )
        result = _normalize_lrc(input_text)
        assert "作词" not in result
        assert "作曲" not in result
        assert "编曲" not in result
        assert "Real lyric line" in result
        assert "Another lyric" in result
        assert result.count("\n") == 1

    def test_timestamps_in_order(self):
        """Function preserves input order (does not sort)."""
        input_text = (
            "[00:30.000]Third\n"
            "[00:10.000]First\n"
            "[00:20.000]Second\n"
        )
        result = _normalize_lrc(input_text)
        lines = result.split("\n")
        assert "Third" in lines[0]
        assert "First" in lines[1]
        assert "Second" in lines[2]

    def test_large_ms_values(self):
        input_text = "[00:05.999]Edge case\n"
        result = _normalize_lrc(input_text)
        assert "[00:05.99]" in result

    def test_zero_timestamps(self):
        input_text = (
            "[00:00.000]Opening line\n"
            "[00:00.100]Quick follow-up\n"
        )
        result = _normalize_lrc(input_text)
        assert "[00:00.00]" in result
        assert "[00:00.10]" in result

    def test_long_timestamps(self):
        input_text = (
            "[10:00.00]Minute ten\n"
            "[59:59.99]Last second\n"
        )
        result = _normalize_lrc(input_text)
        assert "[10:00.00]" in result
        assert "[59:59.99]" in result

    def test_realistic_netease_output(self):
        input_text = (
            "[00:00.000] 作词 : Adam Levine/Lukasz Gottwald\n"
            "[00:01.000] 作曲 : Adam Levine/Lukasz Gottwald\n"
            "[00:08.460]I'm hurting baby,\n"
            "[00:10.450]I'm broken down\n"
            "[00:12.450]I need your loving, loving\n"
            "[00:14.450]I need it now\n"
            "[00:16.480]When I'm without you\n"
            "[00:18.350]I'm something weak\n"
            "[00:20.440]You got me begging, begging\n"
            "[00:22.460]I'm on my knees\n"
            "[00:24.360]I don't wanna be needing your love\n"
            "[00:26.370]I just wanna be deep in your love\n"
        )
        result = _normalize_lrc(input_text)
        lines = result.strip().split("\n")
        assert len(lines) == 10
        for line in lines:
            assert line.startswith("[")
            ts_part = line.split("]")[0]
            assert len(ts_part.split(".")[1]) == 2

    def test_realistic_lrclib_output(self):
        input_text = (
            "[00:07.74] I'm hurtin', baby, I'm broken down\n"
            "[00:12.00] I need your lovin', lovin', I need it now\n"
            "[00:15.96] When I'm without you, I'm somethin' weak\n"
            "[00:19.81] You got me beggin', beggin', I'm on my knees, yeah (hey)\n"
        )
        result = _normalize_lrc(input_text)
        assert "[00:07.74]" in result
        assert "[00:12.00]" in result
        assert "[00:15.96]" in result
        assert "[00:19.81]" in result

    def test_no_timestamps_passthrough(self):
        input_text = (
            "No timestamp here\n"
            "[00:05.00]Timestamped line\n"
            "Another plain line\n"
        )
        result = _normalize_lrc(input_text)
        assert "No timestamp here" in result
        assert "Another plain line" in result
        assert "[00:05.00]" in result

    def test_metadata_with_no_space_after_timestamp(self):
        """Some NetEase outputs have no space between timestamp and metadata."""
        input_text = (
            "[00:00.000]作词: Someone\n"
            "[00:05.00]Real line\n"
        )
        result = _normalize_lrc(input_text)
        assert "作词" not in result
        assert "Real line" in result

    def test_4_decimal_truncates_to_2(self):
        """Edge case: some providers might output 4 decimal places."""
        input_text = "[00:05.1234]Four decimals\n"
        result = _normalize_lrc(input_text)
        assert "[00:05.12]" in result

    def test_single_digit_minutes(self):
        input_text = "[09:59.50]Late in song\n"
        result = _normalize_lrc(input_text)
        assert "[09:59.50]" in result

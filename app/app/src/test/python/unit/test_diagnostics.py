"""Tests for the read-only library diagnostics report."""

import types

import ghostify_dl
from ghostify_dl import (
    DIAGNOSTICS_CUSTOM_VERSION,
    DIAGNOSTICS_MISSING_VERSION,
    DIAGNOSTICS_UNKNOWN_VERSION,
    _diagnostics_module_source,
    _diagnostics_module_version,
    _diagnostics_version_value,
    library_diagnostics,
    library_diagnostics_report,
)


def make_module(name="pkg", **attrs):
    module = types.ModuleType(name)
    module.__file__ = "/site-packages/%s/__init__.py" % name
    for key, value in attrs.items():
        setattr(module, key, value)
    return module


class TestVersionValue:
    def test_string_value(self):
        assert _diagnostics_version_value("4.5.2") == "4.5.2"

    def test_string_is_trimmed(self):
        assert _diagnostics_version_value("  1.2.3\n") == "1.2.3"

    def test_blank_string_is_rejected(self):
        assert _diagnostics_version_value("   ") is None

    def test_module_attribute_is_followed(self):
        inner = make_module("inner", __version__="9.9.9")
        assert _diagnostics_version_value(inner) == "9.9.9"

    def test_module_without_version(self):
        assert _diagnostics_version_value(make_module("bare")) is None

    def test_callable_is_ignored(self):
        assert _diagnostics_version_value(lambda: "1.0") is None

    def test_none_is_ignored(self):
        assert _diagnostics_version_value(None) is None


class TestModuleVersion:
    def test_dunder_version_wins(self):
        module = make_module(__version__="4.5.2", version="0.0.1", VERSION="0.0.2")
        assert _diagnostics_module_version(module, "spotdl") == "4.5.2"

    def test_version_attribute(self):
        module = make_module(VERSION="0.0.2")
        module.version = make_module("inner", __version__="2024.01.01")
        assert _diagnostics_module_version(module, None) == "2024.01.01"

    def test_upper_case_attribute(self):
        assert _diagnostics_module_version(make_module(VERSION="7"), None) == "7"

    def test_metadata_fallback(self):
        assert _diagnostics_module_version(make_module("requests"), "requests") is not None

    def test_metadata_fallback_unknown_distribution(self):
        assert _diagnostics_module_version(make_module(), "not-a-real-dist-xyz") is None

    def test_no_distribution_and_no_attributes(self):
        assert _diagnostics_module_version(make_module(), None) is None


class TestModuleSource:
    def test_file_path(self):
        module = make_module("spotdl")
        assert _diagnostics_module_source(module) == "/site-packages/spotdl/__init__.py"

    def test_missing_file(self):
        module = make_module("spotdl")
        del module.__file__
        assert _diagnostics_module_source(module) == ""

    def test_none_module(self):
        assert _diagnostics_module_source(None) == ""


class TestLibraryDiagnostics:
    def test_reports_the_documented_libraries(self):
        names = [row["name"] for row in library_diagnostics()]
        for expected in ("spotdl", "yt_dlp", "ytmusicapi", "requests", "jev_selector", "ghostify_dl"):
            assert expected in names

    def test_own_modules_report_my_libs_with_a_path(self):
        rows = {row["name"]: row for row in library_diagnostics()}
        for name in ("jev_selector", "ghostify_dl"):
            assert rows[name]["version"] == DIAGNOSTICS_CUSTOM_VERSION
            assert rows[name]["source"].endswith("%s.py" % name)
            assert rows[name]["error"] is None

    def test_installed_library_reports_its_version(self):
        rows = {row["name"]: row for row in library_diagnostics()}
        requests = rows["requests"]
        assert requests["version"] != DIAGNOSTICS_MISSING_VERSION
        assert requests["version"] != DIAGNOSTICS_UNKNOWN_VERSION
        assert requests["source"].endswith(".py")

    def test_missing_module_is_reported_without_raising(self, monkeypatch):
        monkeypatch.setattr(ghostify_dl, "_import", _failing_import)
        rows = {row["name"]: row for row in library_diagnostics()}
        assert rows["spotdl"]["version"] == DIAGNOSTICS_MISSING_VERSION
        assert rows["spotdl"]["source"] == ""
        assert rows["spotdl"]["error"] == "ImportError"

    def test_missing_own_module_reports_not_installed(self, monkeypatch):
        monkeypatch.setattr(ghostify_dl, "_import", _failing_import)
        rows = {row["name"]: row for row in library_diagnostics()}
        assert rows["jev_selector"]["version"] == DIAGNOSTICS_MISSING_VERSION
        assert rows["jev_selector"]["error"] == "ImportError"

    def test_own_module_without_version_still_reports_my_libs(self, monkeypatch):
        monkeypatch.setattr(
            ghostify_dl,
            "_DIAGNOSTIC_MODULES",
            (("jev_selector", None, True),),
        )
        monkeypatch.setattr(
            ghostify_dl, "_import", lambda name: make_module(name, __file__="/app/jev_selector.py")
        )
        rows = library_diagnostics()
        assert rows[0]["version"] == DIAGNOSTICS_CUSTOM_VERSION
        assert rows[0]["source"] == "/app/jev_selector.py"

    def test_unknown_version_when_nothing_is_reported(self, monkeypatch):
        monkeypatch.setattr(
            ghostify_dl,
            "_DIAGNOSTIC_MODULES",
            (("ghost_only", None, False),),
        )
        monkeypatch.setattr(ghostify_dl, "_import", lambda name: make_module(name))
        rows = library_diagnostics()
        assert rows[0]["version"] == DIAGNOSTICS_UNKNOWN_VERSION
        assert rows[0]["error"] is None


class TestLibraryDiagnosticsReport:
    def test_report_carries_the_interpreter_and_rows(self):
        report = library_diagnostics_report()
        assert report["python_version"]
        assert report["implementation"]
        assert isinstance(report["libraries"], list)
        assert all({"name", "version", "source"} <= set(row) for row in report["libraries"])


def _failing_import(name):
    raise ImportError("no module named %s" % name)

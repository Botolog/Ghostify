#!/usr/bin/env python3
"""
Ghostify static security scan — standalone, stdlib-only, no dependencies.

Mirrors the Kotlin `SecurityScan` (components/security-privacy) rule-for-rule
so the same guarantees can be checked on a plain Linux host with no Gradle,
no Android SDK and no Python packages installed. Source of truth for the
*patterns* is SecretRedactor.kt / SecurityScan.kt; keep this file in sync.

Rules (rule ids match the Kotlin component):
  SECRET_IN_SOURCE      credential-shaped value in shipped source      (T-168)
  STORED_SECRET_FIELD   credential-named field in a data class/entity (T-168)
  SPOTIFY_URL_IN_LOG    full Spotify URL reaching a non-LogPolicy log  (T-170)
  CLEARTEXT_HTTP        http:// network literal in shipped code        (T-171)
  CLEARTEXT_MANIFEST    android:usesCleartextTraffic="true"            (T-171)
  EXPORTED_COMPONENT    exported manifest component                    (T-169)
  STORAGE_PERMISSION    broad storage permission in manifest           (T-169)
  DEBUGGABLE            android:debuggable="true"                      (T-169)
  DIRECT_LOG_API        direct android.util.Log usage (WARN, migration) (T-170)

Exit code: 0 when no ERROR finding, 1 otherwise (WARN findings never fail).

Usage:  python3 security_scan.py [repo_root]
"""

from __future__ import annotations

import os
import re
import sys
import xml.etree.ElementTree as ET

SKIP_DIRS = {
    ".git",
    ".gradle",
    "build",
    ".kotlin",
    ".venv",
    ".ruff_cache",
    "node_modules",
    "out",
    "captures",
    ".idea",
    "__pycache__",
}
EXTENSIONS = {
    "kt",
    "kts",
    "java",
    "py",
    "xml",
    "pro",
    "gradle",
    "properties",
    "toml",
    "yml",
    "yaml",
    "json",
    "md",
    "sh",
    "txt",
    "c",
    "cpp",
    "h",
}
TEST_PATH = re.compile(
    r"(^|[/\\])(src[/\\](test|androidTest|jvm-tests|instrumentedTest)|tests)[/\\]"
)

COMMENT_LINE = re.compile(r"^\s*(//|/\*|\*|#|<!--|;)")
HTTP_INSECURE = re.compile(r"\bhttp://", re.I)
HTTP_ALLOWED = re.compile(
    r"http://(?:schemas\.android\.com|www\.w3\.org|w3\.org|apache\.org|www\.apache\.org|"
    r"xmlns\.com|www\.gnu\.org|gnu\.org|localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\]|"
    r"www\.example\.com|example\.com)(?:/|\"|'|\s|$)",
    re.I,
)
FULL_SPOTIFY_URL = re.compile(r"https?://open\.spotify\.com/", re.I)
LOGCAT_CALL = re.compile(
    r"\b(?:android\.util\.)?Log\.(?:v|d|i|w|e|wtf)\(|"
    r"(?:logger|logging)\.(?:v|d|i|w|e|log|info|debug|warning|error|warn)\("
)
# Mirrors Kotlin SecretRedactor PRINT_SINK semantics: the System.out/err sink is
# case-sensitive; the bare `print(`/printf/`print f"` sink is case-insensitive.
# Splitting avoids Python's "global flags not at start of expression" error.
PRINT_SINK_SYSTEM = re.compile(r"\bSystem\.(?:out|err)\.(?:print|println)")
PRINT_SINK_PRINT = re.compile(r"\bprint(?:\s*\(|\s+[f\"]|f\")", re.I)
LOGPOLICY_CALL = re.compile(r"LogPolicy\.(?:log|v|d|i|w|e|debugTrackRef)\(")
DIRECT_LOG_API = re.compile(
    r"android\.util\.Log\b|(?:^|[^a-zA-Z0-9_.])Log\.(?:v|d|i|w|e|wtf)\("
)
DATA_CLASS_MARKER = re.compile(r"data class|@Entity")
SECRET_FIELD = re.compile(
    r"(?i)\b(?:val|var)\s+(?:password|client_secret|clientSecret|client_id|clientId|"
    r"api_key|apiKey|access_token|accessToken|refresh_token|refreshToken|auth_token|"
    r"authToken|private_key|privateKey|secret)\s*(?::|=)"
)

# Mirrors Kotlin SecretRedactor (rule-for-rule): assignment form
# `KEY [:=| to ] VALUE` with a double-quoted / single-quoted / 12+ char bare
# value (Kotlin ASSIGNMENT).
SECRET_ASSIGN = re.compile(
    r"""["']?\b(client[_-]?secret|client[_-]?id|api[_-]?key|apikey|access[_-]?key|access[_-]?token"""
    r"""|refresh[_-]?token|secret|token|password|passwd|passphrase|private[_-]?key|auth[_-]?key)\b["']?"""
    r"""\s*(?::\s*|=\s*|\s+to\s+)\s*(?:"((?:[^"\\]|\\.)*)"|'((?:[^'\\]|\\.)*)'|([A-Za-z0-9+/_.\\-]{12,}))""",
    re.I,
)
BEARER = re.compile(r"(?i)\b(Bearer|Basic)\s+([A-Za-z0-9\-._~+/]+={0,2})")
QUERY_SECRET = re.compile(
    r"([?&](?:access_token|id_token|refresh_token|api_key|apikey|client_secret|code|token|key|auth))=[^&#\s\"']+",
    re.I,
)
PEM_BLOCK = re.compile(
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", re.S
)
PLACEHOLDERS = {
    "",
    "none",
    "null",
    "true",
    "false",
    "0",
    "your-api-key",
    "your-api-key-here",
    "changeme",
    "secret",
    "password",
    "xxx",
    "lorem-ipsum",
    "example",
    "test",
    "spotify",
    "client_id",
    "client_secret",
    "api_key",
    "apikey",
}
# A bare token that is *purely* a lowercase snake_case identifier (no digits, no
# uppercase) — a variable reference, never a credential (Kotlin BARE_SECRET).
BARE_SECRET = re.compile(r"^[a-z_]+$")
# Kotlin EMPTY_LIKE: none/null/false/true/0/empty (optionally + trailing spaces).
EMPTY_LIKE = re.compile(r"(?i)(none|null|false|true|0)?\s*")

ALLOWED_EXPORTED = {"androidx.media3.session.MediaButtonReceiver"}
STORAGE_PERMS = {
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.ACCESS_MEDIA_LOCATION",
}

WARN_RULES = {"DIRECT_LOG_API"}


def looks_like_secret(value: str, quoted: bool) -> bool:
    """Faithful port of Kotlin SecretRedactor.looksLikeSecret."""
    if EMPTY_LIKE.fullmatch(value) is not None:
        return False
    v = value.strip().strip('"').strip("'")
    if len(v) < 4:
        return False
    if v.lower() in PLACEHOLDERS:
        return False
    if quoted:
        return len(v) >= 8
    # Bare token: only a flat credential-shaped string (no field/method access
    # '.' and carries a digit). Avoids `token = AtomicBoolean(...)` /
    # `token = m.groupValues[2]` while still catching `abcXYZ123`.
    if "." in v:
        return False
    if len(v) < 12:
        return False
    if BARE_SECRET.fullmatch(v) is not None:
        return False
    return any(c.isdigit() for c in v)


def find_secrets(text: str):
    """Yields (key, value, start, end) mirroring Kotlin SecretRedactor.find()."""
    for m in SECRET_ASSIGN.finditer(text):
        quoted = m.group(2) is not None or m.group(3) is not None
        value = (
            m.group(2)
            if m.group(2)
            else (m.group(3) if m.group(3) else (m.group(4) or ""))
        )
        if not value:
            continue
        if looks_like_secret(value, quoted):
            if m.group(2):
                start, end = m.start(2), m.end(2)
            elif m.group(3):
                start, end = m.start(3), m.end(3)
            else:
                start, end = m.start(4), m.end(4)
            yield (m.group(1), value, start, end)
    for m in BEARER.finditer(text):
        token = m.group(2)
        if token and looks_like_secret(token, quoted=True):
            yield ("auth", token, m.start(2), m.end(2))
    for m in PEM_BLOCK.finditer(text):
        yield ("private_key", m.group(0), m.start(), m.end())


def strip_allowed(line: str) -> str:
    prev = None
    while prev != line:
        prev = line
        line = HTTP_ALLOWED.sub("", line)
    return line


def audit_manifest(rel: str, findings):
    try:
        tree = ET.parse(rel)
    except ET.ParseError:
        findings.append(("EXPORTED_COMPONENT", rel, 1, "manifest not parseable"))
        return
    app = tree.getroot().find("application")
    if app is None:
        return
    if app.get("android:usesCleartextTraffic") == "true":
        findings.append(("CLEARTEXT_MANIFEST", rel, 1, "usesCleartextTraffic=true"))
    if app.get("android:debuggable") == "true":
        findings.append(("DEBUGGABLE", rel, 1, "debuggable=true"))
    for perm in tree.getroot().findall("uses-permission"):
        name = perm.get("android:name", "")
        if name in STORAGE_PERMS:
            findings.append(("STORAGE_PERMISSION", rel, 1, name))
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for el in tree.getroot().iter(tag):
            name = el.get("android:name", "?")
            exported = el.get("android:exported")
            is_launcher = tag == "activity" and any(
                fs is not None
                and any(
                    (a.get("android:name") == "android.intent.action.MAIN")
                    for a in fs.findall("action")
                )
                and any(
                    (c.get("android:name") == "android.intent.category.LAUNCHER")
                    for c in fs.findall("category")
                )
                for fs in el.findall("intent-filter")
            )
            allowlisted = name in ALLOWED_EXPORTED
            if exported == "true" and not is_launcher and not allowlisted:
                findings.append(
                    (
                        "EXPORTED_COMPONENT",
                        rel,
                        1,
                        f"{tag} {name} exported without allowlist",
                    )
                )
            if tag == "provider" and exported != "false":
                findings.append(
                    (
                        "EXPORTED_COMPONENT",
                        rel,
                        1,
                        f"provider {name} must be exported=false",
                    )
                )


def scan_file(rel: str, text: str, in_test: bool, findings: list):
    is_manifest = os.path.basename(rel) == "AndroidManifest.xml"
    is_security = "/components/security-privacy/" in rel.replace("\\", "/")

    if is_manifest:
        audit_manifest(rel, findings)

    if not in_test:
        for match in find_secrets(text):
            findings.append(
                ("SECRET_IN_SOURCE", rel, 1, f"possible secret '{match[0]}'")
            )

    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or COMMENT_LINE.match(line) or in_test or is_security:
            continue
        if HTTP_INSECURE.search(strip_allowed(line)):
            findings.append(("CLEARTEXT_HTTP", rel, lineno, "http:// URL"))
        if (
            rel.endswith(".kt")
            and DATA_CLASS_MARKER.search(text)
            and SECRET_FIELD.search(line)
        ):
            findings.append(("STORED_SECRET_FIELD", rel, lineno, line))
        if (
            FULL_SPOTIFY_URL.search(line)
            and not LOGPOLICY_CALL.search(line)
            and (
                LOGCAT_CALL.search(line)
                or PRINT_SINK_SYSTEM.search(line)
                or PRINT_SINK_PRINT.search(line)
            )
        ):
            findings.append(
                (
                    "SPOTIFY_URL_IN_LOG",
                    rel,
                    lineno,
                    "full Spotify URL to a non-LogPolicy logger",
                )
            )
        if DIRECT_LOG_API.search(line):
            findings.append(("DIRECT_LOG_API", rel, lineno, "use LogPolicy"))


def scan(root: str):
    findings = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            ext = fn.rsplit(".", 1)[-1].lower() if "." in fn else ""
            if ext not in EXTENSIONS:
                continue
            rel = os.path.join(dirpath, fn)
            try:
                with open(rel, "r", errors="replace") as fh:
                    text = fh.read()
            except OSError:
                continue
            in_test = bool(TEST_PATH.search(rel.replace("\\", "/")))
            scan_file(rel, text, in_test, findings)
    return sorted(findings, key=lambda f: (f[1], f[2], f[0]))


def main(argv) -> int:
    root = os.path.abspath(argv[1] if len(argv) > 1 else ".")
    findings = scan(root)
    errors = 0
    for rule, rel, line, detail in findings:
        severity = "WARN" if rule in WARN_RULES else "ERROR"
        if severity == "ERROR":
            errors += 1
        print(f"{severity}\t{rule}\t{rel}:{line}\t{detail}")
    print(f"security-scan: {len(findings)} finding(s), {errors} error(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

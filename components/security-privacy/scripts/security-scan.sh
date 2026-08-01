#!/usr/bin/env sh
# ============================================================================
# Ghostify — security scan entry point.
#
# Runs the standalone Python scanner (no toolchain needed) and, if Gradle is
# available, the component's JVM static tests (SecurityScanTest,
# ManifestAuditTest, plus all unit tests) so the full gate is covered.
#
# Exit code: 0 = clean, 1 = any ERROR finding or failing test.
#
# Usage:  scripts/security-scan.sh [repo_root]
# ============================================================================
set -u

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT="${1:-$(dirname "$DIR")}"          # default: the repo root (../..)
fail=0

echo "== 1/2 Python scan ($ROOT) =="
python3 "$DIR/security_scan.py" "$ROOT"
rc=$?
[ "$rc" -ne 0 ] && fail=1

echo
echo "== 2/2 JVM static tests (security-privacy component) =="
if command -v gradle >/dev/null 2>&1; then
    (cd "$DIR/.." && gradle test --offline --console=plain)
    rc=$?
    [ "$rc" -ne 0 ] && fail=1
else
    echo "gradle not found — skipped JVM tests (Python scan still ran)."
fi

echo
if [ "$fail" -eq 0 ]; then
    echo "security-scan: CLEAN"
else
    echo "security-scan: FAILED"
fi
exit $fail

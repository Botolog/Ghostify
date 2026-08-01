#!/usr/bin/env bash
# Builds the JVM sources and runs the unit tests for the error-handling component.
set -euo pipefail

KOTLINC="${KOTLINC:-/opt/kotlinc/kotlinc/bin/kotlinc}"
DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD="$DIR/.build"
MAIN_SRC="$DIR/src/main/kotlin"
TEST_SRC="$DIR/src/test/kotlin"
# Runtime classpath needs the Kotlin stdlib (modern stdlib is not on the JDK).
STDLIB_DIR="$(dirname "$(dirname "$KOTLINC")")/lib"
STDLIB="$STDLIB_DIR/kotlin-stdlib.jar"
for extra in kotlin-stdlib-jdk7.jar kotlin-stdlib-jdk8.jar; do
  [ -f "$STDLIB_DIR/$extra" ] && STDLIB="$STDLIB:$STDLIB_DIR/$extra"
done

mkdir -p "$BUILD/classes" "$BUILD/test-classes"

echo "[1/3] compiling main sources..."
"$KOTLINC" -jvm-target 17 -d "$BUILD/classes" $(find "$MAIN_SRC" -name '*.kt')

echo "[2/3] compiling test sources..."
"$KOTLINC" -jvm-target 17 -cp "$BUILD/classes" -d "$BUILD/test-classes" $(find "$TEST_SRC" -name '*.kt')

echo "[3/3] running tests..."
java -cp "$STDLIB:$BUILD/classes:$BUILD/test-classes" com.ghostify.test.TestMainKt

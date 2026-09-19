#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$REPO_DIR/app"
LOCAL_PROPS="$APP_DIR/local.properties"

# --- Detect Android SDK ---
find_sdk() {
  # 1. Environment variables
  for var in ANDROID_HOME ANDROID_SDK_ROOT; do
    if [[ -n "${!var:-}" && -d "${!var}" ]]; then
      echo "${!var}"
      return
    fi
  done

  # 2. Common locations
  for path in \
    "$HOME/Android/Sdk" \
    "$HOME/android-sdk" \
    "$HOME/Library/Android/sdk" \
    "/usr/local/lib/android/sdk" \
    "/opt/android-sdk" \
    "/usr/lib/android-sdk"; do
    if [[ -d "$path" ]]; then
      echo "$path"
      return
    fi
  done

  # 3. Check if sdkmanager or adb is on PATH
  for cmd in sdkmanager adb; do
    if command -v "$cmd" &>/dev/null; then
      local resolved
      resolved="$(command -v "$cmd")"
      # Resolve symlinks and navigate to SDK root
      resolved="$(readlink -f "$resolved")"
      local candidate
      candidate="$(dirname "$(dirname "$resolved")")"
      if [[ -d "$candidate/platforms" || -d "$candidate/build-tools" ]]; then
        echo "$candidate"
        return
      fi
      # Try parent of parent (e.g. cmdline-tools/latest/bin -> SDK root)
      candidate="$(dirname "$candidate")"
      if [[ -d "$candidate/platforms" || -d "$candidate/build-tools" ]]; then
        echo "$candidate"
        return
      fi
    fi
  done

  return 1
}

SDK_DIR="$(find_sdk)" || {
  echo "ERROR: Android SDK not found."
  echo "Set ANDROID_HOME or ANDROID_SDK_ROOT, or install the SDK."
  exit 1
}

echo "Using Android SDK: $SDK_DIR"

# --- Create local.properties if missing ---
if [[ ! -f "$LOCAL_PROPS" ]]; then
  echo "sdk.dir=$SDK_DIR" > "$LOCAL_PROPS"
  echo "Created $LOCAL_PROPS"
else
  echo "local.properties already exists, skipping."
fi

# --- Build ---
echo "Building debug APK..."
cd "$APP_DIR"
./gradlew :app:assembleDebug --no-daemon

echo ""
echo "Build complete. APK at: app/app/build/outputs/apk/debug/app-debug.apk"

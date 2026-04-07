#!/usr/bin/env bash
# Build app/libs/xray.aar via gomobile (same behavior as build-xray-aar.ps1).
#
# Usage:
#   ./scripts/build-xray-aar.bash
#   ./scripts/build-xray-aar.bash "app/libs/xray.aar" 26
#   OUTPUT=dist/xray.aar ANDROID_API=26 ./scripts/build-xray-aar.bash
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE="$(cd "$SCRIPT_DIR/.." && pwd)"

OUTPUT_REL="${OUTPUT:-app/libs/xray.aar}"
ANDROID_API="${ANDROID_API:-26}"
if [[ $# -ge 1 ]]; then OUTPUT_REL="$1"; fi
if [[ $# -ge 2 ]]; then ANDROID_API="$2"; fi

if ! command -v gomobile >/dev/null 2>&1; then
  echo "gomobile is required. Install it with: go install golang.org/x/mobile/cmd/gomobile@latest" >&2
  exit 1
fi

if ! command -v go >/dev/null 2>&1; then
  echo "go is required." >&2
  exit 1
fi

go env >/dev/null
gomobile version >/dev/null

OUTPUT_PATH="$WORKSPACE/$OUTPUT_REL"
OUTPUT_DIR="$(dirname "$OUTPUT_PATH")"
mkdir -p "$OUTPUT_DIR"

(
  cd "$WORKSPACE/xray-go"
  go mod tidy
  gomobile bind \
    -target=android \
    "-androidapi=$ANDROID_API" \
    -o "$OUTPUT_PATH" \
    .
)

echo "AAR generated at $OUTPUT_PATH"

#!/usr/bin/env bash
# Build app/libs/xray.aar via gomobile.
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

if ! command -v go >/dev/null 2>&1; then
  echo "go is required." >&2
  exit 1
fi

OUTPUT_PATH="$WORKSPACE/$OUTPUT_REL"
OUTPUT_DIR="$(dirname "$OUTPUT_PATH")"
mkdir -p "$OUTPUT_DIR"

cd "$WORKSPACE/xray-go"

# Bypass checksum DB for Xray-core (module path lacks the required /vN suffix).
export GONOSUMDB="github.com/xtls/xray-core"

echo "Resolving xray-core..."
go get github.com/xtls/xray-core@main

# Tidy after resolving xray-core so its transitive deps are pruned correctly.
go mod tidy

# Add x/mobile AFTER tidy so tidy doesn't remove it (nothing in our source imports it directly).
echo "Resolving golang.org/x/mobile..."
go get golang.org/x/mobile@latest

# Extract the exact x/mobile version now in go.mod.
# gomobile and gobind MUST be installed at this exact version — a mismatch causes
# gobind to fail with "no Go package in golang.org/x/mobile/bind".
MOBILE_VERSION="$(go list -m golang.org/x/mobile | awk '{print $2}')"
echo "Installing gomobile and gobind at $MOBILE_VERSION..."
go install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION"
go install "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"

echo "Running gomobile bind..."
gomobile bind \
  -target=android \
  "-androidapi=$ANDROID_API" \
  -o "$OUTPUT_PATH" \
  .

echo "AAR generated at $OUTPUT_PATH"

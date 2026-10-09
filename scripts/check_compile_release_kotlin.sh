#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

if [ ! -f gradlew ]; then
  echo "ERROR: gradlew not found in $PROJECT_DIR" >&2
  exit 1
fi

echo "==> Running compileReleaseKotlin only..."
./gradlew :app:compileReleaseKotlin --no-daemon

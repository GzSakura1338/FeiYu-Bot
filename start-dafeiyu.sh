#!/usr/bin/env sh
set -e
cd "$(dirname "$0")/dafeiyu"
chmod +x ./gradlew 2>/dev/null || true
exec ./gradlew run --console=plain -q

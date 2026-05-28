#!/usr/bin/env bash
# Capture a screenshot from the attached Android device and save to
# docs/assets/screenshots/. Usage: ./capture-screenshot.sh <name>
# Example:  ./capture-screenshot.sh phone-01-empty
set -e

if [ -z "$1" ]; then
    echo "usage: $0 <screenshot-name>" >&2
    exit 1
fi

OUT="../docs/assets/screenshots/$1.png"
mkdir -p "$(dirname "$OUT")"
adb exec-out screencap -p > "$OUT"
echo "saved: $OUT  ($(stat -c%s "$OUT") bytes, $(identify -format '%wx%h' "$OUT" 2>/dev/null || echo 'use file/identify to check dims'))"

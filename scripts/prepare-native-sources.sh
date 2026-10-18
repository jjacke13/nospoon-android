#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 2 ]]; then
    echo "Usage: $0 HYPERDHT_SOURCE LIBUDX_SOURCE" >&2
    exit 2
fi
repo_root=$(cd "$(dirname "$0")/.." && pwd)
hyperdht=$(realpath "$1")
libudx=$(realpath "$2")
for file in Native HyperDHT KeyPair Server Stream Types; do
    cmp "$repo_root/app/src/main/java/com/hyperdht/$file.kt" \
        "$hyperdht/wrappers/kotlin/src/main/kotlin/com/hyperdht/$file.kt"
done
mkdir -p "$hyperdht/deps/libudx"
cp -a "$libudx/src" "$libudx/include" "$libudx/LICENSE" "$hyperdht/deps/libudx/"
# shortcut: carry this reviewed NDK-header fix until the pinned upstream includes it.
patch_file="$repo_root/fdroid/patches/android-jni.patch"
if patch --dry-run --batch --forward --fuzz=0 -d "$hyperdht" -p1 < "$patch_file" >/dev/null; then
    patch --batch --forward --fuzz=0 -d "$hyperdht" -p1 < "$patch_file"
else
    # F-Droid can prepare sources both before and inside its build environment.
    patch --dry-run --batch --reverse --fuzz=0 -d "$hyperdht" -p1 < "$patch_file" >/dev/null
    echo "Android JNI patch already applied"
fi

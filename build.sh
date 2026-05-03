#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Check environment
check_env() {
    log_info "Checking environment..."

    if [ -z "$ANDROID_HOME" ]; then
        log_error "ANDROID_HOME not set. Run: nix-shell"
        exit 1
    fi

    if [ ! -d "$ANDROID_HOME" ]; then
        log_error "Android SDK not found at $ANDROID_HOME"
        exit 1
    fi

    # Set JAVA_HOME if not set
    if [ -z "$JAVA_HOME" ]; then
        JAVA_BIN=$(which java 2>/dev/null || echo "")
        if [ -n "$JAVA_BIN" ]; then
            JAVA_HOME=$(dirname $(dirname $(readlink -f $JAVA_BIN)))
            export JAVA_HOME
            log_info "Detected JAVA_HOME=$JAVA_HOME"
        fi
    fi

    log_info "Environment OK"
    log_info "  ANDROID_HOME=$ANDROID_HOME"
}

# Fetch libhyperdht_jni.so from the latest hyperdht-cpp Android CI build.
#
# Source: hyperdht-cpp's `build.yml` workflow uploads
# `hyperdht-android-arm64` (a tarball containing lib/libhyperdht_jni.so
# + the static deps + headers). We extract just the JNI .so.
#
# Override the source branch with HYPERDHT_CI_BRANCH=other-branch.
# Re-download with NOSPOON_FORCE_DOWNLOAD=1.
download_binary() {
    local BINARY_PATH="app/src/main/jniLibs/arm64-v8a/libhyperdht_jni.so"
    local CI_BRANCH="${HYPERDHT_CI_BRANCH:-main}"
    local REPO="jjacke13/hyperdht-cpp"

    if [ -f "$BINARY_PATH" ] && [ -z "$NOSPOON_FORCE_DOWNLOAD" ]; then
        log_info "libhyperdht_jni.so already present (set NOSPOON_FORCE_DOWNLOAD=1 to refresh)"
        return
    fi

    log_info "Fetching latest libhyperdht_jni.so from $REPO ($CI_BRANCH)..."
    mkdir -p "$(dirname "$BINARY_PATH")"

    local tmpdir="/tmp/hyperdht-jni-$$"
    mkdir -p "$tmpdir"
    trap 'rm -rf "$tmpdir"' RETURN

    local RUN_ID
    RUN_ID=$(gh run list \
        --repo "$REPO" \
        --branch "$CI_BRANCH" \
        --status success \
        --limit 5 \
        --json databaseId,name -q '.[] | select(.name | test("[Bb]uild")) | .databaseId' 2>/dev/null \
        | head -1 || true)

    if [ -z "$RUN_ID" ]; then
        log_error "No successful build found on $REPO/$CI_BRANCH"
        log_error "Check workflow status with:"
        log_error "  gh run list --repo $REPO"
        exit 1
    fi

    log_info "Using CI run $RUN_ID"
    if ! gh run download "$RUN_ID" \
            --repo "$REPO" \
            --name hyperdht-android-arm64 \
            --dir "$tmpdir" 2>&1; then
        log_error "Failed to download hyperdht-android-arm64 artifact from run $RUN_ID"
        exit 1
    fi

    # Artifact is hyperdht-android-arm64.tar.gz containing
    #   lib/libhyperdht_jni.so, lib/libhyperdht.a, lib/libudx.a, …
    # We just need the JNI shared lib.
    local tarball
    tarball=$(find "$tmpdir" -maxdepth 2 -name "hyperdht-android-arm64.tar.gz" | head -1)
    if [ -z "$tarball" ]; then
        log_error "Tarball not found in artifact. Tmpdir contents:"
        find "$tmpdir" >&2
        exit 1
    fi

    tar xzf "$tarball" -C "$tmpdir"
    if [ ! -f "$tmpdir/lib/libhyperdht_jni.so" ]; then
        log_error "lib/libhyperdht_jni.so missing inside artifact tarball"
        find "$tmpdir" >&2
        exit 1
    fi

    cp "$tmpdir/lib/libhyperdht_jni.so" "$BINARY_PATH"
    chmod +x "$BINARY_PATH"
    log_info "Installed: $BINARY_PATH ($(stat -c%s "$BINARY_PATH" 2>/dev/null || stat -f%z "$BINARY_PATH") bytes)"
}

# Build debug APK
build_apk() {
    log_info "Building debug APK..."

    # Generate gradle wrapper if not present
    if [ ! -f "gradlew" ]; then
        log_info "Generating Gradle wrapper..."
        gradle wrapper
        chmod +x gradlew
    fi

    ./gradlew assembleDebug

    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    if [ -f "$APK_PATH" ]; then
        log_info "Build successful!"
        log_info "APK: $(realpath $APK_PATH)"
        cp "$APK_PATH" ./nospoon-debug.apk
        log_info "Copied to: $(realpath ./nospoon-debug.apk)"
    else
        log_error "APK not found at $APK_PATH"
        exit 1
    fi
}

# Main
main() {
    log_info "nospoon Android build script"
    log_info "================================"

    check_env
    download_binary
    build_apk

    log_info "Done!"
}

main "$@"

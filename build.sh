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

# Fetch the latest CI-built nospoon arm64-v8a binary.
#
# Source: GitHub Actions workflow .github/workflows/android.yml on the
# nospoon-cpp branch. The workflow cross-compiles libsodium + libuv +
# hyperdht-cpp + nospoon and uploads `libnospoon.so` as the artifact
# `nospoon-android-arm64`. See the workflow file for the build details.
#
# Override the branch with NOSPOON_CI_BRANCH=other-branch.
# Re-download with NOSPOON_FORCE_DOWNLOAD=1.
download_binary() {
    local BINARY_PATH="app/src/main/jniLibs/arm64-v8a/libnospoon.so"
    local CI_BRANCH="${NOSPOON_CI_BRANCH:-nospoon-cpp}"
    local REPO="jjacke13/nospoon"

    if [ -f "$BINARY_PATH" ] && [ -z "$NOSPOON_FORCE_DOWNLOAD" ]; then
        log_info "libnospoon.so already present (set NOSPOON_FORCE_DOWNLOAD=1 to refresh)"
        return
    fi

    log_info "Fetching latest libnospoon.so from $REPO ($CI_BRANCH)..."
    mkdir -p "$(dirname "$BINARY_PATH")"

    local tmpdir="/tmp/nospoon-android-$$"
    mkdir -p "$tmpdir"
    trap 'rm -rf "$tmpdir"' RETURN

    local RUN_ID
    RUN_ID=$(gh run list \
        --repo "$REPO" \
        --workflow android.yml \
        --branch "$CI_BRANCH" \
        --status success \
        --limit 1 \
        --json databaseId -q '.[0].databaseId' 2>/dev/null || true)

    if [ -z "$RUN_ID" ]; then
        log_error "No successful android.yml run found on $CI_BRANCH"
        log_error "Push to GitHub to trigger one, or check the workflow status:"
        log_error "  gh run list --repo $REPO --workflow android.yml"
        exit 1
    fi

    log_info "Using CI run $RUN_ID"
    if ! gh run download "$RUN_ID" \
            --repo "$REPO" \
            --name nospoon-android-arm64 \
            --dir "$tmpdir" 2>&1; then
        log_error "Failed to download nospoon-android-arm64 artifact from run $RUN_ID"
        exit 1
    fi

    if [ ! -f "$tmpdir/libnospoon.so" ]; then
        log_error "Artifact downloaded but libnospoon.so is missing"
        log_error "Tmpdir contents:"
        ls -la "$tmpdir" >&2
        exit 1
    fi

    cp "$tmpdir/libnospoon.so" "$BINARY_PATH"
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

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

    # Set NDK_HOME if not set
    if [ -z "$ANDROID_NDK_HOME" ]; then
        ANDROID_NDK_HOME=$(ls -d $ANDROID_HOME/ndk/*/ 2>/dev/null | head -1)
        if [ -n "$ANDROID_NDK_HOME" ]; then
            export ANDROID_NDK_HOME
            log_info "Detected ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
        fi
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

# Install JS dependencies
install_deps() {
    log_info "Installing JS dependencies..."
    npm install --legacy-peer-deps
}

# Download bare-kit
download_barekit() {
    if [ -f "app/libs/bare-kit/classes.jar" ] && [ -d "app/libs/bare-kit/jni/arm64-v8a" ]; then
        log_info "bare-kit already exists, skipping download"
        return
    fi

    log_info "Downloading bare-kit..."

    # Create libs directory
    mkdir -p app/libs/bare-kit

    # Download and extract bare-kit from GitHub releases
    local tmpdir="/tmp/barekit-$$"
    mkdir -p "$tmpdir"
    gh release download --repo holepunchto/bare-kit v1.15.2 \
        --pattern "prebuilds.zip" \
        --dir "$tmpdir" || {
        log_error "Failed to download bare-kit. Make sure gh CLI is authenticated:"
        log_error "  gh auth login"
        rm -rf "$tmpdir"
        exit 1
    }

    # Extract Android prebuilds
    mkdir -p app/libs/bare-kit/jni
    unzip -o "$tmpdir/prebuilds.zip" "android/bare-kit/jni/*" "android/bare-kit/classes.jar" -d "$tmpdir/extract" > /dev/null
    mv "$tmpdir/extract/android/bare-kit/jni/"* app/libs/bare-kit/jni/
    mv "$tmpdir/extract/android/bare-kit/classes.jar" app/libs/bare-kit/
    rm -rf "$tmpdir"

    log_info "bare-kit installed to app/libs/bare-kit"
}

# Link native addons
link_addons() {
    log_info "Linking native addons..."

    # Create addons directory
    mkdir -p app/src/main/addons

    # Link bare-kit and its dependencies to the addons directory
    npx bare-link --preset android --out app/src/main/addons

    # Verify addons were linked
    local so_count
    so_count=$(find app/src/main/addons -name '*.so' 2>/dev/null | wc -l)
    if [ "$so_count" -gt 0 ]; then
        log_info "Native addons linked: $so_count .so files"
    else
        log_warn "No .so files in addons directory"
    fi
}

# Bundle JS worklet (embed everything, no linking)
bundle_worklet() {
    log_info "Bundling JS worklet..."

    # Create assets directory if needed
    mkdir -p app/src/main/assets

    # Bundle WITHOUT --linked - embed native addons in the bundle
    npx bare-pack --preset android --out app/src/main/assets/client.bundle worklet/client.js

    # Verify bundle was created
    if [ ! -f "app/src/main/assets/client.bundle" ]; then
        log_error "Failed to create client.bundle"
        exit 1
    fi

    log_info "Bundle created: $(ls -la app/src/main/assets/client.bundle)"
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
    install_deps
    download_barekit
    link_addons
    bundle_worklet
    build_apk

    log_info "Done!"
}

main "$@"

#!/usr/bin/env bash
# For CI/F-Droid hosts with pre-provisioned sources and NDK; performs no downloads.
set -euo pipefail
if [[ $# != 6 ]]; then
    echo "Usage: $0 HYPERDHT_SOURCE SODIUM_SOURCE UV_SOURCE NDK WORK_DIR OUTPUT_SO" >&2
    exit 2
fi
repo_root=$(cd "$(dirname "$0")/.." && pwd)
hyperdht=$(realpath "$1")
sodium=$(realpath "$2")
uv=$(realpath "$3")
ndk=$(realpath "$4")
work=$(realpath -m "$5")
output=$(realpath -m "$6")
ndk_version=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["ndk"])' "$repo_root/fdroid/native-sources.json")
actual_ndk=$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$ndk/source.properties" | tr -d '\r')
[[ "$actual_ndk" == "$ndk_version" ]] || {
    echo "NDK $ndk_version required" >&2; exit 1;
}
for definition in 'MAJOR 1' 'MINOR 51' 'PATCH 0'; do
    grep -Eq "^#define UV_VERSION_${definition}[[:space:]]*$" "$uv/include/uv/version.h"
done
grep -q "^PACKAGE_VERSION='1.0.22'$" "$sodium/configure"
test -f "$hyperdht/deps/libudx/src/udx.c"
bin="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
for tool in aarch64-linux-android26-clang aarch64-linux-android26-clang++ llvm-ar llvm-ranlib llvm-strip llvm-readelf; do
    test -x "$bin/$tool" || { echo "Missing NDK tool: $tool" >&2; exit 1; }
done
# A fresh work directory prevents accidentally packaging an old build's output.
mkdir -p "$(dirname "$work")"
mkdir "$work"
mkdir -p "$work/sodium" "$work/prefix"
(
    cd "$work/sodium"
    CC="$bin/aarch64-linux-android26-clang" AR="$bin/llvm-ar" \
        RANLIB="$bin/llvm-ranlib" CFLAGS="-O2 -fPIC -ffile-prefix-map=$sodium=libsodium" \
        "$sodium/configure" --host=aarch64-linux-android --prefix="$work/prefix" \
        --disable-shared --enable-static --with-pic
    make -j2
    make install
)
cmake -S "$uv" -B "$work/uv" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    "-DCMAKE_C_FLAGS=-ffile-prefix-map=$uv=libuv" \
    -DCMAKE_INSTALL_LIBDIR=lib -DCMAKE_INSTALL_PREFIX="$work/prefix" \
    -DLIBUV_BUILD_SHARED=OFF -DLIBUV_BUILD_TESTS=OFF -DLIBUV_BUILD_BENCH=OFF -DBUILD_TESTING=OFF
cmake --build "$work/uv" --target uv_a --parallel 2
cmake --install "$work/uv"
cmake -S "$repo_root/scripts/native" -B "$work/jni" -G Ninja \
    -DHYPERDHT_SOURCE_DIR="$hyperdht" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
    -DHYPERDHT_BUILD_TESTS=OFF -DHYPERDHT_DEBUG=OFF -DHYPERDHT_EMBEDDED=OFF \
    -DCMAKE_DISABLE_FIND_PACKAGE_PkgConfig=ON -DFETCHCONTENT_FULLY_DISCONNECTED=ON \
    -DSODIUM_INCLUDE_DIR="$work/prefix/include" -DSODIUM_LIBRARY="$work/prefix/lib/libsodium.a" \
    -DUV_INCLUDE_DIR="$work/prefix/include" -DUV_LIBRARY="$work/prefix/lib/libuv.a"
cmake --build "$work/jni" --target hyperdht_jni --parallel 2
"$bin/llvm-strip" --strip-unneeded "$work/jni/libhyperdht_jni.so"
"$bin/llvm-readelf" --file-header --program-headers --dynamic --dyn-syms --notes --wide \
    "$work/jni/libhyperdht_jni.so" > "$work/elf.txt"
python3 "$repo_root/scripts/check-jni-elf.py" "$work/elf.txt" "$repo_root/app/src/main/java/com/hyperdht/Native.kt"
mkdir -p "$(dirname "$output")"
cp "$work/jni/libhyperdht_jni.so" "$output"
sha256sum "$output"

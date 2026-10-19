# Licenses and publication assets

Reviewed on 2026-10-18; the 0.1.3 packaging update retains the same
native revisions, Android runtime dependencies and license texts. The application is distributed
under GPL-3.0-only; [LICENSE](../LICENSE) contains the full text. Original
project artwork and listing material use the same project license. Vendored
components retain their own licenses and attribution notices.

## Runtime inventory

| Component | Revision or version | License evidence |
|---|---|---|
| hyperdht-cpp and Kotlin/JNI bindings | `5587e3f21b086beeb1fa8afdf3cfecc46e1623e5` | LGPL-3.0, upstream LICENSE |
| libudx | `759bf7641347f65a1896d4bc4c3b1606a459f466` | Apache-2.0 LICENSE/NOTICE; BSD-3-Clause windowed filters |
| libsodium | `77e1ce5d6dee871c49ef211222ba18ef0c486bda` | ISC plus per-file BSD-2-Clause, permissive and CC0 notices |
| libuv | `5152db2cbfeb5582e9c27c5ea1dba2cd9e10759b` | MIT LICENSE/LICENSE-extra; per-file ISC and BSD-2-Clause notices |
| NDK static C++ runtime, ABI/unwind/support | r27d `27.3.13750724` | Supplied NOTICE/NOTICE.toolchain collections, including Apache-2.0 with LLVM exception and legacy runtime notices |
| Android/JVM runtime dependencies | 66 unique resolved coordinates | Apache-2.0, declared in published POMs or parent POMs |

The exact Android coordinates and their POM URLs are in
[android-runtime.txt](../app/src/main/assets/licenses/android-runtime.txt).
This inventory comes from `releaseRuntimeClasspath`; duplicate artifact forms
are counted once. JUnit/Robolectric are test dependencies, not APK runtime
components. Build tools use OpenJDK, Gradle/AGP, Kotlin and the official SDK/NDK.
No proprietary scanner, analytics or advertising SDK was found in that graph.

The full native license texts, upstream NOTICE, per-file notices, CC0 text,
application GPL text and source/relink directions are packaged under
`app/src/main/assets/licenses/`. About → Licenses reads these files offline.
BLAKE2's optional multi-license grant is used under CC0. Source-family notice
collections deliberately retain platform variants and code that the final
linker may discard; they do not assert that every listed source is linked.

For the statically linked LGPL library, source/relink directions identify the
complete app and all pinned native repositories, toolchain and public patch.
The JNI source patch carries a dated modification comment. Source availability
and notice maintenance remain part of each release, including when pins change.

## Exact NDK notice provenance

Only the two text members below were fetched from the official
[NDK r27d Linux ZIP](https://dl.google.com/android/repository/android-ndk-r27d-linux.zip),
using bounded HTTP ranges. No compiler was installed or invoked for this audit.
The collections cover runtime/CRT/header attribution; including them does not
mean the complete compiler or SDK is bundled in the app.

| Archive member | Bytes | SHA-256 |
|---|---:|---|
| `android-ndk-r27d/NOTICE` | 522923 | `4d5224d1c0b54ffa88b0dc0088191638fdbb7227835095dfb98f7f66b416a323` |
| `android-ndk-r27d/NOTICE.toolchain` | 806124 | `cbe3237be53c0a819f8df6aac5358fdee848eee3b6571b4e2ca20767ebd7465e` |

## Listing images

The project owner confirmed creating/generating the green spoon artwork.
The listing reuses that icon and the existing empty-list and blank-editor
screenshots. They show no private profiles or keys; the editor address is its
default. The obsolete Google scanner image, connected-profile screenshot and
stale About image are excluded. No fresh phone screenshots or real server
configuration were needed.

## Verification limits

The original source-built CI APK passed fdroidserver 2.4.3's binary scanner
with refreshed signatures. The candidate metadata passed `readmeta`, `lint`
and `rewritemeta`. These are bounded checks, not F-Droid inclusion approval or
an exhaustive historical copyright-provenance investigation. The added
buildserver-container job must pass for the final candidate before release.
For native source/build details see [FDROID.md](FDROID.md).

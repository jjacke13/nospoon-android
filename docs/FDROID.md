# F-Droid native source build

This is a candidate recipe, not an accepted F-Droid release. The manual
**F-Droid source-build rehearsal** GitHub Actions workflow builds an unsigned
arm64 APK using the same native scripts and revisions proposed for F-Droid.
It does not publish, sign, or submit anything. Do not run the native build on
the development laptop; use a provisioned Linux CI/F-Droid host.

Ordinary `build.sh` and Nix development builds still reuse the existing JNI
binary. Their success does not validate this recipe.

## Inputs

`fdroid/native-sources.json` records public Git repositories, full commit IDs
and NDK r27d (`27.3.13750724`). The F-Droid fragment repeats those pins because
F-Droid fetches its own sources; the recipe tests check they agree.

- hyperdht-cpp `5587e3f21b086beeb1fa8afdf3cfecc46e1623e5`
- libudx `759bf7641347f65a1896d4bc4c3b1606a459f466` (the native repo's gitlink)
- libsodium 1.0.22 `77e1ce5d6dee871c49ef211222ba18ef0c486bda`
- libuv 1.51.0 `5152db2cbfeb5582e9c27c5ea1dba2cd9e10759b`

These are candidate build inputs, not evidence that this combination has
passed runtime or security review. Keep libuv in the established 1.51.x line.
All six app Kotlin wrappers must match the pinned native source exactly.
The unused `Native.announce` declaration now matches that source too.

## Shared recipe

The host needs Git, Bash, patch, Python 3, Make, CMake >=3.22, Ninja, JDK 17,
SDK platform 36/build-tools 36.0.0 and the exact NDK above. No downloaded
native binaries or host Java JNI headers are used.

1. Fetch the four pinned sources (CI uses JSON; F-Droid uses `srclibs`).
2. `scripts/prepare-native-sources.sh HYPERDHT LIBUDX` checks the wrappers,
   copies libudx source into the expected directory and applies the reviewed
   Android JNI header patch. Preparation can safely run twice.
3. `scripts/build-jni-from-source.sh HYPERDHT SODIUM UV NDK WORK OUTPUT_SO`
   builds static PIC dependencies and JNI for Android 26/arm64, with two
   compiler jobs. `WORK` must not exist. CMake dependency downloads and native
   tests are disabled; libsodium uses its committed generated `configure`.
4. JNI is stripped with the pinned NDK. Its ELF report must show AArch64,
   16 KiB LOAD alignment, only Android system shared dependencies, and every
   JNI export declared in `Native.kt`. Only then is it copied into `jniLibs`.
5. Gradle packages those exact bytes into an unsigned release APK. CI checks
   byte identity, the sole supported ABI, lack of signing and ZIP alignment.

The patch changes `AttachCurrentThread` to take `JNIEnv**`, as required by
Android's NDK C++ headers. `GetEnv` still takes `void**`. It is intentionally
an Android packaging patch; host OpenJDK headers have a different signature.
The app-owned CMake driver avoids the native repo's prebuilt-wrapper target.

## F-Droid integration

[F-Droid requires builds from public source](https://f-droid.org/en/docs/Inclusion_Policy/).
The CI artifact is verification material, not an input for F-Droid.

`fdroid/native-build.yml` is a fragment for a release's `Builds` entry in
fdroiddata. Add the app's published commit, version and normal app metadata
separately. `subdir: app` is deliberate: F-Droid's Gradle APK discovery runs
there, while scripts locate the repository root themselves.

Copy the proposed definitions in `fdroid/srclibs/` into fdroiddata's `srclibs/`
if they are not already present. Use the existing `libsodium` srclib
(`https://github.com/jedisct1/libsodium.git`); its `autogen.sh` preparation
returns immediately when this revision's generated `configure` exists.
Review existing definitions rather than overwriting them blindly.

As described in the [metadata reference](https://f-droid.org/en/docs/Build_Metadata_Reference/),
`prebuild` prepares source before scanning and source archiving; `build` runs
native compilation afterwards. Do not move compilation into `prebuild`,
add a scanner exception for the JNI binary, or fetch CI artifacts in either
phase. F-Droid resolves `$$HyperDHT$$`, the other srclib paths and `$$NDK$$`.

## Validation and remaining gates

Recipe checks (no NDK/compiler needed):

```sh
python3 -m unittest discover -s scripts/tests -v  # PyYAML needed for metadata checks
shellcheck scripts/*-source*.sh
```

A remote clean source build, then F-Droid's actual scanner/build environment,
remain required. A green GitHub job alone is not F-Droid acceptance or proof
of reproducibility. Test JNI loading, connect/disconnect, traffic and
reconnection on a phone before selecting a release. Full app metadata,
license/dependency review and inclusion submission remain separate work.

[Android's 16 KiB guidance](https://developer.android.com/guide/practices/page-sizes)
requires both compatible ELF segments and APK packaging; the rehearsal checks
both. No signing keys, Play access or CI secrets are required by this job.

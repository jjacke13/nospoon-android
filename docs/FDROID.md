# F-Droid native source build

This is a candidate recipe, not an accepted F-Droid release. The manual
**F-Droid source-build rehearsal** GitHub Actions workflow builds an unsigned
arm64 APK using the same native scripts and revisions proposed for F-Droid.
It does not publish, sign, or submit anything. Do not run the native build on
the development laptop; use a provisioned Linux CI/F-Droid host.

Ordinary `build.sh` and Nix development builds still reuse the existing JNI
binary. Their success does not validate this recipe.

## Inputs

Git submodules pin the native source revisions in this app repository.
Clone with `git clone --recurse-submodules` or initialize an existing checkout
with `git submodule update --init --recursive`. This downloads source only;
it does not compile JNI. `.gitmodules` records public source URLs; Git links
record exact commits. `fdroid/native-sources.json` pins NDK r27d
(`27.3.13750724`).

- `native/hyperdht`: hyperdht-cpp `5587e3f21b086beeb1fa8afdf3cfecc46e1623e5`
- `native/hyperdht/deps/libudx`: nested module `759bf7641347f65a1896d4bc4c3b1606a459f466`
- `native/libsodium`: libsodium 1.0.22 `77e1ce5d6dee871c49ef211222ba18ef0c486bda`
- `native/libuv`: libuv 1.51.0 `5152db2cbfeb5582e9c27c5ea1dba2cd9e10759b`

These are candidate build inputs, not evidence that this combination has
passed runtime or security review. Keep libuv in the established 1.51.x line.
All six app Kotlin wrappers must match the pinned native source exactly.
The unused `Native.announce` declaration now matches that source too.

## Shared recipe

The host needs Git, Bash, patch, Python 3, Make, CMake >=3.22, Ninja, JDK 21,
SDK platform 36/build-tools 36.0.0 and the exact NDK above. No downloaded
native binaries or host Java JNI headers are used.

1. Initialize the pinned submodules recursively (CI and F-Droid both do this).
2. `scripts/prepare-native-sources.sh native/hyperdht` checks the wrappers,
   requires the nested libudx sources and applies the reviewed Android JNI
   header patch. Preparation can safely run twice.
3. `scripts/build-jni-from-source.sh HYPERDHT SODIUM UV NDK WORK OUTPUT_SO`
   builds static PIC dependencies and JNI for Android 26/arm64, with two
   compiler jobs. `WORK` must not exist. CMake dependency downloads and native
   tests are disabled; libsodium uses its committed generated `configure`.
4. Source paths are mapped to stable relative prefixes and GNU build IDs are
   disabled, avoiding metadata differences between CI/F-Droid checkout paths.
   JNI is stripped with the pinned NDK. Its ELF report must show AArch64,
   16 KiB LOAD alignment, no GNU build ID, only Android system shared
   dependencies, and every JNI export declared in `Native.kt`. Only then is
   it copied into `jniLibs`.
5. Gradle packages those exact bytes into an unsigned release APK. CI checks
   byte identity, the sole supported ABI, lack of signing and ZIP alignment.

The patch changes `AttachCurrentThread` to take `JNIEnv**`, as required by
Android's NDK C++ headers. `GetEnv` still takes `void**`. It is intentionally
an Android packaging patch; host OpenJDK headers have a different signature.
The app-owned CMake driver avoids the native repo's prebuilt-wrapper target.

## F-Droid integration

[F-Droid requires builds from public source](https://f-droid.org/en/docs/Inclusion_Policy/).
The CI artifact is verification material, not an input for F-Droid.

`fdroid/metadata/com.nospoon.vpn.yml` is the proposed complete fdroiddata
metadata for version 0.1.3 / code 5. Its tag is a release-preparation placeholder;
replace `commit` with the full validated release SHA before submitting it.
`subdir: app` is deliberate: F-Droid's Gradle APK discovery runs there, while
scripts locate the repository root themselves. `submodules: true` makes
F-Droid fetch the native sources recursively. No custom srclibs are needed.
The recipe removes unused JavaScript package/example metadata and a libuv
Keynote documentation archive before scanning; none is used by the C/C++
build. The remaining source tree is scanned without exclusions.

For developer-signed distribution, the fdroiddata entry must also include
`Binaries` pointing to the versioned release APK and `AllowedAPKSigningKeys`
pinning its public certificate. Sign the CI-built unsigned APK using
build-tools 36 apksigner's `--alignment-preserved` option so signing does not
rewrite ZIP padding. Keep the signed APK and tag immutable. Verify a fresh
source rebuild against that public signature before updating the submission.
No v0.1.2-specific alignment/build-ID workaround belongs in a new release.

As described in the [metadata reference](https://f-droid.org/en/docs/Build_Metadata_Reference/),
`prebuild` prepares source before scanning and source archiving; `build` runs
native compilation afterwards. Do not move compilation into `prebuild`,
add a scanner exception for the JNI binary, or fetch CI artifacts in either
phase. F-Droid resolves `$$NDK$$`; native sources are inside the app checkout.

## Validation and remaining gates

Recipe checks (no NDK/compiler needed):

```sh
python3 -m unittest discover -s scripts/tests -v  # PyYAML needed for metadata checks
shellcheck scripts/*-source*.sh
```

The first shared-script rehearsal passed at app commit `694c912`:
[run 39133709420](https://github.com/jjacke13/nospoon-android/actions/runs/39133709420).
The exact CI-built JNI was installed in a development app and a phone VPN
connection was confirmed. This does not establish handover, sustained traffic,
reproducibility or F-Droid acceptance.

The separate manual **F-Droid metadata and build validation** job runs the
real fdroidserver parser/lint/source-scanner/build commands inside the official
buildserver-trixie container, pinned by digest. It pins fdroidserver and
fdroiddata configuration revisions too. Its wrapper refuses ordinary local
execution; never run native compilation on the development laptop.

In that disposable container, the committed metadata is copied into fdroiddata
and only its build commit is replaced with the exact GitHub run SHA. This tests
the candidate before publishing a release tag. Native sources come from
the app's pinned submodules, and compilation is performed by the metadata's
build command. The container's root account is locked and sudo removed by
fdroidserver's normal `--on-server` path; no host setting changes.

The job uploads logs, test APKs, the effective metadata and the native ELF
report. It does not sign, publish or submit. A passing run is build evidence;
maintainer review remains required. Copy the proposed metadata file
into fdroiddata for a New App merge request after choosing/tagging the validated
release. Do not upload listing screenshots into fdroiddata: the standard
`fastlane/metadata/android/en-US/` tree in this app repo supplies them.

The listing, [license inventory](THIRD-PARTY.md), APK-packaged notices and
source/relink directions are prepared for this candidate. The 0.1.3 remote
validation, signed-release verification and inclusion review remain pending.

[Android's 16 KiB guidance](https://developer.android.com/guide/practices/page-sizes)
requires both compatible ELF segments and APK packaging; the rehearsal checks
both. No signing keys, Play access or CI secrets are required by this job.

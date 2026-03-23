# nospoon Android

Android VPN client for nospoon, using Bare runtime + bare-kit.

## Architecture

```
Kotlin (VpnService)          Bare Worklet (JavaScript)
 - Creates TUN via Builder    - Runs HyperDHT client
 - Manages VPN permission     - Reads/writes TUN fd
 - protect() for DHT sockets  - Handles reconnection
 - UI (connect/disconnect)    - Framing + routing (pure JS)
        |                              |
        +---- IPC (JSON over pipe) ----+
```

## Prerequisites

- Android Studio (or Gradle CLI)
- Node.js (for bare-link and bare-pack)
- GitHub CLI (`gh`) — for downloading bare-kit prebuilds

NDK is **not** required — all native code uses prebuilt binaries.

## Build

### Option A: CLI with Nix (recommended)

```bash
cd android
nix-shell          # provides Android SDK, JDK, Node.js, gh
./build.sh         # does everything, outputs nospoon-debug.apk
```

Or via the flake from the repo root:

```bash
nix develop .#android
cd android && ./build.sh
```

### Option B: CLI without Nix

Ensure `ANDROID_HOME`, `JAVA_HOME` are set and Node.js + `gh` are on PATH, then:

```bash
cd android
./build.sh
```

### Option C: Android Studio

Android Studio only runs Gradle — it does **not** run the pre-build steps
automatically. You must prepare the project before opening it:

#### 1. Install JS dependencies

```bash
cd android
npm install --legacy-peer-deps
```

#### 2. Download bare-kit (classes.jar + native runtime)

Download `prebuilds.zip` from [bare-kit v1.15.2](https://github.com/holepunchto/bare-kit/releases/tag/v1.15.2)
and extract the Android files:

```bash
mkdir -p app/libs/bare-kit/jni
gh release download --repo holepunchto/bare-kit v1.15.2 --pattern "prebuilds.zip" --dir /tmp
unzip -o /tmp/prebuilds.zip "android/bare-kit/jni/*" "android/bare-kit/classes.jar" -d /tmp/barekit
mv /tmp/barekit/android/bare-kit/jni/* app/libs/bare-kit/jni/
mv /tmp/barekit/android/bare-kit/classes.jar app/libs/bare-kit/
rm -rf /tmp/barekit /tmp/prebuilds.zip
```

Result — these paths must exist:

```
app/libs/bare-kit/
├── classes.jar
└── jni/
    ├── arm64-v8a/
    │   ├── libbare-kit.so
    │   └── libc++_shared.so
    ├── armeabi-v7a/
    │   └── ...
    ├── x86/
    │   └── ...
    └── x86_64/
        └── ...
```

#### 3. Link native addons

```bash
npx bare-link --preset android --out app/src/main/addons
```

Result — `.so` files for each architecture in:

```
app/src/main/addons/
├── arm64-v8a/
│   ├── libbare-buffer.3.6.0.so
│   ├── libbare-fs.4.5.5.so
│   ├── libsodium-native.5.1.0.so
│   ├── libudx-native.1.19.2.so
│   └── ...
├── armeabi-v7a/
│   └── ...
├── x86/
│   └── ...
└── x86_64/
    └── ...
```

#### 4. Bundle JS worklet

```bash
npx bare-pack --preset android --out app/src/main/assets/client.bundle worklet/client.js
```

Result — single file at:

```
app/src/main/assets/client.bundle
```

#### 5. Open in Android Studio

Open the `android/` directory in Android Studio. Build and run normally.

## IPC Protocol

JSON messages delimited by newlines, over bare-kit IPC pipe.

### Kotlin -> Worklet

| type | fields | description |
|------|--------|-------------|
| `start` | `tunFd`, `serverKey`, `ip`, `seed?` | Start VPN connection |
| `stop` | | Disconnect and shut down |

### Worklet -> Kotlin

| type | fields | description |
|------|--------|-------------|
| `status` | `connected` | Connection state changed |
| `protect` | `fd` | Request socket protection from VPN |
| `identity` | `publicKey` | Client public key (auth mode) |
| `error` | `message` | Error occurred |
| `stopped` | | Worklet has shut down |

# nospoon Android

Android VPN client for nospoon, using Bare runtime + bare-kit.

## Architecture

```
Kotlin (VpnService)          Bare Worklet (JavaScript)
 - Creates TUN via Builder    - Runs HyperDHT client
 - Manages VPN permission     - Reads/writes TUN fd
 - protect() for DHT sockets  - Handles reconnection
 - UI (config list, editor)   - Framing (pure JS)
        |                              |
        +---- IPC (JSON over pipe) ----+
```

### Two-Phase Startup

The tunnel uses a two-phase startup to avoid a routing deadlock:

1. **Phase 1 (DHT connect):** Worklet creates a HyperDHT instance and connects
   to the server over the regular internet (no VPN routes yet). The DHT socket
   is protected via `VpnService.protect()` so it bypasses VPN routing.

2. **Phase 2 (TUN establish):** Once the DHT connection is open, Kotlin calls
   `VpnService.Builder.establish()` to create the TUN interface and sends the
   fd to the worklet. Packet forwarding begins.

This ensures the DHT can always reach the internet, even in full-tunnel mode.

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

#### 4. Bundle JS worklet

```bash
npx bare-pack --preset android --out app/src/main/assets/client.bundle worklet/client.js
```

#### 5. Open in Android Studio

Open the `android/` directory in Android Studio. Build and run normally.

## IPC Protocol

JSON messages delimited by newlines, over bare-kit IPC pipe.

### Kotlin -> Worklet

| type | fields | description |
|------|--------|-------------|
| `start` | `config` (full config JSON object) | Start DHT connection (phase 1) |
| `tun` | `tunFd` | TUN fd ready, start packet forwarding (phase 2) |
| `protected` | `fd`, `ok` | Confirm socket protection result |
| `stop` | | Disconnect and shut down |

### Worklet -> Kotlin

| type | fields | description |
|------|--------|-------------|
| `ready` | | IPC initialized, ready to receive `start` |
| `connected` | | DHT connected, request VPN establishment |
| `status` | `connected` | Connection state changed |
| `protect` | `fd`, `port` | Request socket protection from VPN |
| `identity` | `publicKey` | Client public key (auth mode) |
| `error` | `message` | Error occurred |
| `stopped` | | Worklet has shut down |

### Config Object

The `start` message includes a full config object matching the desktop schema:

```json
{
  "mode": "client",
  "server": "<64-hex-chars>",
  "ip": "10.0.0.2/24",
  "seed": "<64-hex-chars>",
  "mtu": 1400,
  "fullTunnel": false
}
```

The worklet reads `server` and `seed` from the config. Kotlin reads `ip`, `mtu`,
and `fullTunnel` when establishing the VPN interface.

## QR Code Import

The app can scan QR codes containing config JSON to populate all fields at once.
Generate a QR from a config file:

```bash
qrencode -t ANSIUTF8 < config.json
```

Note: strip JSONC comments before encoding (QR scanner expects valid JSON).

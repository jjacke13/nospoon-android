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
- NDK r27+ (for native addons)
- bare-kit prebuilt (download from GitHub releases)

## Setup

### 1. Install JS dependencies

```bash
cd android
npm install
```

### 2. Download bare-kit prebuilt

```bash
gh release download --repo holepunchto/bare-kit v1.15.2
unzip android.zip -d app/libs/bare-kit
```

### 3. Link native addons (udx-native, sodium-native)

```bash
npm run link
```

### 4. Bundle JS worklet

```bash
npm run pack
```

### 5. Build APK

```bash
./gradlew assembleDebug
```

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

## Known Unknowns

- **TUN fd on Bare**: `bare-fs` streams on a TUN fd may need a native
  addon for packet-oriented I/O (TUN is not a regular file)
- **DHT socket fds**: Getting the actual socket fd from udx-native for
  `protect()` needs investigation
- **Foreground service**: Android requires VPN services to run as
  foreground services with a notification (not yet implemented)

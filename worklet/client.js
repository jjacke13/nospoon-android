// Android VPN worklet — runs inside Bare runtime
// Two-phase startup: connect DHT first (over regular internet),
// then receive TUN fd after Kotlin establishes the VPN.
// This avoids a routing deadlock where VPN routes would block
// the DHT from reaching the internet to find the server.

/* global BareKit */

const HyperDHT = require('hyperdht')
const { encode, createDecoder, startKeepalive } = require('./framing')

const INITIAL_RETRY_MS = 1000
const MAX_RETRY_MS = 30000

// IPC protocol: JSON messages delimited by newlines
const ipc = BareKit.IPC

const MAX_FAILURES_BEFORE_RESTART = 3

let dht = null
let activeConnection = null
let tunReader = null
let tunWriter = null
let shuttingDown = false
let retryDelay = INITIAL_RETRY_MS
let tunReady = false // true once we receive the TUN fd from Kotlin
let consecutiveFailures = 0
let pendingConnect = null // deferred connect after protect confirmation

// Packet counters for diagnostics
let stats = { tunRead: 0, tunWrite: 0, tunReadErr: 0, tunWriteErr: 0 }

function sendToKotlin (msg) {
  ipc.write(Buffer.from(JSON.stringify(msg) + '\n'))
}

function setupTun (fd) {
  const fs = require('bare-fs')
  const buf = Buffer.alloc(2000) // MTU 1400 + headroom

  // Manual read loop — bare-fs.createReadStream uses uv_fs_read
  // (designed for regular files) which doesn't poll device fds.
  // TUN fds are blocking, so fs.read() blocks in libuv's thread
  // pool until a packet arrives, then fires the callback.
  // Position MUST be -1 (not null) to use read() instead of pread().
  function readLoop () {
    fs.read(fd, buf, 0, buf.length, -1, function (err, n) {
      if (shuttingDown) return
      if (err) {
        stats.tunReadErr++
        sendToKotlin({ type: 'error', message: 'TUN read: ' + err.message })
        // Retry after brief delay
        setTimeout(readLoop, 100)
        return
      }
      if (n > 0) {
        stats.tunRead++
        const packet = Buffer.from(buf.slice(0, n)) // copy before reuse
        if (activeConnection && !activeConnection.destroyed) {
          activeConnection.write(encode(packet))
        }
      }
      readLoop()
    })
  }

  // TUN write: each write() must be exactly one IP packet
  tunWriter = {
    write: function (packet) {
      fs.write(fd, packet, 0, packet.length, null, function (err) {
        if (err) {
          stats.tunWriteErr++
          if (!shuttingDown) {
            sendToKotlin({ type: 'error', message: 'TUN write: ' + err.message })
          }
        }
      })
    }
  }

  // Log packet stats every 5 seconds
  setInterval(function () {
    if (tunReady) {
      sendToKotlin({ type: 'stats', tunRead: stats.tunRead, tunWrite: stats.tunWrite, tunReadErr: stats.tunReadErr, tunWriteErr: stats.tunWriteErr })
    }
  }, 5000)

  tunReady = true
  tunReader = { destroy: function () {} } // placeholder for shutdown
  readLoop()
}

// Get the DHT socket's fd and port for VpnService.protect().
// Bare runtime's UDX doesn't expose .fd and SELinux blocks /proc scanning,
// so we send the port to Kotlin which scans fds with Os.getsockname().
function getProtectInfo () {
  const sock = dht && dht.socket
  if (!sock) return null

  // Try direct .fd first (works on Node.js, not on Bare)
  if (typeof sock.fd === 'number' && sock.fd >= 0) {
    return { fd: sock.fd, port: sock._port || 0 }
  }

  // Send port — Kotlin will find the fd
  if (typeof sock._port === 'number' && sock._port > 0) {
    return { fd: -1, port: sock._port }
  }

  return null
}

function connect (serverKey, connectOpts) {
  const serverPublicKey = Buffer.from(serverKey, 'hex')
  const connection = dht.connect(serverPublicKey, connectOpts)
  activeConnection = connection

  const decode = createDecoder(function (packet) {
    if (tunWriter && tunReady) {
      stats.tunWrite++
      tunWriter.write(packet)
    }
  })

  connection.on('open', function () {
    retryDelay = INITIAL_RETRY_MS
    consecutiveFailures = 0

    // Always protect the DHT socket — fd may have changed after
    // network switch or DHT restart
    const info = getProtectInfo()
    if (info) {
      sendToKotlin({ type: 'protect', fd: info.fd, port: info.port })
    }

    if (!tunReady) {
      // First connection — tell Kotlin to establish the VPN
      sendToKotlin({ type: 'connected' })
    } else {
      // Reconnection — TUN already active, just resume forwarding
      sendToKotlin({ type: 'status', connected: true })
    }

    startKeepalive(connection)
  })

  connection.on('data', function (data) {
    decode(data)
  })

  connection.on('error', function (err) {
    sendToKotlin({ type: 'error', message: err.message })
  })

  connection.on('close', function () {
    activeConnection = null
    if (shuttingDown) return

    consecutiveFailures++
    sendToKotlin({ type: 'status', connected: false })

    // After repeated failures the DHT socket is likely dead
    // (NAT mapping expired, network changed). Restart DHT entirely
    // and protect the new socket before reconnecting.
    if (consecutiveFailures >= MAX_FAILURES_BEFORE_RESTART) {
      restartDht(serverKey, connectOpts)
      return
    }

    const jitter = Math.floor(Math.random() * 1000)
    const delay = retryDelay + jitter

    setTimeout(function () {
      if (!shuttingDown) connect(serverKey, connectOpts)
    }, delay)

    retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS)
  })
}

// Full DHT restart: the old UDP socket is likely dead (NAT expired,
// network changed). Create a fresh DHT, protect its socket, then reconnect.
function restartDht (serverKey, connectOpts) {
  const oldDht = dht
  dht = new HyperDHT()
  try { oldDht.destroy() } catch (e) {}

  retryDelay = INITIAL_RETRY_MS
  consecutiveFailures = 0

  // Protect the new socket before any DHT traffic flows.
  const info = getProtectInfo()
  if (info) {
    sendToKotlin({ type: 'protect', fd: info.fd, port: info.port })
  }

  // Store the deferred connect — triggered by 'protected' confirmation
  // from Kotlin (or fallback timeout if fd wasn't available)
  pendingConnect = function () {
    pendingConnect = null
    connect(serverKey, connectOpts)
  }

  // Fallback: if Kotlin doesn't respond in 500ms, connect anyway
  // (protect may have worked synchronously, or fd wasn't available)
  setTimeout(function () {
    if (pendingConnect) pendingConnect()
  }, 500)
}

function shutdown () {
  if (shuttingDown) return
  shuttingDown = true

  if (activeConnection) activeConnection.end()
  if (tunReader) { try { tunReader.destroy() } catch (e) {} }
  if (tunWriter) { try { tunWriter.destroy() } catch (e) {} }
  if (dht) dht.destroy()

  sendToKotlin({ type: 'stopped' })
}

// Handle IPC messages from Kotlin
let ipcBuffer = ''

ipc.on('data', function (data) {
  ipcBuffer += data.toString()
  const lines = ipcBuffer.split('\n')
  ipcBuffer = lines.pop() // keep incomplete line

  for (const line of lines) {
    if (!line.trim()) continue

    let msg
    try {
      msg = JSON.parse(line)
    } catch (e) {
      continue
    }

    if (msg.type === 'start') {
      // Phase 1: create DHT and connect over regular internet (no VPN routes yet)
      dht = new HyperDHT()

      const connectOpts = {}
      if (msg.seed) {
        const seedBuf = Buffer.from(msg.seed, 'hex')
        connectOpts.keyPair = HyperDHT.keyPair(seedBuf)
        sendToKotlin({ type: 'identity', publicKey: connectOpts.keyPair.publicKey.toString('hex') })
      }

      connect(msg.serverKey, connectOpts)
    }

    if (msg.type === 'tun') {
      // Phase 2: VPN is established, start packet forwarding
      setupTun(msg.tunFd)
      sendToKotlin({ type: 'status', connected: true })
    }

    if (msg.type === 'protected') {
      // Kotlin confirmed protect() — proceed with deferred connect
      if (pendingConnect) pendingConnect()
    }

    if (msg.type === 'stop') {
      shutdown()
    }
  }
})

// Signal Kotlin that IPC is ready to receive messages
sendToKotlin({ type: 'ready' })

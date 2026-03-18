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

let dht = null
let activeConnection = null
let tunReader = null
let tunWriter = null
let shuttingDown = false
let retryDelay = INITIAL_RETRY_MS
let tunReady = false // true once we receive the TUN fd from Kotlin

function sendToKotlin (msg) {
  ipc.write(Buffer.from(JSON.stringify(msg) + '\n'))
}

function setupTun (fd) {
  const fs = require('bare-fs')

  tunReader = fs.createReadStream('', { fd, autoClose: false })
  tunWriter = fs.createWriteStream('', {
    fd,
    autoClose: false,
    fs: {
      write: fs.write,
      open: function (_p, _f, _m, cb) { cb(null, fd) },
      close: function (_fd, cb) { cb(null) }
    }
  })

  tunReader.on('data', function (packet) {
    if (activeConnection && !activeConnection.destroyed) {
      activeConnection.write(encode(packet))
    }
  })

  tunReader.on('error', function (err) {
    if (!shuttingDown) {
      sendToKotlin({ type: 'error', message: 'TUN read error: ' + err.message })
    }
  })

  tunReady = true
}

// Try to extract the native socket fd for VpnService.protect().
// The fd path depends on the runtime — try several known locations.
function getSocketFd (connection) {
  const rawStream = connection.rawStream
  if (rawStream && rawStream.socket) {
    if (typeof rawStream.socket.fd === 'number') return rawStream.socket.fd
    const handle = rawStream.socket._handle
    if (handle && typeof handle.fd === 'number') return handle.fd
  }
  if (dht && dht.socket) {
    if (typeof dht.socket.fd === 'number') return dht.socket.fd
    const handle = dht.socket._handle
    if (handle && typeof handle.fd === 'number') return handle.fd
  }
  return null
}

function connect (serverKey, connectOpts) {
  const serverPublicKey = Buffer.from(serverKey, 'hex')
  const connection = dht.connect(serverPublicKey, connectOpts)
  activeConnection = connection

  const decode = createDecoder(function (packet) {
    if (tunWriter && tunReady) {
      tunWriter.write(packet)
    }
  })

  connection.on('open', function () {
    retryDelay = INITIAL_RETRY_MS

    if (!tunReady) {
      // First connection — ask Kotlin to protect the DHT socket,
      // then establish the VPN and send us the TUN fd.
      const fd = getSocketFd(connection)
      if (fd !== null) {
        sendToKotlin({ type: 'protect', fd: fd })
      }
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

    sendToKotlin({ type: 'status', connected: false })

    const jitter = Math.floor(Math.random() * 1000)
    const delay = retryDelay + jitter

    setTimeout(function () {
      if (!shuttingDown) connect(serverKey, connectOpts)
    }, delay)

    retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS)
  })
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

    if (msg.type === 'stop') {
      shutdown()
    }
  }
})

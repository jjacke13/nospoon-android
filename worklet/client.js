// Android VPN worklet — runs inside Bare runtime
// Receives TUN fd and config from Kotlin via IPC, connects to HyperDHT server

/* global Bare, BareKit */

const HyperDHT = require('hyperdht')
const { encode, createDecoder, startKeepalive } = require('./framing')

const INITIAL_RETRY_MS = 1000
const MAX_RETRY_MS = 30000

// IPC protocol: JSON messages delimited by newlines
const ipc = BareKit.IPC

let dht = null
let activeConnection = null
let tunFd = null
let tunReader = null
let tunWriter = null
let shuttingDown = false
let retryDelay = INITIAL_RETRY_MS

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
}

function connect (serverKey, connectOpts) {
  const serverPublicKey = Buffer.from(serverKey, 'hex')
  const connection = dht.connect(serverPublicKey, connectOpts)
  activeConnection = connection

  const decode = createDecoder(function (packet) {
    if (tunWriter) {
      tunWriter.write(packet)
    }
  })

  connection.on('open', function () {
    retryDelay = INITIAL_RETRY_MS
    sendToKotlin({ type: 'status', connected: true })
    startKeepalive(connection)

    // Request protection for the DHT socket fd
    const rawStream = connection.rawStream
    if (rawStream && rawStream.socket && rawStream.socket.fd) {
      sendToKotlin({ type: 'protect', fd: rawStream.socket.fd })
    }
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
      tunFd = msg.tunFd
      setupTun(tunFd)

      dht = new HyperDHT()

      const connectOpts = {}
      if (msg.seed) {
        const seedBuf = Buffer.from(msg.seed, 'hex')
        connectOpts.keyPair = HyperDHT.keyPair(seedBuf)
        sendToKotlin({ type: 'identity', publicKey: connectOpts.keyPair.publicKey.toString('hex') })
      }

      connect(msg.serverKey, connectOpts)
    }

    if (msg.type === 'stop') {
      shutdown()
    }
  }
})

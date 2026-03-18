// Routes packets to the correct client connection based on destination IP.
// Supports both IPv4 and IPv6 packets.

const IPV4_MIN_LENGTH = 20
const IPV6_MIN_LENGTH = 40

function createRouter () {
  // ip string → connection
  const routes = new Map()

  function add (ip, connection) {
    routes.set(ip, connection)
    console.log(`Route added: ${ip} → client ${connection.remotePublicKey.toString('hex').slice(0, 8)}...`)
  }

  function remove (ip) {
    routes.delete(ip)
    console.log(`Route removed: ${ip}`)
  }

  function getByIp (ip) {
    return routes.get(ip)
  }

  function getIpByKey (publicKeyHex) {
    for (const [ip, conn] of routes) {
      if (conn.remotePublicKey.toString('hex') === publicKeyHex) {
        return ip
      }
    }
    return null
  }

  function activeCount () {
    return routes.size
  }

  return { add, remove, getByIp, getIpByKey, activeCount }
}

function readIpVersion (packet) {
  if (packet.length < 1) return null
  return (packet[0] >>> 4) & 0x0f
}

function formatIpv4 (packet, offset) {
  return `${packet[offset]}.${packet[offset + 1]}.${packet[offset + 2]}.${packet[offset + 3]}`
}

function formatIpv6 (packet, offset) {
  const groups = []
  for (let i = 0; i < 8; i++) {
    const word = (packet[offset + i * 2] << 8) | packet[offset + i * 2 + 1]
    groups.push(word.toString(16))
  }

  // Collapse longest run of consecutive 0 groups into ::
  let bestStart = -1
  let bestLen = 0
  let curStart = -1
  let curLen = 0

  for (let i = 0; i < groups.length; i++) {
    if (groups[i] === '0') {
      if (curStart === -1) curStart = i
      curLen++
      if (curLen > bestLen) {
        bestStart = curStart
        bestLen = curLen
      }
    } else {
      curStart = -1
      curLen = 0
    }
  }

  if (bestLen > 1) {
    const before = groups.slice(0, bestStart)
    const after = groups.slice(bestStart + bestLen)
    const mid = bestStart === 0 || bestStart + bestLen === 8 ? ':' : ''
    return before.join(':') + '::' + mid + after.join(':')
  }

  return groups.join(':')
}

// Read destination IP from an IP packet header (IPv4 or IPv6)
function readDestinationIp (packet) {
  const version = readIpVersion(packet)

  if (version === 4 && packet.length >= IPV4_MIN_LENGTH) {
    return formatIpv4(packet, 16)
  }

  if (version === 6 && packet.length >= IPV6_MIN_LENGTH) {
    return formatIpv6(packet, 24)
  }

  return null
}

// Read source IP from an IP packet header (IPv4 or IPv6)
function readSourceIp (packet) {
  const version = readIpVersion(packet)

  if (version === 4 && packet.length >= IPV4_MIN_LENGTH) {
    return formatIpv4(packet, 12)
  }

  if (version === 6 && packet.length >= IPV6_MIN_LENGTH) {
    return formatIpv6(packet, 8)
  }

  return null
}

module.exports = { createRouter, readDestinationIp, readSourceIp }

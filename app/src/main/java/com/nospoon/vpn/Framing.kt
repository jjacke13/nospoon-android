package com.nospoon.vpn

/**
 * Length-prefixed framing over the SecretStream-encrypted DHT connection.
 * Wire format identical to cpp/framing.hpp:
 *   - frame: [4-byte big-endian length][payload]
 *   - keepalive: [00 00 00 00] (zero-length frame, every 25 s)
 *
 * Bounds match the cpp side so a peer running either implementation
 * recovers from corrupt or hostile input the same way:
 *   - frame length > 65535         → reset buffer (corrupt stream)
 *   - accumulated buffer > 256 KB  → reset buffer (incomplete frame
 *                                    that never finishes)
 */
object Framing {
    const val MAX_FRAME_SIZE = 65535
    const val MAX_BUFFER_SIZE = 256 * 1024

    /** Prepend a 4-byte big-endian length header to [payload]. */
    fun encode(payload: ByteArray, off: Int = 0, len: Int = payload.size): ByteArray {
        val out = ByteArray(4 + len)
        out[0] = (len ushr 24 and 0xFF).toByte()
        out[1] = (len ushr 16 and 0xFF).toByte()
        out[2] = (len ushr 8 and 0xFF).toByte()
        out[3] = (len and 0xFF).toByte()
        System.arraycopy(payload, off, out, 4, len)
        return out
    }

    /** Zero-length frame used as a keepalive. */
    val keepalive: ByteArray = byteArrayOf(0, 0, 0, 0)
}

/**
 * Streaming frame decoder — feed arbitrary chunks, get whole IP packets.
 *
 * Naive but adequate for typical VPN throughput: copies once per chunk
 * append, once per frame extraction. If profiling shows this hot we can
 * swap in a ring buffer; the public API (`feed`/`reset`) stays the same.
 */
class FrameDecoder {
    private var buf: ByteArray = ByteArray(0)

    /** Push bytes through the decoder; calls [onFrame] for each whole packet. */
    fun feed(chunk: ByteArray, onFrame: (ByteArray) -> Unit) {
        // Append.
        val merged = ByteArray(buf.size + chunk.size)
        System.arraycopy(buf, 0, merged, 0, buf.size)
        System.arraycopy(chunk, 0, merged, buf.size, chunk.size)
        buf = merged

        // Buffer overflow guard — incomplete frame that never finished.
        if (buf.size > Framing.MAX_BUFFER_SIZE) {
            buf = ByteArray(0)
            return
        }

        var i = 0
        while (buf.size - i >= 4) {
            val len = ((buf[i].toInt() and 0xFF) shl 24) or
                      ((buf[i + 1].toInt() and 0xFF) shl 16) or
                      ((buf[i + 2].toInt() and 0xFF) shl 8) or
                      (buf[i + 3].toInt() and 0xFF)

            if (len < 0 || len > Framing.MAX_FRAME_SIZE) {
                // Corrupt / hostile length — drop everything we have and
                // wait for the stream to realign.
                buf = ByteArray(0)
                return
            }

            if (buf.size - i < 4 + len) break  // incomplete

            if (len > 0) {
                val packet = ByteArray(len)
                System.arraycopy(buf, i + 4, packet, 0, len)
                onFrame(packet)
            }
            i += 4 + len
        }

        // Drop the consumed prefix.
        if (i > 0) {
            val rest = ByteArray(buf.size - i)
            System.arraycopy(buf, i, rest, 0, rest.size)
            buf = rest
        }
    }

    /** Drop any partial frame state. */
    fun reset() { buf = ByteArray(0) }
}

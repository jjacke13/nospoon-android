package com.nospoon.vpn

import org.json.JSONObject
import java.util.UUID

data class VpnConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val server: String = "",
    val seed: String? = null,
    val ip: String = "10.0.0.2/24",
    val mtu: Int = 1400,
    val fullTunnel: Boolean = false
) {
    fun displayName(): String {
        return name.ifEmpty {
            if (server.length >= 8) server.take(8) + "..." else server
        }
    }

    fun displayServerKey(): String {
        return if (server.length >= 16) {
            server.take(8) + "..." + server.takeLast(8)
        } else {
            server
        }
    }

    /** Produce config-schema-compatible JSON (matches desktop config file format). */
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("mode", "client")
        obj.put("server", server)
        obj.put("ip", ip)
        obj.put("mtu", mtu)
        obj.put("fullTunnel", fullTunnel)
        seed?.let { obj.put("seed", it) }
        return obj
    }

    companion object {
        /** Parse a config-schema-compatible JSON object (e.g. from QR import). */
        fun fromJson(json: JSONObject, id: String = UUID.randomUUID().toString(), name: String = ""): VpnConfig {
            return VpnConfig(
                id = id,
                name = name,
                server = json.getString("server"),
                seed = json.optString("seed", "").ifEmpty { null },
                ip = json.optString("ip", "10.0.0.2/24"),
                mtu = json.optInt("mtu", 1400),
                fullTunnel = json.optBoolean("fullTunnel", false)
            )
        }
    }
}

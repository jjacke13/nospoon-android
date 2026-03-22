package com.nospoon.vpn

import java.util.UUID

data class VpnConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var serverKey: String = "",
    var seed: String? = null,
    var ip: String = "10.0.0.2/24",
    var fullTunnel: Boolean = true
) {
    fun displayName(): String {
        return name.ifEmpty {
            if (serverKey.length >= 8) serverKey.take(8) + "..." else serverKey
        }
    }

    fun displayServerKey(): String {
        return if (serverKey.length >= 16) {
            serverKey.take(8) + "..." + serverKey.takeLast(8)
        } else {
            serverKey
        }
    }
}

package com.nospoon.vpn

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class VpnConfigRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "nospoon_configs"
        private const val KEY_CONFIGS = "vpn_configs"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadAll(): List<VpnConfig> {
        val json = prefs.getString(KEY_CONFIGS, null) ?: return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val server = if (obj.has("server")) obj.getString("server") else obj.getString("serverKey")
            VpnConfig(
                id = obj.getString("id"),
                name = obj.optString("name", ""),
                server = server,
                seed = obj.optString("seed", "").ifEmpty { null },
                ip = obj.optString("ip", "10.0.0.2/24"),
                mtu = obj.optInt("mtu", 1400),
                fullTunnel = obj.optBoolean("fullTunnel", false)
            )
        }
    }

    fun save(config: VpnConfig) {
        val configs = loadAll()
        val updated = if (configs.any { it.id == config.id }) {
            configs.map { if (it.id == config.id) config else it }
        } else {
            configs + config
        }
        saveAll(updated)
    }

    fun delete(configId: String) {
        saveAll(loadAll().filter { it.id != configId })
    }

    fun getById(configId: String): VpnConfig? {
        return loadAll().find { it.id == configId }
    }

    private fun saveAll(configs: List<VpnConfig>) {
        val array = JSONArray()
        for (config in configs) {
            array.put(JSONObject().apply {
                put("id", config.id)
                put("name", config.name)
                put("server", config.server)
                put("seed", config.seed ?: "")
                put("ip", config.ip)
                put("mtu", config.mtu)
                put("fullTunnel", config.fullTunnel)
            })
        }
        prefs.edit().putString(KEY_CONFIGS, array.toString()).apply()
    }
}

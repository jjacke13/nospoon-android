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
        val configs = mutableListOf<VpnConfig>()
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            configs.add(VpnConfig(
                id = obj.getString("id"),
                name = obj.optString("name", ""),
                serverKey = obj.getString("serverKey"),
                seed = obj.optString("seed", "").ifEmpty { null },
                ip = obj.optString("ip", "10.0.0.2/24")
            ))
        }
        return configs
    }

    fun save(config: VpnConfig) {
        val configs = loadAll().toMutableList()
        val index = configs.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            configs[index] = config
        } else {
            configs.add(config)
        }
        saveAll(configs)
    }

    fun delete(configId: String) {
        val configs = loadAll().toMutableList()
        configs.removeAll { it.id == configId }
        saveAll(configs)
    }

    fun getById(configId: String): VpnConfig? {
        return loadAll().find { it.id == configId }
    }

    private fun saveAll(configs: List<VpnConfig>) {
        val array = JSONArray()
        for (config in configs) {
            val obj = JSONObject()
            obj.put("id", config.id)
            obj.put("name", config.name)
            obj.put("serverKey", config.serverKey)
            obj.put("seed", config.seed ?: "")
            obj.put("ip", config.ip)
            array.put(obj)
        }
        prefs.edit().putString(KEY_CONFIGS, array.toString()).apply()
    }
}

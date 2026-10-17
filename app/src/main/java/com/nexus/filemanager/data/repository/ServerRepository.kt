package com.nexus.filemanager.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.nexus.filemanager.data.model.ServerConfig
import com.nexus.filemanager.data.model.StorageType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class ServerRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nexus_servers", Context.MODE_PRIVATE)

    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    init {
        loadServers()
    }

    private fun loadServers() {
        val jsonString = prefs.getString("saved_servers", "[]") ?: "[]"
        val list = mutableListOf<ServerConfig>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    ServerConfig(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        type = StorageType.valueOf(obj.getString("type")),
                        host = obj.getString("host"),
                        port = obj.getInt("port"),
                        username = obj.optString("username", ""),
                        password = obj.optString("password", ""),
                        shareName = obj.optString("shareName", ""),
                        remotePath = obj.optString("remotePath", "/"),
                        isAnonymous = obj.optBoolean("isAnonymous", false)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _servers.value = list
    }

    fun saveServer(config: ServerConfig) {
        val current = _servers.value.toMutableList()
        val index = current.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            current[index] = config
        } else {
            current.add(config)
        }
        persist(current)
    }

    fun deleteServer(id: String) {
        val current = _servers.value.filter { it.id != id }
        persist(current)
    }

    fun getServer(id: String): ServerConfig? {
        return _servers.value.firstOrNull { it.id == id }
    }

    private fun persist(list: List<ServerConfig>) {
        val jsonArray = JSONArray()
        for (server in list) {
            val obj = JSONObject().apply {
                put("id", server.id)
                put("name", server.name)
                put("type", server.type.name)
                put("host", server.host)
                put("port", server.port)
                put("username", server.username)
                put("password", server.password)
                put("shareName", server.shareName)
                put("remotePath", server.remotePath)
                put("isAnonymous", server.isAnonymous)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString("saved_servers", jsonArray.toString()).apply()
        _servers.value = list
    }
}

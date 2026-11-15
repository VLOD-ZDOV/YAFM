package com.yafm.filemanager.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.yafm.filemanager.data.model.ServerConfig
import com.yafm.filemanager.data.model.StorageType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class ServerRepository(context: Context, private val onChanged: (String) -> Unit = {}) {
    private val prefs: SharedPreferences = context.getSharedPreferences("servers", Context.MODE_PRIVATE)

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
                        password = readPassword(obj),
                        shareName = obj.optString("shareName", ""),
                        remotePath = obj.optString("remotePath", ""),
                        isAnonymous = obj.optBoolean("isAnonymous", false),
                        keyPath = obj.optString("keyPath", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _servers.value = list
        // Older versions stored passwords in clear text: rewrite them encrypted.
        if (jsonString.contains("\"password\"")) persist(list)
    }

    private fun readPassword(obj: JSONObject): String =
        if (obj.has("passwordEnc")) {
            runCatching { SecretBox.decrypt(obj.getString("passwordEnc")) }.getOrDefault("")
        } else {
            obj.optString("password", "")
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
        onChanged(config.id)
    }

    fun deleteServer(id: String) {
        // An imported key goes with its server.
        getServer(id)?.keyPath?.takeIf { it.isNotEmpty() }?.let { java.io.File(it).delete() }
        val current = _servers.value.filter { it.id != id }
        persist(current)
        onChanged(id)
    }

    /** Moves a server up ([by] < 0) or down the list. */
    fun move(id: String, by: Int) {
        val list = _servers.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + by).coerceIn(0, list.size - 1)
        if (to == from) return
        list.add(to, list.removeAt(from))
        persist(list)
    }

    /** A copy under a new id, right after the original; an imported key gets its own copy, as delete removes it. */
    fun duplicate(id: String, name: String): ServerConfig? {
        val src = getServer(id) ?: return null
        var copy = src.copy(id = java.util.UUID.randomUUID().toString(), name = name)
        if (src.keyPath.isNotEmpty()) {
            val key = java.io.File(src.keyPath)
            val dest = java.io.File(key.parentFile, "${copy.id}.key")
            copy = if (runCatching { key.copyTo(dest, overwrite = true) }.isSuccess) {
                runCatching { android.system.Os.chmod(dest.path, "600".toInt(8)) }
                copy.copy(keyPath = dest.path)
            } else copy.copy(keyPath = "")
        }
        val list = _servers.value.toMutableList()
        list.add(list.indexOfFirst { it.id == id } + 1, copy)
        persist(list)
        return copy
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
                put("passwordEnc", SecretBox.encrypt(server.password))
                put("shareName", server.shareName)
                put("remotePath", server.remotePath)
                put("isAnonymous", server.isAnonymous)
                put("keyPath", server.keyPath)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString("saved_servers", jsonArray.toString()).apply()
        _servers.value = list
    }
}

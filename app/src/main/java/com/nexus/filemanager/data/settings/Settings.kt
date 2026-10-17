package com.nexus.filemanager.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/** What opening a video does. */
enum class VideoPlayer { ASK, BUILT_IN, SYSTEM }

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val videoPlayer: VideoPlayer = VideoPlayer.ASK,
    val autoUpdate: Boolean = true,
    val showHidden: Boolean = false,
    val gridView: Boolean = false
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load() = Settings(
        theme = enumOr(prefs.getString("theme", null), ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean("dynamicColor", true),
        videoPlayer = enumOr(prefs.getString("videoPlayer", null), VideoPlayer.ASK),
        autoUpdate = prefs.getBoolean("autoUpdate", true),
        showHidden = prefs.getBoolean("showHidden", false),
        gridView = prefs.getBoolean("gridView", false)
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit()
            .putString("theme", s.theme.name)
            .putBoolean("dynamicColor", s.dynamicColor)
            .putString("videoPlayer", s.videoPlayer.name)
            .putBoolean("autoUpdate", s.autoUpdate)
            .putBoolean("showHidden", s.showHidden)
            .putBoolean("gridView", s.gridView)
            .apply()
    }
}

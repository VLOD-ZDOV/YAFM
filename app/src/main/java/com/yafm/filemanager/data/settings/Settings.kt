package com.yafm.filemanager.data.settings

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
    val gridView: Boolean = false,
    /** Seek buttons and double taps in the video player, in seconds. */
    val seekSeconds: Int = 10,
    /** Brightness (left) and volume (right) swipes in the video player. */
    val videoSideGestures: Boolean = false,
    /** Folder headers folded in the categories, as "CATEGORY:folder". */
    val collapsedFolders: Set<String> = emptySet(),
    /** Shows tools for people who know what they do: creating links. */
    val advanced: Boolean = false,
    /** Favorite folders, in the order added, as "TYPE\tserverId\tpath". */
    val favorites: List<String> = emptyList()
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
        gridView = prefs.getBoolean("gridView", false),
        seekSeconds = prefs.getInt("seekSeconds", 10),
        videoSideGestures = prefs.getBoolean("videoSideGestures", false),
        collapsedFolders = prefs.getStringSet("collapsedFolders", null)?.toSet() ?: emptySet(),
        advanced = prefs.getBoolean("advanced", false),
        favorites = prefs.getString("favorites", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
    )

    // Per-folder choices (sort, view, file types) live apart from the settings: there can be many of them.
    private val folders = context.getSharedPreferences("folder_sort", Context.MODE_PRIVATE)

    fun folderPref(key: String): String? = folders.getString(key, null)

    /** Null forgets the folder's choice. */
    fun setFolderPref(key: String, value: String?) {
        folders.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

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
            .putInt("seekSeconds", s.seekSeconds)
            .putBoolean("videoSideGestures", s.videoSideGestures)
            .putStringSet("collapsedFolders", s.collapsedFolders)
            .putBoolean("advanced", s.advanced)
            .putString("favorites", s.favorites.joinToString("\n"))
            .apply()
    }
}

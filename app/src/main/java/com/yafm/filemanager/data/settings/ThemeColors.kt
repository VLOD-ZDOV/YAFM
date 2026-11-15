package com.yafm.filemanager.data.settings

/** A ready color set; [seed] null: the app's own blue, [DYNAMIC]: the wallpaper's colors (Android 12+). */
enum class Palette(val seed: Long?) {
    DYNAMIC(null),
    BLUE(null),
    GREEN(0xFF22C55E),
    YELLOW(0xFFEAB308),
    ORANGE(0xFFF97316),
    RED(0xFFEF4444),
    PINK(0xFFEC4899),
    PURPLE(0xFF8B5CF6),
    TEAL(0xFF14B8A6),
    CUSTOM(null);

    companion object {
        fun of(name: String?, default: Palette) = entries.firstOrNull { it.name == name } ?: default
    }
}

/** The color roles a custom theme can set by hand; the rest follow from its seed. */
enum class ColorRole {
    PRIMARY, SECONDARY, TERTIARY, BACKGROUND, SURFACE, SURFACE_VARIANT, CARD, TEXT, TEXT_SECONDARY, OUTLINE, ERROR
}

/**
 * The user's own theme: a [seed] color all roles are made from, and roles set by hand, kept apart for
 * the light and the dark look (a dark background would make no sense in the light one).
 */
data class CustomTheme(
    val seed: Int = 0xFF3B82F6.toInt(),
    val light: Map<ColorRole, Int> = emptyMap(),
    val dark: Map<ColorRole, Int> = emptyMap()
) {
    fun encode(): String = buildString {
        append("seed=").append(Integer.toHexString(seed))
        light.forEach { (role, c) -> append(";l.").append(role.name).append('=').append(Integer.toHexString(c)) }
        dark.forEach { (role, c) -> append(";d.").append(role.name).append('=').append(Integer.toHexString(c)) }
    }

    companion object {
        fun decode(text: String?): CustomTheme {
            if (text.isNullOrEmpty()) return CustomTheme()
            var seed = CustomTheme().seed
            val light = mutableMapOf<ColorRole, Int>()
            val dark = mutableMapOf<ColorRole, Int>()
            text.split(';').forEach { part ->
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', "").toLongOrNull(16)?.toInt() ?: return@forEach
                when {
                    key == "seed" -> seed = value
                    key.startsWith("l.") -> ColorRole.entries.firstOrNull { it.name == key.drop(2) }?.let { light[it] = value }
                    key.startsWith("d.") -> ColorRole.entries.firstOrNull { it.name == key.drop(2) }?.let { dark[it] = value }
                }
            }
            return CustomTheme(seed, light, dark)
        }
    }
}

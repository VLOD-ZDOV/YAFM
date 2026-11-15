package com.yafm.filemanager.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.yafm.filemanager.data.settings.ColorRole

/**
 * A full Material color scheme from one color: its hue at fixed lightness steps, much as Material You
 * does, so text stays readable on every surface whichever color is picked.
 */
fun seedScheme(seed: Color, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val h = hsl[0]
    // A grey seed still gives a faintly tinted theme; a neon one is toned down to stay calm.
    val s = hsl[1].coerceIn(0.25f, 0.85f)
    fun c(l: Float, sat: Float = s, hue: Float = h) = Color.hsl(((hue % 360f) + 360f) % 360f, sat.coerceIn(0f, 1f), l)
    val sec = s * 0.4f
    val ter = h + 60f
    val n = s * 0.12f
    val nv = s * 0.22f
    return if (dark) darkColorScheme(
        primary = c(0.72f), onPrimary = c(0.15f), primaryContainer = c(0.28f), onPrimaryContainer = c(0.90f),
        inversePrimary = c(0.40f),
        secondary = c(0.74f, sec), onSecondary = c(0.17f, sec), secondaryContainer = c(0.27f, sec), onSecondaryContainer = c(0.90f, sec),
        tertiary = c(0.74f, s * 0.6f, ter), onTertiary = c(0.17f, s * 0.6f, ter),
        tertiaryContainer = c(0.28f, s * 0.5f, ter), onTertiaryContainer = c(0.90f, s * 0.5f, ter),
        background = c(0.07f, n), onBackground = c(0.91f, n),
        surface = c(0.07f, n), onSurface = c(0.91f, n),
        surfaceVariant = c(0.22f, nv), onSurfaceVariant = c(0.80f, nv),
        surfaceTint = c(0.72f),
        inverseSurface = c(0.90f, n), inverseOnSurface = c(0.15f, n),
        outline = c(0.58f, nv), outlineVariant = c(0.30f, nv),
        surfaceBright = c(0.24f, n), surfaceDim = c(0.07f, n),
        surfaceContainerLowest = c(0.05f, n), surfaceContainerLow = c(0.10f, n), surfaceContainer = c(0.12f, n),
        surfaceContainerHigh = c(0.16f, n), surfaceContainerHighest = c(0.20f, n)
    ) else run {
    // Accents are also text on a white page: yellow or green at the usual step is too faint, so they are
    // darkened until the contrast reaches 4.5:1 (the usual minimum for reading).
    fun readableL(sat: Float, hue: Float): Float {
        var l = 0.40f
        while (l > 0.15f && ColorUtils.calculateContrast(c(l, sat, hue).toArgb(), android.graphics.Color.WHITE) < 4.5) l -= 0.02f
        return l
    }
    val pl = readableL(s, h)
    val sl = readableL(sec, h)
    val tl = readableL(s * 0.6f, ter)
    lightColorScheme(
        // White on yellow or green at this lightness is too faint: text picks black or white by contrast.
        primary = c(pl), onPrimary = readableOn(c(pl)), primaryContainer = c(0.90f), onPrimaryContainer = c(0.12f),
        inversePrimary = c(0.75f),
        secondary = c(sl, sec), onSecondary = readableOn(c(sl, sec)), secondaryContainer = c(0.90f, sec), onSecondaryContainer = c(0.12f, sec),
        tertiary = c(tl, s * 0.6f, ter), onTertiary = readableOn(c(tl, s * 0.6f, ter)),
        tertiaryContainer = c(0.90f, s * 0.5f, ter), onTertiaryContainer = c(0.12f, s * 0.5f, ter),
        background = c(0.98f, n), onBackground = c(0.10f, n),
        surface = c(0.98f, n), onSurface = c(0.10f, n),
        surfaceVariant = c(0.90f, nv), onSurfaceVariant = c(0.30f, nv),
        surfaceTint = c(pl),
        inverseSurface = c(0.18f, n), inverseOnSurface = c(0.95f, n),
        outline = c(0.48f, nv), outlineVariant = c(0.80f, nv),
        surfaceBright = c(0.98f, n), surfaceDim = c(0.86f, n),
        surfaceContainerLowest = Color.White, surfaceContainerLow = c(0.96f, n), surfaceContainer = c(0.94f, n),
        surfaceContainerHigh = c(0.92f, n), surfaceContainerHighest = c(0.90f, n)
    )
    }
}

/** Text that reads on [background]: near black or near white. */
fun readableOn(background: Color): Color =
    if (ColorUtils.calculateLuminance(background.toArgb()) > 0.4) Color(0xFF111111) else Color(0xFFF5F5F5)

/** The scheme with the roles set by hand; text on a hand-set color follows it, so it stays readable. */
fun ColorScheme.withOverrides(roles: Map<ColorRole, Int>): ColorScheme {
    var cs = this
    roles.forEach { (role, argb) ->
        val c = Color(argb)
        cs = when (role) {
            ColorRole.PRIMARY -> cs.copy(primary = c, onPrimary = readableOn(c), surfaceTint = c)
            ColorRole.SECONDARY -> cs.copy(secondary = c, onSecondary = readableOn(c))
            ColorRole.TERTIARY -> cs.copy(tertiary = c, onTertiary = readableOn(c))
            // Text follows a hand-set background unless the text color is set by hand too.
            ColorRole.BACKGROUND -> if (ColorRole.TEXT in roles) cs.copy(background = c) else cs.copy(background = c, onBackground = readableOn(c))
            ColorRole.SURFACE -> if (ColorRole.TEXT in roles) cs.copy(surface = c, surfaceDim = c, surfaceBright = c)
                else cs.copy(surface = c, surfaceDim = c, surfaceBright = c, onSurface = readableOn(c))
            ColorRole.SURFACE_VARIANT -> cs.copy(surfaceVariant = c)
            ColorRole.CARD -> cs.copy(
                surfaceContainer = c, surfaceContainerLow = c, surfaceContainerHigh = c,
                surfaceContainerHighest = c, surfaceContainerLowest = c
            )
            ColorRole.TEXT -> cs.copy(onBackground = c, onSurface = c)
            ColorRole.TEXT_SECONDARY -> cs.copy(onSurfaceVariant = c)
            ColorRole.OUTLINE -> cs.copy(outline = c, outlineVariant = c.copy(alpha = 0.5f))
            ColorRole.ERROR -> cs.copy(error = c, onError = readableOn(c))
        }
    }
    return cs
}

/** What a role currently is in [cs], for the editor's swatches. */
fun ColorScheme.roleColor(role: ColorRole): Color = when (role) {
    ColorRole.PRIMARY -> primary
    ColorRole.SECONDARY -> secondary
    ColorRole.TERTIARY -> tertiary
    ColorRole.BACKGROUND -> background
    ColorRole.SURFACE -> surface
    ColorRole.SURFACE_VARIANT -> surfaceVariant
    ColorRole.CARD -> surfaceContainer
    ColorRole.TEXT -> onSurface
    ColorRole.TEXT_SECONDARY -> onSurfaceVariant
    ColorRole.OUTLINE -> outline
    ColorRole.ERROR -> error
}

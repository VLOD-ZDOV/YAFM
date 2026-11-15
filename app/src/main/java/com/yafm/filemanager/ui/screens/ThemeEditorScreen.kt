package com.yafm.filemanager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.yafm.filemanager.R
import com.yafm.filemanager.data.settings.ColorRole
import com.yafm.filemanager.data.settings.Palette
import com.yafm.filemanager.data.settings.ThemeMode
import com.yafm.filemanager.str
import com.yafm.filemanager.ui.theme.roleColor
import com.yafm.filemanager.ui.viewmodel.FileExplorerViewModel

/** The user's own theme, edited live: the whole app, this screen included, shows each change at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeEditorScreen(viewModel: FileExplorerViewModel, onNavigateBack: () -> Unit) {
    val settings by viewModel.settings.settings.collectAsState()
    val custom = settings.customTheme
    val dark = when (settings.theme) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val overrides = if (dark) custom.dark else custom.light
    var picking by remember { mutableStateOf<ColorRole?>(null) }
    var pickingSeed by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    // Opening the editor is choosing the custom theme. An untouched one starts from the palette in use,
    // so the app looks the same until something is changed.
    LaunchedEffect(Unit) {
        if (settings.palette != Palette.CUSTOM) {
            val seed = settings.palette.seed?.toInt() ?: scheme.primary.toArgb()
            viewModel.settings.update {
                val c = it.customTheme
                val fresh = c.light.isEmpty() && c.dark.isEmpty()
                it.copy(palette = Palette.CUSTOM, customTheme = if (fresh) c.copy(seed = seed) else c)
            }
        }
    }
    BackHandler(onBack = onNavigateBack)

    fun setRole(role: ColorRole, argb: Int?) = viewModel.settings.update { s ->
        val c = s.customTheme
        val map = (if (dark) c.dark else c.light).toMutableMap().apply { if (argb == null) remove(role) else put(role, argb) }
        s.copy(customTheme = if (dark) c.copy(dark = map) else c.copy(light = map))
    }

    if (pickingSeed) {
        ColorPickerDialog(
            initial = Color(custom.seed),
            onDismiss = { pickingSeed = false },
            onPick = { c ->
                pickingSeed = false
                viewModel.settings.update { it.copy(customTheme = it.customTheme.copy(seed = c.toArgb())) }
            }
        )
    }
    picking?.let { role ->
        ColorPickerDialog(
            initial = scheme.roleColor(role),
            onDismiss = { picking = null },
            onPick = { c ->
                picking = null
                setRole(role, c.toArgb())
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(R.string.theme_editor)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.settings.update { it.copy(customTheme = it.customTheme.copy(light = emptyMap(), dark = emptyMap())) } },
                        enabled = custom.light.isNotEmpty() || custom.dark.isNotEmpty()
                    ) { Text(str(R.string.theme_reset_all)) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Which look is edited: the app switches to it, so the change is seen.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to str(R.string.light), true to str(R.string.dark)).forEachIndexed { i, (isDark, label) ->
                    SegmentedButton(
                        selected = dark == isDark,
                        onClick = {
                            viewModel.settings.update {
                                it.copy(theme = if (isDark) (if (it.theme == ThemeMode.AMOLED) ThemeMode.AMOLED else ThemeMode.DARK) else ThemeMode.LIGHT)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, 2)
                    ) { Text(label) }
                }
            }

            Preview()

            Text(str(R.string.theme_base_color), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(str(R.string.theme_base_color_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Swatch(Color(custom.seed)) { pickingSeed = true }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                Palette.entries.mapNotNull { it.seed }.forEach { seed ->
                    Swatch(Color(seed), selected = custom.seed == seed.toInt(), size = 32) {
                        viewModel.settings.update { it.copy(customTheme = it.customTheme.copy(seed = seed.toInt())) }
                    }
                }
            }

            Text(str(R.string.theme_by_hand), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                str(if (dark) R.string.theme_by_hand_hint_dark else R.string.theme_by_hand_hint_light),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            ColorRole.entries.forEach { role ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { picking = role }
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(str(roleName(role)))
                        Text(
                            if (role in overrides) hex(scheme.roleColor(role)) else str(R.string.theme_auto),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    if (role in overrides) {
                        IconButton(onClick = { setRole(role, null) }) {
                            Icon(Icons.Default.Close, contentDescription = str(R.string.theme_reset))
                        }
                    }
                    Swatch(scheme.roleColor(role)) { picking = role }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A little of everything, to see the colors together. */
@Composable
private fun Preview() {
    val scheme = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(str(R.string.theme_sample_title), fontWeight = FontWeight.SemiBold)
                    Text(str(R.string.theme_sample_subtitle), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Switch(checked = true, onCheckedChange = {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) { Text(str(R.string.apply)) }
                OutlinedButton(onClick = {}) { Text(str(R.string.cancel)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = {}, label = { Text("PDF") })
                FilterChip(selected = false, onClick = {}, label = { Text("MP4") })
                Box(Modifier.size(24.dp).clip(CircleShape).background(scheme.tertiary).align(Alignment.CenterVertically))
                Box(Modifier.size(24.dp).clip(CircleShape).background(scheme.secondary).align(Alignment.CenterVertically))
                Box(Modifier.size(24.dp).clip(CircleShape).background(scheme.error).align(Alignment.CenterVertically))
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean = false, size: Int = 40, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
    )
}

private fun roleName(role: ColorRole) = when (role) {
    ColorRole.PRIMARY -> R.string.role_primary
    ColorRole.SECONDARY -> R.string.role_secondary
    ColorRole.TERTIARY -> R.string.role_tertiary
    ColorRole.BACKGROUND -> R.string.role_background
    ColorRole.SURFACE -> R.string.role_surface
    ColorRole.SURFACE_VARIANT -> R.string.role_surface_variant
    ColorRole.CARD -> R.string.role_card
    ColorRole.TEXT -> R.string.role_text
    ColorRole.TEXT_SECONDARY -> R.string.role_text_secondary
    ColorRole.OUTLINE -> R.string.role_outline
    ColorRole.ERROR -> R.string.role_error
}

private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

/** Hue, saturation and lightness sliders, or a typed #RRGGBB. */
@Composable
fun ColorPickerDialog(initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
    val start = remember { FloatArray(3).also { ColorUtils.colorToHSL(initial.toArgb(), it) } }
    var h by remember { mutableFloatStateOf(start[0]) }
    var s by remember { mutableFloatStateOf(start[1]) }
    var l by remember { mutableFloatStateOf(start[2]) }
    val color = Color.hsl(h.coerceIn(0f, 359.99f), s, l)
    var text by remember { mutableStateOf(hex(initial)) }
    LaunchedEffect(h, s, l) {
        val now = hex(color)
        if (!text.equals(now, ignoreCase = true)) text = now
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.theme_pick_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(color)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(8.dp))
                Track(str(R.string.theme_hue), Brush.horizontalGradient((0..6).map { Color.hsl(it * 59.99f, 1f, 0.5f) }))
                Slider(value = h, onValueChange = { h = it }, valueRange = 0f..360f)
                Track(str(R.string.theme_saturation), Brush.horizontalGradient(listOf(Color.hsl(h.coerceIn(0f, 359.99f), 0f, l), Color.hsl(h.coerceIn(0f, 359.99f), 1f, l))))
                Slider(value = s, onValueChange = { s = it })
                Track(str(R.string.theme_lightness), Brush.horizontalGradient(listOf(Color.Black, Color.hsl(h.coerceIn(0f, 359.99f), s, 0.5f), Color.White)))
                Slider(value = l, onValueChange = { l = it })
                OutlinedTextField(
                    value = text,
                    onValueChange = { typed ->
                        text = typed
                        val v = typed.trim().removePrefix("#")
                        if (v.length == 6) v.toLongOrNull(16)?.let { rgb ->
                            val hsl = FloatArray(3)
                            ColorUtils.colorToHSL((0xFF000000 or rgb).toInt(), hsl)
                            h = hsl[0]; s = hsl[1]; l = hsl[2]
                        }
                    },
                    singleLine = true,
                    label = { Text("HEX") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { onPick(color) }) { Text(str(R.string.apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } }
    )
}

@Composable
private fun Track(label: String, brush: Brush) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(brush))
    }
}

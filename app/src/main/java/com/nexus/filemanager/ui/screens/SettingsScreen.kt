package com.nexus.filemanager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexus.filemanager.data.settings.ThemeMode
import com.nexus.filemanager.data.settings.VideoPlayer
import com.nexus.filemanager.ui.viewmodel.FileExplorerViewModel
import com.nexus.filemanager.ui.viewmodel.FileExplorerViewModel.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: FileExplorerViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.settings.collectAsState()
    val update by viewModel.update.collectAsState()
    BackHandler(onBack = onNavigateBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
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
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Section("Оформление")
            listOf(
                ThemeMode.SYSTEM to "Как в системе",
                ThemeMode.LIGHT to "Светлая",
                ThemeMode.DARK to "Тёмная",
                ThemeMode.AMOLED to "AMOLED (чёрная)"
            ).forEach { (mode, label) ->
                Choice(label, settings.theme == mode) { viewModel.settings.update { it.copy(theme = mode) } }
            }
            Toggle("Цвета обоев (Material You)", settings.dynamicColor) { on ->
                viewModel.settings.update { it.copy(dynamicColor = on) }
            }

            Section("Файлы")
            Toggle("Показывать скрытые файлы", settings.showHidden) { viewModel.toggleHiddenFiles() }

            Section("Видео открывать")
            listOf(
                VideoPlayer.ASK to "Спрашивать каждый раз",
                VideoPlayer.BUILT_IN to "Во встроенном плеере",
                VideoPlayer.SYSTEM to "В другом приложении"
            ).forEach { (choice, label) ->
                Choice(label, settings.videoPlayer == choice) { viewModel.settings.update { it.copy(videoPlayer = choice) } }
            }

            Section("Обновления")
            Toggle("Проверять при запуске", settings.autoUpdate) { on ->
                viewModel.settings.update { it.copy(autoUpdate = on) }
            }
            Text(
                "Установлена версия ${viewModel.updater.currentVersion}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            when (val u = update) {
                is UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is UpdateState.Downloading -> LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth())
                is UpdateState.UpToDate -> Text("Это последняя версия", color = MaterialTheme.colorScheme.primary)
                is UpdateState.Failed -> Text("Не удалось проверить: ${u.message}", color = MaterialTheme.colorScheme.error)
                else -> {}
            }
            OutlinedButton(
                onClick = { viewModel.checkForUpdates(manual = true) },
                enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading
            ) { Text("Проверить сейчас") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 4.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

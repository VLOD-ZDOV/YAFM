package com.yafm.filemanager.ui.screens

import android.provider.Settings
import android.os.Build
import android.net.Uri
import android.content.Intent
import com.yafm.filemanager.str
import com.yafm.filemanager.R
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
import androidx.compose.material3.FilterChip
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
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yafm.filemanager.data.settings.ThemeMode
import com.yafm.filemanager.data.settings.VideoPlayer
import com.yafm.filemanager.ui.viewmodel.FileExplorerViewModel
import com.yafm.filemanager.ui.viewmodel.FileExplorerViewModel.UpdateState

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
                title = { Text(str(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
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
            Section(str(R.string.appearance))
            listOf(
                ThemeMode.SYSTEM to str(R.string.system_default),
                ThemeMode.LIGHT to str(R.string.light),
                ThemeMode.DARK to str(R.string.dark),
                ThemeMode.AMOLED to str(R.string.amoled_black)
            ).forEach { (mode, label) ->
                Choice(label, settings.theme == mode) { viewModel.settings.update { it.copy(theme = mode) } }
            }
            Toggle(str(R.string.wallpaper_colors_material_you), settings.dynamicColor) { on ->
                viewModel.settings.update { it.copy(dynamicColor = on) }
            }

            // Android 13+ keeps a language per app in its own settings page.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Section(str(R.string.language))
                val ctx = LocalContext.current
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    )
                }) { Text(str(R.string.choose_app_language)) }
            }

            Section(str(R.string.files))
            Toggle(str(R.string.show_hidden_files_2), settings.showHidden) { viewModel.toggleHiddenFiles() }

            Section(str(R.string.open_videos))
            listOf(
                VideoPlayer.ASK to str(R.string.ask_every_time),
                VideoPlayer.BUILT_IN to str(R.string.in_the_built_in_player),
                VideoPlayer.SYSTEM to str(R.string.in_another_app)
            ).forEach { (choice, label) ->
                Choice(label, settings.videoPlayer == choice) { viewModel.settings.update { it.copy(videoPlayer = choice) } }
            }

            Section(str(R.string.seek_step))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30).forEach { sec ->
                    FilterChip(
                        selected = settings.seekSeconds == sec,
                        onClick = { viewModel.settings.update { it.copy(seekSeconds = sec) } },
                        label = { Text(str(R.string.seconds_n, sec)) }
                    )
                }
            }
            Toggle(str(R.string.video_side_gestures), settings.videoSideGestures) { on ->
                viewModel.settings.update { it.copy(videoSideGestures = on) }
            }

            Section(str(R.string.advanced_user))
            Toggle(str(R.string.advanced_user_hint), settings.advanced) { on ->
                viewModel.settings.update { it.copy(advanced = on) }
            }

            Section(str(R.string.this_phone_s_qsh_key))
            Text(
                str(R.string.qsh_servers_are_signed_in_to_with_this_k),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var publicKey by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            val clipboard = LocalClipboardManager.current
            val context = LocalContext.current
            publicKey?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val key = runCatching { withContext(Dispatchers.IO) { viewModel.repository.qsh.publicKey() } }
                                .getOrElse { e -> str(R.string.error, e.message) }
                            publicKey = key
                            if (!key.startsWith(str(R.string.error_2))) {
                                clipboard.setText(AnnotatedString(key))
                                Toast.makeText(context, str(R.string.key_copied), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = viewModel.repository.qsh.isAvailable
                ) { Text(if (publicKey == null) str(R.string.show_and_copy) else str(R.string.copy_2)) }
            }

            Section(str(R.string.updates))
            Toggle(str(R.string.check_at_startup), settings.autoUpdate) { on ->
                viewModel.settings.update { it.copy(autoUpdate = on) }
            }
            Text(
                str(R.string.installed_version, viewModel.updater.currentVersion),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            when (val u = update) {
                is UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is UpdateState.Downloading -> LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth())
                is UpdateState.UpToDate -> Text(str(R.string.this_is_the_latest_version), color = MaterialTheme.colorScheme.primary)
                is UpdateState.Failed -> Text(str(R.string.could_not_check, u.message), color = MaterialTheme.colorScheme.error)
                else -> {}
            }
            OutlinedButton(
                onClick = { viewModel.checkForUpdates(manual = true) },
                enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading
            ) { Text(str(R.string.check_now)) }

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

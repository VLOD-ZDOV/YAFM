package com.nexus.filemanager.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexus.filemanager.ui.viewmodel.FileExplorerViewModel
import com.nexus.filemanager.ui.viewmodel.FileExplorerViewModel.UpdateState

/** Offers a newer release found on GitHub, and shows the download. */
@Composable
fun UpdateDialog(viewModel: FileExplorerViewModel) {
    val state by viewModel.update.collectAsState()
    val release = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        else -> return
    }
    val downloading = state as? UpdateState.Downloading
    AlertDialog(
        onDismissRequest = { if (downloading == null) viewModel.dismissUpdate() },
        title = { Text("Доступна версия ${release.version}") },
        text = {
            Column {
                if (release.notes.isNotBlank()) {
                    Text(
                        release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                if (downloading != null) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { downloading.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.installUpdate(release) }, enabled = downloading == null) { Text("Обновить") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismissUpdate() }, enabled = downloading == null) { Text("Позже") }
        }
    )
}

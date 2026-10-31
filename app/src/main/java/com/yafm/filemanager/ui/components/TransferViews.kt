package com.yafm.filemanager.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yafm.filemanager.R
import com.yafm.filemanager.data.model.ServerConfig
import com.yafm.filemanager.data.repository.FileRepository
import com.yafm.filemanager.data.transfer.OnConflict
import com.yafm.filemanager.data.transfer.TransferProgress
import com.yafm.filemanager.data.transfer.TransferState
import com.yafm.filemanager.str
import com.yafm.filemanager.ui.viewmodel.FileExplorerViewModel

/** The running transfer, or the one that stopped with an error. */
@Composable
fun TransferCard(state: TransferState, onCancel: () -> Unit, onRetry: () -> Unit, onDismissFailed: () -> Unit) {
    val current = state.current
    val failed = state.failed
    if (current == null && failed == null) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 8.dp)) {
            if (current != null) Running(current, state.queued, onCancel)
            if (failed != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        str(R.string.transfer_failed, failed.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetry) { Text(str(R.string.retry)) }
                    IconButton(onClick = onDismissFailed) { Icon(Icons.Default.Close, contentDescription = str(R.string.close)) }
                }
            }
        }
    }
}

@Composable
private fun Running(p: TransferProgress, queued: Int, onCancel: () -> Unit) {
    val context = LocalContext.current
    fun bytes(n: Long) = Formatter.formatFileSize(context, n)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.name.ifEmpty { "…" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            if (p.bytesTotal > 0) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            val details = if (p.reconnecting) {
                str(R.string.reconnecting)
            } else {
                listOfNotNull(
                    "${bytes(p.bytesDone)} / ${bytes(p.bytesTotal)}",
                    p.bytesPerSecond.takeIf { it > 0 }?.let { str(R.string.speed_per_second, bytes(it)) },
                    p.secondsLeft?.let { str(R.string.time_left, clock(it)) },
                    str(R.string.files_of, p.filesDone, p.filesTotal).takeIf { p.filesTotal > 1 },
                    str(R.string.transfer_queued, queued).takeIf { queued > 0 }
                ).joinToString(" · ")
            }
            Text(details, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = str(R.string.cancel)) }
    }
}

private fun clock(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
fun PasteConflictDialog(conflict: FileExplorerViewModel.PasteConflict, onChoice: (OnConflict?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoice(null) },
        title = { Text(str(R.string.name_conflict_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val first = conflict.names.first()
                Text(if (conflict.names.size == 1) str(R.string.name_conflict_one, first) else str(R.string.name_conflict_many, conflict.names.size, first))
                Spacer(Modifier.height(4.dp))
                listOf(
                    OnConflict.REPLACE to R.string.replace_files,
                    OnConflict.SKIP_SAME to R.string.skip_same_files,
                    OnConflict.KEEP_BOTH to R.string.keep_both_files,
                    OnConflict.SKIP to R.string.skip_files
                ).forEach { (choice, title) ->
                    OutlinedButton(onClick = { onChoice(choice) }, modifier = Modifier.fillMaxWidth()) { Text(str(title)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onChoice(null) }) { Text(str(R.string.cancel)) } }
    )
}

@Composable
fun GoToPathDialog(current: String, onDismiss: () -> Unit, onGo: (String) -> Unit) {
    var path by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.go_to_path).trimEnd('…')) },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text(str(R.string.path)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { Button(onClick = { onGo(path) }, enabled = path.isNotBlank()) { Text(str(R.string.go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } }
    )
}

@Composable
fun ServerInfoDialog(server: ServerConfig, path: String, repository: FileRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val info by produceState<Result<FileRepository.ServerInfo>?>(null, server, path) {
        value = runCatching { repository.serverInfo(server, path) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(server.name) },
        text = {
            val result = info
            when {
                result == null -> Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) { CircularProgressIndicator() }
                result.isFailure -> Text(FileExplorerViewModel.describe(result.exceptionOrNull()!!), color = MaterialTheme.colorScheme.error)
                else -> SelectionContainer {
                    Column {
                        val i = result.getOrThrow()
                        Line(R.string.path, server.summary())
                        i.system?.let { Line(R.string.system, it) }
                        if (i.freeBytes != null && i.totalBytes != null) {
                            Line(R.string.disk_space, str(R.string.space_free_of, Formatter.formatFileSize(context, i.freeBytes), Formatter.formatFileSize(context, i.totalBytes)))
                        }
                        i.fingerprint?.let { Line(R.string.key_fingerprint, it, mono = true) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(str(R.string.close)) } }
    )
}

@Composable
private fun Line(label: Int, value: String, mono: Boolean = false) {
    Text(str(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = if (mono) FontFamily.Monospace else null)
    Spacer(Modifier.height(8.dp))
}

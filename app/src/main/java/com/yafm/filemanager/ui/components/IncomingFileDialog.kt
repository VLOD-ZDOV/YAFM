package com.yafm.filemanager.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yafm.filemanager.R
import com.yafm.filemanager.data.incoming.Incoming
import com.yafm.filemanager.data.incoming.IncomingFile
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.str
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** What to do with a file another app sent to "Open with": open it, show its folder, read it as text, keep a copy. */
@Composable
fun IncomingFileDialog(
    incoming: MutableStateFlow<Uri?>,
    onOpen: (File) -> Unit,
    onShowInFolder: (File) -> Unit,
    onOpenAsText: (File) -> Unit,
    onSaveToDownloads: (File) -> Unit
) {
    val context = LocalContext.current
    val uri by incoming.collectAsState()
    var resolved by remember { mutableStateOf<IncomingFile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        resolved = null
        error = null
        val u = uri ?: return@LaunchedEffect
        runCatching { Incoming.resolve(context, u) }
            .onSuccess { resolved = it }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    val current = uri ?: return
    fun close() { incoming.value = null }
    val file = resolved

    AlertDialog(
        onDismissRequest = { close() },
        title = { Text(file?.file?.name ?: current.lastPathSegment ?: "") },
        text = {
            when {
                error != null -> Text(str(R.string.error, error), color = MaterialTheme.colorScheme.error)
                file == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text(str(R.string.loading, ""))
                }
                else -> Column(Modifier.fillMaxWidth()) {
                    val item = FileItem(file.file.name, file.file.absolutePath, size = file.file.length())
                    Text(
                        listOf(item.typeLabel, item.formattedSize()).filter { it.isNotEmpty() }.joinToString(" · ") +
                            if (file.isCopy) "\n" + str(R.string.incoming_copy_hint) else "\n" + file.file.parent,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOfNotNull(
                        str(R.string.open) to { onOpen(file.file) },
                        if (!file.isCopy) str(R.string.show_in_folder) to { onShowInFolder(file.file) } else null,
                        str(R.string.open_as_text) to { onOpenAsText(file.file) },
                        if (file.isCopy) str(R.string.save_to_downloads) to { onSaveToDownloads(file.file) } else null
                    ).forEach { (label, action) ->
                        TextButton(onClick = { close(); action() }, modifier = Modifier.fillMaxWidth()) {
                            Text(label, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { close() }) { Text(str(R.string.cancel)) } }
    )
}

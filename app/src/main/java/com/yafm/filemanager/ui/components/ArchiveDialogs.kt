package com.yafm.filemanager.ui.components

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yafm.filemanager.data.archive.ArchiveManager
import com.yafm.filemanager.data.model.FileItem
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtractArchiveDialog(
    archiveItem: FileItem,
    currentPath: String,
    progress: ArchiveManager.Progress?,
    isExtracting: Boolean,
    onDismiss: () -> Unit,
    onExtract: (destinationPath: String, conflict: ArchiveManager.Conflict) -> Unit,
    /** Set for an archive on a server that can unpack it itself. */
    onExtractOnServer: (() -> Unit)? = null
) {
    // "pack.tar.lz4" -> "pack": a tar inside a compressor loses both suffixes.
    val baseName = archiveItem.name.replace(Regex("(\\.tar)?\\.[^.]+$", RegexOption.IGNORE_CASE), "").ifEmpty { archiveItem.name }
    val defaultDest = File(currentPath, baseName).absolutePath
    var destPath by remember { mutableStateOf(defaultDest) }
    var conflict by remember { mutableStateOf(ArchiveManager.Conflict.KEEP_BOTH) }
    // The choice matters only when the folder already has something in it.
    val targetBusy = remember(destPath) { File(destPath).list()?.isNotEmpty() == true }

    AlertDialog(
        onDismissRequest = { if (!isExtracting) onDismiss() },
        title = {
            Text(if (isExtracting) str(R.string.extracting) else str(R.string.extract_archive))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = archiveItem.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isExtracting) {
                    LinearProgressIndicator(
                        progress = { progress?.percentage ?: 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = progress?.currentFileName ?: str(R.string.preparing),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = str(R.string.files_processed, progress?.processedCount ?: 0),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    OutlinedTextField(
                        value = destPath,
                        onValueChange = { destPath = it },
                        label = { Text(str(R.string.destination_folder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (targetBusy) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(str(R.string.folder_not_empty), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                ArchiveManager.Conflict.KEEP_BOTH to R.string.keep_both,
                                ArchiveManager.Conflict.SKIP to R.string.skip,
                                ArchiveManager.Conflict.REPLACE to R.string.replace_original
                            ).forEach { (choice, label) ->
                                FilterChip(selected = conflict == choice, onClick = { conflict = choice }, label = { Text(str(label)) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isExtracting) {
                Button(onClick = { onExtract(destPath, if (targetBusy) conflict else ArchiveManager.Conflict.KEEP_BOTH) }) {
                    Text(str(R.string.extract))
                }
            }
        },
        dismissButton = {
            if (!isExtracting) {
                onExtractOnServer?.let { TextButton(onClick = it) { Text(str(R.string.on_server)) } }
                TextButton(onClick = onDismiss) {
                    Text(str(R.string.cancel))
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompressDialog(
    selectedCount: Int,
    progress: ArchiveManager.Progress?,
    isCompressing: Boolean,
    onDismiss: () -> Unit,
    onCompress: (archiveName: String, format: ArchiveManager.Format) -> Unit,
    /** Set when the files are on a server that can pack them itself. */
    onCompressOnServer: ((archiveName: String, format: ArchiveManager.Format) -> Unit)? = null
) {
    var baseName by remember { mutableStateOf("Archive") }
    var format by remember { mutableStateOf(ArchiveManager.Format.ZIP) }
    var onServer by remember { mutableStateOf(onCompressOnServer != null) }

    AlertDialog(
        onDismissRequest = { if (!isCompressing) onDismiss() },
        title = {
            Text(if (isCompressing) str(R.string.creating_archive) else str(R.string.create_archive))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = str(R.string.items_to_compress, selectedCount),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isCompressing) {
                    LinearProgressIndicator(
                        progress = { progress?.percentage ?: 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = progress?.currentFileName ?: str(R.string.compressing),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = baseName,
                        onValueChange = { baseName = it },
                        label = { Text(str(R.string.archive_name)) },
                        suffix = { Text(format.extension) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ArchiveManager.Format.entries.forEach { f ->
                            FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                        }
                    }
                    if (onCompressOnServer != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = onServer, onCheckedChange = { onServer = it })
                            Text(str(R.string.on_server), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isCompressing) {
                Button(
                    onClick = {
                        val name = baseName.trim().removeSuffix(format.extension) + format.extension
                        if (onServer) onCompressOnServer?.invoke(name, format) else onCompress(name, format)
                    },
                    enabled = baseName.isNotBlank() && '/' !in baseName
                ) {
                    Text(str(R.string.compress))
                }
            }
        },
        dismissButton = {
            if (!isCompressing) {
                TextButton(onClick = onDismiss) {
                    Text(str(R.string.cancel))
                }
            }
        }
    )
}

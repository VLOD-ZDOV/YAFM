package com.nexus.filemanager.ui.components

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.filemanager.data.archive.ArchiveManager
import com.nexus.filemanager.data.model.FileItem
import java.io.File

@Composable
fun ExtractArchiveDialog(
    archiveItem: FileItem,
    currentPath: String,
    progress: ArchiveManager.Progress?,
    isExtracting: Boolean,
    onDismiss: () -> Unit,
    onExtract: (destinationPath: String) -> Unit
) {
    val defaultDest = File(currentPath, archiveItem.name.substringBeforeLast('.')).absolutePath
    var destPath by remember { mutableStateOf(defaultDest) }

    AlertDialog(
        onDismissRequest = { if (!isExtracting) onDismiss() },
        title = {
            Text(if (isExtracting) "Извлечение архива..." else "Разархивировать")
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
                        text = progress?.currentFileName ?: "Подготовка...",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Обработано файлов: ${progress?.processedCount ?: 0}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    OutlinedTextField(
                        value = destPath,
                        onValueChange = { destPath = it },
                        label = { Text("Папка назначения") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (!isExtracting) {
                Button(onClick = { onExtract(destPath) }) {
                    Text("Извлечь")
                }
            }
        },
        dismissButton = {
            if (!isExtracting) {
                TextButton(onClick = onDismiss) {
                    Text("Отмена")
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
    onCompress: (archiveName: String, format: ArchiveManager.Format) -> Unit
) {
    var baseName by remember { mutableStateOf("Archive") }
    var format by remember { mutableStateOf(ArchiveManager.Format.ZIP) }

    AlertDialog(
        onDismissRequest = { if (!isCompressing) onDismiss() },
        title = {
            Text(if (isCompressing) "Создание архива..." else "Создать архив")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Выбрано объектов для сжатия: $selectedCount",
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
                        text = progress?.currentFileName ?: "Архивация...",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = baseName,
                        onValueChange = { baseName = it },
                        label = { Text("Имя архива") },
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
                }
            }
        },
        confirmButton = {
            if (!isCompressing) {
                Button(
                    onClick = { onCompress(baseName.trim().removeSuffix(format.extension) + format.extension, format) },
                    enabled = baseName.isNotBlank() && '/' !in baseName
                ) {
                    Text("Сжать")
                }
            }
        },
        dismissButton = {
            if (!isCompressing) {
                TextButton(onClick = onDismiss) {
                    Text("Отмена")
                }
            }
        }
    )
}

package com.yafm.filemanager.ui.components

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.data.repository.FileRepository
import kotlinx.coroutines.launch

@Composable
fun FileDetailsDialog(
    item: FileItem,
    repository: FileRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var hashes by remember { mutableStateOf<Pair<String, String>?>(null) }
    var hashError by remember { mutableStateOf<String?>(null) }
    var hashProgress by remember { mutableFloatStateOf(0f) }
    var isCalculatingHash by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(str(R.string.file_properties))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                DetailRow(label = str(R.string.name), value = item.name)
                DetailRowWithCopy(
                    label = str(R.string.full_path),
                    value = item.path,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(item.path))
                        Toast.makeText(context, str(R.string.path_copied), Toast.LENGTH_SHORT).show()
                    }
                )
                if (!item.isDirectory) {
                    DetailRow(label = str(R.string.size), value = str(R.string.bytes, item.formattedSize(), item.size))
                }
                DetailRow(label = str(R.string.modified), value = item.formattedDate().ifEmpty { str(R.string.unknown) })
                DetailRow(label = str(R.string.permissions_2), value = item.permissions)
                DetailRow(label = str(R.string.owner_group), value = "${item.owner} : ${item.group}")
                DetailRow(label = str(R.string.storage), value = item.storageType.name)

                if (!item.isDirectory) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = str(R.string.checksums),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            val result = hashes
                            when {
                                result != null -> {
                                    HashRow("MD5", result.first) {
                                        clipboardManager.setText(AnnotatedString(result.first))
                                        Toast.makeText(context, str(R.string.md5_copied), Toast.LENGTH_SHORT).show()
                                    }
                                    HashRow("SHA-256", result.second) {
                                        clipboardManager.setText(AnnotatedString(result.second))
                                        Toast.makeText(context, str(R.string.sha_256_copied), Toast.LENGTH_SHORT).show()
                                    }
                                }
                                hashError != null -> Text(hashError ?: "", color = MaterialTheme.colorScheme.error)
                                isCalculatingHash -> LinearProgressIndicator(
                                    progress = { hashProgress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                else -> OutlinedButton(
                                    onClick = {
                                        isCalculatingHash = true
                                        scope.launch {
                                            runCatching { repository.checksums(item) { hashProgress = it } }
                                                .onSuccess { hashes = it }
                                                .onFailure { hashError = str(R.string.error, it.message) }
                                            isCalculatingHash = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(str(R.string.compute_md5_and_sha_256))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(str(R.string.close))
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DetailRowWithCopy(label: String, value: String, onCopy: () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = str(R.string.copy),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun HashRow(label: String, value: String, onCopy: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            )
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Default.ContentCopy, contentDescription = str(R.string.copy))
        }
    }
}

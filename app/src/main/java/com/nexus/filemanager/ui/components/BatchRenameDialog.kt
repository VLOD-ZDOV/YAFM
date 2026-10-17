package com.nexus.filemanager.ui.components

import com.nexus.filemanager.str
import com.nexus.filemanager.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.filemanager.data.model.FileItem

@Composable
fun BatchRenameDialog(
    items: List<FileItem>,
    onDismiss: () -> Unit,
    onApply: (prefix: String, suffix: String, find: String, replace: String, addIndex: Boolean) -> Unit
) {
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var addIndex by remember { mutableStateOf(false) }

    fun previewName(item: FileItem, index: Int): String {
        val ext = if (item.isDirectory) "" else ".${item.extension}"
        val base = if (item.isDirectory) item.name else item.name.substringBeforeLast('.')
        var result = base
        if (findText.isNotEmpty()) {
            result = result.replace(findText, replaceText)
        }
        if (prefix.isNotEmpty()) {
            result = "$prefix$result"
        }
        if (suffix.isNotEmpty()) {
            result = "$result$suffix"
        }
        if (addIndex) {
            result = "${result}_${index + 1}"
        }
        return "$result$ext"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(str(R.string.batch_rename, items.size))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text(str(R.string.prefix_at_the_start)) },
                    placeholder = { Text("Photo_") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = suffix,
                    onValueChange = { suffix = it },
                    label = { Text(str(R.string.suffix_at_the_end)) },
                    placeholder = { Text("_backup") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = findText,
                        onValueChange = { findText = it },
                        label = { Text(str(R.string.find)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    OutlinedTextField(
                        value = replaceText,
                        onValueChange = { replaceText = it },
                        label = { Text(str(R.string.replace_with)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = addIndex, onCheckedChange = { addIndex = it })
                    Text(str(R.string.add_a_number), style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Preview section
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = str(R.string.preview_first),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        items.take(3).forEachIndexed { i, item ->
                            Text(
                                text = "${item.name} ➔ ${previewName(item, i)}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onApply(prefix, suffix, findText, replaceText, addIndex)
            }) {
                Text(str(R.string.rename_all))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(str(R.string.cancel))
            }
        }
    )
}

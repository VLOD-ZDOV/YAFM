package com.nexus.filemanager.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun NewItemDialog(
    isFolder: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isFolder) "Создать папку" else "Создать файл")
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя") },
                placeholder = { Text(if (isFolder) "Новая папка" else "document.txt") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim())
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (newName: String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Переименовать")
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Новое имя") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && name != currentName) {
                        onConfirm(name.trim())
                    }
                },
                enabled = name.isNotBlank() && name != currentName
            ) {
                Text("Переименовать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

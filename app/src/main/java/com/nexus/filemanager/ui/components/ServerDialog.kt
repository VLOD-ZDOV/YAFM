package com.nexus.filemanager.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.filemanager.data.model.ServerConfig
import com.nexus.filemanager.data.model.StorageType
import com.nexus.filemanager.data.network.FtpClient
import com.nexus.filemanager.data.network.SftpClient
import com.nexus.filemanager.data.network.SmbClient
import com.nexus.filemanager.ui.theme.DangerRed
import com.nexus.filemanager.ui.theme.SuccessGreen
import kotlinx.coroutines.launch

@Composable
fun ServerDialog(
    initialConfig: ServerConfig? = null,
    onDismiss: () -> Unit,
    onSave: (ServerConfig) -> Unit
) {
    val scope = rememberCoroutineScope()

    var protocol by remember { mutableStateOf(initialConfig?.type ?: StorageType.SMB) }
    var name by remember { mutableStateOf(initialConfig?.name ?: "") }
    var host by remember { mutableStateOf(initialConfig?.host ?: "") }
    var port by remember {
        mutableStateOf(
            initialConfig?.port?.toString() ?: when (protocol) {
                StorageType.SMB -> "445"
                StorageType.SFTP -> "22"
                StorageType.FTP -> "21"
                else -> "445"
            }
        )
    }
    var username by remember { mutableStateOf(initialConfig?.username ?: "") }
    var password by remember { mutableStateOf(initialConfig?.password ?: "") }
    var shareName by remember { mutableStateOf(initialConfig?.shareName ?: "") }
    var remotePath by remember { mutableStateOf(initialConfig?.remotePath ?: "/") }
    var isAnonymous by remember { mutableStateOf(initialConfig?.isAnonymous ?: false) }
    var showPassword by remember { mutableStateOf(false) }

    var testStatus by remember { mutableStateOf<Boolean?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    fun buildConfig(): ServerConfig {
        return ServerConfig(
            id = initialConfig?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.ifEmpty { "${protocol.name}: $host" },
            type = protocol,
            host = host.trim(),
            port = port.toIntOrNull() ?: when (protocol) {
                StorageType.SMB -> 445
                StorageType.SFTP -> 22
                StorageType.FTP -> 21
                else -> 445
            },
            username = username.trim(),
            password = password,
            shareName = shareName.trim(),
            remotePath = remotePath.trim().ifEmpty { "/" },
            isAnonymous = isAnonymous
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialConfig == null) "Новое сетевое подключение" else "Редактировать сервер")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Protocol Selection
                Text("Протокол передачи:", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = protocol == StorageType.SMB,
                        onClick = {
                            protocol = StorageType.SMB
                            port = "445"
                        },
                        label = { Text("Samba (SMB)") }
                    )
                    FilterChip(
                        selected = protocol == StorageType.SFTP,
                        onClick = {
                            protocol = StorageType.SFTP
                            port = "22"
                        },
                        label = { Text("SFTP / SSH") }
                    )
                    FilterChip(
                        selected = protocol == StorageType.FTP,
                        onClick = {
                            protocol = StorageType.FTP
                            port = "21"
                        },
                        label = { Text("FTP") }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя подключения (необязательно)") },
                    placeholder = { Text("Например: Домашний сервер") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Хост / IP") },
                        placeholder = { Text("192.168.1.100") },
                        modifier = Modifier.weight(2.5f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Порт") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                if (protocol == StorageType.SMB) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = shareName,
                        onValueChange = { shareName = it },
                        label = { Text("Имя общего ресурса (Share)") },
                        placeholder = { Text("shared / public / downloads") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = remotePath,
                        onValueChange = { remotePath = it },
                        label = { Text("Начальный путь") },
                        placeholder = { Text("/") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isAnonymous,
                        onCheckedChange = { isAnonymous = it }
                    )
                    Text("Анонимный вход (гость)", style = MaterialTheme.typography.bodyMedium)
                }

                if (!isAnonymous) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Имя пользователя") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Пароль") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Test Connection Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            isTesting = true
                            testStatus = null
                            scope.launch {
                                val cfg = buildConfig()
                                val ok = when (cfg.type) {
                                    StorageType.SMB -> SmbClient(cfg).testConnection()
                                    StorageType.SFTP -> SftpClient(cfg).testConnection()
                                    StorageType.FTP -> FtpClient(cfg).testConnection()
                                    else -> false
                                }
                                testStatus = ok
                                isTesting = false
                            }
                        },
                        enabled = host.isNotBlank() && !isTesting,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Проверка...")
                        } else {
                            Text("Проверить связь")
                        }
                    }

                    if (testStatus != null) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Icon(
                            imageVector = if (testStatus == true) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (testStatus == true) SuccessGreen else DangerRed,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = if (testStatus == true) "Доступен" else "Ошибка связи",
                            color = if (testStatus == true) SuccessGreen else DangerRed,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(buildConfig()) },
                enabled = host.isNotBlank()
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

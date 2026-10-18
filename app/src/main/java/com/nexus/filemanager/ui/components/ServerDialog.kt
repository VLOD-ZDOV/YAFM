package com.nexus.filemanager.ui.components

import kotlinx.coroutines.launch
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import android.net.Uri
import com.nexus.filemanager.str
import com.nexus.filemanager.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
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
import com.nexus.filemanager.ui.theme.DangerRed
import com.nexus.filemanager.ui.theme.SuccessGreen

@Composable
fun ServerDialog(
    initialConfig: ServerConfig? = null,
    qshAvailable: Boolean,
    onTest: (ServerConfig, (error: String?) -> Unit) -> Unit,
    onPair: (ServerConfig, code: String, (ok: Boolean, message: String) -> Unit) -> Unit,
    phoneKey: suspend () -> String,
    importKey: suspend (Uri, serverId: String) -> String,
    onDismiss: () -> Unit,
    onSave: (ServerConfig) -> Unit
) {
    val id = remember { initialConfig?.id ?: java.util.UUID.randomUUID().toString() }
    var protocol by remember { mutableStateOf(initialConfig?.type ?: StorageType.SFTP) }
    var name by remember { mutableStateOf(initialConfig?.name ?: "") }
    var host by remember { mutableStateOf(initialConfig?.host ?: "") }
    var port by remember { mutableStateOf(initialConfig?.port?.toString() ?: defaultPort(protocol).toString()) }
    var username by remember { mutableStateOf(initialConfig?.username ?: "") }
    var password by remember { mutableStateOf(initialConfig?.password ?: "") }
    var shareName by remember { mutableStateOf(initialConfig?.shareName ?: "") }
    var remotePath by remember { mutableStateOf(initialConfig?.remotePath ?: "") }
    var isAnonymous by remember { mutableStateOf(initialConfig?.isAnonymous ?: false) }
    var showPassword by remember { mutableStateOf(false) }
    var pairCode by remember { mutableStateOf("") }
    var keyPath by remember { mutableStateOf(initialConfig?.keyPath ?: "") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { importKey(uri, id) }
                .onSuccess { keyPath = it }
                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
        }
    }

    // null = not run; "" = success; else the error.
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val isQsh = protocol == StorageType.QSH
    val usesPassword = !isQsh

    fun buildConfig() = ServerConfig(
        id = id,
        name = name.ifEmpty { "${protocol.name}: $host" },
        type = protocol,
        host = host.trim(),
        port = port.toIntOrNull() ?: defaultPort(protocol),
        username = username.trim(),
        password = if (usesPassword) password else "",
        shareName = shareName.trim(),
        remotePath = remotePath.trim(),
        isAnonymous = usesPassword && isAnonymous,
        keyPath = if (protocol == StorageType.SFTP || protocol == StorageType.QSH) keyPath else ""
    )

    val ready = host.isNotBlank() && (!isQsh || username.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialConfig == null) str(R.string.new_network_connection) else str(R.string.edit_server))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(str(R.string.protocol), style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        StorageType.SFTP to "SFTP / SSH",
                        StorageType.QSH to "qsh",
                        StorageType.SMB to "Samba (SMB)",
                        StorageType.FTP to "FTP"
                    ).forEach { (type, label) ->
                        FilterChip(
                            selected = protocol == type,
                            onClick = {
                                if (port == defaultPort(protocol).toString()) port = defaultPort(type).toString()
                                protocol = type
                                status = null
                            },
                            label = { Text(label) }
                        )
                    }
                }

                if (isQsh && !qshAvailable) {
                    Text(
                        str(R.string.this_build_has_no_qsh_libqsh_so_the_conn),
                        color = DangerRed,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(str(R.string.connection_name_optional)) },
                    placeholder = { Text(str(R.string.for_example_home_server)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(str(R.string.host_ip)) },
                        placeholder = { Text("192.168.1.100") },
                        modifier = Modifier.weight(2.5f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text(str(R.string.port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                if (protocol == StorageType.SMB) {
                    OutlinedTextField(
                        value = shareName,
                        onValueChange = { shareName = it },
                        label = { Text(str(R.string.share_name)) },
                        placeholder = { Text("shared / public / downloads") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        value = remotePath,
                        onValueChange = { remotePath = it },
                        label = { Text(str(R.string.start_folder)) },
                        placeholder = { Text(if (protocol == StorageType.FTP) "/" else str(R.string.home_folder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                if (usesPassword) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = isAnonymous, onCheckedChange = { isAnonymous = it })
                        Text(str(R.string.anonymous_guest), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (!usesPassword || !isAnonymous) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(str(R.string.user_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                if (usesPassword && !isAnonymous) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(str(R.string.password)) },
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

                if (isQsh || protocol == StorageType.SFTP) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(str(R.string.key_login), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        str(if (isQsh) R.string.phone_key_hint else R.string.sftp_key_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isQsh) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { phoneKey() }
                                        .onSuccess {
                                            clipboard.setText(AnnotatedString(it))
                                            Toast.makeText(context, str(R.string.key_copied), Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                }
                            },
                            enabled = qshAvailable
                        ) { Text(str(R.string.copy_phone_key)) }
                    }
                    if (keyPath.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                str(R.string.own_key_set, java.io.File(keyPath).name),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { keyPath = "" }) {
                                Icon(Icons.Default.Close, contentDescription = str(R.string.remove_key))
                            }
                        }
                    }
                    OutlinedButton(onClick = { pickKey.launch(arrayOf("*/*")) }) { Text(str(R.string.choose_key_file)) }
                }

                if (isQsh) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        str(R.string.signs_in_with_this_phone_s_key_link_it_t),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = pairCode,
                            onValueChange = { pairCode = it },
                            label = { Text(str(R.string.pairing_code)) },
                            placeholder = { Text("k7f3-9qxm") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                busy = true
                                status = null
                                onPair(buildConfig(), pairCode) { ok, message ->
                                    busy = false
                                    status = if (ok) "" else message
                                    if (ok) pairCode = ""
                                }
                            },
                            enabled = ready && pairCode.isNotBlank() && !busy && qshAvailable
                        ) {
                            Text(str(R.string.pair))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            status = null
                            onTest(buildConfig()) { error ->
                                busy = false
                                status = error ?: ""
                            }
                        },
                        enabled = ready && !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(str(R.string.connecting))
                        } else {
                            Text(str(R.string.test_connection))
                        }
                    }
                }

                status?.let { error ->
                    val ok = error.isEmpty()
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (ok) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (ok) SuccessGreen else DangerRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (ok) str(R.string.works) else error,
                            color = if (ok) SuccessGreen else DangerRed,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(buildConfig()) }, enabled = ready) {
                Text(str(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(str(R.string.cancel))
            }
        }
    )
}

private fun defaultPort(type: StorageType) = when (type) {
    StorageType.SMB -> 445
    StorageType.SFTP -> 22
    StorageType.FTP -> 21
    StorageType.QSH -> 4422
    else -> 0
}

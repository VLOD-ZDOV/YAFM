package com.yafm.filemanager.ui.components

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yafm.filemanager.ui.viewmodel.FileExplorerViewModel

/** Asks for the one-time code (2FA) or other answer a server wants at login. */
@Composable
fun CodeDialog(viewModel: FileExplorerViewModel) {
    val request by viewModel.repository.codeRequest.collectAsState()
    val r = request ?: return
    var code by remember(r) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(r) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = { viewModel.repository.answerCode(null) },
        title = { Text(r.server.name) },
        text = {
            Column {
                Text(r.prompt)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> !c.isWhitespace() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (CODE.containsMatchIn(r.prompt)) KeyboardType.Number else KeyboardType.Password),
                    modifier = Modifier.focusRequester(focus)
                )
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.repository.answerCode(code) }, enabled = code.isNotEmpty()) { Text(str(R.string.sign_in)) }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.repository.answerCode(null) }) { Text(str(R.string.cancel)) }
        }
    )
}

private val CODE = Regex("code|otp|token|код", RegexOption.IGNORE_CASE)

/** A server key to trust, like ssh's question on a first login. A changed key is a warning, with refusal as the safe way. */
@Composable
fun HostKeyDialog(viewModel: FileExplorerViewModel) {
    val request by viewModel.repository.hostKeyRequest.collectAsState()
    val r = request ?: return
    AlertDialog(
        onDismissRequest = { viewModel.repository.answerHostKey(false) },
        title = { Text(str(if (r.changed) R.string.server_key_changed else R.string.new_server_key)) },
        text = {
            Column {
                Text(str(if (r.changed) R.string.server_key_changed_hint else R.string.new_server_key_hint, r.server.host))
                SelectionContainer {
                    Text(
                        r.fingerprint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (r.changed) {
                TextButton(onClick = { viewModel.repository.answerHostKey(true) }) {
                    Text(str(R.string.trust_new_key), color = MaterialTheme.colorScheme.error)
                }
            } else {
                Button(onClick = { viewModel.repository.answerHostKey(true) }) { Text(str(R.string.trust_key)) }
            }
        },
        dismissButton = {
            if (r.changed) Button(onClick = { viewModel.repository.answerHostKey(false) }) { Text(str(R.string.disconnect)) }
            else TextButton(onClick = { viewModel.repository.answerHostKey(false) }) { Text(str(R.string.cancel)) }
        }
    )
}

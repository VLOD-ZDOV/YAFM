package com.yafm.filemanager.ui.components

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.KeyboardType
import com.yafm.filemanager.ui.viewmodel.FileExplorerViewModel

/** Asks for the one-time code (2FA) a qsh server wants. */
@Composable
fun CodeDialog(viewModel: FileExplorerViewModel) {
    val request by viewModel.codeRequest.collectAsState()
    val r = request ?: return
    var code by remember(r) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(r) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = { viewModel.cancelCode() },
        title = { Text(r.server.name) },
        text = {
            Column {
                Text(r.prompt)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> !c.isWhitespace() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.focusRequester(focus)
                )
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.submitCode(code) }, enabled = code.isNotEmpty()) { Text(str(R.string.sign_in)) }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.cancelCode() }) { Text(str(R.string.cancel)) }
        }
    )
}

package com.yafm.filemanager.ui.screens

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import com.yafm.filemanager.data.fs.TextEncoding
import java.nio.charset.Charset
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.data.model.StorageType
import com.yafm.filemanager.data.repository.FileRepository
import com.yafm.filemanager.ui.theme.RootColor
import com.yafm.filemanager.ui.theme.SuccessGreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    fileItem: FileItem,
    repository: FileRepository,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var content by remember { mutableStateOf("") }
    var originalContent by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    // Saving after a failed read would replace the file with whatever was typed into the empty editor.
    var loadFailed by remember { mutableStateOf(false) }
    var fontSize by remember { mutableFloatStateOf(13f) }

    // The file's bytes and how they are read; null charset: a binary file, shown but never saved.
    var bytes by remember { mutableStateOf(ByteArray(0)) }
    var readCharset by remember { mutableStateOf<Charset?>(Charsets.UTF_8) }
    var saveCharset by remember { mutableStateOf<Charset>(Charsets.UTF_8) }
    var showEncodings by remember { mutableStateOf(false) }
    val binary = readCharset == null

    // Converting to another encoding is a change too, even with the same text.
    val isModified = content != originalContent || (readCharset != null && saveCharset != readCharset)

    var confirmLeave by remember { mutableStateOf(false) }

    fun save(then: () -> Unit = {}) {
        isSaving = true
        scope.launch {
            try {
                val encoded = TextEncoding.encode(content, saveCharset)
                    ?: throw java.io.IOException(str(R.string.does_not_fit_encoding, saveCharset.name()))
                // A byte order mark stays when the encoding stays.
                val data = (if (saveCharset == readCharset) TextEncoding.bom(bytes) else ByteArray(0)) + encoded
                repository.writeBytes(fileItem, data)
                bytes = data
                readCharset = saveCharset
                originalContent = content
                Toast.makeText(context, str(R.string.file_saved), Toast.LENGTH_SHORT).show()
                then()
            } catch (e: Exception) {
                Toast.makeText(context, str(R.string.could_not_save, e.message), Toast.LENGTH_LONG).show()
            } finally {
                isSaving = false
            }
        }
    }

    // Unsaved text is asked about, not lost.
    fun leave() = if (isModified && !loadFailed && !binary) confirmLeave = true else onNavigateBack()

    BackHandler { leave() }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(str(R.string.save_changes)) },
            confirmButton = { Button(onClick = { confirmLeave = false; save(onNavigateBack) }) { Text(str(R.string.save)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmLeave = false }) { Text(str(R.string.cancel)) }
                    TextButton(onClick = { confirmLeave = false; onNavigateBack() }) { Text(str(R.string.discard)) }
                }
            }
        )
    }

    LaunchedEffect(fileItem.path) {
        isLoading = true
        try {
            val data = repository.readBytes(fileItem)
            val charset = TextEncoding.detect(data)
            bytes = data
            readCharset = charset
            saveCharset = charset ?: Charsets.UTF_8
            val text = TextEncoding.decode(data, charset ?: Charsets.ISO_8859_1)
            content = text
            originalContent = text
        } catch (e: Exception) {
            loadFailed = true
            Toast.makeText(context, str(R.string.could_not_read, e.message), Toast.LENGTH_LONG).show()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = fileItem.name + (if (isModified) " *" else ""),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                            if (fileItem.storageType == StorageType.ROOT) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "Root",
                                    tint = RootColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            text = fileItem.path,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { showEncodings = true }, enabled = !isLoading && !loadFailed) {
                            Text(
                                if (binary) str(R.string.read_only) else if (saveCharset != readCharset) "→ ${saveCharset.name()}" else readCharset!!.name(),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        DropdownMenu(expanded = showEncodings, onDismissRequest = { showEncodings = false }) {
                            if (binary) {
                                Text(
                                    str(R.string.binary_file_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            } else {
                                Text(str(R.string.read_as), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                                TextEncoding.CHOICES.forEach { cs ->
                                    DropdownMenuItem(
                                        text = { Text(cs.name()) },
                                        trailingIcon = { if (cs == readCharset) Icon(Icons.Default.Check, contentDescription = null) },
                                        // Reading again in another encoding would drop unsaved edits.
                                        enabled = content == originalContent,
                                        onClick = {
                                            showEncodings = false
                                            readCharset = cs
                                            saveCharset = cs
                                            content = TextEncoding.decode(bytes, cs)
                                            originalContent = content
                                        }
                                    )
                                }
                                if (readCharset != Charsets.UTF_8) {
                                    DropdownMenuItem(
                                        text = { Text(str(R.string.convert_to_utf8)) },
                                        onClick = { showEncodings = false; saveCharset = Charsets.UTF_8 }
                                    )
                                }
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            fontSize = if (fontSize >= 18f) 11f else fontSize + 2f
                        }
                    ) {
                        Icon(Icons.Default.FormatSize, contentDescription = str(R.string.font_size))
                    }

                    IconButton(
                        onClick = { save() },
                        enabled = isModified && !isSaving && !loadFailed && !binary
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = str(R.string.save),
                                tint = if (isModified) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            val vScrollState = rememberScrollState()
            val hScrollState = rememberScrollState()
            val linesCount = remember(content) { countLines(content) }
            // One text for all the numbers: a composable per line would be thousands of them in a long file.
            val lineNumbers = remember(linesCount) { (1..linesCount).joinToString("\n") }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                // Edge-to-edge: the keyboard is not part of the Scaffold's insets.
                .consumeWindowInsets(paddingValues)
                .imePadding()
            ) {
                // Line numbers gutter
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(vScrollState)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = lineNumbers,
                            textAlign = TextAlign.End,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                lineHeight = (fontSize * 1.4f).sp
                            )
                        )
                    }
                }

                // Editor area
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(8.dp)
                ) {
                    BasicTextField(
                        value = content,
                        onValueChange = { content = it },
                        readOnly = binary,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = (fontSize * 1.4f).sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(vScrollState)
                            .horizontalScroll(hScrollState)
                    )
                }
            }
        }
    }
}

/** Lines as String.lines() counts them (\n, \r\n or \r), without making a string of each. */
private fun countLines(text: String): Int {
    var lines = 1
    for (i in text.indices) {
        val c = text[i]
        if (c == '\n' || (c == '\r' && text.getOrNull(i + 1) != '\n')) lines++
    }
    return lines
}

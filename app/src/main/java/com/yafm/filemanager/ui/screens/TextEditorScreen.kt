package com.yafm.filemanager.ui.screens

import com.yafm.filemanager.str
import android.content.Context
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import com.yafm.filemanager.ui.components.MarkdownView
import java.io.File
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

    var value by remember { mutableStateOf(TextFieldValue("")) }
    val content = value.text
    var originalContent by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    // Saving after a failed read would replace the file with whatever was typed into the empty editor.
    var loadFailed by remember { mutableStateOf(false) }
    // Font size and wrapping are kept for every file.
    val editorPrefs = remember { context.getSharedPreferences("editor", Context.MODE_PRIVATE) }
    var fontSize by remember { mutableFloatStateOf(editorPrefs.getFloat("fontSize", 13f)) }
    var wrap by remember { mutableStateOf(editorPrefs.getBoolean("wrap", false)) }
    fun changeFont(by: Float) {
        fontSize = (fontSize + by).coerceIn(8f, 28f)
        editorPrefs.edit().putFloat("fontSize", fontSize).apply()
    }
    val isMarkdown = fileItem.name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdown", "mkd")
    var preview by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    val vScrollState = rememberScrollState()
    val hScrollState = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Undo/redo: snapshots of the text, a new one after a pause or at a word's end, so one step is not one letter.
    val undoStack = remember { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember { mutableStateListOf<TextFieldValue>() }
    var lastEdit by remember { mutableLongStateOf(0L) }
    fun edit(new: TextFieldValue) {
        if (new.text != value.text) {
            val now = System.currentTimeMillis()
            val wordEnd = new.text.length > value.text.length && new.text.getOrNull(new.selection.start - 1)?.isWhitespace() == true
            if (undoStack.isEmpty() || now - lastEdit > 1000 || wordEnd) {
                undoStack.add(value)
                // Each step is a whole copy: a big file keeps fewer of them (about 40 MB of text at most).
                while (undoStack.size > 1 && (undoStack.size > 200 || undoStack.sumOf { it.text.length.toLong() } > 20_000_000L)) {
                    undoStack.removeAt(0)
                }
            }
            lastEdit = now
            redoStack.clear()
        }
        value = new
    }

    fun scrollTo(offset: Int) {
        val l = layout ?: return
        if (l.layoutInput.text.length < offset) return
        val line = l.getLineForOffset(offset)
        scope.launch {
            vScrollState.animateScrollTo((l.getLineTop(line) - vScrollState.viewportSize / 3f).toInt().coerceAtLeast(0))
            if (!wrap) hScrollState.animateScrollTo((l.getHorizontalPosition(offset, true) - hScrollState.viewportSize / 2f).toInt().coerceAtLeast(0))
        }
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.add(value)
        value = prev
        lastEdit = 0L
        scrollTo(prev.selection.start)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.add(value)
        value = next
        lastEdit = 0L
        scrollTo(next.selection.start)
    }

    // Find and replace: the match is shown as the selection. They read value.text, not content:
    // after a replace in the same click, content still holds the text from before it.
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    fun select(at: Int) {
        value = value.copy(selection = TextRange(at, at + query.length))
        scrollTo(at)
    }
    fun findNext(fromStart: Boolean = false) {
        if (query.isEmpty()) return
        val from = if (fromStart) value.selection.min else value.selection.max
        val at = value.text.indexOf(query, from, !matchCase).takeIf { it >= 0 } ?: value.text.indexOf(query, 0, !matchCase)
        if (at >= 0) select(at)
    }
    fun findPrev() {
        if (query.isEmpty()) return
        val from = value.selection.min - 1
        val at = (if (from >= 0) value.text.lastIndexOf(query, from, !matchCase) else -1).takeIf { it >= 0 }
            ?: value.text.lastIndexOf(query, value.text.length, !matchCase)
        if (at >= 0) select(at)
    }
    fun selectedIsMatch() = query.isNotEmpty() && value.selection.length == query.length &&
        value.text.regionMatches(value.selection.min, query, 0, query.length, !matchCase)
    fun replaceOne() {
        if (!selectedIsMatch()) return findNext(fromStart = true)
        val start = value.selection.min
        val text = value.text.substring(0, start) + replacement + value.text.substring(start + query.length)
        lastEdit = 0L
        edit(TextFieldValue(text, TextRange(start + replacement.length)))
        findNext()
    }
    fun replaceAll() {
        if (query.isEmpty()) return
        val text = value.text.replace(query, replacement, !matchCase)
        if (text == value.text) return
        lastEdit = 0L
        edit(TextFieldValue(text, TextRange(value.selection.min.coerceAtMost(text.length))))
    }
    fun matchStatus(): String {
        if (query.isEmpty()) return ""
        var count = 0
        var current = 0
        var i = value.text.indexOf(query, 0, !matchCase)
        while (i >= 0 && count < 9999) {
            count++
            if (i == value.selection.min && selectedIsMatch()) current = count
            i = value.text.indexOf(query, i + maxOf(query.length, 1), !matchCase)
        }
        return if (count == 0) "0" else if (current > 0) "$current/$count" else "$count"
    }

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
            value = TextFieldValue(text)
            originalContent = text
            // Markdown opens as it looks; the pencil switches to the text.
            preview = isMarkdown
        } catch (e: Exception) {
            loadFailed = true
            Toast.makeText(context, str(R.string.could_not_read, e.message), Toast.LENGTH_LONG).show()
        } finally {
            isLoading = false
        }
    }

    if (showGoTo) {
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGoTo = false },
            title = { Text(str(R.string.go_to_line)) },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.filter(Char::isDigit).take(9) },
                    singleLine = true,
                    label = { Text(str(R.string.line_number_range, countLines(content))) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                Button(onClick = {
                    showGoTo = false
                    typed.toIntOrNull()?.let { n ->
                        val at = lineStart(content, n)
                        value = value.copy(selection = TextRange(at))
                        scrollTo(at)
                    }
                }) { Text(str(R.string.go)) }
            },
            dismissButton = { TextButton(onClick = { showGoTo = false }) { Text(str(R.string.cancel)) } }
        )
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (fileItem.storageType == StorageType.ROOT) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.Default.Security, contentDescription = str(R.string.root_access), tint = RootColor, modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            text = fileItem.path,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
                    }
                },
                actions = {
                    if (isMarkdown && !isLoading && !loadFailed) {
                        IconButton(onClick = { preview = !preview; if (preview) finding = false }) {
                            Icon(
                                if (preview) Icons.Default.Edit else Icons.Default.Visibility,
                                contentDescription = str(if (preview) R.string.edit else R.string.preview)
                            )
                        }
                    }
                    if (!preview) {
                        IconButton(onClick = { undo() }, enabled = undoStack.isNotEmpty() && !binary) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = str(R.string.undo))
                        }
                        IconButton(onClick = { redo() }, enabled = redoStack.isNotEmpty() && !binary) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = str(R.string.redo))
                        }
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
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = str(R.string.more))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            if (!preview) {
                                DropdownMenuItem(
                                    text = { Text(str(R.string.find_and_replace)) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    onClick = { showMenu = false; finding = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(str(R.string.go_to_line)) },
                                    leadingIcon = { Icon(Icons.Default.FormatListNumbered, contentDescription = null) },
                                    onClick = { showMenu = false; showGoTo = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(str(R.string.word_wrap)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.WrapText, contentDescription = null) },
                                    trailingIcon = { if (wrap) Icon(Icons.Default.Check, contentDescription = null) },
                                    onClick = { wrap = !wrap; editorPrefs.edit().putBoolean("wrap", wrap).apply() }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(str(R.string.font_size_value, fontSize.toInt())) },
                                leadingIcon = { Icon(Icons.Default.FormatSize, contentDescription = null) },
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = { changeFont(-1f) }) { Icon(Icons.Default.Remove, contentDescription = str(R.string.smaller)) }
                                        IconButton(onClick = { changeFont(1f) }) { Icon(Icons.Default.Add, contentDescription = str(R.string.larger)) }
                                    }
                                },
                                onClick = {}
                            )
                            HorizontalDivider()
                            // Encodings: how the bytes are read, and what is written on save.
                            if (binary) {
                                Text(
                                    str(R.string.binary_file_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            } else if (!isLoading && !loadFailed) {
                                Text(
                                    str(R.string.read_as) + " · " + (if (saveCharset != readCharset) "→ ${saveCharset.name()}" else readCharset!!.name()),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                                TextEncoding.CHOICES.forEach { cs ->
                                    DropdownMenuItem(
                                        text = { Text(cs.name()) },
                                        trailingIcon = { if (cs == readCharset) Icon(Icons.Default.Check, contentDescription = null) },
                                        // Reading again in another encoding would drop unsaved edits.
                                        enabled = content == originalContent,
                                        onClick = {
                                            showMenu = false
                                            readCharset = cs
                                            saveCharset = cs
                                            val text = TextEncoding.decode(bytes, cs)
                                            value = TextFieldValue(text)
                                            originalContent = text
                                            undoStack.clear(); redoStack.clear()
                                        }
                                    )
                                }
                                if (readCharset != Charsets.UTF_8) {
                                    DropdownMenuItem(
                                        text = { Text(str(R.string.convert_to_utf8)) },
                                        onClick = { showMenu = false; saveCharset = Charsets.UTF_8 }
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (preview) {
            MarkdownView(
                text = content,
                baseDir = File(fileItem.path).parentFile?.takeIf { fileItem.storageType == StorageType.LOCAL },
                modifier = Modifier.fillMaxSize().padding(paddingValues)
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                // Edge-to-edge: the keyboard is not part of the Scaffold's insets.
                .consumeWindowInsets(paddingValues)
                .imePadding()
        ) {
            if (finding) {
                FindBar(
                    query = query,
                    onQuery = { query = it; findNext(fromStart = true) },
                    replacement = replacement,
                    onReplacement = { replacement = it },
                    matchCase = matchCase,
                    onMatchCase = { matchCase = it; findNext(fromStart = true) },
                    status = matchStatus(),
                    readOnly = binary,
                    onPrev = { findPrev() },
                    onNext = { findNext() },
                    onReplace = { replaceOne() },
                    onReplaceAll = { replaceAll() },
                    onClose = { finding = false }
                )
            }
            val lineStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.4f).sp
            )
            Row(modifier = Modifier.fillMaxSize()) {
                // One text for all the numbers: a composable per line would be thousands of them in a long file.
                // With wrapping, a long line takes several rows and only its first one is numbered.
                val numbers = remember(layout, content, wrap) { gutterText(content, if (wrap) layout else null) }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Text(
                        text = numbers,
                        textAlign = TextAlign.End,
                        style = lineStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .verticalScroll(vScrollState)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = { edit(it) },
                    readOnly = binary,
                    textStyle = lineStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(vScrollState)
                        .then(if (wrap) Modifier else Modifier.horizontalScroll(hScrollState))
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    replacement: String,
    onReplacement: (String) -> Unit,
    matchCase: Boolean,
    onMatchCase: (Boolean) -> Unit,
    status: String,
    readOnly: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit
) {
    var showReplace by remember { mutableStateOf(false) }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    placeholder = { Text(str(R.string.find)) },
                    suffix = { Text(status, style = MaterialTheme.typography.labelSmall) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onNext() }),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onPrev) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = str(R.string.previous)) }
                IconButton(onClick = onNext) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = str(R.string.next)) }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = str(R.string.close)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = matchCase, onClick = { onMatchCase(!matchCase) }, label = { Text(str(R.string.match_case)) })
                Spacer(Modifier.width(8.dp))
                if (!readOnly) {
                    FilterChip(selected = showReplace, onClick = { showReplace = !showReplace }, label = { Text(str(R.string.replace_original)) })
                }
            }
            if (showReplace && !readOnly) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = onReplacement,
                        singleLine = true,
                        placeholder = { Text(str(R.string.replace_with)) },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onReplace) { Text(str(R.string.replace_original)) }
                    TextButton(onClick = onReplaceAll) { Text(str(R.string.replace_all)) }
                }
            }
        }
    }
}

/** Where line [n] (from 1) starts in [text]; past the end: the end. */
private fun lineStart(text: String, n: Int): Int {
    var line = 1
    if (n <= 1) return 0
    for (i in text.indices) {
        val c = text[i]
        if (c == '\n' || (c == '\r' && text.getOrNull(i + 1) != '\n')) {
            line++
            if (line == n) return i + 1
        }
    }
    return text.length
}

/** Line numbers, one per row; with [layout] (wrapping on), the rows a long line wraps into stay blank. */
private fun gutterText(text: String, layout: TextLayoutResult?): String {
    if (layout == null || layout.layoutInput.text.text != text) return (1..countLines(text)).joinToString("\n")
    val rows = arrayOfNulls<String>(layout.lineCount)
    rows[0] = "1"
    var n = 1
    for (i in text.indices) {
        val c = text[i]
        if (c == '\n' || (c == '\r' && text.getOrNull(i + 1) != '\n')) {
            n++
            val row = layout.getLineForOffset(i + 1)
            if (row < rows.size) rows[row] = n.toString()
        }
    }
    return rows.joinToString("\n") { it ?: "" }
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

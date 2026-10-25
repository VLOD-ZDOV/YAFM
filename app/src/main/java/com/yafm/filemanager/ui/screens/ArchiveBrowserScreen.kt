package com.yafm.filemanager.ui.screens

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yafm.filemanager.data.archive.ArchiveContents
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.ui.components.ExtractArchiveDialog
import com.yafm.filemanager.ui.components.FileIcon
import com.yafm.filemanager.ui.viewmodel.FileExplorerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** An archive shown like a folder: browse it, open one file, or extract everything. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveBrowserScreen(
    archiveItem: FileItem,
    viewModel: FileExplorerViewModel,
    startFolder: String,
    onNavigateBack: () -> Unit,
    onOpenFile: (file: FileItem, folder: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ArchiveContents.Entry>?>(null) }
    var local by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf(startFolder) }
    var opening by remember { mutableStateOf<String?>(null) }
    var showExtract by remember { mutableStateOf(false) }
    val archiveProgress by viewModel.archiveProgress.collectAsState()
    val isArchiveProcessing by viewModel.isArchiveProcessing.collectAsState()

    LaunchedEffect(archiveItem.path) {
        runCatching {
            withContext(Dispatchers.IO) {
                val file = viewModel.repository.materialize(archiveItem)
                file to ArchiveContents.list(file)
            }
        }.onSuccess { (file, list) ->
            local = file
            entries = list
        }.onFailure { error = FileExplorerViewModel.describe(it) }
    }

    fun back() {
        if (folder.isEmpty()) onNavigateBack() else folder = folder.substringBeforeLast('/', "")
    }
    BackHandler { back() }

    if (showExtract) {
        ExtractArchiveDialog(
            archiveItem = archiveItem,
            currentPath = viewModel.archiveOutputDir(),
            progress = archiveProgress,
            isExtracting = isArchiveProcessing,
            onDismiss = { showExtract = false },
            onExtract = { dest, conflict ->
                viewModel.extractArchive(archiveItem, dest, conflict) {
                    showExtract = false
                    Toast.makeText(context, str(R.string.extracted_to, dest), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(archiveItem.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text("/" + folder, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showExtract = true }, enabled = entries != null) {
                        Icon(Icons.Default.Unarchive, contentDescription = str(R.string.extract_all))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val list = entries
            when {
                error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                list == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                else -> Column {
                    if (opening != null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(ArchiveContents.children(list, folder), key = { it.path }) { entry ->
                            val item = FileItem(name = entry.name, path = entry.path, size = entry.size.coerceAtLeast(0), isDirectory = entry.isDirectory, lastModified = entry.modified)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = opening == null) {
                                        if (entry.isDirectory) {
                                            folder = entry.path
                                        } else {
                                            val archive = local ?: return@clickable
                                            opening = entry.path
                                            scope.launch {
                                                // One file comes out into the cache; the archive stays as it is.
                                                runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        val dir = File(context.cacheDir, "archive_view").apply { deleteRecursively() }
                                                        ArchiveContents.extractOne(archive, entry.path, dir)
                                                    }
                                                }.onSuccess { file ->
                                                    onOpenFile(FileItem(file.name, file.absolutePath, size = file.length(), lastModified = file.lastModified()), folder)
                                                }.onFailure {
                                                    Toast.makeText(context, FileExplorerViewModel.describe(it), Toast.LENGTH_LONG).show()
                                                }
                                                opening = null
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                FileIcon(item = item.copy(path = ""))
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                    if (!entry.isDirectory) {
                                        Text(
                                            listOf(if (entry.size >= 0) item.formattedSize() else "", item.formattedDate()).filter { it.isNotEmpty() }.joinToString("  "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

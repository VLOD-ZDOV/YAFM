package com.nexus.filemanager.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import com.nexus.filemanager.data.settings.VideoPlayer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nexus.filemanager.data.archive.ArchiveManager
import com.nexus.filemanager.data.model.FileCategory
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.StorageType
import com.nexus.filemanager.ui.components.ActionBottomBar
import com.nexus.filemanager.ui.components.BatchRenameDialog
import com.nexus.filemanager.ui.components.BreadcrumbBar
import com.nexus.filemanager.ui.components.CompressDialog
import com.nexus.filemanager.ui.components.ExtractArchiveDialog
import com.nexus.filemanager.ui.components.FileDetailsDialog
import com.nexus.filemanager.ui.components.FileGridItem
import com.nexus.filemanager.ui.components.FileListItem
import com.nexus.filemanager.ui.components.NewItemDialog
import com.nexus.filemanager.ui.components.PasteFloatingBar
import com.nexus.filemanager.ui.components.PermissionsDialog
import com.nexus.filemanager.ui.components.RenameDialog
import com.nexus.filemanager.ui.theme.RootColor
import com.nexus.filemanager.ui.viewmodel.FileExplorerViewModel
import com.nexus.filemanager.ui.viewmodel.SortMode
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    viewModel: FileExplorerViewModel,
    onNavigateBack: () -> Unit,
    onOpenFileInEditor: (FileItem) -> Unit,
    onOpenImage: (FileItem) -> Unit,
    onOpenVideo: (java.io.File, List<java.io.File>) -> Unit
) {
    val context = LocalContext.current
    val currentPath by viewModel.currentPath.collectAsState()
    val storageType by viewModel.currentStorageType.collectAsState()
    val fileList by viewModel.fileList.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isGridView by viewModel.isGridView.collectAsState()
    val location by viewModel.location.collectAsState()

    // The screen is rebuilt after a file is viewed: scroll positions live in the ViewModel, per folder.
    val listState = remember(location) {
        val (index, offset) = viewModel.scrollPosition(location)
        LazyListState(index, offset)
    }
    val gridState = remember(location) {
        val (index, offset) = viewModel.scrollPosition(location)
        LazyGridState(index, offset)
    }
    DisposableEffect(location, isGridView) {
        onDispose {
            if (isGridView) {
                viewModel.saveScrollPosition(location, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            } else {
                viewModel.saveScrollPosition(location, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
        }
    }
    val showHidden by viewModel.showHiddenFiles.collectAsState()
    val selectedItems by viewModel.selectedItems.collectAsState()
    val clipboardItems by viewModel.clipboardItems.collectAsState()
    val clipboardOp by viewModel.clipboardOp.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val archiveProgress by viewModel.archiveProgress.collectAsState()
    val isArchiveProcessing by viewModel.isArchiveProcessing.collectAsState()
    val progress by viewModel.progress.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    var isSearchActive by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showFabMenu by remember { mutableStateOf(false) }

    // Dialog States
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showBatchRenameDialog by remember { mutableStateOf(false) }
    var extractTarget by remember { mutableStateOf<FileItem?>(null) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var deleteConfirmTargets by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var permissionsTarget by remember { mutableStateOf<FileItem?>(null) }
    var detailsTarget by remember { mutableStateOf<FileItem?>(null) }
    var videoChoice by remember { mutableStateOf<FileItem?>(null) }

    fun playBuiltIn(item: FileItem) {
        viewModel.withLocalCopy(item) { file ->
            // Next/previous: the other videos of the same local folder.
            val playlist = if (item.storageType == StorageType.LOCAL) {
                fileList.filter { it.category == FileCategory.VIDEO }.map { java.io.File(it.path) }
            } else listOf(file)
            onOpenVideo(file, playlist)
        }
    }

    fun openVideo(item: FileItem) {
        when (viewModel.settings.settings.value.videoPlayer) {
            VideoPlayer.BUILT_IN -> playBuiltIn(item)
            VideoPlayer.SYSTEM -> viewModel.withLocalCopy(item) { openExternalIntent(context, item, it) }
            VideoPlayer.ASK -> videoChoice = item
        }
    }

    videoChoice?.let { item ->
        VideoChoiceDialog(
            onDismiss = { videoChoice = null },
            onChoose = { builtIn, remember ->
                videoChoice = null
                if (remember) {
                    viewModel.settings.update { it.copy(videoPlayer = if (builtIn) VideoPlayer.BUILT_IN else VideoPlayer.SYSTEM) }
                }
                if (builtIn) playBuiltIn(item) else viewModel.withLocalCopy(item) { openExternalIntent(context, item, it) }
            }
        )
    }

    val isSelectionMode = selectedItems.isNotEmpty()

    BackHandler {
        if (isSelectionMode) {
            viewModel.clearSelection()
        } else if (isSearchActive) {
            isSearchActive = false
            viewModel.setSearchQuery("")
        } else {
            val handled = viewModel.navigateBack()
            if (!handled) {
                onNavigateBack()
            }
        }
    }

    // Dialogs
    if (showNewFolderDialog) {
        NewItemDialog(
            isFolder = true,
            onDismiss = { showNewFolderDialog = false },
            onConfirm = {
                viewModel.createFolder(it)
                showNewFolderDialog = false
            }
        )
    }

    if (showNewFileDialog) {
        NewItemDialog(
            isFolder = false,
            onDismiss = { showNewFileDialog = false },
            onConfirm = {
                viewModel.createNewFile(it)
                showNewFileDialog = false
            }
        )
    }

    extractTarget?.let { item ->
        ExtractArchiveDialog(
            archiveItem = item,
            currentPath = viewModel.archiveOutputDir(),
            progress = archiveProgress,
            isExtracting = isArchiveProcessing,
            onDismiss = { extractTarget = null },
            onExtract = { dest ->
                viewModel.extractArchive(item, dest) {
                    extractTarget = null
                    Toast.makeText(context, "Распаковано в $dest", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showCompressDialog) {
        CompressDialog(
            selectedCount = selectedItems.size,
            progress = archiveProgress,
            isCompressing = isArchiveProcessing,
            onDismiss = { showCompressDialog = false },
            onCompress = { archiveName, format ->
                viewModel.compressSelected(archiveName, format) {
                    showCompressDialog = false
                }
            }
        )
    }

    if (showBatchRenameDialog) {
        BatchRenameDialog(
            items = selectedItems.toList(),
            onDismiss = { showBatchRenameDialog = false },
            onApply = { prefix, suffix, find, replace, addIndex ->
                viewModel.batchRenameSelected(prefix, suffix, find, replace, addIndex)
                showBatchRenameDialog = false
            }
        )
    }

    renameTarget?.let { item ->
        RenameDialog(
            currentName = item.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.renameFile(item, newName)
                renameTarget = null
            }
        )
    }

    if (deleteConfirmTargets.isNotEmpty()) {
        val targets = deleteConfirmTargets
        AlertDialog(
            onDismissRequest = { deleteConfirmTargets = emptyList() },
            title = { Text(if (targets.size == 1) "Удалить?" else "Удалить ${targets.size} объектов?") },
            text = {
                Text(
                    if (targets.size == 1) "«${targets[0].name}» будет удалён без возможности восстановления."
                    else "Выбранные объекты будут удалены без возможности восстановления."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearSelection()
                        viewModel.deleteItems(targets)
                        deleteConfirmTargets = emptyList()
                    }
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmTargets = emptyList() }) {
                    Text("Отмена")
                }
            }
        )
    }

    permissionsTarget?.let { item ->
        PermissionsDialog(
            item = item,
            onDismiss = { permissionsTarget = null },
            onApply = { octal ->
                viewModel.changePermissions(item, octal)
                permissionsTarget = null
            }
        )
    }

    detailsTarget?.let { item ->
        FileDetailsDialog(
            item = item,
            repository = viewModel.repository,
            onDismiss = { detailsTarget = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        val focusRequester = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Поиск в этой папке...") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            trailingIcon = {
                                IconButton(onClick = {
                                    isSearchActive = false
                                    viewModel.setSearchQuery("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Закрыть поиск")
                                }
                            }
                        )
                    } else {
                        Column {
                            val folderName = location.category?.let { viewModel.categoryTitle(it) }
                                ?: currentPath.substringAfterLast('/').ifEmpty { "/" }
                            Text(
                                text = folderName,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                            Text(
                                text = "${fileList.size} объектов • ${storageType.name}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                                color = if (storageType == StorageType.ROOT) RootColor else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val handled = viewModel.navigateBack()
                        if (!handled) onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (!isSearchActive) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Поиск")
                        }
                    }

                    IconButton(onClick = { viewModel.toggleGridView() }) {
                        Icon(
                            imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = "Сменить вид"
                        )
                    }

                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Сортировка")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("По имени (А-Я)") },
                                onClick = {
                                    viewModel.setSortMode(SortMode.NAME_ASC)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("По имени (Я-А)") },
                                onClick = {
                                    viewModel.setSortMode(SortMode.NAME_DESC)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Сначала новые") },
                                onClick = {
                                    viewModel.setSortMode(SortMode.DATE_DESC)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Сначала старые") },
                                onClick = {
                                    viewModel.setSortMode(SortMode.DATE_ASC)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Сначала большие") },
                                onClick = {
                                    viewModel.setSortMode(SortMode.SIZE_DESC)
                                    showSortMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Сначала маленькие") },
                                onClick = {
                                    viewModel.setSortMode(SortMode.SIZE_ASC)
                                    showSortMenu = false
                                }
                            )
                        }
                    }

                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Меню")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (showHidden) "Скрыть скрытые" else "Показать скрытые") },
                                onClick = {
                                    viewModel.toggleHiddenFiles()
                                    showMoreMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Обновить") },
                                onClick = {
                                    viewModel.loadCurrentDirectory()
                                    showMoreMenu = false
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            // A category is not a folder: nothing can be created or pasted into it.
            if (!isSelectionMode && location.category == null) {
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedVisibility(visible = showFabMenu) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text("Создать файл", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp)
                                }
                                FloatingActionButton(
                                    onClick = {
                                        showFabMenu = false
                                        showNewFileDialog = true
                                    },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null)
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text("Создать папку", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp)
                                }
                                FloatingActionButton(
                                    onClick = {
                                        showFabMenu = false
                                        showNewFolderDialog = true
                                    },
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                                }
                            }
                        }
                    }

                    FloatingActionButton(
                        onClick = { showFabMenu = !showFabMenu },
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(
                            imageVector = if (showFabMenu) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = "Действия"
                        )
                    }
                }
            }
        },
        bottomBar = {
            // Edge-to-edge: keeps the bars (and the FAB above them) clear of the system navigation bar.
            Column(modifier = Modifier.navigationBarsPadding()) {
                PasteFloatingBar(
                    itemCount = if (location.category == null) clipboardItems.size else 0,
                    operation = clipboardOp,
                    onPaste = { viewModel.pasteClipboard() },
                    onCancel = { viewModel.clearClipboard() }
                )

                val hasArchive = selectedItems.any { ArchiveManager.isArchive(it.name) }

                ActionBottomBar(
                    selectedCount = selectedItems.size,
                    hasArchiveSelected = hasArchive,
                    onSelectAll = { viewModel.selectAll() },
                    onClearSelection = { viewModel.clearSelection() },
                    onCopy = { viewModel.copySelected() },
                    onCut = { viewModel.cutSelected() },
                    onDelete = {
                        deleteConfirmTargets = selectedItems.toList()
                    },
                    onPermissions = {
                        selectedItems.firstOrNull()?.let { permissionsTarget = it }
                    },
                    canChangePermissions = storageType != StorageType.SMB,
                    onExtract = {
                        val archive = selectedItems.firstOrNull { ArchiveManager.isArchive(it.name) }
                        if (archive != null) {
                            extractTarget = archive
                        }
                    },
                    onCompress = {
                        showCompressDialog = true
                    },
                    onBatchRename = {
                        showBatchRenameDialog = true
                    }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            progress?.let { text ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // Breadcrumbs Navigation Bar
            BreadcrumbBar(
                segments = viewModel.getBreadcrumbs(),
                onSegmentClick = { segment ->
                    if (location.category == null) viewModel.navigateTo(segment.fullPath)
                }
            )

            // Content Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else if (errorMessage != null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage ?: "Ошибка",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { viewModel.loadCurrentDirectory() }) {
                            Text("Повторить попытку")
                        }
                    }
                } else if (fileList.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "Ничего не найдено" else "Папка пуста",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    if (isGridView) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(110.dp),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(fileList, key = { it.path }) { item ->
                                val isSelected = selectedItems.contains(item)
                                FileGridItem(
                                    item = item,
                                    isSelected = isSelected,
                                    isSelectionMode = isSelectionMode,
                                    onClick = {
                                        if (isSelectionMode) {
                                            viewModel.toggleSelection(item)
                                        } else if (item.isDirectory) {
                                            viewModel.navigateTo(item.path)
                                        } else if (ArchiveManager.isArchive(item.name)) {
                                            extractTarget = item
                                        } else {
                                            if (item.category == FileCategory.VIDEO) openVideo(item) else handleFileClick(item, viewModel, onOpenFileInEditor, onOpenImage, context)
                                        }
                                    },
                                    onLongClick = {
                                        viewModel.toggleSelection(item)
                                    }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(vertical = 4.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(fileList, key = { it.path }) { item ->
                                val isSelected = selectedItems.contains(item)
                                FileListItem(
                                    item = item,
                                    isSelected = isSelected,
                                    isSelectionMode = isSelectionMode,
                                    onClick = {
                                        if (isSelectionMode) {
                                            viewModel.toggleSelection(item)
                                        } else if (item.isDirectory) {
                                            viewModel.navigateTo(item.path)
                                        } else if (ArchiveManager.isArchive(item.name)) {
                                            extractTarget = item
                                        } else {
                                            if (item.category == FileCategory.VIDEO) openVideo(item) else handleFileClick(item, viewModel, onOpenFileInEditor, onOpenImage, context)
                                        }
                                    },
                                    onLongClick = {
                                        viewModel.toggleSelection(item)
                                    },
                                    onRename = { renameTarget = item },
                                    onDelete = { deleteConfirmTargets = listOf(item) },
                                    onPermissions = { permissionsTarget = item },
                                    onDetails = { detailsTarget = item }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun handleFileClick(
    item: FileItem,
    viewModel: FileExplorerViewModel,
    onOpenEditor: (FileItem) -> Unit,
    onOpenImage: (FileItem) -> Unit,
    context: Context
) {
    when (item.category) {
        FileCategory.CODE, FileCategory.TEXT -> onOpenEditor(item)
        FileCategory.IMAGE -> viewModel.withLocalCopy(item) { file ->
            onOpenImage(item.copy(path = file.absolutePath, storageType = StorageType.LOCAL, serverId = null))
        }
        else -> viewModel.withLocalCopy(item) { file -> openExternalIntent(context, item, file) }
    }
}

private fun openExternalIntent(context: Context, item: FileItem, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Открыть с помощью"))
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось открыть файл: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun VideoChoiceDialog(onDismiss: () -> Unit, onChoose: (builtIn: Boolean, remember: Boolean) -> Unit) {
    var remember by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Открыть видео") },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { remember = !remember }
            ) {
                Checkbox(checked = remember, onCheckedChange = { remember = it })
                Text("Запомнить выбор (можно поменять в настройках)")
            }
        },
        confirmButton = {
            Button(onClick = { onChoose(true, remember) }) { Text("Встроенный плеер") }
        },
        dismissButton = {
            TextButton(onClick = { onChoose(false, remember) }) { Text("Другое приложение") }
        }
    )
}

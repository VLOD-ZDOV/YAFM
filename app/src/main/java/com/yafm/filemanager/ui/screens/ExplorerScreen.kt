package com.yafm.filemanager.ui.screens

import com.yafm.filemanager.str
import com.yafm.filemanager.R
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.yafm.filemanager.data.settings.VideoPlayer
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
import com.yafm.filemanager.data.archive.ArchiveManager
import com.yafm.filemanager.data.model.FileCategory
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.data.model.StorageType
import com.yafm.filemanager.ui.components.ActionBottomBar
import com.yafm.filemanager.ui.components.BatchRenameDialog
import com.yafm.filemanager.ui.components.BreadcrumbBar
import com.yafm.filemanager.ui.components.CompressDialog
import com.yafm.filemanager.ui.components.ExtractArchiveDialog
import com.yafm.filemanager.ui.components.FileDetailsDialog
import com.yafm.filemanager.ui.components.FileGridItem
import com.yafm.filemanager.ui.components.FileListItem
import com.yafm.filemanager.ui.components.NewItemDialog
import com.yafm.filemanager.ui.components.PasteFloatingBar
import com.yafm.filemanager.ui.components.PermissionsDialog
import com.yafm.filemanager.ui.components.RenameDialog
import com.yafm.filemanager.ui.theme.RootColor
import com.yafm.filemanager.ui.viewmodel.FileExplorerViewModel
import com.yafm.filemanager.ui.viewmodel.SortMode
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    viewModel: FileExplorerViewModel,
    onNavigateBack: () -> Unit,
    onOpenFileInEditor: (FileItem) -> Unit,
    onOpenImage: (FileItem) -> Unit,
    onOpenVideo: (java.io.File, List<java.io.File>) -> Unit,
    onOpenAudio: (java.io.File, List<java.io.File>) -> Unit,
    onEditImage: (FileItem) -> Unit,
    onOpenDuplicates: () -> Unit
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
    val typeFilter by viewModel.typeFilter.collectAsState()
    val availableTypes by viewModel.availableTypes.collectAsState()
    val collapsedFolders by viewModel.collapsedFolders.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()

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
    var linkTarget by remember { mutableStateOf<FileItem?>(null) }
    var stripTargets by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    val advanced = viewModel.settings.settings.collectAsState().value.advanced

    fun playBuiltIn(item: FileItem) {
        viewModel.withLocalCopy(item) { file ->
            // Next/previous: the other videos of the same local folder.
            val playlist = if (item.storageType == StorageType.LOCAL || item.storageType == StorageType.ROOT) {
                fileList.filter { it.category == FileCategory.VIDEO }.map { java.io.File(it.path) }.filter { it.canRead() }.ifEmpty { listOf(file) }
            } else listOf(file)
            onOpenVideo(file, playlist)
        }
    }

    // The queue is the tracks of the item's own folder, also in a category gathered from many folders.
    fun playAudio(item: FileItem) {
        viewModel.withLocalCopy(item) { file ->
            val folder = item.path.substringBeforeLast('/')
            val playlist = if (item.storageType == StorageType.LOCAL || item.storageType == StorageType.ROOT) {
                fileList.filter { it.category == FileCategory.AUDIO && it.path.substringBeforeLast('/') == folder }
                    .map { java.io.File(it.path) }.filter { it.canRead() }.ifEmpty { listOf(file) }
            } else listOf(file)
            onOpenAudio(file, playlist)
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

    fun openItem(item: FileItem) {
        when {
            item.isDirectory -> viewModel.navigateTo(item.path)
            ArchiveManager.isArchive(item.name) -> extractTarget = item
            item.category == FileCategory.VIDEO -> openVideo(item)
            item.category == FileCategory.AUDIO -> playAudio(item)
            else -> handleFileClick(item, viewModel, onOpenFileInEditor, onOpenImage, context)
        }
    }

    // A file another app asked to open: once its folder is listed, it opens as if tapped.
    val pendingOpen by viewModel.pendingOpen.collectAsState()
    LaunchedEffect(pendingOpen, isLoading) {
        val path = pendingOpen ?: return@LaunchedEffect
        if (isLoading) return@LaunchedEffect
        viewModel.clearPendingOpen()
        (fileList.firstOrNull { it.path == path } ?: FileItem(File(path).name, path, size = File(path).length())).let { openItem(it) }
    }

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
            onExtract = { dest, conflict ->
                viewModel.extractArchive(item, dest, conflict) {
                    extractTarget = null
                    Toast.makeText(context, str(R.string.extracted_to, dest), Toast.LENGTH_SHORT).show()
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
            title = { Text(if (targets.size == 1) str(R.string.delete_2) else str(R.string.delete_items, targets.size)) },
            text = {
                Text(
                    if (targets.size == 1) str(R.string.will_be_deleted_permanently, targets[0].name)
                    else str(R.string.the_selected_items_will_be_deleted_perma)
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
                    Text(str(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmTargets = emptyList() }) {
                    Text(str(R.string.cancel))
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

    if (stripTargets.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { stripTargets = emptyList() },
            title = { Text(str(R.string.remove_metadata)) },
            text = { Text(str(R.string.remove_metadata_hint, stripTargets.size)) },
            confirmButton = {
                Button(onClick = {
                    viewModel.stripMetadata(stripTargets)
                    stripTargets = emptyList()
                }) { Text(str(R.string.remove_metadata)) }
            },
            dismissButton = { TextButton(onClick = { stripTargets = emptyList() }) { Text(str(R.string.cancel)) } }
        )
    }

    linkTarget?.let { item ->
        var name by remember(item) { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { linkTarget = null },
            title = { Text(str(R.string.create_link)) },
            text = {
                Column {
                    Text(str(R.string.link_hint, item.name), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(str(R.string.link_name)) }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createLink(item, name.trim())
                    linkTarget = null
                }, enabled = name.isNotBlank()) { Text(str(R.string.create_link)) }
            },
            dismissButton = { TextButton(onClick = { linkTarget = null }) { Text(str(R.string.cancel)) } }
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
                            placeholder = { Text(str(R.string.search_this_folder)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            trailingIcon = {
                                IconButton(onClick = {
                                    isSearchActive = false
                                    viewModel.setSearchQuery("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = str(R.string.close_search))
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
                                text = str(R.string.items, fileList.size, storageType.name),
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
                    }
                },
                actions = {
                    if (!isSearchActive) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = str(R.string.search))
                        }
                    }

                    IconButton(onClick = { viewModel.toggleGridView() }) {
                        Icon(
                            imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                            contentDescription = str(R.string.change_view)
                        )
                    }

                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = str(R.string.sort))
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            listOf(
                                SortMode.NAME_ASC to R.string.name_a_z,
                                SortMode.NAME_DESC to R.string.name_z_a,
                                SortMode.DATE_DESC to R.string.newest_first,
                                SortMode.DATE_ASC to R.string.oldest_first,
                                SortMode.SIZE_DESC to R.string.largest_first,
                                SortMode.SIZE_ASC to R.string.smallest_first
                            ).forEach { (mode, title) ->
                                DropdownMenuItem(
                                    text = { Text(str(title)) },
                                    trailingIcon = { if (mode == sortMode) Icon(Icons.Default.Check, contentDescription = null) },
                                    onClick = {
                                        viewModel.setSortMode(mode)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }

                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = str(R.string.menu))
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (showHidden) str(R.string.hide_hidden_files) else str(R.string.show_hidden_files)) },
                                onClick = {
                                    viewModel.toggleHiddenFiles()
                                    showMoreMenu = false
                                }
                            )
                            if (location.category == null && fileList.any { it.category == FileCategory.AUDIO }) {
                                DropdownMenuItem(
                                    text = { Text(str(R.string.play_folder)) },
                                    onClick = {
                                        showMoreMenu = false
                                        fileList.firstOrNull { it.category == FileCategory.AUDIO }?.let { playAudio(it) }
                                    }
                                )
                            }
                            if (location.category == null) {
                                val favorite = viewModel.isFavorite(location)
                                DropdownMenuItem(
                                    text = { Text(if (favorite) str(R.string.remove_from_favorites) else str(R.string.add_to_favorites)) },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.toggleFavorite(location)
                                    }
                                )
                            }
                            if (location.category != null) {
                                val folders = FileExplorerViewModel.groupByFolder(fileList, location.path).mapNotNull { it.first }
                                val allCollapsed = folders.isNotEmpty() && collapsedFolders.containsAll(folders)
                                DropdownMenuItem(
                                    text = { Text(if (allCollapsed) str(R.string.expand_all) else str(R.string.collapse_all)) },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.setFoldersCollapsed(folders, !allCollapsed)
                                    }
                                )
                            }
                            if (viewModel.canFindDuplicates) {
                                DropdownMenuItem(
                                    text = { Text(str(R.string.find_duplicates)) },
                                    onClick = {
                                        showMoreMenu = false
                                        viewModel.findDuplicates()
                                        onOpenDuplicates()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(str(R.string.refresh)) },
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
                                    Text(str(R.string.new_file), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp)
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
                                    Text(str(R.string.new_folder_2), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp)
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
                            contentDescription = str(R.string.actions)
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
                    },
                    onShare = {
                        viewModel.withLocalCopies(selectedItems.toList()) { files -> shareFiles(context, files) }
                    },
                    moreActions = buildList {
                        val single = selectedItems.singleOrNull()
                        if (single != null) {
                            add(str(R.string.rename) to { viewModel.clearSelection(); renameTarget = single })
                            if (single.category == FileCategory.IMAGE && single.storageType == StorageType.LOCAL) {
                                add(str(R.string.edit_image) to { viewModel.clearSelection(); onEditImage(single) })
                            }
                            if (!single.isDirectory) add(str(R.string.open_as_text) to { viewModel.clearSelection(); onOpenFileInEditor(single) })
                            add(str(R.string.properties) to { viewModel.clearSelection(); detailsTarget = single })
                            if (single.isDirectory) add(str(R.string.add_to_favorites) to { viewModel.clearSelection(); viewModel.addFavorite(single) })
                            if (advanced) add(str(R.string.create_link) to { viewModel.clearSelection(); linkTarget = single })
                        }
                        val photos = viewModel.strippable(selectedItems)
                        if (photos.isNotEmpty()) add(str(R.string.remove_metadata) to { viewModel.clearSelection(); stripTargets = photos })
                        add(str(R.string.invert_selection) to { viewModel.invertSelection() })
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

            // A category can be narrowed to some of its file types (pdf + docx, flac only...).
            if (location.category != null && availableTypes.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(selected = typeFilter.isEmpty(), onClick = { viewModel.clearTypeFilter() }, label = { Text(str(R.string.all)) })
                    availableTypes.forEach { (ext, count) ->
                        FilterChip(
                            selected = ext in typeFilter,
                            onClick = { viewModel.toggleType(ext) },
                            label = { Text("${ext.ifEmpty { "?" }} ($count)") }
                        )
                    }
                }
            }

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
                            text = errorMessage ?: str(R.string.error_2),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { viewModel.loadCurrentDirectory() }) {
                            Text(str(R.string.try_again))
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
                            text = if (searchQuery.isNotEmpty()) str(R.string.nothing_found) else str(R.string.empty_folder),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    fun onItemClick(item: FileItem) = if (isSelectionMode) viewModel.toggleSelection(item) else openItem(item)
                    // A category gathers files from many folders: they come grouped, each under its folder.
                    val sections = remember(fileList, location) {
                        if (location.category != null) FileExplorerViewModel.groupByFolder(fileList, location.path)
                        else listOf(null to fileList)
                    }
                    if (isGridView) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(110.dp),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            sections.forEach { (folder, group) ->
                                val collapsed = folder != null && folder in collapsedFolders
                                if (folder != null) {
                                    item(key = "folder:$folder", span = { GridItemSpan(maxLineSpan) }) {
                                    FolderHeader(
                                        folder = folder,
                                        count = group.size,
                                        collapsed = collapsed,
                                        onToggleCollapse = { viewModel.toggleFolderCollapsed(folder) },
                                        allSelected = selectedItems.containsAll(group),
                                        onToggleSelection = { viewModel.toggleGroupSelection(group) },
                                        onPlay = if (location.category == FileCategory.AUDIO) ({ playAudio(group.first()) }) else null
                                    )
                                }
                                }
                                if (!collapsed) items(group, key = { it.path }) { item ->
                                    FileGridItem(
                                        item = item,
                                        isSelected = selectedItems.contains(item),
                                        isSelectionMode = isSelectionMode,
                                        onClick = { onItemClick(item) },
                                        onLongClick = { viewModel.toggleSelection(item) }
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(vertical = 4.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            sections.forEach { (folder, group) ->
                                val collapsed = folder != null && folder in collapsedFolders
                                if (folder != null) {
                                    item(key = "folder:$folder") {
                                    FolderHeader(
                                        folder = folder,
                                        count = group.size,
                                        collapsed = collapsed,
                                        onToggleCollapse = { viewModel.toggleFolderCollapsed(folder) },
                                        allSelected = selectedItems.containsAll(group),
                                        onToggleSelection = { viewModel.toggleGroupSelection(group) },
                                        onPlay = if (location.category == FileCategory.AUDIO) ({ playAudio(group.first()) }) else null
                                    )
                                }
                                }
                                if (!collapsed) items(group, key = { it.path }) { item ->
                                    FileListItem(
                                        item = item,
                                        isSelected = selectedItems.contains(item),
                                        isSelectionMode = isSelectionMode,
                                        onClick = { onItemClick(item) },
                                        onLongClick = { viewModel.toggleSelection(item) },
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

private fun shareFiles(context: Context, files: List<File>) {
    if (files.isEmpty()) return
    try {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
        val types = files.map { FileItem(it.name, it.path).mimeType }.distinct()
        // One type for all files, or its family ("image/*"), or anything.
        val type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, str(R.string.share)))
    } catch (e: Exception) {
        Toast.makeText(context, str(R.string.could_not_open_the_file, e.message), Toast.LENGTH_SHORT).show()
    }
}

private fun openExternalIntent(context: Context, item: FileItem, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, str(R.string.open_with)))
    } catch (e: Exception) {
        Toast.makeText(context, str(R.string.could_not_open_the_file, e.message), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun VideoChoiceDialog(onDismiss: () -> Unit, onChoose: (builtIn: Boolean, remember: Boolean) -> Unit) {
    var remember by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.open_video)) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { remember = !remember }
            ) {
                Checkbox(checked = remember, onCheckedChange = { remember = it })
                Text(str(R.string.remember_can_be_changed_in_settings))
            }
        },
        confirmButton = {
            Button(onClick = { onChoose(true, remember) }) { Text(str(R.string.built_in_player)) }
        },
        dismissButton = {
            TextButton(onClick = { onChoose(false, remember) }) { Text(str(R.string.another_app)) }
        }
    )
}

@Composable
private fun FolderHeader(
    folder: String,
    count: Int,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    allSelected: Boolean,
    onToggleSelection: () -> Unit,
    onPlay: (() -> Unit)?
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onToggleCollapse)
            .padding(start = 8.dp, end = 4.dp)
    ) {
        Icon(
            if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
            contentDescription = if (collapsed) str(R.string.expand) else str(R.string.collapse),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$folder ($count)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onPlay != null) {
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = str(R.string.play_folder), tint = MaterialTheme.colorScheme.primary)
            }
        }
        IconButton(onClick = onToggleSelection) {
            Icon(
                if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = str(R.string.select_folder),
                tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

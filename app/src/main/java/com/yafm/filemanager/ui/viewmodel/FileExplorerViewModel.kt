package com.yafm.filemanager.ui.viewmodel

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yafm.filemanager.YafmApp
import com.yafm.filemanager.data.archive.ArchiveManager
import com.yafm.filemanager.data.dupes.DuplicateFinder
import com.yafm.filemanager.data.media.Metadata
import com.yafm.filemanager.data.fs.TextSniffer
import com.yafm.filemanager.data.fs.isHidden
import com.yafm.filemanager.data.fs.walkFiles
import com.yafm.filemanager.data.fs.joinPath
import com.yafm.filemanager.data.model.FileCategory
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.data.model.Location
import com.yafm.filemanager.data.model.RootInfo
import com.yafm.filemanager.data.model.ServerConfig
import com.yafm.filemanager.data.model.StorageStats
import com.yafm.filemanager.data.model.StorageType
import com.yafm.filemanager.data.qsh.Qsh
import com.yafm.filemanager.data.root.RootCommander
import com.yafm.filemanager.data.update.Release
import com.yafm.filemanager.data.update.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class SortMode {
    NAME_ASC,
    NAME_DESC,
    DATE_DESC,
    DATE_ASC,
    SIZE_DESC,
    SIZE_ASC
}

enum class ClipboardOp {
    NONE,
    COPY,
    CUT
}

data class PathSegment(
    val name: String,
    val fullPath: String
)

class FileExplorerViewModel(application: Application) : AndroidViewModel(application) {

    val repository = (application as YafmApp).repository
    val serverRepo = repository.servers
    val settings = (application as YafmApp).settings
    val updater = Updater(application)

    private val internalRoot = Environment.getExternalStorageDirectory().absolutePath

    private val _location = MutableStateFlow(Location(StorageType.LOCAL, internalRoot))
    val location: StateFlow<Location> = _location.asStateFlow()

    val currentPath: StateFlow<String> = _location.map { it.path }
        .stateIn(viewModelScope, SharingStarted.Eagerly, internalRoot)
    val currentStorageType: StateFlow<StorageType> = _location.map { it.type }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StorageType.LOCAL)
    val currentServer: StateFlow<ServerConfig?> = _location.map { it.server }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val rawFiles = MutableStateFlow<List<FileItem>>(emptyList())

    private val _storageStats = MutableStateFlow<List<StorageStats>>(emptyList())
    val storageStats: StateFlow<List<StorageStats>> = _storageStats.asStateFlow()

    private val _rootInfo = MutableStateFlow(RootInfo())
    val rootInfo: StateFlow<RootInfo> = _rootInfo.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Why the current directory could not be listed (shown instead of the list). */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** One-off messages about operations (shown as toasts). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** What a long operation (paste, delete...) is doing right now. */
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showHiddenFiles = MutableStateFlow(settings.settings.value.showHidden)
    val showHiddenFiles: StateFlow<Boolean> = _showHiddenFiles.asStateFlow()

    private val _isGridView = MutableStateFlow(savedGrid(_location.value))
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    /** Each folder, category and server folder keeps its own sort order; NAME_ASC is not stored. */
    private fun sortKey(loc: Location) = loc.category?.let { "category:${it.name}" }
        ?: "${loc.type.name}:${loc.server?.id ?: ""}:${loc.path}"

    private fun savedSort(loc: Location) = settings.folderPref(sortKey(loc))
        ?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: SortMode.NAME_ASC

    private val _sortMode = MutableStateFlow(savedSort(_location.value))
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    /** Extensions shown in a category; empty: all of them. */
    private val _typeFilter = MutableStateFlow<Set<String>>(emptySet())
    val typeFilter: StateFlow<Set<String>> = _typeFilter.asStateFlow()

    /** Extensions present in the current category with their file counts, most common first. */
    val availableTypes: StateFlow<List<Pair<String, Int>>> = combine(rawFiles, _location) { files, loc ->
        if (loc.category == null) emptyList()
        else files.groupingBy { it.extension }.eachCount().toList().sortedByDescending { it.second }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val fileList: StateFlow<List<FileItem>> =
        combine(rawFiles, _searchQuery, _showHiddenFiles, _sortMode, _typeFilter) { files, query, hidden, sort, types ->
            // A remembered type may be gone from the folder: it must not hide everything.
            val shown = types.intersect(files.mapTo(HashSet()) { it.extension })
            filterAndSort(files.filter { shown.isEmpty() || it.extension in shown }, query.trim().lowercase(), hidden, sort)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedItems = MutableStateFlow<Set<FileItem>>(emptySet())
    val selectedItems: StateFlow<Set<FileItem>> = _selectedItems.asStateFlow()

    private val _clipboardItems = MutableStateFlow<List<FileItem>>(emptyList())
    val clipboardItems: StateFlow<List<FileItem>> = _clipboardItems.asStateFlow()

    private val _clipboardOp = MutableStateFlow(ClipboardOp.NONE)
    val clipboardOp: StateFlow<ClipboardOp> = _clipboardOp.asStateFlow()

    /** A qsh server asks for a one-time code; [retry] runs again once it is given. */
    data class CodeRequest(val server: ServerConfig, val prompt: String, val retry: () -> Unit)

    private val _codeRequest = MutableStateFlow<CodeRequest?>(null)
    val codeRequest: StateFlow<CodeRequest?> = _codeRequest.asStateFlow()

    fun submitCode(code: String) {
        val request = _codeRequest.value ?: return
        _codeRequest.value = null
        repository.provideQshCode(request.server, code)
        request.retry()
    }

    fun cancelCode() {
        _codeRequest.value = null
    }

    /**
     * True when [e] was a code request, which is then shown instead of an error. The code goes to the
     * server that asked, which need not be the one open (a paste from another server).
     */
    private fun askForCode(e: Throwable, retry: () -> Unit): Boolean {
        if (e !is Qsh.CodeRequired) return false
        val server = serverRepo.getServer(e.serverId) ?: return false
        _codeRequest.value = CodeRequest(server, e.prompt, retry)
        return true
    }

    private val history = ArrayDeque<Location>()
    private val scrollPositions = mutableMapOf<Location, Pair<Int, Int>>()

    fun scrollPosition(location: Location): Pair<Int, Int> = scrollPositions[location] ?: (0 to 0)

    fun saveScrollPosition(location: Location, index: Int, offset: Int) {
        scrollPositions[location] = index to offset
    }
    private var loadJob: Job? = null

    init {
        refreshDashboard()
        loadCurrentDirectory()
    }

    fun refreshDashboard() {
        viewModelScope.launch {
            val info = RootCommander.checkRoot()
            repository.rootGranted = info.isRootGranted
            _rootInfo.value = info
            _storageStats.value = repository.getStorageStats()
        }
    }

    fun loadCurrentDirectory() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _selectedItems.value = emptySet()
            try {
                rawFiles.value = repository.list(_location.value)
            } catch (e: Exception) {
                if (!askForCode(e) { loadCurrentDirectory() }) {
                    _errorMessage.value = describe(e)
                }
                rawFiles.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Navigation

    private fun go(target: Location) {
        if (target == _location.value) return loadCurrentDirectory()
        history.addLast(_location.value)
        show(target)
        loadCurrentDirectory()
    }

    /** Another directory in the current storage. */
    fun navigateTo(path: String) = go(_location.value.copy(path = path))

    private fun show(target: Location) {
        _location.value = target
        _searchQuery.value = ""
        _typeFilter.value = savedTypes(target)
        _sortMode.value = savedSort(target)
        _isGridView.value = savedGrid(target)
    }

    /** Entering the explorer from outside: history starts over, so Back from here returns to where it came from. */
    private fun enter(target: Location) {
        history.clear()
        show(target)
        loadCurrentDirectory()
    }

    fun navigateToRoot(path: String = "/") = enter(Location(StorageType.ROOT, path))

    fun navigateToInternalStorage() = enter(Location(StorageType.LOCAL, internalRoot))

    fun navigateToLocal(path: String) = enter(Location(StorageType.LOCAL, path))

    fun openCategory(category: FileCategory) = enter(Location(StorageType.LOCAL, internalRoot, category = category))

    fun navigateToServer(server: ServerConfig) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                enter(Location(server.type, repository.startPath(server), server))
            } catch (e: Exception) {
                _isLoading.value = false
                if (!askForCode(e) { navigateToServer(server) }) {
                    _messages.tryEmit("${server.name}: ${describe(e)}")
                }
            }
        }
    }

    // Favorite folders: any storage, shown on the home screen.

    data class Favorite(val type: StorageType, val serverId: String?, val path: String) {
        val key get() = "${type.name}\t${serverId ?: ""}\t$path"
        val name get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
    }

    private fun parseFavorite(key: String): Favorite? {
        val parts = key.split('\t')
        if (parts.size != 3) return null
        val type = runCatching { StorageType.valueOf(parts[0]) }.getOrNull() ?: return null
        return Favorite(type, parts[1].ifEmpty { null }, parts[2])
    }

    val favorites: StateFlow<List<Favorite>> = settings.settings.map { s -> s.favorites.mapNotNull { parseFavorite(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun Location.favorite() = Favorite(type, server?.id, path)

    fun isFavorite(location: Location) = location.category == null && location.favorite() in favorites.value

    fun toggleFavorite(location: Location) {
        val key = location.favorite().key
        settings.update { s -> s.copy(favorites = if (key in s.favorites) s.favorites - key else s.favorites + key) }
    }

    fun addFavorite(item: FileItem) {
        val key = Favorite(item.storageType, item.serverId, item.path).key
        settings.update { s -> if (key in s.favorites) s else s.copy(favorites = s.favorites + key) }
        _messages.tryEmit(str(R.string.added_to_favorites))
    }

    fun removeFavorite(favorite: Favorite) = settings.update { s -> s.copy(favorites = s.favorites - favorite.key) }

    /** False when the favorite's server is gone. */
    fun openFavorite(favorite: Favorite): Boolean {
        val server = favorite.serverId?.let { serverRepo.getServer(it) ?: return false }
        enter(Location(favorite.type, favorite.path, server))
        return true
    }

    // Files opened from other apps

    private val _pendingOpen = MutableStateFlow<String?>(null)
    val pendingOpen: StateFlow<String?> = _pendingOpen.asStateFlow()

    fun clearPendingOpen() {
        _pendingOpen.value = null
    }

    /** Shows the file's folder and opens the file there (or only selects it with [open] false). */
    fun showIncoming(file: File, open: Boolean) {
        navigateToLocal(file.parent ?: "/")
        if (open) _pendingOpen.value = file.absolutePath
    }

    /** A copy from another app kept in Download, under a free name. */
    fun saveToDownloads(file: File) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dest = ArchiveManager.freeFile(File(dir, file.name))
                file.copyTo(dest)
                dest
            }.onSuccess { _messages.tryEmit(str(R.string.saved_as, it.absolutePath)); showIncoming(it, open = false) }
                .onFailure { _messages.tryEmit(describe(it)) }
        }
    }

    fun navigateBack(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        show(previous)
        loadCurrentDirectory()
        return true
    }

    fun getBreadcrumbs(): List<PathSegment> {
        val loc = _location.value
        loc.category?.let { return listOf(PathSegment(categoryTitle(it), "")) }
        val base = when (loc.type) {
            StorageType.LOCAL -> if (loc.path.startsWith(internalRoot)) internalRoot else "/"
            else -> "/"
        }
        val title = when (loc.type) {
            StorageType.ROOT -> "Root (/)"
            StorageType.LOCAL -> if (base == internalRoot) str(R.string.storage_2) else "/"
            else -> loc.server?.name ?: loc.type.name
        }
        val segments = mutableListOf(PathSegment(title, base))
        var cumulative = base
        loc.path.removePrefix(base).split('/').filter { it.isNotEmpty() }.forEach { part ->
            cumulative = joinPath(cumulative, part)
            segments += PathSegment(part, cumulative)
        }
        return segments
    }

    fun categoryTitle(category: FileCategory) = when (category) {
        FileCategory.IMAGE -> str(R.string.all_images)
        FileCategory.VIDEO -> str(R.string.all_videos)
        FileCategory.AUDIO -> str(R.string.all_music)
        FileCategory.DOCUMENT -> str(R.string.all_documents)
        FileCategory.ARCHIVE -> str(R.string.all_archives)
        FileCategory.RECENT -> str(R.string.recent)
        else -> category.name
    }

    // Search and sort

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleHiddenFiles() {
        _showHiddenFiles.value = !_showHiddenFiles.value
        settings.update { it.copy(showHidden = _showHiddenFiles.value) }
    }

    /** The view is kept per folder; folders never switched follow the settings' default. */
    fun toggleGridView() {
        _isGridView.value = !_isGridView.value
        settings.setFolderPref("grid|" + sortKey(_location.value), _isGridView.value.toString())
    }

    private fun savedGrid(loc: Location) =
        settings.folderPref("grid|" + sortKey(loc))?.toBooleanStrictOrNull() ?: settings.settings.value.gridView

    private fun savedTypes(loc: Location) =
        settings.folderPref("types|" + sortKey(loc))?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private fun saveTypes() =
        settings.setFolderPref("types|" + sortKey(_location.value), _typeFilter.value.joinToString(",").ifEmpty { null })

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        settings.setFolderPref(sortKey(_location.value), mode.name.takeIf { mode != SortMode.NAME_ASC })
    }

    fun toggleType(extension: String) {
        val current = _typeFilter.value
        _typeFilter.value = if (extension in current) current - extension else current + extension
        _selectedItems.value = emptySet()
        saveTypes()
    }

    fun clearTypeFilter() {
        _typeFilter.value = emptySet()
        _selectedItems.value = emptySet()
        saveTypes()
    }

    private fun filterAndSort(items: List<FileItem>, query: String, showHidden: Boolean, sort: SortMode): List<FileItem> {
        val filtered = items.filter { (showHidden || !it.isHidden) && (query.isEmpty() || it.name.contains(query, ignoreCase = true)) }
        val dirsFirst = compareBy<FileItem> { !it.isDirectory }
        return filtered.sortedWith(
            when (sort) {
                // Compared in place: lowercase() would make two new strings for every comparison.
                SortMode.NAME_ASC -> dirsFirst.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                SortMode.NAME_DESC -> dirsFirst.thenByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
                SortMode.DATE_DESC -> dirsFirst.thenByDescending { it.lastModified }
                SortMode.DATE_ASC -> dirsFirst.thenBy { it.lastModified }
                SortMode.SIZE_DESC -> dirsFirst.thenByDescending { it.size }
                SortMode.SIZE_ASC -> dirsFirst.thenBy { it.size }
            }
        )
    }

    // Selection

    fun toggleSelection(item: FileItem) {
        val current = _selectedItems.value
        _selectedItems.value = if (item in current) current - item else current + item
    }

    /** Folded folder headers of the open category, by their shown name. */
    val collapsedFolders: StateFlow<Set<String>> = combine(settings.settings, _location) { s, loc ->
        val prefix = "${loc.category?.name}:"
        s.collapsedFolders.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleFolderCollapsed(folder: String) {
        val key = "${_location.value.category?.name}:$folder"
        settings.update { s ->
            s.copy(collapsedFolders = if (key in s.collapsedFolders) s.collapsedFolders - key else s.collapsedFolders + key)
        }
    }

    /** Folds or unfolds every given folder of the open category. */
    fun setFoldersCollapsed(folders: List<String>, collapsed: Boolean) {
        val keys = folders.map { "${_location.value.category?.name}:$it" }.toSet()
        settings.update { s -> s.copy(collapsedFolders = if (collapsed) s.collapsedFolders + keys else s.collapsedFolders - keys) }
    }

    /** A folder's files in a category: selects them all, or clears them when all are already selected. */
    fun toggleGroupSelection(group: List<FileItem>) {
        val current = _selectedItems.value
        _selectedItems.value = if (current.containsAll(group)) current - group.toSet() else current + group
    }

    fun invertSelection() {
        val current = _selectedItems.value
        _selectedItems.value = fileList.value.filterNot { it in current }.toSet()
    }

    fun selectAll() {
        _selectedItems.value = fileList.value.toSet()
    }

    fun clearSelection() {
        _selectedItems.value = emptySet()
    }

    // Clipboard: works across storages (phone <-> server, server <-> server).

    fun copySelected() = toClipboard(ClipboardOp.COPY)

    fun cutSelected() = toClipboard(ClipboardOp.CUT)

    private fun toClipboard(op: ClipboardOp) {
        _clipboardItems.value = _selectedItems.value.toList()
        _clipboardOp.value = op
        clearSelection()
    }

    fun clearClipboard() {
        _clipboardItems.value = emptyList()
        _clipboardOp.value = ClipboardOp.NONE
    }

    fun pasteClipboard() {
        val items = _clipboardItems.value
        val move = _clipboardOp.value == ClipboardOp.CUT
        operation {
            val skipped = repository.transfer(items, _location.value, move) { _progress.value = it }
            clearClipboard()
            _messages.tryEmit(
                (if (move) str(R.string.moved, items.size) else str(R.string.copied, items.size)) +
                    if (skipped > 0) str(R.string.links_to_folders_are_not_copied_skipped, skipped) else ""
            )
        }
    }

    // File operations

    /**
     * Runs a change in the current directory: shows progress, reports the result or the error,
     * and reloads the listing either way (a partial failure still changed something).
     */
    private fun operation(success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            _progress.value = "…"
            try {
                block()
                success?.let { _messages.tryEmit(it) }
            } catch (e: Exception) {
                if (!askForCode(e) { operation(success, block) }) {
                    _messages.tryEmit(describe(e))
                }
            } finally {
                _progress.value = null
                loadCurrentDirectory()
            }
        }
    }

    fun deleteSelected(onDone: () -> Unit = {}) {
        val items = _selectedItems.value.toList()
        clearSelection()
        deleteItems(items, onDone)
    }

    fun deleteItems(items: List<FileItem>, onDone: () -> Unit = {}) {
        operation(str(R.string.deleted, items.size)) {
            items.forEachIndexed { i, item ->
                _progress.value = "${i + 1}/${items.size}: ${item.name}"
                repository.delete(item)
            }
            onDone()
        }
    }

    fun renameFile(item: FileItem, newName: String) = operation { repository.rename(item, newName) }

    fun createFolder(name: String) = operation { repository.createDirectory(_location.value, name) }

    fun createNewFile(name: String) = operation { repository.createFile(_location.value, name) }

    /** Photos whose details (date, place, camera) can be removed: local JPEG, PNG and WebP. */
    fun strippable(items: Collection<FileItem>) =
        items.filter { it.storageType == StorageType.LOCAL && !it.isDirectory && it.extension in Metadata.SUPPORTED }

    fun stripMetadata(items: List<FileItem>) = operation(str(R.string.metadata_removed, items.size)) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            items.forEachIndexed { i, item ->
                _progress.value = "${i + 1}/${items.size}: ${item.name}"
                val copy = Metadata.cleanCopy(File(item.path))
                android.media.MediaScannerConnection.scanFile(getApplication(), arrayOf(copy.absolutePath), null, null)
            }
        }
    }

    fun createLink(target: FileItem, name: String) = operation(str(R.string.link_created)) {
        repository.createLink(target, _location.value, name)
    }

    fun changePermissions(item: FileItem, octal: String) = operation(str(R.string.permissions_changed)) {
        repository.changePermissions(item, octal)
    }

    /** Whether a file of an unknown kind holds text; files on servers are not read for this. */
    fun looksLikeText(item: FileItem, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val text = item.storageType == StorageType.LOCAL &&
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TextSniffer.looksLikeText(File(item.path)) }
            onResult(text)
        }
    }

    /** Local files for sharing; remote ones are fetched to the cache first. Folders are left out. */
    fun withLocalCopies(items: List<FileItem>, onReady: (List<File>) -> Unit) {
        viewModelScope.launch {
            try {
                val files = items.filter { !it.isDirectory }.mapIndexed { i, item ->
                    _progress.value = str(R.string.loading, "${i + 1}/${items.size}: ${item.name}")
                    repository.materialize(item)
                }
                onReady(files)
            } catch (e: Exception) {
                _messages.tryEmit(describe(e))
            } finally {
                _progress.value = null
            }
        }
    }

    /** Opens a file in a viewer or another app: remote files are fetched to the cache first. */
    fun withLocalCopy(item: FileItem, onReady: (File) -> Unit) {
        viewModelScope.launch {
            _progress.value = str(R.string.loading, item.name)
            try {
                onReady(repository.materialize(item))
            } catch (e: Exception) {
                _messages.tryEmit(describe(e))
            } finally {
                _progress.value = null
            }
        }
    }

    // Archives: remote archives are fetched first; the result is written locally.

    private val _archiveProgress = MutableStateFlow<ArchiveManager.Progress?>(null)
    val archiveProgress: StateFlow<ArchiveManager.Progress?> = _archiveProgress.asStateFlow()

    private val _isArchiveProcessing = MutableStateFlow(false)
    val isArchiveProcessing: StateFlow<Boolean> = _isArchiveProcessing.asStateFlow()

    /** Where archive results go: the current folder if it is local, else Download. */
    fun archiveOutputDir(): String =
        _location.value.takeIf { it.type == StorageType.LOCAL }?.path
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    fun extractArchive(archiveItem: FileItem, targetDir: String, conflict: ArchiveManager.Conflict, onDone: () -> Unit) {
        viewModelScope.launch {
            _isArchiveProcessing.value = true
            try {
                val src = repository.materialize(archiveItem)
                ArchiveManager.extractArchive(src, File(targetDir), conflict) { _archiveProgress.value = it }
                onDone()
            } catch (e: Exception) {
                _messages.tryEmit(describe(e))
            } finally {
                _isArchiveProcessing.value = false
                _archiveProgress.value = null
                loadCurrentDirectory()
            }
        }
    }

    fun compressSelected(archiveName: String, format: ArchiveManager.Format, onDone: () -> Unit) {
        viewModelScope.launch {
            _isArchiveProcessing.value = true
            try {
                val sources = _selectedItems.value.map { repository.materialize(it) }
                // An archive with this name already there is kept: the new one gets " (1)".
                val dest = ArchiveManager.freeFile(File(archiveOutputDir(), archiveName))
                if (dest.name != archiveName) _messages.tryEmit(str(R.string.saved_as, dest.name))
                ArchiveManager.createArchive(sources, dest, format) { _archiveProgress.value = it }
                clearSelection()
                onDone()
            } catch (e: Exception) {
                _messages.tryEmit(describe(e))
            } finally {
                _isArchiveProcessing.value = false
                _archiveProgress.value = null
                loadCurrentDirectory()
            }
        }
    }

    fun batchRenameSelected(prefix: String, suffix: String, find: String, replace: String, addIndex: Boolean) {
        val items = _selectedItems.value.toList()
        clearSelection()
        operation(str(R.string.renamed, items.size)) {
            items.forEachIndexed { i, item ->
                val newName = batchName(item, i, prefix, suffix, find, replace, addIndex)
                if (newName != item.name) repository.rename(item, newName)
            }
        }
    }

    fun testConnection(config: ServerConfig, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { repository.testConnection(config) }.exceptionOrNull()?.let { describe(it) })
        }
    }

    fun pairQsh(config: ServerConfig, code: String, onResult: (ok: Boolean, message: String) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.pairQsh(config, code) }
                .onSuccess { onResult(true, it.ifBlank { str(R.string.paired) }) }
                .onFailure { onResult(false, describe(it)) }
        }
    }

    // Duplicates: in the open category, or in the open local folder and all its subfolders.

    /** Null: no search yet. */
    private val _duplicates = MutableStateFlow<List<List<FileItem>>?>(null)
    val duplicates: StateFlow<List<List<FileItem>>?> = _duplicates.asStateFlow()

    private val _duplicatesProgress = MutableStateFlow<String?>(null)
    val duplicatesProgress: StateFlow<String?> = _duplicatesProgress.asStateFlow()

    private var duplicatesJob: Job? = null

    val canFindDuplicates: Boolean
        get() = _location.value.let { it.category != null || it.type == StorageType.LOCAL }

    fun findDuplicates() {
        val loc = _location.value
        val categoryFiles = if (loc.category != null) fileList.value else null
        val showHidden = _showHiddenFiles.value
        duplicatesJob?.cancel()
        _duplicates.value = null
        duplicatesJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _duplicatesProgress.value = str(R.string.scanning_files)
            val files = categoryFiles ?: buildList {
                walkFiles(loc.path, enter = { showHidden || !it.isHidden }) { path, attrs ->
                    ensureActive()
                    if (showHidden || !path.isHidden) {
                        add(FileItem(name = path.fileName.toString(), path = path.toString(), size = attrs.size(), lastModified = attrs.lastModifiedTime().toMillis()))
                    }
                }
            }
            val groups = DuplicateFinder.find(files) { done, total ->
                ensureActive()
                if (done % 20 == 0 || done == total) _duplicatesProgress.value = str(R.string.checked_of, done, total)
            }
            _duplicates.value = groups
            _duplicatesProgress.value = null
        }
    }

    fun cancelDuplicates() {
        duplicatesJob?.cancel()
        _duplicatesProgress.value = null
        _duplicates.value = null
    }

    fun deleteDuplicates(items: List<FileItem>) {
        val gone = items.toSet()
        _duplicates.value = _duplicates.value?.map { it - gone }?.filter { it.size > 1 }
        deleteItems(items)
    }

    // Updates from GitHub releases

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data object UpToDate : UpdateState
        data class Available(val release: Release) : UpdateState
        data class Downloading(val release: Release, val progress: Float) : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /** [manual]: from the settings screen, so "no update" and errors are shown too. */
    fun checkForUpdates(manual: Boolean) {
        if (!manual && (!settings.settings.value.autoUpdate || !updater.checkDue())) return
        viewModelScope.launch {
            _update.value = UpdateState.Checking
            _update.value = try {
                updater.newerRelease()?.let { UpdateState.Available(it) }
                    ?: if (manual) UpdateState.UpToDate else UpdateState.Idle
            } catch (e: Exception) {
                if (manual) UpdateState.Failed(describe(e)) else UpdateState.Idle
            }
        }
    }

    fun installUpdate(release: Release) {
        viewModelScope.launch {
            try {
                val apk = updater.download(release) { _update.value = UpdateState.Downloading(release, it) }
                _update.value = UpdateState.Idle
                if (!updater.install(apk)) {
                    _messages.tryEmit(str(R.string.allow_installs_from_yafm_then_tap_update))
                    _update.value = UpdateState.Available(release)
                }
            } catch (e: Exception) {
                _update.value = UpdateState.Failed(describe(e))
            }
        }
    }

    fun dismissUpdate() {
        _update.value = UpdateState.Idle
    }

    companion object {
        /**
         * Files of a category grouped by folder (shown relative to [root]), folders in name order,
         * each keeping the files' order.
         */
        /** How a category screen is split: recent files by day, the other categories by folder. */
        fun sections(items: List<FileItem>, location: Location): List<Pair<String?, List<FileItem>>> = when (location.category) {
            null -> listOf(null to items)
            FileCategory.RECENT -> groupByDay(items)
            else -> groupByFolder(items, location.path)
        }

        /** Newest first, under "Today", "Yesterday" or the date. */
        fun groupByDay(items: List<FileItem>, now: Long = System.currentTimeMillis()): List<Pair<String?, List<FileItem>>> {
            val cal = java.util.Calendar.getInstance()
            fun day(ms: Long): Int {
                cal.timeInMillis = ms
                return cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
            }
            val today = day(now)
            val yesterday = day(now - 86_400_000L)
            val format = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault())
            return items.sortedByDescending { maxOf(it.lastModified, it.added) }
                .groupBy { day(maxOf(it.lastModified, it.added)) }
                .map { (d, group) ->
                    val title = when (d) {
                        today -> str(R.string.today)
                        yesterday -> str(R.string.yesterday)
                        else -> format.format(java.util.Date(maxOf(group[0].lastModified, group[0].added)))
                    }
                    title to group
                }
        }

        /** "Added 19:40 · Download/Telegram": what happened to a recent file, when, and its folder. */
        fun recentNote(item: FileItem, root: String): String {
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(maxOf(item.lastModified, item.added)))
            val folder = item.path.substringBeforeLast('/').removePrefix(root).trimStart('/').ifEmpty { "/" }
            return "${str(if (item.isNew) R.string.added else R.string.changed)} $time · $folder"
        }

        fun groupByFolder(items: List<FileItem>, root: String): List<Pair<String?, List<FileItem>>> =
            items.groupBy { it.path.substringBeforeLast('/', "") }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .map { (dir, group) ->
                    val shown = dir.removePrefix(root).trimStart('/').ifEmpty { "/" }
                    shown to group
                }

        fun batchName(item: FileItem, index: Int, prefix: String, suffix: String, find: String, replace: String, addIndex: Boolean): String {
            val hasExt = !item.isDirectory && item.extension.isNotEmpty()
            val base = if (hasExt) item.name.substringBeforeLast('.') else item.name
            var result = if (find.isNotEmpty()) base.replace(find, replace) else base
            result = "$prefix$result$suffix"
            if (addIndex) result = "${result}_${index + 1}"
            return if (hasExt) "$result.${item.name.substringAfterLast('.')}" else result
        }

        fun describe(e: Throwable): String {
            val name = e.javaClass.simpleName
            return when {
                name == "JSchChangedHostKeyException" ->
                    str(R.string.the_server_s_key_has_changed_the_server)
                e is java.net.UnknownHostException -> str(R.string.server_not_found, e.message)
                e is java.net.ConnectException || e is java.net.SocketTimeoutException -> str(R.string.the_server_does_not_answer)
                name == "JSchException" && e.message?.contains("Auth fail") == true -> str(R.string.wrong_user_name_or_password)
                else -> e.message?.takeIf { it.isNotBlank() } ?: name
            }
        }
    }
}

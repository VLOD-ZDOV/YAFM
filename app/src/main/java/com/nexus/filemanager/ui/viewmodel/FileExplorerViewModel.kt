package com.nexus.filemanager.ui.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.filemanager.YafmApp
import com.nexus.filemanager.data.archive.ArchiveManager
import com.nexus.filemanager.data.fs.joinPath
import com.nexus.filemanager.data.model.FileCategory
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.Location
import com.nexus.filemanager.data.model.RootInfo
import com.nexus.filemanager.data.model.ServerConfig
import com.nexus.filemanager.data.model.StorageStats
import com.nexus.filemanager.data.model.StorageType
import com.nexus.filemanager.data.qsh.Qsh
import com.nexus.filemanager.data.root.RootCommander
import com.nexus.filemanager.data.update.Release
import com.nexus.filemanager.data.update.Updater
import kotlinx.coroutines.Job
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

    private val _isGridView = MutableStateFlow(settings.settings.value.gridView)
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.NAME_ASC)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    val fileList: StateFlow<List<FileItem>> =
        combine(rawFiles, _searchQuery, _showHiddenFiles, _sortMode) { files, query, hidden, sort ->
            filterAndSort(files, query.trim().lowercase(), hidden, sort)
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
        _location.value = target
        _searchQuery.value = ""
        loadCurrentDirectory()
    }

    /** Another directory in the current storage. */
    fun navigateTo(path: String) = go(_location.value.copy(path = path))

    /** Entering the explorer from outside: history starts over, so Back from here returns to where it came from. */
    private fun enter(target: Location) {
        history.clear()
        _location.value = target
        _searchQuery.value = ""
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

    fun navigateBack(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        _location.value = previous
        _searchQuery.value = ""
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
            StorageType.LOCAL -> if (base == internalRoot) "Память" else "/"
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
        FileCategory.IMAGE -> "Все изображения"
        FileCategory.VIDEO -> "Все видео"
        FileCategory.AUDIO -> "Вся музыка"
        FileCategory.DOCUMENT -> "Все документы"
        FileCategory.ARCHIVE -> "Все архивы"
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

    fun toggleGridView() {
        _isGridView.value = !_isGridView.value
        settings.update { it.copy(gridView = _isGridView.value) }
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    private fun filterAndSort(items: List<FileItem>, query: String, showHidden: Boolean, sort: SortMode): List<FileItem> {
        val filtered = items.filter { (showHidden || !it.isHidden) && (query.isEmpty() || it.name.lowercase().contains(query)) }
        val dirsFirst = compareBy<FileItem> { !it.isDirectory }
        return filtered.sortedWith(
            when (sort) {
                SortMode.NAME_ASC -> dirsFirst.thenBy { it.name.lowercase() }
                SortMode.NAME_DESC -> dirsFirst.thenByDescending { it.name.lowercase() }
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
                (if (move) "Перемещено: ${items.size}" else "Скопировано: ${items.size}") +
                    if (skipped > 0) ". Ссылки на папки не копируются, пропущено: $skipped" else ""
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
        operation("Удалено: ${items.size}") {
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

    fun changePermissions(item: FileItem, octal: String) = operation("Права изменены") {
        repository.changePermissions(item, octal)
    }

    /** Opens a file in a viewer or another app: remote files are fetched to the cache first. */
    fun withLocalCopy(item: FileItem, onReady: (File) -> Unit) {
        viewModelScope.launch {
            _progress.value = "Загрузка ${item.name}…"
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

    fun extractArchive(archiveItem: FileItem, targetDir: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _isArchiveProcessing.value = true
            try {
                val src = repository.materialize(archiveItem)
                ArchiveManager.extractArchive(src, File(targetDir)) { _archiveProgress.value = it }
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
                val dest = File(archiveOutputDir(), archiveName)
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
        operation("Переименовано: ${items.size}") {
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
                .onSuccess { onResult(true, it.ifBlank { "Связано" }) }
                .onFailure { onResult(false, describe(it)) }
        }
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
                    _messages.tryEmit("Разрешите установку из YAFM и нажмите «Обновить» ещё раз")
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
                    "Ключ сервера изменился. Возможна подмена сервера, подключение отменено."
                e is java.net.UnknownHostException -> "Сервер не найден: ${e.message}"
                e is java.net.ConnectException || e is java.net.SocketTimeoutException -> "Сервер не отвечает"
                name == "JSchException" && e.message?.contains("Auth fail") == true -> "Неверный логин или пароль"
                else -> e.message?.takeIf { it.isNotBlank() } ?: name
            }
        }
    }
}

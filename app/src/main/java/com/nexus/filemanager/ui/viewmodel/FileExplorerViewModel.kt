package com.nexus.filemanager.ui.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.RootInfo
import com.nexus.filemanager.data.model.ServerConfig
import com.nexus.filemanager.data.model.StorageStats
import com.nexus.filemanager.data.model.StorageType
import com.nexus.filemanager.data.repository.FileRepository
import com.nexus.filemanager.data.repository.ServerRepository
import com.nexus.filemanager.data.root.RootCommander
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Stack

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

    private val fileRepo = FileRepository()
    val serverRepo = ServerRepository(application)

    // Current State
    private val _currentPath = MutableStateFlow(Environment.getExternalStorageDirectory().absolutePath)
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _currentStorageType = MutableStateFlow(StorageType.LOCAL)
    val currentStorageType: StateFlow<StorageType> = _currentStorageType.asStateFlow()

    private val _currentServer = MutableStateFlow<ServerConfig?>(null)
    val currentServer: StateFlow<ServerConfig?> = _currentServer.asStateFlow()

    private val _fileList = MutableStateFlow<List<FileItem>>(emptyList())
    val fileList: StateFlow<List<FileItem>> = _fileList.asStateFlow()

    private val _storageStats = MutableStateFlow<List<StorageStats>>(emptyList())
    val storageStats: StateFlow<List<StorageStats>> = _storageStats.asStateFlow()

    private val _rootInfo = MutableStateFlow(RootInfo())
    val rootInfo: StateFlow<RootInfo> = _rootInfo.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Preferences & View Modes
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showHiddenFiles = MutableStateFlow(false)
    val showHiddenFiles: StateFlow<Boolean> = _showHiddenFiles.asStateFlow()

    private val _isGridView = MutableStateFlow(false)
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private val _sortMode = MutableStateFlow(SortMode.NAME_ASC)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    // Selection & Clipboard
    private val _selectedItems = MutableStateFlow<Set<FileItem>>(emptySet())
    val selectedItems: StateFlow<Set<FileItem>> = _selectedItems.asStateFlow()

    private val _clipboardItems = MutableStateFlow<List<FileItem>>(emptyList())
    val clipboardItems: StateFlow<List<FileItem>> = _clipboardItems.asStateFlow()

    private val _clipboardOp = MutableStateFlow(ClipboardOp.NONE)
    val clipboardOp: StateFlow<ClipboardOp> = _clipboardOp.asStateFlow()

    // Navigation Stack
    private val navigationStack = Stack<Pair<String, StorageType>>()

    init {
        refreshDashboard()
        loadCurrentDirectory()
    }

    fun refreshDashboard() {
        viewModelScope.launch {
            _rootInfo.value = RootCommander.checkRoot()
            _storageStats.value = fileRepo.getStorageStats()
        }
    }

    fun loadCurrentDirectory() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _selectedItems.value = emptySet()
            try {
                val files = fileRepo.listFiles(
                    path = _currentPath.value,
                    storageType = _currentStorageType.value,
                    serverConfig = _currentServer.value
                )
                _fileList.value = applyFiltersAndSort(files)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Не удалось загрузить файлы"
                _fileList.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun navigateTo(path: String, storageType: StorageType = _currentStorageType.value, server: ServerConfig? = _currentServer.value) {
        navigationStack.push(Pair(_currentPath.value, _currentStorageType.value))
        _currentPath.value = path
        _currentStorageType.value = storageType
        _currentServer.value = server
        loadCurrentDirectory()
    }

    fun navigateToRoot() {
        navigateTo("/", StorageType.ROOT, null)
    }

    fun navigateToInternalStorage() {
        navigateTo(Environment.getExternalStorageDirectory().absolutePath, StorageType.LOCAL, null)
    }

    fun navigateToServer(server: ServerConfig) {
        val initialPath = when (server.type) {
            StorageType.SMB -> "/"
            StorageType.SFTP -> server.remotePath.ifEmpty { "/" }
            StorageType.FTP -> server.remotePath.ifEmpty { "/" }
            else -> "/"
        }
        navigateTo(initialPath, server.type, server)
    }

    fun navigateBack(): Boolean {
        if (!navigationStack.isEmpty()) {
            val (prevPath, prevType) = navigationStack.pop()
            _currentPath.value = prevPath
            _currentStorageType.value = prevType
            loadCurrentDirectory()
            return true
        }
        return false
    }

    fun getBreadcrumbs(): List<PathSegment> {
        val path = _currentPath.value
        val segments = mutableListOf<PathSegment>()

        val prefix = when (_currentStorageType.value) {
            StorageType.ROOT -> "Root (/)"
            StorageType.LOCAL -> "Память"
            StorageType.SMB -> _currentServer.value?.name ?: "SMB"
            StorageType.SFTP -> _currentServer.value?.name ?: "SFTP"
            StorageType.FTP -> _currentServer.value?.name ?: "FTP"
        }

        segments.add(PathSegment(prefix, if (_currentStorageType.value == StorageType.ROOT) "/" else Environment.getExternalStorageDirectory().absolutePath))

        if (path == "/" || path == Environment.getExternalStorageDirectory().absolutePath) {
            return segments
        }

        val relative = if (_currentStorageType.value == StorageType.LOCAL) {
            val root = Environment.getExternalStorageDirectory().absolutePath
            if (path.startsWith(root)) path.removePrefix(root).trim('/') else path.trim('/')
        } else {
            path.trim('/')
        }

        if (relative.isNotEmpty()) {
            val parts = relative.split('/')
            var cumulative = if (_currentStorageType.value == StorageType.LOCAL) Environment.getExternalStorageDirectory().absolutePath else ""
            for (p in parts) {
                cumulative = if (cumulative.endsWith("/")) "$cumulative$p" else "$cumulative/$p"
                segments.add(PathSegment(p, cumulative))
            }
        }

        return segments
    }

    // Search and Sort
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        loadCurrentDirectory()
    }

    fun toggleHiddenFiles() {
        _showHiddenFiles.value = !_showHiddenFiles.value
        loadCurrentDirectory()
    }

    fun toggleGridView() {
        _isGridView.value = !_isGridView.value
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        loadCurrentDirectory()
    }

    private fun applyFiltersAndSort(items: List<FileItem>): List<FileItem> {
        val query = _searchQuery.value.trim().lowercase()
        val showHidden = _showHiddenFiles.value

        var filtered = items.filter { item ->
            (showHidden || !item.isHidden) && (query.isEmpty() || item.name.lowercase().contains(query))
        }

        filtered = when (_sortMode.value) {
            SortMode.NAME_ASC -> filtered.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            SortMode.NAME_DESC -> filtered.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.name.lowercase() })
            SortMode.DATE_DESC -> filtered.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.lastModified })
            SortMode.DATE_ASC -> filtered.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.lastModified })
            SortMode.SIZE_DESC -> filtered.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenByDescending { it.size })
            SortMode.SIZE_ASC -> filtered.sortedWith(compareBy<FileItem> { !it.isDirectory }.thenBy { it.size })
        }

        return filtered
    }

    // Selection
    fun toggleSelection(item: FileItem) {
        val current = _selectedItems.value.toMutableSet()
        if (current.contains(item)) {
            current.remove(item)
        } else {
            current.add(item)
        }
        _selectedItems.value = current
    }

    fun selectAll() {
        _selectedItems.value = _fileList.value.toSet()
    }

    fun clearSelection() {
        _selectedItems.value = emptySet()
    }

    // Clipboard
    fun copySelected() {
        _clipboardItems.value = _selectedItems.value.toList()
        _clipboardOp.value = ClipboardOp.COPY
        clearSelection()
    }

    fun cutSelected() {
        _clipboardItems.value = _selectedItems.value.toList()
        _clipboardOp.value = ClipboardOp.CUT
        clearSelection()
    }

    fun clearClipboard() {
        _clipboardItems.value = emptyList()
        _clipboardOp.value = ClipboardOp.NONE
    }

    fun pasteClipboard() {
        viewModelScope.launch {
            _isLoading.value = true
            val targetDir = _currentPath.value
            val op = _clipboardOp.value
            val items = _clipboardItems.value

            for (item in items) {
                if (op == ClipboardOp.COPY) {
                    fileRepo.copy(item, targetDir, _currentServer.value)
                } else if (op == ClipboardOp.CUT) {
                    fileRepo.move(item, targetDir, _currentServer.value)
                }
            }

            clearClipboard()
            loadCurrentDirectory()
        }
    }

    // File Operations
    fun deleteSelected(onDone: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            for (item in _selectedItems.value) {
                fileRepo.delete(item, _currentServer.value)
            }
            clearSelection()
            loadCurrentDirectory()
            onDone()
        }
    }

    fun renameFile(item: FileItem, newName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            fileRepo.rename(item, newName, _currentServer.value)
            loadCurrentDirectory()
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            _isLoading.value = true
            fileRepo.createDirectory(_currentPath.value, name, _currentStorageType.value, _currentServer.value)
            loadCurrentDirectory()
        }
    }

    fun createNewFile(name: String) {
        viewModelScope.launch {
            _isLoading.value = true
            fileRepo.createFile(_currentPath.value, name, _currentStorageType.value, _currentServer.value)
            loadCurrentDirectory()
        }
    }

    fun changePermissions(item: FileItem, octal: String) {
        viewModelScope.launch {
            _isLoading.value = true
            fileRepo.changePermissions(item, octal, _currentServer.value)
            loadCurrentDirectory()
        }
    }

    fun remountPartition(partition: String, rw: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            RootCommander.remountPartition(partition, rw)
            _isLoading.value = false
        }
    }
}

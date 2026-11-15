package com.yafm.filemanager

import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.collectAsState
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import com.yafm.filemanager.ui.components.MiniPlayer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.yafm.filemanager.data.model.FileItem
import com.yafm.filemanager.ui.screens.AppManagerScreen
import com.yafm.filemanager.ui.screens.ExplorerScreen
import com.yafm.filemanager.ui.screens.HomeScreen
import com.yafm.filemanager.ui.screens.ImageViewerScreen
import com.yafm.filemanager.ui.screens.ImageEditorScreen
import com.yafm.filemanager.ui.screens.ArchiveBrowserScreen
import com.yafm.filemanager.ui.screens.openExternalIntent
import com.yafm.filemanager.data.model.FileCategory
import com.yafm.filemanager.ui.screens.RootTerminalScreen
import com.yafm.filemanager.ui.screens.SettingsScreen
import com.yafm.filemanager.ui.screens.VideoPlayerScreen
import com.yafm.filemanager.ui.screens.AudioPlayerScreen
import com.yafm.filemanager.ui.components.UpdateDialog
import com.yafm.filemanager.data.fs.ConnectionService
import com.yafm.filemanager.ui.components.CodeDialog
import com.yafm.filemanager.ui.components.HostKeyDialog
import com.yafm.filemanager.ui.components.IncomingFileDialog
import com.yafm.filemanager.ui.screens.StorageAnalyzerScreen
import com.yafm.filemanager.ui.screens.DuplicatesScreen
import com.yafm.filemanager.ui.screens.TextEditorScreen
import com.yafm.filemanager.ui.theme.YafmTheme
import com.yafm.filemanager.ui.viewmodel.FileExplorerViewModel
import kotlinx.coroutines.flow.MutableStateFlow

sealed class AppScreen {
    data object Home : AppScreen()
    data object Explorer : AppScreen()
    data class TextEditor(val file: FileItem, val back: AppScreen = Explorer) : AppScreen()
    /** [images]: the folder's pictures, to swipe through. */
    data class ImageViewer(val file: FileItem, val back: AppScreen = Explorer, val images: List<FileItem> = emptyList()) : AppScreen()
    /** An archive browsed like a folder; [folder] is where it was left. */
    data class ArchiveBrowser(val file: FileItem, val back: AppScreen, val folder: String = "") : AppScreen()
    data class ImageEditor(val file: FileItem, val back: AppScreen) : AppScreen()
    data object AppManager : AppScreen()
    data object StorageAnalyzer : AppScreen()
    data object Duplicates : AppScreen()
    data object RootTerminal : AppScreen()
    data object Settings : AppScreen()
    data object ThemeEditor : AppScreen()
    /** [streams]: links for entries of [playlist] that play from a server, by their path. */
    data class VideoPlayer(
        val file: java.io.File,
        val playlist: List<java.io.File>,
        val back: AppScreen = Explorer,
        val streams: Map<String, android.net.Uri> = emptyMap()
    ) : AppScreen()
    /** No [file]: shows what is already playing. */
    data class AudioPlayer(val file: java.io.File?, val playlist: List<java.io.File>, val back: AppScreen = Explorer) : AppScreen()
}

class MainActivity : ComponentActivity() {

    private val viewModel: FileExplorerViewModel by viewModels()

    /** Set by a tap on the music notification. */
    private val openPlayer = MutableStateFlow(false)

    /** A file another app asked to open ("Open with"). */
    private val incoming = MutableStateFlow<Uri?>(null)

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) openPlayer.value = true
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { incoming.value = it }
    }

    override fun onStart() {
        super.onStart()
        ConnectionService.stop(this)
    }

    override fun onStop() {
        super.onStop()
        val repository = (application as YafmApp).repository
        if (!isChangingConfigurations && (repository.hasOpenConnections || repository.isBusy)) ConnectionService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            viewModel.loadCurrentDirectory()
            viewModel.refreshDashboard()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            viewModel.loadCurrentDirectory()
            viewModel.refreshDashboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 11+: a short explanation first (in FileManagerApp), the system page only on "Allow".
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) checkAndRequestStoragePermissions()
        if (savedInstanceState == null) handle(intent)

        val settings = (application as YafmApp).settings
        setContent {
            val s by settings.settings.collectAsState()
            YafmTheme(theme = s.theme, palette = s.palette, custom = s.customTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FileManagerApp(
                        viewModel = viewModel,
                        openPlayer = openPlayer,
                        incoming = incoming,
                        onAllowAllFiles = ::checkAndRequestStoragePermissions
                    )
                }
            }
        }
    }

    fun checkAndRequestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    manageStorageLauncher.launch(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    manageStorageLauncher.launch(intent)
                }
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val needed = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                legacyPermissionLauncher.launch(needed.toTypedArray())
            }
        }
    }
}

@Composable
fun FileManagerApp(
    viewModel: FileExplorerViewModel,
    openPlayer: MutableStateFlow<Boolean>,
    incoming: MutableStateFlow<Uri?>,
    onAllowAllFiles: () -> Unit
) {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
    AllFilesAccessPrompt(onAllowAllFiles)
    NotificationPermissionPrompt(
        wanted = currentScreen is AppScreen.AudioPlayer || viewModel.transfers.collectAsState().value.current != null
    )
    var dualPane by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var activePane by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // The pane a screen was opened from: duplicates, archives and saved photos act on its folder.
    var paneVm by remember { mutableStateOf(viewModel) }
    val context = androidx.compose.ui.platform.LocalContext.current

    IncomingFileDialog(
        incoming = incoming,
        onOpen = { file ->
            viewModel.showIncoming(file, open = true)
            currentScreen = AppScreen.Explorer
        },
        onShowInFolder = { file ->
            viewModel.showIncoming(file, open = false)
            currentScreen = AppScreen.Explorer
        },
        onOpenAsText = { file ->
            currentScreen = AppScreen.TextEditor(FileItem(file.name, file.absolutePath, size = file.length()))
        },
        onSaveToDownloads = { file ->
            viewModel.saveToDownloads(file)
            currentScreen = AppScreen.Explorer
        }
    )

    val playerRequested by openPlayer.collectAsState()
    LaunchedEffect(playerRequested) {
        if (playerRequested) {
            openPlayer.value = false
            val screen = currentScreen
            if (screen !is AppScreen.AudioPlayer) currentScreen = AppScreen.AudioPlayer(null, emptyList(), back = screen)
        }
    }

    LaunchedEffect(Unit) { viewModel.checkForUpdates(manual = false) }
    UpdateDialog(viewModel)
    CodeDialog(viewModel)
    HostKeyDialog(viewModel)

    // Music playing in the background gets a bar under the browsing screens.
    val showMini = currentScreen is AppScreen.Home || currentScreen is AppScreen.Explorer
    // Only a bar that is really shown takes over the navigation bar's space; without music the
    // screen above must keep clear of it itself.
    var miniShown by remember { mutableStateOf(false) }
    // In landscape the navigation bar and the camera cutout sit at a side: everything but the video
    // (which plays edge to edge) keeps clear of them.
    Column(
        Modifier
            .fillMaxSize()
            .then(
                if (currentScreen is AppScreen.VideoPlayer) Modifier
                else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            )
    ) {
    Box(
        Modifier
            .weight(1f)
            .then(if (showMini && miniShown) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)
    ) {
    Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
        when (screen) {
            is AppScreen.Home -> {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToExplorer = {
                        currentScreen = AppScreen.Explorer
                    },
                    onNavigateToAppManager = {
                        currentScreen = AppScreen.AppManager
                    },
                    onNavigateToStorageAnalyzer = {
                        currentScreen = AppScreen.StorageAnalyzer
                    },
                    onNavigateToRootTerminal = {
                        currentScreen = AppScreen.RootTerminal
                    },
                    onNavigateToSettings = {
                        currentScreen = AppScreen.Settings
                    }
                )
            }
            is AppScreen.Explorer -> {
                @Composable
                fun pane(vm: FileExplorerViewModel, index: Int, other: FileExplorerViewModel?) {
                    if (!dualPane || activePane == index) androidx.compose.runtime.SideEffect { paneVm = vm }
                    ExplorerScreen(
                    viewModel = vm,
                    onNavigateBack = {
                        // The second pane closes; the first one leaves the explorer.
                        if (index == 1) { dualPane = false; activePane = 0 } else currentScreen = AppScreen.Home
                    },
                    onOpenFileInEditor = { file -> currentScreen = AppScreen.TextEditor(file) },
                    onOpenImage = { file ->
                        val images = vm.fileList.value.filter { it.category == FileCategory.IMAGE && !it.isDirectory }
                        currentScreen = AppScreen.ImageViewer(file, images = images)
                    },
                    onOpenVideo = { file, playlist, streams ->
                        currentScreen = AppScreen.VideoPlayer(file, playlist, streams = streams)
                    },
                    onOpenAudio = { file, playlist -> currentScreen = AppScreen.AudioPlayer(file, playlist) },
                    onEditImage = { file -> currentScreen = AppScreen.ImageEditor(file, back = AppScreen.Explorer) },
                    onOpenArchive = { file -> currentScreen = AppScreen.ArchiveBrowser(file, back = AppScreen.Explorer) },
                    onOpenDuplicates = { currentScreen = AppScreen.Duplicates },
                    active = !dualPane || activePane == index,
                    dual = dualPane,
                    onToggleDual = { dualPane = !dualPane; activePane = 0; paneVm = viewModel },
                    otherLocation = other?.location?.collectAsState()?.value
                )
                }
                if (!dualPane) {
                    pane(viewModel, 0, null)
                } else {
                    // Made the first time two panes are opened, not on every start.
                    val secondPane: FileExplorerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "second")
                    remember(secondPane) { secondPane.isSecondPane = true }
                    // Two folders at once, e.g. a server above and the phone below: copy or move between them.
                    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val accent = MaterialTheme.colorScheme.primary
                    @Composable
                    fun framed(index: Int, modifier: Modifier, content: @Composable () -> Unit) = Box(
                        modifier
                            .pointerInput(Unit) {
                                // Seen before the pane handles the touch, which it still gets.
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    activePane = index
                                }
                            }
                            .drawWithContent {
                                drawContent()
                                if (activePane == index) drawRect(accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                            }
                    ) { content() }
                    if (landscape) {
                        Row(Modifier.fillMaxSize()) {
                            framed(0, Modifier.weight(1f)) { pane(viewModel, 0, secondPane) }
                            framed(1, Modifier.weight(1f)) { pane(secondPane, 1, viewModel) }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            framed(0, Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) { pane(viewModel, 0, secondPane) }
                            framed(1, Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) { pane(secondPane, 1, viewModel) }
                        }
                    }
                }
            }
            is AppScreen.TextEditor -> {
                TextEditorScreen(
                    fileItem = screen.file,
                    repository = viewModel.repository,
                    onNavigateBack = {
                        currentScreen = screen.back
                    }
                )
            }
            is AppScreen.ImageViewer -> {
                ImageViewerScreen(
                    fileItem = screen.file,
                    images = screen.images,
                    onNavigateBack = {
                        currentScreen = screen.back
                    },
                    // Back from the editor returns to the picture that was edited.
                    onEdit = { item -> currentScreen = AppScreen.ImageEditor(item, back = screen.copy(file = item)) }
                )
            }
            is AppScreen.ImageEditor -> {
                ImageEditorScreen(
                    fileItem = screen.file,
                    onNavigateBack = { currentScreen = screen.back },
                    onSaved = {
                        paneVm.loadCurrentDirectory()
                        currentScreen = AppScreen.Explorer
                    }
                )
            }
            is AppScreen.ArchiveBrowser -> {
                ArchiveBrowserScreen(
                    archiveItem = screen.file,
                    viewModel = paneVm,
                    startFolder = screen.folder,
                    onNavigateBack = { currentScreen = screen.back },
                    onOpenFile = { item, folder ->
                        // Back from the file returns into the archive, where it was left.
                        val here = screen.copy(folder = folder)
                        val file = java.io.File(item.path)
                        currentScreen = when {
                            com.yafm.filemanager.data.archive.ArchiveManager.isArchive(item.name) -> AppScreen.ArchiveBrowser(item, back = here)
                            item.category == FileCategory.IMAGE -> AppScreen.ImageViewer(item, back = here)
                            item.category == FileCategory.VIDEO -> AppScreen.VideoPlayer(file, listOf(file), back = here)
                            item.category == FileCategory.AUDIO -> AppScreen.AudioPlayer(file, listOf(file), back = here)
                            item.category == FileCategory.CODE || item.category == FileCategory.TEXT ||
                                com.yafm.filemanager.data.fs.TextSniffer.looksLikeText(file) -> AppScreen.TextEditor(item, back = here)
                            else -> {
                                openExternalIntent(context, item, file)
                                here
                            }
                        }
                    }
                )
            }
            is AppScreen.AppManager -> {
                AppManagerScreen(
                    isRootAvailable = viewModel.rootInfo.value.isRootGranted,
                    onNavigateBack = {
                        currentScreen = AppScreen.Home
                    }
                )
            }
            is AppScreen.StorageAnalyzer -> {
                StorageAnalyzerScreen(
                    onNavigateBack = {
                        currentScreen = AppScreen.Home
                    },
                    onOpenFile = { file ->
                        viewModel.navigateToLocal(java.io.File(file.path).parent ?: "/")
                        currentScreen = AppScreen.Explorer
                    }
                )
            }
            is AppScreen.Duplicates -> {
                DuplicatesScreen(
                    viewModel = paneVm,
                    onNavigateBack = { currentScreen = AppScreen.Explorer },
                    onOpenFolder = { folder ->
                        paneVm.cancelDuplicates()
                        paneVm.navigateToLocal(folder)
                        currentScreen = AppScreen.Explorer
                    }
                )
            }
            is AppScreen.VideoPlayer -> {
                val s = viewModel.settings.settings.value
                VideoPlayerScreen(
                    file = screen.file,
                    playlist = screen.playlist,
                    streams = screen.streams,
                    seekStepMs = s.seekSeconds * 1000L,
                    sideGestures = s.videoSideGestures,
                    onNavigateBack = { currentScreen = screen.back }
                )
            }
            is AppScreen.AudioPlayer -> {
                AudioPlayerScreen(
                    file = screen.file,
                    playlist = screen.playlist,
                    seekStepMs = viewModel.settings.settings.value.seekSeconds * 1000L,
                    onNavigateBack = { currentScreen = screen.back }
                )
            }
            is AppScreen.Settings -> {
                SettingsScreen(
                    viewModel = viewModel,
                    onNavigateBack = { currentScreen = AppScreen.Home },
                    onOpenThemeEditor = { currentScreen = AppScreen.ThemeEditor }
                )
            }
            is AppScreen.ThemeEditor -> {
                com.yafm.filemanager.ui.screens.ThemeEditorScreen(viewModel = viewModel, onNavigateBack = { currentScreen = AppScreen.Settings })
            }
            is AppScreen.RootTerminal -> {
                RootTerminalScreen(
                    onNavigateBack = {
                        currentScreen = AppScreen.Home
                    }
                )
            }
        }
    }
    }
    if (showMini) {
        MiniPlayer(
            onShown = { miniShown = it },
            onOpen = { file, queue -> currentScreen = AppScreen.AudioPlayer(file, queue, back = currentScreen) },
            modifier = Modifier.navigationBarsPadding()
        )
    }
    }
}

/**
 * Android 11+: what "All files access" is for, before the system page. "Later" keeps the app usable with
 * what it can reach without it (servers, root); the card comes back on the next start until it is given.
 */
@Composable
private fun AllFilesAccessPrompt(onAllow: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var later by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // Back from the system page: look again.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = Environment.isExternalStorageManager()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (granted || later) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { later = true },
        icon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.FolderOpen, contentDescription = null) },
        title = { androidx.compose.material3.Text(str(R.string.all_files_title)) },
        text = { androidx.compose.material3.Text(str(R.string.all_files_text)) },
        confirmButton = { androidx.compose.material3.Button(onClick = onAllow) { androidx.compose.material3.Text(str(R.string.allow)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { later = true }) { androidx.compose.material3.Text(str(R.string.later)) } }
    )
}

/** Android 13+: notifications are asked for once, when music or a transfer first needs them. */
@Composable
private fun NotificationPermissionPrompt(wanted: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    androidx.compose.runtime.LaunchedEffect(wanted) {
        if (!wanted || prefs.getBoolean("askedNotifications", false)) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        prefs.edit().putBoolean("askedNotifications", true).apply()
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

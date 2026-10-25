package com.yafm.filemanager

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
import com.yafm.filemanager.ui.screens.RootTerminalScreen
import com.yafm.filemanager.ui.screens.SettingsScreen
import com.yafm.filemanager.ui.screens.VideoPlayerScreen
import com.yafm.filemanager.ui.screens.AudioPlayerScreen
import com.yafm.filemanager.ui.components.UpdateDialog
import com.yafm.filemanager.ui.components.CodeDialog
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
    data class TextEditor(val file: FileItem) : AppScreen()
    data class ImageViewer(val file: FileItem) : AppScreen()
    data class ImageEditor(val file: FileItem, val back: AppScreen) : AppScreen()
    data object AppManager : AppScreen()
    data object StorageAnalyzer : AppScreen()
    data object Duplicates : AppScreen()
    data object RootTerminal : AppScreen()
    data object Settings : AppScreen()
    data class VideoPlayer(val file: java.io.File, val playlist: List<java.io.File>) : AppScreen()
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

        checkAndRequestStoragePermissions()
        if (savedInstanceState == null) handle(intent)

        val settings = (application as YafmApp).settings
        setContent {
            val s by settings.settings.collectAsState()
            YafmTheme(theme = s.theme, dynamicColor = s.dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FileManagerApp(viewModel = viewModel, openPlayer = openPlayer, incoming = incoming)
                }
            }
        }
    }

    private fun checkAndRequestStoragePermissions() {
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
fun FileManagerApp(viewModel: FileExplorerViewModel, openPlayer: MutableStateFlow<Boolean>, incoming: MutableStateFlow<Uri?>) {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }

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
                ExplorerScreen(
                    viewModel = viewModel,
                    onNavigateBack = {
                        currentScreen = AppScreen.Home
                    },
                    onOpenFileInEditor = { file ->
                        currentScreen = AppScreen.TextEditor(file)
                    },
                    onOpenImage = { file ->
                        currentScreen = AppScreen.ImageViewer(file)
                    },
                    onOpenVideo = { file, playlist ->
                        currentScreen = AppScreen.VideoPlayer(file, playlist)
                    },
                    onOpenAudio = { file, playlist ->
                        currentScreen = AppScreen.AudioPlayer(file, playlist)
                    },
                    onEditImage = { file ->
                        currentScreen = AppScreen.ImageEditor(file, back = AppScreen.Explorer)
                    },
                    onOpenDuplicates = {
                        currentScreen = AppScreen.Duplicates
                    }
                )
            }
            is AppScreen.TextEditor -> {
                TextEditorScreen(
                    fileItem = screen.file,
                    repository = viewModel.repository,
                    onNavigateBack = {
                        currentScreen = AppScreen.Explorer
                    }
                )
            }
            is AppScreen.ImageViewer -> {
                ImageViewerScreen(
                    fileItem = screen.file,
                    onNavigateBack = {
                        currentScreen = AppScreen.Explorer
                    },
                    onEdit = { currentScreen = AppScreen.ImageEditor(screen.file, back = screen) }
                )
            }
            is AppScreen.ImageEditor -> {
                ImageEditorScreen(
                    fileItem = screen.file,
                    onNavigateBack = { currentScreen = screen.back },
                    onSaved = {
                        viewModel.loadCurrentDirectory()
                        currentScreen = AppScreen.Explorer
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
                    viewModel = viewModel,
                    onNavigateBack = { currentScreen = AppScreen.Explorer },
                    onOpenFolder = { folder ->
                        viewModel.cancelDuplicates()
                        viewModel.navigateToLocal(folder)
                        currentScreen = AppScreen.Explorer
                    }
                )
            }
            is AppScreen.VideoPlayer -> {
                val s = viewModel.settings.settings.value
                VideoPlayerScreen(
                    file = screen.file,
                    playlist = screen.playlist,
                    seekStepMs = s.seekSeconds * 1000L,
                    sideGestures = s.videoSideGestures,
                    onNavigateBack = { currentScreen = AppScreen.Explorer }
                )
            }
            is AppScreen.AudioPlayer -> {
                AudioPlayerScreen(file = screen.file, playlist = screen.playlist, onNavigateBack = { currentScreen = screen.back })
            }
            is AppScreen.Settings -> {
                SettingsScreen(viewModel = viewModel, onNavigateBack = { currentScreen = AppScreen.Home })
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

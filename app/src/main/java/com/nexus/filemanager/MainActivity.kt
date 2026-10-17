package com.nexus.filemanager

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
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.ui.screens.AppManagerScreen
import com.nexus.filemanager.ui.screens.ExplorerScreen
import com.nexus.filemanager.ui.screens.HomeScreen
import com.nexus.filemanager.ui.screens.ImageViewerScreen
import com.nexus.filemanager.ui.screens.RootTerminalScreen
import com.nexus.filemanager.ui.screens.SettingsScreen
import com.nexus.filemanager.ui.screens.VideoPlayerScreen
import com.nexus.filemanager.ui.screens.AudioPlayerScreen
import com.nexus.filemanager.ui.components.UpdateDialog
import com.nexus.filemanager.ui.screens.StorageAnalyzerScreen
import com.nexus.filemanager.ui.screens.TextEditorScreen
import com.nexus.filemanager.ui.theme.NexusFileManagerTheme
import com.nexus.filemanager.ui.viewmodel.FileExplorerViewModel

sealed class AppScreen {
    data object Home : AppScreen()
    data object Explorer : AppScreen()
    data class TextEditor(val file: FileItem) : AppScreen()
    data class ImageViewer(val file: FileItem) : AppScreen()
    data object AppManager : AppScreen()
    data object StorageAnalyzer : AppScreen()
    data object RootTerminal : AppScreen()
    data object Settings : AppScreen()
    data class VideoPlayer(val file: java.io.File, val playlist: List<java.io.File>) : AppScreen()
    data class AudioPlayer(val file: java.io.File, val playlist: List<java.io.File>) : AppScreen()
}

class MainActivity : ComponentActivity() {

    private val viewModel: FileExplorerViewModel by viewModels()

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

        val settings = (application as YafmApp).settings
        setContent {
            val s by settings.settings.collectAsState()
            NexusFileManagerTheme(theme = s.theme, dynamicColor = s.dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FileManagerApp(viewModel = viewModel)
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
fun FileManagerApp(viewModel: FileExplorerViewModel) {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }

    LaunchedEffect(Unit) { viewModel.checkForUpdates(manual = false) }
    UpdateDialog(viewModel)

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
            is AppScreen.VideoPlayer -> {
                VideoPlayerScreen(file = screen.file, playlist = screen.playlist, onNavigateBack = { currentScreen = AppScreen.Explorer })
            }
            is AppScreen.AudioPlayer -> {
                AudioPlayerScreen(file = screen.file, playlist = screen.playlist, onNavigateBack = { currentScreen = AppScreen.Explorer })
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

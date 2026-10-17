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

        setContent {
            NexusFileManagerTheme {
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
                    }
                )
            }
            is AppScreen.TextEditor -> {
                TextEditorScreen(
                    fileItem = screen.file,
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
                        currentScreen = AppScreen.Explorer
                    }
                )
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

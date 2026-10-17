package com.nexus.filemanager.ui.screens

import androidx.compose.ui.res.stringResource
import com.nexus.filemanager.R
import android.os.Environment
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.filemanager.data.model.FileCategory
import com.nexus.filemanager.data.model.ServerConfig
import com.nexus.filemanager.ui.components.ServerDialog
import com.nexus.filemanager.ui.components.StorageGaugeCard
import com.nexus.filemanager.ui.theme.ApkColor
import com.nexus.filemanager.ui.theme.ArchiveColor
import com.nexus.filemanager.ui.theme.AudioColor
import com.nexus.filemanager.ui.theme.DangerRed
import com.nexus.filemanager.ui.theme.DocumentColor
import com.nexus.filemanager.ui.theme.FolderColor
import com.nexus.filemanager.ui.theme.ImageColor
import com.nexus.filemanager.ui.theme.NexusBluePrimary
import com.nexus.filemanager.ui.theme.RootColor
import com.nexus.filemanager.ui.theme.SuccessGreen
import com.nexus.filemanager.ui.theme.VideoColor
import com.nexus.filemanager.ui.viewmodel.FileExplorerViewModel

data class CategoryItem(
    val title: String,
    val icon: ImageVector,
    val color: Color,
    val path: String,
    /** Set: all files of this kind; null: open [path]. */
    val category: FileCategory? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: FileExplorerViewModel,
    onNavigateToExplorer: () -> Unit,
    onNavigateToAppManager: () -> Unit,
    onNavigateToStorageAnalyzer: () -> Unit,
    onNavigateToRootTerminal: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val storageStats by viewModel.storageStats.collectAsState()
    val rootInfo by viewModel.rootInfo.collectAsState()
    val servers by viewModel.serverRepo.servers.collectAsState()

    var showAddServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var deletingServer by remember { mutableStateOf<ServerConfig?>(null) }

    val categories = remember {
        val root = Environment.getExternalStorageDirectory().absolutePath
        listOf(
            CategoryItem("Загрузки", Icons.Default.Download, NexusBluePrimary, "$root/Download"),
            CategoryItem("Изображения", Icons.Default.Image, ImageColor, root, FileCategory.IMAGE),
            CategoryItem("Видео", Icons.Default.Movie, VideoColor, root, FileCategory.VIDEO),
            CategoryItem("Музыка", Icons.Default.MusicNote, AudioColor, root, FileCategory.AUDIO),
            CategoryItem("Документы", Icons.Default.Description, DocumentColor, root, FileCategory.DOCUMENT),
            CategoryItem("Архивы", Icons.Default.Archive, ArchiveColor, root, FileCategory.ARCHIVE)
        )
    }

    if (showAddServerDialog || editingServer != null) {
        val close = {
            showAddServerDialog = false
            editingServer = null
        }
        ServerDialog(
            initialConfig = editingServer,
            qshAvailable = viewModel.repository.qsh.isAvailable,
            onTest = viewModel::testConnection,
            onPair = viewModel::pairQsh,
            onDismiss = close,
            onSave = { config ->
                viewModel.serverRepo.saveServer(config)
                close()
            }
        )
    }

    deletingServer?.let { server ->
        AlertDialog(
            onDismissRequest = { deletingServer = null },
            title = { Text("Удалить подключение?") },
            text = { Text("«${server.name}» исчезнет из списка. Файлы на сервере не затрагиваются.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.serverRepo.deleteServer(server.id)
                    deletingServer = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deletingServer = null }) { Text("Отмена") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = stringResource(R.string.app_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshDashboard() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Root Status Banner
            item {
                RootStatusCard(
                    rootInfo = rootInfo,
                    onNavigateRoot = {
                        viewModel.navigateToRoot()
                        onNavigateToExplorer()
                    }
                )
            }

            // Storage Overview Section
            item {
                Text(
                    text = "Хранилища устройства",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    storageStats.forEach { stat ->
                        StorageGaugeCard(
                            stats = stat,
                            onClick = {
                                if (stat.isRoot) {
                                    viewModel.navigateToRoot()
                                } else {
                                    viewModel.navigateToInternalStorage()
                                }
                                onNavigateToExplorer()
                            }
                        )
                    }
                }
            }

            // Quick Categories Grid
            item {
                Text(
                    text = "Категории",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    categories.take(3).forEach { cat ->
                        CategoryCard(
                            category = cat,
                            onClick = {
                                cat.category?.let { viewModel.openCategory(it) } ?: viewModel.navigateToLocal(cat.path)
                                onNavigateToExplorer()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    categories.drop(3).take(3).forEach { cat ->
                        CategoryCard(
                            category = cat,
                            onClick = {
                                cat.category?.let { viewModel.openCategory(it) } ?: viewModel.navigateToLocal(cat.path)
                                onNavigateToExplorer()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Quick Jumps
            item {
                Text(
                    text = "Быстрый переход",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            viewModel.navigateToInternalStorage()
                            onNavigateToExplorer()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("/sdcard", fontSize = 12.sp)
                    }

                    FilledTonalButton(
                        onClick = {
                            viewModel.navigateToRoot("/system")
                            onNavigateToExplorer()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(16.dp), tint = RootColor)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("/system", fontSize = 12.sp)
                    }

                    FilledTonalButton(
                        onClick = {
                            viewModel.navigateToRoot("/data")
                            onNavigateToExplorer()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp), tint = RootColor)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("/data", fontSize = 12.sp)
                    }
                }
            }

            // Tools & Utilities
            item {
                Text(
                    text = "Инструменты и утилиты",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToAppManager() },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ApkColor.copy(alpha = 0.15f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Apps, contentDescription = null, tint = ApkColor)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Приложения", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp))
                            Text("Бэкап APK", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToStorageAnalyzer() },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = NexusBluePrimary.copy(alpha = 0.15f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PieChart, contentDescription = null, tint = NexusBluePrimary)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Анализ диска", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp))
                            Text("Топ файлов", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToRootTerminal() },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = RootColor.copy(alpha = 0.15f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Terminal, contentDescription = null, tint = RootColor)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Root Шелл", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp))
                            Text("Консоль su", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Network Storage & Servers (Samba, SFTP, FTP)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Сетевые хранилища",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    IconButton(onClick = { showAddServerDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Добавить сервер",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (servers.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAddServerDialog = true },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "Добавить сетевой диск",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "SFTP, qsh, Samba (Windows, NAS) или FTP",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                items(servers) { server ->
                    ServerCard(
                        server = server,
                        onClick = {
                            viewModel.navigateToServer(server)
                            onNavigateToExplorer()
                        },
                        onEdit = { editingServer = server },
                        onDelete = { deletingServer = server }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}

@Composable
fun RootStatusCard(
    rootInfo: com.nexus.filemanager.data.model.RootInfo,
    onNavigateRoot: () -> Unit
) {
    val isGranted = rootInfo.isRootGranted
    val tint = if (isGranted) RootColor else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) RootColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AdminPanelSettings,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isGranted) "Root доступ активен" else "Режим пользователя",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (isGranted) RootColor else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isGranted) "Менеджер: ${rootInfo.rootImplementation} (${rootInfo.suVersion})" else "Root права не предоставлены или устройство не рутировано",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isGranted) {
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onNavigateRoot,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Root проводник (/)")
                }
            }
        }
    }
}

@Composable
fun CategoryCard(
    category: CategoryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = category.color.copy(alpha = 0.15f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = category.icon,
                        contentDescription = null,
                        tint = category.color,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = category.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun ServerCard(
    server: ServerConfig,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = NexusBluePrimary.copy(alpha = 0.15f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = NexusBluePrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = server.summary(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Изменить",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}

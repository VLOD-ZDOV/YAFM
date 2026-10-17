package com.nexus.filemanager.ui.screens

import android.os.Environment
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.filemanager.data.model.FileCategory
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.StorageType
import com.nexus.filemanager.ui.components.FileIcon
import com.nexus.filemanager.ui.theme.ApkColor
import com.nexus.filemanager.ui.theme.ArchiveColor
import com.nexus.filemanager.ui.theme.AudioColor
import com.nexus.filemanager.ui.theme.DangerRed
import com.nexus.filemanager.ui.theme.DocumentColor
import com.nexus.filemanager.ui.theme.FolderColor
import com.nexus.filemanager.ui.theme.ImageColor
import com.nexus.filemanager.ui.theme.NexusBluePrimary
import com.nexus.filemanager.ui.theme.TextColor
import com.nexus.filemanager.ui.theme.VideoColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CategoryStat(
    val category: FileCategory,
    val title: String,
    val color: Color,
    val totalBytes: Long,
    val count: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyzerScreen(
    onNavigateBack: () -> Unit,
    onOpenFile: (FileItem) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isAnalyzing by remember { mutableStateOf(true) }
    var largestFiles by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var categoryStats by remember { mutableStateOf<List<CategoryStat>>(emptyList()) }
    var totalScannedBytes by remember { mutableStateOf(0L) }

    BackHandler {
        onNavigateBack()
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val root = Environment.getExternalStorageDirectory()
            val filesList = mutableListOf<File>()
            val catBytes = mutableMapOf<FileCategory, Long>()
            val catCounts = mutableMapOf<FileCategory, Int>()

            try {
                root.walkTopDown()
                    .maxDepth(6)
                    .filter { it.isFile && !it.name.startsWith(".") }
                    .forEach { file ->
                        val len = file.length()
                        if (len > 0) {
                            filesList.add(file)
                            val item = FileItem(name = file.name, path = file.absolutePath, size = len)
                            val cat = item.category
                            catBytes[cat] = (catBytes[cat] ?: 0L) + len
                            catCounts[cat] = (catCounts[cat] ?: 0) + 1
                        }
                    }
            } catch (_: Exception) {}

            filesList.sortByDescending { it.length() }
            val top50 = filesList.take(50).map {
                FileItem(
                    name = it.name,
                    path = it.absolutePath,
                    size = it.length(),
                    isDirectory = false,
                    lastModified = it.lastModified(),
                    storageType = StorageType.LOCAL
                )
            }

            val total = catBytes.values.sum()
            val stats = listOf(
                CategoryStat(FileCategory.VIDEO, "Видео", VideoColor, catBytes[FileCategory.VIDEO] ?: 0L, catCounts[FileCategory.VIDEO] ?: 0),
                CategoryStat(FileCategory.IMAGE, "Изображения", ImageColor, catBytes[FileCategory.IMAGE] ?: 0L, catCounts[FileCategory.IMAGE] ?: 0),
                CategoryStat(FileCategory.AUDIO, "Аудио", AudioColor, catBytes[FileCategory.AUDIO] ?: 0L, catCounts[FileCategory.AUDIO] ?: 0),
                CategoryStat(FileCategory.APK, "Установочные APK", ApkColor, catBytes[FileCategory.APK] ?: 0L, catCounts[FileCategory.APK] ?: 0),
                CategoryStat(FileCategory.ARCHIVE, "Архивы", ArchiveColor, catBytes[FileCategory.ARCHIVE] ?: 0L, catCounts[FileCategory.ARCHIVE] ?: 0),
                CategoryStat(FileCategory.DOCUMENT, "Документы", DocumentColor, catBytes[FileCategory.DOCUMENT] ?: 0L, catCounts[FileCategory.DOCUMENT] ?: 0),
                CategoryStat(FileCategory.OTHER, "Другое", TextColor, catBytes[FileCategory.OTHER] ?: 0L, catCounts[FileCategory.OTHER] ?: 0)
            ).filter { it.totalBytes > 0 }.sortedByDescending { it.totalBytes }

            withContext(Dispatchers.Main) {
                largestFiles = top50
                categoryStats = stats
                totalScannedBytes = total
                isAnalyzing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Анализ хранилища", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        if (isAnalyzing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Сканирование и анализ файлов...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Category Distribution Card
                item {
                    Text(
                        text = "Распределение по категориям",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            categoryStats.forEach { stat ->
                                val pct = if (totalScannedBytes > 0) stat.totalBytes.toFloat() / totalScannedBytes else 0f
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = stat.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                        val mb = stat.totalBytes.toDouble() / (1024.0 * 1024.0)
                                        Text(
                                            text = if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb),
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { pct },
                                        color = stat.color,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                    )
                                }
                            }
                        }
                    }
                }

                // Top Largest Files
                item {
                    Text(
                        text = "Самые большие файлы (Топ 50)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                items(largestFiles, key = { it.path }) { item ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFile(item) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FileIcon(item = item)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.path,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = item.formattedSize(),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = DangerRed
                                )
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

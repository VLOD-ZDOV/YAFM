package com.nexus.filemanager.ui.components

import com.nexus.filemanager.str
import com.nexus.filemanager.R
import java.io.File
import com.nexus.filemanager.ui.thumbs.AudioCover
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import coil.request.videoFrameMillis
import coil.request.ImageRequest
import coil.compose.AsyncImage
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.filemanager.data.model.FileCategory
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.StorageType
import com.nexus.filemanager.ui.theme.ApkColor
import com.nexus.filemanager.ui.theme.ArchiveColor
import com.nexus.filemanager.ui.theme.AudioColor
import com.nexus.filemanager.ui.theme.CodeColor
import com.nexus.filemanager.ui.theme.DocumentColor
import com.nexus.filemanager.ui.theme.FolderColor
import com.nexus.filemanager.ui.theme.ImageColor
import com.nexus.filemanager.ui.theme.TextColor
import com.nexus.filemanager.ui.theme.VideoColor

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(
    item: FileItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPermissions: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        Color.Transparent
    }

    Surface(
        color = bgColor,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            FileIcon(item = item)

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.formattedSize().isNotEmpty()) {
                        Text(
                            text = item.formattedSize(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    if (item.formattedDate().isNotEmpty()) {
                        Text(
                            text = item.formattedDate(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    if (item.storageType == StorageType.ROOT) {
                        Text(
                            text = item.permissions,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (!isSelectionMode) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(str(R.string.properties)) },
                            onClick = {
                                showMenu = false
                                onDetails()
                            }
                        )
                        if (item.storageType != StorageType.SMB) {
                            DropdownMenuItem(
                                text = { Text(str(R.string.permissions_chmod_2)) },
                                onClick = {
                                    showMenu = false
                                    onPermissions()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(str(R.string.rename)) },
                            onClick = {
                                showMenu = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(str(R.string.delete), color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FileIcon(item: FileItem, modifier: Modifier = Modifier, fill: Boolean = false) {
    val (icon, tint) = when (item.category) {
        FileCategory.FOLDER -> Pair(Icons.Default.Folder, FolderColor)
        FileCategory.IMAGE -> Pair(Icons.Default.Image, ImageColor)
        FileCategory.VIDEO -> Pair(Icons.Default.Movie, VideoColor)
        FileCategory.AUDIO -> Pair(Icons.Default.MusicNote, AudioColor)
        FileCategory.DOCUMENT -> Pair(Icons.Default.Description, DocumentColor)
        FileCategory.ARCHIVE -> Pair(Icons.Default.Archive, ArchiveColor)
        FileCategory.APK -> Pair(Icons.Default.Android, ApkColor)
        FileCategory.CODE -> Pair(Icons.Default.Code, CodeColor)
        FileCategory.TEXT -> Pair(Icons.AutoMirrored.Filled.Article, TextColor)
        FileCategory.OTHER -> Pair(Icons.AutoMirrored.Filled.InsertDriveFile, TextColor)
    }

    // Previews only for files the app can read directly; remote and root files keep the icon.
    val preview = (item.storageType == StorageType.LOCAL || item.storageType == StorageType.ROOT) &&
        (item.category == FileCategory.IMAGE || item.category == FileCategory.VIDEO || item.category == FileCategory.AUDIO)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.15f),
        // fill: the caller sizes it (grid tiles); otherwise a list row's 40 dp square.
        modifier = if (fill) modifier else modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(if (fill) 48.dp else 24.dp)
            )
            if (preview) {
                val context = LocalContext.current
                val request = remember(item.path, item.lastModified) {
                    ImageRequest.Builder(context)
                        .data(if (item.category == FileCategory.AUDIO) AudioCover(item.path) else File(item.path))
                        .size(256)
                        .videoFrameMillis(1000)
                        .memoryCacheKey("thumb:${item.path}:${item.lastModified}")
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

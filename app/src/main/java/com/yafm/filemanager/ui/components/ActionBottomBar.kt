package com.yafm.filemanager.ui.components

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yafm.filemanager.ui.viewmodel.ClipboardOp

import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Unarchive

@Composable
fun ActionBottomBar(
    selectedCount: Int,
    hasArchiveSelected: Boolean = false,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onPermissions: () -> Unit,
    canChangePermissions: Boolean,
    onExtract: () -> Unit = {},
    onCompress: () -> Unit = {},
    onBatchRename: () -> Unit = {},
    onShare: () -> Unit = {},
    /** Extra actions for the selection, shown under "More". */
    moreActions: List<Pair<String, () -> Unit>> = emptyList(),
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = selectedCount > 0,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        Surface(
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val count = @Composable {
                Text(
                    text = str(R.string.selected, selectedCount),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val allAndClose = @Composable {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSelectAll) {
                        Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(str(R.string.all))
                    }
                    IconButton(onClick = onClearSelection) {
                        Icon(Icons.Default.Close, contentDescription = str(R.string.cancel))
                    }
                }
            }
            val actions = @Composable {
                    if (hasArchiveSelected) {
                        ActionButton(icon = Icons.Default.Unarchive, label = str(R.string.extract), onClick = onExtract)
                    }
                    ActionButton(icon = Icons.Default.FolderZip, label = str(R.string.to_zip), onClick = onCompress)
                    if (selectedCount > 1) {
                        ActionButton(icon = Icons.Default.DriveFileRenameOutline, label = str(R.string.batch), onClick = onBatchRename)
                    }
                    ActionButton(icon = Icons.Default.ContentCopy, label = str(R.string.copy), onClick = onCopy)
                    ActionButton(icon = Icons.Default.ContentCut, label = str(R.string.cut), onClick = onCut)
                    ActionButton(icon = Icons.Default.Share, label = str(R.string.share), onClick = onShare)
                    ActionButton(
                        icon = Icons.Default.Delete,
                        label = str(R.string.delete),
                        onClick = onDelete,
                        tint = MaterialTheme.colorScheme.error
                    )
                    // Permissions and the rest live here so the row fits a phone's width.
                    Box {
                        var open by remember { mutableStateOf(false) }
                        ActionButton(icon = Icons.Default.MoreVert, label = str(R.string.more), onClick = { open = true })
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            val all = if (canChangePermissions) moreActions + (str(R.string.permissions) to onPermissions) else moreActions
                            all.forEach { (label, action) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
                            }
                        }
                    }
            }
            // Landscape has little height: everything goes in one row.
            if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    count()
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) { actions() }
                    allAndClose()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        count()
                        allAndClose()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) { actions() }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        IconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = label, tint = tint)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
            color = tint
        )
    }
}

@Composable
fun PasteFloatingBar(
    itemCount: Int,
    operation: ClipboardOp,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = itemCount > 0 && operation != ClipboardOp.NONE,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (operation == ClipboardOp.COPY) Icons.Default.ContentCopy else Icons.Default.ContentCut,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (operation == ClipboardOp.COPY) str(R.string.copying, itemCount) else str(R.string.moving, itemCount),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Row {
                    TextButton(onClick = onCancel) {
                        Text(str(R.string.cancel), color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Button(
                        onClick = onPaste,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(str(R.string.paste))
                    }
                }
            }
        }
    }
}

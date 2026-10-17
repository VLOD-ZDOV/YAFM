package com.nexus.filemanager.ui.components

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.filemanager.ui.viewmodel.ClipboardOp

@Composable
fun ActionBottomBar(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onPermissions: () -> Unit,
    canChangePermissions: Boolean,
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
                    Text(
                        text = "Выбрано: $selectedCount",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row {
                        TextButton(onClick = onSelectAll) {
                            Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Все")
                        }
                        IconButton(onClick = onClearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Отмена")
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ActionButton(icon = Icons.Default.ContentCopy, label = "Копировать", onClick = onCopy)
                    ActionButton(icon = Icons.Default.ContentCut, label = "Вырезать", onClick = onCut)
                    if (canChangePermissions) {
                        ActionButton(icon = Icons.Default.Lock, label = "Права", onClick = onPermissions)
                    }
                    ActionButton(
                        icon = Icons.Default.Delete,
                        label = "Удалить",
                        onClick = onDelete,
                        tint = MaterialTheme.colorScheme.error
                    )
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
                        text = "${if (operation == ClipboardOp.COPY) "Копирование" else "Перемещение"} ($itemCount)",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Row {
                    TextButton(onClick = onCancel) {
                        Text("Отмена", color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Button(
                        onClick = onPaste,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Вставить")
                    }
                }
            }
        }
    }
}

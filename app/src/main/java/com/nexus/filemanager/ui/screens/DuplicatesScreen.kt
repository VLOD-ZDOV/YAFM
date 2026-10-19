package com.nexus.filemanager.ui.screens

import com.nexus.filemanager.str
import com.nexus.filemanager.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.ui.components.FileIcon
import com.nexus.filemanager.ui.viewmodel.FileExplorerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    viewModel: FileExplorerViewModel,
    onNavigateBack: () -> Unit,
    onOpenFolder: (String) -> Unit
) {
    val groups by viewModel.duplicates.collectAsState()
    val progress by viewModel.duplicatesProgress.collectAsState()
    var marked by remember { mutableStateOf<Set<FileItem>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun leave() {
        viewModel.cancelDuplicates()
        onNavigateBack()
    }
    BackHandler { leave() }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(str(R.string.delete_items, marked.size)) },
            text = { Text(str(R.string.the_selected_items_will_be_deleted_perma)) },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteDuplicates(marked.toList())
                    marked = emptySet()
                    confirmDelete = false
                }) { Text(str(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(str(R.string.cancel)) } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(R.string.duplicates), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            val current = groups
            if (!current.isNullOrEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // The oldest copy of each group stays: it is most likely the original.
                    FilledTonalButton(
                        onClick = { marked = current.flatMap { it.drop(1) }.toSet() },
                        modifier = Modifier.weight(1f)
                    ) { Text(str(R.string.mark_extra_copies), maxLines = 1) }
                    Button(
                        onClick = { confirmDelete = true },
                        enabled = marked.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text(str(R.string.delete_n, marked.size), maxLines = 1) }
                }
            }
        }
    ) { padding ->
        val current = groups
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                progress != null || current == null -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(progress ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                current.isEmpty() -> Text(
                    str(R.string.no_duplicates),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        val wasted = current.sumOf { it[0].size * (it.size - 1) }
                        Text(
                            str(R.string.duplicates_summary, current.size, FileItem("", "", size = wasted).formattedSize()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    items(current, key = { it[0].path }) { group ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                "${group[0].formattedSize()} × ${group.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 10.dp)
                            )
                            group.forEach { item ->
                                DuplicateRow(
                                    item = item,
                                    checked = item in marked,
                                    onCheck = { marked = if (item in marked) marked - item else marked + item },
                                    onOpen = { onOpenFolder(item.path.substringBeforeLast('/')) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateRow(item: FileItem, checked: Boolean, onCheck: () -> Unit, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = { onCheck() })
        FileIcon(item = item)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${item.path.substringBeforeLast('/')} · ${item.formattedDate()}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

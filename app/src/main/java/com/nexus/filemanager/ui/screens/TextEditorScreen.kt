package com.nexus.filemanager.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.filemanager.data.model.FileItem
import com.nexus.filemanager.data.model.StorageType
import com.nexus.filemanager.data.repository.FileRepository
import com.nexus.filemanager.ui.theme.RootColor
import com.nexus.filemanager.ui.theme.SuccessGreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    fileItem: FileItem,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { FileRepository() }

    var content by remember { mutableStateOf("") }
    var originalContent by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var fontSize by remember { mutableFloatStateOf(13f) }

    val isModified = content != originalContent

    BackHandler {
        onNavigateBack()
    }

    LaunchedEffect(fileItem.path) {
        isLoading = true
        try {
            val text = repo.readFile(fileItem.path, fileItem.storageType, null)
            content = text
            originalContent = text
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка чтения: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = fileItem.name + (if (isModified) " *" else ""),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                            if (fileItem.storageType == StorageType.ROOT) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "Root",
                                    tint = RootColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text(
                            text = fileItem.path,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            fontSize = if (fontSize >= 18f) 11f else fontSize + 2f
                        }
                    ) {
                        Icon(Icons.Default.FormatSize, contentDescription = "Размер шрифта")
                    }

                    IconButton(
                        onClick = {
                            isSaving = true
                            scope.launch {
                                val ok = repo.writeFile(fileItem.path, content, fileItem.storageType, null)
                                isSaving = false
                                if (ok) {
                                    originalContent = content
                                    Toast.makeText(context, "Файл успешно сохранен", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Не удалось сохранить файл", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = isModified && !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "Сохранить",
                                tint = if (isModified) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            val vScrollState = rememberScrollState()
            val hScrollState = rememberScrollState()
            val linesCount = remember(content) { content.lines().size.coerceAtLeast(1) }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Line numbers gutter
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(vScrollState)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        for (i in 1..linesCount) {
                            Text(
                                text = "$i",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSize.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    lineHeight = (fontSize * 1.4f).sp
                                )
                            )
                        }
                    }
                }

                // Editor area
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(8.dp)
                ) {
                    BasicTextField(
                        value = content,
                        onValueChange = { content = it },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = (fontSize * 1.4f).sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(vScrollState)
                            .horizontalScroll(hScrollState)
                    )
                }
            }
        }
    }
}

package com.nexus.filemanager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.filemanager.data.root.RootCommander
import com.nexus.filemanager.ui.theme.RootColor
import com.nexus.filemanager.ui.theme.SuccessGreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootTerminalScreen(
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var inputCommand by remember { mutableStateOf("") }
    val terminalOutput = remember {
        mutableStateListOf(
            "YAFM Root Shell [v1.0]",
            "Подключено к локальной консоли суперпользователя (su)",
            "------------------------------------------------"
        )
    }
    var isExecuting by remember { mutableStateOf(false) }

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    BackHandler {
        onNavigateBack()
    }

    fun runCmd(cmd: String) {
        if (cmd.isBlank() || isExecuting) return
        val command = cmd.trim()
        inputCommand = ""
        terminalOutput.add("# $command")
        isExecuting = true

        scope.launch {
            val result = RootCommander.execute(command)
            if (result.stdout.isNotEmpty()) {
                terminalOutput.addAll(result.stdout)
            }
            if (result.stderr.isNotEmpty()) {
                terminalOutput.addAll(result.stderr.map { "[err] $it" })
            }
            if (result.exitCode != 0) {
                terminalOutput.add("[Process exited with code ${result.exitCode}]")
            }
            isExecuting = false
            vScroll.animateScrollTo(vScroll.maxValue)
        }
    }

    LaunchedEffect(terminalOutput.size) {
        vScroll.animateScrollTo(vScroll.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = RootColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Root Консоль", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { terminalOutput.clear() }) {
                        Icon(Icons.Default.ClearAll, contentDescription = "Очистить")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Quick Command Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = false,
                    onClick = { runCmd("getenforce") },
                    label = { Text("SELinux Статус") }
                )
                FilterChip(
                    selected = false,
                    onClick = { runCmd("setenforce 0; getenforce") },
                    label = { Text("SELinux Permissive") }
                )
                FilterChip(
                    selected = false,
                    onClick = { runCmd("setenforce 1; getenforce") },
                    label = { Text("SELinux Enforcing") }
                )
                FilterChip(
                    selected = false,
                    onClick = { runCmd("df -h") },
                    label = { Text("Разделы (df -h)") }
                )
                FilterChip(
                    selected = false,
                    onClick = { runCmd("uname -a") },
                    label = { Text("Ядро (uname -a)") }
                )
                FilterChip(
                    selected = false,
                    onClick = { runCmd("ls -la /data/adb/modules 2>/dev/null") },
                    label = { Text("Magisk Модули") }
                )
                FilterChip(
                    selected = false,
                    onClick = { runCmd("getprop ro.build.version.release") },
                    label = { Text("Android Version") }
                )
            }

            // Terminal Black Screen
            Surface(
                color = Color(0xFF090D16),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(vScroll)
                        .horizontalScroll(hScroll)
                ) {
                    terminalOutput.forEach { line ->
                        val color = when {
                            line.startsWith("# ") -> SuccessGreen
                            line.startsWith("[err]") -> RootColor
                            line.startsWith("[Process") -> Color.Yellow
                            else -> Color(0xFFE2E8F0)
                        }
                        Text(
                            text = line,
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = color,
                                lineHeight = 16.sp
                            )
                        )
                    }
                }
            }

            // Command input row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputCommand,
                    onValueChange = { inputCommand = it },
                    placeholder = { Text("Введите команду...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { runCmd(inputCommand) },
                    enabled = inputCommand.isNotBlank() && !isExecuting
                ) {
                    if (isExecuting) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить",
                            tint = if (inputCommand.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

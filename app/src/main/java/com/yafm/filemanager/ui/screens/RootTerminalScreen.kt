package com.yafm.filemanager.ui.screens

import androidx.compose.material3.AssistChip
import com.yafm.filemanager.str
import com.yafm.filemanager.R
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
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
import com.yafm.filemanager.data.root.RootCommander
import com.yafm.filemanager.ui.theme.RootColor
import com.yafm.filemanager.ui.theme.SuccessGreen
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
            str(R.string.connected_to_the_local_superuser_console),
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
                        Text(str(R.string.root_console), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { terminalOutput.clear() }) {
                        Icon(Icons.Default.ClearAll, contentDescription = str(R.string.clear))
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
                // Edge-to-edge: the keyboard is not part of the Scaffold's insets.
                .consumeWindowInsets(paddingValues)
                .imePadding()
        ) {
            // Quick commands, by group: mostly read-only looks at the system.
            var group by remember { mutableStateOf(0) }
            val groups = remember {
                listOf(
                    R.string.cmd_group_system to listOf(
                        "Android" to "getprop ro.build.version.release; getprop ro.build.version.security_patch",
                        "Build" to "getprop ro.build.fingerprint",
                        "Kernel" to "uname -a",
                        "Uptime" to "uptime",
                        "SELinux" to "getenforce",
                        "SELinux → Permissive" to "setenforce 0; getenforce",
                        "SELinux → Enforcing" to "setenforce 1; getenforce",
                        "Mounts" to "mount | grep -E ' /(system|vendor|product|odm|data) '",
                        "df -h" to "df -h"
                    ),
                    R.string.cmd_group_hardware to listOf(
                        "Battery" to "dumpsys battery | grep -E 'level|status|health|temperature|voltage|charge counter'",
                        "Temperatures" to "for z in /sys/class/thermal/thermal_zone*; do echo \"$(cat \$z/type): $(cat \$z/temp)\"; done 2>/dev/null | head -30",
                        "CPU" to "grep -m1 -E 'Hardware|model name' /proc/cpuinfo; nproc",
                        "CPU MHz" to "for c in /sys/devices/system/cpu/cpu[0-9]*; do echo \"\${c##*/}: $((\$(cat \$c/cpufreq/scaling_cur_freq) / 1000)) MHz\"; done 2>/dev/null",
                        "Memory" to "free -h 2>/dev/null || head -5 /proc/meminfo",
                        "Display" to "wm size; wm density"
                    ),
                    R.string.cmd_group_network to listOf(
                        "IP" to "ip -br addr",
                        "Routes" to "ip route",
                        "DNS" to "getprop net.dns1; getprop net.dns2",
                        "Ports" to "netstat -tln 2>/dev/null || ss -tln",
                        "Ping 1.1.1.1" to "ping -c 3 -W 2 1.1.1.1",
                        "Wi-Fi" to "dumpsys wifi | grep -m1 'mWifiInfo'"
                    ),
                    R.string.cmd_group_apps to listOf(
                        "Current app" to "dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'",
                        "User apps" to "pm list packages -3 | wc -l",
                        "Log" to "logcat -d -t 40",
                        "Crashes" to "logcat -d -b crash -t 40"
                    ),
                    // CPU is averaged over 3 s: the second frame of top covers just that interval.
                    R.string.cmd_group_processes to listOf(
                        "Top CPU (3 s)" to "top -b -n 2 -d 3 -m 12 | awk '/Tasks:/{n++} n==2'",
                        "Top RAM" to "ps -A -o PID,USER,RSS,NAME -k -rss | head -13 | awk 'NR==1{printf \"%6s %-12s %7s  %s\\n\", \"PID\", \"USER\", \"MB\", \"NAME\"; next} {printf \"%6s %-12s %7.1f  %s\\n\", \$1, \$2, \$3/1024, \$4}'",
                        "Top" to "top -b -n 1 -m 15"
                    ),
                    R.string.cmd_group_root to listOf(
                        "Modules" to "ls /data/adb/modules 2>/dev/null",
                        "Root version" to "magisk -v 2>/dev/null; ksud -V 2>/dev/null; apd -V 2>/dev/null; su -v",
                        "Boot state" to "getprop ro.boot.verifiedbootstate; getprop ro.boot.flash.locked",
                        "id" to "id"
                    )
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groups.forEachIndexed { i, (title, _) ->
                    FilterChip(selected = group == i, onClick = { group = i }, label = { Text(str(title)) })
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groups[group].second.forEach { (label, command) ->
                    AssistChip(onClick = { runCmd(command) }, label = { Text(label) })
                }
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
                    placeholder = { Text(str(R.string.enter_a_command)) },
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
                            contentDescription = str(R.string.send),
                            tint = if (inputCommand.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

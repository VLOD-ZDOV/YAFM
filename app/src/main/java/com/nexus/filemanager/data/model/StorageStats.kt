package com.nexus.filemanager.data.model

import java.util.Locale

data class StorageStats(
    val label: String,
    val path: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val isRoot: Boolean = false
) {
    val usedBytes: Long
        get() = (totalBytes - freeBytes).coerceAtLeast(0L)

    val usagePercentage: Float
        get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    fun formattedTotal(): String = formatBytes(totalBytes)
    fun formattedUsed(): String = formatBytes(usedBytes)
    fun formattedFree(): String = formatBytes(freeBytes)

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 GB"
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f GB", gb)
    }
}

data class RootInfo(
    val isRootAvailable: Boolean = false,
    val isRootGranted: Boolean = false,
    val suVersion: String = "",
    val rootImplementation: String = "None"
)

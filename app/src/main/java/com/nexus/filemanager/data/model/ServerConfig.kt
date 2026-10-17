package com.nexus.filemanager.data.model

import java.util.UUID

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: StorageType,
    val host: String,
    val port: Int = when (type) {
        StorageType.SMB -> 445
        StorageType.SFTP -> 22
        StorageType.FTP -> 21
        else -> 0
    },
    val username: String = "",
    val password: String = "",
    val shareName: String = "",     // SMB share name, e.g. "Public" or "shared"
    val remotePath: String = "/",    // Default directory, e.g. "/" or "/home"
    val isAnonymous: Boolean = false
) {
    fun summary(): String {
        return when (type) {
            StorageType.SMB -> "smb://$host${if (shareName.isNotEmpty()) "/$shareName" else ""}"
            StorageType.SFTP -> "sftp://${if (username.isNotEmpty()) "$username@" else ""}$host:$port"
            StorageType.FTP -> "ftp://${if (username.isNotEmpty()) "$username@" else ""}$host:$port"
            else -> host
        }
    }
}

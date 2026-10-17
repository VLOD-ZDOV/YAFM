package com.nexus.filemanager.data.model

/** A directory in one of the storages: the explorer's position and the target of a paste. */
data class Location(
    val type: StorageType,
    val path: String,
    val server: ServerConfig? = null
)

package com.nexus.filemanager

import android.app.Application
import com.nexus.filemanager.data.repository.FileRepository
import com.nexus.filemanager.data.settings.SettingsRepository

class YafmApp : Application() {
    val repository by lazy { FileRepository(this) }
    val settings by lazy { SettingsRepository(this) }
}

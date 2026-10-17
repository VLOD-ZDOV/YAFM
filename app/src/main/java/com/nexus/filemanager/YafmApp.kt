package com.nexus.filemanager

import android.app.Application
import com.nexus.filemanager.data.repository.FileRepository

class YafmApp : Application() {
    val repository by lazy { FileRepository(this) }
}

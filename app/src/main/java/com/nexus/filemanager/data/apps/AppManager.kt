package com.nexus.filemanager.data.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import com.nexus.filemanager.data.root.RootCommander
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

data class AppModel(
    val name: String,
    val packageName: String,
    val versionName: String,
    val isSystemApp: Boolean,
    val apkFile: File,
    val apkSize: Long,
    val icon: Drawable? = null
) {
    fun formattedSize(): String {
        if (apkSize <= 0) return "0 MB"
        val mb = apkSize.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }
}

object AppManager {

    suspend fun getInstalledApps(context: Context): List<AppModel> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_META_DATA
        }

        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(flags as PackageManager.PackageInfoFlags)
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags as Int)
        }

        val list = mutableListOf<AppModel>()

        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val apkFile = File(appInfo.sourceDir)
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            val label = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                pkg.packageName
            }

            val icon = try {
                pm.getApplicationIcon(appInfo)
            } catch (_: Exception) {
                null
            }

            list.add(
                AppModel(
                    name = label,
                    packageName = pkg.packageName,
                    versionName = pkg.versionName ?: "1.0",
                    isSystemApp = isSystem,
                    apkFile = apkFile,
                    apkSize = if (apkFile.exists()) apkFile.length() else 0L,
                    icon = icon
                )
            )
        }

        list.sortedWith(compareBy({ it.isSystemApp }, { it.name.lowercase() }))
    }

    suspend fun extractApk(app: AppModel): File = withContext(Dispatchers.IO) {
        val backupDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ApkBackups")
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }

        val sanitizedName = app.name.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
        val targetFile = File(backupDir, "${sanitizedName}_${app.versionName}.apk")

        FileInputStream(app.apkFile).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }

        targetFile
    }

    suspend fun uninstallSystemAppRoot(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val result = RootCommander.execute("pm uninstall --user 0 $packageName")
        result.isSuccess
    }

    suspend fun toggleFreezeAppRoot(packageName: String, freeze: Boolean): Boolean = withContext(Dispatchers.IO) {
        val action = if (freeze) "disable" else "enable"
        val result = RootCommander.execute("pm $action $packageName")
        result.isSuccess
    }
}

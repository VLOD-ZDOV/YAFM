package com.yafm.filemanager.data.update

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long)

/** Checks this app's GitHub releases and installs a newer APK through the system installer. */
class Updater(private val context: Context) {

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    val currentVersion: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Whether the automatic check is due (at most every 12 hours). */
    fun checkDue(): Boolean = System.currentTimeMillis() - prefs.getLong("lastCheck", 0) > 12 * 3600_000L

    /** The latest release if it is newer than this build, else null. */
    suspend fun newerRelease(): Release? = withContext(Dispatchers.IO) {
        prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode == 404) return@withContext null
            if (conn.responseCode != 200) throw IOException(str(R.string.github_code, conn.responseCode))
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val version = json.getString("tag_name").removePrefix("v")
            if (!isNewer(version, currentVersion)) return@withContext null
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@withContext null
            Release(
                version = version,
                notes = json.optString("body", ""),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size", 0)
            )
        } finally {
            conn.disconnect()
        }
    }

    suspend fun download(release: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "YAFM-${release.version}.apk")
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException(str(R.string.download_code, conn.responseCode))
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        out
    }

    /** False when the user first has to allow installs from this app; its settings page is opened then. */
    fun install(apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/VLOD-ZDOV/YAFM/releases/latest"

        /** Compares dotted versions numerically: 1.10.0 > 1.9.2. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

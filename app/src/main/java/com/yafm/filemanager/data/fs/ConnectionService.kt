package com.yafm.filemanager.data.fs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yafm.filemanager.MainActivity
import com.yafm.filemanager.R
import com.yafm.filemanager.YafmApp
import com.yafm.filemanager.data.repository.FileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Android soon freezes an app that left the screen, and its connections die; on a server with one-time
 * codes that means a new code. While the app is away with a server open, this keeps it running for
 * [FileRepository.KEEP_MINUTES], then closes the connections.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timer: Job? = null
    private var progress: Job? = null
    private val repository get() = (application as YafmApp).repository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_KEEP -> keep()
            ACTION_CLOSE -> closeConnections()
            else -> finish(startId)
        }
        return START_NOT_STICKY
    }

    private fun keep() {
        val keepMs = TimeUnit.MINUTES.toMillis(FileRepository.KEEP_MINUTES)
        startForeground(NOTIFICATION_ID, notification(System.currentTimeMillis() + keepMs))
        val deadline = SystemClock.elapsedRealtime() + keepMs
        progress?.cancel()
        progress = scope.launch {
            repository.transfers.state.collectLatest { state ->
                val current = state.current ?: return@collectLatest
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(System.currentTimeMillis() + keepMs, current.name, (current.fraction * 100).toInt())
                )
                delay(NOTIFY_MS)
            }
        }
        timer?.cancel()
        timer = scope.launch {
            // Delays do not count while the phone sleeps, so the time is looked up.
            while (SystemClock.elapsedRealtime() < deadline || repository.isBusy) delay(CHECK_MS)
            closeConnections()
        }
    }

    private fun closeConnections() {
        repository.closeConnections()
        finish()
    }

    private fun finish(startId: Int? = null) {
        timer?.cancel()
        progress?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    // Android's daily limit for this kind of service, far above the time kept here.
    override fun onTimeout(startId: Int, fgsType: Int) = closeConnections()

    override fun onDestroy() {
        scope.cancel()
        running = false
        super.onDestroy()
    }

    private fun notification(closesAt: Long, file: String? = null, percent: Int = 0): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.connections), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(
            this, 0, Intent(this, ConnectionService::class.java).setAction(ACTION_CLOSE), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle(getString(if (file != null) R.string.transfer_running else R.string.connection_stays_open))
            .setContentText(file ?: getString(R.string.connection_closes_by_itself))
            .setProgress(if (file != null) 100 else 0, percent, false)
            .setContentIntent(open)
            .addAction(R.drawable.ic_close, getString(R.string.close), close)
            .setWhen(closesAt)
            .setUsesChronometer(file == null)
            .setChronometerCountDown(file == null)
            .setShowWhen(file == null)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "connections"
        private const val NOTIFICATION_ID = 2
        private const val CHECK_MS = 30_000L
        private const val NOTIFY_MS = 2_000L
        private const val ACTION_KEEP = "keep"
        private const val ACTION_CLOSE = "close"
        private const val ACTION_STOP = "stop"

        @Volatile
        private var running = false

        fun start(context: Context) {
            // Android may refuse it once the app is in the background: connections then last as they did.
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java).setAction(ACTION_KEEP))
                running = true
            }
        }

        // A command, not stopService(): stopping before startForeground() has run would crash the app.
        fun stop(context: Context) {
            if (!running) return
            running = false
            context.startService(Intent(context, ConnectionService::class.java).setAction(ACTION_STOP))
        }
    }
}

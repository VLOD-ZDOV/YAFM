package com.yafm.filemanager.data.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.yafm.filemanager.MainActivity
import com.yafm.filemanager.R
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult

/** Keeps music playing in the background, with the system's media notification and lock screen controls. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val close = SessionCommand("com.yafm.filemanager.CLOSE", Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        // Shuffle, repeat and speed stay as they were left.
        val saved = getSharedPreferences("player", MODE_PRIVATE)
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                // By default the queue goes round: Next on the last track plays the first one.
                repeatMode = saved.getInt("repeat", Player.REPEAT_MODE_ALL)
                shuffleModeEnabled = saved.getBoolean("shuffle", false)
                setPlaybackSpeed(saved.getFloat("speed", 1f))
                addListener(object : Player.Listener {
                    override fun onRepeatModeChanged(repeatMode: Int) = saved.edit().putInt("repeat", repeatMode).apply()
                    override fun onShuffleModeEnabledChanged(enabled: Boolean) = saved.edit().putBoolean("shuffle", enabled).apply()
                    override fun onPlaybackParametersChanged(parameters: PlaybackParameters) =
                        saved.edit().putFloat("speed", parameters.speed).apply()
                })
            }
        // Tapping the notification opens the player screen.
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setMediaButtonPreferences(
                listOf(
                    CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                        .setCustomIconResId(R.drawable.ic_close)
                        .setDisplayName(getString(R.string.close))
                        .setSessionCommand(close)
                        .build()
                )
            )
            .setCallback(object : MediaSession.Callback {
                // The service is exported for the system's media controls; other apps get no access,
                // since a controller could queue any file this app can read and see what is playing.
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    if (controller.isTrusted || controller.packageName == packageName) {
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailableSessionCommands(
                                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(close).build()
                            )
                            .build()
                    } else {
                        MediaSession.ConnectionResult.reject()
                    }

                // The notification's cross: an empty player has nothing to show, so the notification goes.
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand != close) return super.onCustomCommand(session, controller, customCommand, args)
                    session.player.stop()
                    session.player.clearMediaItems()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiping the app away while paused ends the service; while playing, music goes on.
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

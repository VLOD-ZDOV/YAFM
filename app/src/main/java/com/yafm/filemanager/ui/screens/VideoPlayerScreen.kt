package com.yafm.filemanager.ui.screens

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.Locale
import kotlin.math.abs

private val SCALES = listOf(
    MediaPlayer.ScaleType.SURFACE_BEST_FIT to str(R.string.fit),
    MediaPlayer.ScaleType.SURFACE_FILL to str(R.string.fill),
    MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
    MediaPlayer.ScaleType.SURFACE_4_3 to "4:3",
    MediaPlayer.ScaleType.SURFACE_ORIGINAL to str(R.string.original)
)
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * libVLC player: plays practically any container and codec (mkv with AC3/DTS, avi, wmv, flv...).
 * [playlist] are the videos of the same folder, for next/previous.
 */
@Composable
fun VideoPlayerScreen(
    file: File,
    playlist: List<File>,
    seekStepMs: Long,
    sideGestures: Boolean,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as Activity
    val prefs = remember { context.getSharedPreferences("video_positions", Context.MODE_PRIVATE) }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var current by remember { mutableStateOf(file) }
    val libVlc = remember { LibVLC(context, arrayListOf("--audio-time-stretch")) }
    val player = remember { MediaPlayer(libVlc) }
    val layout = remember { VLCVideoLayout(context) }

    var playing by remember { mutableStateOf(false) }
    var time by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var scaleIndex by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var hint by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    var seekDrag by remember { mutableStateOf<Float?>(null) }

    fun touch() {
        lastTouch = System.currentTimeMillis()
    }

    fun savePosition() {
        val l = player.length
        val t = player.time
        // Near the end means watched: start from the beginning next time.
        prefs.edit().putLong(current.absolutePath, if (l > 0 && t < l - 5_000) t else 0L).apply()
    }

    /** [startAt]: where to begin, in ms; by default where this file was left. */
    fun open(f: File, startAt: Long? = null) {
        if (player.media != null) savePosition()
        current = f
        val media = Media(libVlc, Uri.fromFile(f))
        media.setHWDecoderEnabled(true, false)
        // A seek right after play() is ignored while the file is still opening: libVLC starts there itself.
        val start = startAt ?: prefs.getLong(f.absolutePath, 0L)
        if (start > 0) media.addOption(":start-time=${start / 1000.0}")
        player.media = media
        media.release()
        player.play()
    }

    // After the end libVLC is stopped and ignores seeks: the file is opened again first.
    var ended by remember { mutableStateOf(false) }

    fun restartIfEnded(): Boolean {
        if (!ended) return false
        ended = false
        open(current, 0L)
        return true
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, maxOf(player.length, length, 0L))
        if (ended) {
            ended = false
            open(current, target)
        } else {
            player.setTime(target)
        }
        // Paused, libVLC sends no time events: the bar would stay where it was.
        time = target
        touch()
    }

    fun seekBy(ms: Long) = seekTo((if (ended) length else player.time) + ms)

    val index = playlist.indexOfFirst { it.absolutePath == current.absolutePath }

    DisposableEffect(Unit) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val brightness = window.attributes.screenBrightness

        player.attachViews(layout, null, true, false)
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> playing = true
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.TimeChanged -> time = e.timeChanged
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.EndReached -> {
                    playing = false
                    prefs.edit().putLong(current.absolutePath, 0L).apply()
                    val next = playlist.getOrNull(playlist.indexOfFirst { it.absolutePath == current.absolutePath } + 1)
                    if (next != null) open(next) else {
                        ended = true
                        controls = true
                    }
                }
            }
        }
        open(file)

        onDispose {
            savePosition()
            player.setEventListener(null)
            player.stop()
            player.detachViews()
            player.release()
            libVlc.release()
            insets.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = brightness }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Turn the screen the way the video is: wide films go landscape. libVLC knows the frame size
    // only some time after playback starts, so it is looked up until it is there.
    LaunchedEffect(current) {
        repeat(50) {
            val v = player.currentVideoTrack
            if (v != null && v.width > 0 && v.height > 0) {
                // Orientations 4..7 are the transposed ones (rotated by 90 or 270 degrees).
                val (w, h) = if (v.orientation in 4..7) v.height to v.width else v.width to v.height
                activity.requestedOrientation = if (w > h) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                return@LaunchedEffect
            }
            delay(200)
        }
    }

    // Controls hide after three quiet seconds while playing.
    LaunchedEffect(controls, lastTouch, playing) {
        if (controls && playing) {
            delay(3_000)
            if (System.currentTimeMillis() - lastTouch >= 2_900) controls = false
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(900)
            hint = null
        }
    }

    // Back unlocks a locked screen instead of leaving, so the lock never traps the user.
    BackHandler {
        if (locked) {
            locked = false
            controls = true
            touch()
        } else {
            onNavigateBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(factory = { layout }, modifier = Modifier.fillMaxSize())

        // Gestures: tap shows controls, double tap seeks, vertical drags set brightness (left) and volume (right),
        // horizontal drags seek.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = {
                            controls = !controls
                            touch()
                        },
                        onDoubleTap = { offset ->
                            if (locked) return@detectTapGestures
                            val seconds = seekStepMs / 1000
                            if (offset.x < size.width / 2) {
                                seekBy(-seekStepMs)
                                hint = "−$seconds s"
                            } else {
                                seekBy(seekStepMs)
                                hint = "+$seconds s"
                            }
                        }
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var mode = 0 // 0 undecided, 1 seek, 2 brightness, 3 volume
                    var accX = 0f
                    var accY = 0f
                    var startTime = 0L
                    detectDragGestures(
                        onDragStart = { start ->
                            accX = 0f
                            accY = 0f
                            startTime = player.time
                            // Brightness and volume swipes only when turned on in the settings.
                            mode = when {
                                !sideGestures -> -1
                                start.x < size.width / 2 -> -2
                                else -> -3
                            }
                        },
                        onDragEnd = {
                            seekDrag?.let { seekTo(it.toLong()) }
                            seekDrag = null
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            accX += drag.x
                            accY += drag.y
                            if (mode < 0 && (abs(accX) > 24 || abs(accY) > 24)) {
                                mode = if (abs(accX) > abs(accY)) 1 else if (mode == -1) 0 else -mode
                            }
                            when (mode) {
                                1 -> {
                                    val target = (startTime + (accX / size.width) * 90_000).coerceIn(0f, length.toFloat())
                                    seekDrag = target
                                    hint = formatTime(target.toLong())
                                }
                                2 -> {
                                    val window = activity.window
                                    val now = window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
                                    val value = (now - drag.y / size.height).coerceIn(0.01f, 1f)
                                    window.attributes = window.attributes.apply { screenBrightness = value }
                                    hint = str(R.string.brightness, (value * 100).toInt())
                                }
                                3 -> {
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val step = size.height / (max * 1.5f)
                                    if (abs(accY) >= step) {
                                        val delta = if (accY < 0) 1 else -1
                                        accY = 0f
                                        val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + delta).coerceIn(0, max)
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                        hint = str(R.string.volume, v * 100 / max)
                                    }
                                }
                            }
                        }
                    )
                }
        )

        hint?.let {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            }
        }

        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            if (locked) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Button(
                        onClick = { locked = false; touch() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.6f), contentColor = Color.White),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(str(R.string.unlock))
                    }
                }
                return@AnimatedVisibility
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .safeDrawingPadding()
            ) {
                // Top: options, and the title on a line of its own so narrow screens still show it
                Column(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back), tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menu = "audio"; touch() }) {
                            Icon(Icons.Default.Audiotrack, contentDescription = str(R.string.audio_track), tint = Color.White)
                        }
                        DropdownMenu(expanded = menu == "audio", onDismissRequest = { menu = null }) {
                            (player.audioTracks ?: emptyArray()).forEach { t ->
                                DropdownMenuItem(
                                    text = { Text((if (t.id == player.audioTrack) "• " else "") + t.name) },
                                    onClick = { player.setAudioTrack(t.id); menu = null }
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = "spu"; touch() }) {
                            Icon(Icons.Default.Subtitles, contentDescription = str(R.string.subtitles), tint = Color.White)
                        }
                        DropdownMenu(expanded = menu == "spu", onDismissRequest = { menu = null }) {
                            val tracks = player.spuTracks ?: emptyArray()
                            if (tracks.isEmpty()) DropdownMenuItem(text = { Text(str(R.string.no_subtitles)) }, onClick = { menu = null })
                            tracks.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text((if (t.id == player.spuTrack) "• " else "") + t.name) },
                                    onClick = { player.setSpuTrack(t.id); menu = null }
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = "speed"; touch() }) {
                            Icon(Icons.Default.Speed, contentDescription = str(R.string.speed), tint = Color.White)
                        }
                        DropdownMenu(expanded = menu == "speed", onDismissRequest = { menu = null }) {
                            SPEEDS.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text((if (s == speed) "• " else "") + "${s}×") },
                                    onClick = { speed = s; player.rate = s; menu = null }
                                )
                            }
                        }
                    }
                    IconButton(onClick = {
                        scaleIndex = (scaleIndex + 1) % SCALES.size
                        player.videoScale = SCALES[scaleIndex].first
                        hint = SCALES[scaleIndex].second
                        touch()
                    }) {
                        Icon(Icons.Default.AspectRatio, contentDescription = str(R.string.frame), tint = Color.White)
                    }
                    IconButton(onClick = {
                        activity.requestedOrientation =
                            if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        touch()
                    }) {
                        Icon(Icons.Default.ScreenRotation, contentDescription = str(R.string.rotate), tint = Color.White)
                    }
                    IconButton(onClick = { locked = true; controls = false }) {
                        Icon(Icons.Default.LockOpen, contentDescription = str(R.string.lock), tint = Color.White)
                    }
                }
                Text(
                    current.name,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                }

                // Center: previous, rewind, play, forward, next
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { playlist.getOrNull(index - 1)?.let { open(it) }; touch() }, enabled = index > 0) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = str(R.string.previous_2), tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    IconButton(onClick = { seekBy(-seekStepMs) }) {
                        Icon(replayIcon(seekStepMs), contentDescription = str(R.string.s_10_s_2), tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    IconButton(
                        onClick = {
                            if (!restartIfEnded()) {
                                if (player.isPlaying) player.pause() else player.play()
                            }
                            touch()
                        },
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) str(R.string.pause) else str(R.string.play),
                            tint = Color.White,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                    IconButton(onClick = { seekBy(seekStepMs) }) {
                        Icon(forwardIcon(seekStepMs), contentDescription = str(R.string.s_10_s), tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    IconButton(
                        onClick = { playlist.getOrNull(index + 1)?.let { open(it) }; touch() },
                        enabled = index >= 0 && index < playlist.size - 1
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = str(R.string.next_2), tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }

                // Bottom: seek bar
                Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    var sliding by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = sliding ?: seekDrag ?: time.toFloat(),
                        onValueChange = { sliding = it; touch() },
                        onValueChangeFinished = { sliding?.let { seekTo(it.toLong()) }; sliding = null },
                        valueRange = 0f..maxOf(length.toFloat(), 1f),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White)
                    )
                    Row {
                        Text(formatTime((sliding ?: seekDrag)?.toLong() ?: time), color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(formatTime(length), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(1.dp))
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun replayIcon(step: Long) = when (step) {
    5_000L -> Icons.Default.Replay5
    10_000L -> Icons.Default.Replay10
    30_000L -> Icons.Default.Replay30
    else -> Icons.Default.FastRewind
}

private fun forwardIcon(step: Long) = when (step) {
    5_000L -> Icons.Default.Forward5
    10_000L -> Icons.Default.Forward10
    30_000L -> Icons.Default.Forward30
    else -> Icons.Default.FastForward
}

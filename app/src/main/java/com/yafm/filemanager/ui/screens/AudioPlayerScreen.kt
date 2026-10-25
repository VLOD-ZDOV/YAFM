package com.yafm.filemanager.ui.screens

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.content.ComponentName
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.yafm.filemanager.data.audio.PlaybackService
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/** Plays [file] in the background service, with the other tracks of its folder as the queue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    file: File?,
    playlist: List<File>,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var meta by remember { mutableStateOf(MediaMetadata.EMPTY) }
    var index by remember { mutableIntStateOf(0) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_ALL) }
    var queue by remember(playlist, file) { mutableStateOf(if (file == null) emptyList() else playlist.ifEmpty { listOf(file) }) }

    BackHandler(onBack = onNavigateBack)

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                playing = player.isPlaying
                duration = player.duration.coerceAtLeast(0L)
                meta = player.mediaMetadata
                index = player.currentMediaItemIndex
                shuffle = player.shuffleModeEnabled
                repeat = player.repeatMode
            }
        }
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(listener)
            if (file == null) {
                // Opened from the notification: show the queue that plays.
                queue = (0 until c.mediaItemCount).map { File(c.getMediaItemAt(it).mediaId) }
                listener.onEvents(c, Player.Events(androidx.media3.common.FlagSet.Builder().build()))
                return@addListener
            }
            val items = queue.map { f ->
                MediaItem.Builder()
                    .setMediaId(f.absolutePath)
                    .setUri(Uri.fromFile(f))
                    // No title here: it would hide the one from the file's tags. Without tags the
                    // screen falls back to the file name.
                    .build()
            }
            val start = queue.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
            // Opening the track that is already playing keeps it going instead of starting over.
            if (c.currentMediaItem?.mediaId != file.absolutePath) {
                c.setMediaItems(items, start, 0L)
                c.prepare()
                c.play()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller?.removeListener(listener)
            MediaController.releaseFuture(future)
        }
    }

    LaunchedEffect(controller) {
        while (true) {
            controller?.let { position = it.currentPosition }
            delay(500)
        }
    }

    val art = remember(meta) {
        meta.artworkData?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }

    @Composable
    fun Cover(modifier: Modifier) {
        Box(
            modifier = modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (art != null) {
                Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }

    @Composable
    fun Controls() {
        // Fixed heights: a long title or a missing artist must not move the buttons.
        Text(
            meta.title?.toString() ?: queue.getOrNull(index)?.nameWithoutExtension ?: "",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            listOfNotNull(meta.artist, meta.albumTitle).joinToString(" — "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        var sliding by remember { mutableStateOf<Float?>(null) }
        Slider(
            value = sliding ?: position.toFloat().coerceAtMost(duration.toFloat()),
            onValueChange = { sliding = it },
            onValueChangeFinished = { sliding?.let { controller?.seekTo(it.toLong()) }; sliding = null },
            valueRange = 0f..maxOf(duration.toFloat(), 1f)
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(sliding?.toLong() ?: position), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(formatClock(duration), style = MaterialTheme.typography.bodySmall)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
        ) {
            IconButton(onClick = { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = str(R.string.shuffle),
                    tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { controller?.seekToPrevious() }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = str(R.string.previous), modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) str(R.string.pause) else str(R.string.play),
                    modifier = Modifier.size(36.dp)
                )
            }
            IconButton(onClick = { controller?.seekToNext() }) {
                Icon(Icons.Default.SkipNext, contentDescription = str(R.string.next), modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = {
                controller?.let {
                    it.repeatMode = when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
            }) {
                Icon(
                    if (repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = str(R.string.repeat),
                    tint = if (repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    fun Queue(modifier: Modifier) {
        LazyColumn(modifier = modifier.fillMaxWidth()) {
            itemsIndexed(queue) { i, f ->
                Text(
                    f.nameWithoutExtension,
                    color = if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (i == index) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { controller?.seekTo(i, 0L); controller?.play() }
                        .padding(vertical = 10.dp)
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(R.string.music)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            // Landscape: the cover on the left, sized by the height; controls and queue on the right.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Cover(Modifier.fillMaxHeight())
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Controls()
                    Queue(Modifier.weight(1f))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Cover(Modifier.fillMaxWidth(0.7f))
                Spacer(Modifier.height(16.dp))
                Controls()
                Spacer(Modifier.height(8.dp))
                Queue(Modifier.weight(1f))
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

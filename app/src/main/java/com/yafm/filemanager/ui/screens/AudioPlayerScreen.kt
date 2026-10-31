package com.yafm.filemanager.ui.screens

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.content.ComponentName
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.annotation.OptIn as AndroidOptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.yafm.filemanager.data.audio.PlaybackService
import com.yafm.filemanager.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Plays [file] in the background service, with the other tracks of its folder as the queue. */
@OptIn(ExperimentalMaterial3Api::class)
@AndroidOptIn(UnstableApi::class)
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
    var speed by remember { mutableFloatStateOf(1f) }
    var showSpeeds by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var queue by remember(playlist, file) { mutableStateOf(if (file == null) emptyList() else playlist.ifEmpty { listOf(file) }) }

    BackHandler(onBack = onNavigateBack)

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        fun sync(player: Player) {
            playing = player.isPlaying
            duration = player.duration.coerceAtLeast(0L)
            position = player.currentPosition
            meta = player.mediaMetadata
            index = player.currentMediaItemIndex
            shuffle = player.shuffleModeEnabled
            repeat = player.repeatMode
            speed = player.playbackParameters.speed
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync(player)
        }
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(listener)
            // Events come only on changes: a track that already plays must be shown right away.
            sync(c)
            if (file == null) {
                // Opened from the notification: show the queue that plays.
                queue = (0 until c.mediaItemCount).map { File(c.getMediaItemAt(it).mediaId) }
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

    // The position moves only while playing: a paused player is read once, not polled.
    LaunchedEffect(controller, playing) {
        controller?.let { position = it.currentPosition }
        while (playing) {
            delay(500)
            controller?.let { position = it.currentPosition }
        }
    }

    // Decoded off the main thread and scaled down: a big cover would take tens of MB at full size.
    val art by produceState<ImageBitmap?>(null, meta) {
        value = meta.artworkData?.let { withContext(Dispatchers.Default) { decodeArt(it) } }
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
            val cover = art
            if (cover != null) {
                Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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

    val track = controller?.currentMediaItem?.mediaId ?: queue.getOrNull(index)?.absolutePath
    if (showInfo && track != null) {
        val tracks = controller?.currentTracks?.groups.orEmpty()
        TrackInfoDialog(
            path = track,
            format = tracks.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.getTrackFormat(0),
            onDismiss = { showInfo = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(R.string.music)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
                    }
                },
                actions = {
                    Box {
                        val speedHint = str(R.string.playback_speed)
                        TextButton(
                            onClick = { showSpeeds = true },
                            modifier = Modifier.semantics { contentDescription = speedHint }
                        ) { Text(speedLabel(speed)) }
                        DropdownMenu(expanded = showSpeeds, onDismissRequest = { showSpeeds = false }) {
                            SPEEDS.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(speedLabel(s)) },
                                    trailingIcon = { if (s == speed) Icon(Icons.Default.Check, contentDescription = null) },
                                    onClick = {
                                        controller?.setPlaybackSpeed(s)
                                        showSpeeds = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Default.Info, contentDescription = str(R.string.track_info))
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

/** Cover art at most about [max] px a side. */
private fun decodeArt(data: ByteArray, max: Int = 1024): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(data, 0, data.size, options)?.asImageBitmap()
}

private fun speedLabel(speed: Float) =
    String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"

@Composable
private fun TrackInfoDialog(path: String, format: Format?, onDismiss: () -> Unit) {
    val rows by produceState<List<Pair<Int, String>>>(emptyList(), path, format) {
        value = withContext(Dispatchers.IO) { trackInfo(path, format) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(File(path).name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    rows.forEach { (label, value) ->
                        Text(str(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(str(R.string.close)) } }
    )
}

/** Tags from the file; rate and channels from the player, which knows them on every Android version. */
private fun trackInfo(path: String, format: Format?): List<Pair<Int, String>> {
    val file = File(path)
    val retriever = MediaMetadataRetriever()
    try {
        runCatching { retriever.setDataSource(path) }
        fun tag(key: Int) = runCatching { retriever.extractMetadata(key) }.getOrNull()?.takeIf { it.isNotBlank() }
        val kbps = tag(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { it / 1000 }
        val ms = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val mime = format?.sampleMimeType ?: tag(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        return listOfNotNull(
            tag(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { R.string.title to it },
            tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { R.string.artist to it },
            tag(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { R.string.album to it },
            tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.let { R.string.album_artist to it },
            tag(MediaMetadataRetriever.METADATA_KEY_YEAR)?.let { R.string.year to it },
            tag(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { R.string.genre to it },
            tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let { R.string.track_number to it },
            ms?.let { R.string.duration to formatClock(it) },
            mime?.let { R.string.format to it },
            kbps?.let { R.string.bitrate to str(R.string.bitrate_value, it) },
            format?.sampleRate?.takeIf { it != Format.NO_VALUE }?.let { R.string.sample_rate to str(R.string.sample_rate_value, it) },
            format?.channelCount?.takeIf { it != Format.NO_VALUE }?.let { R.string.channels to it.toString() },
            R.string.size to FileItem(name = file.name, path = path, size = file.length()).formattedSize(),
            R.string.file_location to path
        )
    } finally {
        retriever.release()
    }
}

private fun formatClock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

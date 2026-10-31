package com.yafm.filemanager.ui.components

import android.content.ComponentName
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.yafm.filemanager.R
import com.yafm.filemanager.data.audio.PlaybackService
import com.yafm.filemanager.str
import com.yafm.filemanager.ui.thumbs.Thumbnail
import java.io.File

/** A bar for the music that plays in the background: pause, stop, or open the player. */
@Composable
fun MiniPlayer(onOpen: (File, List<File>) -> Unit, onShown: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var artist by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var hasMusic by remember { mutableStateOf(false) }
    var track by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        fun sync(player: Player) {
            hasMusic = player.mediaItemCount > 0 && player.playbackState != Player.STATE_IDLE
            playing = player.isPlaying
            val id = player.currentMediaItem?.mediaId
            track = id
            title = player.mediaMetadata.title?.toString() ?: id?.let { File(it).nameWithoutExtension }
            artist = player.mediaMetadata.artist?.toString()
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync(player)
        }
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(listener)
            sync(c)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller?.removeListener(listener)
            MediaController.releaseFuture(future)
        }
    }

    val c = controller
    val shown = hasMusic && c != null
    LaunchedEffect(shown) { onShown(shown) }
    DisposableEffect(Unit) { onDispose { onShown(false) } }
    if (!shown || c == null) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable {
                    val queue = (0 until c.mediaItemCount).map { File(c.getMediaItemAt(it).mediaId) }
                    c.currentMediaItem?.mediaId?.let { onOpen(File(it), queue) }
                }
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
        ) {
            val px = with(LocalDensity.current) { 40.dp.roundToPx() }
            val cover = remember(track, px) {
                track?.let { path ->
                    ImageRequest.Builder(context)
                        .data(Thumbnail(path, File(path).lastModified(), audio = true, size = px))
                        .size(px)
                        .build()
                }
            }
            // The note stays under the cover, for songs without one.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                artist?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
            }
            IconButton(onClick = { if (c.isPlaying) c.pause() else c.play() }) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = str(if (playing) R.string.pause else R.string.play)
                )
            }
            IconButton(onClick = {
                c.stop()
                c.clearMediaItems()
            }) {
                Icon(Icons.Default.Close, contentDescription = str(R.string.close))
            }
        }
    }
}

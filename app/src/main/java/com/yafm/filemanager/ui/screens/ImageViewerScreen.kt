package com.yafm.filemanager.ui.screens

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.yafm.filemanager.data.model.FileItem
import java.io.File

// Zoomed in, the picture is decoded again up to this size, so it stays sharp without a 50 MP bitmap.
private const val SHARP_SIDE = 4096

/**
 * Pictures of a folder, swiped left and right ([images]; just [fileItem] when empty). Pinch zooms where the
 * fingers are, a double tap zooms in or back, a tap hides the bars.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    fileItem: FileItem,
    images: List<FileItem> = emptyList(),
    onNavigateBack: () -> Unit,
    onEdit: (FileItem) -> Unit
) {
    val context = LocalContext.current
    val list = remember(fileItem, images) { images.takeIf { l -> l.any { it.path == fileItem.path } } ?: listOf(fileItem) }
    val pager = rememberPagerState(initialPage = list.indexOfFirst { it.path == fileItem.path }.coerceAtLeast(0)) { list.size }
    var bars by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val current = list[pager.currentPage.coerceIn(0, list.size - 1)]

    BackHandler(onBack = onNavigateBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            // Zoomed in, a drag moves the picture instead of turning the page.
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            key = { list[it].path },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            ZoomableImage(
                file = File(list[page].path),
                description = list[page].name,
                isCurrent = page == pager.currentPage,
                onZoomed = { if (page == pager.currentPage) zoomed = it },
                onTap = { bars = !bars }
            )
        }

        AnimatedVisibility(visible = bars, enter = fadeIn(), exit = fadeOut()) {
            val imageFile = File(current.path)
            TopAppBar(
                title = {
                    Column {
                        Text(current.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                        Text(
                            (if (list.size > 1) "${pager.currentPage + 1} / ${list.size} · " else "") + current.formattedSize(),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                            color = Color.LightGray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back), tint = Color.White)
                    }
                },
                actions = {
                    // A copy fetched from a server lives in the cache: edits there would be lost.
                    if (!imageFile.absolutePath.startsWith(context.cacheDir.absolutePath)) {
                        IconButton(onClick = { onEdit(current) }) {
                            Icon(Icons.Default.Edit, contentDescription = str(R.string.edit_image), tint = Color.White)
                        }
                    }
                    IconButton(onClick = { shareImage(context, imageFile) }) {
                        Icon(Icons.Default.Share, contentDescription = str(R.string.share), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.6f))
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    file: File,
    description: String,
    isCurrent: Boolean,
    onZoomed: (Boolean) -> Unit,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }

    // A page swiped away comes back unzoomed.
    DisposableEffect(isCurrent) {
        if (!isCurrent) { scale = 1f; offset = Offset.Zero }
        onDispose { }
    }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = area.width * (s - 1f) / 2f
        val maxY = area.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** Scales by [zoom] keeping the point under [focus] (screen pixels) in place. */
    fun zoomAt(focus: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(1f, 8f)
        val center = Offset(area.width / 2f, area.height / 2f)
        val k = newScale / scale
        offset = if (newScale == 1f) Offset.Zero else clamp((offset - (focus - center)) * k + (focus - center), newScale)
        scale = newScale
        onZoomed(scale > 1f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { area = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap -> if (scale > 1f) { scale = 1f; offset = Offset.Zero; onZoomed(false) } else zoomAt(tap, 2.5f) }
                )
            }
            .pointerInput(Unit) {
                // Two fingers zoom; one finger moves the picture only when zoomed, else the pager turns the page.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f) zoomAt(event.calculateCentroid(useCurrent = true), zoom)
                            if (scale > 1f) offset = clamp(offset + pan, scale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val layer = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        SubcomposeAsyncImage(
            model = file,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = layer,
            loading = {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        )
        // Over the screen-sized picture, a sharper one once zoomed in.
        if (isCurrent && scale > 1.2f) {
            val sharp = remember(file) {
                ImageRequest.Builder(context).data(file).size(SHARP_SIDE).crossfade(false).build()
            }
            AsyncImage(model = sharp, contentDescription = null, contentScale = ContentScale.Fit, modifier = layer)
        }
    }
}

private fun shareImage(context: Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, str(R.string.share_image)))
    } catch (_: Exception) {}
}

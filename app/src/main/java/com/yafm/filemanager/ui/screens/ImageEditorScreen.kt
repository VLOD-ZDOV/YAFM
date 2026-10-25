package com.yafm.filemanager.ui.screens

import com.yafm.filemanager.str
import com.yafm.filemanager.R
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import android.media.MediaScannerConnection
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yafm.filemanager.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** A line drawn on the picture, in the picture's own pixels. */
private class Stroke2(val color: Color, val width: Float, val points: MutableList<Offset> = mutableListOf())

// Big photos are edited at most this size on a side: a 50 MP picture in full would not fit in memory.
private const val MAX_SIDE = 4096

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditorScreen(
    fileItem: FileItem,
    onNavigateBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var downscaled by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<Bitmap>() }
    val strokes = remember { mutableStateListOf<Stroke2>() }
    var drawing by remember { mutableStateOf(false) }
    var brushColor by remember { mutableStateOf(Color.Red) }
    var brushWidth by remember { mutableFloatStateOf(12f) }
    var showResize by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var changed by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var redraw by remember { mutableIntStateOf(0) }

    LaunchedEffect(fileItem.path) {
        val (bmp, scaled) = withContext(Dispatchers.IO) { decode(fileItem.path) }
        bitmap = bmp
        downscaled = scaled
    }

    fun leave() = if (changed || strokes.isNotEmpty()) confirmLeave = true else onNavigateBack()
    BackHandler { leave() }

    /** Strokes are drawn into the picture before any other change, so they turn and flip with it. */
    fun baked(): Bitmap? {
        val src = bitmap ?: return null
        if (strokes.isEmpty()) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(out)
        strokes.forEach { s ->
            val paint = Paint().apply {
                color = s.color.toArgb(); strokeWidth = s.width; style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
            }
            val path = android.graphics.Path()
            s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            if (s.points.size == 1) path.lineTo(s.points[0].x + 0.1f, s.points[0].y)
            canvas.drawPath(path, paint)
        }
        strokes.clear()
        return out
    }

    fun apply(transform: (Bitmap) -> Bitmap) {
        val src = baked() ?: return
        history.add(bitmap!!)
        // A few steps back are enough; each one holds a whole picture.
        while (history.size > 4) history.removeAt(0)
        bitmap = transform(src)
        changed = true
    }

    fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
        else history.removeLastOrNull()?.let { bitmap = it }
    }

    fun save(replace: Boolean) {
        val result = baked() ?: return
        busy = true
        scope.launch {
            val original = File(fileItem.path)
            val target = if (replace) original else copyName(original)
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    // Read before writing: replacing overwrites the original's details.
                    val details = readDetails(original)
                    val modified = original.lastModified()
                    // Formats Android cannot write (heic, gif...) are saved as a copy in PNG or JPEG.
                    FileOutputStream(target).use { out -> result.compress(formatFor(target), 92, out) }
                    writeDetails(target, details)
                    // Galleries without a shooting date sort by this: the photo stays among its own days.
                    if (replace) target.setLastModified(modified)
                    MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
                }.exceptionOrNull()
            }
            busy = false
            if (error != null) {
                Toast.makeText(context, str(R.string.error, error.message), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, str(R.string.saved_as, target.name), Toast.LENGTH_SHORT).show()
                onSaved()
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(str(R.string.discard_changes)) },
            confirmButton = { Button(onClick = { confirmLeave = false; onNavigateBack() }) { Text(str(R.string.discard)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(str(R.string.cancel)) } }
        )
    }

    if (showSave) {
        // A shrunk big photo must not take the original's place.
        val canReplace = File(fileItem.path).extension.lowercase() in WRITABLE && !downscaled
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text(str(R.string.save)) },
            text = { Text(if (canReplace) str(R.string.save_copy_or_replace) else str(R.string.save_copy_only)) },
            confirmButton = { Button(onClick = { showSave = false; save(replace = false) }) { Text(str(R.string.save_copy)) } },
            dismissButton = {
                if (canReplace) TextButton(onClick = { showSave = false; save(replace = true) }) { Text(str(R.string.replace_original)) }
            }
        )
    }

    bitmap?.let { bmp ->
        if (showResize) {
            AlertDialog(
                onDismissRequest = { showResize = false },
                title = { Text(str(R.string.resize)) },
                text = {
                    Column {
                        Text("${bmp.width} × ${bmp.height}")
                        listOf(75, 50, 25).forEach { pct ->
                            val w = bmp.width * pct / 100
                            val h = bmp.height * pct / 100
                            TextButton(onClick = {
                                showResize = false
                                apply { Bitmap.createScaledBitmap(it, w.coerceAtLeast(1), h.coerceAtLeast(1), true) }
                            }) { Text("$pct% — $w × $h") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showResize = false }) { Text(str(R.string.cancel)) } }
            )
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(fileItem.name, maxLines = 1, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back), tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { undo() }, enabled = history.isNotEmpty() || strokes.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = str(R.string.undo), tint = Color.White)
                    }
                    IconButton(onClick = { showSave = true }, enabled = bitmap != null && !busy) {
                        Icon(Icons.Default.Save, contentDescription = str(R.string.save), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (downscaled) {
                    Text(str(R.string.edited_downscaled, MAX_SIDE), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
                if (drawing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.White, Color.Black).forEach { c ->
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .background(c, CircleShape)
                                    .border(if (c == brushColor) 3.dp else 1.dp, if (c == brushColor) Color.White else Color.Gray, CircleShape)
                                    .clickable { brushColor = c }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(str(R.string.brush), color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Slider(value = brushWidth, onValueChange = { brushWidth = it }, valueRange = 2f..60f, modifier = Modifier.weight(1f))
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val enabled = bitmap != null && !busy
                    IconButton(onClick = { apply { rotate(it, -90f) } }, enabled = enabled) {
                        Icon(Icons.Default.RotateLeft, contentDescription = str(R.string.rotate_left), tint = Color.White)
                    }
                    IconButton(onClick = { apply { rotate(it, 90f) } }, enabled = enabled) {
                        Icon(Icons.Default.RotateRight, contentDescription = str(R.string.rotate_right), tint = Color.White)
                    }
                    IconButton(onClick = { apply { flip(it, horizontal = true) } }, enabled = enabled) {
                        Icon(Icons.Default.Flip, contentDescription = str(R.string.flip_horizontal), tint = Color.White)
                    }
                    IconButton(onClick = { apply { flip(it, horizontal = false) } }, enabled = enabled) {
                        Icon(
                            Icons.Default.Flip, contentDescription = str(R.string.flip_vertical), tint = Color.White,
                            modifier = Modifier.rotate(90f)
                        )
                    }
                    IconButton(onClick = { showResize = true }, enabled = enabled) {
                        Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = str(R.string.resize), tint = Color.White)
                    }
                    FilterChip(
                        selected = drawing,
                        onClick = { drawing = !drawing },
                        enabled = enabled,
                        label = { Icon(Icons.Default.Brush, contentDescription = str(R.string.draw)) }
                    )
                }
            }
        }
    ) { padding ->
        val bmp = bitmap
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (bmp == null || busy) {
                CircularProgressIndicator(color = Color.White)
            }
            if (bmp != null) {
                // Where the picture sits on screen with Fit, to map touches to its pixels.
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val scale = minOf(boxW / bmp.width, boxH / bmp.height)
                val left = (boxW - bmp.width * scale) / 2
                val top = (boxH - bmp.height * scale) / 2
                val image = remember(bmp) { bmp.asImageBitmap() }
                Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (drawing) Modifier.pointerInput(bmp, brushColor, brushWidth) {
                                detectDragGestures(
                                    onDragStart = { p ->
                                        strokes.add(Stroke2(brushColor, brushWidth / scale, mutableListOf(Offset((p.x - left) / scale, (p.y - top) / scale))))
                                    },
                                    onDrag = { change, _ ->
                                        val p = change.position
                                        val last = strokes.lastOrNull() ?: return@detectDragGestures
                                        last.points.add(Offset((p.x - left) / scale, (p.y - top) / scale))
                                        // The points list is not observed: this redraws the canvas.
                                        redraw++
                                    }
                                )
                            } else Modifier
                        )
                ) {
                    redraw.let { }
                    strokes.forEach { s ->
                        val path = Path()
                        s.points.forEachIndexed { i, p ->
                            val x = left + p.x * scale
                            val y = top + p.y * scale
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, s.color, style = Stroke(width = s.width * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
        }
    }
}

private val WRITABLE = setOf("jpg", "jpeg", "png", "webp")

private fun formatFor(file: File): Bitmap.CompressFormat = when (file.extension.lowercase()) {
    "png" -> Bitmap.CompressFormat.PNG
    @Suppress("DEPRECATION")
    "webp" -> Bitmap.CompressFormat.WEBP
    "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
    else -> Bitmap.CompressFormat.PNG
}

/** "photo.jpg" -> "photo_edited.jpg" (then _edited_2...); unwritable formats become PNG. */
private fun copyName(file: File): File {
    val ext = file.extension.lowercase().takeIf { it in WRITABLE } ?: "png"
    val base = file.nameWithoutExtension + "_edited"
    var candidate = File(file.parentFile, "$base.$ext")
    var n = 2
    while (candidate.exists()) candidate = File(file.parentFile, "${base}_${n++}.$ext")
    return candidate
}

/** The picture upright (as its EXIF says), at most [MAX_SIDE] on a side; true when it was made smaller. */
private fun decode(path: String): Pair<Bitmap?, Boolean> {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
    val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = sample
        inMutable = false
    }) ?: return null to false
    val degrees = runCatching {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    return (if (degrees != 0f) rotate(bmp, degrees) else bmp) to (sample > 1)
}

// What a camera writes about a shot: when, where, with what. The picture itself is new, so its size,
// orientation (already applied) and thumbnail are not carried over.
private val DETAILS = listOf(
    ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_ARTIST,
    ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_USER_COMMENT
)

private fun readDetails(file: File): Map<String, String> = runCatching {
    val exif = ExifInterface(file.absolutePath)
    DETAILS.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
}.getOrDefault(emptyMap())

/** Best effort: a picture without its details is still a saved picture. */
private fun writeDetails(file: File, details: Map<String, String>) {
    if (details.isEmpty()) return
    runCatching {
        val exif = ExifInterface(file.absolutePath)
        details.forEach { (tag, value) -> exif.setAttribute(tag, value) }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        exif.saveAttributes()
    }
}

private fun rotate(src: Bitmap, degrees: Float): Bitmap =
    Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees) }, true)

private fun flip(src: Bitmap, horizontal: Boolean): Bitmap =
    Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { if (horizontal) preScale(-1f, 1f) else preScale(1f, -1f) }, true)

package com.yafm.filemanager.data.media

import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Removes what a photo says about itself: when and where it was taken, the camera, comments. */
object Metadata {

    /** Formats whose details can be rewritten in place. */
    val SUPPORTED = setOf("jpg", "jpeg", "png", "webp")

    // Tags that describe how the picture is stored, not the shot: removing them could break the file.
    // The orientation stays too, or the picture would show turned.
    private val KEEP = setOf(
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
        ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION, ExifInterface.TAG_BITS_PER_SAMPLE,
        ExifInterface.TAG_COMPRESSION, ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION, ExifInterface.TAG_SAMPLES_PER_PIXEL,
        ExifInterface.TAG_PLANAR_CONFIGURATION, ExifInterface.TAG_Y_CB_CR_SUB_SAMPLING, ExifInterface.TAG_Y_CB_CR_POSITIONING,
        ExifInterface.TAG_STRIP_OFFSETS, ExifInterface.TAG_STRIP_BYTE_COUNTS, ExifInterface.TAG_ROWS_PER_STRIP,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT, ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
        ExifInterface.TAG_COLOR_SPACE, ExifInterface.TAG_EXIF_VERSION, ExifInterface.TAG_DEFAULT_CROP_SIZE
    )

    private val TAGS: List<String> by lazy {
        ExifInterface::class.java.fields
            .filter { it.name.startsWith("TAG_") && it.type == String::class.java }
            .mapNotNull { it.get(null) as? String }
            .filter { it !in KEEP }
            .distinct()
    }

    fun strip(file: File) {
        val exif = ExifInterface(file.absolutePath)
        TAGS.forEach { tag -> if (exif.getAttribute(tag) != null) exif.setAttribute(tag, null) }
        exif.saveAttributes()
    }
}

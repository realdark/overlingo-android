package com.overlingo.android.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loading and preparing photos.
 *
 * Everything is scaled down to MAX_SIDE pixels on the long side: enough for
 * text recognition (ML Kit needs at least ~16 px per character), while saving memory.
 */
object ImageLoader {

    const val MAX_SIDE = 3200

    /**
     * Photo from the camera: scales down and rotates per the sensor (in that order - less
     * memory). Intermediate copies are recycled right away - the camera photo exists
     * only for this processing and nothing else holds on to it.
     */
    fun fromCamera(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val scaled = scaleDown(bitmap, MAX_SIDE)
        if (scaled !== bitmap) bitmap.recycle()
        val rotated = rotate(scaled, rotationDegrees)
        if (rotated !== scaled) scaled.recycle()
        return rotated
    }

    /** Photo from the gallery or "Share": scales down already while decoding and makes it upright. */
    fun fromUri(context: Context, uri: Uri): Bitmap {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(context, uri)
        } else {
            decodeWithBitmapFactory(context, uri)
        }
        return scaleDown(bitmap, MAX_SIDE)
    }

    /**
     * Android 9+: ImageDecoder also reads HEIC/HEIF (Samsung photos) and rotates
     * the image per EXIF by itself. Software bitmap - ML Kit and Canvas don't
     * work with "hardware" bitmaps.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height))
        }
    }

    /** Android 8: BitmapFactory + manual rotation per EXIF. */
    private fun decodeWithBitmapFactory(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver

        // Dimensions only (inJustDecodeBounds) - decodeStream then always returns null, that's expected.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported image format")

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Cannot decode $uri")

        val rotation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (e: IOException) {
            0
        }
        return rotate(decoded, rotation)
    }

    /** A power of 2 such that the long side stays at least MAX_SIDE. */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= MAX_SIDE) sample *= 2
        return sample
    }

    /**
     * The camera captures the full sensor (4:3), while the screen shows a cropped part
     * (FILL_CENTER). CameraX records the visible part in ImageProxy.cropRect,
     * but toBitmap() doesn't apply it - we crop here so the photo keeps only
     * what was on screen. The old copy is recycled.
     */
    fun cropToPreview(bitmap: Bitmap, imageWidth: Int, imageHeight: Int, left: Int, top: Int, right: Int, bottom: Int): Bitmap {
        val bounds = cropBounds(bitmap.width, bitmap.height, imageWidth, imageHeight, left, top, right, bottom)
            ?: return bitmap
        val cropped = Bitmap.createBitmap(bitmap, bounds[0], bounds[1], bounds[2], bounds[3])
        if (cropped !== bitmap) bitmap.recycle()
        return cropped
    }

    /**
     * The crop in bitmap pixels: [x, y, width, height]; null - nothing to crop
     * (cropRect covers the whole image or is invalid). cropRect is in the
     * camera frame's dimensions, while the bitmap may be a different size.
     */
    fun cropBounds(
        bitmapWidth: Int, bitmapHeight: Int, imageWidth: Int, imageHeight: Int,
        left: Int, top: Int, right: Int, bottom: Int,
    ): IntArray? {
        if (imageWidth <= 0 || imageHeight <= 0 || right <= left || bottom <= top) return null
        val sx = bitmapWidth.toFloat() / imageWidth
        val sy = bitmapHeight.toFloat() / imageHeight
        val x = (left * sx).roundToInt().coerceIn(0, bitmapWidth - 1)
        val y = (top * sy).roundToInt().coerceIn(0, bitmapHeight - 1)
        val w = ((right - left) * sx).roundToInt().coerceIn(1, bitmapWidth - x)
        val h = ((bottom - top) * sy).roundToInt().coerceIn(1, bitmapHeight - y)
        if (x == 0 && y == 0 && w == bitmapWidth && h == bitmapHeight) return null
        return intArrayOf(x, y, w, h)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true
        )
    }
}

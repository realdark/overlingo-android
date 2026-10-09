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
 * Зареждане и подготовка на снимки.
 *
 * Всичко се смалява до MAX_SIDE пиксела по дългата страна: стига за
 * разпознаване на текста (ML Kit иска поне ~16 px на буква), а пести памет.
 */
object ImageLoader {

    const val MAX_SIDE = 3200

    /**
     * Снимка от камерата: смалява и завърта според сензора (в този ред - по-малко
     * памет). Междинните копия се освобождават веднага - снимката от камерата е
     * само за тази обработка и никой друг не я държи.
     */
    fun fromCamera(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val scaled = scaleDown(bitmap, MAX_SIDE)
        if (scaled !== bitmap) bitmap.recycle()
        val rotated = rotate(scaled, rotationDegrees)
        if (rotated !== scaled) scaled.recycle()
        return rotated
    }

    /** Снимка от галерията или "Сподели": смалява още при декодиране и я изправя. */
    fun fromUri(context: Context, uri: Uri): Bitmap {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(context, uri)
        } else {
            decodeWithBitmapFactory(context, uri)
        }
        return scaleDown(bitmap, MAX_SIDE)
    }

    /**
     * Android 9+: ImageDecoder чете и HEIC/HEIF (снимките на Samsung) и сам
     * завърта снимката според EXIF. Софтуерен bitmap - ML Kit и Canvas не
     * работят с "хардуерни" bitmap-и.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height))
        }
    }

    /** Android 8: BitmapFactory + ръчно завъртане според EXIF. */
    private fun decodeWithBitmapFactory(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver

        // Само размерите (inJustDecodeBounds) - decodeStream тогава винаги връща null, това е нормално.
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

    /** Степен на 2, така че дългата страна да остане поне MAX_SIDE. */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= MAX_SIDE) sample *= 2
        return sample
    }

    /**
     * Камерата снима целия сензор (4:3), а екранът показва изрязана част
     * (FILL_CENTER). CameraX отбелязва показаната част в ImageProxy.cropRect,
     * но toBitmap() не я прилага - изрязваме тук, за да остане на снимката
     * само това, което е било на екрана. Старото копие се освобождава.
     */
    fun cropToPreview(bitmap: Bitmap, imageWidth: Int, imageHeight: Int, left: Int, top: Int, right: Int, bottom: Int): Bitmap {
        val bounds = cropBounds(bitmap.width, bitmap.height, imageWidth, imageHeight, left, top, right, bottom)
            ?: return bitmap
        val cropped = Bitmap.createBitmap(bitmap, bounds[0], bounds[1], bounds[2], bounds[3])
        if (cropped !== bitmap) bitmap.recycle()
        return cropped
    }

    /**
     * Изрязването в пиксели на bitmap-а: [x, y, ширина, височина]; null - няма
     * какво да се реже (cropRect е цялата снимка или е невалиден). cropRect е
     * в размерите на кадъра от камерата, а bitmap-ът може да е с друг размер.
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

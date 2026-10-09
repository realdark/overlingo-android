package com.overlingo.android.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Записва снимката с преводите в галерията или я подготвя за "Сподели". */
object ImageExporter {

    private const val JPEG_QUALITY = 92

    /**
     * В галерията, в папка Pictures/Overlingo. На Android 10+ не иска разрешение;
     * на Android 8-9 - WRITE_EXTERNAL_STORAGE (иска го екранът преди да извика това).
     */
    fun saveToGallery(context: Context, image: Bitmap): Uri {
        val resolver = context.contentResolver
        val name = "Overlingo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Overlingo")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                // Android 8-9 няма RELATIVE_PATH - пътят се задава директно (иначе отива в DCIM/Camera).
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Overlingo")
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri")
            stream.use { image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // не оставяме празен файл в галерията
            throw e
        }
        return uri
    }

    /** Временен файл (в кеша на приложението) с адрес, който друго приложение може да прочете. */
    fun shareableUri(context: Context, image: Bitmap): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // Ново име всеки път - предишното споделяне може още да се чете от другото приложение;
        // по-старите файлове се трият, за да не се трупат.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L }?.forEach { it.delete() }
        val file = File(dir, "overlingo-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}

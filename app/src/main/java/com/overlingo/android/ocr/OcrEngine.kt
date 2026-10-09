package com.overlingo.android.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OcrException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Text recognition with Google ML Kit (Latin script). The model is bundled in
 * the APK and works without internet. ML Kit itself finds the blocks (paragraphs,
 * headings, signs) and the lines within them, even when the photo is tilted or
 * slightly curved.
 */
class OcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** The bitmap must already be upright (see ImageLoader). Coordinates are in its pixels. */
    suspend fun recognize(bitmap: Bitmap): List<TextBlock> {
        val result = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw OcrException(e.message ?: e.javaClass.simpleName, e)
        }
        return BlockCleaner.clean(result.textBlocks.mapNotNull(::convert))
    }

    fun close() = recognizer.close()

    private fun convert(block: Text.TextBlock): TextBlock? {
        val lines = block.lines.mapNotNull { line ->
            val words = line.elements.mapNotNull { element ->
                val box = element.boundingBox?.toBox() ?: return@mapNotNull null
                OcrWord(element.text, box)
            }
            if (words.isEmpty()) null else TextLine(words)
        }
        return if (lines.isEmpty()) null else TextBlock.of(lines)
    }

    private fun Rect.toBox() = Box(left, top, right, bottom)
}

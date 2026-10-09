package com.overlingo.android.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.overlingo.android.ocr.TextBlock
import kotlin.math.max

/**
 * Draws the translations onto the photo itself (for "Save" and "Share") - the same
 * boxes as on screen: a dark/light rectangle over the original text
 * with the translation fitted inside it.
 */
object OverlayRenderer {

    /** translations = null: just the photo (when the screen shows the original). */
    fun render(photo: Bitmap, blocks: List<TextBlock>, translations: List<String>?, style: OverlayStyle): Bitmap {
        if (translations == null) return photo
        val result = photo.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.background }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = style.text }

        blocks.forEachIndexed { index, block ->
            val text = translations.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val pad = block.lineHeight * 0.2f
            val rect = RectF(
                block.box.left - pad, block.box.top - pad,
                block.box.right + pad, block.box.bottom + pad,
            )
            val innerWidth = max(1, (rect.width() - 2 * pad).toInt())

            paint.textSize = fitFontSize(text, paint, innerWidth, rect.height(), block.lineHeight * 1.1f) * style.textScale
            val layout = layout(text, paint, innerWidth)
            // If the text doesn't fit, the box is extended downwards (same as on screen).
            if (layout.height > rect.height()) rect.bottom = rect.top + layout.height + pad

            canvas.drawRoundRect(rect, pad * 0.75f, pad * 0.75f, background)
            canvas.save()
            canvas.translate(rect.left + pad, rect.top + (rect.height() - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
        return result
    }

    /** The largest font size (binary search) at which the text fits in the rectangle. */
    private fun fitFontSize(text: String, paint: TextPaint, width: Int, height: Float, maxPx: Float): Float {
        var low = OverlayStyle.MIN_FONT_PX
        var high = max(low, maxPx)
        repeat(7) {
            val mid = (low + high) / 2f
            paint.textSize = mid
            if (layout(text, paint, width).height <= height) low = mid else high = mid
        }
        return low
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, OverlayStyle.LINE_SPACING)
            .setIncludePad(false)
            .build()
}

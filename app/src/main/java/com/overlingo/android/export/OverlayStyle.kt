package com.overlingo.android.export

import com.overlingo.android.data.AppSettings
import kotlin.math.roundToInt

/**
 * Как изглеждат прозорците с превод - общо за екрана и за запазената снимка.
 * Цветовете са ARGB (Int), за да се ползват и от Compose, и от android.graphics.
 */
data class OverlayStyle(
    /** Множител върху автоматично вписания размер на текста (0.7 - 1.5). */
    val textScale: Float = 1.0f,
    val background: Int = argb(0.9f, DARK),
    val text: Int = WHITE,
    val outline: Int = OUTLINE,
) {
    companion object {
        private const val DARK = 0x111827
        private const val LIGHT = 0xFFFFFF
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val NEAR_BLACK = 0xFF111827.toInt()
        private const val OUTLINE = 0xCC3B82F6.toInt()

        /** Минимален размер на шрифта (px) при вписването. */
        const val MIN_FONT_PX = 4f

        /** Междуредие (спрямо размера на шрифта). */
        const val LINE_SPACING = 1.15f

        fun from(settings: AppSettings) = OverlayStyle(
            textScale = settings.overlayTextScale,
            background = argb(settings.overlayOpacity, if (settings.overlayLight) LIGHT else DARK),
            text = if (settings.overlayLight) NEAR_BLACK else WHITE,
        )

        private fun argb(opacity: Float, rgb: Int): Int =
            ((opacity.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)
    }
}

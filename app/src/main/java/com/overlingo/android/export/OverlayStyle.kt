package com.overlingo.android.export

import com.overlingo.android.data.AppSettings
import kotlin.math.roundToInt

/**
 * How the translation boxes look - shared by the screen and the saved photo.
 * Colors are ARGB (Int) so they can be used by both Compose and android.graphics.
 */
data class OverlayStyle(
    /** Multiplier applied to the auto-fitted text size (0.7 - 1.5). */
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

        /** Minimum font size (px) when fitting. */
        const val MIN_FONT_PX = 4f

        /** Line spacing (relative to the font size). */
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

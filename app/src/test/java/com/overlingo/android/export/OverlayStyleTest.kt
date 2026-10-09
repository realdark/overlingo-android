package com.overlingo.android.export

import com.overlingo.android.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayStyleTest {

    @Test
    fun darkByDefault() {
        val style = OverlayStyle.from(AppSettings())
        assertEquals(0xFFFFFFFF.toInt(), style.text)
        assertEquals(0xE6, style.background ushr 24) // 90% opacity
        assertEquals(0x111827, style.background and 0xFFFFFF)
    }

    @Test
    fun lightWindowsWithCustomOpacityAndSize() {
        val style = OverlayStyle.from(AppSettings(overlayLight = true, overlayOpacity = 0.5f, overlayTextScale = 1.3f))
        assertEquals(0xFF111827.toInt(), style.text)
        assertEquals(0x80, style.background ushr 24)
        assertEquals(0xFFFFFF, style.background and 0xFFFFFF)
        assertEquals(1.3f, style.textScale)
    }
}

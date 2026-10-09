package com.overlingo.android.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropBoundsTest {

    @Test
    fun cropOfSameSizeBitmap() {
        // 4:3 frame (2592x1944), the screen shows the middle at a 9:16 width ratio -> only a central strip.
        assertArrayEquals(
            intArrayOf(750, 0, 1094, 1944),
            ImageLoader.cropBounds(2592, 1944, 2592, 1944, 750, 0, 1844, 1944),
        )
    }

    @Test
    fun cropIsScaledWhenBitmapIsSmaller() {
        assertArrayEquals(
            intArrayOf(375, 0, 547, 972),
            ImageLoader.cropBounds(1296, 972, 2592, 1944, 750, 0, 1844, 1944),
        )
    }

    @Test
    fun fullOrInvalidCropMeansNoCrop() {
        assertNull(ImageLoader.cropBounds(2592, 1944, 2592, 1944, 0, 0, 2592, 1944))
        assertNull(ImageLoader.cropBounds(2592, 1944, 2592, 1944, 0, 0, 0, 0))
        assertNull(ImageLoader.cropBounds(2592, 1944, 0, 0, 0, 0, 10, 10))
    }

    @Test
    fun cropStaysInsideBitmap() {
        assertArrayEquals(
            intArrayOf(0, 100, 100, 100),
            ImageLoader.cropBounds(100, 200, 100, 200, -10, 100, 120, 260),
        )
    }
}

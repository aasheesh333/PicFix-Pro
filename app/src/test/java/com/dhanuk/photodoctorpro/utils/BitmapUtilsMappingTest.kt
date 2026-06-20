package com.dhanuk.photodoctorpro.utils

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BitmapUtilsMappingTest {

    private fun mockBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    @Test
    fun `mapToBitmap returns null for zero-sized layout`() {
        val bitmap = mockBitmap(100, 100)
        assertNull(mapToBitmap(0f, 0f, 0f, 0f, bitmap))
        bitmap.recycle()
    }

    @Test
    fun `mapToBitmap maps top-left of layout to top-left of bitmap`() {
        val bitmap = mockBitmap(200, 100)
        val (x, y) = mapToBitmap(0f, 0f, 1000f, 1000f, bitmap)!!
        assertEquals(0f, x, 0.0001f)
        assertEquals(0f, y, 0.0001f)
        bitmap.recycle()
    }

    @Test
    fun `mapToBitmap respects ContentScale Fit padding when image is wider`() {
        val bitmap = mockBitmap(200, 100)
        val layoutW = 1000f
        val layoutH = 1000f
        val (x, y) = mapToBitmap(layoutW, layoutH / 2f, layoutW, layoutH, bitmap)!!
        assertEquals(200f, x, 0.5f)
        assertEquals(100f, y, 0.5f)
        bitmap.recycle()
    }

    @Test
    fun `calculateScaleFactor returns 1 for layout matching bitmap aspect`() {
        val bitmap = mockBitmap(500, 250)
        val factor = calculateScaleFactor(1000f, 500f, bitmap)
        assertEquals(2f, factor, 0.0001f)
        bitmap.recycle()
    }

    @Test
    fun `calculateScaleFactor returns 1 for zero-sized layout`() {
        val bitmap = mockBitmap(100, 100)
        assertEquals(1f, calculateScaleFactor(0f, 0f, bitmap), 0.0001f)
        bitmap.recycle()
    }
}

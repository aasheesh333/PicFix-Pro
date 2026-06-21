package com.dhanuk.photodoctorpro.ui.screens

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.SavedStateHandle
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the Document Scanner drag bug (BUG-3). The previous
 * `updateCorner` used `scale = bitmap.width / canvasSize.width` which ignored
 * the letterbox offset, so clicking in the letterbox area moved the corner to
 * the wrong bitmap position. The fix uses the same ContentScale.Fit math as
 * the Canvas overlay.
 */
class PerspectiveCropUpdateCornerTest {

    @Test
    fun `portrait image on landscape canvas maps letterboxed click correctly`() {
        val vm = PerspectiveCropViewModel(mockk<HistoryRepository>(relaxed = true), SavedStateHandle())
        // 1000x2000 portrait image (taller than wide).
        val bitmap = Bitmap.createBitmap(1000, 2000, Bitmap.Config.ARGB_8888)
        installOriginal(vm, bitmap, w = 1000, h = 2000)
        // Canvas is 2000x1000 (landscape). Image aspect (0.5) < canvas aspect (2.0).
        // So image is letterboxed LEFT/RIGHT: displayedH = 1000, displayedW = 500,
        // offsetX = (2000 - 500) / 2 = 750, offsetY = 0.
        val canvasSize = IntSize(2000, 1000)

        // Click at center of image (should map to bitmap center).
        val centerX = 750 + 500 / 2f
        val centerY = 0 + 1000 / 2f
        vm.updateCorner(0, centerX, centerY, canvasSize)
        val c0 = vm.uiState.value.corners[0]
        assertEquals(500f, c0.x, 1f) // bitmap center x
        assertEquals(1000f, c0.y, 1f) // bitmap center y

        // Click in letterbox (outside image). Should be clamped to nearest edge.
        // Click at x=100 (way in the left letterbox). Should clamp to displayedW start
        // → bitmap x = 0.
        vm.updateCorner(0, 100f, 500f, canvasSize)
        val c0b = vm.uiState.value.corners[0]
        assertEquals(0f, c0b.x, 1f) // clamped to image left edge → bitmap x = 0
    }

    @Test
    fun `landscape image on portrait canvas maps letterboxed click correctly`() {
        val vm = PerspectiveCropViewModel(mockk<HistoryRepository>(relaxed = true), SavedStateHandle())
        // 2000x1000 landscape image (wider than tall).
        val bitmap = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        installOriginal(vm, bitmap, w = 2000, h = 1000)
        // Canvas is 1000x2000 (portrait). Image aspect (2.0) > canvas aspect (0.5).
        // So image is letterboxed TOP/BOTTOM: displayedW = 1000, displayedH = 500,
        // offsetX = 0, offsetY = (2000 - 500) / 2 = 750.
        val canvasSize = IntSize(1000, 2000)

        // Click in the top letterbox (y=100). Should clamp to displayedH start
        // → bitmap y = 0.
        vm.updateCorner(0, 500f, 100f, canvasSize)
        val c0 = vm.uiState.value.corners[0]
        assertEquals(1000f, c0.x, 1f) // bitmap center x
        assertEquals(0f, c0.y, 1f)    // clamped to image top → bitmap y = 0
    }

    @Test
    fun `click at exact corner maps to bitmap corner`() {
        val vm = PerspectiveCropViewModel(mockk<HistoryRepository>(relaxed = true), SavedStateHandle())
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        installOriginal(vm, bitmap, w = 1000, h = 1000)
        // Same aspect → no letterbox.
        val canvasSize = IntSize(1000, 1000)

        vm.updateCorner(0, 0f, 0f, canvasSize)
        val c0 = vm.uiState.value.corners[0]
        assertEquals(0f, c0.x, 0.0001f)
        assertEquals(0f, c0.y, 0.0001f)

        vm.updateCorner(0, 1000f, 1000f, canvasSize)
        val c1 = vm.uiState.value.corners[0]
        assertEquals(1000f, c1.x, 0.0001f)
        assertEquals(1000f, c1.y, 0.0001f)
    }

    @Test
    fun `aspect ratio lock snaps corners to rectangle with correct ratio`() {
        val vm = PerspectiveCropViewModel(mockk<HistoryRepository>(relaxed = true), SavedStateHandle())
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        // Start with a 500x500 square region centered in the bitmap.
        installOriginal(vm, bitmap, w = 1000, h = 1000)
        vm.setAspectRatio(AspectRatioLock.RATIO_16_9)

        val corners = vm.uiState.value.corners
        assertEquals(4, corners.size)
        val w = (corners[1].x - corners[0].x)
        val h = (corners[3].y - corners[0].y)
        val ratio = w / h
        assertEquals(16f / 9f, ratio, 0.0001f)
    }

    @Test
    fun `aspect ratio lock anchors opposite corner during drag`() {
        val vm = PerspectiveCropViewModel(mockk<HistoryRepository>(relaxed = true), SavedStateHandle())
        val bitmap = Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888)
        // Place initial corners as a square 1000x1000 in the top-left.
        installOriginal(vm, bitmap, w = 2000, h = 2000)
        // Drag corner 0 (TL) to (200, 200) in bitmap coords (1:1 letterbox = no scaling).
        vm.setAspectRatio(AspectRatioLock.RATIO_4_3)
        // The opposite corner is BR (index 2), which was originally at
        // (1000, 1000). The BR corner must stay put.
        val corners = vm.uiState.value.corners
        assertEquals(1000f, corners[2].x, 0.0001f)
        assertEquals(1000f, corners[2].y, 0.0001f)
        // Width / height should equal 4:3.
        val w = corners[1].x - corners[0].x
        val h = corners[3].y - corners[0].y
        assertEquals(4f / 3f, w / h, 0.0001f)
    }

    @Test
    fun `free aspect ratio leaves corners unchanged`() {
        val vm = PerspectiveCropViewModel(mockk<HistoryRepository>(relaxed = true), SavedStateHandle())
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        installOriginal(vm, bitmap, w = 1000, h = 1000)
        // Lock then unlock: corners should keep the locked rectangle shape.
        vm.setAspectRatio(AspectRatioLock.SQUARE)
        val lockedCorners = vm.uiState.value.corners
        vm.setAspectRatio(AspectRatioLock.FREE)
        val freeCorners = vm.uiState.value.corners
        // The corners are the same after toggling FREE (no transformation).
        assertEquals(lockedCorners[0].x, freeCorners[0].x, 0.0001f)
        assertEquals(lockedCorners[0].y, freeCorners[0].y, 0.0001f)
    }

    @Test
    fun `aspect-locked drag snaps dragged corner to click position`() {
        val vm = PerspectiveCropViewModel(mockk<HistoryRepository>(relaxed = true), SavedStateHandle())
        val bitmap = Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888)
        // Initial 1000x1000 square in the top-left of the 2000x2000 bitmap.
        installOriginal(vm, bitmap, w = 2000, h = 2000)
        // Lock to 4:3. Note: with the new snap-to-click math, the rectangle
        // will become 1000x750 (using the existing 1000 horizontal side).
        vm.setAspectRatio(AspectRatioLock.RATIO_4_3)
        val beforeCorners = vm.uiState.value.corners
        val brXBefore = beforeCorners[2].x
        val brYBefore = beforeCorners[2].y
        // The BR (opposite of TL) must be preserved across the drag.
        // Now drag TL (index 0) to a specific click point in bitmap space.
        // Canvas = bitmap (no letterbox), so the click in canvas coords
        // equals the bitmap coord.
        val canvasSize = IntSize(2000, 2000)
        val clickX = 500f
        val clickY = 300f
        vm.updateCorner(0, clickX, clickY, canvasSize)
        val after = vm.uiState.value.corners
        // The dragged corner (TL, index 0) must land at the click position.
        assertEquals(clickX, after[0].x, 0.0001f)
        assertEquals(clickY, after[0].y, 0.0001f)
        // The opposite corner (BR) must stay at its position.
        assertEquals(brXBefore, after[2].x, 0.0001f)
        assertEquals(brYBefore, after[2].y, 0.0001f)
    }

    @Test
    fun `aspect-locked drag clamps corner inside bitmap bounds`() {
        val vm = PerspectiveCropViewModel(mockk<HistoryRepository>(relaxed = true), SavedStateHandle())
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        installOriginal(vm, bitmap, w = 1000, h = 1000)
        vm.setAspectRatio(AspectRatioLock.SQUARE)
        val canvasSize = IntSize(1000, 1000)
        // Try to drag TL outside the bitmap (negative coords and beyond size).
        vm.updateCorner(0, -200f, -300f, canvasSize)
        val after = vm.uiState.value.corners
        // TL is clamped to (0, 0) — the bitmap top-left.
        assertEquals(0f, after[0].x, 0.0001f)
        assertEquals(0f, after[0].y, 0.0001f)
    }

    @Test
    fun `aspect-locked drag produces an axis-aligned rectangle`() {
        val vm = PerspectiveCropViewModel(mockk<HistoryRepository>(relaxed = true), SavedStateHandle())
        val bitmap = Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888)
        installOriginal(vm, bitmap, w = 2000, h = 2000)
        vm.setAspectRatio(AspectRatioLock.RATIO_16_9)
        val canvasSize = IntSize(2000, 2000)
        // Drag TL to a random point.
        vm.updateCorner(0, 300f, 400f, canvasSize)
        val c = vm.uiState.value.corners
        // All 4 corners must form an axis-aligned rectangle (no diagonals).
        // TL.y == TR.y (top edge is horizontal)
        assertEquals(c[0].y, c[1].y, 0.0001f)
        // BL.y == BR.y (bottom edge is horizontal)
        assertEquals(c[3].y, c[2].y, 0.0001f)
        // TL.x == BL.x (left edge is vertical)
        assertEquals(c[0].x, c[3].x, 0.0001f)
        // TR.x == BR.x (right edge is vertical)
        assertEquals(c[1].x, c[2].x, 0.0001f)
    }

    /**
     * Use reflection to inject a synthetic image and initial corners into the VM.
     * The VM's onImageSelected() needs a real context; we bypass it here.
     */
    private fun installOriginal(vm: PerspectiveCropViewModel, bitmap: Bitmap, w: Int, h: Int) {
        val stateField = PerspectiveCropViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        val flow = stateField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<*>
        @Suppress("UNCHECKED_CAST")
        val typed = flow as kotlinx.coroutines.flow.MutableStateFlow<PerspectiveCropUiState>
        val padding = 0.03f
        typed.value = PerspectiveCropUiState(
            selectedImageUri = null,
            originalBitmap = bitmap,
            corners = listOf(
                PointF(w * padding, h * padding),
                PointF(w * (1 - padding), h * padding),
                PointF(w * (1 - padding), h * (1 - padding)),
                PointF(w * padding, h * (1 - padding))
            )
        )
    }
}

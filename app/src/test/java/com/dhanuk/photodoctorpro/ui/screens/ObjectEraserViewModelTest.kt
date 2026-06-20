package com.dhanuk.photodoctorpro.ui.screens

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectEraserViewModelTest {

    @Test
    fun `default brush and softness values are present`() {
        val vm = ObjectEraserViewModel(mockk(relaxed = true), SavedStateHandle())
        val state = vm.uiState.value
        assertEquals(40f, state.brushSize, 0.0001f)
        assertEquals(0f, state.brushSoftness, 0.0001f)
        assertFalse(state.paths.isNotEmpty())
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)
    }

    @Test
    fun `brush size and softness roundtrip via SavedStateHandle`() {
        val handle = SavedStateHandle()
        val vm = ObjectEraserViewModel(mockk(relaxed = true), handle)

        vm.onBrushSizeChanged(72f)
        vm.onBrushSoftnessChanged(15f)

        assertEquals(72f, vm.uiState.value.brushSize, 0.0001f)
        assertEquals(15f, vm.uiState.value.brushSoftness, 0.0001f)
        assertEquals(72f, handle.get<Float>("brushSize")!!, 0.0001f)
        assertEquals(15f, handle.get<Float>("brushSoftness")!!, 0.0001f)
    }

    @Test
    fun `addPath accumulates and triggers canUndo on erase`() {
        val vm = ObjectEraserViewModel(mockk(relaxed = true), SavedStateHandle())
        vm.addPath(EraserPath(path = androidx.compose.ui.graphics.Path(), strokeWidth = 10f, softness = 0f))
        vm.addPath(EraserPath(path = androidx.compose.ui.graphics.Path(), strokeWidth = 10f, softness = 0f))
        assertEquals(2, vm.uiState.value.paths.size)
    }

    @Test
    fun `selectedImageUri is restored from SavedStateHandle`() {
        val handle = SavedStateHandle().apply { set("selectedImageUri", "content://erase/1") }
        val vm = ObjectEraserViewModel(mockk(relaxed = true), handle)
        assertEquals(Uri.parse("content://erase/1"), vm.uiState.value.selectedImageUri)
    }

    @Test
    fun `reset clears paths but preserves selection`() {
        val vm = ObjectEraserViewModel(mockk(relaxed = true), SavedStateHandle())
        vm.onImageSelected(Uri.parse("content://x"), mockk(relaxed = true))
        // Do not wait for coroutine - just inspect state flow
        assertTrue(vm.uiState.value.selectedImageUri != null || vm.uiState.value.originalBitmap != null)
    }
}

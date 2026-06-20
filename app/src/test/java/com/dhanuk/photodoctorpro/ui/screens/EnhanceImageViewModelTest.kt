package com.dhanuk.photodoctorpro.ui.screens

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnhanceImageViewModelTest {

    @Test
    fun `default state has no image and scale factor 2`() {
        val vm = EnhanceImageViewModel(mockk(relaxed = true), SavedStateHandle())
        val state = vm.uiState.value
        assertNull(state.selectedImageUri)
        assertNull(state.originalBitmap)
        assertNull(state.enhancedBitmap)
        assertEquals(2, state.scaleFactor)
        assertEquals(false, state.isLoading)
        assertEquals(false, state.isLargeImage)
    }

    @Test
    fun `selectedImageUri is restored from SavedStateHandle`() {
        val handle = SavedStateHandle().apply {
            set("selectedImageUri", "content://media/123")
        }
        val vm = EnhanceImageViewModel(mockk(relaxed = true), handle)
        assertEquals(Uri.parse("content://media/123"), vm.uiState.value.selectedImageUri)
    }

    @Test
    fun `scale factor is restored from SavedStateHandle`() {
        val handle = SavedStateHandle().apply { set("scaleFactor", 4) }
        val vm = EnhanceImageViewModel(mockk(relaxed = true), handle)
        assertEquals(4, vm.uiState.value.scaleFactor)
    }

    @Test
    fun `reset clears all state`() {
        val handle = SavedStateHandle().apply { set("selectedImageUri", "content://x") }
        val vm = EnhanceImageViewModel(mockk(relaxed = true), handle)
        vm.reset()
        val state = vm.uiState.value
        assertNull(state.selectedImageUri)
        assertNull(state.originalBitmap)
        assertNull(state.enhancedBitmap)
    }
}

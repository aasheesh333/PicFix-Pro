package com.dhanuk.photodoctorpro.ui.screens

import androidx.lifecycle.SavedStateHandle
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageToPdfViewModelReorderTest {

    private fun viewModel(): ImageToPdfViewModel =
        ImageToPdfViewModel(mockk(relaxed = true), SavedStateHandle())

    @Test
    fun `reorder from lower to higher index adjusts target`() {
        val vm = viewModel()
        vm.onImagesSelected(listOf(uri("a"), uri("b"), uri("c"), uri("d")))
        vm.onImageReordered(from = 0, to = 3)
        assertEquals(
            listOf("b", "c", "d", "a"),
            vm.uiState.value.selectedImageUris.map { it.toString() }
        )
    }

    @Test
    fun `reorder from higher to lower index does not double-adjust`() {
        val vm = viewModel()
        vm.onImagesSelected(listOf(uri("a"), uri("b"), uri("c"), uri("d")))
        vm.onImageReordered(from = 2, to = 0)
        assertEquals(
            listOf("c", "a", "b", "d"),
            vm.uiState.value.selectedImageUris.map { it.toString() }
        )
    }

    @Test
    fun `reorder with same index is a no-op`() {
        val vm = viewModel()
        val original = listOf(uri("a"), uri("b"), uri("c"))
        vm.onImagesSelected(original)
        vm.onImageReordered(from = 1, to = 1)
        assertEquals(
            original.map { it.toString() },
            vm.uiState.value.selectedImageUris.map { it.toString() }
        )
    }

    @Test
    fun `reorder with out-of-bounds index is ignored`() {
        val vm = viewModel()
        val original = listOf(uri("a"), uri("b"))
        vm.onImagesSelected(original)
        vm.onImageReordered(from = 0, to = 99)
        assertEquals(
            original.map { it.toString() },
            vm.uiState.value.selectedImageUris.map { it.toString() }
        )
    }

    private fun uri(s: String): android.net.Uri = android.net.Uri.parse("content://test/$s")
}

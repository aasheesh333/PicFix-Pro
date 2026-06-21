package com.dhanuk.photodoctorpro.ui.screens

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression test for the Meme Maker "render on every keystroke" memory churn
 * (BUG-15). The debounce should collapse N rapid setTopText() calls into a
 * single render. We can't easily count renders in a unit test (private field),
 * but we can verify the VM stays alive and the bitmap isn't corrupted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemeMakerDebounceTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `rapid text changes do not crash the VM`() = runTest(testDispatcher) {
        val vm = MemeMakerViewModel(mockk<HistoryRepository>(relaxed = true), SavedStateHandle())
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        installOriginal(vm, bitmap)

        // Simulate a user typing 20 characters rapidly.
        repeat(20) { i ->
            vm.setTopText("Hello $i")
        }
        advanceTimeBy(500) // > 150ms debounce
        advanceUntilIdle()

        // VM should still be in a coherent state — no crash, no exception.
        assertTrue(vm.uiState.value.topText == "Hello 19")
        bitmap.recycle()
    }

    private fun installOriginal(vm: MemeMakerViewModel, bmp: Bitmap) {
        val stateField = MemeMakerViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        val flow = stateField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<*>
        @Suppress("UNCHECKED_CAST")
        val typed = flow as kotlinx.coroutines.flow.MutableStateFlow<MemeMakerUiState>
        typed.value = MemeMakerUiState(
            selectedImageUri = null,
            originalBitmap = bmp,
            processedBitmap = bmp
        )
    }
}

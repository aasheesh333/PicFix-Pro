package com.dhanuk.photodoctorpro.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.SavedStateHandle
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the Color Adjustments "trying to use a recycled bitmap"
 * crash (BUG-2). The bug was: `setOriginal()` set `processedBitmap = originalBitmap`
 * (same reference); the next `runAdjustment()` recycled the previous
 * `processedBitmap` (== original), then a subsequent runAdjustment would try to
 * `applyColorMatrix` on the now-recycled original.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ColorAdjustmentsViewModelRecycledBitmapTest {

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
    fun `original bitmap is not recycled after multiple adjustments`() = runTest(testDispatcher) {
        val vm = ColorAdjustmentsViewModel(mockk<HistoryRepository>(relaxed = true), SavedStateHandle())

        // Inject a 64x64 ARGB bitmap directly into the state.
        val original = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        original.eraseColor(Color.RED)
        // We can't call setOriginal without a real context, so we set state via reflection
        // of the public init flow: set originalBitmap AND processedBitmap = same ref.
        setOriginalAndProcessed(vm, original)
        assertSame(original, vm.uiState.value.originalBitmap)
        assertSame(original, vm.uiState.value.processedBitmap)

        // Simulate 5 brightness changes — each will trigger runAdjustment via the debounce.
        repeat(5) { i ->
            vm.updateBrightness(0.1f * (i + 1))
        }
        advanceUntilIdle()

        // The original must still be alive (not recycled).
        assertFalse(
            "originalBitmap was recycled by runAdjustment — BUG-2 regression!",
            original.isRecycled
        )
    }

    @Test
    fun `runAdjustment skips when original is recycled`() = runTest(testDispatcher) {
        val vm = ColorAdjustmentsViewModel(mockk<HistoryRepository>(relaxed = true), SavedStateHandle())
        val original = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        setOriginalAndProcessed(vm, original)
        original.recycle() // simulate the pre-fix bug
        vm.updateBrightness(0.3f)
        advanceUntilIdle()
        // The VM must not throw; it should bail out at the safety net.
        // We just assert no exception escaped.
    }

    /**
     * Use reflection to install a synthetic (original, processed) pair into the VM.
     * setOriginal() requires a real context, so we use the private state setter
     * via reflection to keep the test pure.
     */
    private fun setOriginalAndProcessed(vm: ColorAdjustmentsViewModel, bmp: Bitmap) {
        val stateField = ColorAdjustmentsViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        val flow = stateField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<*>
        @Suppress("UNCHECKED_CAST")
        val typed = flow as kotlinx.coroutines.flow.MutableStateFlow<ColorAdjustmentsUiState>
        typed.value = ColorAdjustmentsUiState(
            selectedImageUri = null,
            originalBitmap = bmp,
            processedBitmap = bmp
        )
    }
}

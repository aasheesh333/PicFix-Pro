package com.dhanuk.photodoctorpro.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.createSavedStateHandle
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository

class ViewModelFactory(
    private val repository: HistoryRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val handle: SavedStateHandle = extras.createSavedStateHandle()
        @Suppress("UNCHECKED_CAST")
        return when {
            modelClass.isAssignableFrom(HistoryViewModel::class.java) ->
                HistoryViewModel(repository, handle) as T
            modelClass.isAssignableFrom(RemoveBackgroundViewModel::class.java) ->
                RemoveBackgroundViewModel(repository, handle) as T
            modelClass.isAssignableFrom(ObjectEraserViewModel::class.java) ->
                ObjectEraserViewModel(repository, handle) as T
            modelClass.isAssignableFrom(EnhanceImageViewModel::class.java) ->
                EnhanceImageViewModel(repository, handle) as T
            modelClass.isAssignableFrom(ImageToPdfViewModel::class.java) ->
                ImageToPdfViewModel(repository, handle) as T
            modelClass.isAssignableFrom(ColorAdjustmentsViewModel::class.java) ->
                ColorAdjustmentsViewModel(repository, handle) as T
            modelClass.isAssignableFrom(ResizeCompressViewModel::class.java) ->
                ResizeCompressViewModel(repository, handle) as T
            modelClass.isAssignableFrom(PerspectiveCropViewModel::class.java) ->
                PerspectiveCropViewModel(repository, handle) as T
            modelClass.isAssignableFrom(ExifStripperViewModel::class.java) ->
                ExifStripperViewModel(repository, handle) as T
            modelClass.isAssignableFrom(MemeMakerViewModel::class.java) ->
                MemeMakerViewModel(repository, handle) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        throw IllegalArgumentException(
            "Use create(modelClass, extras) so SavedStateHandle can be provided."
        )
    }
}
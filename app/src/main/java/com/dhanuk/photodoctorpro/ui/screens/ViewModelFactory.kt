package com.dhanuk.photodoctorpro.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository

class ViewModelFactory(private val repository: HistoryRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HistoryViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(RemoveBackgroundViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RemoveBackgroundViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(ObjectEraserViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ObjectEraserViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(EnhanceImageViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return EnhanceImageViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(ImageToPdfViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ImageToPdfViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

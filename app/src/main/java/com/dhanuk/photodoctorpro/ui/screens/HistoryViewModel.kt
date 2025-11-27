package com.dhanuk.photodoctorpro.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val repository: HistoryRepository) : ViewModel() {

    val history = repository.getAllHistory()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }
}

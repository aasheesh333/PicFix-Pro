package com.dhanuk.photodoctorpro.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.utils.viewModelExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val repository: HistoryRepository,
    @Suppress("unused") private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    val history: StateFlow<List<com.dhanuk.photodoctorpro.data.local.History>> = repository.getAllHistory()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000L),
            initialValue = emptyList()
        )

    private val _isClearing = MutableStateFlow(false)
    val isClearing: StateFlow<Boolean> = _isClearing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearHistory() {
        if (_isClearing.value) return
        viewModelScope.launch(viewModelExceptionHandler("HistoryVM") + Dispatchers.IO) {
            _isClearing.value = true
            try {
                repository.clearHistory()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isClearing.value = false
            }
        }
    }

    fun deleteEntry(id: Int) {
        viewModelScope.launch(viewModelExceptionHandler("HistoryVM") + Dispatchers.IO) {
            try {
                repository.deleteHistory(id)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun onErrorShown() {
        _error.value = null
    }
}

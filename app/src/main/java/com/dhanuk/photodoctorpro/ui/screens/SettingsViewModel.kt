package com.dhanuk.photodoctorpro.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.utils.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _saveDirectory = MutableStateFlow<String?>(null)
    val saveDirectory = _saveDirectory.asStateFlow()

    init {
        loadSaveDirectory()
    }

    private fun loadSaveDirectory() {
        viewModelScope.launch {
            _saveDirectory.value = UserPreferences.getSaveDirectory(getApplication())
        }
    }

    fun setSaveDirectory(uri: Uri) {
        viewModelScope.launch {
            // Persist permission
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

            UserPreferences.setSaveDirectory(getApplication(), uri.toString())
            _saveDirectory.value = uri.toString()
        }
    }

    fun clearSaveDirectory() {
        viewModelScope.launch {
             UserPreferences.setSaveDirectory(getApplication(), null)
             _saveDirectory.value = null
        }
    }
}

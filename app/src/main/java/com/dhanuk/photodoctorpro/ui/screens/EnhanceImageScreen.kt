package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository

@Composable
fun EnhanceImageScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as Activity
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: EnhanceImageViewModel = viewModel(factory = ViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onImageSelected(it, context) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.enhance_image)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                 if (uiState.isEnhancing) {
                    CircularProgressIndicator()
                } else {
                    Crossfade(targetState = uiState.processedBitmap) { bitmap ->
                        if (bitmap != null) {
                            ImageView(bitmap = bitmap)
                        } else if (uiState.originalBitmap != null) {
                            ImageView(bitmap = uiState.originalBitmap)
                        } else {
                            Text(stringResource(R.string.select_an_image_to_enhance))
                        }
                    }
                }
            }

            if (uiState.showEnhanceSuggestion) {
                Text(stringResource(R.string.suggested_enhance_this_image), style = MaterialTheme.typography.bodyMedium)
            }

            if (uiState.originalBitmap == null) {
                Button(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.select_image))
                }
            } else if (uiState.processedBitmap == null) {
                Button(
                    onClick = { viewModel.enhanceImage(activity) },
                    enabled = !uiState.isEnhancing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.one_tap_enhance))
                }
            } else {
                Button(
                    onClick = { viewModel.saveImage(activity) },
                    enabled = !uiState.isEnhancing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.save_enhanced_image))
                }
            }

            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

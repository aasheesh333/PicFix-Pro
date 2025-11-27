package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.screens.ViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageToPdfScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as Activity
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: ImageToPdfViewModel = viewModel(factory = ViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        viewModel.onImagesSelected(uris)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.image_to_pdf)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                Text(stringResource(R.string.select_images))
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.selectedImageUris.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                ) {
                    items(uiState.selectedImageUris) { item ->
                        ImageRow(uri = item, modifier = Modifier.shadow(0.dp))
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.select_one_or_more_images_to_create_a_pdf))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.isCreating) {
                CircularProgressIndicator()
            } else {
                Button(
                    onClick = { viewModel.createPdf(activity) },
                    enabled = uiState.selectedImageUris.isNotEmpty()
                ) {
                    Text(stringResource(R.string.create_pdf))
                }
            }

            if (uiState.pdfCreationSuccess) {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }

            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun ImageRow(uri: Uri, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberAsyncImagePainter(uri),
                contentDescription = "Selected image thumbnail",
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(uri.lastPathSegment ?: "Image", modifier = Modifier.weight(1f))
            Icon(Icons.Default.Reorder, contentDescription = stringResource(R.string.drag_to_reorder))
        }
    }
}

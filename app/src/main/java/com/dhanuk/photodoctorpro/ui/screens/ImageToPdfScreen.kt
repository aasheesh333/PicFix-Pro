package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.components.AnimatedSnackbar
import com.dhanuk.photodoctorpro.ui.components.SnackbarType
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import com.dhanuk.photodoctorpro.ui.components.AnimatedLoadingIndicator
import com.dhanuk.photodoctorpro.utils.resolveFileUri
import com.dhanuk.photodoctorpro.utils.resolveWhatsAppPackage
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.findActivity
import com.dhanuk.photodoctorpro.ui.screens.ViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageToPdfScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository.getInstance(db.historyDao())
    val viewModel: ImageToPdfViewModel = viewModel(factory = ViewModelFactory.getInstance(repository))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showSaveSuccessDialog by remember { mutableStateOf<String?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var snackbarType by remember { mutableStateOf(SnackbarType.INFO) }

    // Multi-image picker:
    //   * On API 33+ (Android 13+): uses the system Photo Picker via
    //     PickMultipleVisualMedia — true multi-select with thumbnails.
    //   * On older Android: falls back to ACTION_OPEN_DOCUMENT with
    //     EXTRA_ALLOW_MULTIPLE so the system file picker returns a list.
    val multiImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.onImagesSelected(uris)
    }
    val fallbackMultiPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.onImagesSelected(uris)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(context.getString(R.string.error_prefix, it))
            viewModel.onErrorShown()
        }
    }

    LaunchedEffect(uiState.savedFilePath) {
        uiState.savedFilePath?.let { path ->
            (context as? android.app.Activity)?.let { AdManager.showInterstitialOnSave(it) }
            showSaveSuccessDialog = path
            viewModel.onSavedMessageShown()
        }
    }

    showSaveSuccessDialog?.let { path ->
        SaveSuccessDialog(
            filePath = path,
            onDismiss = { showSaveSuccessDialog = null },
            onShareWhatsApp = {
                try {
                    val uri = resolveFileUri(path, context)
                    val waPkg = resolveWhatsAppPackage(context)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri as android.os.Parcelable)
                        if (waPkg != null) setPackage(waPkg)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) { snackbarMessage = context.getString(R.string.whatsapp_not_installed); snackbarType = SnackbarType.ERROR }
            },
            onShareOther = {
                try {
                    val uri = resolveFileUri(path, context)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri as android.os.Parcelable)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
                } catch (e: Exception) { if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("ImageToPdfVM", "operation failed", e) }
            },
            onOpen = {
                try {
                    val uri = resolveFileUri(path, context)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) { snackbarMessage = context.getString(R.string.file_not_found); snackbarType = SnackbarType.ERROR }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.image_to_pdf)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back_button))
                    }
                },
                actions = {
                    if (uiState.selectedImageUris.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearAll() }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.clear))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(onClick = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    multiImagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                } else {
                    fallbackMultiPicker.launch(arrayOf("image/*"))
                }
            }) {
                Text(
                    if (uiState.selectedImageUris.isNotEmpty())
                        stringResource(R.string.action_add_images)
                    else
                        stringResource(R.string.select_images)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.selectedImageUris.isNotEmpty()) {
                Text(stringResource(R.string.reorder_instructions), style = MaterialTheme.typography.bodySmall)
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                ) {
                    itemsIndexed(uiState.selectedImageUris) { index, item ->
                        ImageRow(
                            uri = item,
                            modifier = Modifier.shadow(0.dp),
                            onMoveUp = if (index > 0) { { viewModel.onImageReordered(index, index - 1) } } else null,
                            onMoveDown = if (index < uiState.selectedImageUris.size - 1) { { viewModel.onImageReordered(index, index + 1) } } else null,
                            onRemove = { viewModel.onImageRemoved(index) }
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.select_one_or_more_images_to_create_a_pdf))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

        if (uiState.isCreating) {
            AnimatedLoadingIndicator(message = stringResource(R.string.generating))
        } else {
            Button(
                onClick = { viewModel.createPdf(activity) },
                enabled = uiState.selectedImageUris.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.create_pdf))
            }
        }

            AnimatedSnackbar(
                message = snackbarMessage ?: "",
                type = snackbarType,
                visible = snackbarMessage != null,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            if (snackbarMessage != null) {
                LaunchedEffect(snackbarMessage) {
                    kotlinx.coroutines.delay(3000)
                    snackbarMessage = null
                }
            }
        }
    }
}

@Composable
fun ImageRow(
    uri: Uri,
    modifier: Modifier = Modifier,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: (() -> Unit)? = null
) {
    val context = LocalContext.current
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
                contentDescription = stringResource(R.string.cd_image_thumbnail),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(uri.lastPathSegment ?: context.getString(R.string.image), modifier = Modifier.weight(1f))

            Column {
                IconButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.move_up))
                }
                IconButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.move_down))
                }
            }
            if (onRemove != null) {
                IconButton(onClick = { onRemove() }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.delete_entry), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

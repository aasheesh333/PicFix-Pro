package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import com.dhanuk.photodoctorpro.ui.components.rememberBitmap
import com.dhanuk.photodoctorpro.utils.findActivity
import com.dhanuk.photodoctorpro.utils.BitmapSaver
import com.dhanuk.photodoctorpro.ui.screens.ViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ExifStripperUiState(
    val selectedUri: Uri? = null,
    val previewBitmap: Bitmap? = null,
    val hasExif: Boolean = false,
    val exifSummary: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)

class ExifStripperViewModel(
    private val repository: com.dhanuk.photodoctorpro.data.repository.HistoryRepository,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        ExifStripperUiState(
            selectedUri = savedStateHandle.get<String>("exif_uri")?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            }
        )
    )
    val uiState: StateFlow<ExifStripperUiState> = _uiState.asStateFlow()

    fun onImageSelected(uri: Uri, context: android.content.Context) {
        savedStateHandle["exif_uri"] = uri.toString()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedUri = uri, error = null) }
            try {
                val (bitmap, exifInfo) = withContext(Dispatchers.IO) {
                    val cr = context.contentResolver
                    val bmp = com.dhanuk.photodoctorpro.utils.BitmapUtils.loadBitmapFromUri(uri, context, 3000)
                    val exif = try {
                        cr.openInputStream(uri)?.use { stream ->
                            val exifInterface = ExifInterface(stream)
                            val tags = mutableListOf<String>()
                            if (!exifInterface.getAttribute(ExifInterface.TAG_GPS_LATITUDE).isNullOrEmpty()) {
                                tags.add("GPS Location")
                            }
                            if (!exifInterface.getAttribute(ExifInterface.TAG_DATETIME).isNullOrEmpty()) {
                                tags.add("Date/Time: ${exifInterface.getAttribute(ExifInterface.TAG_DATETIME)}")
                            }
                            if (!exifInterface.getAttribute(ExifInterface.TAG_MAKE).isNullOrEmpty() ||
                                !exifInterface.getAttribute(ExifInterface.TAG_MODEL).isNullOrEmpty()) {
                                tags.add("Camera: ${exifInterface.getAttribute(ExifInterface.TAG_MAKE)} ${exifInterface.getAttribute(ExifInterface.TAG_MODEL)}")
                            }
                            tags.joinToString("\n")
                        } ?: ""
                    } catch (e: Exception) { "" }
                    Pair(bmp, exif)
                }
                val old = _uiState.value.previewBitmap
                _uiState.update {
                    it.copy(
                        previewBitmap = bitmap,
                        hasExif = exifInfo.isNotEmpty(),
                        exifSummary = exifInfo,
                        isLoading = false
                    )
                }
                if (old != null && old != bitmap && !old.isRecycled) old.recycle()
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("ExifStripperVM", "onImageSelected failed", e)
                }
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun saveCleanImage(context: android.content.Context) {
        val state = _uiState.value
        val bitmap = state.previewBitmap ?: return
        viewModelScope.launch {
            try {
                val savedPath = com.dhanuk.photodoctorpro.utils.UnifiedSaveHelper.saveAndRecordNoAd(
                    context = context,
                    bitmap = bitmap,
                    fileNamePrefix = "PDPro_Safe",
                    operationType = "EXIF Strip",
                    inputUriString = state.selectedUri?.toString() ?: "",
                    repository = repository,
                    format = android.graphics.Bitmap.CompressFormat.JPEG,
                )
                _uiState.update { it.copy(savedFilePath = savedPath) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun onErrorShown() { _uiState.update { it.copy(error = null) } }
    fun onSavedMessageShown() { _uiState.update { it.copy(savedFilePath = null) } }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.previewBitmap?.takeIf { !it.isRecycled }?.recycle()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExifStripperScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: ExifStripperViewModel = viewModel(factory = ViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveSuccessDialog by remember { mutableStateOf<String?>(null) }

    val previewImage = rememberBitmap(uiState.previewBitmap)

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onImageSelected(it, context) } }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar("Error: $it")
            viewModel.onErrorShown()
        }
    }

    LaunchedEffect(uiState.savedFilePath) {
        uiState.savedFilePath?.let { path ->
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
                    val file = File(path)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        setPackage("com.whatsapp")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
                }
            },
            onShareOther = {
                try {
                    val file = File(path)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share Image"))
                } catch (e: Exception) { if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("ExifStripperVM", "operation failed", e) }
            },
            onOpen = {
                try {
                    val file = File(path)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) { if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("ExifStripperVM", "operation failed", e) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_doctor)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back_button))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)
        ) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (previewImage != null) {
                    Image(
                        bitmap = previewImage,
                        contentDescription = stringResource(R.string.cd_image_preview),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.privacy_doctor_subtitle), style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.exif_metadata_header), color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                            Text(stringResource(R.string.pick_image))
                        }
                    }
                }
            }

            if (uiState.previewBitmap != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    if (uiState.hasExif) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.has_exif_metadata), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(uiState.exifSummary, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Text(
                                stringResource(R.string.no_exif_metadata),
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.new_image))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Button(
                            onClick = { viewModel.saveCleanImage(context) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = stringResource(R.string.cd_save_directory))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.strip_save))
                        }
                    }
                }
            }
        }
    }
}
package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
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

class ExifStripperViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ExifStripperUiState())
    val uiState: StateFlow<ExifStripperUiState> = _uiState.asStateFlow()

    fun onImageSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedUri = uri, error = null) }
            try {
                val context = appContext ?: return@launch
                val (bitmap, exifInfo) = withContext(Dispatchers.IO) {
                    val cr = context.contentResolver
                    val inputStream = cr.openInputStream(uri) ?: return@withContext Pair(null as Bitmap?, "")
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = false }
                    val bmp = BitmapFactory.decodeStream(inputStream)
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
                _uiState.update {
                    it.copy(
                        previewBitmap = bitmap,
                        hasExif = exifInfo.isNotEmpty(),
                        exifSummary = exifInfo,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    private var appContext: android.content.Context? = null
    fun setContext(context: android.content.Context) {
        appContext = context
    }

    fun saveCleanImage(context: android.content.Context) {
        val state = _uiState.value
        val bitmap = state.previewBitmap ?: return
        viewModelScope.launch {
            try {
                val savedPath = withContext(Dispatchers.IO) {
                    val file = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                        "PDPro_Safe_${System.currentTimeMillis()}.jpg"
                    )
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    // Strip EXIF by re-saving (already done by re-encoding to JPEG)
                    file.absolutePath
                }
                _uiState.update { it.copy(savedFilePath = savedPath) }

                try {
                    val db = AppDatabase.getDatabase(context)
                    db.historyDao().insert(
                        History(
                            operationType = "EXIF Strip",
                            inputFilePath = state.selectedUri?.toString() ?: "",
                            filePath = savedPath,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                } catch (_: Exception) {}
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun onErrorShown() { _uiState.update { it.copy(error = null) } }
    fun onSavedMessageShown() { _uiState.update { it.copy(savedFilePath = null) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExifStripperScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as Activity
    val viewModel: ExifStripperViewModel = viewModel()
    LaunchedEffect(Unit) { viewModel.setContext(context) }
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveSuccessDialog by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onImageSelected(it) } }

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
                } catch (e: Exception) { e.printStackTrace() }
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
                } catch (e: Exception) { e.printStackTrace() }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Doctor") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                if (uiState.previewBitmap != null) {
                    Image(
                        bitmap = uiState.previewBitmap!!.asImageBitmap(),
                        contentDescription = "Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Strip hidden metadata from your photos", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Remove GPS, camera info, and timestamps", color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                            Text("Pick Image to Scan")
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
                                Text("⚠️ Hidden data found:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
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
                                "✓ No hidden metadata found. Safe to share!",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                            Text("New Image")
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Button(
                            onClick = { viewModel.saveCleanImage(context) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Clean")
                        }
                    }
                }
            }
        }
    }
}
package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import com.dhanuk.photodoctorpro.ui.components.rememberBitmap
import com.dhanuk.photodoctorpro.utils.findActivity
import com.dhanuk.photodoctorpro.utils.BitmapSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class MemeMakerUiState(
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val topText: String = "TOP TEXT",
    val bottomText: String = "BOTTOM TEXT",
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)

class MemeMakerViewModel(private val repository: com.dhanuk.photodoctorpro.data.repository.HistoryRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(MemeMakerUiState())
    val uiState: StateFlow<MemeMakerUiState> = _uiState.asStateFlow()

    fun onImageSelected(uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    com.dhanuk.photodoctorpro.utils.BitmapUtils.loadBitmapFromUri(uri, context, 3000)
                }
                if (bitmap != null) {
                    val argbBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val oldOriginal = _uiState.value.originalBitmap
                    val oldProcessed = _uiState.value.processedBitmap
                    _uiState.update {
                        it.copy(
                            originalBitmap = argbBitmap,
                            processedBitmap = null,
                            isLoading = false
                        )
                    }
                    if (oldOriginal != null && oldOriginal != argbBitmap && !oldOriginal.isRecycled) oldOriginal.recycle()
                    if (oldProcessed != null && !oldProcessed.isRecycled) oldProcessed.recycle()
                    if (argbBitmap != bitmap && !bitmap.isRecycled) bitmap.recycle()
                } else {
                    _uiState.update { it.copy(error = "Failed to load image", isLoading = false) }
                }
            } catch (e: Exception) {
                if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) {
                    android.util.Log.e("MemeMakerVM", "onImageSelected failed", e)
                }
                _uiState.update { it.copy(error = e.message ?: "Load failed", isLoading = false) }
            }
        }
    }

    fun setTopText(text: String) {
        _uiState.update { it.copy(topText = text) }
        renderMeme()
    }

    fun setBottomText(text: String) {
        _uiState.update { it.copy(bottomText = text) }
        renderMeme()
    }

    fun renderMeme() {
        val state = _uiState.value
        val original = state.originalBitmap ?: return
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            val result = drawMeme(original, state.topText, state.bottomText)
            if (!isActive) {
                if (result != original && !result.isRecycled) result.recycle()
                return@launch
            }
            withContext(Dispatchers.Main) {
                val old = _uiState.value.processedBitmap
                _uiState.update { it.copy(processedBitmap = result) }
                if (old != null && old != result && !old.isRecycled) old.recycle()
            }
        }
    }

    private var renderJob: kotlinx.coroutines.Job? = null

    private fun drawMeme(bitmap: Bitmap, topText: String, bottomText: String): Bitmap {
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("Failed to allocate bitmap for meme render")
        val canvas = Canvas(mutable)
        val w = mutable.width.toFloat()
        val h = mutable.height.toFloat()

        // Meme text size scales with image width
        val textSize = (w / 12f).coerceIn(40f, 200f)
        val strokeWidth = textSize / 12f

        val paint = Paint().apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val strokePaint = Paint().apply {
            color = Color.BLACK
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }

        if (topText.isNotBlank()) {
            val y = h * 0.10f
            canvas.drawText(topText.uppercase(), w / 2f, y, strokePaint)
            canvas.drawText(topText.uppercase(), w / 2f, y, paint)
        }

        if (bottomText.isNotBlank()) {
            val y = h * 0.93f
            canvas.drawText(bottomText.uppercase(), w / 2f, y, strokePaint)
            canvas.drawText(bottomText.uppercase(), w / 2f, y, paint)
        }

        return mutable
    }

    fun saveImage(context: android.content.Context) {
        val bitmap = _uiState.value.processedBitmap ?: return
        viewModelScope.launch {
            try {
                val savedPath = com.dhanuk.photodoctorpro.utils.UnifiedSaveHelper.saveAndRecordNoAd(
                    context = context,
                    bitmap = bitmap,
                    fileNamePrefix = "PDPro_Meme",
                    operationType = "Meme",
                    inputUriString = "",
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
        renderJob?.cancel()
        renderJob = null
        _uiState.value.originalBitmap?.takeIf { !it.isRecycled }?.recycle()
        _uiState.value.processedBitmap?.takeIf { !it.isRecycled }?.recycle()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemeMakerScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: MemeMakerViewModel = viewModel(factory = ViewModelFactory(repository))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveSuccessDialog by remember { mutableStateOf<String?>(null) }

    val originalImage = rememberBitmap(uiState.originalBitmap)
    val processedImage = rememberBitmap(uiState.processedBitmap)

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
                    context.startActivity(Intent.createChooser(intent, "Share Meme"))
                } catch (e: Exception) { if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("MemeMakerVM", "operation failed", e) }
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
                } catch (e: Exception) { if (com.dhanuk.photodoctorpro.BuildConfig.DEBUG) android.util.Log.e("MemeMakerVM", "operation failed", e) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meme Maker") },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (processedImage != null) {
                    Image(
                        bitmap = processedImage,
                        contentDescription = "Meme Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Create viral memes in seconds", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                            Text("Pick Image")
                        }
                    }
                }
            }

            if (uiState.originalBitmap != null) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    OutlinedTextField(
                        value = uiState.topText,
                        onValueChange = { viewModel.setTopText(it) },
                        label = { Text("Top Text") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uiState.bottomText,
                        onValueChange = { viewModel.setBottomText(it) },
                        label = { Text("Bottom Text") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f)
                        ) { Text("New Image") }
                        Spacer(modifier = Modifier.width(16.dp))
                        Button(
                            onClick = { viewModel.saveImage(context) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Save")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}
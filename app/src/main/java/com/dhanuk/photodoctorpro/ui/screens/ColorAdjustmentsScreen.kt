package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.components.BeforeAfterSlider
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import com.dhanuk.photodoctorpro.utils.BitmapUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ColorAdjustmentsUiState(
    val originalBitmap: Bitmap? = null,
    val processedBitmap: Bitmap? = null,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
    val isLoading: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)

class ColorAdjustmentsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ColorAdjustmentsUiState())
    val uiState: StateFlow<ColorAdjustmentsUiState> = _uiState.asStateFlow()

    fun setOriginal(uri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                    }
                }
                _uiState.update {
                    it.copy(
                        originalBitmap = bitmap,
                        processedBitmap = bitmap,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun updateBrightness(value: Float) {
        _uiState.update { it.copy(brightness = value) }
        applyAdjustments()
    }

    fun updateContrast(value: Float) {
        _uiState.update { it.copy(contrast = value) }
        applyAdjustments()
    }

    fun updateSaturation(value: Float) {
        _uiState.update { it.copy(saturation = value) }
        applyAdjustments()
    }

    fun updateWarmth(value: Float) {
        _uiState.update { it.copy(warmth = value) }
        applyAdjustments()
    }

    fun reset() {
        _uiState.update {
            it.copy(
                brightness = 0f,
                contrast = 1f,
                saturation = 1f,
                warmth = 0f
            )
        }
        applyAdjustments()
    }

    private fun applyAdjustments() {
        val state = _uiState.value
        val original = state.originalBitmap ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val output = withContext(Dispatchers.Default) {
                applyColorMatrix(original, state.brightness, state.contrast, state.saturation, state.warmth)
            }
            _uiState.update { it.copy(processedBitmap = output) }
        }
    }

    fun onErrorShown() {
        _uiState.update { it.copy(error = null) }
    }

    fun onSavedMessageShown() {
        _uiState.update { it.copy(savedFilePath = null) }
    }

    fun saveImage(activity: Activity, context: Context) {
        val state = _uiState.value
        val bitmap = state.processedBitmap ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val savedPath = withContext(Dispatchers.IO) {
                    val fileName = "PhotoDoctorPro_Color_${System.currentTimeMillis()}"
                    BitmapUtils.saveBitmap(context, bitmap, fileName, Bitmap.CompressFormat.PNG)
                }
                _uiState.update { it.copy(savedFilePath = savedPath) }

                try {
                    val db = AppDatabase.getDatabase(context)
                    val historyEntry = com.dhanuk.photodoctorpro.data.local.History(
                        operationType = "Color Adjustments",
                        inputFilePath = "memory",
                        filePath = savedPath,
                        timestamp = System.currentTimeMillis()
                    )
                    db.historyDao().insert(historyEntry)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    companion object {
        fun applyColorMatrix(
            source: Bitmap,
            brightness: Float,
            contrast: Float,
            saturation: Float,
            warmth: Float
        ): Bitmap {
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint().apply {
                isAntiAlias = true
                val cm = ColorMatrix()
                cm.setSaturation(saturation)
                val contrastMatrix = ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, (brightness * 128),
                        0f, contrast, 0f, 0f, (brightness * 128),
                        0f, 0f, contrast, 0f, (brightness * 128),
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                cm.postConcat(contrastMatrix)
                // Warmth: shift R up, B down for warm; opposite for cool
                val warmthValue = warmth * 30
                val warmthMatrix = ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, warmthValue,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f, 0f, -warmthValue,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                cm.postConcat(warmthMatrix)
                colorFilter = ColorMatrixColorFilter(cm)
            }
            canvas.drawBitmap(source, 0f, 0f, paint)
            return output
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorAdjustmentsScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as Activity
    val viewModel: ColorAdjustmentsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveSuccessDialog by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setOriginal(it, context) }
    }

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
                        type = "image/png"
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
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share Image"))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
                title = { Text("Color Adjustments") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.reset() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset")
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
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.originalBitmap != null && uiState.processedBitmap != null) {
                    BeforeAfterSlider(
                        beforeImage = uiState.originalBitmap!!.asImageBitmap(),
                        afterImage = uiState.processedBitmap!!.asImageBitmap(),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Select an image to start", color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                            Text("Pick Image")
                        }
                    }
                }
            }

            if (uiState.originalBitmap != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    AdjustmentSlider(
                        label = "Brightness",
                        value = uiState.brightness,
                        valueRange = -0.5f..0.5f,
                        onValueChange = { viewModel.updateBrightness(it) }
                    )
                    AdjustmentSlider(
                        label = "Contrast",
                        value = uiState.contrast,
                        valueRange = 0.5f..1.5f,
                        onValueChange = { viewModel.updateContrast(it) }
                    )
                    AdjustmentSlider(
                        label = "Saturation",
                        value = uiState.saturation,
                        valueRange = 0f..2f,
                        onValueChange = { viewModel.updateSaturation(it) }
                    )
                    AdjustmentSlider(
                        label = "Warmth",
                        value = uiState.warmth,
                        valueRange = -1f..1f,
                        onValueChange = { viewModel.updateWarmth(it) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f)
                        ) { Text("New Image") }
                        Spacer(modifier = Modifier.width(16.dp))
                        Button(
                            onClick = {
                                val state = viewModel.uiState.value
                                if (state.processedBitmap != null) {
                                    viewModel.saveImage(activity, context)
                                }
                            },
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

@Composable
fun AdjustmentSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            Text(String.format("%.2f", value), color = MaterialTheme.colorScheme.secondary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
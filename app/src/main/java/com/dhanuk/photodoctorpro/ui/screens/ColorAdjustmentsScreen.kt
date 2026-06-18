package com.dhanuk.photodoctorpro.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.utils.BitmapSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ColorAdjustmentsUiState(
    val selectedImageUri: Uri? = null,
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
        _uiState.value = _uiState.value.copy(selectedImageUri = uri, isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                _uiState.update {
                    it.copy(
                        selectedImageUri = uri,
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
            val output = applyColorMatrix(original, state.brightness, state.contrast, state.saturation, state.warmth)
            _uiState.update { it.copy(processedBitmap = output) }
        }
    }

    fun onErrorShown() { _uiState.update { it.copy(error = null) } }
    fun onSavedMessageShown() { _uiState.update { it.copy(savedFilePath = null) } }

    fun saveImage(context: Context) {
        val state = _uiState.value
        val bitmap = state.processedBitmap ?: return
        viewModelScope.launch {
            try {
                val savedPath = BitmapSaver.save(
                    context = context,
                    bitmap = bitmap,
                    baseName = "PhotoDoctorPro_Color_${System.currentTimeMillis()}",
                    subdir = "PhotoDoctorPro",
                    format = Bitmap.CompressFormat.PNG,
                    quality = 100
                )
                _uiState.update { it.copy(savedFilePath = savedPath) }

                try {
                    val db = AppDatabase.getDatabase(context)
                    db.historyDao().insert(
                        History(
                            operationType = "Color Adjustments",
                            inputFilePath = state.selectedImageUri?.toString() ?: "",
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.runtime.ExperimentalMaterial3Api::class)
@Composable
fun ColorAdjustmentsScreen(navController: NavController) {
    val context = LocalContext.current
    val viewModel: ColorAdjustmentsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
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
                try { context.startActivity(createShareIntent(path, context, "com.whatsapp")) }
                catch (_: Exception) { Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show() }
            },
            onShareOther = {
                try { context.startActivity(Intent.createChooser(createShareIntent(path, context), "Share Image")) }
                catch (_: Exception) { }
            },
            onOpen = {
                try { context.startActivity(createOpenIntent(path, context)) }
                catch (_: Exception) { }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.color_adjustments),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.originalBitmap != null) {
                        IconButton(onClick = { viewModel.reset() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Reset")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .luminaGlass(
                        shape = RoundedCornerShape(24.dp),
                        cornerRadius = 24.dp,
                        alpha = 0.06f,
                        borderAlpha = 0.10f
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    uiState.processedBitmap != null -> {
                        Image(
                            bitmap = uiState.processedBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    uiState.selectedImageUri != null -> {
                        AsyncImage(
                            model = uiState.selectedImageUri,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    else -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.select_an_image_to_start),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(stringResource(R.string.select_image))
                            }
                        }
                    }
                }
            }

            if (uiState.originalBitmap != null) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    AdjustmentSlider(
                        label = stringResource(R.string.brightness),
                        value = uiState.brightness,
                        valueRange = -0.5f..0.5f,
                        onValueChange = { viewModel.updateBrightness(it) }
                    )
                    AdjustmentSlider(
                        label = stringResource(R.string.contrast),
                        value = uiState.contrast,
                        valueRange = 0.5f..1.5f,
                        onValueChange = { viewModel.updateContrast(it) }
                    )
                    AdjustmentSlider(
                        label = stringResource(R.string.saturation),
                        value = uiState.saturation,
                        valueRange = 0f..2f,
                        onValueChange = { viewModel.updateSaturation(it) }
                    )
                    AdjustmentSlider(
                        label = stringResource(R.string.warmth),
                        value = uiState.warmth,
                        valueRange = -1f..1f,
                        onValueChange = { viewModel.updateWarmth(it) }
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(stringResource(R.string.new_image)) }
                        Button(
                            onClick = {
                                if (uiState.processedBitmap != null) {
                                    scope.launch { viewModel.saveImage(context) }
                                }
                            },
                            enabled = uiState.processedBitmap != null && !uiState.isLoading,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.save), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun AdjustmentSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                String.format("%+.2f", value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                thumbColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

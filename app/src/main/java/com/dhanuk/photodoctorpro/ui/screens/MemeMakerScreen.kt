package com.dhanuk.photodoctorpro.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
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
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import com.dhanuk.photodoctorpro.utils.BitmapSaver
import kotlinx.coroutines.Dispatchers
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

class MemeMakerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MemeMakerUiState())
    val uiState: StateFlow<MemeMakerUiState> = _uiState.asStateFlow()

    private var appContext: android.content.Context? = null
    fun setContext(context: android.content.Context) { appContext = context }

    fun onImageSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val context = appContext ?: return@launch
                val bitmap = withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    BitmapFactory.decodeStream(inputStream)
                }
                if (bitmap != null) {
                    _uiState.update {
                        it.copy(
                            originalBitmap = bitmap,
                            processedBitmap = bitmap,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
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
        viewModelScope.launch(Dispatchers.Default) {
            val result = withContext(Dispatchers.Default) {
                drawMeme(original, state.topText, state.bottomText)
            }
            _uiState.update { it.copy(processedBitmap = result) }
        }
    }

    private fun drawMeme(bitmap: Bitmap, topText: String, bottomText: String): Bitmap {
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
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
                val savedPath = BitmapSaver.save(
                    context = context,
                    bitmap = bitmap,
                    baseName = "PDPro_Meme_${System.currentTimeMillis()}",
                    subdir = "PhotoDoctorPro",
                    format = Bitmap.CompressFormat.JPEG,
                    quality = 95
                )
                _uiState.update { it.copy(savedFilePath = savedPath) }
                try {
                    val db = AppDatabase.getDatabase(context)
                    db.historyDao().insert(
                        History(
                            operationType = "Meme",
                            inputFilePath = "",
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
fun MemeMakerScreen(navController: NavController) {
    val context = LocalContext.current
    val activity = context as Activity
    val viewModel: MemeMakerViewModel = viewModel()
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
                    context.startActivity(Intent.createChooser(intent, "Share Meme"))
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
                if (uiState.processedBitmap != null) {
                    Image(
                        bitmap = uiState.processedBitmap!!.asImageBitmap(),
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
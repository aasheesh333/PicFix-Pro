package com.dhanuk.photodoctorpro.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.components.BeforeAfterSlider
import com.dhanuk.photodoctorpro.ui.components.AnimatedLoadingIndicator
import com.dhanuk.photodoctorpro.ui.components.AnimatedSnackbar
import com.dhanuk.photodoctorpro.ui.components.SnackbarType
import com.dhanuk.photodoctorpro.ui.components.SaveSuccessDialog
import com.dhanuk.photodoctorpro.ui.components.luminaGlass
import com.dhanuk.photodoctorpro.ui.components.rememberBitmap
import com.dhanuk.photodoctorpro.utils.AdManager
import com.dhanuk.photodoctorpro.utils.createOpenIntent
import com.dhanuk.photodoctorpro.utils.createShareIntent
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResizeCompressScreen(navController: NavController) {
    val context = LocalContext.current
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository.getInstance(db.historyDao())
    val viewModel: ResizeCompressViewModel = viewModel(factory = ViewModelFactory.getInstance(repository))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveSuccessDialog by remember { mutableStateOf<String?>(null) }
    var compareMode by remember { mutableStateOf(false) }
    var showCustomPanel by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var snackbarType by remember { mutableStateOf(SnackbarType.INFO) }

    val originalImage = rememberBitmap(uiState.originalBitmap)
    val processedImage = rememberBitmap(uiState.processedBitmap)

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { viewModel.onImageSelected(it, context) } }

    LaunchedEffect(Unit) {
        viewModel.restoreIfNeeded(context)
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
                try { context.startActivity(createShareIntent(path, context, "com.whatsapp")) }
                catch (_: Exception) { snackbarMessage = context.getString(R.string.whatsapp_not_installed); snackbarType = SnackbarType.ERROR }
            },
            onShareOther = {
                try { context.startActivity(Intent.createChooser(createShareIntent(path, context), context.getString(R.string.share))) }
                catch (_: Exception) { }
            },
            onOpen = {
                try { context.startActivity(createOpenIntent(path, context)) }
                catch (_: Exception) { }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.resize_compress),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.cd_back_button))
                    }
                },
                actions = {
                    if (uiState.originalBitmap != null && uiState.processedBitmap != null) {
                        IconButton(onClick = { compareMode = !compareMode }) {
                            Icon(
                                Icons.Outlined.Compare,
                                contentDescription = stringResource(R.string.compare_with_original),
                                tint = if (compareMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (uiState.originalBitmap != null) {
                        IconButton(onClick = { imagePickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.new_image))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.originalBitmap == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .luminaGlass(
                            shape = RoundedCornerShape(24.dp),
                            cornerRadius = 24.dp,
                            alpha = 0.06f,
                            borderAlpha = 0.10f
                        )
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Compress,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.resize_compress_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { imagePickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(stringResource(R.string.pick_image))
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .luminaGlass(
                            shape = RoundedCornerShape(20.dp),
                            cornerRadius = 20.dp,
                            alpha = 0.06f,
                            borderAlpha = 0.10f
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.isProcessing) {
                        AnimatedLoadingIndicator(message = stringResource(R.string.saving))
                    } else if (compareMode && originalImage != null && processedImage != null) {
                        BeforeAfterSlider(
                            beforeImage = originalImage,
                            afterImage = processedImage,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        )
                    } else {
                        when {
                            processedImage != null -> {
                                Image(
                                    bitmap = processedImage,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            uiState.selectedUri != null -> {
                                AsyncImage(
                                    model = uiState.selectedUri,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    val ob = uiState.originalBitmap
                    if (ob != null) {
                        SizeSummaryRow(
                            originalBytes = uiState.originalSizeBytes,
                            processedBytes = uiState.processedSizeBytes,
                            originalW = ob.width,
                            originalH = ob.height,
                            processedW = uiState.processedBitmap?.width ?: ob.width,
                            processedH = uiState.processedBitmap?.height ?: ob.height
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ResizePreset.values().forEach { preset ->
                            PresetChip(
                                label = preset.label,
                                selected = uiState.preset == preset,
                                onClick = {
                                    viewModel.onPresetSelected(preset)
                                    showCustomPanel = preset == ResizePreset.CUSTOM
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (showCustomPanel && uiState.preset == ResizePreset.CUSTOM) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = uiState.customWidthText,
                                onValueChange = { viewModel.onCustomWidthChanged(it) },
                                label = { Text(stringResource(R.string.width_label), style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(10.dp),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            IconButton(
                                onClick = { viewModel.onMaintainAspectRatioChanged(!uiState.maintainAspectRatio) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    if (uiState.maintainAspectRatio) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                    contentDescription = stringResource(R.string.cd_maintain_aspect_ratio),
                                    tint = if (uiState.maintainAspectRatio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            OutlinedTextField(
                                value = uiState.customHeightText,
                                onValueChange = { viewModel.onCustomHeightChanged(it) },
                                label = { Text(stringResource(R.string.height_label), style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(10.dp),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.quality),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "${(uiState.quality * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = uiState.quality,
                        onValueChange = { viewModel.onQualityChanged(it) },
                        valueRange = 0.4f..1.0f,
                        modifier = Modifier.fillMaxWidth().height(24.dp)
                    )

                    Spacer(Modifier.height(6.dp))

                    Button(
                        onClick = { viewModel.saveImage(context) },
                        enabled = !uiState.isProcessing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(
                            stringResource(R.string.save_compressed),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }

                    Spacer(Modifier.height(12.dp))
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
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .height(36.dp)
            .luminaGlass(
                shape = RoundedCornerShape(10.dp),
                cornerRadius = 10.dp,
                alpha = if (selected) 0f else 0.04f,
                borderAlpha = if (selected) 0f else 0.10f
            )
            .background(if (selected) container else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = content,
            maxLines = 1
        )
    }
}

@Composable
private fun SizeSummaryRow(
    originalBytes: Long,
    processedBytes: Long,
    originalW: Int,
    originalH: Int,
    processedW: Int,
    processedH: Int
) {
    val pct = if (originalBytes > 0L) {
        val saved = originalBytes - processedBytes
        val ratio = saved.toDouble() / originalBytes.toDouble()
        "${(ratio * 100).coerceIn(-999.0, 100.0).toInt()}%"
    } else "—"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                        stringResource(R.string.original),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "$originalW x $originalH",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                stringResource(R.string.saved_pct, pct),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                stringResource(R.string.optimized),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "$processedW x $processedH • ${formatBytes(processedBytes)}",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private val BYTE_FORMAT = DecimalFormat("#,##0.#")

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return "${BYTE_FORMAT.format(value)} ${units[unitIndex]}"
}

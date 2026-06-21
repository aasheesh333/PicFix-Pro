package com.dhanuk.photodoctorpro.ui.screens

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.utils.BitmapUtils
import com.dhanuk.photodoctorpro.utils.resolveFileUri
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(navController: NavController) {
    val context = LocalContext.current
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: HistoryViewModel = viewModel(factory = ViewModelFactory(repository))
    val historyItems = viewModel.history.collectAsState().value
    val isClearing by viewModel.isClearing.collectAsState()
    val errorMessage by viewModel.error.collectAsState()
    var selectedItem by remember { mutableStateOf<History?>(null) }
    var pendingDelete by remember { mutableStateOf<History?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val fileMissingMsg = stringResource(R.string.file_not_found)

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(context.getString(R.string.error_prefix, it))
            viewModel.onErrorShown()
        }
    }

    selectedItem?.let { item ->
        val isContentUri = item.filePath.startsWith("content://")
                    val displayName = if (isContentUri) context.getString(R.string.gallery_selected_folder) else File(item.filePath).name
        val exists = isContentUri || File(item.filePath).exists()

        AlertDialog(
            onDismissRequest = { selectedItem = null },
            title = { Text(item.operationType) },
            text = {
                Column {
                    Text(stringResource(R.string.saved_to, displayName))
                    if (!exists) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.file_missing),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!exists) {
                            scope.launch { snackbarHostState.showSnackbar(fileMissingMsg) }
                            selectedItem = null
                            return@Button
                        }
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            val mimeType = if (item.filePath.endsWith(".pdf")) "application/pdf" else "image/*"
                            val uri = resolveFileUri(item.filePath, context)
                            setDataAndType(uri, mimeType)
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            scope.launch { snackbarHostState.showSnackbar(fileMissingMsg) }
                        }
                        selectedItem = null
                    },
                    enabled = exists
                ) {
                    Text(stringResource(R.string.open))
                }
            },
            dismissButton = {
                Row {
                    if (exists) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    val mimeType = if (item.filePath.endsWith(".pdf")) "application/pdf" else "image/*"
                                    val uri = resolveFileUri(item.filePath, context)
                                    type = mimeType
                                    putExtra(Intent.EXTRA_STREAM, uri as android.os.Parcelable)
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                }
                                try {
                                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
                                } catch (e: Exception) {
                                    scope.launch { snackbarHostState.showSnackbar(fileMissingMsg) }
                                }
                                selectedItem = null
                            }
                        ) {
                            Text(stringResource(R.string.share))
                        }
                    }
                    TextButton(onClick = {
                        pendingDelete = item
                        selectedItem = null
                    }) {
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_entry)) },
            text = { Text(stringResource(R.string.delete_entry_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEntry(item.id)
                    pendingDelete = null
                }) {
                    Text(
                        stringResource(R.string.action_remove),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history)) },
                actions = {
                    if (historyItems.isNotEmpty()) {
                        Button(
                            onClick = { viewModel.clearHistory() },
                            enabled = !isClearing,
                            modifier = Modifier.semantics {
                                contentDescription = context.getString(R.string.cd_clear_history)
                            }
                        ) {
                            Text(stringResource(R.string.clear))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (historyItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_history))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(historyItems, key = { it.id }) { item ->
                    val isContentUri = item.filePath.startsWith("content://")
        val displayName = if (isContentUri) context.getString(R.string.gallery_selected_folder) else File(item.filePath).name
                    val exists = isContentUri || File(item.filePath).exists()
                    val cardDescription = "${item.operationType}, $displayName, ${if (exists) context.getString(R.string.available) else context.getString(R.string.file_missing)}"
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .combinedClickable(
                                onClick = { selectedItem = item },
                                onLongClick = { pendingDelete = item }
                            )
                            .semantics { contentDescription = cardDescription }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.operationType,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!exists) {
                                    Icon(
                                        Icons.Outlined.Warning,
                                        contentDescription = stringResource(R.string.file_missing),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.timestamp)),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.dhanuk.photodoctorpro.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(navController: NavController) {
    val context = LocalContext.current
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: HistoryViewModel = viewModel(factory = ViewModelFactory(repository))
    val historyItems = viewModel.history.collectAsState().value
    var selectedItem by remember { mutableStateOf<History?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    if (selectedItem != null) {
        val item = selectedItem!!
        AlertDialog(
            onDismissRequest = { selectedItem = null },
            title = { Text(item.operationType) },
            text = {
                Column {
                     Text(stringResource(R.string.saved_to, item.filePath))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = File(item.filePath)
                        if (file.exists()) {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                val mimeType = if (item.filePath.endsWith(".pdf")) "application/pdf" else "image/*"
                                setDataAndType(uri, mimeType)
                                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Handle no app found
                            }
                        }
                        selectedItem = null
                    }
                ) {
                    Text(stringResource(R.string.open))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                         val file = File(item.filePath)
                        if (file.exists()) {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                val mimeType = if (item.filePath.endsWith(".pdf")) "application/pdf" else "image/*"
                                type = mimeType
                                putExtra(Intent.EXTRA_STREAM, uri)
                                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            }
                             context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
                        }
                        selectedItem = null
                    }
                ) {
                    Text(stringResource(R.string.share))
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
                        Button(onClick = { viewModel.clearHistory() }) {
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
                items(historyItems) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable { selectedItem = item }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(item.operationType, style = MaterialTheme.typography.titleMedium)
                            Text(File(item.filePath).name, style = MaterialTheme.typography.bodyMedium)
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

package com.dhanuk.photodoctorpro.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.data.local.AppDatabase
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import com.dhanuk.photodoctorpro.ui.components.BannerAd

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = AppDatabase.getDatabase(context)
    val repository = HistoryRepository(db.historyDao())
    val viewModel: HistoryViewModel = viewModel(factory = ViewModelFactory(repository))
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.clear_history)) },
            text = { Text(stringResource(R.string.are_you_sure_you_want_to_clear_all_history)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearHistory()
                        showDialog = false
                    }
                ) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                Button(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.app_info), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.app_name))
            Text(stringResource(R.string.version_1_0))
            Text(stringResource(R.string.developer_dhanuk_software))

            Spacer(modifier = Modifier.height(32.dp))

            Text(stringResource(R.string.clear_history), style = MaterialTheme.typography.titleLarge)
            Button(onClick = { showDialog = true }) {
                Text(stringResource(R.string.clear_all_history))
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(stringResource(R.string.legal), style = MaterialTheme.typography.titleLarge)
            Button(onClick = { navController.navigate("privacy_policy") }) {
                Text(stringResource(R.string.privacy_policy))
            }
            Button(onClick = { navController.navigate("terms_and_conditions") }) {
                Text(stringResource(R.string.terms_conditions))
            }

            Spacer(modifier = Modifier.weight(1f))
            BannerAd()
        }
    }
}

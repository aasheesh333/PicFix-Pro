package com.dhanuk.photodoctorpro.ui.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.launch

sealed class BottomNavItem(val route: String, val icon: ImageVector, val label: String) {
    object Home : BottomNavItem("home", Icons.Default.Home, "Home")
    object History : BottomNavItem("history", Icons.Default.History, "History")
    object Settings : BottomNavItem("settings", Icons.Default.Settings, "Settings")
}

@Composable
fun BottomNavigationBar(navController: NavController) {
    val items = listOf(
        BottomNavItem.Home,
        BottomNavItem.History,
        BottomNavItem.Settings
    )

    val globalState = LocalGlobalNavigationState.current
    var pendingRoute by remember { mutableStateOf<String?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                showDialog = false
                pendingRoute = null
            },
            title = { Text("Unsaved Changes") },
            text = { Text("You have unsaved changes. What would you like to do?") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val success = globalState.onSave?.invoke() ?: false
                        if (success) {
                            showDialog = false
                            globalState.clear()
                            pendingRoute?.let { route ->
                                navigateToFresh(navController, route)
                            }
                            pendingRoute = null
                        }
                    }
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        // DISCARD
                        showDialog = false
                        globalState.clear()
                        globalState.onDiscard?.invoke()
                        pendingRoute?.let { route ->
                            navigateToFresh(navController, route)
                        }
                        pendingRoute = null
                    }) {
                        Text("Discard")
                    }
                    TextButton(onClick = {
                        // CANCEL
                        showDialog = false
                        pendingRoute = null
                    }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        items.forEach { item ->
            NavigationBarItem(
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                selected = currentRoute == item.route,
                onClick = {
                    if (globalState.hasUnsavedChanges) {
                        pendingRoute = item.route
                        showDialog = true
                    } else {
                        navigateToFresh(navController, item.route)
                    }
                }
            )
        }
    }
}

fun navigateToFresh(navController: NavController, route: String) {
    navController.navigate(route) {
        // Clear everything to start fresh
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

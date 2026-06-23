package com.dhanuk.photodoctorpro.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dhanuk.photodoctorpro.R
import com.dhanuk.photodoctorpro.ui.components.luminaGlass
import kotlinx.coroutines.launch

sealed class BottomNavItem(val route: String, val iconSelected: ImageVector, val iconUnselected: ImageVector, val label: String) {
    object Home : BottomNavItem("home", Icons.Rounded.Home, Icons.Rounded.Home, "Home")
    object History : BottomNavItem("history", Icons.Rounded.History, Icons.Outlined.History, "History")
    object Settings : BottomNavItem("settings", Icons.Rounded.Settings, Icons.Outlined.Settings, "Settings")
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
            title = { Text(stringResource(R.string.unsaved_warning_title)) },
            text = { Text(stringResource(R.string.unsaved_warning_body)) },
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
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showDialog = false
                        globalState.clear()
                        globalState.onDiscard?.invoke()
                        pendingRoute?.let { route ->
                            navigateToFresh(navController, route)
                        }
                        pendingRoute = null
                    }) {
                        Text(stringResource(R.string.discard))
                    }
                    TextButton(onClick = {
                        showDialog = false
                        pendingRoute = null
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val navBarInset = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(bottom = navBarInset.calculateBottomPadding())
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .luminaGlass(
                    shape = RoundedCornerShape(32.dp),
                    cornerRadius = 32.dp,
                    alpha = 0.06f,
                    borderAlpha = 0.10f
                )
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val selected = currentRoute == item.route
                NavPill(
                    icon = if (selected) item.iconSelected else item.iconUnselected,
                    label = item.label,
                    selected = selected,
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
}

@Composable
private fun NavPill(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        if (selected) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor
            )
        }
    }
}

fun navigateToFresh(navController: NavController, route: String) {
    navController.navigate(route) {
        popUpTo("home") { inclusive = route == "home"; saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

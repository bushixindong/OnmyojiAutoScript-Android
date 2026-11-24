// OASX primary_navigation_shell.dart adapted for native Android: Home, Screen, Settings.
package com.oas.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.oas.android.R

@Composable
fun AppBottomNavigation(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        NavigationBarItem(selected = selected == 0, onClick = { onSelect(0) }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("主页") })
        NavigationBarItem(selected = selected == 1, onClick = { onSelect(1) }, icon = { Icon(painterResource(R.drawable.ic_oasx_devices), null) }, label = { Text("画面") })
        NavigationBarItem(selected = selected == 2, onClick = { onSelect(2) }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
    }
}

@Composable
fun AppNavigationRail(selected: Int, onSelect: (Int) -> Unit) {
    NavigationRail(modifier = Modifier.width(80.dp).fillMaxHeight(), containerColor = MaterialTheme.colorScheme.background) {
        Spacer(Modifier.height(12.dp))
        NavigationRailItem(selected = selected == 0, onClick = { onSelect(0) }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("主页") })
        Spacer(Modifier.height(12.dp))
        NavigationRailItem(selected = selected == 1, onClick = { onSelect(1) }, icon = { Icon(painterResource(R.drawable.ic_oasx_devices), null) }, label = { Text("画面") })
        Spacer(Modifier.height(12.dp))
        NavigationRailItem(selected = selected == 2, onClick = { onSelect(2) }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
    }
}

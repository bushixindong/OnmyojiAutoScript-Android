// OASX primary_navigation_shell.dart & appbar.dart adapted for Android.
package com.oas.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.oas.android.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OasAppScreen(controller: OasController) {
    var destination by rememberSaveable { mutableIntStateOf(0) }
    var showWorkspace by rememberSaveable { mutableStateOf(false) }
    var homeTab by rememberSaveable { mutableIntStateOf(0) }
    var workspaceTask by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deviceSheet by rememberSaveable { mutableStateOf(false) }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }

    OasTheme(controller.appearance) {
        val dark = controller.appearance == "dark" || controller.appearance == "system" && isSystemInDarkTheme()
        SideEffect { controller.updateSystemBars(dark) }
        val lifecycle = (androidx.activity.compose.LocalActivity.current as androidx.activity.ComponentActivity).lifecycle
        LaunchedEffect(controller) {
            var tick = 0
            while(true) {
                controller.sync()
                // The periodic task-list refresh enters Python (config load + JSON) and contends
                // with the running engine for the GIL; only do it while the UI is actually visible.
                if(controller.active && tick % 4 == 0 &&
                    lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) controller.refresh()
                tick++
                delay(500)
            }
        }
        BackHandler(enabled = editing != null || workspaceTask != null || destination != 0) {
            when {
                editing != null -> editing = null
                workspaceTask != null -> workspaceTask = null
                else -> destination = 0
            }
        }
        fun editStandalone(id: String) { controller.loadEditor(id); editing = id }
        fun editInWorkspace(id: String) {
            if(id == "GlobalGame" || id == "Script") editStandalone(id)
            else { controller.loadEditor(id); workspaceTask = id }
        }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if(editing != null) {
                TaskEditor(controller, editing!!, onBack = { editing = null })
            } else BoxWithConstraints(Modifier.fillMaxSize()) {
                // Match OASX primary_navigation_shell breakpoint: 80 + 170 + 16 + 360 = 626dp.
                val wide = maxWidth >= 626.dp
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(painterResource(R.drawable.oasx_logo), "OASA", Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)))
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        when(destination) {
                                            0 -> "OASA / 主页"
                                            1 -> "OASA / 画面"
                                            else -> "OASA / 设置"
                                        },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                        )
                    },
                    bottomBar = { if(!wide) AppBottomNavigation(destination) { destination = it } }
                ) { padding ->
                    Row(Modifier.fillMaxSize().padding(padding)) {
                        if(wide) {
                            AppNavigationRail(destination) { destination = it }
                            VerticalDivider()
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            when(destination) {
                                0 -> HomeWorkbench(
                                    controller = controller,
                                    wide = wide,
                                    showWorkspace = showWorkspace,
                                    onShowWorkspace = { showWorkspace = it },
                                    tab = homeTab,
                                    onTab = { homeTab = it; workspaceTask = null },
                                    activeTask = workspaceTask,
                                    onCloseTask = { workspaceTask = null },
                                    onDevice = { deviceSheet = true },
                                    onEdit = ::editInWorkspace
                                )
                                1 -> ScreenDestination(controller, onDevice = { deviceSheet = true })
                                else -> SettingsScreen(
                                    controller,
                                    onDevice = { deviceSheet = true },
                                    onEdit = ::editStandalone,
                                    onInfo = { title, content -> info = title to content }
                                )
                            }
                        }
                    }
                }
            }
            if(deviceSheet) DeviceSheet(controller) { deviceSheet = false }
            info?.let { (title, content) ->
                AlertDialog(
                    onDismissRequest = { info = null },
                    title = { Text(title) },
                    text = { SelectionContainer { LazyColumn { item { Text(content, style = MaterialTheme.typography.bodyMedium) } } } },
                    confirmButton = { TextButton(onClick = { info = null }) { Text("关闭") } }
                )
            }
        }
    }
}

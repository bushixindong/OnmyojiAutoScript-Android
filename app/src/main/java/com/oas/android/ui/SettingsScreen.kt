// OASX settings_view.dart, setting_card.dart & setting_item.dart adapted for native Android.
package com.oas.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oas.android.BuildConfig
import com.oas.android.RuntimeUpdater

@Composable
fun SettingsScreen(controller: OasController, onDevice: () -> Unit, onEdit: (String) -> Unit, onInfo: (String, String) -> Unit) {
    var showUpdateDialog by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            OasSettingCard("设备与环境") {
                SettingItemRow(
                    title = "设备与游戏",
                    subtitle = if(controller.mode) "前台兼容模式" else "后台虚拟屏模式",
                    trailing = {
                        Text(
                            controller.permissionText(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if(controller.connected) OasDesignTokens.StatusColors.RunningAccent else OasDesignTokens.StatusColors.AbnormalAccent
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    onClick = onDevice
                )
                SettingItemRow(
                    title = "游戏包名",
                    subtitle = controller.gamePackage,
                    trailing = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    onClick = onDevice
                )
            }
        }
        item {
            OasSettingCard("OAS 设置") {
                SettingItemRow(
                    title = "游戏设置",
                    subtitle = "庭院皮肤、战斗、邀请与全局选项",
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { onEdit("GlobalGame") }
                )
                SettingItemRow(
                    title = "脚本与调度",
                    subtitle = "优先级、作息与任务间隔",
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { onEdit("Script") }
                )
                SettingItemRow(
                    title = "最近运行结果",
                    subtitle = "查看任务状态与结束原因",
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { onInfo("运行结果", resultText(controller.lastResult, controller.labels)) }
                )
                SettingItemRow(
                    title = "识别与高级工具",
                    subtitle = "截图、触控、OCR、原生副本与诊断",
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = controller::openTools
                )
            }
        }
        item {
            OasSettingCard("外观与系统") {
                Text("外观主题", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.padding(top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                        FilterChip(
                            selected = controller.appearance == value,
                            onClick = { controller.changeAppearance(value) },
                            label = { Text(label) }
                        )
                    }
                }
                SettingItemRow(
                    title = "检查更新",
                    subtitle = "脚本热更新与版本检查 · ${controller.localRuntimeVersion}",
                    trailing = {
                        if(controller.remoteUpdate?.let { it.hasRuntimeUpdate || it.hasAppUpdate } == true) {
                            Text("有新版", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    onClick = { showUpdateDialog = true }
                )
                SettingItemRow(
                    title = "当前版本",
                    subtitle = "Onmyoji AutoScript Android",
                    trailing = {
                        Text("OASA ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    },
                    onClick = null
                )
            }
        }
        item { SystemSettings(controller) }
    }

    if(showUpdateDialog) {
        UpdateDialog(controller = controller, onDismiss = { showUpdateDialog = false })
    }
}

@Composable
private fun UpdateDialog(controller: OasController, onDismiss: () -> Unit) {
    var showSourceConfig by remember { mutableStateOf(false) }
    var manifestInput by remember(controller.updateManifestUrl) { mutableStateOf(controller.updateManifestUrl) }
    var mirrorInput by remember(controller.updateMirrorPrefix) { mutableStateOf(controller.updateMirrorPrefix) }
    val info = controller.remoteUpdate

    AlertDialog(
        onDismissRequest = { if(!controller.updateBusy) onDismiss() },
        title = { Text("脚本与版本更新") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("客户端版本：OASA ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text("当前脚本包：${controller.localRuntimeVersion}", style = MaterialTheme.typography.bodyMedium)
                if(controller.updateStatus.isNotBlank()) {
                    Text(
                        controller.updateStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if(controller.updateNeedsRestart || info?.hasRuntimeUpdate == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if(controller.updateBusy && controller.updateProgress > 0f) {
                    LinearProgressIndicator(progress = { controller.updateProgress }, modifier = Modifier.fillMaxWidth())
                }
                if(info != null && info.runtimeNotes.isNotBlank()) {
                    Text("脚本更新说明：${info.runtimeNotes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(info != null && info.hasAppUpdate) {
                    OutlinedButton(
                        onClick = { controller.openExternalUrl(info.appDownloadUrl) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("前往下载新版 APK (${info.appVersion})") }
                }
                TextButton(
                    onClick = { showSourceConfig = !showSourceConfig },
                    contentPadding = PaddingValues(0.dp)
                ) { Text(if(showSourceConfig) "收起更新源设置" else "配置更新源 / 镜像加速") }
                if(showSourceConfig) {
                    Text(
                        "默认优先使用 GitHub 官方源；若连接超时或下载过慢，将自动切换至 ${RuntimeUpdater.FALLBACK_SITE_BASE} 备用加速源。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = manifestInput,
                        onValueChange = { manifestInput = it },
                        label = { Text("update.json 地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = mirrorInput,
                        onValueChange = { mirrorInput = it },
                        label = { Text("GitHub 镜像前缀（可选，如 https://ghfast.top/）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(
                        onClick = { controller.saveUpdateSource(manifestInput, mirrorInput) },
                        enabled = !controller.updateBusy
                    ) { Text("保存更新源") }
                }
            }
        },
        confirmButton = {
            when {
                controller.updateNeedsRestart -> Button(onClick = controller::restartApp) { Text("立即重启生效") }
                info?.hasRuntimeUpdate == true -> Button(
                    onClick = controller::applyRuntimeUpdate,
                    enabled = !controller.updateBusy && !controller.active
                ) { Text("更新脚本") }
                else -> Button(
                    onClick = controller::checkUpdates,
                    enabled = !controller.updateBusy
                ) { Text("检查更新") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !controller.updateBusy) { Text("关闭") }
        }
    )
}

@Composable
fun OasSettingCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OasCard {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun SettingItemRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit,
    onClick: (() -> Unit)?
) {
    val modifier = if(onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()
    Row(modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if(!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(controller: OasController, onDismiss: () -> Unit) {
    var packageName by remember { mutableStateOf(controller.gamePackage) }
    var primary by remember { mutableStateOf(controller.mode) }
    var dpi by remember { mutableIntStateOf(controller.dpi) }
    val games = remember { controller.installedGames() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("设备与游戏", style = MaterialTheme.typography.headlineSmall)
            InfoRow("Shizuku", controller.permissionText(), controller.connected)
            if(!controller.connected) {
                val hint = when(controller.shizukuStage) {
                    ShizukuStage.NOT_INSTALLED -> "请先安装并启动 Shizuku。"
                    ShizukuStage.NOT_RUNNING -> "请先启动 Shizuku 服务。"
                    ShizukuStage.NEED_AUTH -> "请授权 OASA 访问 Shizuku。"
                    ShizukuStage.READY -> controller.message.ifBlank { "正在连接设备服务…" }
                    ShizukuStage.CONNECTED -> ""
                }
                if(hint.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(hint, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when(controller.shizukuStage) {
                    ShizukuStage.NOT_INSTALLED, ShizukuStage.NOT_RUNNING -> {
                        Button(onClick = controller::openShizuku, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                            Text(if(controller.shizukuStage == ShizukuStage.NOT_INSTALLED) "下载并安装 Shizuku" else "打开 Shizuku 启动服务")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { controller.refreshShizuku(autoConnect = true) }, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small) { Text("重新检测") }
                            OutlinedButton(onClick = controller::connect, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small) { Text("尝试连接") }
                        }
                    }
                    ShizukuStage.NEED_AUTH -> Button(onClick = controller::connect, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text("授权并连接 Shizuku") }
                    ShizukuStage.READY -> Button(onClick = controller::connect, enabled = !controller.shizukuBinding, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                        Text(if(controller.shizukuBinding) "正在连接设备服务…" else "连接设备服务")
                    }
                    ShizukuStage.CONNECTED -> Unit
                }
            }
            SectionTitle("运行模式")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { primary = false }) {
                RadioButton(selected = !primary, onClick = { primary = false })
                Column {
                    Text("后台虚拟屏", style = MaterialTheme.typography.bodyLarge)
                    Text("真机推荐 · 游戏在独立屏幕运行", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { primary = true }) {
                RadioButton(selected = primary, onClick = { primary = true })
                Column {
                    Text("前台兼容", style = MaterialTheme.typography.bodyLarge)
                    Text("模拟器推荐 · 游戏画面需为 1280 × 720", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(!primary) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(240, 160).forEach { value -> FilterChip(selected = dpi == value, onClick = { dpi = value }, label = { Text("$value DPI") }) }
            }
            SectionTitle("阴阳师版本")
            if(games.size > 1) games.forEach { (pkg, title) ->
                Row(Modifier.fillMaxWidth().clickable { packageName = pkg }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = packageName == pkg, onClick = { packageName = pkg })
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedTextField(
                value = packageName,
                onValueChange = { packageName = it.trim() },
                label = { Text("游戏应用包名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.small
            )
            Button(
                onClick = { if(controller.saveDevice(packageName, primary, dpi)) { controller.prepare(); onDismiss() } },
                enabled = controller.connected && !controller.active && !controller.busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.small
            ) { Text(if(primary) "保存并准备前台模式" else "保存并打开虚拟屏幕") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if(controller.saveDevice(packageName, primary, dpi)) onDismiss() }) { Text("仅保存配置") }
                TextButton(onClick = { controller.release(); onDismiss() }, enabled = controller.connected && !controller.active) { Text("释放运行屏幕") }
            }
        }
    }
}

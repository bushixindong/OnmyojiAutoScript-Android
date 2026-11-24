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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oas.android.BuildConfig

@Composable
fun SettingsScreen(controller: OasController, onDevice: () -> Unit, onEdit: (String) -> Unit, onInfo: (String, String) -> Unit) {
    val context = LocalContext.current
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
                    title = "开源声明",
                    subtitle = "MaaMeow、OAS 与第三方组件",
                    trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {
                        onInfo("开源声明", context.assets.open("THIRD_PARTY.txt").bufferedReader().use { it.readText() })
                    }
                )
                SettingItemRow(
                    title = "当前版本",
                    subtitle = "沿用 OASX 主题与工作台设计 · 百鬼夜行暂缓",
                    trailing = {
                        Text("OASA ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    },
                    onClick = null
                )
            }
        }
        item { SystemSettings(controller) }
    }
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
                    ShizukuStage.NOT_INSTALLED -> "尚未安装 Shizuku。请先下载安装 Shizuku 并通过无线调试或电脑 ADB 启动，返回本页将自动识别。"
                    ShizukuStage.NOT_RUNNING -> "Shizuku 已安装但未启动。点击下方打开 Shizuku 启动服务（Android 11+ 可用无线调试），切回 OASA 将自动连接。"
                    ShizukuStage.NEED_AUTH -> "Shizuku 服务已运行，请授权 OASA 访问设备服务。"
                    ShizukuStage.READY -> controller.message.ifBlank { "已获得 Shizuku 授权，正在准备连接设备服务。" }
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
            Text("仅创建独立运行屏幕，不调整手机主屏分辨率。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = { if(controller.saveDevice(packageName, primary, dpi)) { controller.prepare(); onDismiss() } },
                enabled = controller.connected && !controller.active && !controller.busy,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.small
            ) { Text("保存并打开游戏") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if(controller.saveDevice(packageName, primary, dpi)) onDismiss() }) { Text("仅保存配置") }
                TextButton(onClick = { controller.release(); onDismiss() }, enabled = controller.connected && !controller.active) { Text("释放运行屏幕") }
            }
        }
    }
}

// OASX config_collection_panel.dart, active_config_panel.dart & log_center_panel.dart adapted for native Android.
package com.oas.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.oas.android.R
import org.json.JSONObject

private enum class ScriptStateFilter(val label: String) {
    ALL("全部"), RUNNING("运行中"), STOPPED("已停止"), ABNORMAL("异常")
}

@Composable
fun HomeWorkbench(
    controller: OasController,
    wide: Boolean,
    showWorkspace: Boolean,
    onShowWorkspace: (Boolean) -> Unit,
    tab: Int,
    onTab: (Int) -> Unit,
    activeTask: String?,
    onCloseTask: () -> Unit,
    onDevice: () -> Unit,
    onEdit: (String) -> Unit
) {
    BackHandler(enabled = !wide && showWorkspace && activeTask == null) { onShowWorkspace(false) }

    Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if(wide) {
            Box(Modifier.width(240.dp).fillMaxHeight()) {
                ConfigCollectionPanel(
                    controller = controller,
                    activeHighlight = true,
                    onOpenWorkspace = { onShowWorkspace(true) },
                    onDevice = onDevice,
                    onEdit = onEdit
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                ActiveConfigPanel(
                    controller = controller,
                    tab = tab,
                    onTab = onTab,
                    activeTask = activeTask,
                    onCloseTask = onCloseTask,
                    onBackToScripts = null,
                    onDevice = onDevice,
                    onEdit = onEdit
                )
            }
        } else if(!showWorkspace) {
            ConfigCollectionPanel(
                controller = controller,
                activeHighlight = false,
                onOpenWorkspace = { onShowWorkspace(true) },
                onDevice = onDevice,
                onEdit = onEdit
            )
        } else {
            ActiveConfigPanel(
                controller = controller,
                tab = tab,
                onTab = onTab,
                activeTask = activeTask,
                onCloseTask = onCloseTask,
                onBackToScripts = { onCloseTask(); onShowWorkspace(false) },
                onDevice = onDevice,
                onEdit = onEdit
            )
        }
    }
}

@Composable
private fun ConfigCollectionPanel(
    controller: OasController,
    activeHighlight: Boolean,
    onOpenWorkspace: () -> Unit,
    onDevice: () -> Unit,
    onEdit: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ScriptStateFilter.ALL) }
    var filterMenu by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var renamingTarget by remember { mutableStateOf<String?>(null) }
    var deletingTarget by remember { mutableStateOf<String?>(null) }

    fun presetState(preset: PresetItem): ScriptStateFilter = when {
        preset.running -> ScriptStateFilter.RUNNING
        preset.active && !controller.connected -> ScriptStateFilter.ABNORMAL
        else -> ScriptStateFilter.STOPPED
    }

    val allPresets = controller.presets.ifEmpty {
        listOf(PresetItem(controller.activePreset, active = true, running = controller.active, previewType = "none", previewId = "", previewTitle = "", previewTime = ""))
    }
    val runningCount = allPresets.count { presetState(it) == ScriptStateFilter.RUNNING }
    val abnormalCount = allPresets.count { presetState(it) == ScriptStateFilter.ABNORMAL }
    val stoppedCount = allPresets.count { presetState(it) == ScriptStateFilter.STOPPED }
    val visiblePresets = allPresets.filter { preset ->
        (filter == ScriptStateFilter.ALL || presetState(preset) == filter) &&
            (query.isBlank() || preset.name.contains(query.trim(), ignoreCase = true))
    }

    OasCard(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val sepColor = MaterialTheme.colorScheme.outline
                Text(
                    buildAnnotatedString {
                        append("脚本列表 ")
                        withStyle(SpanStyle(color = OasDesignTokens.StatusColors.RunningAccent, fontWeight = FontWeight.SemiBold)) { append(runningCount.toString()) }
                        withStyle(SpanStyle(color = sepColor, fontWeight = FontWeight.SemiBold)) { append("/") }
                        withStyle(SpanStyle(color = sepColor, fontWeight = FontWeight.SemiBold)) { append(stoppedCount.toString()) }
                        withStyle(SpanStyle(color = sepColor, fontWeight = FontWeight.SemiBold)) { append("/") }
                        withStyle(SpanStyle(color = OasDesignTokens.StatusColors.AbnormalAccent, fontWeight = FontWeight.SemiBold)) { append(abnormalCount.toString()) }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = controller::refresh, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, "刷新任务", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDevice, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_oasx_devices), "设备与游戏",
                        modifier = Modifier.size(20.dp),
                        tint = if(controller.connected && controller.environmentReady) MaterialTheme.colorScheme.primary else OasDesignTokens.StatusColors.AbnormalAccent
                    )
                }
                IconButton(
                    onClick = { showAddDialog = true },
                    enabled = !controller.active && !controller.presetBusy,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Add, "新增脚本预设", modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("搜索脚本", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = if(query.isNotEmpty()) {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清除搜索") } }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Box {
                    IconButton(onClick = { filterMenu = true }) {
                        Icon(
                            painterResource(R.drawable.ic_oasx_filter_list),
                            "筛选状态",
                            tint = if(filter != ScriptStateFilter.ALL) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        ScriptStateFilter.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.label,
                                        fontWeight = if(option == filter) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if(option == filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = { filter = option; filterMenu = false }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if(visiblePresets.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("没有匹配的脚本", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(visiblePresets, key = { it.name }) { preset ->
                        var tileMenu by remember(preset.name) { mutableStateOf(false) }
                        val state = presetState(preset)
                        val accentColor = when(state) {
                            ScriptStateFilter.RUNNING -> OasDesignTokens.StatusColors.RunningAccent
                            ScriptStateFilter.ABNORMAL -> OasDesignTokens.StatusColors.AbnormalAccent
                            else -> MaterialTheme.colorScheme.outline
                        }
                        val isSelected = preset.name == controller.activePreset
                        val tileBg = if(activeHighlight && isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLowest
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            color = tileBg,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Min)
                                    .clickable { controller.selectPreset(preset.name, onOpenWorkspace) }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .width(6.dp)
                                        .fillMaxHeight(0.8f)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(accentColor)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(preset.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.height(6.dp))
                                    when(preset.previewType) {
                                        "running" -> TaskPreviewRow(R.drawable.ic_oasx_flash_on, OasDesignTokens.StatusColors.Running, preset.previewTitle)
                                        "pending" -> TaskPreviewRow(R.drawable.ic_oasx_layers, OasDesignTokens.StatusColors.Pending, preset.previewTitle)
                                        "waiting" -> {
                                            val time = formatOasxClockOnly(preset.previewTime)
                                            val text = if(time.isNotBlank()) "${preset.previewTitle} $time" else preset.previewTitle
                                            TaskPreviewRow(R.drawable.ic_oasx_schedule, OasDesignTokens.StatusColors.Waiting, text)
                                        }
                                        else -> Text("没有任务", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        if(preset.running) controller.stop()
                                        else controller.selectPreset(preset.name) { toggleScriptPower(controller, onDevice) }
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_oasx_power),
                                        if(preset.running) "停止脚本" else "启动脚本",
                                        modifier = Modifier.size(22.dp),
                                        tint = if(preset.running) OasDesignTokens.StatusColors.Running else LocalContentColor.current
                                    )
                                }
                                Box {
                                    IconButton(onClick = { tileMenu = true }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.MoreVert, "配置操作", modifier = Modifier.size(18.dp))
                                    }
                                    DropdownMenu(expanded = tileMenu, onDismissRequest = { tileMenu = false }) {
                                        DropdownMenuItem(text = { Text("进入工作台") }, onClick = { tileMenu = false; controller.selectPreset(preset.name, onOpenWorkspace) })
                                        DropdownMenuItem(text = { Text("重命名") }, enabled = !controller.active, onClick = { tileMenu = false; renamingTarget = preset.name })
                                        DropdownMenuItem(text = { Text("导出配置") }, onClick = { tileMenu = false; controller.exportPreset(preset.name) })
                                        DropdownMenuItem(text = { Text("删除") }, enabled = !controller.active && allPresets.size > 1, onClick = { tileMenu = false; deletingTarget = preset.name })
                                        HorizontalDivider()
                                        DropdownMenuItem(text = { Text("设备与游戏") }, onClick = { tileMenu = false; onDevice() })
                                        DropdownMenuItem(text = { Text("游戏设置") }, onClick = { tileMenu = false; controller.selectPreset(preset.name) { onEdit("GlobalGame") } })
                                        DropdownMenuItem(text = { Text("脚本与调度") }, onClick = { tileMenu = false; controller.selectPreset(preset.name) { onEdit("Script") } })
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            if(!controller.connected || !controller.environmentReady) {
                Spacer(Modifier.height(10.dp))
                DeviceReadinessCard(controller, onDevice)
            }
        }
    }

    if(showAddDialog) {
        AddPresetDialog(
            suggestedName = controller.suggestPresetName(),
            existingPresets = allPresets.map { it.name },
            onPickJson = { cb -> controller.onPickJsonFile?.invoke(cb) },
            onDismiss = { showAddDialog = false },
            onConfirm = { name, source, importJson ->
                showAddDialog = false
                controller.createPreset(name, source, importJson) { onOpenWorkspace() }
            }
        )
    }
    renamingTarget?.let { oldName ->
        RenamePresetDialog(
            oldName = oldName,
            onDismiss = { renamingTarget = null },
            onConfirm = { newName ->
                renamingTarget = null
                controller.renamePreset(oldName, newName)
            }
        )
    }
    deletingTarget?.let { targetName ->
        AlertDialog(
            onDismissRequest = { deletingTarget = null },
            title = { Text("删除") },
            text = { Text("确定要删除预设 \"$targetName\" 吗？") },
            confirmButton = {
                Button(onClick = { deletingTarget = null; controller.deletePreset(targetName) }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { deletingTarget = null }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPresetDialog(
    suggestedName: String,
    existingPresets: List<String>,
    onPickJson: ((String, String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (name: String, source: String?, importJson: String?) -> Unit
) {
    var name by remember { mutableStateOf(suggestedName) }
    val templateChoices = remember(existingPresets) { listOf("__template__" to "默认模板 (template)") + existingPresets.map { it to it } }
    var selectedSource by remember { mutableStateOf("__template__") }
    var expandedDropdown by remember { mutableStateOf(false) }
    var pickedFileName by remember { mutableStateOf<String?>(null) }
    var importedJson by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增配置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("新名称", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("从已有配置复制", style = MaterialTheme.typography.labelLarge)
                ExposedDropdownMenuBox(
                    expanded = expandedDropdown,
                    onExpandedChange = { expandedDropdown = !expandedDropdown }
                ) {
                    val label = templateChoices.firstOrNull { it.first == selectedSource }?.second ?: selectedSource
                    OutlinedTextField(
                        value = label,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        enabled = importedJson.isBlank(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = expandedDropdown, onDismissRequest = { expandedDropdown = false }) {
                        templateChoices.forEach { (key, title) ->
                            DropdownMenuItem(
                                text = { Text(title) },
                                onClick = { selectedSource = key; expandedDropdown = false }
                            )
                        }
                    }
                }
                Text("导入 JSON（可选）", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(
                    onClick = {
                        onPickJson { fileName, content ->
                            pickedFileName = fileName
                            importedJson = content
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_oasx_download), null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(pickedFileName ?: "选择配置文件 (.json)")
                }
                OutlinedTextField(
                    value = importedJson,
                    onValueChange = { importedJson = it },
                    placeholder = { Text("或直接粘贴完整配置 JSON") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = name.trim()
                    if(clean.isNotEmpty()) {
                        onConfirm(clean, selectedSource, importedJson.takeIf { it.isNotBlank() })
                    }
                },
                enabled = name.trim().isNotEmpty()
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RenamePresetDialog(
    oldName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(oldName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("新名称") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { val clean = name.trim(); if(clean.isNotEmpty() && clean != oldName) onConfirm(clean) },
                enabled = name.trim().isNotEmpty() && name.trim() != oldName
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun TaskPreviewRow(iconRes: Int, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(iconRes), null, modifier = Modifier.size(14.dp), tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveConfigPanel(
    controller: OasController,
    tab: Int,
    onTab: (Int) -> Unit,
    activeTask: String?,
    onCloseTask: () -> Unit,
    onBackToScripts: (() -> Unit)?,
    onDevice: () -> Unit,
    onEdit: (String) -> Unit
) {
    val hasBulkTasks = controller.tasks.any { it.enabled && it.runnable && it.schedulable }
    val bulkIdle = controller.bulkMode == "none"

    OasCard(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if(onBackToScripts != null) {
                    IconButton(onClick = onBackToScripts, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "脚本列表")
                    }
                }
                Text(controller.activePreset, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                BulkQuickButton(
                    iconRes = R.drawable.ic_oasx_flash_on,
                    tooltip = "全部立即运行",
                    loading = controller.bulkMode == "runNow",
                    enabled = bulkIdle && hasBulkTasks && !controller.active,
                    onClick = { controller.bulkQuickSchedule(true) }
                )
                BulkQuickButton(
                    iconRes = R.drawable.ic_oasx_schedule,
                    tooltip = "全部置为等待",
                    loading = controller.bulkMode == "waitNow",
                    enabled = bulkIdle && hasBulkTasks && !controller.active,
                    onClick = { controller.bulkQuickSchedule(false) }
                )
                Spacer(Modifier.width(6.dp))
                ConfigStateBadge(controller, onDevice)
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { toggleScriptPower(controller, onDevice) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_oasx_power),
                        if(controller.active) "停止脚本" else "启动脚本",
                        tint = if(controller.active) OasDesignTokens.StatusColors.Running else LocalContentColor.current
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("总览", "任务", "日志", "画面").forEachIndexed { index, label ->
                    FilterChip(
                        selected = tab == index,
                        onClick = { onTab(index) },
                        label = { Text(label) },
                        leadingIcon = null
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if(activeTask != null) {
                    OasxTaskParameterPanel(
                        controller = controller,
                        id = activeTask,
                        onBack = onCloseTask,
                        showTopTitleBar = false
                    )
                } else {
                    when(tab) {
                        0 -> TaskStatusPanel(controller, onEdit, onDevice)
                        1 -> TaskScreen(controller, onEdit)
                        2 -> LogCenterPanel(controller)
                        3 -> ScreenPanel(controller)
                    }
                }
            }
        }
    }
}

@Composable
private fun BulkQuickButton(iconRes: Int, tooltip: String, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled && !loading, modifier = Modifier.size(40.dp)) {
        if(loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(painterResource(iconRes), tooltip, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ConfigStateBadge(controller: OasController, onDevice: () -> Unit) {
    val color = when {
        controller.active -> OasDesignTokens.StatusColors.Running
        !controller.connected -> OasDesignTokens.StatusColors.AbnormalAccent
        !controller.environmentReady -> OasDesignTokens.StatusColors.Pending
        else -> OasDesignTokens.StatusColors.Stopped
    }
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .clickable(onClick = onDevice),
        contentAlignment = Alignment.Center
    ) {
        if(controller.active || !controller.connected) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        } else {
            Icon(painterResource(R.drawable.ic_oasx_donut), "设备状态", modifier = Modifier.size(18.dp), tint = color)
        }
    }
}

private fun toggleScriptPower(controller: OasController, onDevice: () -> Unit) {
    if(controller.active) {
        controller.stop()
    } else if(!controller.connected || !controller.environmentReady) {
        controller.notify("请先连接 Shizuku 并准备运行环境")
        onDevice()
    } else {
        controller.start()
    }
}

@Composable
fun DeviceReadinessCard(controller: OasController, onDevice: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDevice),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if(!controller.connected) "Shizuku ${controller.permissionText()}" else "运行屏幕：${controller.screenLabel}",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    if(!controller.connected) "点按连接 Shizuku 并开启运行环境" else "点按准备游戏运行屏幕",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = onDevice, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text("设备与游戏")
            }
        }
    }
}

@Composable
private fun LogCenterPanel(controller: OasController) {
    var logTab by rememberSaveable { mutableIntStateOf(0) }
    var wrapLines by rememberSaveable { mutableStateOf(true) }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val infoLines = remember(controller.logLines, controller.message) {
        if(controller.logLines.isNotEmpty()) controller.logLines
        else listOfNotNull(controller.message.takeIf { it.isNotBlank() })
    }
    LaunchedEffect(infoLines.size, autoScroll, logTab) {
        if(logTab == 0 && autoScroll && infoLines.isNotEmpty()) {
            listState.scrollToItem(infoLines.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow {
                listOf("信息", "结果").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = logTab == index,
                        onClick = { logTab = index },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        icon = {},
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { wrapLines = !wrapLines }, modifier = Modifier.size(38.dp)) {
                Icon(
                    painterResource(R.drawable.ic_oasx_wrap_text),
                    "自动换行",
                    modifier = Modifier.size(20.dp),
                    tint = if(wrapLines) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if(logTab == 0) {
                IconButton(onClick = { autoScroll = !autoScroll }, modifier = Modifier.size(38.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_oasx_auto_scroll),
                        "自动滚动",
                        modifier = Modifier.size(20.dp),
                        tint = if(autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = {
                    val text = if(logTab == 0) infoLines.joinToString("\n") else resultText(controller.lastResult, controller.labels)
                    val manager = context.getSystemService(ClipboardManager::class.java)
                    manager?.setPrimaryClip(ClipData.newPlainText("OASA Log", text))
                    controller.notify("已复制内容")
                },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(painterResource(R.drawable.ic_oasx_copy), "复制日志", modifier = Modifier.size(20.dp))
            }
            if(logTab == 0) {
                IconButton(onClick = controller::clearLogs, modifier = Modifier.size(38.dp)) {
                    Icon(painterResource(R.drawable.ic_oasx_delete), "清空日志", modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        ) {
            if(logTab == 0) {
                if(infoLines.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无运行日志", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    val hScroll = rememberScrollState()
                    val modifier = if(wrapLines) Modifier.fillMaxSize() else Modifier.fillMaxSize().horizontalScroll(hScroll)
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = modifier,
                            contentPadding = PaddingValues(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(infoLines) { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    softWrap = wrapLines
                                )
                            }
                        }
                    }
                }
            } else {
                SelectionContainer {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        Text("最近结果", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        Text(resultText(controller.lastResult, controller.labels), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenPanel(controller: OasController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GamePreview(controller)
        Text(
            "后台虚拟屏支持实时预览。打开手动控制后可直接点击、滑动；脚本运行时自动禁用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun OasCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(OasDesignTokens.CornerRadius.card),
        elevation = CardDefaults.cardElevation(defaultElevation = OasDesignTokens.Card.elevation),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        content = content
    )
}

@Composable
fun SectionTitle(value: String) { Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }

@Composable
fun InfoRow(title: String, value: String, status: Boolean? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            (if(status != null) "● " else "") + value,
            style = MaterialTheme.typography.bodyMedium,
            color = if(status == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun resultText(raw: String, labels: JSONObject? = null): String {
    if(raw.isBlank()) return "最近一次还没有运行记录"
    fun labelOf(name: String): String =
        if(name.isBlank()) "" else labels?.optString(name, name)?.takeIf { it.isNotBlank() } ?: name
    return try {
        val value = JSONObject(raw)
        val status = when(value.optString("status")) {
            "finished" -> "已完成"
            "cancelled" -> "已停止"
            "failed" -> "失败"
            else -> value.optString("status")
        }
        val runs = value.optJSONArray("runs")?.let { list ->
            (0 until list.length()).joinToString("\n") {
                val entry = list.getJSONObject(it)
                "• ${labelOf(entry.optString("task"))} · ${entry.optString("status")}"
            }
        }.orEmpty()
        listOfNotNull(
            labelOf(value.optString("task")).takeIf { it.isNotBlank() }?.let { "$it · $status" } ?: status,
            value.optString("detail").takeIf { it.isNotBlank() },
            runs.takeIf { it.isNotBlank() }
        ).joinToString("\n")
    } catch(_: Exception) { raw }
}

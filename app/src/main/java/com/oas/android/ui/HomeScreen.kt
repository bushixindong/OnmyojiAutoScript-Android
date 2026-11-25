// OASX config_collection_panel.dart, active_config_panel.dart & log_center_panel.dart adapted for native Android.
package com.oas.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    fun isPresetRunning(preset: PresetItem): Boolean =
        preset.running || (controller.active && preset.name == controller.activePreset)

    fun presetState(preset: PresetItem): ScriptStateFilter = when {
        isPresetRunning(preset) -> ScriptStateFilter.RUNNING
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
                                val runningNow = isPresetRunning(preset)
                                IconButton(
                                    onClick = {
                                        if(runningNow) controller.stop()
                                        else controller.selectPreset(preset.name) { toggleScriptPower(controller, onDevice) }
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_oasx_power),
                                        if(runningNow) "停止脚本" else "启动脚本",
                                        modifier = Modifier.size(22.dp),
                                        tint = if(runningNow) OasDesignTokens.StatusColors.Running else LocalContentColor.current
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
                    enabled = bulkIdle && hasBulkTasks,
                    onClick = { controller.bulkQuickSchedule(true) }
                )
                BulkQuickButton(
                    iconRes = R.drawable.ic_oasx_schedule,
                    tooltip = "全部置为等待",
                    loading = controller.bulkMode == "waitNow",
                    enabled = bulkIdle && hasBulkTasks,
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
                val safeTab = tab.coerceIn(0, 4)
                listOf("总览", "任务", "日志", "统计", "收益").forEachIndexed { index, label ->
                    FilterChip(
                        selected = safeTab == index,
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
                    when(tab.coerceIn(0, 4)) {
                        0 -> TaskStatusPanel(controller, onEdit, onDevice)
                        1 -> TaskScreen(controller, onEdit)
                        2 -> LogCenterPanel(controller)
                        3 -> StatisticsPanel(controller)
                        else -> IncomePanel(controller)
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
            Text(
                if(!controller.connected) "Shizuku ${controller.permissionText()}" else "运行屏幕：${controller.screenLabel}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
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
    var previewImagePath by remember { mutableStateOf<String?>(null) }
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
    LaunchedEffect(logTab) {
        if(logTab == 1) controller.refreshErrorLogs()
    }

    val selectedError = controller.selectedError
    val showTextActions = logTab == 0 || selectedError != null

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow {
                listOf("信息", "错误").forEachIndexed { index, label ->
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
            if(showTextActions) {
                IconButton(onClick = { wrapLines = !wrapLines }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_oasx_wrap_text),
                        "自动换行",
                        modifier = Modifier.size(20.dp),
                        tint = if(wrapLines) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if(logTab == 0) {
                IconButton(onClick = { autoScroll = !autoScroll }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_oasx_auto_scroll),
                        "自动滚动",
                        modifier = Modifier.size(20.dp),
                        tint = if(autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if(selectedError == null) {
                IconButton(onClick = controller::refreshErrorLogs, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, "刷新错误日志", modifier = Modifier.size(20.dp))
                }
            }
            if(showTextActions) {
                IconButton(
                    onClick = {
                        val text = if(logTab == 0) infoLines.joinToString("\n") else selectedError?.logText.orEmpty()
                        val manager = context.getSystemService(ClipboardManager::class.java)
                        manager?.setPrimaryClip(ClipData.newPlainText("OASA Log", text))
                        controller.notify("已复制日志")
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_oasx_copy), "复制日志", modifier = Modifier.size(20.dp))
                }
            }
            IconButton(
                onClick = {
                    if(logTab == 0) controller.clearLogs()
                    else if(selectedError != null) controller.deleteErrorLog(selectedError.item.id)
                    else controller.deleteErrorLog(null)
                },
                enabled = if(logTab == 0) infoLines.isNotEmpty() else controller.errorLogs.isNotEmpty(),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(painterResource(R.drawable.ic_oasx_delete), if(logTab == 0) "清空日志" else "删除报错记录", modifier = Modifier.size(20.dp))
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
                                OasxLogLineText(line = line, wrapLines = wrapLines)
                            }
                        }
                    }
                }
            } else if(selectedError != null) {
                ErrorLogDetailPanel(
                    detail = selectedError,
                    wrapLines = wrapLines,
                    onBack = controller::closeErrorLog,
                    onOpenImage = { previewImagePath = it }
                )
            } else {
                ErrorLogListPanel(controller)
            }
        }
    }

    previewImagePath?.let { path ->
        ErrorImagePreviewDialog(path = path, onDismiss = { previewImagePath = null })
    }
}

@Composable
private fun ErrorLogListPanel(controller: OasController) {
    if(controller.errorLogsLoading && controller.errorLogs.isEmpty()) {
        LoadingPanel("正在读取错误日志…")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        if(controller.errorLogs.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Text("暂无报错记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(controller.errorLogs, key = { it.id }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { controller.openErrorLog(item.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.taskLabel.ifBlank { "异常" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("· ${item.imageCount} 张图", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(item.timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                        if(item.summary.isNotBlank()) {
                            Text(
                                item.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("最近运行结果", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(resultText(controller.lastResult, controller.labels), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun ErrorLogDetailPanel(
    detail: ErrorLogDetail,
    wrapLines: Boolean,
    onBack: () -> Unit,
    onOpenImage: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回错误列表")
            }
            Column(Modifier.weight(1f)) {
                Text(detail.item.taskLabel.ifBlank { "错误详情" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail.item.timeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        if(detail.imagePaths.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(156.dp),
                contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(detail.imagePaths, key = { it }) { path ->
                    ErrorThumbnailCard(path = path, onClick = { onOpenImage(path) })
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
        val hScroll = rememberScrollState()
        val vScroll = rememberScrollState()
        val textMod = if(wrapLines) {
            Modifier.weight(1f).fillMaxWidth().verticalScroll(vScroll).padding(10.dp)
        } else {
            Modifier.weight(1f).fillMaxWidth().verticalScroll(vScroll).horizontalScroll(hScroll).padding(10.dp)
        }
        SelectionContainer(modifier = textMod) {
            val lines = remember(detail.logText) {
                if(detail.logText.isBlank()) listOf("无日志内容") else detail.logText.lines()
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lines.forEach { line ->
                    OasxLogLineText(line = line, wrapLines = wrapLines)
                }
            }
        }
    }
}

@Composable
private fun OasxLogLineText(line: String, wrapLines: Boolean) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val annotated = remember(line, isDark) { buildOasxLogSpan(line, isDark) }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontFeatureSettings = "tnum",
            lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.15f
        ),
        softWrap = wrapLines
    )
}

private fun buildOasxLogSpan(source: String, isDark: Boolean) = buildAnnotatedString {
    val value = source.trimEnd('\r', '\n')
    if(value.length > 2000) {
        append(value)
        return@buildAnnotatedString
    }
    val cyan = if(isDark) Color(0xFF26C6DA) else Color(0xFF00838F)
    val infoBlue = if(isDark) Color(0xFF64B5F6) else Color(0xFF376D88)
    val warnYellow = if(isDark) Color(0xFFFFD54F) else Color(0xFFE65100)
    val errRed = if(isDark) Color(0xFFEF5350) else Color(0xFFD32F2F)
    val okGreen = if(isDark) Color(0xFF8BC34A) else Color(0xFF558B2F)
    val nonePurple = if(isDark) Color(0xFFCE93D8) else Color(0xFF7B1FA2)

    val isHeading = value.contains("<<<") && value.contains(">>>")
    val tokenStyles = listOf(
        "CRITICAL" to SpanStyle(color = errRed, fontWeight = FontWeight.Bold),
        "WARNING" to SpanStyle(color = warnYellow, fontWeight = FontWeight.SemiBold),
        "ERROR" to SpanStyle(color = errRed, fontWeight = FontWeight.Bold),
        "INFO" to SpanStyle(color = infoBlue, fontWeight = FontWeight.SemiBold),
        "True" to SpanStyle(color = okGreen),
        "False" to SpanStyle(color = errRed),
        "None" to SpanStyle(color = nonePurple)
    )
    fun isWord(c: Char) = c.isLetterOrDigit() || c == '_'
    fun isDigits(s: String, start: Int, len: Int): Boolean {
        if(start + len > s.length) return false
        for(k in 0 until len) if(!s[start + k].isDigit()) return false
        return true
    }
    fun timeLen(s: String, idx: Int): Int {
        // YYYY-MM-DD HH:mm:ss.SSS or YYYY-MM-DD HH:mm:ss
        if(idx + 19 <= s.length && isDigits(s, idx, 4) && s[idx + 4] == '-' && isDigits(s, idx + 5, 2) &&
            s[idx + 7] == '-' && isDigits(s, idx + 8, 2) && s[idx + 10] == ' ' &&
            isDigits(s, idx + 11, 2) && s[idx + 13] == ':' && isDigits(s, idx + 14, 2) &&
            s[idx + 16] == ':' && isDigits(s, idx + 17, 2)) {
            return if(idx + 23 <= s.length && s[idx + 19] == '.' && isDigits(s, idx + 20, 3)) 23 else 19
        }
        // HH:mm:ss.SSS or HH:mm:ss
        if(idx + 8 <= s.length && isDigits(s, idx, 2) && s[idx + 2] == ':' && isDigits(s, idx + 3, 2) &&
            s[idx + 5] == ':' && isDigits(s, idx + 6, 2)) {
            return if(idx + 12 <= s.length && s[idx + 8] == '.' && isDigits(s, idx + 9, 3)) 12 else 8
        }
        return 0
    }

    var plainStart = 0
    var i = 0
    while(i < value.length) {
        val tsLen = timeLen(value, i)
        if(tsLen > 0 && (i == 0 || !isWord(value[i - 1]))) {
            if(plainStart < i) {
                val seg = value.substring(plainStart, i)
                if(isHeading) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(seg) } else append(seg)
            }
            withStyle(SpanStyle(color = cyan)) { append(value.substring(i, i + tsLen)) }
            i += tsLen
            plainStart = i
            continue
        }
        var matched = false
        for((tok, style) in tokenStyles) {
            if(value.startsWith(tok, i)) {
                val beforeOk = i == 0 || !isWord(value[i - 1])
                val afterIdx = i + tok.length
                val afterOk = afterIdx >= value.length || !isWord(value[afterIdx])
                if(beforeOk && afterOk) {
                    if(plainStart < i) {
                        val seg = value.substring(plainStart, i)
                        if(isHeading) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(seg) } else append(seg)
                    }
                    withStyle(style) { append(tok) }
                    i = afterIdx
                    plainStart = i
                    matched = true
                    break
                }
            }
        }
        if(!matched) i++
    }
    if(plainStart < value.length) {
        val seg = value.substring(plainStart)
        if(isHeading) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(seg) } else append(seg)
    }
}

@Composable
private fun ErrorThumbnailCard(path: String, onClick: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            try { BitmapFactory.decodeFile(path)?.asImageBitmap() } catch(_: Throwable) { null }
        }
    }
    Surface(
        modifier = Modifier.width(220.dp).fillMaxHeight().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        val img = bitmap
        if(img != null) {
            Image(img, "报错截图", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ErrorImagePreviewDialog(path: String, onDismiss: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            try { BitmapFactory.decodeFile(path)?.asImageBitmap() } catch(_: Throwable) { null }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("报错现场截图") },
        text = {
            val img = bitmap
            if(img != null) {
                Image(img, "报错现场截图", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp))
            } else {
                Text("无法加载图片")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
fun ScreenDestination(controller: OasController, onDevice: () -> Unit) {
    val listState = rememberLazyListState()
    val infoLines = remember(controller.logLines, controller.message) {
        if(controller.logLines.isNotEmpty()) controller.logLines
        else listOfNotNull(controller.message.takeIf { it.isNotBlank() })
    }
    LaunchedEffect(infoLines.size) {
        if(infoLines.isNotEmpty()) listState.scrollToItem(infoLines.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        OasCard(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GamePreview(controller)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if(controller.active) {
                        Button(
                            onClick = controller::stop,
                            enabled = !controller.stopping,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(painterResource(R.drawable.ic_oasx_power), null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if(controller.stopping) "正在停止…" else "停止 · ${controller.activePreset}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else if(controller.connected && controller.environmentReady) {
                        OutlinedButton(
                            onClick = controller::release,
                            enabled = !controller.busy,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("释放虚拟屏", maxLines = 1) }
                    } else {
                        OutlinedButton(
                            onClick = onDevice,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("设备与游戏", maxLines = 1) }
                    }
                    OutlinedButton(
                        onClick = controller::toggleFloatingBall,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(if(controller.floatingBallVisible) "关闭悬浮球" else "打开悬浮球", maxLines = 1) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "实时日志 · ${controller.activePreset}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if(controller.active) "运行中" else if(controller.environmentReady) "已就绪" else controller.screenLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if(controller.active) OasDesignTokens.StatusColors.Running else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                ) {
                    if(infoLines.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无运行日志", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        SelectionContainer {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                items(infoLines) { line ->
                                    OasxLogLineText(line = line, wrapLines = true)
                                }
                            }
                        }
                    }
                }
            }
        }
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

// SPDX-License-Identifier: AGPL-3.0-only
// OASX task_status_panel.dart & task_catalog_panel.dart adapted for native Android.
package com.oas.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oas.android.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class OverviewBucket { RUNNING, PENDING, WAITING }

@Composable
fun TaskStatusPanel(controller: OasController, onEdit: (String) -> Unit, onDevice: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var editingTimeFor by remember { mutableStateOf<TaskItem?>(null) }

    val enabled = remember(controller.tasks, query, controller.active) {
        val list = controller.tasks
            .filter { it.enabled && it.runnable && (query.isBlank() || it.title.contains(query.trim(), true) || it.id.contains(query.trim(), true)) }
        list.sortedWith(compareBy<TaskItem> { bucketPriority(bucketOf(it)) }.thenBy { it.nextRun })
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索任务") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = if(query.isNotEmpty()) {
                { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清除搜索") } }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(12.dp))
        if(!controller.connected || !controller.environmentReady) {
            DeviceReadinessCard(controller, onDevice)
            Spacer(Modifier.height(10.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if(enabled.isEmpty()) {
                item {
                    EmptyPanel("暂无已启用任务", "在任务页勾选加入计划")
                }
            } else {
                items(enabled, key = { it.id }) { task ->
                    TaskStatusRow(
                        task = task,
                        locked = task.running || task.id in controller.pending || controller.bulkMode != "none",
                        onQuickRun = { controller.quickRun(task) },
                        onQuickWait = { controller.quickWait(task) },
                        onEditTask = { onEdit(task.id) },
                        onClickTime = { editingTimeFor = task }
                    )
                }
            }
        }
    }

    editingTimeFor?.let { task ->
        TaskDateTimeDialog(
            initial = parseOasxDateTime(task.nextRun) ?: LocalDateTime.now(),
            title = task.title,
            onDismiss = { editingTimeFor = null },
            onConfirm = { value ->
                controller.setNextRun(task, value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                editingTimeFor = null
            }
        )
    }
}

@Composable
private fun TaskStatusRow(
    task: TaskItem,
    locked: Boolean,
    onQuickRun: () -> Unit,
    onQuickWait: () -> Unit,
    onEditTask: () -> Unit,
    onClickTime: () -> Unit
) {
    val bucket = bucketOf(task)
    val scheme = MaterialTheme.colorScheme
    val background = when(bucket) {
        OverviewBucket.RUNNING -> scheme.tertiaryContainer.copy(alpha = 0.24f)
        OverviewBucket.PENDING -> scheme.secondaryContainer.copy(alpha = 0.20f)
        OverviewBucket.WAITING -> scheme.surfaceContainerHigh
    }
    val borderColor = when(bucket) {
        OverviewBucket.RUNNING -> OasDesignTokens.StatusColors.Running.copy(alpha = 0.28f)
        OverviewBucket.PENDING -> OasDesignTokens.StatusColors.Pending.copy(alpha = 0.30f)
        OverviewBucket.WAITING -> scheme.outlineVariant.copy(alpha = 0.70f)
    }
    val (iconRes, iconTint) = when(bucket) {
        OverviewBucket.RUNNING -> R.drawable.ic_oasx_flash_on to OasDesignTokens.StatusColors.Running
        OverviewBucket.PENDING -> R.drawable.ic_oasx_layers to OasDesignTokens.StatusColors.Pending
        OverviewBucket.WAITING -> R.drawable.ic_oasx_schedule to OasDesignTokens.StatusColors.Waiting
    }
    val canSchedule = task.schedulable && !locked

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = background,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(painterResource(iconRes), null, modifier = Modifier.size(22.dp), tint = iconTint)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val timeLabel = formatOasxNextRun(task.nextRun)
                if(timeLabel.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        timeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = if(!locked) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.clickable(enabled = canSchedule, onClick = onClickTime)
                    )
                }
            }
            TaskActionButtons(
                canQuickSchedule = canSchedule,
                onQuickRun = onQuickRun,
                onQuickWait = onQuickWait,
                onOpenParams = onEditTask
            )
        }
    }
}

@Composable
fun TaskScreen(controller: OasController, onEdit: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部任务") }
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var filterMenu by remember { mutableStateOf(false) }

    val filters = listOf("全部任务", "已启用", "未启用")
    val visible = controller.tasks.filter { it.groupId.isNotEmpty() && !it.deferred }.filter {
        (when(filter) { "已启用" -> it.enabled; "未启用" -> !it.enabled; else -> true }) &&
            (query.isBlank() || it.title.contains(query.trim(), true) || it.id.contains(query.trim(), true))
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索任务") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
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
                        "筛选任务",
                        tint = if(filter != "全部任务") MaterialTheme.colorScheme.primary else LocalContentColor.current
                    )
                }
                DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    filters.forEach { value ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    value,
                                    fontWeight = if(value == filter) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if(value == filter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = { filter = value; filterMenu = false }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if(controller.loading && controller.tasks.isEmpty()) item { LoadingPanel("正在载入完整任务…") }
            if(controller.error.isNotBlank()) item { ErrorPanel(controller.error, controller::refresh) }

            visible.groupBy { it.groupId }.forEach { (group, tasks) ->
                val isExpanded = group in expanded || query.isNotBlank()
                item(key = "section-$group") {
                    CatalogSectionCard(
                        category = tasks.first().category,
                        tasks = tasks,
                        expanded = isExpanded,
                        forceExpanded = query.isNotBlank(),
                        onToggleExpand = { expanded = if(group in expanded) expanded - group else expanded + group },
                        controller = controller,
                        onEdit = onEdit
                    )
                }
            }
            if(visible.isEmpty() && !controller.loading) {
                item { EmptyPanel(if(query.isNotBlank()) "未找到相关任务" else "暂无任务", "") }
            }
        }
    }
}

@Composable
private fun CatalogSectionCard(
    category: String,
    tasks: List<TaskItem>,
    expanded: Boolean,
    forceExpanded: Boolean,
    onToggleExpand: () -> Unit,
    controller: OasController,
    onEdit: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if(expanded) scheme.surfaceContainerLowest else scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !forceExpanded, onClick = onToggleExpand)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(category, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(tasks.size.toString(), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Icon(
                    painterResource(if(expanded) R.drawable.ic_oasx_expand_less else R.drawable.ic_oasx_expand_more),
                    null,
                    modifier = Modifier.size(22.dp),
                    tint = scheme.onSurfaceVariant
                )
            }
            if(expanded) {
                tasks.forEachIndexed { index, task ->
                    if(index > 0) {
                        HorizontalDivider(
                            Modifier.padding(horizontal = 12.dp),
                            thickness = 0.5.dp,
                            color = scheme.outlineVariant.copy(alpha = 0.6f)
                        )
                    } else {
                        HorizontalDivider(thickness = 0.5.dp, color = scheme.outlineVariant.copy(alpha = 0.6f))
                    }
                    CatalogTaskRow(task, controller, onEdit)
                }
            }
        }
    }
}

@Composable
private fun CatalogTaskRow(task: TaskItem, controller: OasController, onEdit: (String) -> Unit) {
    val isPending = task.id in controller.pending
    val locked = task.running || isPending || controller.bulkMode != "none"
    val canQuickSchedule = task.runnable && task.schedulable && !task.deferred && !locked
    val scheme = MaterialTheme.colorScheme

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !task.deferred) { onEdit(task.id) }
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if(task.schedulable && !task.deferred) {
            if(isPending) {
                CircularProgressIndicator(Modifier.size(22.dp).padding(2.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    painterResource(if(task.enabled) R.drawable.ic_oasx_check_circle else R.drawable.ic_oasx_unchecked),
                    if(task.enabled) "禁用 ${task.title}" else "启用 ${task.title}",
                    modifier = Modifier.size(22.dp).clickable(enabled = !locked) { controller.setEnabled(task, !task.enabled) },
                    tint = if(task.enabled) scheme.onSurface else scheme.onSurfaceVariant
                )
            }
        } else {
            Icon(
                if(task.deferred) Icons.Default.Info else if(!task.runnable) Icons.Default.Settings else Icons.Default.PlayArrow,
                null,
                modifier = Modifier.size(22.dp),
                tint = scheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if(!task.deferred) {
            TaskActionButtons(
                canQuickSchedule = canQuickSchedule,
                onQuickRun = { controller.quickRun(task) },
                onQuickWait = { controller.quickWait(task) },
                onOpenParams = { onEdit(task.id) }
            )
        }
    }
}

@Composable
private fun TaskActionButtons(
    canQuickSchedule: Boolean,
    onQuickRun: () -> Unit,
    onQuickWait: () -> Unit,
    onOpenParams: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onQuickRun, enabled = canQuickSchedule, modifier = Modifier.size(36.dp)) {
            Icon(painterResource(R.drawable.ic_oasx_flash_on), "立即运行", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onQuickWait, enabled = canQuickSchedule, modifier = Modifier.size(36.dp)) {
            Icon(painterResource(R.drawable.ic_oasx_schedule), "置为等待", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onOpenParams, modifier = Modifier.size(36.dp)) {
            Icon(painterResource(R.drawable.ic_oasx_tune), "打开任务参数", modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TaskDateTimeDialog(
    initial: LocalDateTime,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (LocalDateTime) -> Unit
) {
    OasxWheelDateTimeSheet(
        initial = initial,
        title = "设置 $title 执行时间",
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

private fun parseOasxDateTime(value: String): LocalDateTime? = try {
    if(value.isBlank() || value == "null") null else LocalDateTime.parse(value.trim().replace(' ', 'T'))
} catch(_: Exception) { null }

fun isTaskDueOrNow(value: String): Boolean {
    val dt = parseOasxDateTime(value) ?: return true
    return !dt.isAfter(LocalDateTime.now())
}

private fun bucketOf(task: TaskItem): OverviewBucket = when {
    task.running -> OverviewBucket.RUNNING
    isTaskDueOrNow(task.nextRun) -> OverviewBucket.PENDING
    else -> OverviewBucket.WAITING
}

private fun bucketPriority(bucket: OverviewBucket): Int = when(bucket) {
    OverviewBucket.RUNNING -> 0
    OverviewBucket.PENDING -> 1
    OverviewBucket.WAITING -> 2
}

fun formatOasxClockOnly(value: String): String {
    val dt = parseOasxDateTime(value) ?: return ""
    return dt.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
}

private fun formatOasxNextRun(value: String): String {
    val dt = parseOasxDateTime(value) ?: return if(value.isBlank() || value == "null") "未设置时间" else value
    return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}

@Composable
fun LoadingPanel(text: String) {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyPanel(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        if(detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun ErrorPanel(detail: String, retry: () -> Unit) {
    OasCard {
        Column(Modifier.padding(16.dp)) {
            Text(detail, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = retry) { Text("重试") }
        }
    }
}

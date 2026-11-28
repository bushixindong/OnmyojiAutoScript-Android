// OASX statistics_panel.dart, statistics_history_chart.dart & statistics_detail_section.dart faithfully ported to Compose.
package com.oas.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private enum class StatsMetric(val label: String, val usesDuration: Boolean) {
    DURATION("总耗时", true),
    RUN_COUNT("运行次数", false),
    BATTLE_COUNT("战斗次数", false),
    BATTLE_AVG("平均战斗耗时", true)
}

private enum class StatsSortField(val label: String) {
    VALUE("按数值"),
    TIME("按时间")
}

private val statsPalette = listOf(
    Color(0xFF4E79A7),
    Color(0xFFF28E2B),
    Color(0xFF59A14F),
    Color(0xFFE15759),
    Color(0xFF76B7B2),
    Color(0xFFEDC948),
    Color(0xFFB07AA1),
    Color(0xFFFF9DA7),
    Color(0xFF9C755F),
    Color(0xFF6B8E23)
)

private fun statsTaskColor(name: String): Color = statsPalette[abs(name.hashCode()) % statsPalette.size]

private fun formatStatsDuration(seconds: Double): String {
    val total = max(0, seconds.roundToInt())
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "${h}h ${m}m ${s}s"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

private fun formatStatsAxisTick(value: Double, metric: StatsMetric): String = when {
    metric.usesDuration -> {
        val total = max(0, value.roundToInt())
        when {
            total == 0 -> "0"
            total >= 3600 -> "${total / 3600}h"
            total >= 60 -> "${total / 60}m"
            else -> "${total}s"
        }
    }
    else -> value.roundToInt().toString()
}

private fun formatClockPart(raw: String): String {
    val trimmed = raw.trim()
    val idx = trimmed.indexOf(' ')
    val clock = if(idx >= 0) trimmed.substring(idx + 1) else trimmed
    val dot = clock.indexOf('.')
    return if(dot > 0) clock.substring(0, dot) else clock
}

private fun StatsTaskSummary.metricValue(metric: StatsMetric): Double = when(metric) {
    StatsMetric.DURATION -> totalDurationSeconds
    StatsMetric.RUN_COUNT -> runCount.toDouble()
    StatsMetric.BATTLE_COUNT -> battleCount.toDouble()
    StatsMetric.BATTLE_AVG -> battleAvgDurationSeconds
}

private fun formatMetricValue(value: Double, metric: StatsMetric): String = when(metric) {
    StatsMetric.DURATION, StatsMetric.BATTLE_AVG -> formatStatsDuration(value)
    StatsMetric.RUN_COUNT, StatsMetric.BATTLE_COUNT -> "${value.roundToInt()}"
}

private fun resolveStatsInterval(maxValue: Double, metric: StatsMetric): Double = if(metric.usesDuration) {
    when {
        maxValue <= 600.0 -> 120.0
        maxValue <= 3600.0 -> 600.0
        maxValue <= 4 * 3600.0 -> 1800.0
        else -> 3600.0
    }
} else {
    when {
        maxValue <= 10.0 -> 2.0
        maxValue <= 50.0 -> 10.0
        maxValue <= 100.0 -> 20.0
        else -> max(ceil(maxValue / 5.0), 1.0)
    }
}

@Composable
fun StatisticsPanel(controller: OasController) {
    var metric by remember { mutableStateOf(StatsMetric.DURATION) }
    var sortField by remember { mutableStateOf(StatsSortField.VALUE) }
    var descending by remember { mutableStateOf(true) }
    var dateMenu by remember { mutableStateOf(false) }
    var metricMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var focusedTask by remember { mutableStateOf("") }
    var sixRealmsDialogTask by remember { mutableStateOf<StatsTaskSummary?>(null) }

    LaunchedEffect(controller.activePreset) {
        focusedTask = ""
        controller.loadStats()
    }

    val day = controller.statsDay
    val sortedTasks = remember(day, metric, sortField, descending) {
        val raw = day?.tasks.orEmpty().filter { it.metricValue(metric) > 0 || metric == StatsMetric.DURATION }
        val sorted = when(sortField) {
            StatsSortField.VALUE -> raw.sortedBy { it.metricValue(metric) }
            StatsSortField.TIME -> raw.sortedBy { it.runs.lastOrNull()?.endTime ?: "" }
        }
        if(descending) sorted.reversed() else sorted
    }

    LaunchedEffect(sortedTasks) {
        if(sortedTasks.isNotEmpty() && sortedTasks.none { it.taskName == focusedTask }) {
            focusedTask = sortedTasks.first().taskName
        }
    }

    val focusedSummary = sortedTasks.firstOrNull { it.taskName == focusedTask }
    val focusedRuns = remember(focusedSummary) { focusedSummary?.runs.orEmpty().reversed() }

    fun labelOf(name: String): String =
        controller.labels.optString(name, name).takeIf { it.isNotBlank() } ?: name

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        // _HeaderSection: top row (date dropdown + refresh + bold total runtime) + filter row (metric + sort + direction)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(
                            onClick = { dateMenu = true },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(controller.statsSelectedDate.ifBlank { "今日" })
                            Spacer(Modifier.width(2.dp))
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = dateMenu, onDismissRequest = { dateMenu = false }) {
                            controller.statsDates.forEach { d ->
                                DropdownMenuItem(
                                    text = { Text(d, fontWeight = if(d == controller.statsSelectedDate) FontWeight.SemiBold else FontWeight.Normal) },
                                    onClick = { dateMenu = false; controller.loadStats(d) }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { controller.loadStats(controller.statsSelectedDate.ifBlank { null }) }, enabled = !controller.statsLoading) {
                        if(controller.statsLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "刷新统计")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatStatsDuration(day?.totalRuntimeSeconds ?: 0.0),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(
                            onClick = { metricMenu = true },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(metric.label, style = MaterialTheme.typography.bodySmall)
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = metricMenu, onDismissRequest = { metricMenu = false }) {
                            StatsMetric.entries.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.label) },
                                    onClick = { metricMenu = false; metric = item }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box {
                        OutlinedButton(
                            onClick = { sortMenu = true },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(sortField.label, style = MaterialTheme.typography.bodySmall)
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            StatsSortField.entries.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.label) },
                                    onClick = { sortMenu = false; sortField = item }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = { descending = !descending },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(if(descending) "↓ 降序" else "↑ 升序", style = MaterialTheme.typography.labelMedium)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            }
        }

        if(controller.statsError.isNotBlank()) {
            item { Text(controller.statsError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        if(sortedTasks.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if(controller.statsLoading) "正在统计日志…" else "当日暂无运行统计数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val maxVal = sortedTasks.maxOf { it.metricValue(metric) }
            val interval = resolveStatsInterval(maxVal, metric)
            val axisMax = max(interval, ceil(maxVal / interval) * interval)
            val ticks = buildList {
                var cur = 0.0
                while(cur <= axisMax + 0.001 && size < 8) {
                    add(cur)
                    cur += interval
                }
            }

            // _ChartCard: fixed top axis + OASX pill-bar rows
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Top Axis Header
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            Spacer(Modifier.width(96.dp))
                            Column(Modifier.weight(1f)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    ticks.forEach { tick ->
                                        Text(
                                            formatStatsAxisTick(tick, metric),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                            }
                        }

                        // Horizontal Bar Rows (OASX _HistoryBarRow)
                        sortedTasks.forEach { entry ->
                            val value = entry.metricValue(metric)
                            val ratio = if(axisMax <= 0.0) 0f else (value / axisMax).toFloat().coerceIn(0.04f, 1f)
                            val color = statsTaskColor(entry.taskName)
                            val focused = entry.taskName == focusedTask
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        focusedTask = entry.taskName
                                        if(entry.sixRealms != null) {
                                            sixRealmsDialogTask = entry
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    labelOf(entry.taskName),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if(focused) color else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if(focused) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(88.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                                    Box(
                                        Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(999.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                                    )
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(ratio).height(if(focused) 26.dp else 22.dp),
                                        shape = RoundedCornerShape(999.dp),
                                        color = color.copy(alpha = if(focused) 0.92f else 0.76f),
                                        border = BorderStroke(if(focused) 1.3.dp else 1.dp, if(focused) MaterialTheme.colorScheme.onSurface else color)
                                    ) {}
                                    Surface(
                                        modifier = Modifier.align(Alignment.CenterEnd),
                                        shape = RoundedCornerShape(999.dp),
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                        border = BorderStroke(0.8.dp, color.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            formatMetricValue(value, metric),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = color,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ScriptStatisticsDetailSection
            if(focusedSummary != null) {
                item {
                    Text(
                        "运行明细：${labelOf(focusedSummary.taskName)}",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if(focusedRuns.isEmpty()) {
                    item {
                        Text("暂无单次明细记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    itemsIndexed(focusedRuns) { index, run ->
                        val serial = focusedRuns.size - index
                        val color = statsTaskColor(focusedSummary.taskName)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    Modifier.size(28.dp).clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("$serial", style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "${formatClockPart(run.startTime)} - ${formatClockPart(run.endTime)}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        if(run.battleCount > 0) "战斗次数: ${run.battleCount}  ·  平均战斗耗时: ${formatStatsDuration(run.battleAvgDurationSeconds)}"
                                        else "无战斗记录",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    formatStatsDuration(run.durationSeconds),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val dialogTask = sixRealmsDialogTask
    val sr = dialogTask?.sixRealms
    if(dialogTask != null && sr != null) {
        val winRate = if(sr.total > 0) String.format("%.1f%%", sr.win * 100.0 / sr.total) else "-"
        AlertDialog(
            onDismissRequest = { sixRealmsDialogTask = null },
            title = { Text("六道之门 · ${labelOf(dialogTask.taskName)}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "日期：${controller.statsSelectedDate.ifBlank { "今日" }}   ·   胜率：$winRate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Triple("总次数", "${sr.total}", MaterialTheme.colorScheme.primary),
                            Triple("成功", "${sr.win}", Color(0xFF2E7D32)),
                            Triple("失败", "${sr.lose}", MaterialTheme.colorScheme.error)
                        ).forEach { (label, value, tint) ->
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = tint.copy(alpha = 0.10f),
                                border = BorderStroke(1.dp, tint.copy(alpha = 0.25f))
                            ) {
                                Column(
                                    Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tint)
                                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if(sr.lose > 0) {
                        Text(
                            "失败构成：BOSS 战败 ${sr.bossLose} 次  ·  识别/异常报错 ${sr.errorLose} 次",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sixRealmsDialogTask = null }) { Text("确定") }
            }
        )
    }
}

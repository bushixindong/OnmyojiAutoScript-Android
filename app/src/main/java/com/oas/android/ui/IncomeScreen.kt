// OASX income_panel.dart faithfully ported to Jetpack Compose with OASX resource icons, month calendar & trend chart.
package com.oas.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.oas.android.R
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private data class IncomeResourceMeta(val key: String, val label: String, val iconRes: Int)

private val incomeResources = listOf(
    IncomeResourceMeta("gold", "金币", R.drawable.ic_income_gold),
    IncomeResourceMeta("jade", "勾玉", R.drawable.ic_income_jade),
    IncomeResourceMeta("ap", "体力", R.drawable.ic_income_ap),
    IncomeResourceMeta("mystery_amulet", "蓝票", R.drawable.ic_income_mystery_amulet),
    IncomeResourceMeta("sca_orochi_scales", "紫蛇皮", R.drawable.ic_income_sca_orochi_scales),
    IncomeResourceMeta("sca_reverse_scale", "金蛇皮", R.drawable.ic_income_sca_reverse_scale),
    IncomeResourceMeta("sca_demon_souls", "逢魔皮", R.drawable.ic_income_sca_demon_souls),
    IncomeResourceMeta("sca_picture_book", "海蛇皮", R.drawable.ic_income_sca_picture_book)
)

private val incomeResourceMap = incomeResources.associateBy { it.key }
private val incomeHistoryRanges = listOf(3, 7, 15, 30, 90, 365)

// OASX convention: 涨用红、跌用绿
private fun incomeDeltaColor(delta: Int): Color =
    if(delta >= 0) Color(0xFFE0533D) else Color(0xFF2E9E5B)

private fun trimTrailingZero(value: Double): String {
    val text = String.format(Locale.US, "%.1f", value)
    return if(text.endsWith(".0")) text.dropLast(2) else text
}

private fun formatIncomeValue(value: Int): String = when {
    value < 0 -> "--"
    value >= 100_000_000 -> "${trimTrailingZero(value / 100_000_000.0)}亿"
    value >= 10_000 -> "${trimTrailingZero(value / 10_000.0)}万"
    else -> value.toString()
}

private fun formatIncomeSignedValue(value: Double?): String {
    if(value == null) return "--"
    val rounded = value.roundToInt()
    val sign = if(rounded > 0) "+" else if(rounded < 0) "-" else ""
    return "$sign${formatIncomeValue(abs(rounded))}"
}

private fun formatIncomeTimeOnly(collectedAt: String): String {
    val text = collectedAt.trim()
    if(text.isEmpty()) return ""
    val parts = text.split(" ")
    return if(parts.size >= 2) parts[1] else text
}

private fun formatIncomeDayLabel(dayKey: String): String =
    try { LocalDate.parse(dayKey).format(DateTimeFormatter.ofPattern("MM-dd")) } catch(_: Exception) { dayKey }

private fun formatIncomeRangeLabel(days: Int): String = if(days >= 365) "1年" else "${days}天"

private fun niceStep(rawStep: Double): Double {
    if(rawStep <= 0) return 1.0
    val exponent = floor(log10(rawStep))
    val base = 10.0.pow(exponent)
    val fraction = rawStep / base
    val nice = when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * base
}

private fun niceIncomeAxis(min: Double, max: Double): Triple<Double, Double, Double> {
    var low = min
    var high = max
    if(high - low < 1.0) {
        val pad = abs(high) * 0.1 + 1.0
        low -= pad
        high += pad
    }
    val span = high - low
    val step = if(span <= 4.0) 1.0 else niceStep(span / 5.0)
    var axisMin = floor(low / step) * step
    var axisMax = ceil(high / step) * step
    if(axisMax - axisMin < step) axisMax = axisMin + step
    if(min >= 0.0 && axisMin < 0.0) axisMin = 0.0
    return Triple(axisMin, axisMax, step)
}

private fun formatIncomeAxisValue(value: Double, span: Double): String {
    val rounded = value.roundToInt()
    if(span > 0 && span < 100_000) return NumberFormat.getIntegerInstance(Locale.US).format(rounded)
    return when {
        rounded >= 100_000_000 -> String.format(Locale.US, "%.1f亿", rounded / 100_000_000.0)
        rounded >= 10_000 -> "${(rounded / 10_000.0).roundToInt()}万"
        rounded <= -100_000_000 -> String.format(Locale.US, "-%.1f亿", (-rounded) / 100_000_000.0)
        rounded <= -10_000 -> "-${((-rounded) / 10_000.0).roundToInt()}万"
        else -> rounded.toString()
    }
}

@Composable
private fun IncomeIcon(name: String, size: Dp = 22.dp) {
    val meta = incomeResourceMap[name]
    if(meta != null) {
        Image(
            painter = painterResource(meta.iconRes),
            contentDescription = meta.label,
            modifier = Modifier.size(size)
        )
    }
}

@Composable
fun IncomePanel(controller: OasController) {
    var selectedResource by remember { mutableStateOf("gold") }
    var calendarMode by remember { mutableStateOf<Boolean?>(null) } // null = closed, false = pick, true = manage

    LaunchedEffect(controller.activePreset) {
        controller.loadIncome()
    }

    val day = controller.incomeDay
    val dates = controller.incomeDates
    val selected = controller.incomeSelectedDate
    val history = controller.incomeHistory
    val orderedItems = remember(day) {
        val byName = day?.items?.associateBy { it.name }.orEmpty()
        if(byName.isEmpty()) emptyList()
        else incomeResources.mapNotNull { byName[it.key] }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        // _IncomeHeader: collectedAt time + calendar date button + delete manage button + refresh button
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatIncomeTimeOnly(day?.collectedAt.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { calendarMode = false },
                    enabled = dates.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.DateRange, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if(selected.isEmpty()) "--" else formatIncomeDayLabel(selected))
                }
                IconButton(
                    onClick = { calendarMode = true },
                    enabled = dates.isNotEmpty() && !controller.incomeLoading
                ) {
                    Icon(Icons.Default.Delete, "管理记录")
                }
                IconButton(
                    onClick = { controller.loadIncome(selected.ifBlank { null }, controller.incomeHistoryDays) },
                    enabled = !controller.incomeLoading
                ) {
                    if(controller.incomeLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, "刷新收益")
                }
            }
        }

        if(controller.incomeError.isNotBlank()) {
            item {
                Text(controller.incomeError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        if(orderedItems.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if(controller.incomeLoading) "正在读取收益快照…" else "暂无收益记录（开启「日常琐事」中的收益统计后自动采集）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // _IncomeGrid: 2-column OASX resource tiles with PNG icons and red/green delta
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    orderedItems.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { item ->
                                IncomeTile(
                                    item = item,
                                    selected = selectedResource == item.name,
                                    onClick = { selectedResource = item.name },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if(pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if(dates.isNotEmpty()) {
                item {
                    IncomeTrendSection(
                        history = history,
                        days = controller.incomeHistoryDays,
                        resource = selectedResource,
                        loading = controller.incomeLoading,
                        onSelectDays = { d -> controller.loadIncome(selected.ifBlank { null }, d) },
                        onSelectResource = { selectedResource = it }
                    )
                }
            }
        }
    }

    calendarMode?.let { multiSelect ->
        IncomeCalendarDialog(
            multiSelect = multiSelect,
            markedDates = dates.toSet(),
            initialSelectedDate = selected,
            busy = controller.incomeLoading,
            onDismiss = { calendarMode = null },
            onSelectDate = { dateKey ->
                calendarMode = null
                controller.loadIncome(dateKey, controller.incomeHistoryDays)
            },
            onDeleteDates = { keys ->
                calendarMode = null
                controller.deleteIncome(keys.joinToString(","))
            },
            onDeleteAll = {
                calendarMode = null
                controller.deleteIncome(null)
            }
        )
    }
}

@Composable
private fun IncomeTile(
    item: IncomeItemData,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = incomeResourceMap[item.name]?.label ?: item.name
    val delta = item.delta
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = if(selected) BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IncomeIcon(item.name, size = 22.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    if(item.value >= 0) formatIncomeValue(item.value) else "--",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                if(delta != null && delta != 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${if(delta > 0) "↑ +" else "↓ "}${formatIncomeValue(abs(delta))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = incomeDeltaColor(delta),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun IncomeTrendSection(
    history: IncomeHistoryData?,
    days: Int,
    resource: String,
    loading: Boolean,
    onSelectDays: (Int) -> Unit,
    onSelectResource: (String) -> Unit
) {
    var rangeMenu by remember { mutableStateOf(false) }
    var resMenu by remember { mutableStateOf(false) }
    var touchedIndex by remember(history, resource) { mutableStateOf<Int?>(null) }

    val points = remember(history, resource) {
        val recs = history?.records.orEmpty()
        val firstDate = recs.firstOrNull()?.date?.let { try { LocalDate.parse(it) } catch(_: Exception) { null } }
        recs.mapNotNull { rec ->
            val v = rec.values[resource]
            if(v != null && v >= 0) {
                val d = try { LocalDate.parse(rec.date) } catch(_: Exception) { null }
                val offset = if(firstDate != null && d != null) ChronoUnit.DAYS.between(firstDate, d).toDouble() else 0.0
                Triple(offset, v.toDouble(), rec.date)
            } else null
        }
    }

    val firstVal = points.firstOrNull()?.second
    val lastVal = points.lastOrNull()?.second
    val changeVal = if(points.size >= 2 && firstVal != null && lastVal != null) lastVal - firstVal else null
    val minVal = points.minOfOrNull { it.second } ?: 0.0
    val maxVal = points.maxOfOrNull { it.second } ?: 0.0

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("变化趋势", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if(points.isNotEmpty()) {
                    Text(
                        "变化 ${formatIncomeSignedValue(changeVal)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = incomeDeltaColor(if((changeVal ?: 0.0) >= 0.0) 1 else -1)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(
                        onClick = { rangeMenu = true },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(formatIncomeRangeLabel(days), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(2.dp))
                        Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = rangeMenu, onDismissRequest = { rangeMenu = false }) {
                        incomeHistoryRanges.forEach { range ->
                            DropdownMenuItem(
                                text = { Text(formatIncomeRangeLabel(range)) },
                                onClick = { rangeMenu = false; onSelectDays(range) }
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(
                        onClick = { resMenu = true },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        IncomeIcon(resource, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(incomeResourceMap[resource]?.label ?: resource, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(2.dp))
                        Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = resMenu, onDismissRequest = { resMenu = false }) {
                        incomeResources.forEach { meta ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IncomeIcon(meta.key, size = 18.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(meta.label)
                                    }
                                },
                                onClick = { resMenu = false; onSelectResource(meta.key) }
                            )
                        }
                    }
                }
            }

            if(loading) {
                Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                    Text("加载趋势中…", style = MaterialTheme.typography.bodySmall)
                }
            } else if(points.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text("该时间范围内暂无记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val (axisMin, axisMax, axisStep) = niceIncomeAxis(minVal, maxVal)
                val axisSpan = maxVal - minVal
                val yTicks = remember(axisMin, axisMax, axisStep) {
                    val list = mutableListOf<Double>()
                    var cur = axisMin
                    while(cur <= axisMax + axisStep * 0.01 && list.size < 12) {
                        list.add(cur)
                        cur += axisStep
                    }
                    list
                }
                val primaryColor = MaterialTheme.colorScheme.primary
                val gridColor = MaterialTheme.colorScheme.outlineVariant
                val maxX = (points.last().first).coerceAtLeast(1.0)

                touchedIndex?.let { idx ->
                    points.getOrNull(idx)?.let { (_, value, dateKey) ->
                        Text(
                            "${formatIncomeDayLabel(dateKey)}  ${formatIncomeValue(value.roundToInt())}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryColor
                        )
                    }
                }

                Row(Modifier.fillMaxWidth().height(190.dp)) {
                    // Left Y-axis ticks (matching OASX leftTitles)
                    Column(
                        Modifier.width(52.dp).fillMaxHeight().padding(top = 6.dp, bottom = 22.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        val visibleTicks = yTicks.reversed()
                        visibleTicks.forEachIndexed { idx, v ->
                            val isEdge = idx == 0 || idx == visibleTicks.lastIndex
                            Text(
                                if(isEdge && visibleTicks.size > 2) "" else formatIncomeAxisValue(v, axisSpan),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Canvas(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(top = 6.dp, end = 12.dp)
                                .pointerInput(points) {
                                    detectTapGestures { offset ->
                                        val w = size.width.toFloat().coerceAtLeast(1f)
                                        val best = points.indices.minByOrNull { i ->
                                            val px = if(points.size == 1) w / 2f else (points[i].first / maxX).toFloat() * w
                                            abs(px - offset.x)
                                        }
                                        touchedIndex = best
                                    }
                                }
                        ) {
                            val w = size.width
                            val h = size.height
                            val yRange = (axisMax - axisMin).coerceAtLeast(1.0)
                            yTicks.forEach { tick ->
                                val norm = ((tick - axisMin) / yRange).toFloat()
                                val y = h - norm * h
                                drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                            }
                            val coords = points.map { (dayOffset, value, _) ->
                                val x = if(points.size == 1) w / 2f else (dayOffset / maxX).toFloat() * w
                                val y = h - ((value - axisMin) / yRange).toFloat() * h
                                Offset(x, y)
                            }
                            if(coords.size >= 2) {
                                val fillPath = Path().apply {
                                    moveTo(coords.first().x, h)
                                    coords.forEach { lineTo(it.x, it.y) }
                                    lineTo(coords.last().x, h)
                                    close()
                                }
                                drawPath(fillPath, color = primaryColor.copy(alpha = 0.12f))
                                val linePath = Path().apply {
                                    moveTo(coords.first().x, coords.first().y)
                                    coords.drop(1).forEach { lineTo(it.x, it.y) }
                                }
                                drawPath(linePath, color = primaryColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
                            }
                            coords.forEachIndexed { idx, pt ->
                                val focused = touchedIndex == idx
                                drawCircle(color = primaryColor, radius = if(focused) 8f else 5f, center = pt)
                            }
                        }
                        // Bottom X-axis labels
                        Row(
                            Modifier.fillMaxWidth().height(22.dp).padding(end = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(formatIncomeDayLabel(points.first().third), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if(points.size >= 3) {
                                val mid = points[points.size / 2]
                                Text(formatIncomeDayLabel(mid.third), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(points.size >= 2) {
                                Text(formatIncomeDayLabel(points.last().third), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // Bottom summary stats: 初始 / 最新 / 共 N天
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("初始 ${formatIncomeValue(firstVal?.roundToInt() ?: -1)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("最新 ${formatIncomeValue(lastVal?.roundToInt() ?: -1)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("共 ${points.size}天", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IncomeCalendarDialog(
    multiSelect: Boolean,
    markedDates: Set<String>,
    initialSelectedDate: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSelectDate: (String) -> Unit,
    onDeleteDates: (List<String>) -> Unit,
    onDeleteAll: () -> Unit
) {
    val initialMonth = remember(initialSelectedDate) {
        try { YearMonth.from(LocalDate.parse(initialSelectedDate)) } catch(_: Exception) { YearMonth.now() }
    }
    var month by remember { mutableStateOf(initialMonth) }
    var selectedForDelete by remember { mutableStateOf(emptySet<String>()) }
    var confirmAction by remember { mutableStateOf<String?>(null) }

    val todayKey = remember { LocalDate.now().toString() }
    val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
    val firstDay = month.atDay(1)
    val leading = firstDay.dayOfWeek.value - 1
    val totalDays = month.lengthOfMonth()
    val monthPrefix = month.toString()

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if(multiSelect) "管理收益记录" else "选择日期", style = MaterialTheme.typography.titleMedium)
                Text(
                    if(multiSelect) "点选带圆点的日期批量删除，或清空全部记录" else "带圆点的日期表示已有收益记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上个月")
                    }
                    Text(month.toString(), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下个月")
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    weekdays.forEach { wd ->
                        Text(wd, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                }
                val cells = buildList<Int?> {
                    repeat(leading) { add(null) }
                    for(d in 1..totalDays) add(d)
                    while(size % 7 != 0) add(null)
                }
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        week.forEach { dayNum ->
                            if(dayNum == null) {
                                Spacer(Modifier.weight(1f).height(38.dp))
                            } else {
                                val dateKey = month.atDay(dayNum).toString()
                                val marked = dateKey in markedDates
                                val isSelected = if(multiSelect) dateKey in selectedForDelete else dateKey == initialSelectedDate
                                val isToday = dateKey == todayKey
                                Surface(
                                    modifier = Modifier.weight(1f).height(38.dp).clickable(enabled = marked) {
                                        if(multiSelect) {
                                            selectedForDelete = if(dateKey in selectedForDelete) selectedForDelete - dateKey else selectedForDelete + dateKey
                                        } else {
                                            onSelectDate(dateKey)
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if(isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    border = if(isToday) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text(
                                            "$dayNum",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if(marked) FontWeight.SemiBold else FontWeight.Normal,
                                            color = when {
                                                !marked -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                                isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
                                                else -> MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                        Box(
                                            Modifier.size(4.dp).clip(CircleShape).background(
                                                if(marked) (if(isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary)
                                                else Color.Transparent
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if(multiSelect) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { selectedForDelete = selectedForDelete + markedDates.filter { it.startsWith(monthPrefix) } },
                            enabled = !busy
                        ) { Text("全选本月") }
                        TextButton(
                            onClick = { selectedForDelete = emptySet() },
                            enabled = !busy && selectedForDelete.isNotEmpty()
                        ) { Text("清除选择") }
                        Button(
                            onClick = { confirmAction = "selected" },
                            enabled = !busy && selectedForDelete.isNotEmpty()
                        ) { Text("删除所选 (${selectedForDelete.size})") }
                        TextButton(
                            onClick = { confirmAction = "all" },
                            enabled = !busy,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("清空全部") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    }
                }
            }
        }
    }

    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(if(action == "all") "清空全部收益记录" else "删除所选收益记录") },
            text = { Text(if(action == "all") "确定清空当前配置的全部收益记录吗？" else "确定删除所选 ${selectedForDelete.size} 天的收益记录吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmAction = null
                        if(action == "all") onDeleteAll() else onDeleteDates(selectedForDelete.toList())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("取消") } }
        )
    }
}

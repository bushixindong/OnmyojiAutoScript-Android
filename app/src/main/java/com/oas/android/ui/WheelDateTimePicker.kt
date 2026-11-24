// OASX date_time_picker.dart (DateMode.YMDHMS 6-wheel bottom sheet) ported to Jetpack Compose.
package com.oas.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OasxWheelDateTimeSheet(
    initial: LocalDateTime,
    title: String = "选择日期时间",
    onDismiss: () -> Unit,
    onConfirm: (LocalDateTime) -> Unit
) {
    val nowYear = remember { LocalDateTime.now().year }
    val years = remember(nowYear) { ((nowYear - 1)..(nowYear + 1)).toList() }
    val months = remember { (1..12).toList() }
    val hours = remember { (0..23).toList() }
    val minutes = remember { (0..59).toList() }
    val seconds = remember { (0..59).toList() }

    val clampedInitYear = initial.year.coerceIn(years.first(), years.last())
    var year by remember { mutableIntStateOf(clampedInitYear) }
    var month by remember { mutableIntStateOf(initial.monthValue) }
    val maxDay = remember(year, month) { YearMonth.of(year, month).lengthOfMonth() }
    val days = remember(maxDay) { (1..maxDay).toList() }
    var day by remember { mutableIntStateOf(initial.dayOfMonth.coerceIn(1, maxDay)) }
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    var second by remember { mutableIntStateOf(initial.second) }

    LaunchedEffect(maxDay) {
        if(day > maxDay) day = maxDay
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = null
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    onConfirm(LocalDateTime.of(year, month, day.coerceIn(1, maxDay), hour, minute, second))
                }) { Text("确定", fontWeight = FontWeight.SemiBold) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                // Center selection band (OASX picker highlight)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                            RoundedCornerShape(8.dp)
                        )
                )
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    WheelColumn(
                        items = years,
                        selected = year,
                        label = { "${it}年" },
                        onSelect = { year = it },
                        modifier = Modifier.weight(1.35f)
                    )
                    WheelColumn(
                        items = months,
                        selected = month,
                        label = { "%02d月".format(it) },
                        onSelect = { month = it },
                        modifier = Modifier.weight(1f)
                    )
                    WheelColumn(
                        items = days,
                        selected = day.coerceIn(1, maxDay),
                        label = { "%02d日".format(it) },
                        onSelect = { day = it },
                        modifier = Modifier.weight(1f)
                    )
                    WheelColumn(
                        items = hours,
                        selected = hour,
                        label = { "%02d时".format(it) },
                        onSelect = { hour = it },
                        modifier = Modifier.weight(1f)
                    )
                    WheelColumn(
                        items = minutes,
                        selected = minute,
                        label = { "%02d分".format(it) },
                        onSelect = { minute = it },
                        modifier = Modifier.weight(1f)
                    )
                    WheelColumn(
                        items = seconds,
                        selected = second,
                        label = { "%02d秒".format(it) },
                        onSelect = { second = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelColumn(
    items: List<Int>,
    selected: Int,
    label: (Int) -> String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val itemHeight = 40.dp
    val initialIndex = remember(items.size) { items.indexOf(selected).coerceAtLeast(0) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val fling = rememberSnapFlingBehavior(lazyListState = state)

    LaunchedEffect(items.size, selected) {
        val target = items.indexOf(selected)
        if(target >= 0 && !state.isScrollInProgress && state.firstVisibleItemIndex != target) {
            state.scrollToItem(target)
        }
    }

    val centeredIndex by remember {
        derivedStateOf {
            val idx = state.firstVisibleItemIndex
            val offset = state.firstVisibleItemScrollOffset
            (if(offset > 40) idx + 1 else idx).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(centeredIndex, items) {
        items.getOrNull(centeredIndex)?.let { value ->
            if(value != selected) onSelect(value)
        }
    }

    LazyColumn(
        state = state,
        flingBehavior = fling,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(items.size) { index ->
            val value = items[index]
            val isCenter = index == centeredIndex
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(itemHeight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(value),
                    fontSize = if(isCenter) 15.sp else 13.sp,
                    fontWeight = if(isCenter) FontWeight.SemiBold else FontWeight.Normal,
                    color = if(isCenter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.alpha(if(isCenter) 1f else 0.55f)
                )
            }
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay

@Composable
fun SystemSettings(controller: OasController) {
    val lifecycle = (LocalActivity.current as ComponentActivity).lifecycle
    LaunchedEffect(controller, controller.connected) {
        while(true) {
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.refreshSystem()
            delay(2000)
        }
    }
    OasSettingCard("运行与省电") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("自动熄屏、静音和保活选项在下次启动脚本时生效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SystemToggle("后台保活", "运行时保持 CPU 和运行屏活跃", controller.keepAwake) { controller.systemOption("keepAwake", it) }
            SystemToggle("自动熄屏", "后台模式运行脚本时关闭实体屏幕，结束后恢复", controller.autoPanelOff) { controller.systemOption("autoPanelOff", it) }
            SystemToggle("自动静音游戏", "仅静音当前游戏，脚本结束后恢复声音", controller.autoMute) { controller.systemOption("autoMute", it) }
            SystemToggle("帧率监测", "显示游戏帧率，持续低帧时给出提示", controller.fpsEnabled) { controller.systemOption("fpsEnabled", it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Text("任务取图上限", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15 to "省电 · 15", 30 to "均衡 · 30", 60 to "流畅 · 60").forEach { (fps, label) ->
                    FilterChip(selected = controller.captureFps == fps, onClick = { controller.frameRate(capture = fps) }, label = { Text(label) }, enabled = !controller.systemBusy)
                }
            }
            Text("实际取图频率由任务决定；更高上限会增加耗电和发热。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("实时预览帧率", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30, 60).forEach { fps -> FilterChip(selected = controller.previewFps == fps, onClick = { controller.frameRate(preview = fps) }, label = { Text("$fps FPS") }, enabled = !controller.systemBusy) }
            }
            if(controller.connected) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                val info = controller.systemInfo
                Text("运行屏幕：${info.optString("backend", "读取中…")}", style = MaterialTheme.typography.bodySmall)
                if(info.optBoolean("rawFrames")) Text("原始帧加速已启用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                val fps = info.optDouble("fps", -1.0)
                if(controller.fpsEnabled && fps >= 0) Text("${info.optString("fpsSource")}：${"%.1f".format(fps)} FPS", style = MaterialTheme.typography.bodySmall)
                val warning = listOf(info.optString("powerError"), info.optString("fpsAdvice")).filter { it.isNotBlank() }.joinToString("\n")
                if(warning.isNotBlank()) Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller.panelPower(!info.optBoolean("panelOff")) }, enabled = !controller.systemBusy && info.optInt("displayId", -1) > 0) {
                        Text(if(info.optBoolean("panelOff")) "点亮实体屏幕" else "熄灭实体屏幕")
                    }
                    OutlinedButton(onClick = controller::toggleGameAudio, enabled = !controller.systemBusy) {
                        Text(if(controller.gameMuted) "恢复游戏声音" else "静音游戏")
                    }
                }
            } else {
                Text("连接设备后可查看实际运行状态，并单独控制游戏声音和实体屏幕。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SystemToggle(title: String, description: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, change, modifier = Modifier.semantics { contentDescription = title })
    }
}

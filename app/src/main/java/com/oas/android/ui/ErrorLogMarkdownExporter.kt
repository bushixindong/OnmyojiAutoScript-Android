package com.oas.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

data class EmbeddedErrorImage(
    val name: String,
    val mimeType: String,
    val base64Data: String
)

object ErrorLogMarkdownExporter {
    private val INVALID_FILE_CHARS = Regex("[\\\\/:*?\"<>|\\s]+")

    fun sanitizeFilePart(raw: String, fallback: String): String {
        val cleaned = raw.trim()
            .replace(INVALID_FILE_CHARS, "_")
            .trim('_', '.')
            .take(48)
        return cleaned.ifBlank { fallback }
    }

    fun buildScreenshotFileName(preset: String, timestamp: String): String {
        val safePreset = sanitizeFilePart(preset, "android")
        val safeTime = sanitizeFilePart(timestamp, "capture")
        return "OASA_${safePreset}_${safeTime}.png"
    }

    fun buildErrorLogFileName(item: ErrorLogItem): String {
        val safeConfig = sanitizeFilePart(item.config, "android")
        val safeTask = sanitizeFilePart(item.task.ifBlank { item.taskLabel }, "error")
        val safeTime = sanitizeFilePart(item.timeText.ifBlank { item.id }, "log")
        return "OASA-error-${safeConfig}-${safeTask}-${safeTime}.md"
    }

    internal fun chooseCodeFence(content: String): String {
        var maxRun = 0
        var current = 0
        for(ch in content) {
            if(ch == '`') {
                current++
                if(current > maxRun) maxRun = current
            } else {
                current = 0
            }
        }
        return "`".repeat(maxOf(3, maxRun + 1))
    }

    fun buildMarkdown(
        detail: ErrorLogDetail,
        images: List<EmbeddedErrorImage>,
        appVersion: String,
        exportedAt: String,
        missingImageNames: List<String> = emptyList()
    ): String {
        val item = detail.item
        val titleTask = item.taskLabel.ifBlank { item.task.ifBlank { "未知任务" } }
        val sb = StringBuilder()
        sb.appendLine("# OASA 报错日志报告")
        sb.appendLine()
        sb.appendLine("- **客户端版本**：OASA $appVersion")
        sb.appendLine("- **配置名称**：`${item.config.ifBlank { "未知" }}`")
        sb.appendLine("- **报错任务**：$titleTask" + if(item.task.isNotBlank() && item.task != titleTask) " (`${item.task}`)" else "")
        sb.appendLine("- **发生时间**：${item.timeText.ifBlank { item.id }}")
        sb.appendLine("- **导出时间**：$exportedAt")
        if(item.summary.isNotBlank()) {
            sb.appendLine("- **异常摘要**：${item.summary.replace("\r\n", " ").replace("\n", " ").trim()}")
        }
        sb.appendLine("- **现场截图**：${images.size} 张")
        sb.appendLine()

        if(images.isNotEmpty() || missingImageNames.isNotEmpty()) {
            sb.appendLine("## 现场截图")
            sb.appendLine()
            images.forEachIndexed { index, img ->
                sb.appendLine("### 截图 ${index + 1} · `${img.name}`")
                sb.appendLine()
                sb.appendLine("![截图 ${index + 1} (${img.name})](data:${img.mimeType};base64,${img.base64Data})")
                sb.appendLine()
            }
            if(missingImageNames.isNotEmpty()) {
                sb.appendLine("> 以下截图文件未能读取：${missingImageNames.joinToString(", ") { "`$it`" }}")
                sb.appendLine()
            }
        }

        sb.appendLine("## 完整报错日志")
        sb.appendLine()
        val logBody = detail.logText.trimEnd()
        if(logBody.isBlank()) {
            sb.appendLine("_无日志内容_")
        } else {
            val fence = chooseCodeFence(logBody)
            sb.appendLine("${fence}text")
            sb.appendLine(logBody)
            sb.appendLine(fence)
        }
        return sb.toString()
    }

    fun encodeImageFile(path: String): EmbeddedErrorImage? {
        val file = File(path)
        if(!file.isFile || file.length() <= 0L) return null
        val name = file.name.ifBlank { "screenshot.png" }
        val bitmap = try { BitmapFactory.decodeFile(file.absolutePath) } catch(_: Throwable) { null }
        if(bitmap != null) {
            return try {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                EmbeddedErrorImage(name, "image/jpeg", encoded)
            } finally {
                bitmap.recycle()
            }
        }
        return try {
            val raw = file.readBytes()
            val lower = name.lowercase(Locale.ROOT)
            val mime = when {
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".webp") -> "image/webp"
                else -> "image/png"
            }
            EmbeddedErrorImage(name, mime, Base64.encodeToString(raw, Base64.NO_WRAP))
        } catch(_: Throwable) {
            null
        }
    }
}

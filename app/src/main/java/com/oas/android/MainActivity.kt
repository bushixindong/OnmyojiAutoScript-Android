package com.oas.android

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.oas.android.ui.OasAppScreen
import com.oas.android.ui.OasController
import java.io.InputStream
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    private lateinit var controller: OasController
    private var pendingImport: ((String, String) -> Unit)? = null
    private var pendingExport: ByteArray? = null
    private var pendingMarkdownExport: ByteArray? = null

    private val openJsonLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = pendingImport
        pendingImport = null
        if(uri != null && callback != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                    ?: error("无法读取所选文件")
                callback(displayNameOf(uri), text)
            } catch(problem: Throwable) {
                controller.notify("读取 JSON 失败：" + (problem.message ?: "未知错误"))
            }
        }
    }

    private val saveJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = pendingExport
        pendingExport = null
        if(uri != null && payload != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(payload) } ?: error("无法写入目标文件")
                controller.notify("配置已导出")
            } catch(problem: Throwable) {
                controller.notify("导出失败：" + (problem.message ?: "未知错误"))
            }
        }
    }

    private val saveMarkdownLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val payload = pendingMarkdownExport
        pendingMarkdownExport = null
        if(uri != null && payload != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(payload) } ?: error("无法写入目标文件")
                controller.notify("报错日志 MD 已导出")
            } catch(problem: Throwable) {
                controller.notify("导出 MD 失败：" + (problem.message ?: "未知错误"))
            }
        }
    }

    private fun displayNameOf(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if(cursor.moveToFirst()) return cursor.getString(0) ?: "config.json"
        }
        return uri.lastPathSegment ?: "config.json"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(BuildConfig.DEBUG) when(intent.getStringExtra("uiTestOrientation")) {
            "portrait" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        enableEdgeToEdge()
        controller = OasController(this).apply {
            onPickJsonFile = { callback ->
                pendingImport = callback
                openJsonLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            onSaveJsonFile = { fileName, bytes ->
                pendingExport = bytes
                saveJsonLauncher.launch(fileName)
            }
            onSaveMarkdownFile = { fileName, bytes ->
                pendingMarkdownExport = bytes
                saveMarkdownLauncher.launch(fileName)
            }
        }
        setContent { OasAppScreen(controller) }
    }

    override fun onResume() {
        super.onResume()
        FloatingOverlayController.collapse()
        if(::controller.isInitialized) controller.onResume()
    }
    override fun onDestroy() {
        if (!isChangingConfigurations) FloatingOverlayController.hide()
        if(::controller.isInitialized) controller.dispose()
        super.onDestroy()
    }
    companion object { @JvmStatic fun readText(input: InputStream): String = ToolsActivity.readText(input) }
}

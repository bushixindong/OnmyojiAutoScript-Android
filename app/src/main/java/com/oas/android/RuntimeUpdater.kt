// MAA-style script hot-updater & release checker for OASA.
package com.oas.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

data class LocalRuntimeInfo(val version: String, val sha256: String)

data class RemoteUpdateInfo(
    val runtimeVersion: String,
    val runtimeArchiveUrl: String,
    val runtimeSha256: String,
    val runtimeManifestUrl: String,
    val runtimeNotes: String,
    val appVersion: String,
    val appVersionCode: Int,
    val appDownloadUrl: String,
    val appNotes: String,
    val hasRuntimeUpdate: Boolean,
    val hasAppUpdate: Boolean
)

object RuntimeUpdater {
    const val DEFAULT_MANIFEST_URL =
        "https://github.com/bushixindong/OnmyojiAutoScript-Android/releases/latest/download/update.json"

    fun localRuntimeInfo(context: Context): LocalRuntimeInfo {
        val root = File(context.filesDir, "oas-engine")
        val hot = File(root, ".hot-update.json")
        if(hot.isFile) {
            try {
                val obj = JSONObject(hot.readText(Charsets.UTF_8))
                val sha = obj.optString("archiveSha256", "").trim()
                val ver = obj.optString("runtimeVersion", "").trim()
                if(sha.isNotEmpty()) return LocalRuntimeInfo(ver.ifBlank { "已热更" }, sha)
            } catch(_: Exception) { }
        }
        val marker = File(root, ".source-sha256")
        val sha = if(marker.isFile) try { marker.readText(Charsets.UTF_8).trim() } catch(_: Exception) { "" } else ""
        val fallbackSha = if(sha.isNotEmpty()) sha else try {
            context.assets.open("runtime/manifest.json").use { JSONObject(it.readBytes().decodeToString()).optString("archiveSha256", "") }
        } catch(_: Exception) { "" }
        return LocalRuntimeInfo("内置 (${BuildConfig.VERSION_NAME})", fallbackSha)
    }

    private fun applyMirror(rawUrl: String, mirrorPrefix: String): String {
        val url = rawUrl.trim()
        val prefix = mirrorPrefix.trim()
        if(prefix.isEmpty() || !url.startsWith("https://github.com/")) return url
        return if(prefix.endsWith("/")) "$prefix$url" else "$prefix/$url"
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "OASA/${BuildConfig.VERSION_NAME}")
        return conn
    }

    fun checkUpdate(context: Context, manifestUrl: String, mirrorPrefix: String): RemoteUpdateInfo {
        val target = applyMirror(manifestUrl.ifBlank { DEFAULT_MANIFEST_URL }, mirrorPrefix)
        val conn = openConnection(target)
        try {
            val code = conn.responseCode
            if(code !in 200..299) throw IllegalStateException("获取更新信息失败（HTTP $code）")
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            val json = JSONObject(body)
            val local = localRuntimeInfo(context)
            val rtVer = json.optString("runtimeVersion", "").trim()
            val rtUrl = json.optString("runtimeArchiveUrl", "").trim()
            val rtSha = json.optString("runtimeSha256", "").trim().lowercase(Locale.ROOT)
            val rtManifestUrl = json.optString("runtimeManifestUrl", "").trim()
            val rtNotes = json.optString("runtimeNotes", "").trim()
            val appVer = json.optString("appVersion", "").trim()
            val appCode = json.optInt("appVersionCode", 0)
            val appUrl = json.optString("appDownloadUrl", "https://github.com/bushixindong/OnmyojiAutoScript-Android/releases/latest").trim()
            val appNotes = json.optString("appNotes", "").trim()
            val hasRt = rtUrl.isNotEmpty() && rtSha.length == 64 && !rtSha.equals(local.sha256, ignoreCase = true)
            val hasApp = appCode > BuildConfig.VERSION_CODE
            return RemoteUpdateInfo(rtVer, rtUrl, rtSha, rtManifestUrl, rtNotes, appVer, appCode, appUrl, appNotes, hasRt, hasApp)
        } finally {
            conn.disconnect()
        }
    }

    fun applyRuntimeUpdate(
        context: Context,
        info: RemoteUpdateInfo,
        mirrorPrefix: String,
        onProgress: (Float, String) -> Unit
    ) {
        if(info.runtimeArchiveUrl.isBlank() || info.runtimeSha256.length != 64) {
            throw IllegalArgumentException("更新清单缺少有效的脚本包地址或 SHA-256")
        }
        val tempZip = File(context.cacheDir, "oas-runtime-update.zip")
        val root = File(context.filesDir, "oas-engine")
        try {
            onProgress(0f, "正在下载脚本更新包…")
            val conn = openConnection(applyMirror(info.runtimeArchiveUrl, mirrorPrefix))
            try {
                val code = conn.responseCode
                if(code !in 200..299) throw IllegalStateException("下载脚本包失败（HTTP $code）")
                val total = conn.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    FileOutputStream(tempZip).use { output ->
                        val buf = ByteArray(16384)
                        var copied = 0L
                        while(true) {
                            val n = input.read(buf)
                            if(n < 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            copied += n
                            if(total > 0) {
                                val pct = (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                onProgress(pct * 0.8f, "正在下载 ${(pct * 100).toInt()}%")
                            }
                        }
                    }
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
                if(!actualSha.equals(info.runtimeSha256, ignoreCase = true)) {
                    throw SecurityException("脚本包 SHA-256 校验失败，已取消更新")
                }
            } finally {
                conn.disconnect()
            }

            // Optional remote manifest.json (contains updated task list if upstream added tasks)
            val remoteManifest = if(info.runtimeManifestUrl.isNotBlank()) {
                try {
                    val mConn = openConnection(applyMirror(info.runtimeManifestUrl, mirrorPrefix))
                    try {
                        if(mConn.responseCode in 200..299) mConn.inputStream.use { it.readBytes().decodeToString() } else null
                    } finally { mConn.disconnect() }
                } catch(_: Exception) { null }
            } else null

            onProgress(0.85f, "正在校验并应用脚本文件…")
            root.mkdirs()
            val prefix = root.canonicalPath + File.separator
            ZipInputStream(FileInputStream(tempZip)).use { zip ->
                var entry = zip.nextEntry
                while(entry != null) {
                    val name = entry.name
                    if(!name.startsWith("config/") && !name.startsWith("log/")) {
                        val target = File(root, name)
                        if(!target.canonicalPath.startsWith(prefix)) throw SecurityException("压缩包路径越界：$name")
                        if(entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                val buf = ByteArray(16384)
                                while(true) {
                                    val n = zip.read(buf)
                                    if(n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            if(!remoteManifest.isNullOrBlank()) {
                File(root, "runtime-manifest.json").writeText(remoteManifest, Charsets.UTF_8)
            }
            File(root, ".source-sha256").writeText(info.runtimeSha256, Charsets.US_ASCII)
            val hotMeta = JSONObject()
                .put("runtimeVersion", info.runtimeVersion.ifBlank { info.runtimeSha256.take(8) })
                .put("archiveSha256", info.runtimeSha256)
                .put("baseApkVersionCode", BuildConfig.VERSION_CODE)
            File(root, ".hot-update.json").writeText(hotMeta.toString(2), Charsets.UTF_8)
            onProgress(1f, "脚本已更新到 ${info.runtimeVersion.ifBlank { info.runtimeSha256.take(8) }}，重启应用后生效")
        } finally {
            if(tempZip.exists()) tempZip.delete()
        }
    }
}

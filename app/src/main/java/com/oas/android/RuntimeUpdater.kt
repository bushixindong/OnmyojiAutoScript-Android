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
    val hasAppUpdate: Boolean,
    val usedFallback: Boolean = false
)

object RuntimeUpdater {
    const val DEFAULT_MANIFEST_URL =
        "https://github.com/kongdr418/OnmyojiAutoScript-Android/releases/latest/download/update.json"
    const val FALLBACK_SITE_BASE = "https://kongdr.bbs0.cc"
    const val FALLBACK_MANIFEST_URL = "$FALLBACK_SITE_BASE/update.json"
    const val FALLBACK_RUNTIME_ARCHIVE_URL = "$FALLBACK_SITE_BASE/downloads/oas-runtime.zip"
    const val FALLBACK_RUNTIME_MANIFEST_URL = "$FALLBACK_SITE_BASE/downloads/manifest.json"

    private const val PRIMARY_CONNECT_TIMEOUT_MS = 8_000
    private const val PRIMARY_READ_TIMEOUT_MS = 12_000
    private const val FALLBACK_CONNECT_TIMEOUT_MS = 15_000
    private const val FALLBACK_READ_TIMEOUT_MS = 30_000
    private const val SLOW_CHECK_WARMUP_MS = 5_000L
    private const val SLOW_WINDOW_MS = 4_000L
    private const val MIN_ACCEPTABLE_BYTES_PER_SEC = 40L * 1024L

    internal class SlowDownloadException(message: String) : Exception(message)

    internal fun parseComparableVersion(version: String): List<Long>? {
        val trimmed = version.trim().removePrefix("v").removePrefix("V")
        if(trimmed.isEmpty()) return null
        val parts = trimmed.split('.')
        if(parts.size < 2) return null
        val nums = ArrayList<Long>(parts.size)
        for(part in parts) {
            if(part.isEmpty() || !part.all { it.isDigit() }) return null
            nums.add(part.toLongOrNull() ?: return null)
        }
        return nums
    }

    internal fun compareVersions(a: List<Long>, b: List<Long>): Int {
        val maxLen = maxOf(a.size, b.size)
        for(i in 0 until maxLen) {
            val av = a.getOrElse(i) { 0L }
            val bv = b.getOrElse(i) { 0L }
            if(av != bv) return av.compareTo(bv)
        }
        return 0
    }

    internal fun isFallbackSiteUrl(url: String): Boolean {
        val lower = url.trim().lowercase(Locale.ROOT)
        val base = FALLBACK_SITE_BASE.lowercase(Locale.ROOT)
        return lower == base || lower.startsWith("$base/")
    }

    internal fun shouldProtectAgainstDowngrade(effectiveManifestUrl: String, autoSwitchedToFallback: Boolean): Boolean {
        return autoSwitchedToFallback || isFallbackSiteUrl(effectiveManifestUrl)
    }

    internal fun isAcceptableRuntimeVersion(localVersion: String, remoteVersion: String, protectDowngrade: Boolean): Boolean {
        if(!protectDowngrade) return true
        val localParts = parseComparableVersion(localVersion) ?: return true
        val remoteParts = parseComparableVersion(remoteVersion) ?: return false
        return compareVersions(remoteParts, localParts) > 0
    }

    internal fun isManifestArchiveShaMatched(manifestArchiveSha: String?, expectedSha256: String): Boolean {
        val actual = manifestArchiveSha?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val expected = expectedSha256.trim().lowercase(Locale.ROOT)
        return actual.length == 64 && actual == expected
    }

    internal fun isValidManifestMeta(manifestArchiveSha: String?, expectedSha256: String, taskEntries: List<Any?>): Boolean {
        if(!isManifestArchiveShaMatched(manifestArchiveSha, expectedSha256)) return false
        if(taskEntries.isEmpty()) return false
        return taskEntries.all { it is String && it.trim().isNotEmpty() }
    }

    internal fun validateManifestForArchive(rawBody: String, expectedSha256: String): String? {
        return try {
            val obj = JSONObject(rawBody)
            val tasksArray = obj.optJSONArray("tasks") ?: return null
            val taskEntries = ArrayList<Any?>(tasksArray.length())
            for(i in 0 until tasksArray.length()) {
                taskEntries.add(tasksArray.opt(i))
            }
            if(isValidManifestMeta(obj.optString("archiveSha256", ""), expectedSha256, taskEntries)) rawBody else null
        } catch(_: Throwable) {
            null
        }
    }

    internal fun executeDownloadWithFallback(
        canFallback: Boolean,
        tryPrimary: (abortOnSlow: Boolean) -> Unit,
        tryFallback: () -> Unit,
        onSwitchingToFallback: () -> Unit,
        onRetryingPrimary: (fallbackError: String) -> Unit
    ): Boolean {
        return try {
            tryPrimary(canFallback)
            false
        } catch(primaryErr: Throwable) {
            if(!canFallback || primaryErr is SecurityException) throw primaryErr
            onSwitchingToFallback()
            try {
                tryFallback()
                true
            } catch(fallbackErr: Throwable) {
                val fallbackReason = fallbackErr.message?.takeIf { it.isNotBlank() } ?: fallbackErr.javaClass.simpleName
                if(primaryErr is SlowDownloadException) {
                    onRetryingPrimary(fallbackReason)
                    try {
                        tryPrimary(false)
                        false
                    } catch(retryErr: Throwable) {
                        val retryReason = retryErr.message?.takeIf { it.isNotBlank() } ?: retryErr.javaClass.simpleName
                        throw IllegalStateException("备用源不可用（$fallbackReason），继续主源下载失败（$retryReason）", retryErr)
                    }
                } else {
                    val primaryReason = primaryErr.message?.takeIf { it.isNotBlank() } ?: primaryErr.javaClass.simpleName
                    throw IllegalStateException("主源下载失败（$primaryReason）；备用源也失败（$fallbackReason）", primaryErr)
                }
            }
        }
    }

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

    private fun isOfficialGithubUrl(url: String): Boolean {
        val lower = url.trim().lowercase(Locale.ROOT)
        return lower.contains("github.com/kongdr418/onmyojiautoscript-android/") ||
            lower.contains("objects.githubusercontent.com/")
    }

    private fun openConnection(url: String, connectTimeoutMs: Int = PRIMARY_CONNECT_TIMEOUT_MS, readTimeoutMs: Int = PRIMARY_READ_TIMEOUT_MS): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "OASA/${BuildConfig.VERSION_NAME}")
        return conn
    }

    private fun fetchManifestJson(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): Pair<JSONObject, String> {
        val conn = openConnection(url, connectTimeoutMs, readTimeoutMs)
        try {
            val code = conn.responseCode
            if(code !in 200..299) throw IllegalStateException("获取更新信息失败（HTTP $code）")
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            val finalUrl = conn.url?.toString().orEmpty().ifBlank { url }
            return JSONObject(body) to finalUrl
        } finally {
            conn.disconnect()
        }
    }

    fun checkUpdate(context: Context, manifestUrl: String, mirrorPrefix: String): RemoteUpdateInfo {
        val rawManifest = manifestUrl.ifBlank { DEFAULT_MANIFEST_URL }
        val primaryTarget = applyMirror(rawManifest, mirrorPrefix)
        val canFallback = isOfficialGithubUrl(rawManifest) && !primaryTarget.startsWith(FALLBACK_SITE_BASE, ignoreCase = true)

        var usedFallback = false
        val (json, effectiveManifestUrl) = try {
            fetchManifestJson(
                primaryTarget,
                if(canFallback) PRIMARY_CONNECT_TIMEOUT_MS else FALLBACK_CONNECT_TIMEOUT_MS,
                if(canFallback) PRIMARY_READ_TIMEOUT_MS else FALLBACK_READ_TIMEOUT_MS
            )
        } catch(primaryErr: Throwable) {
            if(!canFallback) throw primaryErr
            usedFallback = true
            try {
                fetchManifestJson(FALLBACK_MANIFEST_URL, FALLBACK_CONNECT_TIMEOUT_MS, FALLBACK_READ_TIMEOUT_MS)
            } catch(fallbackErr: Throwable) {
                val primaryReason = primaryErr.message?.takeIf { it.isNotBlank() } ?: primaryErr.javaClass.simpleName
                val fallbackReason = fallbackErr.message?.takeIf { it.isNotBlank() } ?: fallbackErr.javaClass.simpleName
                throw IllegalStateException("主源检查更新失败（$primaryReason）；备用源也失败（$fallbackReason）", primaryErr)
            }
        }

        val protectDowngrade = shouldProtectAgainstDowngrade(effectiveManifestUrl, usedFallback)

        val local = localRuntimeInfo(context)
        val rtVer = json.optString("runtimeVersion", "").trim()
        val rtUrl = json.optString("runtimeArchiveUrl", "").trim()
        val rtSha = json.optString("runtimeSha256", "").trim().lowercase(Locale.ROOT)
        val rtManifestUrl = json.optString("runtimeManifestUrl", "").trim()
        val rtNotes = json.optString("runtimeNotes", "").trim()
        val appVer = json.optString("appVersion", "").trim()
        val appCode = json.optInt("appVersionCode", 0)
        val appUrl = json.optString("appDownloadUrl", "https://github.com/kongdr418/OnmyojiAutoScript-Android/releases/latest").trim()
        val appNotes = json.optString("appNotes", "").trim()
        val hasRt = rtUrl.isNotEmpty() &&
            rtManifestUrl.isNotEmpty() &&
            rtSha.length == 64 &&
            !rtSha.equals(local.sha256, ignoreCase = true) &&
            isAcceptableRuntimeVersion(local.version, rtVer, protectDowngrade)
        val hasApp = appCode > BuildConfig.VERSION_CODE
        return RemoteUpdateInfo(rtVer, rtUrl, rtSha, rtManifestUrl, rtNotes, appVer, appCode, appUrl, appNotes, hasRt, hasApp, usedFallback)
    }

    private fun downloadRuntimeZip(
        url: String,
        dest: File,
        expectedSha256: String,
        abortOnSlow: Boolean,
        labelPrefix: String,
        onProgress: (Float, String) -> Unit
    ) {
        val conn = openConnection(
            url,
            if(abortOnSlow) PRIMARY_CONNECT_TIMEOUT_MS else FALLBACK_CONNECT_TIMEOUT_MS,
            if(abortOnSlow) PRIMARY_READ_TIMEOUT_MS else FALLBACK_READ_TIMEOUT_MS
        )
        try {
            val code = conn.responseCode
            if(code !in 200..299) throw IllegalStateException("下载脚本包失败（HTTP $code）")
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            val startedAt = System.nanoTime()
            var windowStart = startedAt
            var windowBytes = 0L
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(16384)
                    var copied = 0L
                    while(true) {
                        val n = input.read(buf)
                        if(n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        copied += n
                        windowBytes += n
                        val now = System.nanoTime()
                        if(abortOnSlow) {
                            val totalElapsedMs = (now - startedAt) / 1_000_000L
                            val windowElapsedMs = (now - windowStart) / 1_000_000L
                            if(totalElapsedMs >= SLOW_CHECK_WARMUP_MS && windowElapsedMs >= SLOW_WINDOW_MS) {
                                val bytesPerSec = windowBytes * 1000L / windowElapsedMs.coerceAtLeast(1L)
                                if(bytesPerSec < MIN_ACCEPTABLE_BYTES_PER_SEC) {
                                    throw SlowDownloadException("主源下载过慢（${bytesPerSec / 1024} KB/s）")
                                }
                                windowStart = now
                                windowBytes = 0L
                            }
                        }
                        if(total > 0) {
                            val pct = (copied.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            onProgress(pct * 0.8f, "$labelPrefix ${(pct * 100).toInt()}%")
                        }
                    }
                }
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
            if(!actualSha.equals(expectedSha256, ignoreCase = true)) {
                throw SecurityException("脚本包 SHA-256 校验失败，已取消更新")
            }
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
        if(info.runtimeManifestUrl.isBlank()) {
            throw IllegalArgumentException("更新清单缺少有效的任务清单地址（runtimeManifestUrl），已取消更新以保留当前安装")
        }
        val tempZip = File(context.cacheDir, "oas-runtime-update.zip")
        val root = File(context.filesDir, "oas-engine")
        try {
            val primaryArchiveUrl = applyMirror(info.runtimeArchiveUrl, mirrorPrefix)
            val canFallbackArchive = isOfficialGithubUrl(info.runtimeArchiveUrl) &&
                !primaryArchiveUrl.startsWith(FALLBACK_SITE_BASE, ignoreCase = true)

            onProgress(0f, "正在下载脚本更新包…")
            val switchedToFallback = executeDownloadWithFallback(
                canFallback = canFallbackArchive,
                tryPrimary = { abortOnSlow ->
                    if(tempZip.exists()) tempZip.delete()
                    downloadRuntimeZip(
                        url = primaryArchiveUrl,
                        dest = tempZip,
                        expectedSha256 = info.runtimeSha256,
                        abortOnSlow = abortOnSlow,
                        labelPrefix = if(info.usedFallback) "备用源下载中" else "正在下载",
                        onProgress = onProgress
                    )
                },
                tryFallback = {
                    if(tempZip.exists()) tempZip.delete()
                    downloadRuntimeZip(
                        url = FALLBACK_RUNTIME_ARCHIVE_URL,
                        dest = tempZip,
                        expectedSha256 = info.runtimeSha256,
                        abortOnSlow = false,
                        labelPrefix = "备用源下载中",
                        onProgress = onProgress
                    )
                },
                onSwitchingToFallback = {
                    onProgress(0f, "GitHub 超时或较慢，自动切换备用高速源…")
                },
                onRetryingPrimary = { fallbackReason ->
                    onProgress(0f, "备用源不可用（$fallbackReason），继续从主源下载…")
                }
            )
            val usedFallback = info.usedFallback || switchedToFallback

            // Validate remote manifest.json (archiveSha256 + non-empty tasks) before touching existing installation
            val primaryManifestUrl = applyMirror(info.runtimeManifestUrl, mirrorPrefix)
            val manifestCandidates = buildList {
                if(usedFallback && isOfficialGithubUrl(info.runtimeManifestUrl)) {
                    add(FALLBACK_RUNTIME_MANIFEST_URL)
                    add(primaryManifestUrl)
                } else {
                    add(primaryManifestUrl)
                    if(isOfficialGithubUrl(info.runtimeManifestUrl)) add(FALLBACK_RUNTIME_MANIFEST_URL)
                }
            }.distinct()
            var remoteManifest: String? = null
            for(candidate in manifestCandidates) {
                try {
                    val mConn = openConnection(candidate, PRIMARY_CONNECT_TIMEOUT_MS, PRIMARY_READ_TIMEOUT_MS)
                    try {
                        if(mConn.responseCode in 200..299) {
                            val raw = mConn.inputStream.use { it.readBytes().decodeToString() }
                            val valid = validateManifestForArchive(raw, info.runtimeSha256)
                            if(valid != null) {
                                remoteManifest = valid
                                break
                            }
                        }
                    } finally { mConn.disconnect() }
                } catch(_: Exception) { }
            }
            val validatedManifest = remoteManifest
                ?: throw IllegalStateException("无法获取与脚本包匹配的有效任务清单（缺少 tasks 或 archiveSha256 不一致），已取消更新以保留当前安装")

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
            File(root, "runtime-manifest.json").writeText(validatedManifest, Charsets.UTF_8)
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

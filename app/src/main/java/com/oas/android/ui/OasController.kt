package com.oas.android.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.*
import com.chaquo.python.PyObject
import com.oas.android.*
import com.oas.android.engine.PythonHost
import com.oas.android.engine.TaskEngine
import com.oas.android.engine.getTaskSummary
import com.oas.android.engine.putTaskSummary
import com.oas.android.engine.removeTaskSummary
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

data class TaskItem(val id: String, val title: String, val enabled: Boolean, val runnable: Boolean,
    val deferred: Boolean, val schedulable: Boolean, val nextRun: String,
    val groupId: String, val category: String, val running: Boolean, val preset: String)

data class PresetItem(val name: String, val active: Boolean, val running: Boolean,
    val previewType: String, val previewId: String, val previewTitle: String, val previewTime: String)

data class ErrorLogItem(val id: String, val timeText: String, val config: String,
    val task: String, val taskLabel: String, val summary: String, val imageCount: Int)

data class ErrorLogDetail(val item: ErrorLogItem, val logText: String, val imagePaths: List<String>)

data class StatsTaskRun(val startTime: String, val endTime: String, val durationSeconds: Double, val battleCount: Int, val battleAvgDurationSeconds: Double)
data class StatsTaskSummary(val taskName: String, val runCount: Int, val totalDurationSeconds: Double, val battleCount: Int, val battleAvgDurationSeconds: Double, val runs: List<StatsTaskRun>)
data class StatsDayData(val scriptName: String, val date: String, val totalRuntimeSeconds: Double, val totalTaskRunCount: Int, val totalBattleCount: Int, val tasks: List<StatsTaskSummary>)

data class IncomeItemData(val name: String, val value: Int, val delta: Int?)
data class IncomeDayData(val scriptName: String, val date: String, val collectedAt: String, val baselineDate: String, val items: List<IncomeItemData>)
data class IncomeHistoryRecordData(val date: String, val values: Map<String, Int>)
data class IncomeHistoryData(val scriptName: String, val days: Int, val start: String, val end: String, val records: List<IncomeHistoryRecordData>)

enum class ShizukuStage { NOT_INSTALLED, NOT_RUNNING, NEED_AUTH, READY, CONNECTED }

/** UI adapter only: settings and task decisions continue to belong to the OAS engine. */
class OasController(private val activity: Activity) {
    val session = (activity.application as OasApp).session
    private val preferences = activity.getSharedPreferences("MainActivity", 0)
    private val executor = Executors.newSingleThreadExecutor()
    private var disposed = false
    private var refreshQueued = false
    var tasks by mutableStateOf<List<TaskItem>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    var message by mutableStateOf(session.message); private set
    var logLines by mutableStateOf<List<String>>(emptyList()); private set
    var active by mutableStateOf(session.taskActive.get()); private set
    var stopping by mutableStateOf(false); private set
    var connected by mutableStateOf(session.bridge != null); private set
    var shizukuStage by mutableStateOf(detectShizukuStage()); private set
    var shizukuBinding by mutableStateOf(session.binding); private set
    var environmentReady by mutableStateOf(false); private set
    var screenLabel by mutableStateOf("未创建"); private set
    private var knownBridge: android.os.IBinder? = null
    private var knownStateVersion = -1
    var busy by mutableStateOf(false); private set
    var editorBusy by mutableStateOf(false); private set
    var systemBusy by mutableStateOf(false); private set
    var systemInfo by mutableStateOf(JSONObject()); private set
    var keepAwake by mutableStateOf(preferences.getBoolean("keepAwake",true)); private set
    var autoPanelOff by mutableStateOf(preferences.getBoolean("autoPanelOff",false)); private set
    var autoMute by mutableStateOf(preferences.getBoolean("autoMute",false)); private set
    var fpsEnabled by mutableStateOf(preferences.getBoolean("fpsEnabled",true)); private set
    var floatingBallEnabled by mutableStateOf(preferences.getBoolean("floatingBallEnabled", true)); private set
    var floatingBallVisible by mutableStateOf(com.oas.android.FloatingOverlayController.isShowing()); private set
    var overlayGranted by mutableStateOf(com.oas.android.FloatingOverlayController.canDrawOverlays(activity)); private set
    private var autoShownOverlayOnce = false
    private val overlayListener = Runnable { ui { floatingBallVisible = com.oas.android.FloatingOverlayController.isShowing() } }
    var captureFps by mutableIntStateOf(preferences.getInt("captureFps",30)); private set
    var previewFps by mutableIntStateOf(preferences.getInt("previewFps",30)); private set
    var gameMuted by mutableStateOf(false); private set
    private var refreshingSystem=false
    var pending by mutableStateOf(setOf<String>()); private set
    var bulkMode by mutableStateOf("none"); private set
    var deviceEpoch by mutableIntStateOf(0); private set
    var definition by mutableStateOf<JSONObject?>(null); private set
    var labels by mutableStateOf(JSONObject()); private set
    var editorError by mutableStateOf(""); private set
    var editing by mutableStateOf(""); private set
    private var editorPreset = ""
    var mode by mutableStateOf(preferences.getBoolean("primaryMode", session.primaryMode)); private set
    var dpi by mutableIntStateOf(preferences.getInt("dpi", 240)); private set
    var appearance by mutableStateOf(preferences.getString("appearance", "system") ?: "system"); private set
    var gamePackage by mutableStateOf(preferences.getString("package", detectPackage()) ?: detectPackage()); private set
    var activePreset by mutableStateOf(preferences.getString("activePreset", "android") ?: "android"); private set
    var presets by mutableStateOf<List<PresetItem>>(emptyList()); private set
    var presetBusy by mutableStateOf(false); private set
    var errorLogs by mutableStateOf<List<ErrorLogItem>>(emptyList()); private set
    var selectedError by mutableStateOf<ErrorLogDetail?>(null); private set
    var errorLogsLoading by mutableStateOf(false); private set
    var statsDates by mutableStateOf<List<String>>(emptyList()); private set
    var statsSelectedDate by mutableStateOf(""); private set
    var statsDay by mutableStateOf<StatsDayData?>(null); private set
    var statsLoading by mutableStateOf(false); private set
    var statsError by mutableStateOf(""); private set
    var incomeDates by mutableStateOf<List<String>>(emptyList()); private set
    var incomeSelectedDate by mutableStateOf(""); private set
    var incomeDay by mutableStateOf<IncomeDayData?>(null); private set
    var incomeHistory by mutableStateOf<IncomeHistoryData?>(null); private set
    var incomeHistoryDays by mutableIntStateOf(30); private set
    var incomeLoading by mutableStateOf(false); private set
    var incomeError by mutableStateOf(""); private set
    var localRuntimeVersion by mutableStateOf(RuntimeUpdater.localRuntimeInfo(activity).version); private set
    var updateManifestUrl by mutableStateOf(preferences.getString("updateManifestUrl", RuntimeUpdater.DEFAULT_MANIFEST_URL) ?: RuntimeUpdater.DEFAULT_MANIFEST_URL); private set
    var updateMirrorPrefix by mutableStateOf(preferences.getString("updateMirrorPrefix", "") ?: ""); private set
    var remoteUpdate by mutableStateOf<RemoteUpdateInfo?>(null); private set
    var updateBusy by mutableStateOf(false); private set
    var updateProgress by mutableFloatStateOf(0f); private set
    var updateStatus by mutableStateOf(""); private set
    var updateNeedsRestart by mutableStateOf(false); private set
    var lastResult by mutableStateOf(activity.getSharedPreferences("task_records", 0).getString("last", "") ?: ""); private set
    var onPickJsonFile: (((String, String) -> Unit) -> Unit)? = null
    var onSaveJsonFile: ((String, ByteArray) -> Unit)? = null
    private val listener = Runnable { sync() }

    init {
        session.addListener(listener)
        com.oas.android.FloatingOverlayController.addVisibilityListener(overlayListener)
        tasks = cachedTasks(activePreset)
        val cachedPresets = preferences.getString("presetSummary", null)
        presets = try {
            if(cachedPresets != null) parsePresets(cachedPresets).map {
                if(!session.taskActive.get()) it.copy(running = false) else it
            }.ifEmpty {
                listOf(PresetItem(activePreset, active = true, running = false, previewType = "none", previewId = "", previewTitle = "", previewTime = ""))
            } else {
                listOf(PresetItem(activePreset, active = true, running = false, previewType = "none", previewId = "", previewTitle = "", previewTime = ""))
            }
        } catch(_: Exception) {
            listOf(PresetItem(activePreset, active = true, running = false, previewType = "none", previewId = "", previewTitle = "", previewTime = ""))
        }
    }

    private fun ui(action: () -> Unit) { activity.runOnUiThread { if(!disposed) action() } }
    private fun engine(): PyObject = TaskEngine.initialize(activity, PythonHost(session, gamePackage))
    private fun selectedPreset() = preferences.getString("activePreset", "android") ?: "android"
    private fun cachedTasks(preset: String) =
        runCatching { preferences.getTaskSummary(preset)?.let { parseTasks(it, preset) } }.getOrNull().orEmpty()

    private fun syncSelectedPreset() {
        val selected = selectedPreset()
        if (selected == activePreset) return
        activePreset = selected
        tasks = cachedTasks(selected)
    }

    private fun displayTasks(preset: String, values: List<TaskItem>) {
        if (selectedPreset() == preset) {
            activePreset = preset
            tasks = values
        }
    }
    private fun parsePresets(raw: String): List<PresetItem> {
        val values = JSONArray(raw)
        return (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            val preview = item.optJSONObject("preview") ?: JSONObject()
            PresetItem(
                name = item.getString("name"),
                active = item.optBoolean("active"),
                running = item.optBoolean("running"),
                previewType = preview.optString("type", "none"),
                previewId = preview.optString("id", ""),
                previewTitle = preview.optString("title", ""),
                previewTime = preview.optString("time", "")
            )
        }
    }
    private fun parseTasks(raw: String, preset: String): List<TaskItem> {
        val values = JSONArray(raw)
        return (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            TaskItem(item.getString("id"), item.getString("label"), item.optBoolean("enabled"),
                item.optBoolean("runnable"), item.optBoolean("deferred"), item.optBoolean("hasScheduler"), item.optString("nextRun", ""),
                item.getString("groupId"), item.getString("groupLabel"), item.optBoolean("running", false), preset)
        }
    }
    fun sync() {
        val wasActive = active
        if(message != session.message) {
            val raw = session.message
            val formatted = if(raw.length >= 8 && raw[2] == ':' && raw[5] == ':') raw else {
                val time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
                "$time | INFO | $raw"
            }
            logLines = (logLines + formatted).takeLast(200)
        }
        message = session.message; active = session.taskActive.get() && !busy; connected = session.bridge != null
        syncSelectedPreset()
        shizukuStage = detectShizukuStage(); shizukuBinding = session.binding
        val binder = session.bridge?.asBinder()
        if(binder != knownBridge) { knownBridge = binder; deviceEpoch++; refreshDeviceStatus() }
        lastResult = activity.getSharedPreferences("task_records", 0).getString("last", "") ?: ""
        val ver = session.stateVersion.get()
        if(ver != knownStateVersion) {
            knownStateVersion = ver
            refresh()
        }
        if(wasActive && !active) { refresh(); refreshDeviceStatus() }
    }
    fun refresh() {
        syncWithoutRefresh()
        syncSelectedPreset()
        if(loading) { refreshQueued = true; return }
        val preset = activePreset
        loading = true; error = ""
        executor.submit {
            try {
                val module = engine()
                val bundle = JSONObject(module.callAttr("preset_bundle", preset).toString())
                val owner = bundle.getString("active")
                val raw = bundle.getJSONArray("dashboard").toString()
                val presetsRaw = bundle.getJSONArray("presets").toString()
                val values = parseTasks(raw, owner)
                val presetList = parsePresets(presetsRaw)
                val translations = JSONObject(module.callAttr("labels").toString())
                ui {
                    if (owner == selectedPreset()) {
                        preferences.edit().putTaskSummary(owner, raw).putString("presetSummary", presetsRaw).apply()
                        displayTasks(owner, values)
                        presets = presetList
                        labels = translations
                    }
                    finishRefresh()
                }
            } catch(problem: Throwable) {
                ui {
                    if (preset == selectedPreset()) error = problem.message ?: "任务加载失败"
                    finishRefresh()
                }
            }
        }
    }
    private fun finishRefresh() {
        loading = false
        if (refreshQueued) {
            refreshQueued = false
            refresh()
        }
    }
    fun selectPreset(name: String, onDone: () -> Unit = {}) {
        if(active || presetBusy) { if(name == activePreset) onDone(); return }
        if(name == activePreset && presets.any { it.name == name && it.active }) { onDone(); return }
        val previous = selectedPreset()
        presetBusy = true
        executor.submit {
            try {
                if(session.taskActive.get()) error("任务运行中，请先停止再切换预设")
                val res = JSONObject(engine().callAttr("preset_bundle", name).toString())
                applyPresetBundle(res, previous) { selected -> if(selected) onDone() }
            } catch(problem: Throwable) { ui { presetBusy = false; notify(problem.message ?: "切换预设失败") } }
        }
    }
    fun suggestPresetName(): String {
        val used = presets.map { it.name }.toSet()
        var i = 1
        while("oas$i" in used) i++
        return "oas$i"
    }
    fun createPreset(name: String, source: String?, importJson: String?, onCreated: (String) -> Unit) {
        if(active || presetBusy) return
        val previous = selectedPreset()
        presetBusy = true
        executor.submit {
            try {
                if(session.taskActive.get()) error("任务运行中，请先停止再新建预设")
                val res = JSONObject(engine().callAttr("create_preset", name, source, importJson).toString())
                applyPresetBundle(res, previous) { selected ->
                    notify("已创建预设：$name")
                    if(selected) onCreated(name)
                }
            } catch(problem: Throwable) { ui { presetBusy = false; notify(problem.message ?: "新建预设失败") } }
        }
    }
    fun renamePreset(oldName: String, newName: String, onDone: () -> Unit = {}) {
        if(active || presetBusy) return
        val previous = selectedPreset()
        presetBusy = true
        executor.submit {
            try {
                if(session.taskActive.get()) error("任务运行中，请先停止再重命名")
                val res = JSONObject(engine().callAttr("rename_preset", oldName, newName, previous).toString())
                applyPresetBundle(res, previous, oldName) { notify("已重命名为 $newName"); onDone() }
            } catch(problem: Throwable) { ui { presetBusy = false; notify(problem.message ?: "重命名失败") } }
        }
    }
    fun deletePreset(name: String) {
        if(active || presetBusy) return
        val previous = selectedPreset()
        presetBusy = true
        executor.submit {
            try {
                if(session.taskActive.get()) error("任务运行中，请先停止再删除")
                val res = JSONObject(engine().callAttr("delete_preset", name, previous).toString())
                applyPresetBundle(res, previous, name) { notify("已删除预设：$name") }
            } catch(problem: Throwable) { ui { presetBusy = false; notify(problem.message ?: "删除失败") } }
        }
    }
    fun exportPreset(name: String) {
        executor.submit {
            try {
                val jsonText = engine().callAttr("export_preset", name).toString()
                val bytes = jsonText.toByteArray(Charsets.UTF_8)
                ui {
                    val saver = onSaveJsonFile
                    if(saver != null) saver("$name.json", bytes) else notify("当前不支持导出文件")
                }
            } catch(problem: Throwable) { ui { notify(problem.message ?: "导出失败") } }
        }
    }
    fun importTaskJson(taskId: String, jsonText: String) {
        if(editorBusy) return
        val preset = editorPreset
        editorBusy = true; editorError = ""
        executor.submit {
            try {
                val res = JSONObject(engine().callAttr("import_task_json", taskId, jsonText, preset).toString())
                val defObj = res.getJSONObject("config")
                val dashRaw = res.getJSONArray("dashboard").toString()
                val values = parseTasks(dashRaw, preset)
                val translations = JSONObject(engine().callAttr("labels").toString())
                ui {
                    preferences.edit().putTaskSummary(preset, dashRaw).apply()
                    if(editing == taskId && editorPreset == preset) definition = defObj
                    displayTasks(preset, values)
                    labels = translations; editorBusy = false; notify("任务配置已导入")
                }
            } catch(problem: Throwable) { ui { editorBusy = false; notify(problem.message ?: "导入失败") } }
        }
    }
    fun exportTaskJson(taskId: String, copyOnly: Boolean) {
        val preset = editorPreset
        executor.submit {
            try {
                val jsonText = engine().callAttr("export_task_json", taskId, preset).toString()
                ui {
                    if(copyOnly) {
                        val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText(taskId, jsonText))
                        notify("已复制 $taskId JSON")
                    } else {
                        val saver = onSaveJsonFile
                        if(saver != null) saver("${preset}_${taskId}.json", jsonText.toByteArray(Charsets.UTF_8))
                        else notify("当前不支持导出文件")
                    }
                }
            } catch(problem: Throwable) { ui { notify(problem.message ?: "导出失败") } }
        }
    }
    private fun applyPresetBundle(res: JSONObject, previous: String, removed: String? = null, after: (Boolean) -> Unit) {
        val nextActive = res.getString("active")
        val presetsRaw = res.getJSONArray("presets").toString()
        val presetList = parsePresets(presetsRaw)
        val dashRaw = res.getJSONArray("dashboard").toString()
        val values = parseTasks(dashRaw, nextActive)
        ui {
            val current = selectedPreset()
            val selected = current == nextActive || current == removed ||
                (current == previous && !session.taskActive.get())
            val edit = preferences.edit().putString("presetSummary", presetsRaw)
            if(removed != null) edit.removeTaskSummary(removed)
            if(selected) edit.putString("activePreset", nextActive).putTaskSummary(nextActive, dashRaw)
            edit.apply()
            presets = presetList.map { it.copy(active = it.name == selectedPreset()) }
            if(selected) displayTasks(nextActive, values) else syncSelectedPreset()
            presetBusy = false
            session.notifyStateChanged()
            after(selected)
        }
    }
    private fun syncWithoutRefresh() { message = session.message; active = session.taskActive.get() && !busy; if(!active) stopping = false; connected = session.bridge != null
        shizukuStage = detectShizukuStage(); shizukuBinding = session.binding
        val binder = session.bridge?.asBinder(); if(binder != knownBridge) { knownBridge = binder; deviceEpoch++; refreshDeviceStatus() } }
    private fun refreshDeviceStatus() {
        if(session.bridge == null) { environmentReady = false; screenLabel = "等待连接"; return }
        executor.submit {
            try { val value = JSONObject(session.requireBridge().status())
                ui { environmentReady = value.optInt("displayId", -1) >= 0
                    screenLabel = if(environmentReady) "${value.optInt("width")} × ${value.optInt("height")}" else "未创建" }
            } catch(_: Throwable) { ui { environmentReady = false; screenLabel = "等待连接" } }
        }
    }
    fun setEnabled(task: TaskItem, enabled: Boolean) = mutateSchedule(task) {
        engine().callAttr("set_task_enabled", task.id, enabled, task.preset).toString()
    }
    fun quickRun(task: TaskItem) = mutateSchedule(task, "已设为立即运行") {
        engine().callAttr("quick_run_task", task.id, task.preset).toString()
    }
    fun quickWait(task: TaskItem) = mutateSchedule(task, "已设为等待中") {
        engine().callAttr("quick_wait_task", task.id, task.preset).toString()
    }
    fun setNextRun(task: TaskItem, nextRun: String) = mutateSchedule(task, "执行时间已更新") {
        engine().callAttr("set_task_next_run", task.id, nextRun, null, task.preset).toString()
    }
    fun bulkQuickSchedule(runNow: Boolean) {
        if(bulkMode != "none") return
        val preset = activePreset
        bulkMode = if(runNow) "runNow" else "waitNow"
        executor.submit {
            try {
                val raw = engine().callAttr("bulk_quick_schedule", runNow, preset).toString()
                val values = parseTasks(raw, preset)
                ui {
                    preferences.edit().putTaskSummary(preset, raw).apply()
                    displayTasks(preset, values)
                    bulkMode = "none"; notify(if(runNow) "已将启用任务全部设为立即运行" else "已将启用任务全部设为等待")
                }
            } catch(problem: Throwable) { ui { bulkMode = "none"; notify(problem.message ?: "批量调度失败") } }
        }
    }
    private fun mutateSchedule(task: TaskItem, doneText: String? = null, call: () -> String) {
        val id = task.id
        if(task.running || id in pending || bulkMode != "none") return
        pending = pending + id
        executor.submit {
            try {
                val raw = call()
                val values = parseTasks(raw, task.preset)
                ui {
                    preferences.edit().putTaskSummary(task.preset, raw).apply()
                    displayTasks(task.preset, values)
                    pending = pending - id
                    if(doneText != null) notify(doneText)
                }
            } catch(problem: Throwable) { ui { pending = pending - id; notify(problem.message ?: "调度更新失败") } }
        }
    }
    fun clearLogs() { logLines = emptyList() }
    private fun parseErrorLogs(raw: String): List<ErrorLogItem> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { idx ->
            val obj = arr.getJSONObject(idx)
            ErrorLogItem(
                id = obj.optString("id"),
                timeText = obj.optString("timeText"),
                config = obj.optString("config"),
                task = obj.optString("task"),
                taskLabel = obj.optString("taskLabel", obj.optString("task")),
                summary = obj.optString("summary"),
                imageCount = obj.optInt("imageCount", 0)
            )
        }
    }
    fun refreshErrorLogs() {
        if(errorLogsLoading) return
        errorLogsLoading = true
        executor.submit {
            try {
                val list = parseErrorLogs(engine().callAttr("list_error_logs", 30).toString())
                ui { errorLogs = list; errorLogsLoading = false }
            } catch(_: Throwable) { ui { errorLogsLoading = false } }
        }
    }
    fun openErrorLog(id: String) {
        errorLogsLoading = true
        executor.submit {
            try {
                val res = JSONObject(engine().callAttr("get_error_log", id).toString())
                val m = res.getJSONObject("meta")
                val item = ErrorLogItem(
                    id = m.optString("id", id),
                    timeText = m.optString("timeText", id),
                    config = m.optString("config"),
                    task = m.optString("task"),
                    taskLabel = m.optString("taskLabel", m.optString("task")),
                    summary = m.optString("summary"),
                    imageCount = m.optInt("imageCount", 0)
                )
                val pathsArr = res.optJSONArray("imagePaths") ?: JSONArray()
                val paths = (0 until pathsArr.length()).map { pathsArr.getString(it) }
                val detail = ErrorLogDetail(item, res.optString("logText"), paths)
                ui { selectedError = detail; errorLogsLoading = false }
            } catch(problem: Throwable) { ui { errorLogsLoading = false; notify(problem.message ?: "读取错误详情失败") } }
        }
    }
    fun closeErrorLog() { selectedError = null }
    fun deleteErrorLog(id: String? = null) {
        executor.submit {
            try {
                val list = parseErrorLogs(engine().callAttr("delete_error_log", id).toString())
                ui {
                    errorLogs = list
                    if(id == null || selectedError?.item?.id == id) selectedError = null
                    notify(if(id == null) "已清空报错记录" else "已删除该报错记录")
                }
            } catch(problem: Throwable) { ui { notify(problem.message ?: "删除失败") } }
        }
    }
    fun loadStats(targetDate: String? = null) {
        if(statsLoading) return
        statsLoading = true; statsError = ""
        val scriptName = activePreset
        executor.submit {
            try {
                val mod = engine()
                val datesObj = JSONObject(mod.callAttr("stats_dates", scriptName).toString())
                val datesArr = datesObj.optJSONArray("dates") ?: JSONArray()
                val dates = (0 until datesArr.length()).map { datesArr.getString(it) }
                val today = java.time.LocalDate.now().toString()
                val chosen = when {
                    !targetDate.isNullOrBlank() -> targetDate
                    statsSelectedDate.isNotBlank() && statsSelectedDate in dates -> statsSelectedDate
                    dates.isNotEmpty() -> dates.first()
                    else -> today
                }
                val snap = JSONObject(mod.callAttr("stats_day", chosen, scriptName).toString())
                val tasksObj = snap.optJSONObject("tasks") ?: JSONObject()
                val taskList = mutableListOf<StatsTaskSummary>()
                val keys = tasksObj.keys()
                while(keys.hasNext()) {
                    val key = keys.next()
                    val t = tasksObj.getJSONObject(key)
                    val tb = t.optJSONObject("battle")
                    val runsArr = t.optJSONArray("runs") ?: JSONArray()
                    val runs = (0 until runsArr.length()).map { idx ->
                        val r = runsArr.getJSONObject(idx)
                        val rb = r.optJSONObject("battle")
                        StatsTaskRun(
                            startTime = r.optString("start_time", ""),
                            endTime = r.optString("end_time", ""),
                            durationSeconds = r.optDouble("duration_seconds", 0.0),
                            battleCount = rb?.optInt("count", 0) ?: 0,
                            battleAvgDurationSeconds = rb?.optDouble("avg_duration_seconds", 0.0) ?: 0.0
                        )
                    }
                    taskList.add(
                        StatsTaskSummary(
                            taskName = key,
                            runCount = t.optInt("run_count", 0),
                            totalDurationSeconds = t.optDouble("total_duration_seconds", 0.0),
                            battleCount = tb?.optInt("count", 0) ?: 0,
                            battleAvgDurationSeconds = tb?.optDouble("avg_duration_seconds", 0.0) ?: 0.0,
                            runs = runs
                        )
                    )
                }
                val dayData = StatsDayData(
                    scriptName = snap.optString("script_name", scriptName),
                    date = snap.optString("date", chosen),
                    totalRuntimeSeconds = snap.optDouble("total_runtime_seconds", 0.0),
                    totalTaskRunCount = snap.optInt("total_task_run_count", 0),
                    totalBattleCount = snap.optInt("total_battle_count", 0),
                    tasks = taskList
                )
                ui {
                    statsDates = if(chosen in dates) dates else (listOf(chosen) + dates).distinct()
                    statsSelectedDate = chosen
                    statsDay = dayData
                    statsLoading = false
                }
            } catch(problem: Throwable) {
                ui { statsError = problem.message ?: "统计加载失败"; statsLoading = false }
            }
        }
    }
    fun loadIncome(targetDate: String? = null, days: Int = incomeHistoryDays) {
        if(incomeLoading) return
        incomeLoading = true; incomeError = ""; incomeHistoryDays = days
        val scriptName = activePreset
        executor.submit {
            try {
                val mod = engine()
                val datesObj = JSONObject(mod.callAttr("income_dates", scriptName).toString())
                val datesArr = datesObj.optJSONArray("dates") ?: JSONArray()
                val dates = (0 until datesArr.length()).map { datesArr.getString(it) }
                val chosen = when {
                    !targetDate.isNullOrBlank() -> targetDate
                    incomeSelectedDate.isNotBlank() && incomeSelectedDate in dates -> incomeSelectedDate
                    dates.isNotEmpty() -> dates.first()
                    else -> ""
                }
                val dayData = if(chosen.isNotBlank()) {
                    val obj = JSONObject(mod.callAttr("income_day", chosen, scriptName).toString())
                    val arr = obj.optJSONArray("items") ?: JSONArray()
                    val items = (0 until arr.length()).map { i ->
                        val item = arr.getJSONObject(i)
                        val delta = if(item.isNull("delta")) null else item.optInt("delta")
                        IncomeItemData(item.optString("name"), item.optInt("value", -1), delta)
                    }
                    if(items.isEmpty()) null else IncomeDayData(
                        scriptName = obj.optString("script_name", scriptName),
                        date = obj.optString("date", chosen),
                        collectedAt = obj.optString("collected_at", ""),
                        baselineDate = obj.optString("baseline_date", ""),
                        items = items
                    )
                } else null
                val histObj = JSONObject(mod.callAttr("income_history", days, scriptName).toString())
                val recArr = histObj.optJSONArray("records") ?: JSONArray()
                val recs = (0 until recArr.length()).map { i ->
                    val r = recArr.getJSONObject(i)
                    val valsObj = r.optJSONObject("values") ?: JSONObject()
                    val map = mutableMapOf<String, Int>()
                    val kIt = valsObj.keys()
                    while(kIt.hasNext()) { val k = kIt.next(); map[k] = valsObj.optInt(k, -1) }
                    IncomeHistoryRecordData(r.optString("date", ""), map)
                }
                val histData = IncomeHistoryData(
                    scriptName = histObj.optString("script_name", scriptName),
                    days = histObj.optInt("days", days),
                    start = histObj.optString("start", ""),
                    end = histObj.optString("end", ""),
                    records = recs
                )
                ui {
                    incomeDates = dates
                    incomeSelectedDate = chosen
                    incomeDay = dayData
                    incomeHistory = histData
                    incomeLoading = false
                }
            } catch(problem: Throwable) {
                ui { incomeError = problem.message ?: "收益加载失败"; incomeLoading = false }
            }
        }
    }
    fun deleteIncome(targetDate: String? = null) {
        if(incomeLoading) return
        incomeLoading = true
        val scriptName = activePreset
        executor.submit {
            try {
                val res = JSONObject(engine().callAttr("income_delete", targetDate, scriptName).toString())
                val count = res.optJSONArray("deleted")?.length() ?: 0
                ui { incomeLoading = false; incomeSelectedDate = ""; notify("已删除 $count 条收益记录"); loadIncome(null, incomeHistoryDays) }
            } catch(problem: Throwable) {
                ui { incomeLoading = false; notify(problem.message ?: "删除失败") }
            }
        }
    }
    fun loadEditor(id: String) {
        val preset = activePreset
        editorPreset = preset
        editing = id; definition = null; editorError = ""
        executor.submit {
            try {
                val module = engine(); val value = JSONObject(module.callAttr("task_config", id, preset).toString())
                val translations = JSONObject(module.callAttr("labels").toString())
                ui { if(editing == id && editorPreset == preset) { definition = value; labels = translations } }
            } catch(problem: Throwable) { ui { if(editing == id && editorPreset == preset) editorError = problem.message ?: "配置加载失败" } }
        }
    }
    fun saveEditor(id: String, value: String, run: Boolean, onSaved: () -> Unit) {
        if(editorBusy) return
        val preset = editorPreset
        editorBusy = true; editorError = ""
        executor.submit {
            try {
                engine().callAttr("save_task_config", id, value, preset)
                ui { editorBusy = false; notify("配置已保存"); refresh(); onSaved(); if(run && !active) start(id, preset) }
            } catch(problem: Throwable) { ui { editorBusy = false; editorError = problem.message ?: "配置校验失败" } }
        }
    }
    fun start(task: String? = null, preset: String = activePreset) {
        if(active || busy) return
        if(task == null && tasks.none { it.enabled && it.runnable }) { notify("先在任务页勾选要运行的任务"); return }
        try {
            session.requireBridge()
            if(!environmentReady) { notify("请先在设备与游戏中准备运行环境"); return }
            if(Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            session.update("启动配置：$preset")
            activity.startForegroundService(Intent(activity, MonitorService::class.java)
                .setAction(if(task == null) "engine-schedule" else "engine").putExtra("package", gamePackage)
                .putExtra("engineTask", task).putExtra("preset", preset))
            message = "正在启动任务…"; active = true
        } catch(problem: Throwable) { notify(problem.message ?: "启动失败") }
    }
    fun stop() {
        if(stopping) return
        stopping = true
        message = "正在停止任务…"
        activity.stopService(Intent(activity, MonitorService::class.java))
        Thread({ TaskEngine.requestStop() }, "oas-stop").start()
    }
    fun connect() { session.connect(); sync() }
    fun refreshShizuku(autoConnect: Boolean = true) {
        syncWithoutRefresh()
        if(autoConnect && shizukuStage == ShizukuStage.READY && !shizukuBinding) connect()
    }
    fun onResume() {
        overlayGranted = com.oas.android.FloatingOverlayController.canDrawOverlays(activity)
        if (floatingBallEnabled && overlayGranted && !autoShownOverlayOnce && !com.oas.android.FloatingOverlayController.isShowing()) {
            autoShownOverlayOnce = true
            com.oas.android.FloatingOverlayController.show(activity)
        }
        floatingBallVisible = com.oas.android.FloatingOverlayController.isShowing()
        refreshShizuku(autoConnect = true)
        refresh()
    }
    fun requestOverlayPermission() {
        try {
            activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", activity.packageName, null)))
        } catch (_: Throwable) {
            notify("请在系统设置中允许 OASA「显示在其他应用上层」权限")
        }
    }
    fun updateFloatingBallEnabled(enabled: Boolean) {
        floatingBallEnabled = enabled
        preferences.edit().putBoolean("floatingBallEnabled", enabled).apply()
        if (!enabled) {
            com.oas.android.FloatingOverlayController.hide()
            floatingBallVisible = false
            return
        }
        if (!com.oas.android.FloatingOverlayController.canDrawOverlays(activity)) {
            notify("请先开启「显示在其他应用上层」悬浮窗权限")
            requestOverlayPermission()
            return
        }
        autoShownOverlayOnce = true
        if (com.oas.android.FloatingOverlayController.show(activity)) {
            floatingBallVisible = true
        }
    }
    fun toggleFloatingBall() {
        if (com.oas.android.FloatingOverlayController.isShowing()) {
            com.oas.android.FloatingOverlayController.hide()
            floatingBallVisible = false
            return
        }
        updateFloatingBallEnabled(true)
    }
    fun openShizuku() {
        try {
            val intent = (activity.packageManager.getLaunchIntentForPackage(DeviceSession.SHIZUKU_PACKAGE)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch(problem: Throwable) {
            notify("无法打开 Shizuku：" + (problem.message ?: "请手动前往安装或启动"))
        }
    }
    private fun detectShizukuStage(): ShizukuStage {
        if(session.bridge != null) return ShizukuStage.CONNECTED
        return try {
            if(!Shizuku.pingBinder()) {
                if(session.isShizukuInstalled()) ShizukuStage.NOT_RUNNING else ShizukuStage.NOT_INSTALLED
            } else if(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStage.READY
            } else ShizukuStage.NEED_AUTH
        } catch(_: Throwable) {
            if(session.isShizukuInstalled()) ShizukuStage.NOT_RUNNING else ShizukuStage.NOT_INSTALLED
        }
    }
    fun permissionText(): String = when(shizukuStage) {
        ShizukuStage.CONNECTED -> "已连接"
        ShizukuStage.READY -> if(shizukuBinding) "连接中…" else "已授权"
        ShizukuStage.NEED_AUTH -> "待授权"
        ShizukuStage.NOT_RUNNING -> "未启动"
        ShizukuStage.NOT_INSTALLED -> "未安装"
    }
    fun saveDevice(packageName: String, primary: Boolean, density: Int): Boolean {
        if(!packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { notify("应用包名格式不正确"); return false }
        gamePackage = packageName; mode = primary; dpi = density
        preferences.edit().putString("package", gamePackage).putBoolean("primaryMode", mode).putInt("dpi", dpi).apply()
        return true
    }
    fun prepare() = deviceOperation {
        val bridge = session.requireBridge()
        if(mode) bridge.usePrimaryDisplay() else bridge.createDisplay(dpi)
        session.primaryMode = mode
        if(mode) "前台兼容环境已就绪" else "虚拟屏幕已创建"
    }
    fun release() = deviceOperation { try { session.restoreAudio() } finally { session.requireBridge().releaseDisplay() }; "运行屏幕已释放" }
    private fun deviceOperation(operation: () -> String) {
        if(busy || !session.taskActive.compareAndSet(false, true)) { notify("请先停止当前任务"); return }
        busy = true
        executor.submit {
            try { val result = operation(); session.update(result); ui { notify(result) } }
            catch(problem: Throwable) { ui { notify(problem.message ?: "设备操作失败") } }
            finally { session.taskActive.set(false); ui { busy = false; sync(); refreshDeviceStatus() } }
        }
    }
    fun refreshSystem() {
        if(refreshingSystem) return
        if(!connected) { systemInfo=JSONObject(); return }
        refreshingSystem=true
        executor.submit {
            try { val value=JSONObject(session.requireBridge().systemStatus()); ui { systemInfo=value;gameMuted=session.isGameMuted(gamePackage) } }
            catch(problem: Exception) { ui { systemInfo=JSONObject().put("powerError",problem.message ?: "设备状态读取失败") } }
            finally { ui { refreshingSystem=false } }
        }
    }
    fun systemOption(key: String,value: Boolean) {
        when(key) { "keepAwake" -> keepAwake=value; "autoPanelOff" -> autoPanelOff=value; "autoMute" -> autoMute=value; "fpsEnabled" -> fpsEnabled=value; else -> return }
        preferences.edit().putBoolean(key,value).apply()
        if(connected) systemOperation {
            session.requireBridge().configureRuntime(session.taskActive.get(),keepAwake,fpsEnabled)
        }
    }
    fun frameRate(capture: Int=captureFps,preview: Int=previewFps) {
        captureFps=capture;previewFps=preview
        preferences.edit().putInt("captureFps",capture).putInt("previewFps",preview).apply()
        if(connected) systemOperation {session.requireBridge().configureFrameRates(capture,preview)}
    }
    fun panelPower(on: Boolean) = systemOperation {session.requireBridge().setDisplayPower(on)}
    fun toggleGameAudio() = systemOperation {session.setGameMuted(gamePackage,!session.isGameMuted(gamePackage))}
    private fun systemOperation(operation: () -> Any) {
        if(systemBusy) return
        if(!connected) {notify("请先连接设备服务");return}
        systemBusy=true
        executor.submit {
            try {operation()}
            catch(problem: Exception) {ui {notify(problem.message ?: "系统操作失败")}}
            finally {ui {systemBusy=false;gameMuted=session.isGameMuted(gamePackage);refreshSystem()}}
        }
    }
    fun changeAppearance(value: String) { appearance = value; preferences.edit().putString("appearance", value).apply() }
    fun updateSystemBars(dark: Boolean) {
        val insets = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        insets.isAppearanceLightStatusBars = !dark; insets.isAppearanceLightNavigationBars = !dark
    }
    fun openTools() { activity.startActivity(Intent(activity, ToolsActivity::class.java)) }
    fun saveUpdateSource(manifestUrl: String, mirrorPrefix: String) {
        updateManifestUrl = manifestUrl.trim().ifBlank { RuntimeUpdater.DEFAULT_MANIFEST_URL }
        updateMirrorPrefix = mirrorPrefix.trim()
        preferences.edit()
            .putString("updateManifestUrl", updateManifestUrl)
            .putString("updateMirrorPrefix", updateMirrorPrefix)
            .apply()
        notify("更新源已保存")
    }
    fun checkUpdates() {
        if(updateBusy) return
        updateBusy = true; updateProgress = 0f; updateStatus = "正在检查更新…"
        val url = updateManifestUrl; val mirror = updateMirrorPrefix
        Thread({
            try {
                val info = RuntimeUpdater.checkUpdate(activity, url, mirror)
                val status = when {
                    info.hasRuntimeUpdate && info.hasAppUpdate -> "发现新脚本 (${info.runtimeVersion}) 与新版 APK (${info.appVersion})"
                    info.hasRuntimeUpdate -> "发现新脚本版本：${info.runtimeVersion}"
                    info.hasAppUpdate -> "发现新版 APK：${info.appVersion}"
                    else -> "当前脚本与客户端均为最新版本"
                }
                ui { remoteUpdate = info; updateStatus = status; updateBusy = false }
            } catch(problem: Throwable) {
                ui { updateStatus = problem.message ?: "检查更新失败"; updateBusy = false }
            }
        }, "oas-update-check").start()
    }
    fun applyRuntimeUpdate() {
        val info = remoteUpdate ?: return
        if(updateBusy) return
        if(active) { notify("请先停止正在运行的脚本再更新"); return }
        updateBusy = true; updateProgress = 0f; updateStatus = "准备下载…"
        val mirror = updateMirrorPrefix
        Thread({
            try {
                RuntimeUpdater.applyRuntimeUpdate(activity, info, mirror) { pct, text ->
                    ui { updateProgress = pct; updateStatus = text }
                }
                val refreshed = RuntimeUpdater.localRuntimeInfo(activity).version
                ui {
                    localRuntimeVersion = refreshed
                    remoteUpdate = info.copy(hasRuntimeUpdate = false)
                    updateNeedsRestart = true
                    updateBusy = false
                }
            } catch(problem: Throwable) {
                ui { updateStatus = problem.message ?: "更新失败"; updateBusy = false }
            }
        }, "oas-update-apply").start()
    }
    fun restartApp() {
        val launch = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(launch)
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }
    fun openExternalUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch(problem: Throwable) {
            notify(problem.message ?: "无法打开链接")
        }
    }
    fun notify(text: String) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    fun installedGames(): List<Pair<String,String>> = candidates.mapNotNull { pkg ->
        try { val info = activity.packageManager.getApplicationInfo(pkg, 0); pkg to activity.packageManager.getApplicationLabel(info).toString() }
        catch(_: Exception) { null }
    }
    private fun detectPackage(): String = candidates.firstOrNull { try { activity.packageManager.getApplicationInfo(it, 0); true } catch(_: Exception) { false } } ?: candidates[0]
    fun dispose() { disposed = true; session.removeListener(listener); com.oas.android.FloatingOverlayController.removeVisibilityListener(overlayListener); executor.shutdownNow() }
    companion object {
        val candidates = listOf("com.netease.onmyoji", "com.netease.onmyoji.wyzymnqsd_cps", "com.netease.onmyoji.bili", "com.netease.onmyoji.mi", "com.netease.onmyoji.huawei", "com.netease.onmyoji.vivo", "com.netease.onmyoji.nearme.gamecenter", "com.netease.onmyoji.m4399", "com.tencent.tmgp.yys.zqb")
    }
}

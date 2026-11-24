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
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

data class TaskItem(val id: String, val title: String, val enabled: Boolean, val runnable: Boolean,
    val deferred: Boolean, val schedulable: Boolean, val nextRun: String,
    val groupId: String, val category: String, val running: Boolean = false)

data class PresetItem(val name: String, val active: Boolean, val running: Boolean,
    val previewType: String, val previewId: String, val previewTitle: String, val previewTime: String)

data class ErrorLogItem(val id: String, val timeText: String, val config: String,
    val task: String, val taskLabel: String, val summary: String, val imageCount: Int)

data class ErrorLogDetail(val item: ErrorLogItem, val logText: String, val imagePaths: List<String>)

enum class ShizukuStage { NOT_INSTALLED, NOT_RUNNING, NEED_AUTH, READY, CONNECTED }

/** UI adapter only: settings and task decisions continue to belong to the OAS engine. */
class OasController(private val activity: Activity) {
    val session = (activity.application as OasApp).session
    private val preferences = activity.getSharedPreferences("MainActivity", 0)
    private val executor = Executors.newSingleThreadExecutor()
    private var disposed = false
    var tasks by mutableStateOf<List<TaskItem>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    var message by mutableStateOf(session.message); private set
    var logLines by mutableStateOf<List<String>>(emptyList()); private set
    var active by mutableStateOf(session.taskActive.get()); private set
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
    var lastResult by mutableStateOf(activity.getSharedPreferences("task_records", 0).getString("last", "") ?: ""); private set
    var onPickJsonFile: (((String, String) -> Unit) -> Unit)? = null
    var onSaveJsonFile: ((String, ByteArray) -> Unit)? = null
    private val listener = Runnable { sync() }

    init {
        session.addListener(listener)
        val cached = preferences.getString("taskSummary", null)
        if(cached != null) try { tasks = parseTasks(cached) } catch(_: Exception) { }
        presets = listOf(PresetItem(activePreset, active = true, running = false, previewType = "none", previewId = "", previewTitle = "", previewTime = ""))
    }

    private fun ui(action: () -> Unit) { activity.runOnUiThread { if(!disposed) action() } }
    private fun engine(): PyObject {
        val mod = TaskEngine.initialize(activity, PythonHost(session, gamePackage))
        val desired = activePreset
        try {
            if(mod.callAttr("active_config").toString() != desired && !session.taskActive.get()) {
                mod.callAttr("set_active_config", desired)
            }
        } catch(_: Throwable) { }
        return mod
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
    private fun parseTasks(raw: String): List<TaskItem> {
        val values = JSONArray(raw)
        return (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            TaskItem(item.getString("id"), item.getString("label"), item.optBoolean("enabled"),
                item.optBoolean("runnable"), item.optBoolean("deferred"), item.optBoolean("hasScheduler"), item.optString("nextRun", ""),
                item.getString("groupId"), item.getString("groupLabel"), item.optBoolean("running", false))
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
        if(loading) return
        loading = true; error = ""
        executor.submit {
            try {
                val module = engine(); val raw = module.callAttr("dashboard").toString()
                val presetList = parsePresets(module.callAttr("list_presets").toString())
                val values = parseTasks(raw); val translations = JSONObject(module.callAttr("labels").toString())
                preferences.edit().putString("taskSummary", raw).apply()
                ui { tasks = values; presets = presetList; labels = translations; loading = false }
            } catch(problem: Throwable) { ui { error = problem.message ?: "任务加载失败"; loading = false } }
        }
    }
    fun selectPreset(name: String, onDone: () -> Unit = {}) {
        if(active || presetBusy) { if(name == activePreset) onDone(); return }
        if(name == activePreset && presets.any { it.name == name && it.active }) { onDone(); return }
        presetBusy = true
        executor.submit {
            try {
                if(session.taskActive.get()) error("任务运行中，请先停止再切换预设")
                val res = JSONObject(engine().callAttr("set_active_config", name).toString())
                val nextActive = res.getString("active")
                val presetList = parsePresets(res.getJSONArray("presets").toString())
                val dashRaw = res.getJSONArray("dashboard").toString()
                val values = parseTasks(dashRaw)
                preferences.edit().putString("activePreset", nextActive).putString("taskSummary", dashRaw).apply()
                ui { activePreset = nextActive; presets = presetList; tasks = values; presetBusy = false; onDone() }
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
        presetBusy = true
        executor.submit {
            try {
                if(session.taskActive.get()) error("任务运行中，请先停止再新建预设")
                val res = JSONObject(engine().callAttr("create_preset", name, source, importJson).toString())
                applyPresetBundle(res) { ui { presetBusy = false; notify("已创建预设：$name"); onCreated(name) } }
            } catch(problem: Throwable) { ui { presetBusy = false; notify(problem.message ?: "新建预设失败") } }
        }
    }
    fun renamePreset(oldName: String, newName: String, onDone: () -> Unit = {}) {
        if(active || presetBusy) return
        presetBusy = true
        executor.submit {
            try {
                if(session.taskActive.get()) error("任务运行中，请先停止再重命名")
                val res = JSONObject(engine().callAttr("rename_preset", oldName, newName).toString())
                applyPresetBundle(res) { ui { presetBusy = false; notify("已重命名为 $newName"); onDone() } }
            } catch(problem: Throwable) { ui { presetBusy = false; notify(problem.message ?: "重命名失败") } }
        }
    }
    fun deletePreset(name: String) {
        if(active || presetBusy) return
        presetBusy = true
        executor.submit {
            try {
                if(session.taskActive.get()) error("任务运行中，请先停止再删除")
                val res = JSONObject(engine().callAttr("delete_preset", name).toString())
                applyPresetBundle(res) { ui { presetBusy = false; notify("已删除预设：$name") } }
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
        editorBusy = true; editorError = ""
        executor.submit {
            try {
                val res = JSONObject(engine().callAttr("import_task_json", taskId, jsonText).toString())
                val defObj = res.getJSONObject("config")
                val dashRaw = res.getJSONArray("dashboard").toString()
                val values = parseTasks(dashRaw)
                val translations = JSONObject(engine().callAttr("labels").toString())
                preferences.edit().putString("taskSummary", dashRaw).apply()
                ui { if(editing == taskId) definition = defObj; tasks = values; labels = translations; editorBusy = false; notify("任务配置已导入") }
            } catch(problem: Throwable) { ui { editorBusy = false; notify(problem.message ?: "导入失败") } }
        }
    }
    fun exportTaskJson(taskId: String, copyOnly: Boolean) {
        executor.submit {
            try {
                val jsonText = engine().callAttr("export_task_json", taskId).toString()
                ui {
                    if(copyOnly) {
                        val cm = activity.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText(taskId, jsonText))
                        notify("已复制 $taskId JSON")
                    } else {
                        val saver = onSaveJsonFile
                        if(saver != null) saver("${activePreset}_${taskId}.json", jsonText.toByteArray(Charsets.UTF_8))
                        else notify("当前不支持导出文件")
                    }
                }
            } catch(problem: Throwable) { ui { notify(problem.message ?: "导出失败") } }
        }
    }
    private fun applyPresetBundle(res: JSONObject, after: () -> Unit) {
        val nextActive = res.getString("active")
        val presetList = parsePresets(res.getJSONArray("presets").toString())
        val dashRaw = res.getJSONArray("dashboard").toString()
        val values = parseTasks(dashRaw)
        preferences.edit().putString("activePreset", nextActive).putString("taskSummary", dashRaw).apply()
        ui { activePreset = nextActive; presets = presetList; tasks = values; after() }
    }
    private fun syncWithoutRefresh() { message = session.message; active = session.taskActive.get() && !busy; connected = session.bridge != null
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
    fun setEnabled(task: TaskItem, enabled: Boolean) {
        if(task.running || task.id in pending) return
        pending = pending + task.id
        executor.submit {
            try {
                val raw = engine().callAttr("set_task_enabled", task.id, enabled).toString()
                val values = parseTasks(raw); preferences.edit().putString("taskSummary", raw).apply()
                ui { tasks = values; pending = pending - task.id }
            } catch(problem: Throwable) { ui { pending = pending - task.id; notify(problem.message ?: "保存失败") } }
        }
    }
    fun quickRun(task: TaskItem) = mutateSchedule(task.id, "已设为立即运行") {
        engine().callAttr("quick_run_task", task.id).toString()
    }
    fun quickWait(task: TaskItem) = mutateSchedule(task.id, "已设为等待中") {
        engine().callAttr("quick_wait_task", task.id).toString()
    }
    fun setNextRun(task: TaskItem, nextRun: String) = mutateSchedule(task.id, "执行时间已更新") {
        engine().callAttr("set_task_next_run", task.id, nextRun).toString()
    }
    fun bulkQuickSchedule(runNow: Boolean) {
        if(bulkMode != "none") return
        bulkMode = if(runNow) "runNow" else "waitNow"
        executor.submit {
            try {
                val raw = engine().callAttr("bulk_quick_schedule", runNow).toString()
                val values = parseTasks(raw); preferences.edit().putString("taskSummary", raw).apply()
                ui { tasks = values; bulkMode = "none"; notify(if(runNow) "已将启用任务全部设为立即运行" else "已将启用任务全部设为等待") }
            } catch(problem: Throwable) { ui { bulkMode = "none"; notify(problem.message ?: "批量调度失败") } }
        }
    }
    private fun mutateSchedule(id: String, doneText: String, call: () -> String) {
        if(id in pending || bulkMode != "none") return
        pending = pending + id
        executor.submit {
            try {
                val raw = call()
                val values = parseTasks(raw); preferences.edit().putString("taskSummary", raw).apply()
                ui { tasks = values; pending = pending - id; notify(doneText) }
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
    fun loadEditor(id: String) {
        editing = id; definition = null; editorError = ""
        executor.submit {
            try {
                val module = engine(); val value = JSONObject(module.callAttr("task_config", id).toString())
                val translations = JSONObject(module.callAttr("labels").toString())
                ui { if(editing == id) { definition = value; labels = translations } }
            } catch(problem: Throwable) { ui { editorError = problem.message ?: "配置加载失败" } }
        }
    }
    fun saveEditor(id: String, value: String, run: Boolean, onSaved: () -> Unit) {
        if(editorBusy) return
        editorBusy = true; editorError = ""
        executor.submit {
            try {
                engine().callAttr("save_task_config", id, value)
                ui { editorBusy = false; notify("配置已保存"); refresh(); onSaved(); if(run && !active) start(id) }
            } catch(problem: Throwable) { ui { editorBusy = false; editorError = problem.message ?: "配置校验失败" } }
        }
    }
    fun start(task: String? = null) {
        if(active || busy) return
        if(task == null && tasks.none { it.enabled && it.runnable }) { notify("先在任务页勾选要运行的任务"); return }
        try {
            session.requireBridge()
            if(!environmentReady) { notify("请先在设备与游戏中准备运行环境"); return }
            if(Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            activity.startForegroundService(Intent(activity, MonitorService::class.java)
                .setAction(if(task == null) "engine-schedule" else "engine").putExtra("package", gamePackage).putExtra("engineTask", task))
            message = "正在启动任务…"; active = true
        } catch(problem: Throwable) { notify(problem.message ?: "启动失败") }
    }
    fun stop() {
        TaskEngine.requestStop()
        activity.stopService(Intent(activity, MonitorService::class.java))
        message = "正在停止任务…"
    }
    fun connect() { session.connect(); sync() }
    fun refreshShizuku(autoConnect: Boolean = true) {
        syncWithoutRefresh()
        if(autoConnect && shizukuStage == ShizukuStage.READY && !shizukuBinding) connect()
    }
    fun onResume() { refreshShizuku(autoConnect = true); refresh() }
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
        bridge.launch(gamePackage)
        "游戏已打开，运行环境已准备"
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
    fun notify(text: String) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    fun installedGames(): List<Pair<String,String>> = candidates.mapNotNull { pkg ->
        try { val info = activity.packageManager.getApplicationInfo(pkg, 0); pkg to activity.packageManager.getApplicationLabel(info).toString() }
        catch(_: Exception) { null }
    }
    private fun detectPackage(): String = candidates.firstOrNull { try { activity.packageManager.getApplicationInfo(it, 0); true } catch(_: Exception) { false } } ?: candidates[0]
    fun dispose() { disposed = true; session.removeListener(listener); executor.shutdownNow() }
    companion object {
        val candidates = listOf("com.netease.onmyoji", "com.netease.onmyoji.wyzymnqsd_cps", "com.netease.onmyoji.bili", "com.netease.onmyoji.mi", "com.netease.onmyoji.huawei", "com.netease.onmyoji.vivo", "com.netease.onmyoji.nearme.gamecenter", "com.netease.onmyoji.m4399", "com.tencent.tmgp.yys.zqb")
    }
}

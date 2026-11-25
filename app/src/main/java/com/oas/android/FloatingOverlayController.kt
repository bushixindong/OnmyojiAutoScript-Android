// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.oas.android.engine.PythonHost
import com.oas.android.engine.TaskEngine
import com.oas.android.engine.getTaskSummary
import com.oas.android.engine.putTaskSummary
import com.oas.android.ui.OasDesignTokens
import com.oas.android.ui.OasTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val store = ViewModelStore()
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun start() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}

private enum class OverlayTab(val label: String) {
    CONFIG("配置"),
    SCREEN("画面"),
    LOG("日志")
}

private data class OverlayPreset(
    val name: String,
    val subtitle: String = ""
)

private data class OverlayTask(
    val id: String,
    val label: String,
    val nextRun: String,
    val running: Boolean,
    val due: Boolean
)

private data class OverlayUiState(
    val appearance: String = "system",
    val preset: String = "android",
    val presets: List<OverlayPreset> = emptyList(),
    val tasks: List<OverlayTask> = emptyList(),
    val tasksPreset: String = "",
    val tasksLoading: Boolean = false,
    val busyTaskId: String? = null,
    val isPrimary: Boolean = false,
    val active: Boolean = false,
    val stopping: Boolean = false,
    val connected: Boolean = false,
    val message: String = "",
    val logs: List<String> = emptyList()
)

/**
 * Material 3 Jetpack Compose floating ball + 3-tab expandable panel (Config / Screen / Log),
 * pinned to the physical default display metrics so Activity PiP never shrinks the overlay.
 */
object FloatingOverlayController {
    private val main = Handler(Looper.getMainLooper())
    private val stateListeners = CopyOnWriteArraySet<Runnable>()

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var expanded by mutableStateOf(false)
    private var selectedTab by mutableStateOf(OverlayTab.CONFIG)
    private var viewingTasks by mutableStateOf(false)
    private var uiState by mutableStateOf(OverlayUiState())
    private var idleHalfHidden by mutableStateOf(false)
    private var stoppingLocal = false
    private val taskExecutor = Executors.newSingleThreadExecutor()
    private var taskLoadId = 0L

    private var ballX = 0
    private var ballY = 0
    private var hasSavedPos = false
    private var dockedLeft = false
    private var edgeAnimator: ValueAnimator? = null
    private val autoDockRunnable = Runnable { enterHalfHidden() }

    private val sessionListener = Runnable { refreshState() }

    @JvmStatic
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    @JvmStatic
    fun isShowing(): Boolean = composeView != null

    @JvmStatic
    fun addVisibilityListener(listener: Runnable) { stateListeners.add(listener) }

    @JvmStatic
    fun removeVisibilityListener(listener: Runnable) { stateListeners.remove(listener) }

    private fun notifyVisibility() {
        main.post { stateListeners.forEach { it.run() } }
    }

    @JvmStatic
    fun show(context: Context): Boolean {
        val app = context.applicationContext
        if (!canDrawOverlays(app)) return false
        main.post {
            appContext = app
            if (composeView != null) {
                refreshState()
                return@post
            }
            windowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            expanded = false
            refreshState()
            attachComposeOverlay(app)
            (app as? OasApp)?.session?.addListener(sessionListener)
            notifyVisibility()
        }
        return true
    }

    @JvmStatic
    fun hide() {
        main.post {
            (appContext as? OasApp)?.session?.removeListener(sessionListener)
            detachComposeOverlay()
            expanded = false
            stoppingLocal = false
            notifyVisibility()
        }
    }

    @JvmStatic
    fun collapse(halfHideImmediately: Boolean = false) {
        main.post {
            if (composeView == null) return@post
            if (expanded) updateExpanded(false)
            if (halfHideImmediately) {
                cancelAutoDock()
                enterHalfHidden()
            }
        }
    }

    @JvmStatic
    fun toggle(context: Context): Boolean = if (isShowing()) { hide(); false } else show(context)

    /** Always read physical default display metrics so Activity PiP configuration changes never shrink the overlay. */
    private fun physicalDisplayMetrics(): DisplayMetrics {
        val ctx = appContext
        val dm = DisplayMetrics()
        if (ctx != null) {
            val displayManager = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                @Suppress("DEPRECATION")
                display.getRealMetrics(dm)
                if (dm.widthPixels > 0 && dm.density > 0f) return dm
            }
            return ctx.resources.displayMetrics
        }
        dm.widthPixels = 1080
        dm.heightPixels = 2400
        dm.density = 3f
        dm.scaledDensity = 3f
        return dm
    }

    private fun dp(value: Float): Int = (value * physicalDisplayMetrics().density).roundToInt()

    private fun screenSize(): Pair<Int, Int> {
        val dm = physicalDisplayMetrics()
        return dm.widthPixels to dm.heightPixels
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun attachComposeOverlay(ctx: Context) {
        val wm = windowManager ?: return
        detachComposeOverlay()

        val (sw, sh) = screenSize()
        if (!hasSavedPos) {
            ballX = sw - dp(54f)
            ballY = (sh * 0.30f).toInt()
            hasSavedPos = true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX.coerceIn(dp(6f), (sw - dp(54f)).coerceAtLeast(dp(6f)))
            y = ballY.coerceIn(dp(48f), (sh - dp(96f)).coerceAtLeast(dp(48f)))
        }
        layoutParams = params
        dockedLeft = params.x + dp(26f) < sw / 2
        idleHalfHidden = false

        val owner = OverlayLifecycleOwner()
        lifecycleOwner = owner

        val displayContext = run {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val defaultDisplay = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (defaultDisplay != null) ctx.createDisplayContext(defaultDisplay) else ctx
        }

        val view = ComposeView(displayContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val fixedMetrics = remember { physicalDisplayMetrics() }
                val fixedDensity = remember(fixedMetrics) {
                    Density(
                        density = fixedMetrics.density,
                        fontScale = (fixedMetrics.scaledDensity / fixedMetrics.density).coerceIn(0.85f, 1.3f)
                    )
                }
                CompositionLocalProvider(LocalDensity provides fixedDensity) {
                    val displayedState = uiState
                    OverlayRootContent(
                        session = (ctx as? OasApp)?.session,
                        state = displayedState,
                        expanded = expanded,
                        halfHidden = idleHalfHidden,
                        selectedTab = selectedTab,
                        viewingTasks = viewingTasks,
                        onTabSelect = { selectedTab = it },
                        onOpenPreset = { openPresetTasks(it) },
                        onBackToPresets = { viewingTasks = false },
                        onQuickSchedule = { taskId, runNow -> quickScheduleTask(displayedState.tasksPreset, taskId, runNow) },
                        onPoll = { refreshState() },
                        onExpand = { updateExpanded(true) },
                        onCollapse = { updateExpanded(false) },
                        onClose = { hide() },
                        onToggleRun = { handleToggleRun() },
                        onOpenApp = { openMainApp() },
                        onDrag = { dx, dy -> moveBy(dx, dy) },
                        onBallDragEnd = { snapBallToEdge() }
                    )
                }
            }
        }
        composeView = view
        try {
            wm.addView(view, params)
            owner.start()
            scheduleAutoDock()
        } catch (e: Throwable) {
            android.util.Log.e("OAS", "Failed to attach Compose overlay", e)
            detachComposeOverlay()
            notifyVisibility()
        }
    }

    private fun cancelAutoDock() {
        main.removeCallbacks(autoDockRunnable)
        edgeAnimator?.cancel()
        edgeAnimator = null
    }

    private fun scheduleAutoDock() {
        cancelAutoDock()
        if (!expanded && composeView != null) {
            main.postDelayed(autoDockRunnable, 3000L)
        }
    }

    private fun animateBallX(targetX: Int, durationMs: Long = 240L) {
        val view = composeView ?: return
        val params = layoutParams ?: return
        edgeAnimator?.cancel()
        val startX = params.x
        if (startX == targetX) return
        edgeAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = composeView ?: return@addUpdateListener
                val p = layoutParams ?: return@addUpdateListener
                p.x = anim.animatedValue as Int
                try { windowManager?.updateViewLayout(v, p) } catch (_: Throwable) { }
            }
            start()
        }
    }

    private fun enterHalfHidden() {
        if (expanded || composeView == null) return
        val (sw, _) = screenSize()
        val totalSize = dp(52f) // 44dp ball + 4dp padding each side
        val halfOffset = dp(24f)
        val targetX = if (dockedLeft) -halfOffset else sw - totalSize + halfOffset
        idleHalfHidden = true
        animateBallX(targetX, 260L)
    }

    private fun detachComposeOverlay() {
        cancelAutoDock()
        idleHalfHidden = false
        val view = composeView
        composeView = null
        layoutParams = null
        if (view != null) {
            try { windowManager?.removeViewImmediate(view) } catch (_: Throwable) { }
        }
        lifecycleOwner?.destroy()
        lifecycleOwner = null
    }

    private fun updateExpanded(target: Boolean) {
        if (expanded == target) return
        cancelAutoDock()
        idleHalfHidden = false
        expanded = target
        val view = composeView ?: return
        val params = layoutParams ?: return
        val (sw, sh) = screenSize()
        if (target) {
            refreshState(forceFull = true)
            val panelW = dp(316f)
            params.x = ((sw - panelW) / 2).coerceAtLeast(dp(8f))
            params.y = ballY.coerceIn(dp(44f), (sh - dp(390f)).coerceAtLeast(dp(44f)))
        } else {
            val totalSize = dp(52f)
            val edgeX = if (dockedLeft) dp(4f) else (sw - totalSize - dp(4f)).coerceAtLeast(dp(4f))
            ballX = edgeX
            params.x = edgeX
            params.y = ballY.coerceIn(dp(44f), (sh - dp(96f)).coerceAtLeast(dp(44f)))
            scheduleAutoDock()
        }
        try { windowManager?.updateViewLayout(view, params) } catch (_: Throwable) { }
    }

    private fun moveBy(dx: Float, dy: Float) {
        cancelAutoDock()
        if (idleHalfHidden) idleHalfHidden = false
        val view = composeView ?: return
        val params = layoutParams ?: return
        val (sw, sh) = screenSize()
        val maxW = if (expanded) dp(316f) else dp(52f)
        val maxH = if (expanded) dp(120f) else dp(60f)
        params.x = (params.x + dx.roundToInt()).coerceIn(-dp(24f), (sw - maxW + dp(24f)).coerceAtLeast(0))
        params.y = (params.y + dy.roundToInt()).coerceIn(dp(24f), (sh - maxH).coerceAtLeast(dp(24f)))
        if (!expanded) {
            ballX = params.x
            ballY = params.y
        } else {
            ballY = params.y
        }
        try { windowManager?.updateViewLayout(view, params) } catch (_: Throwable) { }
    }

    private fun snapBallToEdge() {
        if (expanded) return
        val params = layoutParams ?: return
        val (sw, _) = screenSize()
        val totalSize = dp(52f)
        dockedLeft = params.x + totalSize / 2 < sw / 2
        val targetX = if (dockedLeft) dp(4f) else (sw - totalSize - dp(4f)).coerceAtLeast(dp(4f))
        ballX = targetX
        ballY = params.y
        idleHalfHidden = false
        animateBallX(targetX, 180L)
        scheduleAutoDock()
    }

    private fun openMainApp() {
        val ctx = appContext ?: return
        updateExpanded(false)
        try {
            val intent = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            ctx.startActivity(intent)
        } catch (_: Throwable) { }
    }

    private fun openPresetTasks(name: String) {
        val ctx = appContext ?: return
        val session = (ctx as? OasApp)?.session ?: return
        val prefs = ctx.getSharedPreferences("MainActivity", 0)
        val current = prefs.getString("activePreset", "android") ?: "android"
        if (name != current && session.taskActive.get()) {
            session.update("请先停止当前任务再切换配置")
            return
        }
        // Never allow stale tasks from another preset to remain clickable while loading.
        if (uiState.tasksPreset != name) {
            uiState = uiState.copy(preset = name, tasks = emptyList(), tasksPreset = "", tasksLoading = true)
        } else {
            uiState = uiState.copy(preset = name, tasksLoading = true)
        }
        viewingTasks = true
        loadTasksAsync(name, current)
    }

    private fun parseOverlayTasks(raw: String): List<OverlayTask> {
        val arr = JSONArray(raw)
        val nowStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val result = mutableListOf<OverlayTask>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (!obj.optBoolean("enabled") || !obj.optBoolean("hasScheduler")) continue
            val nextRun = obj.optString("nextRun", "").trim()
            val running = obj.optBoolean("running")
            val due = !running && (nextRun.isEmpty() || nextRun <= nowStr)
            result.add(
                OverlayTask(
                    id = obj.optString("id"),
                    label = obj.optString("label").ifBlank { obj.optString("id") },
                    nextRun = nextRun,
                    running = running,
                    due = due
                )
            )
        }
        return result.sortedWith(
            compareByDescending<OverlayTask> { it.running }
                .thenByDescending { it.due }
                .thenBy { it.nextRun.ifEmpty { "9999-99-99 99:99:99" } }
        )
    }

    private fun loadTasksAsync(presetName: String, previous: String) {
        val ctx = appContext ?: return
        val session = (ctx as? OasApp)?.session ?: return
        val loadId = ++taskLoadId
        taskExecutor.submit {
            try {
                val prefs = ctx.getSharedPreferences("MainActivity", 0)
                val pkg = prefs.getString("package", "com.netease.onmyoji") ?: "com.netease.onmyoji"
                val engine = TaskEngine.initialize(ctx, PythonHost(session, pkg))
                val bundle = JSONObject(engine.callAttr("preset_bundle", presetName).toString())
                val owner = bundle.getString("active")
                val raw = bundle.getJSONArray("dashboard").toString()
                val presetsRaw = bundle.getJSONArray("presets").toString()
                val parsed = parseOverlayTasks(raw)
                main.post {
                    if (loadId == taskLoadId) {
                        val current = prefs.getString("activePreset", "android") ?: "android"
                        if (current == owner || (current == previous && !session.taskActive.get())) {
                            prefs.edit().putString("activePreset", owner).putTaskSummary(owner, raw)
                                .putString("presetSummary", presetsRaw).apply()
                            uiState = uiState.copy(preset = owner, tasks = parsed, tasksPreset = owner, tasksLoading = false)
                            if (current != owner) session.notifyStateChanged()
                        } else {
                            uiState = uiState.copy(tasksLoading = false)
                            refreshState()
                        }
                    }
                }
            } catch (e: Throwable) {
                main.post {
                    if (loadId == taskLoadId) {
                        uiState = uiState.copy(tasksLoading = false)
                        refreshState()
                        session.update("读取任务列表失败：" + (e.message ?: "未知错误"))
                    }
                }
            }
        }
    }

    private fun quickScheduleTask(presetName: String, taskId: String, runNow: Boolean) {
        val ctx = appContext ?: return
        val session = (ctx as? OasApp)?.session ?: return
        val prefs = ctx.getSharedPreferences("MainActivity", 0)
        if (uiState.busyTaskId != null || uiState.tasksLoading || uiState.tasksPreset != presetName ||
            prefs.getString("activePreset", "android") != presetName) return
        uiState = uiState.copy(busyTaskId = taskId)
        taskExecutor.submit {
            try {
                val pkg = prefs.getString("package", "com.netease.onmyoji") ?: "com.netease.onmyoji"
                val engine = TaskEngine.initialize(ctx, PythonHost(session, pkg))
                val method = if (runNow) "quick_run_task" else "quick_wait_task"
                val raw = engine.callAttr(method, taskId, presetName).toString()
                val parsed = parseOverlayTasks(raw)
                main.post {
                    prefs.edit().putTaskSummary(presetName, raw).apply()
                    if (prefs.getString("activePreset", "android") == presetName && uiState.preset == presetName) {
                        uiState = uiState.copy(tasks = parsed, tasksPreset = presetName, busyTaskId = null)
                    } else {
                        uiState = uiState.copy(busyTaskId = null)
                    }
                    session.notifyStateChanged()
                }
            } catch (e: Throwable) {
                main.post { uiState = uiState.copy(busyTaskId = null) }
                session.update("调度操作失败：" + (e.message ?: "未知错误"))
            }
        }
    }

    private fun handleToggleRun() {
        val ctx = appContext ?: return
        val session = (ctx as? OasApp)?.session ?: return
        if (session.taskActive.get()) {
            if (stoppingLocal) return
            stoppingLocal = true
            session.update("正在停止任务…")
            ctx.stopService(Intent(ctx, MonitorService::class.java))
            Thread({ TaskEngine.requestStop() }, "oas-overlay-stop").start()
        } else {
            if (uiState.tasksLoading) {
                session.update("请等待配置加载")
                return
            }
            val prefs = ctx.getSharedPreferences("MainActivity", 0)
            val pkg = prefs.getString("package", "com.netease.onmyoji") ?: "com.netease.onmyoji"
            val preset = prefs.getString("activePreset", "android") ?: "android"
            val isPrimary = prefs.getBoolean("primaryMode", session.primaryMode)
            try {
                ctx.startForegroundService(
                    Intent(ctx, MonitorService::class.java)
                        .setAction("engine-schedule")
                        .putExtra("package", pkg)
                        .putExtra("preset", preset)
                )
                session.update("正在启动配置：$preset …")
                if (isPrimary) collapse(halfHideImmediately = true)
            } catch (e: Throwable) {
                session.update("悬浮窗启动失败：" + (e.message ?: "未知错误"))
            }
        }
        refreshState()
    }

    private fun loadPresets(ctx: Context, activeName: String): List<OverlayPreset> {
        val byName = linkedMapOf<String, OverlayPreset>()
        val prefs = ctx.getSharedPreferences("MainActivity", 0)
        val cached = prefs.getString("presetSummary", null)
        if (!cached.isNullOrBlank()) {
            try {
                val arr = JSONArray(cached)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val name = obj.optString("name").trim()
                    if (name.isEmpty()) continue
                    val preview = obj.optJSONObject("preview")
                    val title = preview?.optString("title").orEmpty()
                    val type = preview?.optString("type").orEmpty()
                    val sub = when {
                        obj.optBoolean("running") -> "运行中"
                        type == "pending" && title.isNotBlank() -> "待运行 · $title"
                        type == "waiting" && title.isNotBlank() -> "等待中 · $title"
                        else -> ""
                    }
                    byName[name] = OverlayPreset(name, sub)
                }
            } catch (_: Throwable) { }
        }
        val configDir = File(ctx.filesDir, "oas-engine/config")
        configDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".json") && !file.name.equals("template.json", ignoreCase = true)) {
                val name = file.name.removeSuffix(".json")
                if (name.isNotBlank() && name !in byName) {
                    byName[name] = OverlayPreset(name)
                }
            }
        }
        if (activeName.isNotBlank() && activeName !in byName) {
            byName[activeName] = OverlayPreset(activeName)
        }
        return byName.values.toList()
    }

    private fun refreshState(forceFull: Boolean = false) {
        val ctx = appContext ?: return
        val session = (ctx as? OasApp)?.session ?: return
        val active = session.taskActive.get()
        if (!active) stoppingLocal = false
        val prefs = ctx.getSharedPreferences("MainActivity", 0)
        val appearance = prefs.getString("appearance", "system") ?: "system"
        val connected = session.bridge != null
        // When collapsed to the edge ball, skip JSON parsing, directory scans, and log copying.
        if (!expanded && !forceFull) {
            if (uiState.active != active || uiState.stopping != stoppingLocal || uiState.connected != connected || uiState.appearance != appearance) {
                uiState = uiState.copy(
                    appearance = appearance,
                    active = active,
                    stopping = stoppingLocal,
                    connected = connected
                )
            }
            return
        }
        val activePreset = if (uiState.tasksLoading) uiState.preset
            else prefs.getString("activePreset", "android") ?: "android"
        val cachedTasks = prefs.getTaskSummary(activePreset)
        val (parsedTasks, resolvedTasksPreset) = when {
            !cachedTasks.isNullOrBlank() -> try {
                parseOverlayTasks(cachedTasks) to activePreset
            } catch (_: Throwable) {
                if (uiState.tasksPreset == activePreset) uiState.tasks to activePreset else emptyList<OverlayTask>() to ""
            }
            uiState.tasksPreset == activePreset -> uiState.tasks to activePreset
            else -> emptyList<OverlayTask>() to ""
        }
        uiState = uiState.copy(
            appearance = appearance,
            preset = activePreset,
            presets = if (selectedTab == OverlayTab.CONFIG || forceFull) loadPresets(ctx, activePreset) else uiState.presets,
            tasks = parsedTasks,
            tasksPreset = resolvedTasksPreset,
            isPrimary = prefs.getBoolean("primaryMode", session.primaryMode),
            active = active,
            stopping = stoppingLocal,
            connected = connected,
            message = session.message,
            logs = if (selectedTab == OverlayTab.LOG || forceFull) session.recentLogsSnapshot().takeLast(60) else uiState.logs
        )
    }
}

@Composable
private fun OverlayRootContent(
    session: DeviceSession?,
    state: OverlayUiState,
    expanded: Boolean,
    halfHidden: Boolean,
    selectedTab: OverlayTab,
    viewingTasks: Boolean,
    onTabSelect: (OverlayTab) -> Unit,
    onOpenPreset: (String) -> Unit,
    onBackToPresets: () -> Unit,
    onQuickSchedule: (String, Boolean) -> Unit,
    onPoll: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    onToggleRun: () -> Unit,
    onOpenApp: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onBallDragEnd: () -> Unit
) {
    LaunchedEffect(Unit) {
        while (true) {
            onPoll()
            delay(500)
        }
    }

    OasTheme(state.appearance) {
        if (!expanded) {
            OasFloatBall(
                active = state.active,
                connected = state.connected,
                halfHidden = halfHidden,
                onClick = onExpand,
                onLongClick = onClose,
                onDrag = onDrag,
                onDragEnd = onBallDragEnd
            )
        } else {
            OasExpandedOverlayPanel(
                session = session,
                state = state,
                selectedTab = selectedTab,
                viewingTasks = viewingTasks,
                onTabSelect = onTabSelect,
                onOpenPreset = onOpenPreset,
                onBackToPresets = onBackToPresets,
                onQuickSchedule = onQuickSchedule,
                onCollapse = onCollapse,
                onToggleRun = onToggleRun,
                onOpenApp = onOpenApp,
                onDrag = onDrag
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OasFloatBall(
    active: Boolean,
    connected: Boolean,
    halfHidden: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val infiniteTransition = rememberInfiniteTransition(label = "float_ball_breath")
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.70f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath_alpha"
    )
    val ringColor = when {
        active -> OasDesignTokens.StatusColors.Running
        connected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val visualAlpha = when {
        halfHidden -> 0.58f
        active -> breathingAlpha
        else -> 1f
    }

    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(44.dp)
            .alpha(visualAlpha)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                ),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            tonalElevation = 4.dp,
            border = BorderStroke(2.dp, ringColor)
        ) {
            Image(
                painter = painterResource(R.drawable.oasx_logo),
                contentDescription = "OASA 悬浮球",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .clip(CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(11.dp)
                .clip(CircleShape)
                .background(ringColor)
                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
        )
    }
}

@Composable
private fun OasExpandedOverlayPanel(
    session: DeviceSession?,
    state: OverlayUiState,
    selectedTab: OverlayTab,
    viewingTasks: Boolean,
    onTabSelect: (OverlayTab) -> Unit,
    onOpenPreset: (String) -> Unit,
    onBackToPresets: () -> Unit,
    onQuickSchedule: (String, Boolean) -> Unit,
    onCollapse: () -> Unit,
    onToggleRun: () -> Unit,
    onOpenApp: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    val statusColor = when {
        state.stopping -> MaterialTheme.colorScheme.tertiary
        state.active -> OasDesignTokens.StatusColors.Running
        state.connected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        modifier = Modifier
            .padding(6.dp)
            .width(308.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        shadowElevation = 12.dp,
        tonalElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Draggable Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    }
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.oasx_logo),
                    contentDescription = "OASA",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = state.preset,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                        ) {
                            Text(
                                text = if (state.isPrimary) "真实屏" else "虚拟屏",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = when {
                                state.stopping -> "正在停止…"
                                state.active -> "运行中"
                                state.connected -> "已连接 · 空闲"
                                else -> "未连接"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onOpenApp, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Home, contentDescription = "回到 OASA", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onCollapse, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "收起为悬浮球", modifier = Modifier.size(18.dp))
                }
            }

            // 3-Tab Switcher Bar (MaaMeow PanelHeader style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                OverlayTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onTabSelect(tab) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                        shadowElevation = if (selected) 1.dp else 0.dp
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Tab Content Body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (selectedTab) {
                    OverlayTab.CONFIG -> if (viewingTasks) {
                        OverlayTaskScheduleTab(
                            preset = state.preset,
                            tasks = state.tasks,
                            ready = !state.tasksLoading && state.tasksPreset == state.preset,
                            loading = state.tasksLoading,
                            busyTaskId = state.busyTaskId,
                            onBack = onBackToPresets,
                            onQuickSchedule = onQuickSchedule
                        )
                    } else {
                        OverlayPresetTab(
                            presets = state.presets,
                            selectedPreset = state.preset,
                            running = state.active,
                            onOpen = onOpenPreset
                        )
                    }
                    OverlayTab.SCREEN -> OverlayScreenTab(
                        session = session,
                        connected = state.connected,
                        isPrimary = state.isPrimary
                    )
                    OverlayTab.LOG -> OverlayLogTab(
                        logs = state.logs,
                        fallbackMessage = state.message
                    )
                }

                // Bottom Action Bar
                Button(
                    onClick = onToggleRun,
                    enabled = !state.stopping,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_oasx_power),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when {
                            state.stopping -> "正在停止…"
                            state.active -> "停止 · ${state.preset}"
                            else -> "启动 · ${state.preset}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayPresetTab(
    presets: List<OverlayPreset>,
    selectedPreset: String,
    running: Boolean,
    onOpen: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        if (presets.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无可选配置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(presets, key = { it.name }) { item ->
                    val selected = item.name == selectedPreset
                    val canOpen = !running || selected
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = canOpen) { onOpen(item.name) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color.Transparent,
                        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (item.subtitle.isNotBlank()) {
                                    Text(
                                        text = item.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "已选中",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(2.dp))
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "进入任务调度",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayTaskScheduleTab(
    preset: String,
    tasks: List<OverlayTask>,
    ready: Boolean,
    loading: Boolean,
    busyTaskId: String?,
    onBack: () -> Unit,
    onQuickSchedule: (String, Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Sub-header: Back to presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBack)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回配置列表",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "$preset · 启用任务 (${tasks.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

            if (loading && tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else if (tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("当前配置没有已启用的任务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(tasks.size) { index ->
                        val task = tasks[index]
                        val busy = busyTaskId == task.id
                        val shortTime = if (task.nextRun.length >= 16) task.nextRun.substring(5, 16) else task.nextRun
                        val sub = when {
                            task.running -> "运行中"
                            task.due -> "待运行"
                            shortTime.isNotBlank() -> shortTime
                            else -> "等待中"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (task.running) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = task.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = sub,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    color = when {
                                        task.running -> OasDesignTokens.StatusColors.Running
                                        task.due -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1
                                )
                            }
                            val canAct = ready && !busy && !task.running
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = canAct) { onQuickSchedule(task.id, true) },
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (!canAct) 0.35f else 0.9f)
                            ) {
                                Text(
                                    text = "执行",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = canAct) { onQuickSchedule(task.id, false) },
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (!canAct) 0.35f else 1f)
                            ) {
                                Text(
                                    text = "等待",
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayScreenTab(
    session: DeviceSession?,
    connected: Boolean,
    isPrimary: Boolean
) {
    var surface by remember { mutableStateOf<Surface?>(null) }
    var live by remember { mutableStateOf(false) }
    var dimensions by remember { mutableStateOf(1280 to 720) }
    var statusLabel by remember { mutableStateOf("正在连接虚拟屏画面…") }
    var attachedBridge by remember { mutableStateOf<IDeviceBridge?>(null) }
    val owner = remember { System.nanoTime() }
    val touchExecutor = remember { Executors.newSingleThreadExecutor() }
    val allowTouch = remember { AtomicBoolean(false) }
    val currentDimensions by rememberUpdatedState(dimensions)

    SideEffect { allowTouch.set(live && !isPrimary && connected) }

    DisposableEffect(Unit) {
        onDispose {
            allowTouch.set(false)
            val bridge = attachedBridge
            if (bridge != null) {
                touchExecutor.submit { try { bridge.previewTouch(MotionEvent.ACTION_CANCEL, 0, 0, owner) } catch (_: Throwable) { } }
            }
            touchExecutor.shutdown()
        }
    }

    LaunchedEffect(session, connected, isPrimary, surface) {
        live = false
        attachedBridge = null
        if (isPrimary) {
            statusLabel = "当前为真实屏模式\n游戏画面直接显示在手机主屏"
            return@LaunchedEffect
        }
        if (!connected || session == null) {
            statusLabel = "请先连接 Shizuku 与后台虚拟屏"
            return@LaunchedEffect
        }
        val currentSurface = surface ?: return@LaunchedEffect
        var bridge: IDeviceBridge? = null
        var attached = false
        try {
            bridge = session.requireBridge()
            while (isActive) {
                val status = withContext(Dispatchers.IO) { JSONObject(bridge.previewStatus()) }
                val w = status.optInt("width", 1280).coerceAtLeast(1)
                val h = status.optInt("height", 720).coerceAtLeast(1)
                dimensions = w to h
                if (status.optString("mode") == "primary") {
                    live = false
                    statusLabel = "当前为真实屏模式"
                } else if (status.optInt("displayId", -1) < 0) {
                    live = false
                    statusLabel = "尚未创建后台虚拟屏"
                } else {
                    val activeOwner = status.optLong("owner", 0L)
                    val takenByOther = activeOwner != 0L && activeOwner != owner
                    if (!takenByOther && (!attached || !status.optBoolean("attached"))) {
                        withContext(Dispatchers.IO) { bridge.setPreviewSurface(currentSurface, owner) }
                        attached = true
                        attachedBridge = bridge
                    }
                    val owned = status.optBoolean("attached") && activeOwner == owner
                    val age = status.optLong("ageMs", -1)
                    val err = status.optString("error")
                    live = owned && age in 0..2500
                    statusLabel = when {
                        takenByOther -> "画面已切换至主界面"
                        err.isNotBlank() -> "预览异常：$err"
                        live -> ""
                        else -> "等待虚拟屏新画面…"
                    }
                }
                delay(500)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Throwable) {
            live = false
            statusLabel = problem.message ?: "预览连接失败"
        } finally {
            allowTouch.set(false)
            attachedBridge = null
            if (bridge != null && attached) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { bridge.previewTouch(MotionEvent.ACTION_CANCEL, 0, 0, owner) } catch (_: Throwable) { }
                    try { bridge.clearPreviewSurface(owner) } catch (_: Throwable) { }
                }
            }
        }
    }

    val aspect = (dimensions.first.toFloat() / dimensions.second.toFloat()).coerceIn(0.5f, 2.5f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (!isPrimary && connected) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.setFormat(PixelFormat.RGBA_8888)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surface = holder.surface }
                            override fun surfaceDestroyed(holder: SurfaceHolder) { if (surface == holder.surface) surface = null }
                        })
                        val held = linkedSetOf<Int>()
                        var lastMove = 0L
                        setOnTouchListener { view, event ->
                            val bridge = attachedBridge
                            if (!allowTouch.get() || bridge == null || view.width <= 0 || view.height <= 0) {
                                held.clear()
                                return@setOnTouchListener false
                            }
                            val (fw, fh) = currentDimensions
                            val scale = min(view.width.toFloat() / fw, view.height.toFloat() / fh)
                            val offsetX = (view.width - fw * scale) / 2f
                            val offsetY = (view.height - fh * scale) / 2f
                            val action = event.actionMasked
                            val changed = event.getPointerId(event.actionIndex)
                            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                                val x = (event.getX(event.actionIndex) - offsetX) / scale
                                val y = (event.getY(event.actionIndex) - offsetY) / scale
                                if (x < 0 || y < 0 || x >= fw || y >= fh) return@setOnTouchListener held.isNotEmpty()
                                if (action == MotionEvent.ACTION_DOWN) held.clear()
                                if (held.size >= 10) return@setOnTouchListener true
                                held.add(changed)
                            }
                            if (held.isEmpty()) return@setOnTouchListener false
                            if (action == MotionEvent.ACTION_MOVE && event.eventTime - lastMove < 16) return@setOnTouchListener true
                            if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_CANCEL && changed !in held) return@setOnTouchListener true
                            lastMove = event.eventTime
                            val indices = (0 until event.pointerCount).filter { event.getPointerId(it) in held }
                            val ids = indices.map { event.getPointerId(it) }.toIntArray()
                            val xy = FloatArray(ids.size * 2)
                            indices.forEachIndexed { slot, idx ->
                                xy[slot * 2] = ((event.getX(idx) - offsetX) / scale).coerceIn(0f, fw - 1f)
                                xy[slot * 2 + 1] = ((event.getY(idx) - offsetY) / scale).coerceIn(0f, fh - 1f)
                            }
                            val mapped = when {
                                action == MotionEvent.ACTION_POINTER_DOWN && ids.size == 1 -> MotionEvent.ACTION_DOWN
                                action == MotionEvent.ACTION_POINTER_UP && ids.size == 1 -> MotionEvent.ACTION_UP
                                else -> action
                            }
                            val actionIndex = ids.indexOf(changed).coerceAtLeast(0)
                            touchExecutor.submit {
                                try {
                                    if (allowTouch.get()) bridge.previewPointers(mapped, actionIndex, ids, xy, owner)
                                    else bridge.previewTouch(MotionEvent.ACTION_CANCEL, 0, 0, owner)
                                } catch (_: Throwable) {
                                    try { bridge.previewTouch(MotionEvent.ACTION_CANCEL, 0, 0, owner) } catch (_: Throwable) { }
                                }
                            }
                            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) held.remove(changed)
                            if (action == MotionEvent.ACTION_CANCEL) held.clear()
                            if (held.isEmpty() && action == MotionEvent.ACTION_UP) view.performClick()
                            true
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (!live) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun OverlayLogTab(
    logs: List<String>,
    fallbackMessage: String
) {
    val listState = rememberLazyListState()
    val displayLogs = remember(logs, fallbackMessage) {
        if (logs.isNotEmpty()) logs
        else listOfNotNull(fallbackMessage.takeIf { it.isNotBlank() })
    }
    LaunchedEffect(displayLogs.size) {
        if (displayLogs.isNotEmpty()) listState.scrollToItem(displayLogs.lastIndex)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(172.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        if (displayLogs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无实时日志", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(displayLogs) { line ->
                    val lineColor = when {
                        line.contains("| ERROR |") || line.contains("失败") -> MaterialTheme.colorScheme.error
                        line.contains("| WARNING |") || line.contains("| WARN |") -> Color(0xFFD97706)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        text = line,
                        color = lineColor,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

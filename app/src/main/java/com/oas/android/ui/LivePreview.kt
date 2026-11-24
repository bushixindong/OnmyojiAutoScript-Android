// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android.ui

import android.graphics.PixelFormat
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.oas.android.IDeviceBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

private data class PreviewState(val label: String = "正在连接画面…", val live: Boolean = false,
    val width: Int = 1280, val height: Int = 720, val frames: Long = 0,
    val fps: Double = -1.0, val fpsSource: String = "",val advice: String = "")

/** Only status metadata crosses Binder repeatedly. Frames go straight to the native Surface. */
@Composable
fun GamePreview(controller: OasController) {
    val lifecycle = (LocalActivity.current as ComponentActivity).lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var window by remember { mutableStateOf<Surface?>(null) }
    var state by remember { mutableStateOf(PreviewState()) }
    var manual by remember { mutableStateOf(false) }
    val owner = remember { System.nanoTime() }
    val handover = remember { Mutex() }
    val touchExecutor = remember { Executors.newSingleThreadExecutor() }
    val allowTouch = remember { AtomicBoolean(false) }
    var attachedBridge by remember { mutableStateOf<IDeviceBridge?>(null) }
    val currentState by rememberUpdatedState(state)
    SideEffect { allowTouch.set(manual && !controller.active && !controller.busy && resumed && state.live) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); allowTouch.set(false); touchExecutor.shutdown() }
    }
    LaunchedEffect(controller.connected, controller.deviceEpoch, resumed, window) {
        state = PreviewState(if(!controller.connected) "请先连接 Shizuku 并准备运行环境" else "正在连接画面…")
        if(!controller.connected || !resumed || window == null) return@LaunchedEffect
        handover.withLock {
            var bridge: IDeviceBridge? = null
            var attached = false
            var dimensions = 1280 to 720
            try {
                bridge = controller.session.requireBridge()
                while(isActive) {
                    val status = withContext(Dispatchers.IO) { JSONObject(bridge.previewStatus()) }
                    dimensions = status.optInt("width",1280) to status.optInt("height",720)
                    if(status.optString("mode") == "primary") {
                        state = PreviewState("前台兼容模式无法在本页实时预览，请使用后台虚拟屏")
                    } else if(status.optInt("displayId",-1) < 0) {
                        state = PreviewState("请先在设备与游戏中准备后台虚拟屏")
                    } else {
                        if(!attached || !status.optBoolean("attached")) {
                            withContext(Dispatchers.IO) { bridge.setPreviewSurface(window,owner) }
                            attached = true; attachedBridge = bridge
                        }
                        val age = status.optLong("ageMs",-1)
                        val live = status.optBoolean("attached") && age in 0..2000
                        val error = status.optString("error")
                        state = PreviewState(when {
                            error.isNotBlank() -> "预览异常：$error"
                            live -> "实时预览"
                            else -> "等待虚拟屏新画面…"
                        },live,dimensions.first,dimensions.second,status.optLong("frames"),
                            status.optDouble("fps",-1.0),status.optString("fpsSource"),status.optString("fpsAdvice"))
                    }
                    delay(500)
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(problem: Exception) { state = PreviewState(problem.message ?: "预览连接失败") }
            finally {
                allowTouch.set(false); attachedBridge = null
                if(bridge != null && attached) withContext(NonCancellable + Dispatchers.IO) {
                    try { bridge.clearPreviewSurface(owner) } catch(_: Exception) { }
                }
            }
        }
    }
    LaunchedEffect(manual, controller.active, controller.busy, resumed, state.live) {
        if(!manual || controller.active || controller.busy || !resumed || !state.live) {
            val bridge = attachedBridge
            if(bridge != null) touchExecutor.submit { try { bridge.previewTouch(MotionEvent.ACTION_CANCEL,0,0,owner) } catch(_: Exception) { } }
        }
        if(controller.active || controller.busy || !resumed) manual = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val previewHeight = minOf(maxWidth * state.height / state.width,320.dp)
            Box(Modifier.fillMaxWidth().height(previewHeight).clip(MaterialTheme.shapes.medium).background(Color.Black)) {
                // Recreate after a shell service replacement; the old BufferQueue belongs to the old process.
                key(controller.deviceEpoch,resumed) {
                    if(resumed) AndroidView(factory = { context ->
                        SurfaceView(context).apply {
                            contentDescription = "游戏实时画面"
                            holder.setFormat(PixelFormat.RGBA_8888)
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) { window = holder.surface }
                                override fun surfaceChanged(holder: SurfaceHolder,format: Int,width: Int,height: Int) { window = holder.surface }
                                override fun surfaceDestroyed(holder: SurfaceHolder) { if(window == holder.surface) window = null }
                            })
                            val held=linkedSetOf<Int>()
                            var lastMove=0L
                            setOnTouchListener { view,event ->
                                val bridge=attachedBridge
                                if(!allowTouch.get() || controller.session.taskActive.get() || bridge==null) {
                                    held.clear();view.parent.requestDisallowInterceptTouchEvent(false)
                                    return@setOnTouchListener false
                                }
                                val snapshot=currentState
                                val scale=min(view.width.toFloat()/snapshot.width,view.height.toFloat()/snapshot.height)
                                val offsetX=(view.width-snapshot.width*scale)/2f
                                val offsetY=(view.height-snapshot.height*scale)/2f
                                val action=event.actionMasked
                                val changed=event.getPointerId(event.actionIndex)
                                if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) {
                                    val x=(event.getX(event.actionIndex)-offsetX)/scale
                                    val y=(event.getY(event.actionIndex)-offsetY)/scale
                                    if(x<0 || y<0 || x>=snapshot.width || y>=snapshot.height) return@setOnTouchListener held.isNotEmpty()
                                    if(action==MotionEvent.ACTION_DOWN)held.clear()
                                    if(held.size>=10)return@setOnTouchListener true
                                    held.add(changed);view.parent.requestDisallowInterceptTouchEvent(true)
                                }
                                if(held.isEmpty())return@setOnTouchListener false
                                if(action==MotionEvent.ACTION_MOVE && event.eventTime-lastMove<16)return@setOnTouchListener true
                                if(action!=MotionEvent.ACTION_MOVE && action!=MotionEvent.ACTION_CANCEL && changed !in held)return@setOnTouchListener true
                                lastMove=event.eventTime
                                val indices=(0 until event.pointerCount).filter { event.getPointerId(it) in held }
                                val ids=indices.map { event.getPointerId(it) }.toIntArray()
                                val xy=FloatArray(ids.size*2)
                                indices.forEachIndexed { slot,index ->
                                    xy[slot*2]=((event.getX(index)-offsetX)/scale).coerceIn(0f,snapshot.width-1f)
                                    xy[slot*2+1]=((event.getY(index)-offsetY)/scale).coerceIn(0f,snapshot.height-1f)
                                }
                                val mapped=when {
                                    action==MotionEvent.ACTION_POINTER_DOWN && ids.size==1 -> MotionEvent.ACTION_DOWN
                                    action==MotionEvent.ACTION_POINTER_UP && ids.size==1 -> MotionEvent.ACTION_UP
                                    else -> action
                                }
                                val actionIndex=ids.indexOf(changed).coerceAtLeast(0)
                                touchExecutor.submit {
                                    try {
                                        if(allowTouch.get() && !controller.session.taskActive.get()) bridge.previewPointers(mapped,actionIndex,ids,xy,owner)
                                        else bridge.previewTouch(MotionEvent.ACTION_CANCEL,0,0,owner)
                                    } catch(problem: Exception) {
                                        try {bridge.previewTouch(MotionEvent.ACTION_CANCEL,0,0,owner)}catch(_: Exception){}
                                        allowTouch.set(false)
                                        view.post {manual=false;controller.notify(problem.message ?: "手动控制失败")}
                                    }
                                }
                                if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP)held.remove(changed)
                                if(action==MotionEvent.ACTION_CANCEL)held.clear()
                                if(held.isEmpty()) {view.parent.requestDisallowInterceptTouchEvent(false);if(action==MotionEvent.ACTION_UP)view.performClick()}
                                true
                            }
                        }
                    },modifier=Modifier.fillMaxSize())
                }
                if(!state.live) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) {
                    Text(state.label,Modifier.padding(20.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(state.live) Surface(Modifier.align(Alignment.TopEnd).padding(8.dp),shape=MaterialTheme.shapes.extraLarge,
                    color=MaterialTheme.colorScheme.surface.copy(alpha=.9f)) {
                    Text("● 实时预览",Modifier.padding(horizontal=10.dp,vertical=5.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("手动控制",style=MaterialTheme.typography.bodyMedium)
                Text(if(controller.active) "脚本运行中，手动控制已禁用" else "开启后支持点击、滑动与多点触控",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=manual,onCheckedChange={ manual=it },enabled=state.live && !controller.active && !controller.busy,
                modifier=Modifier.semantics{contentDescription="手动控制开关"})
        }
        if(state.live && state.fps>=0)Text("${state.fpsSource} · ${"%.1f".format(state.fps)} FPS",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(state.advice.isNotBlank())Text(state.advice,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
    }
}

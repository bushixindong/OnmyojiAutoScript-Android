// SPDX-License-Identifier: AGPL-3.0-only
// Display flags and shell display construction adapted from MaaMeow. See THIRD_PARTY.md.
package com.oas.android.bridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Surface;
import com.oas.android.IDeviceBridge;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Runs only inside a Shizuku shell user service (UID 2000), never in the app process. */
public final class DeviceBridge extends IDeviceBridge.Stub {
    // Reserved Shizuku UserService Binder destroy handshake (13.1.5 protocol).
    // Keep the wire ID here rather than importing its @RestrictTo library API.
    private static final int SHIZUKU_DESTROY_TRANSACTION = 16777115;
    public static final int WIDTH = 1280, HEIGHT = 720;
    private int densityDpi = 240;
    private int frameWidth=WIDTH,frameHeight=HEIGHT;
    private final HandlerThread controlThread = new HandlerThread("oas-control");
    private final HandlerThread frameThread = new HandlerThread("oas-frames");
    private final Handler control;
    private final Handler frames;
    private final Object frameLock = new Object();
    private VirtualDisplay display;
    private ImageReader reader;
    private volatile DisplayRenderer renderer;
    private long previewOwner;
    private final ManualTouchController manual=new ManualTouchController();
    private VirtualDeviceDisplay virtualDevice;
    private ScreenPower power;
    private GameAudio audio;
    private final GameFpsMonitor fps;
    private boolean runtimeActive,keepAwake=true,fpsEnabled=true;
    private int captureFps=30,previewFps=30;
    private String targetPackage;
    private final Runnable cancelManual = () -> { try { cancelManualTouch(); } catch(Exception e) { android.util.Log.w("OAS","Cancel manual touch",e); } };
    private RawFrameStore rawStore;
    private long rawEpoch;
    private volatile long frameAt;
    private volatile int displayId = -1;
    private volatile boolean closed;
    private int shellChildPid = -1, shellChildFd = -1;
    private String flagsDescription = "";
    private String gamePackage;
    private boolean primaryMode;

    public DeviceBridge() {
        controlThread.start(); frameThread.start();
        control = new Handler(controlThread.getLooper());
        frames = new Handler(frameThread.getLooper());
        fps=new GameFpsMonitor(control,()->renderer==null?-1:renderer.compositionFrames);
    }

    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == SHIZUKU_DESTROY_TRANSACTION) {
            destroy(); return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    private <T> T call(Callable<T> action) {
        long identity = Binder.clearCallingIdentity();
        try {
            if (closed) throw new IllegalStateException("设备服务已关闭，请重新连接");
            FutureTask<T> task = new FutureTask<>(action);
            if (!control.post(task)) throw new IllegalStateException("设备线程已停止");
            try { return task.get(60, TimeUnit.SECONDS); }
            catch (Exception e) {
                task.cancel(true);
                Throwable cause = e.getCause() == null ? e : e.getCause();
                while (cause instanceof InvocationTargetException && cause.getCause() != null) cause = cause.getCause();
                throw new IllegalStateException(cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
            }
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private <T> T onFrames(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        if(!frames.post(task)) throw new IllegalStateException("画面线程已停止");
        return task.get(10, TimeUnit.SECONDS);
    }

    @Override public String status() {
        return call(() -> new JSONObject().put("uid", android.os.Process.myUid())
            .put("sdk", Build.VERSION.SDK_INT).put("displayId", displayId)
            .put("width", frameWidth).put("height", frameHeight).put("dpi", densityDpi)
            .put("mode", primaryMode ? "primary" : "virtual")
            .put("gamePackage", gamePackage == null ? "" : gamePackage)
            .put("gameDisplayId", gamePackage == null ? -2 : TaskController.displayOf(gamePackage))
            .put("isForeground",gamePackage!=null&&TaskController.isTopOnDisplay(gamePackage,displayId))
            .put("previewAttached", renderer != null && renderer.attached)
            .put("previewFrames", renderer == null ? 0 : renderer.previewFrames)
            .put("previewAgeMs", renderer == null || renderer.previewAt == 0 ? -1 : SystemClock.elapsedRealtime() - renderer.previewAt)
            .put("previewError", renderer == null ? "" : renderer.error)
            .put("flags", flagsDescription).put("frameAgeMs", frameAt == 0 ? -1 : SystemClock.elapsedRealtime() - frameAt).toString());
    }

    @Override public int createDisplay(int dpi) {
        return call(() -> {
            if (dpi != 160 && dpi != 240) throw new IllegalArgumentException("DPI 仅支持 160 或 240");
            if (display != null && densityDpi != dpi) throw new IllegalStateException("请先释放虚拟屏，再更改 DPI");
            primaryMode = false;
            densityDpi = dpi; return createInternal();
        });
    }

    @Override public void usePrimaryDisplay() {
        call(() -> { releaseInternal(); primaryMode = true; displayId = 0; return null; });
    }

    private int createInternal() throws Exception {
        if (display != null) return displayId;
        reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::receiveFrame, frames);
        try {
            rawStore=new RawFrameStore();rawEpoch++;
            renderer = onFrames(() -> new DisplayRenderer(frames, reader.getSurface(), WIDTH, HEIGHT));
            if(Build.VERSION.SDK_INT>=34) {
                try {
                    virtualDevice=VirtualDeviceDisplay.create(renderer.input(),WIDTH,HEIGHT,densityDpi);
                    display=virtualDevice.display;displayId=display.getDisplay().getDisplayId();flagsDescription=virtualDevice.description;
                    displayReady();return displayId;
                }catch(Exception failure){virtualDevice=null;flagsDescription="VirtualDevice unavailable: "+failure.getMessage();android.util.Log.w("OAS","Falling back to legacy virtual display",failure);}
            }
            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            DisplayManager manager = ctor.newInstance(new ShellContext());
            // Same tiered fallback as MaaMeow: modern shell permissions vary by ROM.
            int base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | (1 << 6) | (1 << 8);
            int modern = (1 << 10) | (1 << 11) | (1 << 12) | (1 << 13);
            if (Build.VERSION.SDK_INT >= 34) modern |= (1 << 14) | (1 << 15) | (1 << 16);
            int[] tiers = Build.VERSION.SDK_INT >= 33 ? new int[]{base | modern, base | (1 << 13)}
                    : Build.VERSION.SDK_INT >= 31 ? new int[]{base | (1 << 10), base} : new int[]{base};
            for (int i = 0; i < tiers.length; i++) {
                try {
                    display = manager.createVirtualDisplay("OAS_720P", WIDTH, HEIGHT, densityDpi, renderer.input(), tiers[i]);
                    flagsDescription = "tier=" + i + ", flags=0x" + Integer.toHexString(tiers[i]);
                    break;
                } catch (SecurityException e) { if (i == tiers.length - 1) throw e; }
            }
            if (display == null) throw new IllegalStateException("系统未创建虚拟屏");
            displayId = display.getDisplay().getDisplayId();
            displayReady();
            return displayId;
        } catch (Exception e) { releaseInternal(); throw e; }
    }

    private void receiveFrame(ImageReader source) {
        try (Image image = source.acquireLatestImage()) {
            if (image == null) return;
            Image.Plane plane = image.getPlanes()[0];
            int pixelStride = plane.getPixelStride(), rowStride = plane.getRowStride();
            if (pixelStride != 4) return;
            synchronized(frameLock) {
                if(source!=reader || rawStore==null)return;
                NativeFrames.publish(rawStore.mapping,plane.getBuffer(),image.getWidth(),image.getHeight(),rowStride,SystemClock.elapsedRealtime());
                frameAt=SystemClock.elapsedRealtime();frameLock.notifyAll();
            }
        } catch (IllegalStateException ignored) { /* reader released while its callback was queued */ }
        catch (RuntimeException error) { android.util.Log.e("OAS", "Frame conversion failed", error); }
    }

    @Override public void releaseDisplay() { call(() -> { releaseInternal(); return null; }); }

    private void releaseInternal() throws Exception {
        cancelManualTouch();
        // Releasing a DESTROY_CONTENT display would remove its game task. Restore it first.
        if (display != null && gamePackage != null && displayId >= 0 && TaskController.displayOf(gamePackage) == displayId)
            TaskController.moveExisting(gamePackage, 0);
        gamePackage = null;targetPackage=null;fps.close();
        if(audio!=null)audio.close();
        if(power!=null)power.close();
        if(Build.VERSION.SDK_INT>=34 && virtualDevice!=null){virtualDevice.close();virtualDevice=null;}
        else if (display != null) display.release();
        display = null; displayId = -1;
        onFrames(() -> { if(renderer != null) renderer.close(); renderer=null; previewOwner=0; return null; });
        frameWidth=WIDTH;frameHeight=HEIGHT;
        synchronized (frameLock) {
            ImageReader old = reader; reader = null;
            if (old != null) { old.setOnImageAvailableListener(null, null); old.close(); }
            if(rawStore!=null)rawStore.close();rawStore=null;rawEpoch++;
            frameAt = 0; frameLock.notifyAll();
        }
    }

    @Override public ParcelFileDescriptor screenshot() {
        return call(() -> {
            if (primaryMode && displayId == 0) return primaryScreenshot();
            if (display == null) throw new IllegalStateException("请先创建虚拟屏");
            if (gamePackage != null && TaskController.displayOf(gamePackage) != displayId)
                throw new IllegalStateException("游戏已退出或离开虚拟屏，停止使用旧画面");
            byte[] png;
            long deadline = SystemClock.elapsedRealtime() + 5000;
            synchronized (frameLock) {
                while (frameAt == 0 && SystemClock.elapsedRealtime() < deadline) frameLock.wait(100);
                if (frameAt == 0) throw new IllegalStateException("尚未收到画面，请先启动游戏或测试画面");
                if (SystemClock.elapsedRealtime() - frameAt > 5000) throw new IllegalStateException("截图已超过 5 秒未更新，请检查游戏或虚拟屏状态");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                Bitmap image=RawFrameReader.bitmap(NativeFrames.read(rawStore.mapping,SystemClock.elapsedRealtime()));
                try {image.compress(Bitmap.CompressFormat.PNG, 100, out);} finally {image.recycle();}
                png = out.toByteArray();
            }
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { out.write(png); }
                catch (Exception ignored) { /* receiving app closed */ }
            }, "oas-screenshot-pipe").start();
            return pipe[0];
        });
    }

    @Override public Bundle rawFrameBuffer() {
        return call(() -> {
            if(primaryMode)return null;
            if(display==null || rawStore==null)throw new IllegalStateException("请先创建虚拟屏");
            if(gamePackage!=null && TaskController.displayOf(gamePackage)!=displayId)throw new IllegalStateException("游戏已离开虚拟屏");
            synchronized(frameLock) {
                long deadline=SystemClock.elapsedRealtime()+5000;
                while(frameAt==0 && SystemClock.elapsedRealtime()<deadline)frameLock.wait(100);
                if(frameAt==0 || SystemClock.elapsedRealtime()-frameAt>5000)throw new IllegalStateException("虚拟屏没有有效新画面");
                Bundle value=new Bundle();value.putParcelable("memory",rawStore.memory);value.putLong("epoch",rawEpoch);return value;
            }
        });
    }

    @Override public void setPreviewSurface(Surface surface, long owner) {
        call(() -> {
            if(surface == null) throw new IllegalArgumentException("缺少预览窗口");
            if(primaryMode || display == null || renderer == null) {
                surface.release(); throw new IllegalStateException("实时预览需要后台虚拟屏");
            }
            cancelManualTouch();
            onFrames(() -> { renderer.setPreview(surface); return null; });
            previewOwner=owner;if(power!=null)power.update(runtimeActive&&keepAwake,true); return null;
        });
    }
    @Override public void clearPreviewSurface(long owner) {
        call(() -> {
            if(previewOwner==owner) {
                cancelManualTouch();
                onFrames(() -> { if(renderer!=null) renderer.setPreview(null); return null; });
                previewOwner=0;if(power!=null)power.update(runtimeActive&&keepAwake,false);
            }
            return null;
        });
    }

    @Override public String previewStatus() {
        return call(() -> new JSONObject().put("displayId",displayId).put("mode",primaryMode ? "primary" : "virtual")
            .put("width",frameWidth).put("height",frameHeight)
            .put("attached",renderer!=null && renderer.attached)
            .put("frames",renderer==null ? 0 : renderer.previewFrames)
            .put("fps",fps.fps()).put("fpsSource",fps.source()).put("fpsAdvice",fps.advice())
            .put("ageMs",renderer==null || renderer.previewAt==0 ? -1 : SystemClock.elapsedRealtime()-renderer.previewAt)
            .put("error",renderer==null ? "" : renderer.error).toString());
    }

    @Override public void previewTouch(int action,int x,int y,long owner) {
        previewPointers(action,0,new int[]{0},new float[]{x,y},owner);
    }
    @Override public void previewPointers(int action,int index,int[] ids,float[] coordinates,long owner) {
        call(() -> {
            if(owner!=previewOwner || renderer==null || !renderer.attached || primaryMode)throw new IllegalStateException("预览已断开");
            if(action==MotionEvent.ACTION_CANCEL){cancelManualTouch();return null;}
            if(coordinates==null||ids==null||coordinates.length!=ids.length*2)throw new IllegalArgumentException("触点数据不完整");
            for(int i=0;i<coordinates.length;i+=2){
                if(!Float.isFinite(coordinates[i])||!Float.isFinite(coordinates[i+1]))throw new IllegalArgumentException("触点坐标无效");
                if(coordinates[i]<0||coordinates[i]>=frameWidth||coordinates[i+1]<0||coordinates[i+1]>=frameHeight)throw new IllegalArgumentException("触点超出运行屏幕");
            }
            if(action==MotionEvent.ACTION_DOWN){cancelManualTouch();requireForeground();}
            try {
                manual.accept(action,index,ids,coordinates,displayId);control.removeCallbacks(cancelManual);
                if(action!=MotionEvent.ACTION_UP)control.postDelayed(cancelManual,15000);
            }catch(Exception failure){cancelManualTouch();throw failure;}
            return null;
        });
    }
    private void cancelManualTouch() throws Exception {control.removeCallbacks(cancelManual);manual.cancel();}

    private void displayReady() throws Exception {
        renderer.captureIntervalMs=runtimeActive?Math.max(1,1000/captureFps):180;renderer.previewIntervalMs=Math.max(1,1000/previewFps);
        if(power==null)power=new ScreenPower(control);
        power.target(displayId,display.getDisplay());power.update(runtimeActive&&keepAwake,previewOwner!=0);
        if(display.getDisplay().getRotation()!=Surface.ROTATION_0)try {
            Object window=ShellApis.service("window","android.view.IWindowManager");
            try{ShellApis.invoke(window.getClass().getMethod("freezeDisplayRotation",int.class,int.class,String.class),window,displayId,0,"OASA");}
            catch(NoSuchMethodException older){ShellApis.invoke(window.getClass().getMethod("freezeDisplayRotation",int.class,int.class),window,displayId,0);}
        }catch(Exception error){android.util.Log.w("OAS","Virtual-display rotation",error);}
    }
    private void monitorTarget(String pkg) {
        targetPackage=pkg;if(fpsEnabled)fps.start(pkg);
    }
    @Override public String systemStatus() {
        return call(() -> {
            JSONObject value=power==null?new JSONObject():power.status();
            return value.put("backend",primaryMode?"主屏":virtualDevice==null?"VirtualDisplay":"VirtualDevice")
                .put("displayId",displayId).put("flags",flagsDescription).put("fps",fps.fps()).put("fpsSource",fps.source()).put("fpsAdvice",fps.advice())
                .put("rawFrames",!primaryMode&&rawStore!=null).put("runtimeActive",runtimeActive)
                .put("captureFps",captureFps).put("previewFps",previewFps).toString();
        });
    }
    @Override public void configureRuntime(boolean active,boolean awake,boolean monitorFps) {
        call(() -> {
            runtimeActive=active;keepAwake=awake;
            if(renderer!=null)renderer.captureIntervalMs=active?Math.max(1,1000/captureFps):180;
            if(power==null)power=new ScreenPower(control);
            if(primaryMode)power.target(displayId,null);
            power.update(active&&awake,previewOwner!=0);
            if(!active)power.restorePanel();
            if(fpsEnabled!=monitorFps){fpsEnabled=monitorFps;if(fpsEnabled&&targetPackage!=null)fps.start(targetPackage);else fps.close();}
            return null;
        });
    }
    @Override public void configureFrameRates(int capture,int preview) {
        call(() -> {
            if((capture!=15&&capture!=30&&capture!=60)||(preview!=30&&preview!=60))throw new IllegalArgumentException("不支持的帧率");
            captureFps=capture;previewFps=preview;
            if(renderer!=null){renderer.captureIntervalMs=runtimeActive?Math.max(1,1000/captureFps):180;renderer.previewIntervalMs=Math.max(1,1000/previewFps);}
            return null;
        });
    }
    @Override public boolean setDisplayPower(boolean on) {return call(() -> {if(power==null)power=new ScreenPower(control);return power.panel(on);});}
    @Override public String gameAudioState(String pkg) {return call(() -> {if(audio==null)audio=new GameAudio(control);return audio.state(pkg);});}
    @Override public boolean setGameMuted(String pkg,boolean muted,int previous) {return call(() -> {if(audio==null)audio=new GameAudio(control);return audio.set(pkg,muted,previous);});}

    private ParcelFileDescriptor primaryScreenshot() throws Exception {
        if (gamePackage != null && !TaskController.isTopOnDisplay(gamePackage, 0))
            throw new IllegalStateException("游戏不在前台，已停止截取其他应用");
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        Process process = new ProcessBuilder("screencap", "-p").start();
        new Thread(() -> {
            try (InputStream input = process.getInputStream();
                 ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                byte[] buffer = new byte[16384]; int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                frameAt = SystemClock.elapsedRealtime();
            } catch (Exception error) { android.util.Log.w("OAS", "Primary screenshot ended", error); }
            finally { process.destroy(); }
        }, "oas-primary-screenshot").start();
        return pipe[0];
    }

    @Override public String launch(String packageName) {
        return call(() -> {
            if (displayId < 0) throw new IllegalStateException("请先创建虚拟屏");
            if (packageName == null || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
                throw new IllegalArgumentException("包名无效");
            boolean restartAfterMove = false;
            if (!packageName.equals("com.oas.android")) {
                try {
                    if (TaskController.moveExisting(packageName, displayId)) {
                        gamePackage = packageName;
                        synchronized (frameLock) { frameAt = 0; }
                        monitorTarget(packageName);
                        return "已接管运行中的游戏 · Display " + displayId + "，保留当前会话";
                    }
                } catch (Exception error) {
                    // Some game engines exit themselves when their display configuration changes.
                    // Restart only after the old task has disappeared; never duplicate a live session.
                    if (TaskController.find(packageName) != null) throw error;
                    restartAfterMove = true;
                    android.util.Log.w("OAS", "Game exited during display migration; cold-starting on target display", error);
                }
            }
            String component;
            if (packageName.equals("com.oas.android")) component = "com.oas.android/.ProbeActivity";
            else {
                String result = command("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.LAUNCHER", packageName);
                component = null;
                for (String line : result.split("\n")) if (line.trim().matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) component = line.trim();
                if (component == null) throw new IllegalStateException("没有找到应用启动入口：" + result.trim());
            }
            // No force-stop: do not discard an ongoing game session.
            String output = command("am", "start", "--display", Integer.toString(displayId), "--windowingMode", "1", "-n", component);
            String lower = output.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("error:") || lower.contains("exception") || lower.contains("error type")) throw new IllegalStateException(output);
            if (!packageName.equals("com.oas.android")) {
                gamePackage = packageName;
                // A channel SDK can start a second Activity back on display 0 after launch.
                SystemClock.sleep(2000);
                if (!TaskController.moveExisting(packageName, displayId)) throw new IllegalStateException("启动后未找到游戏任务");
            }
            synchronized (frameLock) {
                frameAt = 0;
            }
            monitorTarget(packageName);
            return (restartAfterMove ? "游戏在切换屏幕后退出，已在目标屏重新启动\n" : "") + output.trim();
        });
    }

    private static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) { byte[] buffer = new byte[2048]; int n; while ((n = stream.read(buffer)) != -1) output.write(buffer, 0, n); }
            catch (Exception ignored) { }
        });
        drain.start();
        if (!process.waitFor(12, TimeUnit.SECONDS)) { process.destroy(); throw new IllegalStateException("设备命令超时"); }
        drain.join(1000);
        String result = output.toString("UTF-8");
        if (process.exitValue() != 0) throw new IllegalStateException(result);
        return result;
    }

    @Override public void tap(int x, int y) { swipe(x, y, x, y, 70); }

    @Override public void swipe(int x1, int y1, int x2, int y2, int durationMs) {
        pressAndDrag(x1,y1,x2,y2,0,durationMs);
    }
    @Override public void pressAndDrag(int x1,int y1,int x2,int y2,int holdMs,int durationMs){
        call(() -> {
            cancelManualTouch();
            if (displayId < 0) throw new IllegalStateException("虚拟屏未创建");
            if (gamePackage != null && !TaskController.isTopOnDisplay(gamePackage, displayId))
                throw new IllegalStateException("游戏不在目标屏幕最前方，已停止触控");
            checkPoint(x1, y1); checkPoint(x2, y2);
            int duration = Math.max(1, Math.min(5000, durationMs));
            int hold=Math.max(0,Math.min(10000,holdMs));
            Object input = new ShellContext().getSystemService(Context.INPUT_SERVICE);
            Method inject = input.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
            Method setDisplay = InputEvent.class.getMethod("setDisplayId", int.class);
            long down = SystemClock.uptimeMillis();
            int steps = Math.max(1, duration / 16);
            boolean pressed = false;
            try {
                send(input, inject, setDisplay, down, MotionEvent.ACTION_DOWN, x1, y1); pressed = true;
                if(hold>0)SystemClock.sleep(hold);
                long move=down+hold;
                for (int i = 1; i < steps; i++) {
                    SystemClock.sleep(Math.max(1, move + (long) duration * i / steps - SystemClock.uptimeMillis()));
                    send(input, inject, setDisplay, down, MotionEvent.ACTION_MOVE,
                         x1 + (x2 - x1) * i / (float) steps, y1 + (y2 - y1) * i / (float) steps);
                }
                SystemClock.sleep(Math.max(1, move + duration - SystemClock.uptimeMillis()));
                send(input, inject, setDisplay, down, MotionEvent.ACTION_UP, x2, y2); pressed = false;
            } finally {
                if (pressed) send(input, inject, setDisplay, down, MotionEvent.ACTION_CANCEL, x2, y2);
            }
            return null;
        });
    }

    @Override public void drawPath(int[] coordinates,int intervalMs){
        call(()->{
            cancelManualTouch();
            if(coordinates==null||coordinates.length<4||coordinates.length%2!=0||coordinates.length>2000)
                throw new IllegalArgumentException("绘制路径需要 2 至 1000 个点");
            if(intervalMs<1||intervalMs>100||((long)coordinates.length/2)*intervalMs>15000)
                throw new IllegalArgumentException("绘制时间超出限制");
            requireForeground();
            for(int i=0;i<coordinates.length;i+=2)checkPoint(coordinates[i],coordinates[i+1]);
            Object input=new ShellContext().getSystemService(Context.INPUT_SERVICE);
            Method inject=input.getClass().getMethod("injectInputEvent",InputEvent.class,int.class);
            Method setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);
            long down=SystemClock.uptimeMillis();boolean pressed=false;int last=0;
            try{
                send(input,inject,setDisplay,down,MotionEvent.ACTION_DOWN,coordinates[0],coordinates[1]);pressed=true;
                for(int i=2;i<coordinates.length;i+=2){
                    SystemClock.sleep(Math.max(1,down+(long)(i/2)*intervalMs-SystemClock.uptimeMillis()));
                    requireForeground();
                    send(input,inject,setDisplay,down,MotionEvent.ACTION_MOVE,coordinates[i],coordinates[i+1]);last=i;
                }
                send(input,inject,setDisplay,down,MotionEvent.ACTION_UP,coordinates[last],coordinates[last+1]);pressed=false;
            }finally{if(pressed)send(input,inject,setDisplay,down,MotionEvent.ACTION_CANCEL,coordinates[last],coordinates[last+1]);}
            return null;
        });
    }
    @Override public ParcelFileDescriptor readTaskFile(String path){
        return call(()->{
            java.io.File file=new java.io.File(path).getCanonicalFile();
            if(!file.getPath().startsWith("/storage/emulated/0/Android/data/")||!file.getPath().contains("/files/netease/onmyoji/Documents/userdata/")||!file.getName().equals("pvp2v2_record"))
                throw new IllegalArgumentException("只能读取任务所需的游戏战报");
            if(file.length()>64L*1024*1024)throw new IllegalArgumentException("战报文件过大");
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        });
    }
    private void checkPoint(int x, int y) {
        if (x < 0 || x >= frameWidth || y < 0 || y >= frameHeight) throw new IllegalArgumentException("触控坐标超出运行屏幕");
    }
    private void requireForeground()throws Exception{
        String expected=gamePackage==null?"com.oas.android":gamePackage;
        if(displayId<0||!TaskController.isTopOnDisplay(expected,displayId))throw new IllegalStateException("应用不在目标屏幕前台");
    }

    @Override public void resizeDisplay(int width,int height){
        call(()->{
            cancelManualTouch();
            if(!((width==1280&&height==720)||(width==720&&height==1280)))throw new IllegalArgumentException("仅支持标准横屏或竖屏");
            if(primaryMode||display==null)throw new IllegalStateException("竖屏任务需要虚拟屏，不修改手机主屏设置");
            if(width==frameWidth&&height==frameHeight)return null;
            ImageReader replacement=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);
            replacement.setOnImageAvailableListener(this::receiveFrame,frames);
            ImageReader previous=reader;
            RawFrameStore replacementStore;
            try{replacementStore=new RawFrameStore();}catch(Exception failure){replacement.close();throw failure;}
            int oldWidth=frameWidth,oldHeight=frameHeight;
            try{
                onFrames(() -> { renderer.setCapture(replacement.getSurface(),width,height); return null; });
                display.resize(width,height,densityDpi);
                synchronized(frameLock){reader=replacement;frameWidth=width;frameHeight=height;RawFrameStore old=rawStore;rawStore=replacementStore;rawEpoch++;frameAt=0;if(old!=null)old.close();}
                if(previous!=null)previous.close();
            }catch(Exception error){
                try{onFrames(()->{renderer.setCapture(previous.getSurface(),oldWidth,oldHeight);return null;});display.resize(oldWidth,oldHeight,densityDpi);}
                catch(Exception rollback){error.addSuppressed(rollback);}
                replacementStore.close();replacement.close();throw error;
            }
            return null;
        });
    }
    @Override public String shell(String[] arguments){
        return call(()->{
            if(arguments!=null&&arguments.length==6&&arguments[0].equals("find")&&arguments[1].matches("/sdcard/Android/data/[A-Za-z0-9_.]+/files/netease/onmyoji/Documents/userdata")&&arguments[2].equals("-name")&&arguments[3].equals("pvp2v2_record")&&arguments[4].equals("-type")&&arguments[5].equals("f"))return command(arguments);
            if(arguments==null||arguments.length==0||!java.util.Arrays.asList("getprop","pidof","ps","dumpsys","cmd","pm","uiautomator","cat","ls").contains(arguments[0]))
                throw new IllegalArgumentException("该命令不是手机任务所需的查询操作");
            try{return command(arguments);}catch(IllegalStateException error){if(arguments[0].equals("pidof"))return "";throw error;}
        });
    }
    @Override public void stopPackage(String packageName){
        call(()->{if(packageName==null||!packageName.matches("[A-Za-z0-9_.]+"))throw new IllegalArgumentException("包名无效");command("am","force-stop",packageName);return null;});
    }
    @Override public void keyEvent(int keyCode){
        call(()->{
            requireForeground();
            Object input=new ShellContext().getSystemService(Context.INPUT_SERVICE);
            Method inject=input.getClass().getMethod("injectInputEvent",InputEvent.class,int.class);
            Method setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);long now=SystemClock.uptimeMillis();
            for(int action:new int[]{android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.ACTION_UP}){
                android.view.KeyEvent event=new android.view.KeyEvent(now,SystemClock.uptimeMillis(),action,keyCode,0);
                setDisplay.invoke(event,displayId);if(!Boolean.TRUE.equals(inject.invoke(input,event,2)))throw new IllegalStateException("键盘事件被系统拒绝");
            }
            return null;
        });
    }
    @Override public void inputText(String text){
        call(()->{
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)new ShellContext().getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OAS task input",text));return null;
        });
        keyEvent(android.view.KeyEvent.KEYCODE_PASTE);
    }
    @Override public void selectAllText(){
        call(()->{
            requireForeground();
            Object input=new ShellContext().getSystemService(Context.INPUT_SERVICE);
            Method inject=input.getClass().getMethod("injectInputEvent",InputEvent.class,int.class);
            Method setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);long now=SystemClock.uptimeMillis();
            for(int action:new int[]{android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.ACTION_UP}){
                android.view.KeyEvent event=new android.view.KeyEvent(now,SystemClock.uptimeMillis(),action,android.view.KeyEvent.KEYCODE_A,0,android.view.KeyEvent.META_CTRL_ON);
                setDisplay.invoke(event,displayId);if(!Boolean.TRUE.equals(inject.invoke(input,event,2)))throw new IllegalStateException("文本选择被系统拒绝");
            }
            return null;
        });
    }

    private void send(Object input, Method inject, Method setDisplay, long down, int action, float x, float y) throws Exception {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        try {
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            setDisplay.invoke(event, displayId);
            if (!Boolean.TRUE.equals(inject.invoke(input, event, 2))) throw new IllegalStateException("系统拒绝触控，请检查 USB 调试安全设置");
        } finally { event.recycle(); }
    }

    @Override public void spawnShellBridge(String apkPath, String token) {
        call(() -> {
            if (android.os.Process.myUid() != 0) throw new SecurityException("仅 Root 服务进程可派生降权 Shell 桥");
            if (shellChildPid > 0 || shellChildFd >= 0) {
                NativeFrames.stopShellBridge(shellChildPid, shellChildFd);
                shellChildPid = -1; shellChildFd = -1;
            }
            int[] child = NativeFrames.spawnShellBridge(apkPath, token);
            shellChildPid = child[0]; shellChildFd = child[1];
            return null;
        });
    }

    @Override public void destroy() {
        if (closed) return;
        if (shellChildPid > 0 || shellChildFd >= 0) {
            NativeFrames.stopShellBridge(shellChildPid, shellChildFd);
            shellChildPid = -1; shellChildFd = -1;
        }
        releaseDisplay(); closed = true;
        controlThread.quitSafely(); frameThread.quitSafely();
        // Shizuku's user-service contract uses destroy() to terminate its isolated process.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}

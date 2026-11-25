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
    private PrimaryCapture primaryCapture;
    private long primaryReadAt;
    private boolean primaryCaptureUnavailable;
    private ImageReader reader;
    private volatile FramePipeline renderer;
    private long previewOwner;
    private final ManualTouchController manual=new ManualTouchController();
    private VirtualDeviceDisplay virtualDevice;
    private ScreenPower power;
    private GameAudio audio;
    private final GameFpsMonitor fps;
    private boolean runtimeActive,keepAwake=true,fpsEnabled=true;
    private boolean scheduleIdle;
    private int captureFps=30,previewFps=30;
    private String targetPackage;
    private int absentChecks, moveAttempts;
    private boolean invalidTarget;
    private final Runnable inspectDisplay = this::inspectDisplayTarget;
    private final Runnable cancelManual = () -> { try { cancelManualTouch(); } catch(Exception e) { android.util.Log.w("OAS","Cancel manual touch",e); } };
    private RawFrameStore rawStore;
    private long rawEpoch;
    private volatile long frameAt;
    private long captureSequence;
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
        fps=new GameFpsMonitor(control,()-> { FramePipeline current=renderer; return current==null?-1:current.compositionCount(); });
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
        try {
            rawStore=new RawFrameStore();rawEpoch++;
            try {
                renderer = onFrames(() -> new HardwareFramePipeline(frames, WIDTH, HEIGHT,
                    rawStore.mapping, this::framePublished));
            } catch (Exception unavailable) {
                android.util.Log.w("OAS", "Native capture unavailable, using EGL capture", unavailable);
                reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3);
                reader.setOnImageAvailableListener(this::receiveFrame, frames);
                renderer = onFrames(() -> new DisplayRenderer(frames, reader.getSurface(), WIDTH, HEIGHT));
            }
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
                framePublished();
            }
        } catch (IllegalStateException ignored) { /* reader released while its callback was queued */ }
        catch (RuntimeException error) { android.util.Log.e("OAS", "Frame conversion failed", error); }
    }

    private void framePublished() {
        synchronized (frameLock) {
            frameAt = SystemClock.elapsedRealtime();
            captureSequence++;
            frameLock.notifyAll();
        }
    }

    @Override public void releaseDisplay() { call(() -> { releaseInternal(); return null; }); }

    private void releaseInternal() throws Exception {
        cancelManualTouch();
        control.removeCallbacks(inspectDisplay);
        if (primaryCapture != null) closePrimaryCapture();
        primaryCaptureUnavailable = false;
        invalidTarget = false;
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
            awaitCapture();
            byte[] raw;
            synchronized (frameLock) {
                raw=NativeFrames.read(rawStore.mapping,SystemClock.elapsedRealtime());
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            Bitmap image = RawFrameReader.bitmap(raw);
            try { image.compress(Bitmap.CompressFormat.PNG, 100, encoded); }
            finally { image.recycle(); }
            byte[] png = encoded.toByteArray();
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
            if (primaryMode) {
                if (!ensurePrimaryCapture()) return null;
                try {
                    awaitCapture(1200);
                    requireForeground();
                    primaryReadAt = SystemClock.elapsedRealtime();
                    return mappedFrame();
                } catch (IllegalStateException unavailable) {
                    closePrimaryCapture();
                    primaryCaptureUnavailable = true;
                    requireForeground();
                    android.util.Log.i("OAS", "Primary continuous capture unavailable, using screencap", unavailable);
                    return null;
                }
            }
            if(display==null || rawStore==null)throw new IllegalStateException("请先创建虚拟屏");
            if(gamePackage!=null && TaskController.displayOf(gamePackage)!=displayId)throw new IllegalStateException("游戏已离开虚拟屏");
            awaitCapture();
            return mappedFrame();
        });
    }

    private Bundle mappedFrame() {
        Bundle value = new Bundle();
        value.putParcelable("memory", rawStore.memory);
        value.putLong("epoch", rawEpoch);
        return value;
    }

    private void awaitCapture() throws InterruptedException { awaitCapture(5000); }

    private void awaitCapture(long timeoutMs) throws InterruptedException {
        FramePipeline current = renderer;
        if (current == null) throw new IllegalStateException("尚未创建取图通道");
        synchronized (frameLock) {
            long before = captureSequence;
            if (!frames.post(current::requestCapture)) throw new IllegalStateException("画面线程已停止");
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            while (captureSequence == before) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new IllegalStateException("虚拟屏没有有效新画面");
                frameLock.wait(remaining);
            }
        }
    }

    private boolean ensurePrimaryCapture() throws Exception {
        if (displayId != 0 || gamePackage == null || primaryCaptureUnavailable) return false;
        requireForeground();
        int[] size;
        try { size = PrimaryCapture.dimensions(); }
        catch (Exception unavailable) { primaryCaptureUnavailable = true; return false; }
        if (primaryCapture != null && (primaryCapture.width != size[0] || primaryCapture.height != size[1])) closePrimaryCapture();
        if (size[0] != WIDTH || size[1] != HEIGHT) return false;
        if (primaryCapture != null) return true;
        try {
            rawStore = new RawFrameStore();
            renderer = onFrames(() -> new HardwareFramePipeline(frames, size[0], size[1], rawStore.mapping, this::framePublished));
            renderer.captureIntervalMs = (1000 + captureFps - 1) / captureFps;
            primaryCapture = new PrimaryCapture(renderer.input(), size[0], size[1]);
            rawEpoch++;
            primaryReadAt = SystemClock.elapsedRealtime();
            control.removeCallbacks(inspectDisplay);
            control.postDelayed(inspectDisplay, 500);
            return true;
        } catch (Exception unavailable) {
            closePrimaryCapture();
            primaryCaptureUnavailable = true;
            android.util.Log.i("OAS", "No persistent primary mirror on this device", unavailable);
            return false;
        }
    }

    private void closePrimaryCapture() throws Exception {
        if (primaryCapture != null) primaryCapture.close();
        primaryCapture = null;
        FramePipeline old = renderer;
        renderer = null;
        if (old != null) onFrames(() -> { old.close(); return null; });
        if (rawStore != null) rawStore.close();
        rawStore = null;
        rawEpoch++;
        frameAt = 0;
    }

    @Override public void setPreviewSurface(Surface surface, long owner) {
        call(() -> {
            if(surface == null) throw new IllegalArgumentException("缺少预览窗口");
            if(primaryMode || display == null || renderer == null) {
                surface.release(); throw new IllegalStateException("实时预览需要后台虚拟屏");
            }
            cancelManualTouch();
            if (renderer instanceof HardwareFramePipeline) {
                Surface fallback = duplicateSurface(surface);
                try {
                    if (!renderer.error.isEmpty()) {
                        surface.release();
                        useLegacyCapture();
                        onFrames(() -> { renderer.setPreview(fallback); return null; });
                    } else {
                        try { onFrames(() -> { renderer.setPreview(surface); return null; }); }
                        catch (Exception unavailable) {
                            android.util.Log.w("OAS", "Native preview rejected by driver, using EGL capture", unavailable);
                            useLegacyCapture();
                            onFrames(() -> { renderer.setPreview(fallback); return null; });
                        }
                        if (renderer instanceof HardwareFramePipeline) fallback.release();
                    }
                } catch (Exception failure) {
                    fallback.release();
                    throw failure;
                }
            } else onFrames(() -> { renderer.setPreview(surface); return null; });
            previewOwner=owner;updateDisplayKeepAlive();return null;
        });
    }

    private static Surface duplicateSurface(Surface surface) {
        Parcel parcel = Parcel.obtain();
        try {
            surface.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return Surface.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }

    private void useLegacyCapture() throws Exception {
        FramePipeline old = renderer;
        ImageReader nextReader = ImageReader.newInstance(frameWidth, frameHeight, PixelFormat.RGBA_8888, 3);
        nextReader.setOnImageAvailableListener(this::receiveFrame, frames);
        DisplayRenderer next;
        try { next = onFrames(() -> new DisplayRenderer(frames, nextReader.getSurface(), frameWidth, frameHeight)); }
        catch (Exception failure) { nextReader.close(); throw failure; }
        try { display.setSurface(next.input()); }
        catch (Exception failure) {
            onFrames(() -> { next.close(); return null; });
            nextReader.close();
            throw failure;
        }
        next.captureIntervalMs = old.captureIntervalMs;
        next.previewIntervalMs = old.previewIntervalMs;
        next.previewFrames = old.previewFrames;
        synchronized (frameLock) {
            reader = nextReader;
            renderer = next;
            frameAt = 0;
        }
        onFrames(() -> { old.close(); return null; });
        if (targetPackage != null && fpsEnabled) fps.start(targetPackage);
    }
    @Override public void clearPreviewSurface(long owner) {
        call(() -> {
            if(previewOwner==owner) {
                cancelManualTouch();
                onFrames(() -> { if(renderer!=null) renderer.setPreview(null); return null; });
                previewOwner=0;updateDisplayKeepAlive();
            }
            return null;
        });
    }

    @Override public String previewStatus() {
        return call(() -> new JSONObject().put("displayId",displayId).put("mode",primaryMode ? "primary" : "virtual")
            .put("width",frameWidth).put("height",frameHeight)
            .put("attached",renderer!=null && renderer.attached)
            .put("owner",previewOwner)
            .put("frames",renderer==null ? 0 : renderer.previewFrames)
            .put("fps",fps.fps()).put("fpsSource",fps.source()).put("fpsAdvice",fps.advice())
            .put("ageMs",invalidTarget || renderer==null || renderer.previewAt==0 ? -1 : SystemClock.elapsedRealtime()-renderer.previewAt)
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
        renderer.captureIntervalMs=(1000+captureFps-1)/captureFps;renderer.previewIntervalMs=Math.max(1,1000/previewFps);
        if(power==null)power=new ScreenPower(control);
        power.target(displayId,display.getDisplay());updateDisplayKeepAlive();
        if(display.getDisplay().getRotation()!=Surface.ROTATION_0)try {
            Object window=ShellApis.service("window","android.view.IWindowManager");
            try{ShellApis.invoke(window.getClass().getMethod("freezeDisplayRotation",int.class,int.class,String.class),window,displayId,0,"OASA");}
            catch(NoSuchMethodException older){ShellApis.invoke(window.getClass().getMethod("freezeDisplayRotation",int.class,int.class),window,displayId,0);}
        }catch(Exception error){android.util.Log.w("OAS","Virtual-display rotation",error);}
    }
    private void monitorTarget(String pkg) {
        targetPackage=pkg;
        absentChecks = moveAttempts = 0;
        invalidTarget = primaryCaptureUnavailable = false;
        control.removeCallbacks(inspectDisplay);
        if (fpsEnabled && !primaryMode) fps.start(pkg);
        if (primaryCapture != null) control.postDelayed(inspectDisplay, 500);
        else if (!primaryMode && gamePackage != null) control.postDelayed(inspectDisplay, 2500);
    }

    private void inspectDisplayTarget() {
        if (primaryMode) {
            if (primaryCapture == null) return;
            try {
                if (SystemClock.elapsedRealtime() - primaryReadAt > 3000 || !TaskController.isTopOnDisplay(gamePackage, 0)) {
                    closePrimaryCapture();
                    return;
                }
            } catch (Exception failure) {
                try { closePrimaryCapture(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                android.util.Log.w("OAS", "Primary mirror stopped", failure);
                return;
            }
            control.postDelayed(inspectDisplay, 500);
            return;
        }
        if (display == null || gamePackage == null) return;
        try {
            int actual = TaskController.displayOf(gamePackage);
            if (actual == displayId) {
                absentChecks = 0;
                invalidTarget = false;
            } else if (++absentChecks >= 2) {
                if (!invalidTarget) {
                    invalidTarget = true;
                    FramePipeline current = renderer;
                    if (current != null) frames.post(current::invalidate);
                    frameAt = 0;
                }
                // A live game can drift to the main screen on some ROMs.
                // A stopped game still goes through the scheduler's existing Restart path.
                if (actual == 0 && runtimeActive && !scheduleIdle && moveAttempts < 2) {
                    moveAttempts++;
                    if (TaskController.moveExisting(gamePackage, displayId)) {
                        invalidTarget = false;
                        absentChecks = 0;
                    }
                }
            }
        } catch (Exception failure) {
            android.util.Log.w("OAS", "Virtual display target check failed", failure);
        }
        control.postDelayed(inspectDisplay, 2500);
    }
    @Override public String systemStatus() {
        return call(() -> {
            JSONObject value=power==null?new JSONObject():power.status();
            return value.put("backend",primaryMode?"主屏":virtualDevice==null?"VirtualDisplay":"VirtualDevice")
                .put("displayId",displayId).put("flags",flagsDescription).put("fps",fps.fps()).put("fpsSource",fps.source()).put("fpsAdvice",fps.advice())
                .put("rawFrames",rawStore!=null).put("runtimeActive",runtimeActive)
                .put("captureBackend",renderer instanceof HardwareFramePipeline ? "NDK ImageReader" : renderer!=null ? "EGL ImageReader" : "")
                .put("recognitionFrames",captureSequence)
                .put("scheduleIdle",scheduleIdle)
                .put("captureFps",captureFps).put("previewFps",previewFps).toString();
        });
    }
    @Override public void configureRuntime(boolean active,boolean awake,boolean monitorFps) {
        call(() -> {
            runtimeActive=active;keepAwake=awake;
            if (!active) scheduleIdle = false;
            if(power==null)power=new ScreenPower(control);
            if(primaryMode)power.target(displayId,null);
            updateDisplayKeepAlive();
            if(!active)power.restorePanel();
            if(fpsEnabled!=monitorFps){fpsEnabled=monitorFps;if(fpsEnabled&&targetPackage!=null&&!primaryMode)fps.start(targetPackage);else fps.close();}
            if (!active && primaryCapture != null) closePrimaryCapture();
            return null;
        });
    }
    @Override public void setScheduleIdle(boolean idle) {
        call(() -> { scheduleIdle = idle; updateDisplayKeepAlive(); return null; });
    }

    private void updateDisplayKeepAlive() {
        if (power != null) power.update(runtimeActive && keepAwake && !scheduleIdle, previewOwner != 0);
    }
    @Override public void configureFrameRates(int capture,int preview) {
        call(() -> {
            if((capture!=15&&capture!=30&&capture!=60)||(preview!=30&&preview!=60))throw new IllegalArgumentException("不支持的帧率");
            captureFps=capture;previewFps=preview;
            if(renderer!=null){renderer.captureIntervalMs=(1000+captureFps-1)/captureFps;renderer.previewIntervalMs=Math.max(1,1000/previewFps);}
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
            if (primaryCapture != null && !packageName.equals(gamePackage)) closePrimaryCapture();
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
            if (renderer instanceof HardwareFramePipeline) {
                resizeHardware(width, height);
                return null;
            }
            DisplayRenderer legacy = (DisplayRenderer)renderer;
            ImageReader replacement=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,3);
            replacement.setOnImageAvailableListener(this::receiveFrame,frames);
            ImageReader previous=reader;
            RawFrameStore replacementStore;
            try{replacementStore=new RawFrameStore();}catch(Exception failure){replacement.close();throw failure;}
            int oldWidth=frameWidth,oldHeight=frameHeight;
            try{
                onFrames(() -> { legacy.setCapture(replacement.getSurface(),width,height); return null; });
                display.resize(width,height,densityDpi);
                synchronized(frameLock){reader=replacement;frameWidth=width;frameHeight=height;RawFrameStore old=rawStore;rawStore=replacementStore;rawEpoch++;frameAt=0;if(old!=null)old.close();}
                if(previous!=null)previous.close();
            }catch(Exception error){
                try{onFrames(()->{legacy.setCapture(previous.getSurface(),oldWidth,oldHeight);return null;});display.resize(oldWidth,oldHeight,densityDpi);}
                catch(Exception rollback){error.addSuppressed(rollback);}
                replacementStore.close();replacement.close();throw error;
            }
            return null;
        });
    }
    private void resizeHardware(int width, int height) throws Exception {
        HardwareFramePipeline previous = (HardwareFramePipeline) renderer;
        RawFrameStore nextStore = new RawFrameStore();
        HardwareFramePipeline next;
        try {
            next = onFrames(() -> new HardwareFramePipeline(frames, width, height,
                nextStore.mapping, this::framePublished));
        } catch (Exception error) { nextStore.close(); throw error; }
        try {
            display.resize(width, height, densityDpi);
            display.setSurface(next.input());
        } catch (Exception error) {
            display.resize(frameWidth, frameHeight, densityDpi);
            display.setSurface(previous.input());
            onFrames(() -> { next.close(); return null; });
            nextStore.close();
            throw error;
        }
        RawFrameStore oldStore = rawStore;
        next.captureIntervalMs = previous.captureIntervalMs;
        next.previewIntervalMs = previous.previewIntervalMs;
        next.previewFrames = previous.previewFrames;
        renderer = next;
        rawStore = nextStore;
        rawEpoch++;
        frameAt = 0;
        frameWidth = width;
        frameHeight = height;
        onFrames(() -> {
            Surface preview = previous.detachPreviewForResize();
            previous.close();
            oldStore.close();
            if (preview != null) next.setPreview(preview);
            return null;
        });
        if (targetPackage != null && fpsEnabled) fps.start(targetPackage);
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

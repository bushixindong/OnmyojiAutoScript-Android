package com.oas.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import com.oas.android.bridge.DeviceBridge;
import rikka.shizuku.Shizuku;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** App-side binding; no shell execution or hidden APIs in the unprivileged app. */
public final class DeviceSession {
    private final Context appContext;
    private final Object audioLock=new Object();
    private final com.oas.android.bridge.RawFrameReader rawReader=new com.oas.android.bridge.RawFrameReader();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private final java.util.ArrayDeque<String> recentLogs = new java.util.ArrayDeque<>(120);
    private final Shizuku.UserServiceArgs args;
    public volatile IDeviceBridge bridge;
    private volatile IDeviceBridge rootBridge;
    public volatile String message = "请先启动 Shizuku，再授权 OAS";
    public volatile boolean primaryMode;
    public final java.util.concurrent.atomic.AtomicBoolean taskActive = new java.util.concurrent.atomic.AtomicBoolean();
    public final java.util.concurrent.atomic.AtomicInteger stateVersion = new java.util.concurrent.atomic.AtomicInteger();
    public volatile boolean binding;
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            new Thread(() -> attachShizuku(binder), "oas-shizuku-connect").start();
        }
        @Override public void onServiceDisconnected(ComponentName name) { binding = false; bridge = null; rootBridge = null; update("设备服务已断开"); }
    };

    private void attachShizuku(IBinder rawBinder) {
        String token = null;
        try {
            IDeviceBridge raw = IDeviceBridge.Stub.asInterface(rawBinder);
            int uid = new org.json.JSONObject(raw.status()).optInt("uid", -1);
            if (uid == 2000) {
                rootBridge = null;
                bridge = raw;
                rawBinder.linkToDeath(() -> {
                    if (bridge != null && bridge.asBinder() == rawBinder) { bridge = null; binding = false; update("设备服务已断开，请重新连接"); }
                }, 0);
                binding = false;
                update("设备服务已连接 · 免 root");
                recover(raw);
                return;
            }
            if (uid != 0) throw new IllegalStateException("不支持的设备服务 UID：" + uid);
            rootBridge = raw;
            rawBinder.linkToDeath(() -> {
                if (rootBridge != null && rootBridge.asBinder() == rawBinder) {
                    rootBridge = null; bridge = null; binding = false; update("设备服务已断开，请重新连接");
                }
            }, 0);
            byte[] bytes = new byte[16];
            new java.security.SecureRandom().nextBytes(bytes);
            StringBuilder hex = new StringBuilder(32);
            for (byte b : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
            token = hex.toString();
            java.util.concurrent.CompletableFuture<IBinder> waiter = BridgeAttachProvider.expect(token);
            raw.spawnShellBridge(appContext.getApplicationInfo().sourceDir, token);
            IBinder shellBinder = waiter.get(8, java.util.concurrent.TimeUnit.SECONDS);
            IDeviceBridge shell = IDeviceBridge.Stub.asInterface(shellBinder);
            int shellUid = new org.json.JSONObject(shell.status()).optInt("uid", -1);
            if (shellUid != 2000) throw new IllegalStateException("降权后的 Shell 服务 UID 异常：" + shellUid);
            bridge = shell;
            shellBinder.linkToDeath(() -> {
                if (bridge != null && bridge.asBinder() == shellBinder) { bridge = null; binding = false; update("设备服务已断开，请重新连接"); }
            }, 0);
            binding = false;
            update("设备服务已连接 · Root 降权 Shell");
            recover(shell);
        } catch (Throwable error) {
            if (token != null) BridgeAttachProvider.cancel(token);
            bridge = null; rootBridge = null; binding = false;
            Throwable cause = error instanceof java.util.concurrent.ExecutionException && error.getCause() != null ? error.getCause() : error;
            update("连接设备服务失败：" + cause.getMessage());
        }
    }

    DeviceSession(Context context) {
        appContext=context.getApplicationContext();
        args = new Shizuku.UserServiceArgs(new ComponentName(context, DeviceBridge.class))
                .daemon(false).processNameSuffix("oas_device").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE);
        Shizuku.addBinderReceivedListenerSticky(() -> {
            try {
                if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    if (bridge == null) connect();
                    return;
                }
            } catch (Throwable ignored) { }
            update("Shizuku 已启动，请授权并连接");
        });
        Shizuku.addBinderDeadListener(() -> { bridge = null; binding = false; update("Shizuku 已停止，请重新启动"); });
        Shizuku.addRequestPermissionResultListener((requestCode, result) -> {
            if (result == PackageManager.PERMISSION_GRANTED) connect(); else update("未获得 Shizuku 授权");
        });
    }

    public boolean isShizukuInstalled() {
        try {
            appContext.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    public java.util.List<String> recentLogsSnapshot() {
        synchronized (recentLogs) { return new java.util.ArrayList<>(recentLogs); }
    }
    public void update(String text) {
        message = text;
        if (text != null && !text.trim().isEmpty()) {
            String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
            String line = text.matches("^\\d{2}:\\d{2}:\\d{2}.*") ? text : (time + " | " + text);
            synchronized (recentLogs) {
                if (recentLogs.size() >= 120) recentLogs.removeFirst();
                recentLogs.addLast(line);
            }
        }
        main.post(() -> { for (Runnable listener : listeners) listener.run(); });
    }

    public void connect() {
        try {
            if (!Shizuku.pingBinder()) {
                update(isShizukuInstalled() ? "Shizuku 未启动，请先在 Shizuku 中启动服务" : "未安装 Shizuku，请先安装并启动服务");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                update("请在弹窗中允许 OASA 使用 Shizuku");
                Shizuku.requestPermission(1);
                return;
            }
            if (bridge != null && bridge.asBinder().isBinderAlive()) { update("设备服务已连接"); return; }
            if (binding) return;
            binding = true; update("正在连接设备服务…");
            Shizuku.bindUserService(args, connection);
            main.postDelayed(() -> { if (binding && bridge == null) { binding = false; update("连接超时，请检查 Shizuku 服务状态后重试"); } }, 12000);
        } catch (Throwable e) { binding = false; update("连接失败：" + e.getMessage()); }
    }

    public IDeviceBridge requireBridge() {
        IDeviceBridge value = bridge;
        if (value == null || !value.asBinder().isBinderAlive()) throw new IllegalStateException("请先授权并连接设备服务");
        return value;
    }

    // The debug provider is present only in debug APKs and accepts only shell/root callers.
    public void attachDebug(IBinder binder) {
        if (!BuildConfig.DEBUG || binder == null) throw new SecurityException("Debug connection is unavailable");
        bridge = IDeviceBridge.Stub.asInterface(binder);
        try { binder.linkToDeath(() -> {
            if (bridge != null && bridge.asBinder() == binder) { bridge = null; update("ADB 调试服务已断开"); }
        }, 0); }
        catch (Exception e) { bridge = null; throw new IllegalStateException(e); }
        update("ADB 调试设备服务已连接 · 正式使用请通过 Shizuku 授权");
        recover(bridge);
    }

    private void recover(IDeviceBridge connected) {
        new Thread(()->{
            try {
                restoreAudio(connected);
                android.content.SharedPreferences options=appContext.getSharedPreferences("MainActivity",0);
                connected.configureRuntime(taskActive.get(),options.getBoolean("keepAwake",true),options.getBoolean("fpsEnabled",true));
                connected.configureFrameRates(options.getInt("captureFps",30),options.getInt("previewFps",30));
            }catch(Exception error){update("设备已连接，系统状态恢复待重试："+error.getMessage());}
        },"oas-device-recovery").start();
    }
    public boolean isGameMuted(String pkg) {
        return appContext.getSharedPreferences("device_recovery",0).contains("audio:"+pkg);
    }
    public void setGameMuted(String pkg,boolean muted) throws Exception {
        synchronized(audioLock) {
            android.content.SharedPreferences journal=appContext.getSharedPreferences("device_recovery",0);
            IDeviceBridge device=requireBridge();String key="audio:"+pkg;
            if(muted) {
                if(journal.contains(key))return;
                org.json.JSONObject state=new org.json.JSONObject(device.gameAudioState(pkg));
                int previous=state.getInt("mode");
                if(!journal.edit().putInt(key,previous).commit())throw new IllegalStateException("无法保存游戏声音恢复记录");
                if(!device.setGameMuted(pkg,true,previous))throw new IllegalStateException("游戏静音失败，恢复记录已保留");
            } else if(journal.contains(key)) {
                if(device.setGameMuted(pkg,false,journal.getInt(key,0)))journal.edit().remove(key).commit();
                else throw new IllegalStateException("游戏声音尚未恢复，请重试");
            }
        }
    }
    public void restoreAudio() throws Exception {restoreAudio(requireBridge());}
    private void restoreAudio(IDeviceBridge device) throws Exception {
        synchronized(audioLock) {
            android.content.SharedPreferences journal=appContext.getSharedPreferences("device_recovery",0);
            Exception failure=null;
            for(java.util.Map.Entry<String,?> entry:journal.getAll().entrySet())if(entry.getKey().startsWith("audio:"))try {
                if(device.setGameMuted(entry.getKey().substring(6),false,(Integer)entry.getValue()))journal.edit().remove(entry.getKey()).commit();
            }catch(Exception error){failure=error;}
            if(failure!=null)throw failure;
        }
    }

    public void notifyStateChanged() {
        stateVersion.incrementAndGet();
        main.post(() -> { for (Runnable listener : listeners) listener.run(); });
    }

    public Bitmap captureDisplay() throws Exception {
        byte[] raw=rawReader.read(requireBridge());
        if(raw!=null){
            Bitmap image=com.oas.android.bridge.RawFrameReader.bitmap(raw);
            if(image.getWidth()>0&&image.getHeight()>0)return image;
            image.recycle();
        }
        try (ParcelFileDescriptor descriptor = requireBridge().screenshot();
             ParcelFileDescriptor.AutoCloseInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            Bitmap frame = BitmapFactory.decodeStream(stream);
            if (frame == null || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
                if (frame != null) frame.recycle();
                throw new IllegalStateException("未收到有效的屏幕截图");
            }
            return frame;
        }
    }

    public Bitmap screenshot() throws Exception {
        byte[] raw=rawReader.read(requireBridge());
        if(raw!=null){
            Bitmap image=com.oas.android.bridge.RawFrameReader.bitmap(raw);
            if(image.getWidth()!=1280||image.getHeight()!=720){image.recycle();throw new IllegalStateException("识别需要 1280×720 原图");}
            return image;
        }
        try (ParcelFileDescriptor descriptor = requireBridge().screenshot();
             ParcelFileDescriptor.AutoCloseInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            Bitmap frame = BitmapFactory.decodeStream(stream);
            if (frame == null || frame.getWidth() != 1280 || frame.getHeight() != 720) {
                if (frame != null) frame.recycle();
                throw new IllegalStateException("未收到有效的 1280×720 截图");
            }
            return frame;
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import com.oas.android.engine.TaskEngine;

/** Bounded CPU wake lock plus reversible shell power/audio changes for one foreground run. */
public final class RuntimePowerGuard implements AutoCloseable {
    private final DeviceSession session;
    private final SharedPreferences options;
    private final String pkg;
    private PowerManager.WakeLock cpu;
    private boolean muted;
    private final Context context;
    private final AlarmManager alarms;
    private final long timeout;
    private PendingIntent idleAlarm;
    private boolean registered, idle;
    private final BroadcastReceiver wake = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            synchronized (RuntimePowerGuard.this) {
                if (!idle) return;
                acquireCpu();
            }
            // Don't enter Python on the main thread while a Python task is stopping.
            new Thread(TaskEngine::wakeSchedule, "oasa-alarm-wake").start();
        }
    };
    RuntimePowerGuard(Context context,DeviceSession session,String pkg,long timeout) throws Exception {
        this.context=context;this.session=session;this.pkg=pkg;this.timeout=timeout;
        options=context.getSharedPreferences("MainActivity",0);
        alarms=context.getSystemService(AlarmManager.class);
        try {
            boolean awake=options.getBoolean("keepAwake",true);
            if(awake) {
                cpu=((PowerManager)context.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OASA:task");
                cpu.setReferenceCounted(false);
                acquireCpu();
            }
            session.requireBridge().configureRuntime(true,awake,options.getBoolean("fpsEnabled",true));
            session.requireBridge().configureFrameRates(options.getInt("captureFps",30),options.getInt("previewFps",30));
            if(options.getBoolean("autoMute",false)&&!session.isGameMuted(pkg)) {muted=true;session.setGameMuted(pkg,true);}
            if(options.getBoolean("autoPanelOff",false)&&!session.primaryMode)session.requireBridge().setDisplayPower(false);
        }catch(Exception failure){close();throw failure;}
    }
    private void acquireCpu() {
        if (cpu == null || cpu.isHeld()) return;
        if (timeout > 0) cpu.acquire(timeout); else cpu.acquire();
    }

    public static boolean exactAlarmsAvailable(Context context) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        return manager != null && (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms());
    }

    synchronized boolean beginIdleWait(long millis) {
        if (timeout != 0 || millis < 60_000 || !exactAlarmsAvailable(context)) return false;
        String action = context.getPackageName() + ".WAKE_ACTIVE_PLAN";
        if (!registered) {
            androidx.core.content.ContextCompat.registerReceiver(context, wake, new IntentFilter(action),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
            registered = true;
        }
        idleAlarm = PendingIntent.getBroadcast(context, 0, new Intent(action).setPackage(context.getPackageName()),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + millis, idleAlarm);
        } catch (SecurityException revoked) {
            idleAlarm.cancel();
            idleAlarm = null;
            return false;
        }
        idle = true;
        updateDeviceIdle(true);
        if (cpu != null && cpu.isHeld()) cpu.release();
        return true;
    }

    synchronized void endIdleWait() {
        if (!idle) return;
        cancelIdleAlarm();
        acquireCpu();
        updateDeviceIdle(false);
    }

    synchronized boolean holdsCpu() { return cpu != null && cpu.isHeld(); }

    private void updateDeviceIdle(boolean value) {
        try { session.requireBridge().setScheduleIdle(value); }
        catch (Exception failure) { android.util.Log.w("OAS", "Update scheduled idle state", failure); }
    }

    private void cancelIdleAlarm() {
        idle = false;
        if (idleAlarm != null) {
            alarms.cancel(idleAlarm);
            idleAlarm.cancel();
            idleAlarm = null;
        }
    }

    @Override public synchronized void close() {
        if (idle) updateDeviceIdle(false);
        cancelIdleAlarm();
        if (registered) {
            context.unregisterReceiver(wake);
            registered = false;
        }
        try {if(muted)session.setGameMuted(pkg,false);}
        catch(Exception error){session.update("任务结束，游戏声音恢复待重试："+error.getMessage());}
        try {session.requireBridge().configureRuntime(false,options.getBoolean("keepAwake",true),options.getBoolean("fpsEnabled",true));}
        catch(Exception error){android.util.Log.w("OAS","Runtime power cleanup",error);}
        if(cpu!=null&&cpu.isHeld())cpu.release();cpu=null;
    }
}

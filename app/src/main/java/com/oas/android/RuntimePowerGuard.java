// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;

/** Bounded CPU wake lock plus reversible shell power/audio changes for one foreground run. */
final class RuntimePowerGuard implements AutoCloseable {
    private final DeviceSession session;
    private final SharedPreferences options;
    private final String pkg;
    private PowerManager.WakeLock cpu;
    private boolean muted;
    RuntimePowerGuard(Context context,DeviceSession session,String pkg,long timeout) throws Exception {
        this.session=session;this.pkg=pkg;options=context.getSharedPreferences("MainActivity",0);
        try {
            boolean awake=options.getBoolean("keepAwake",true);
            if(awake) {
                cpu=((PowerManager)context.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OASA:task");
                cpu.setReferenceCounted(false);cpu.acquire(timeout);
            }
            session.requireBridge().configureRuntime(true,awake,options.getBoolean("fpsEnabled",true));
            session.requireBridge().configureFrameRates(options.getInt("captureFps",30),options.getInt("previewFps",30));
            if(options.getBoolean("autoMute",false)&&!session.isGameMuted(pkg)) {muted=true;session.setGameMuted(pkg,true);}
            if(options.getBoolean("autoPanelOff",false)&&!session.primaryMode)session.requireBridge().setDisplayPower(false);
        }catch(Exception failure){close();throw failure;}
    }
    @Override public void close() {
        try {if(muted)session.setGameMuted(pkg,false);}
        catch(Exception error){session.update("任务结束，游戏声音恢复待重试："+error.getMessage());}
        try {session.requireBridge().configureRuntime(false,options.getBoolean("keepAwake",true),options.getBoolean("fpsEnabled",true));}
        catch(Exception error){android.util.Log.w("OAS","Runtime power cleanup",error);}
        if(cpu!=null&&cpu.isHeld())cpu.release();cpu=null;
    }
}

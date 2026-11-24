// SPDX-License-Identifier: AGPL-3.0-only
// Physical-panel power and per-display activity policy adapted from MaaMeow PowerController.
package com.oas.android.bridge;

import android.content.*;
import android.os.*;
import android.view.Display;
import java.lang.reflect.*;
import org.json.JSONObject;

final class ScreenPower implements AutoCloseable {
    private final Handler handler;
    private final Context context;
    private final Object power;
    private boolean poweredOff,task,preview,registered;
    private int display=-1,group;
    private String error="";
    private Class<?> displayControl;
    ScreenPower(Handler handler) throws Exception {
        if(android.os.Process.myUid()!=2000)throw new SecurityException("ScreenPower is shell-only");
        this.handler=handler;context=new ShellContext();power=ShellApis.service("power","android.os.IPowerManager");
    }
    private final BroadcastReceiver receiver=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {poweredOff=false;}
    };
    private final Runnable keepAlive=new Runnable() {
        @Override public void run() {
            if(display<0 || !(task || preview&&group>0))return;
            try {
                if(Build.VERSION.SDK_INT>=31)ShellApis.invoke(power.getClass().getMethod("userActivity",int.class,long.class,int.class,int.class),power,display,SystemClock.uptimeMillis(),0,0);
                else ShellApis.invoke(power.getClass().getMethod("userActivity",long.class,int.class,int.class),power,SystemClock.uptimeMillis(),0,0);
                if(Build.VERSION.SDK_INT>=36 && group>0) {
                    Object interactive=ShellApis.invoke(power.getClass().getMethod("isDisplayInteractive",int.class),power,display);
                    if(Boolean.FALSE.equals(interactive))ShellApis.invoke(power.getClass().getMethod("wakeUpWithDisplayId",long.class,int.class,String.class,String.class,int.class),power,SystemClock.uptimeMillis(),2,"oasa:virtual","com.android.shell",display);
                }
                error="";
            }catch(Exception failure) {error="后台保活受系统限制："+failure.getMessage();}
            handler.postDelayed(this,4000);
        }
    };
    void target(int id,Display virtual) {
        display=id;group=0;
        if(virtual!=null)try {group=ShellApis.displayGroup(id);}
        catch(Exception ignored) { }
        update(task,preview);
    }
    void update(boolean task,boolean preview) {
        this.task=task;this.preview=preview;handler.removeCallbacks(keepAlive);
        if(display>=0&&(task||preview&&group>0))handler.post(keepAlive);
    }
    boolean panel(boolean on) throws Exception {
        if(!on && display<=0)throw new IllegalStateException("请先准备后台虚拟屏，再熄灭实体屏幕");
        Class<?> surface=Class.forName("android.view.SurfaceControl");
        Method set=surface.getMethod("setDisplayPowerMode",IBinder.class,int.class);
        int mode=on?2:0;java.util.List<IBinder> tokens=new java.util.ArrayList<>();
        try {
            Class<?> source=surface;
            try {source.getMethod("getPhysicalDisplayIds");}
            catch(NoSuchMethodException missing) {source=displayControl();}
            long[] ids=(long[])source.getMethod("getPhysicalDisplayIds").invoke(null);
            if(ids!=null)for(long id:ids)tokens.add((IBinder)source.getMethod("getPhysicalDisplayToken",long.class).invoke(null,id));
        }catch(ReflectiveOperationException unavailable) {
            String method=Build.VERSION.SDK_INT>=29?"getInternalDisplayToken":"getBuiltInDisplay";
            tokens.add((IBinder)(Build.VERSION.SDK_INT>=29?surface.getMethod(method).invoke(null):surface.getMethod(method,int.class).invoke(null,0)));
        }
        if(tokens.isEmpty()||tokens.contains(null))throw new IllegalStateException("系统不支持独立控制实体屏幕电源");
        try {for(IBinder token:tokens)ShellApis.invoke(set,null,token,mode);}
        catch(Exception failure) {
            if(!on)for(IBinder token:tokens)try {ShellApis.invoke(set,null,token,2);}catch(Exception ignored) { }
            throw failure;
        }
        poweredOff=!on;
        if(!registered) {
            try {
                IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_SCREEN_OFF);
                if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,null,handler,Context.RECEIVER_NOT_EXPORTED);
                else context.registerReceiver(receiver,filter,null,handler);
                registered=true;
            } catch(Throwable ignored) {
                // Shizuku app_process is not a registered AMS app process on Android 13+; panel power mode already applied.
            }
        }
        return true;
    }
    // Shell UID is exempt from app hidden-API limits; same services.jar bridge as MaaMeow.
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private Class<?> displayControl() throws Exception {
        if(displayControl!=null)return displayControl;
        Class<?> factory=Class.forName("com.android.internal.os.ClassLoaderFactory");
        ClassLoader loader=(ClassLoader)factory.getDeclaredMethod("createClassLoader",String.class,String.class,String.class,ClassLoader.class,int.class,boolean.class,String.class)
            .invoke(null,System.getenv("SYSTEMSERVERCLASSPATH"),null,null,ClassLoader.getSystemClassLoader(),0,true,null);
        Class<?> value=loader.loadClass("com.android.server.display.DisplayControl");
        Method load=Runtime.class.getDeclaredMethod("loadLibrary0",Class.class,String.class);load.setAccessible(true);load.invoke(Runtime.getRuntime(),value,"android_servers");
        displayControl=value;return value;
    }
    JSONObject status() throws Exception {
        return new JSONObject().put("panelOff",poweredOff).put("displayGroup",group).put("keepAlive",display>=0&&(task||preview&&group>0)).put("powerError",error);
    }
    void restorePanel() {
        if(!poweredOff)return;
        try {
            boolean interactive=((android.os.PowerManager)context.getSystemService(Context.POWER_SERVICE)).isInteractive();
            if(interactive)panel(true);else poweredOff=false;
        }catch(Exception failure) {android.util.Log.w("OAS","Restore panel power",failure);}
    }
    @Override public void close() {
        task=false;preview=false;display=-1;handler.removeCallbacks(keepAlive);restorePanel();
        if(registered) {context.unregisterReceiver(receiver);registered=false;}
    }
}

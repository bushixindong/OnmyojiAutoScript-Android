// SPDX-License-Identifier: AGPL-3.0-only
// Task FPS callback with composition-frame fallback, adapted from MaaMeow GameFpsMonitor.kt.
package com.oas.android.bridge;

import android.os.*;
import java.lang.reflect.*;
import java.util.function.LongSupplier;

final class GameFpsMonitor implements AutoCloseable {
    private final Handler handler;
    private final LongSupplier frames;
    private volatile float fps=-1;
    private volatile long reported;
    private Object window,callback;
    private long lastCount,lastTime;
    private boolean running;
    private String source="未监测",advice="";
    private final float[] samples=new float[15];
    private int sampleIndex,sampleCount,idle;
    private boolean lowWarned,degradedWarned;
    GameFpsMonitor(Handler handler,LongSupplier frames) {this.handler=handler;this.frames=frames;}
    private final Runnable sample=new Runnable() {
        @Override public void run() {
            if(!running)return;
            long now=SystemClock.elapsedRealtime(),count=frames.getAsLong();
            if(callback==null)fps=count<0?-1:lastTime==0?0:(count-lastCount)*1000f/Math.max(1,now-lastTime);
            else if(now-reported>2000)fps=0;
            lastCount=count;lastTime=now;advise(fps);handler.postDelayed(this,1000);
        }
    };
    void start(String pkg) {
        close();running=true;lowWarned=false;degradedWarned=false;sampleCount=0;sampleIndex=0;idle=0;advice="";
        if(Build.VERSION.SDK_INT>=33)try {
            android.app.ActivityManager.RunningTaskInfo task=TaskController.find(pkg);
            if(task!=null) {
                window=ShellApis.service("window","android.view.IWindowManager");
                Class<?> iface=Class.forName("android.window.ITaskFpsCallback");
                Binder binder=new Binder() {
                    {attachInterface(null,"android.window.ITaskFpsCallback");}
                    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
                        if(code==IBinder.FIRST_CALL_TRANSACTION) {data.enforceInterface("android.window.ITaskFpsCallback");fps=data.readFloat();reported=SystemClock.elapsedRealtime();return true;}
                        return super.onTransact(code,data,reply,flags);
                    }
                };
                Object next=Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},(proxy,method,args)->{
                    switch(method.getName()) {case "asBinder":return binder;case "hashCode":return System.identityHashCode(proxy);case "equals":return proxy==args[0];case "toString":return "OASA task fps";default:return null;}
                });
                ShellApis.invoke(window.getClass().getMethod("registerTaskFpsCallback",int.class,iface),window,task.id,next);
                callback=next;reported=SystemClock.elapsedRealtime();source="游戏任务帧率";
            }
        }catch(Exception error) {android.util.Log.i("OAS","Task FPS unavailable, using composition frames",error);}
        if(callback==null)source="虚拟屏合成帧率";
        lastTime=SystemClock.elapsedRealtime();lastCount=frames.getAsLong();handler.postDelayed(sample,1000);
    }
    private void advise(float value) {
        if(value<=0) {if(++idle>3){sampleCount=0;sampleIndex=0;}return;}
        idle=0;samples[sampleIndex++%samples.length]=value;sampleCount=Math.min(samples.length,sampleCount+1);
        if(sampleCount<samples.length)return;
        int low=0,degraded=0;for(float sample:samples){if(sample<30)low++;if(sample<50)degraded++;}
        if(low>=12){lowWarned=true;advice="游戏帧率持续低于 30，可降低游戏画质或检查温度";}
        else if(degraded>=12){degradedWarned=true;advice="游戏帧率偏低，可检查游戏帧率设置和省电模式";}
        else if(degraded<=3){lowWarned=false;degradedWarned=false;advice="";}
    }
    float fps() {return fps;}
    String source() {return source;}
    String advice() {return advice;}
    @Override public void close() {
        running=false;handler.removeCallbacks(sample);
        if(callback!=null)try {ShellApis.invoke(window.getClass().getMethod("unregisterTaskFpsCallback",Class.forName("android.window.ITaskFpsCallback")),window,callback);}
        catch(Exception ignored) { }
        callback=null;window=null;fps=-1;source="未监测";advice="";
    }
}

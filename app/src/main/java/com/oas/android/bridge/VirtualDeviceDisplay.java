// SPDX-License-Identifier: AGPL-3.0-only
// Android 14+ companion VirtualDevice fallback tiers adapted from MaaMeow VdmDisplayController.
package com.oas.android.bridge;

import android.hardware.display.*;
import android.content.Context;
import android.os.*;
import android.view.Surface;
import androidx.annotation.RequiresApi;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Executor;

@RequiresApi(34)
final class VirtualDeviceDisplay implements AutoCloseable {
    private static final String PACKAGE="com.android.shell",ADDRESS="02:1A:2B:3C:4D:5E",PROFILE="android.app.role.COMPANION_DEVICE_APP_STREAMING";
    private Object companions,device;
    private boolean associated;
    VirtualDisplay display;
    String description;
    static VirtualDeviceDisplay create(Surface surface,int width,int height,int dpi) throws Exception {
        VirtualDeviceDisplay result=new VirtualDeviceDisplay();
        try {result.open(surface,width,height,dpi);return result;}
        catch(Exception failure) {try{result.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    // Trusted/focus/always-unlocked flag bits are hidden system flags, allowed to shell.
    @android.annotation.SuppressLint("WrongConstant")
    private void open(Surface surface,int width,int height,int dpi) throws Exception {
        if(android.os.Process.myUid()!=2000 || android.system.Os.geteuid()!=2000)throw new SecurityException("VirtualDevice owner must remain shell");
        companions=ShellApis.service("companiondevice","android.companion.ICompanionDeviceManager");
        Object association=find();
        if(association!=null && Build.VERSION.SDK_INT>=35 && !PROFILE.equals(association.getClass().getMethod("getDeviceProfile").invoke(association))) {
            associated=true;remove();association=null;
        }
        associated=true; // Unique OASA address; clean up late associations even if creation times out.
        if(association==null) {
            List<String> args=new ArrayList<>(Arrays.asList("cmd","companiondevice","associate","0",PACKAGE,ADDRESS));
            if(Build.VERSION.SDK_INT>=35){args.add(PROFILE);args.add("false");}
            command(args);long deadline=SystemClock.elapsedRealtime()+17000;
            while((association=find())==null && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
            if(association==null)throw new IllegalStateException("Companion association timed out");
        }
        int associationId=((Number)association.getClass().getMethod("getId").invoke(association)).intValue();
        Object service=ShellApis.service(Context.VIRTUAL_DEVICE_SERVICE,"android.companion.virtual.IVirtualDeviceManager");
        Class<?> iface=Class.forName("android.companion.virtual.IVirtualDeviceManager"),managerClass=Class.forName("android.companion.virtual.VirtualDeviceManager");
        Constructor<?> ctor=managerClass.getDeclaredConstructor(iface,Context.class);ctor.setAccessible(true);
        Object manager=ctor.newInstance(service,new ShellContext());
        Class<?> params=Class.forName("android.companion.virtual.VirtualDeviceParams"),builder=Class.forName("android.companion.virtual.VirtualDeviceParams$Builder");
        Class<?> deviceClass=Class.forName("android.companion.virtual.VirtualDeviceManager$VirtualDevice");
        boolean[][] tiers={{true,true},{true,false},{false,true},{false,false}};
        Exception last=null;
        for(int i=0;i<tiers.length;i++) {
            try {
                Object config=builder.getConstructor().newInstance();
                if(tiers[i][0])builder.getMethod("setLockState",int.class).invoke(config,1);
                Object built=builder.getMethod("build").invoke(config);
                device=ShellApis.invoke(managerClass.getMethod("createVirtualDevice",int.class,params),manager,associationId,built);
                int flags=(1<<6)|(1<<8)|(1<<13);
                if(tiers[i][1])flags|=(1<<10)|(1<<14)|(1<<16);
                VirtualDisplayConfig displayConfig=new VirtualDisplayConfig.Builder("DisplayMirror",width,height,dpi).setSurface(surface).setFlags(flags).build();
                display=(VirtualDisplay)ShellApis.invoke(deviceClass.getMethod("createVirtualDisplay",VirtualDisplayConfig.class,Executor.class,VirtualDisplay.Callback.class),device,displayConfig,null,null);
                long deadline=SystemClock.elapsedRealtime()+1000;
                while(SystemClock.elapsedRealtime()<deadline) {
                    android.view.Display value=display.getDisplay();int group=ShellApis.displayGroup(value.getDisplayId());
                    if(group>0 && value.getState()==android.view.Display.STATE_ON) {description="VirtualDevice tier="+i+", unlocked="+tiers[i][0]+", trusted="+tiers[i][1];return;}
                    SystemClock.sleep(20);
                }
                throw new IllegalStateException("VirtualDevice display did not become ready");
            }catch(Exception failure) {
                last=failure;
                if(display!=null){display.release();display=null;}
                if(device!=null){try{deviceClass.getMethod("close").invoke(device);}catch(Exception cleanup){failure.addSuppressed(cleanup);}device=null;}
                if(!(failure instanceof SecurityException) && !(failure instanceof InvocationTargetException))break;
            }
        }
        throw last==null?new IllegalStateException("VirtualDevice unavailable"):last;
    }
    private Object find() throws Exception {
        Object result=ShellApis.invoke(companions.getClass().getMethod("getAssociations",String.class,int.class),companions,PACKAGE,0);
        for(Object item:(List<?>)result) {
            Object mac=item.getClass().getMethod("getDeviceMacAddress").invoke(item);
            if(mac!=null && ADDRESS.equalsIgnoreCase(mac.toString()))return item;
        }
        return null;
    }
    private static void command(List<String> args) throws Exception {
        java.lang.Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        Thread drain=new Thread(()->{try(java.io.InputStream in=process.getInputStream()){byte[] buf=new byte[2048];int n;while((n=in.read(buf))!=-1)output.write(buf,0,n);}catch(Exception ignored){}});drain.start();
        if(!process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)){process.destroy();throw new IllegalStateException("Companion command timed out");}
        drain.join(1000);if(process.exitValue()!=0)throw new IllegalStateException(output.toString("UTF-8"));
    }
    private void remove() throws Exception {
        command(Arrays.asList("cmd","companiondevice","disassociate","0",PACKAGE,ADDRESS));
        long deadline=SystemClock.elapsedRealtime()+17000;
        while(find()!=null && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
        if(find()!=null)throw new IllegalStateException("Companion association cleanup timed out");associated=false;
    }
    @Override public void close() throws Exception {
        try {if(display!=null){try{display.setSurface(null);}finally{display.release();display=null;}}}
        finally {
            try {if(device!=null){device.getClass().getMethod("close").invoke(device);device=null;}}
            finally {if(associated && companions!=null)remove();}
        }
    }
}

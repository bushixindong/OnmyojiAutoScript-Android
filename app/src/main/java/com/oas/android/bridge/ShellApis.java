// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android.bridge;

import android.os.IBinder;
import java.lang.reflect.*;

final class ShellApis {
    private ShellApis() { }
    static Object service(String name,String iface) throws Exception {
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
        if(binder==null)throw new IllegalStateException("System service unavailable: "+name);
        return Class.forName(iface+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
    }
    static Object invoke(Method method,Object target,Object... args) throws Exception {
        try {return method.invoke(target,args);}
        catch(InvocationTargetException wrapper) {
            Throwable cause=wrapper.getCause();if(cause instanceof Exception)throw (Exception)cause;throw wrapper;
        }
    }
    static void packageName(String pkg) {
        if(pkg==null||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException("包名无效");
    }
    static int displayGroup(int id) throws Exception {
        Object manager=service("display","android.hardware.display.IDisplayManager");
        Object info=invoke(manager.getClass().getMethod("getDisplayInfo",int.class),manager,id);
        if(info==null)throw new IllegalStateException("Display disappeared");
        return info.getClass().getField("displayGroupId").getInt(info);
    }
}

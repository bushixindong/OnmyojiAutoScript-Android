// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android.bridge;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import java.lang.reflect.Method;

/** Shell-UID (2000) bridge entrypoint forked by a Root-mode Shizuku process. */
public final class ShellBridgeMain {
    public static void main(String[] args) {
        try {
            if (args.length < 1 || args[0].isEmpty()) throw new IllegalArgumentException("Missing bridge token");
            if (android.os.Process.myUid() != 2000) throw new SecurityException("ShellBridgeMain must run as shell UID 2000");
            Looper.prepareMainLooper();
            DeviceBridge bridge = new DeviceBridge();
            Thread lifeline = new Thread(() -> {
                try {
                    while (System.in.read() >= 0) { }
                } catch (Exception ignored) { }
                try { bridge.destroy(); } catch (Exception ignored) { }
                System.exit(0);
            }, "oas-shell-lifeline");
            lifeline.setDaemon(true);
            lifeline.start();

            Bundle data = new Bundle();
            data.putString("token", args[0]);
            data.putBinder("bridge", bridge);
            Bundle reply = attach(data);
            if (reply == null || !reply.getBoolean("attached")) throw new IllegalStateException("Shell bridge attach rejected");
            Looper.loop();
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static Bundle attach(Bundle data) throws Exception {
        IBinder activityBinder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "activity");
        Object activity = Class.forName("android.app.IActivityManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, activityBinder);
        IBinder token = new Binder();
        Object holder;
        try {
            holder = activity.getClass().getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                    .invoke(activity, "com.oas.android.shizuku_bridge", 0, token, "OASA");
        } catch (NoSuchMethodException e) {
            holder = activity.getClass().getMethod("getContentProviderExternal", String.class, int.class, IBinder.class)
                    .invoke(activity, "com.oas.android.shizuku_bridge", 0, token);
        }
        if (holder == null) throw new IllegalStateException("OASA bridge provider unavailable");
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            for (Method method : provider.getClass().getMethods()) {
                if (!method.getName().equals("call")) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length < 4 || types[types.length - 1] != Bundle.class) continue;
                if (android.os.Build.VERSION.SDK_INT >= 31 && !types[0].getName().equals("android.content.AttributionSource")) continue;
                if (android.os.Build.VERSION.SDK_INT >= 29 && types.length < 5) continue;
                Object[] values = new Object[types.length];
                values[values.length - 1] = data;
                values[values.length - 2] = null;
                values[values.length - 3] = "attach";
                if (types.length >= 5) values[values.length - 4] = "com.oas.android.shizuku_bridge";
                if (types[0] == String.class) values[0] = "com.android.shell";
                else if (android.os.Build.VERSION.SDK_INT >= 31) {
                    values[0] = new AttributionSource.Builder(android.os.Process.myUid())
                            .setPackageName("com.android.shell").build();
                }
                method.setAccessible(true);
                return (Bundle) method.invoke(provider, values);
            }
            throw new NoSuchMethodException("IContentProvider.call");
        } finally {
            try {
                activity.getClass().getMethod("removeContentProviderExternal", String.class, IBinder.class)
                        .invoke(activity, "com.oas.android.shizuku_bridge", token);
            } catch (ReflectiveOperationException ignored) { }
        }
    }
}

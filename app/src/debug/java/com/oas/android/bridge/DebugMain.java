package com.oas.android.bridge;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import java.lang.reflect.Method;

public final class DebugMain {
    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            DeviceBridge bridge = new DeviceBridge();
            Bundle data = new Bundle(); data.putBinder("bridge", bridge);
            Bundle reply = attach(data);
            if (reply == null || !reply.getBoolean("attached")) throw new IllegalStateException("Debug attach failed");
            System.out.println("OAS ADB debug bridge attached (UID " + android.os.Process.myUid() + ")");
            Looper.loop();
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }

    private static Bundle attach(Bundle data) throws Exception {
        // A shell app_process has no normal ApplicationContentResolver. Use the same
        // external-provider route as scrcpy/MaaMeow's shell ContentResolver.
        IBinder activityBinder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "activity");
        Object activity = Class.forName("android.app.IActivityManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, activityBinder);
        IBinder token = new Binder();
        Object holder;
        try { holder = activity.getClass().getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                    .invoke(activity, "com.oas.android.debug", 0, token, "OAS"); }
        catch (NoSuchMethodException e) { holder = activity.getClass().getMethod("getContentProviderExternal", String.class, int.class, IBinder.class)
                    .invoke(activity, "com.oas.android.debug", 0, token); }
        if (holder == null) throw new IllegalStateException("OAS debug provider not found");
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            for (Method method : provider.getClass().getMethods()) {
                if (!method.getName().equals("call")) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length < 4 || types[types.length - 1] != Bundle.class) continue;
                // Android keeps legacy default overloads which substitute authority="unknown".
                if (android.os.Build.VERSION.SDK_INT >= 31 && !types[0].getName().equals("android.content.AttributionSource")) continue;
                if (android.os.Build.VERSION.SDK_INT >= 29 && types.length < 5) continue;
                Object[] values = new Object[types.length];
                values[values.length - 1] = data;
                values[values.length - 2] = null; // arg
                values[values.length - 3] = "attach";
                if (types.length >= 5) values[values.length - 4] = "com.oas.android.debug";
                if (types[0] == String.class) values[0] = "com.android.shell";
                else if (android.os.Build.VERSION.SDK_INT >= 31) values[0] = new AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build();
                method.setAccessible(true);
                return (Bundle) method.invoke(provider, values);
            }
            throw new NoSuchMethodException("IContentProvider.call");
        } finally {
            try { activity.getClass().getMethod("removeContentProviderExternal", String.class, IBinder.class).invoke(activity, "com.oas.android.debug", token); }
            catch (ReflectiveOperationException ignored) { }
        }
    }
}

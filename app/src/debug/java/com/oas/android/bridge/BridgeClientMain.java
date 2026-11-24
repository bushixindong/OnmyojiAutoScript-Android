package com.oas.android.bridge;

import android.content.AttributionSource;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import com.oas.android.IDeviceBridge;
import java.lang.reflect.Method;

/** Shell-only QA client for the app's already-authorized real Shizuku service. Debug APK only. */
public final class BridgeClientMain {
    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            if (args.length == 0) throw new IllegalArgumentException("status/create/launch/screenshot/tap/task/release");
            if (args[0].equals("task")) {
                Bundle extras = new Bundle(); extras.putString("package", args.length > 2 ? args[2] : "com.netease.onmyoji");
                call("task", args[1], extras); System.out.println("Task requested: " + args[1]); return;
            }
            IDeviceBridge bridge = IDeviceBridge.Stub.asInterface(call("get-bridge", null, null).getBinder("bridge"));
            if (bridge == null) throw new IllegalStateException("App has no connected device service");
            switch (args[0]) {
                case "status": System.out.println(bridge.status()); break;
                case "preview-status": System.out.println(bridge.previewStatus()); break;
                case "primary": bridge.usePrimaryDisplay(); System.out.println("Primary mode selected"); break;
                case "create": System.out.println("displayId=" + bridge.createDisplay(args.length > 1 ? Integer.parseInt(args[1]) : 240)); break;
                case "launch": System.out.println(bridge.launch(args[1])); break;
                case "release": bridge.releaseDisplay(); System.out.println("Display released"); break;
                case "tap": bridge.tap(Integer.parseInt(args[1]), Integer.parseInt(args[2])); System.out.println("Touch sent"); break;
                case "screenshot":
                    String path = args.length > 1 ? args[1] : "/data/local/tmp/oas-frame.png";
                    if (!path.startsWith("/data/local/tmp/oas-") || path.contains("..")) throw new IllegalArgumentException("Use /data/local/tmp/oas-*.png");
                    try (ParcelFileDescriptor descriptor = bridge.screenshot();
                         ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                         java.io.FileOutputStream output = new java.io.FileOutputStream(path)) {
                        byte[] buffer = new byte[16384]; int n;
                        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                    }
                    Bitmap bitmap = BitmapFactory.decodeFile(path);
                    if (bitmap == null) throw new IllegalStateException("Invalid screenshot");
                    System.out.println("Saved " + bitmap.getWidth() + "x" + bitmap.getHeight() + " to " + path); bitmap.recycle(); break;
                default: throw new IllegalArgumentException("Unknown operation");
            }
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }

    private static Bundle call(String operation, String arg, Bundle extras) throws Exception {
        IBinder activityBinder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "activity");
        Object activity = Class.forName("android.app.IActivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, activityBinder);
        IBinder token = new Binder(); Object holder;
        try { holder = activity.getClass().getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                .invoke(activity, "com.oas.android.debug", 0, token, "OAS QA"); }
        catch (NoSuchMethodException e) { holder = activity.getClass().getMethod("getContentProviderExternal", String.class, int.class, IBinder.class)
                .invoke(activity, "com.oas.android.debug", 0, token); }
        if (holder == null) throw new IllegalStateException("OAS debug provider unavailable");
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            for (Method method : provider.getClass().getMethods()) {
                if (!method.getName().equals("call")) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length < 4 || types[types.length - 1] != Bundle.class) continue;
                if (android.os.Build.VERSION.SDK_INT >= 31 && !types[0].getName().equals("android.content.AttributionSource")) continue;
                if (android.os.Build.VERSION.SDK_INT >= 29 && types.length < 5) continue;
                Object[] values = new Object[types.length];
                values[values.length - 1] = extras; values[values.length - 2] = arg; values[values.length - 3] = operation;
                if (types.length >= 5) values[values.length - 4] = "com.oas.android.debug";
                if (types[0] == String.class) values[0] = "com.android.shell";
                else if (android.os.Build.VERSION.SDK_INT >= 31) values[0] = new AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build();
                method.setAccessible(true); return (Bundle) method.invoke(provider, values);
            }
            throw new NoSuchMethodException("IContentProvider.call");
        } finally {
            try { activity.getClass().getMethod("removeContentProviderExternal", String.class, IBinder.class).invoke(activity, "com.oas.android.debug", token); }
            catch (ReflectiveOperationException ignored) { }
        }
    }
}

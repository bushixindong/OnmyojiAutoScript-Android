// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from the shell Context technique in MaaMeow / scrcpy. See THIRD_PARTY.md.
package com.oas.android.bridge;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import java.lang.reflect.Method;

final class ShellContext extends ContextWrapper {
    ShellContext() throws Exception { super(systemContext()); }

    private static Context systemContext() throws Exception {
        Class<?> cls = Class.forName("android.app.ActivityThread");
        Object thread = cls.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = cls.getMethod("systemMain").invoke(null);
        Method method = cls.getDeclaredMethod("getSystemContext");
        method.setAccessible(true);
        return (Context) method.invoke(thread);
    }

    @Override public String getPackageName() { return "com.android.shell"; }
    @Override public String getOpPackageName() { return "com.android.shell"; }
    @Override public Context getApplicationContext() { return this; }
    @Override public AttributionSource getAttributionSource() {
        if (Build.VERSION.SDK_INT < 31) return super.getAttributionSource();
        return new AttributionSource.Builder(android.os.Process.myUid())
                .setPackageName(getPackageName()).build();
    }
}

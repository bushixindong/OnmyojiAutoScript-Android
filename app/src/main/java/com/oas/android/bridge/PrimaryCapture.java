// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android.bridge;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Surface;

/** A session mirror of display 0, created only while the configured game is on top. */
final class PrimaryCapture implements AutoCloseable {
    final VirtualDisplay mirror;
    final int width, height;

    static int[] dimensions() throws Exception {
        Object manager = ShellApis.service("display", "android.hardware.display.IDisplayManager");
        Object info = ShellApis.invoke(manager.getClass().getMethod("getDisplayInfo", int.class), manager, 0);
        return new int[]{info.getClass().getField("logicalWidth").getInt(info),
            info.getClass().getField("logicalHeight").getInt(info)};
    }

    PrimaryCapture(Surface input, int width, int height) throws Exception {
        this.width = width;
        this.height = height;
        mirror = (VirtualDisplay) ShellApis.invoke(DisplayManager.class.getMethod("createVirtualDisplay",
            String.class, int.class, int.class, int.class, Surface.class), null,
            "OASA primary capture", width, height, 0, input);
        if (mirror == null) throw new IllegalStateException("Primary capture unavailable");
    }

    @Override public void close() { mirror.release(); }
}

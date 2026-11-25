// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android.bridge;

import android.view.Surface;

/** Frame-thread-owned capture and preview backend. Only status and limits cross threads. */
abstract class FramePipeline implements AutoCloseable {
    volatile long previewFrames, previewAt;
    volatile int captureIntervalMs = 34, previewIntervalMs = 34;
    volatile boolean attached;
    volatile String error = "";

    abstract Surface input();
    abstract void requestCapture();
    abstract void setPreview(Surface window);
    abstract long compositionCount();
    abstract void invalidate();
    @Override public abstract void close();
}

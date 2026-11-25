// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android.bridge;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Direct native ImageReader output. No EGL is initialized until a preview is attached. */
final class HardwareFramePipeline extends FramePipeline {
    private final Handler handler;
    private final Runnable framePublished;
    private final long handle;
    private final Surface input;
    private ByteBuffer store;
    private Surface previewWindow;
    private long lastCapture;
    private long lastPreviewSerial;
    private HandlerThread previewThread;
    private volatile Handler previewHandler;
    private volatile boolean closed;
    private final Runnable capture = this::readFrame;
    private final Runnable preview = this::renderPreview;

    HardwareFramePipeline(Handler handler, int width, int height, ByteBuffer store, Runnable framePublished) {
        this.handler = handler;
        this.store = store;
        this.framePublished = framePublished;
        handle = NativeFrames.createHardwareCapture(width, height);
        try { input = NativeFrames.hardwareCaptureSurface(handle); }
        catch (RuntimeException failure) { NativeFrames.closeHardwareCapture(handle); throw failure; }
    }

    @Override Surface input() { return input; }
    @Override synchronized long compositionCount() { return closed ? 0 : NativeFrames.hardwareFrameCount(handle); }

    @Override void invalidate() {
        NativeFrames.invalidateHardwareFrame(handle);
        previewAt = 0;
        Handler output = previewHandler;
        if (output != null) output.post(() -> NativeFrames.blankHardwarePreview(handle));
    }

    @Override void requestCapture() {
        requestedAt = SystemClock.elapsedRealtime();
        handler.removeCallbacks(capture);
        readFrame();
    }

    private void readFrame() {
        if (closed) return;
        long now = SystemClock.elapsedRealtime();
        long wait = lastCapture + captureIntervalMs - now;
        if (wait > 0) { handler.postDelayed(capture, wait); return; }
        if (NativeFrames.readHardwareFrame(handle, store, now)) {
            lastCapture = now;
            framePublished.run();
        } else if (now - requestedAt < 4900) {
            handler.postDelayed(capture, 16);
        }
    }

    private long requestedAt;

    @Override void setPreview(Surface surface) {
        if (previewWindow == surface) return;
        Surface previous = detachPreviewForResize();
        if (previous != null) previous.release();
        if (surface == null) return;
        HandlerThread thread = new HandlerThread("oasa-hardware-preview");
        thread.start();
        Handler output = new Handler(thread.getLooper());
        previewThread = thread;
        previewHandler = output;
        previewWindow = surface;
        try {
            onPreviewThread(output, () -> NativeFrames.hardwarePreviewWindow(handle, surface));
        } catch (RuntimeException failure) {
            try { detachPreviewForResize().release(); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        attached = true;
        previewAt = lastPreviewSerial = 0;
        error = "";
        output.post(preview);
    }

    Surface detachPreviewForResize() {
        attached = false;
        Handler output = previewHandler;
        if (output != null) {
            output.removeCallbacks(preview);
            onPreviewThread(output, () -> NativeFrames.hardwarePreviewWindow(handle, null));
            previewThread.quitSafely();
            previewThread = null;
            previewHandler = null;
        }
        Surface result = previewWindow;
        previewWindow = null;
        return result;
    }

    private void onPreviewThread(Handler output, Runnable operation) {
        FutureTask<Void> task = new FutureTask<>(operation, null);
        if (!output.post(task)) throw new IllegalStateException("Preview renderer stopped");
        try { task.get(4, TimeUnit.SECONDS); }
        catch (Exception failure) {
            task.cancel(false);
            throw new IllegalStateException("Native preview: " + failure.getMessage(), failure);
        }
    }

    private void renderPreview() {
        if (closed || !attached) return;
        Handler output = previewHandler;
        if (output == null) return;
        try {
            long serial = NativeFrames.drawHardwarePreview(handle, lastPreviewSerial);
            if (serial > 0) {
                lastPreviewSerial = serial;
                previewAt = SystemClock.elapsedRealtime();
                previewFrames++;
            }
            if (attached) output.postDelayed(preview, previewIntervalMs);
        } catch (RuntimeException failure) {
            handler.post(() -> {
                if (previewHandler != output) return;
                setPreview(null);
                error = failure.getMessage();
            });
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        handler.removeCallbacks(capture);
        setPreview(null);
        closed = true;
        input.release();
        NativeFrames.closeHardwareCapture(handle);
        store = null;
    }
}

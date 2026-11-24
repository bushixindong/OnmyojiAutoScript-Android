package com.oas.android.bridge;

import android.graphics.*;
import android.media.*;
import android.os.*;
import android.view.MotionEvent;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import android.view.Surface;
import java.util.concurrent.atomic.AtomicInteger;

/** Verifies actual GPU preview buffers without opening a real game. Shell/debug APK only. */
public final class PreviewSmokeMain {
    private static Surface copy(Surface surface) {
        Parcel parcel=Parcel.obtain();
        try { surface.writeToParcel(parcel,0); parcel.setDataPosition(0); return Surface.CREATOR.createFromParcel(parcel); }
        finally { parcel.recycle(); }
    }
    private static Bitmap capture(DeviceBridge bridge) throws Exception {
        try(ParcelFileDescriptor fd=bridge.screenshot(); ParcelFileDescriptor.AutoCloseInputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            Bitmap image=BitmapFactory.decodeStream(in); if(image==null) throw new AssertionError("Empty capture"); return image;
        }
    }
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        DeviceBridge bridge=new DeviceBridge(); HandlerThread thread=new HandlerThread("preview-test-consumer"); thread.start();
        ImageReader sink=ImageReader.newInstance(1280,720,PixelFormat.RGBA_8888,3);
        AtomicInteger frames=new AtomicInteger(),changed=new AtomicInteger();
        int[] last={0},top={0},bottom={0};
        sink.setOnImageAvailableListener(reader -> {
            try(Image image=reader.acquireLatestImage()) {
                if(image==null) return;
                Image.Plane p=image.getPlanes()[0]; ByteBuffer b=p.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
                int color=b.getInt(300*p.getRowStride()+1050*p.getPixelStride());
                if(last[0]!=color) changed.incrementAndGet(); last[0]=color;
                top[0]=b.getInt(50*p.getRowStride()+1190*p.getPixelStride());
                bottom[0]=b.getInt(675*p.getRowStride()+1190*p.getPixelStride());
                frames.incrementAndGet();
            }
        },new Handler(thread.getLooper()));
        try {
            bridge.createDisplay(160); bridge.launch("com.oas.android"); Thread.sleep(800);
            bridge.setPreviewSurface(copy(sink.getSurface()),41); Thread.sleep(1600);
            if(frames.get()<15 || changed.get()<10) throw new AssertionError("Not continuous: "+frames+" frames, "+changed+" changes; "+bridge.previewStatus());
            // RGBA little-endian: red in low byte; cyan in the next two bytes.
            if((top[0]&255)<200 || ((top[0]>>>8)&255)>40 || (bottom[0]&255)>40 || ((bottom[0]>>>8)&255)<200)
                throw new AssertionError("Preview orientation/color wrong: "+Integer.toHexString(top[0])+"/"+Integer.toHexString(bottom[0]));
            Bitmap before=capture(bridge);
            if(before.getWidth()!=1280 || before.getHeight()!=720 || Color.blue(before.getPixel(100,300))<100) throw new AssertionError("Recognition capture invalid");
            if(Color.red(before.getPixel(1190,50))<200 || Color.green(before.getPixel(1190,675))<200) throw new AssertionError("Capture upside down"); before.recycle();
            bridge.previewTouch(MotionEvent.ACTION_DOWN,640,360,41); bridge.previewTouch(MotionEvent.ACTION_UP,640,360,41);
            Thread.sleep(400); Bitmap after=capture(bridge);
            if(Color.green(after.getPixel(100,300))<130) throw new AssertionError("Preview tap not delivered"); after.recycle();
            bridge.previewTouch(MotionEvent.ACTION_DOWN,200,300,41);
            bridge.previewTouch(MotionEvent.ACTION_MOVE,400,350,41); bridge.previewTouch(MotionEvent.ACTION_MOVE,600,350,41);
            bridge.previewTouch(MotionEvent.ACTION_UP,800,400,41);
            bridge.clearPreviewSurface(40);
            if(!new JSONObject(bridge.previewStatus()).getBoolean("attached")) throw new AssertionError("Stale detach removed new preview");
            bridge.resizeDisplay(720,1280); Thread.sleep(450);
            Bitmap portrait=capture(bridge); if(portrait.getWidth()!=720 || portrait.getHeight()!=1280) throw new AssertionError("Portrait capture invalid"); portrait.recycle();
            bridge.resizeDisplay(1280,720); Thread.sleep(450);
            bridge.previewTouch(MotionEvent.ACTION_DOWN,640,360,41); // Detach must cancel the held pointer.
            bridge.clearPreviewSurface(41); Thread.sleep(200); int stopped=frames.get(); Thread.sleep(500);
            if(new JSONObject(bridge.previewStatus()).getBoolean("attached") || frames.get()!=stopped) throw new AssertionError("Preview kept rendering after detach");
            Bitmap detached=capture(bridge); detached.recycle();
            bridge.setPreviewSurface(copy(sink.getSurface()),42); Thread.sleep(400);
            if(frames.get()<=stopped) throw new AssertionError("Reattach failed");
            bridge.releaseDisplay();
            if(new JSONObject(bridge.previewStatus()).getBoolean("attached")) throw new AssertionError("Release kept preview alive");
            bridge.createDisplay(240); bridge.releaseDisplay();
            System.out.println("PASS continuous GPU preview: "+frames+" buffers, "+changed+" changing frames; colors/orientation, concurrent recognition capture, tap/swipe, resize/restore, stale detach, detach/cancel, reattach, release/recreate");
            sink.close(); thread.quitSafely(); System.exit(0);
        } catch(Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}

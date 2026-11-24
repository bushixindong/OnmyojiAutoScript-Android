package com.oas.android.bridge;

import android.graphics.*;
import android.media.ImageReader;
import android.os.*;
import android.view.MotionEvent;
import android.view.Surface;
import org.json.JSONObject;
import java.util.Arrays;

/** No account/game actions: actual virtual display, RGB transport, multi-touch and reversible audio/power. */
public final class OptimizationSmokeMain {
    private static Surface copy(Surface surface) {
        Parcel parcel=Parcel.obtain();try{surface.writeToParcel(parcel,0);parcel.setDataPosition(0);return Surface.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}
    }
    private static Bitmap png(DeviceBridge bridge) throws Exception {
        try(ParcelFileDescriptor fd=bridge.screenshot();ParcelFileDescriptor.AutoCloseInputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            Bitmap frame=BitmapFactory.decodeStream(in);if(frame==null)throw new AssertionError("PNG capture failed");return frame;
        }
    }
    private static double median(long[] values){Arrays.sort(values);return values[values.length/2]/1e6;}
    public static void main(String[] args) {
        Looper.prepareMainLooper();DeviceBridge bridge=new DeviceBridge();HandlerThread consume=new HandlerThread("optimization-consumer");consume.start();
        ImageReader sink=ImageReader.newInstance(1280,720,PixelFormat.RGBA_8888,3);
        sink.setOnImageAvailableListener(reader->{try(android.media.Image image=reader.acquireLatestImage()){}},new Handler(consume.getLooper()));
        try(RawFrameReader raw=new RawFrameReader()) {
            int id=bridge.createDisplay(160);bridge.launch("com.oas.android");Thread.sleep(700);
            JSONObject report=new JSONObject().put("sdk",Build.VERSION.SDK_INT).put("displayId",id);
            byte[] bytes=raw.read(bridge);Bitmap frame=RawFrameReader.bitmap(bytes);
            if(frame.getWidth()!=1280||frame.getHeight()!=720||Color.red(frame.getPixel(1190,50))<200||Color.green(frame.getPixel(1190,675))<200)throw new AssertionError("Raw RGB dimensions/color/orientation");frame.recycle();
            // Binder recipients must never be able to alter the shell frame store.
            Bundle buffer=bridge.rawFrameBuffer();SharedMemory source=buffer.getParcelable("memory");boolean protectedStore=false;
            try{java.nio.ByteBuffer bad=source.mapReadWrite();SharedMemory.unmap(bad);}catch(android.system.ErrnoException expected){protectedStore=true;}
            if(!protectedStore)throw new AssertionError("Shared memory was writable by a reader");
            for(int i=0;i<6;i++)raw.read(bridge);
            long[] fast=new long[25],slow=new long[9];
            for(int i=0;i<fast.length;i++){long start=System.nanoTime();raw.read(bridge);fast[i]=System.nanoTime()-start;}
            for(int i=0;i<slow.length;i++){long start=System.nanoTime();Bitmap image=png(bridge);image.recycle();slow[i]=System.nanoTime()-start;}
            report.put("rawMedianMs",median(fast)).put("pngMedianMs",median(slow)).put("readOnlySharedMemory",true);
            bridge.configureRuntime(true,true,true);bridge.configureFrameRates(60,60);
            bridge.setPreviewSurface(copy(sink.getSurface()),51);Thread.sleep(600);
            bridge.previewPointers(MotionEvent.ACTION_DOWN,0,new int[]{0},new float[]{200,300},51);
            bridge.previewPointers(MotionEvent.ACTION_POINTER_DOWN,1,new int[]{0,1},new float[]{200,300,800,300},51);
            bridge.previewPointers(MotionEvent.ACTION_MOVE,0,new int[]{0,1},new float[]{250,350,750,350},51);
            bridge.previewPointers(MotionEvent.ACTION_POINTER_UP,0,new int[]{0,1},new float[]{250,350,750,350},51);
            bridge.previewPointers(MotionEvent.ACTION_MOVE,0,new int[]{1},new float[]{700,400},51);
            bridge.previewPointers(MotionEvent.ACTION_UP,0,new int[]{1},new float[]{700,400},51);
            Thread.sleep(150);Bitmap touches=RawFrameReader.bitmap(raw.read(bridge));int pointers=touches.getPixel(160,620);touches.recycle();
            if(Color.red(pointers)!=2||Color.green(pointers)!=1||Color.blue(pointers)!=1)throw new AssertionError("Multi-touch sequence not delivered: "+Integer.toHexString(pointers));
            bridge.previewTouch(MotionEvent.ACTION_DOWN,640,360,51);bridge.clearPreviewSurface(51);Thread.sleep(150);
            Bitmap cancelled=RawFrameReader.bitmap(raw.read(bridge));if(Color.red(cancelled.getPixel(220,620))<1)throw new AssertionError("Detach did not cancel held pointer");cancelled.recycle();
            report.put("multiTouch",true).put("detachCancelsPointers",true);
            bridge.resizeDisplay(720,1280);Thread.sleep(350);Bitmap portrait=RawFrameReader.bitmap(raw.read(bridge));
            if(portrait.getWidth()!=720||portrait.getHeight()!=1280)throw new AssertionError("Old raw mapping survived resize");portrait.recycle();
            bridge.resizeDisplay(1280,720);Thread.sleep(350);Bitmap landscape=RawFrameReader.bitmap(raw.read(bridge));if(landscape.getWidth()!=1280)throw new AssertionError("Raw restore");landscape.recycle();
            JSONObject state=new JSONObject(bridge.systemStatus());if(!state.getBoolean("runtimeActive")||!state.getBoolean("keepAlive"))throw new AssertionError("Runtime keepalive missing: "+state);
            Thread.sleep(1100);state=new JSONObject(bridge.systemStatus());if(state.getDouble("fps")<=0)throw new AssertionError("FPS did not update: "+state);report.put("system",state);
            int previous=new JSONObject(bridge.gameAudioState("com.oas.android")).getInt("mode");
            try{bridge.setGameMuted("com.oas.android",true,previous);if(!new JSONObject(bridge.gameAudioState("com.oas.android")).getBoolean("muted"))throw new AssertionError("Game mute not tracked");}
            finally{bridge.setGameMuted("com.oas.android",false,previous);}
            if(new JSONObject(bridge.gameAudioState("com.oas.android")).getInt("mode")!=previous)throw new AssertionError("Original audio mode not restored");report.put("audioRestored",true);
            if(args.length>0&&args[0].equals("panel")) {
                try{bridge.setDisplayPower(false);Thread.sleep(500);report.put("panelOff",new JSONObject(bridge.systemStatus()).optBoolean("panelOff"));}
                finally{bridge.setDisplayPower(true);}
            }
            bridge.configureRuntime(false,true,true);bridge.releaseDisplay();
            boolean rejected=false;try{raw.read(bridge);}catch(IllegalStateException expected){rejected=true;}if(!rejected)throw new AssertionError("Read stale frame after release");
            bridge.createDisplay(240);bridge.launch("com.oas.android");Thread.sleep(350);Bitmap recreated=RawFrameReader.bitmap(raw.read(bridge));recreated.recycle();bridge.releaseDisplay();
            report.put("rawResizeReleaseRecreate",true).put("status","passed");System.out.println(report);sink.close();consume.quitSafely();System.exit(0);
        }catch(Throwable failure){try{bridge.setDisplayPower(true);}catch(Exception ignored){}try{bridge.releaseDisplay();}catch(Exception ignored){}failure.printStackTrace();System.exit(1);}
    }
}

package com.oas.android.bridge;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

/** Developer-only shell entry point: tests the exact same service without granting app permissions. */
public final class SmokeMain {
    private static Bitmap screenshot(DeviceBridge bridge) throws Exception {
        try (ParcelFileDescriptor fd = bridge.screenshot(); ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap == null || bitmap.getWidth() != 1280 || bitmap.getHeight() != 720) throw new AssertionError("Invalid screenshot");
            return bitmap;
        }
    }
    public static void main(String[] args) {
        Looper.prepareMainLooper();
        DeviceBridge bridge = new DeviceBridge();
        try {
            int id = bridge.createDisplay(160);
            if (id <= 0) throw new AssertionError("Expected a separate virtual display");
            bridge.launch("com.oas.android"); Thread.sleep(1200);
            Bitmap before = screenshot(bridge);
            int blue = before.getPixel(100, 300); before.recycle();
            if (Color.blue(blue) < 100 || Color.green(blue) > 110) throw new AssertionError("Probe was not blue: " + Integer.toHexString(blue));
            bridge.tap(640, 360); Thread.sleep(500);
            Bitmap after = screenshot(bridge);
            int green = after.getPixel(100, 300); after.recycle();
            if (Color.green(green) < 130 || Color.blue(green) > 110) throw new AssertionError("Touch did not reach virtual display: " + Integer.toHexString(green));
            bridge.swipe(300, 300, 900, 500, 250);
            bridge.pressAndDrag(200,250,700,500,200,300);
            Thread.sleep(200);Bitmap counters=screenshot(bridge);int old=counters.getPixel(100,620);counters.recycle();
            bridge.drawPath(new int[]{100,250,200,300,400,400,600,350,800,250},20);
            Thread.sleep(250);Bitmap drawn=screenshot(bridge);int updated=drawn.getPixel(100,620);drawn.recycle();
            if(Color.red(updated)!=Color.red(old)+1||Color.blue(updated)!=Color.blue(old)+1||Color.green(updated)<Color.green(old)+4)throw new AssertionError("Path did not deliver one continuous DOWN/MOVE/UP sequence");
            boolean pathRejected=false;try{bridge.drawPath(new int[]{10,10,1280,720},10);}catch(IllegalStateException expected){pathRejected=true;}
            if(!pathRejected)throw new AssertionError("Invalid path accepted");
            bridge.resizeDisplay(720,1280);Thread.sleep(500);
            try(ParcelFileDescriptor fd=bridge.screenshot();ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){
                Bitmap portrait=BitmapFactory.decodeStream(input);if(portrait==null||portrait.getWidth()!=720||portrait.getHeight()!=1280)throw new AssertionError("Portrait virtual display capture failed");portrait.recycle();
            }
            bridge.resizeDisplay(1280,720);Thread.sleep(500);
            System.out.println("PASS hold-drag, continuous path events, invalid path rejection, virtual portrait resize/restore");
            boolean invalidRejected = false;
            try { bridge.tap(1280, 720); } catch (IllegalStateException expected) { invalidRejected = true; }
            if (!invalidRejected) throw new AssertionError("Out-of-bounds touch was accepted");
            System.out.println("PASS virtual display, 1280x720 screenshot, display-specific tap/swipe, coordinate validation");
            System.out.println(bridge.status());
            bridge.releaseDisplay();
            if (!bridge.status().contains("\"displayId\":-1")) throw new AssertionError("Display was not released");
            bridge.createDisplay(240); bridge.releaseDisplay();
            System.out.println("PASS release and recreate");
            System.exit(0);
        } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    }
}

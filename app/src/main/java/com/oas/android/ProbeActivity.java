package com.oas.android;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

/** A controlled target for verifying virtual-screen capture and display-specific injection. */
public final class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        setContentView(new View(this) {
            final Paint paint = new Paint(3);
            boolean touched;
            int downs,moves,ups;
            int maxPointers,pointerDowns,pointerUps,cancels;
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(touched ? Color.rgb(26, 153, 83) : Color.rgb(31, 68, 146));
                paint.setColor(Color.WHITE); paint.setTextSize(42);
                canvas.drawText("OAS · 1280 × 720", 80, 120, paint);
                paint.setTextSize(27);
                canvas.drawText(touched ? "Touch received on virtual display" : "Tap or swipe to test input", 80, 180, paint);
                paint.setColor(Color.rgb(237, 195, 100));
                canvas.drawRect(500, 280, 780, 440, paint);
                paint.setColor(Color.rgb(Math.min(255,downs),Math.min(255,moves),Math.min(255,ups)));
                canvas.drawRect(80,600,120,640,paint);
                paint.setColor(Color.rgb(maxPointers,pointerDowns,pointerUps));canvas.drawRect(140,600,180,640,paint);
                paint.setColor(Color.rgb(cancels,0,0));canvas.drawRect(200,600,240,640,paint);
                paint.setColor(Color.RED); canvas.drawRect(1150,30,1230,80,paint);
                paint.setColor(Color.CYAN); canvas.drawRect(1150,650,1230,700,paint);
                long tick=android.os.SystemClock.uptimeMillis();
                paint.setColor(Color.HSVToColor(new float[]{(tick/8)%360,1,1}));
                canvas.drawRect(950,250,1200,430,paint);
                paint.setColor(Color.WHITE); paint.setTextSize(24);
                canvas.drawText("LIVE "+tick,930,480,paint);
                // Keep producing frames, so capture/latency can be measured on a static test page.
                postInvalidateDelayed(16);
            }
            @Override public boolean onTouchEvent(MotionEvent event) {
                maxPointers=Math.min(255,Math.max(maxPointers,event.getPointerCount()));
                if(event.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN)pointerDowns=Math.min(255,pointerDowns+1);
                if(event.getActionMasked()==MotionEvent.ACTION_POINTER_UP)pointerUps=Math.min(255,pointerUps+1);
                if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)cancels=Math.min(255,cancels+1);
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN)downs++;
                if(event.getActionMasked()==MotionEvent.ACTION_MOVE)moves++;
                if(event.getActionMasked()==MotionEvent.ACTION_UP)ups++;
                if (event.getAction() == MotionEvent.ACTION_UP) { touched = true; invalidate(); }
                return true;
            }
        });
    }
}

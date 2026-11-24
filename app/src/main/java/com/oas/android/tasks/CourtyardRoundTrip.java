package com.oas.android.tasks;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import com.oas.android.DeviceSession;
import com.oas.android.vision.ScreenRecognizer;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Port of OAS page_main -> page_act_list -> page_main, using the original edge recognizers. */
public final class CourtyardRoundTrip {
    private CourtyardRoundTrip() { }
    public static String run(Context context, DeviceSession session, ScreenRecognizer recognizer, BooleanSupplier running) throws Exception {
        await(session, recognizer, running, "I_CHECK_MAIN");
        click(session, recognizer, running, "I_ACT_LIST_EXPAND");
        await(session, recognizer, running, "I_CHECK_ACT_LIST");
        click(session, recognizer, running, "I_UI_BACK_YELLOW");
        await(session, recognizer, running, "I_CHECK_MAIN");
        Bitmap frame = session.screenshot();
        try (java.io.FileOutputStream output = context.openFileOutput("last-capture.png", 0)) { frame.compress(Bitmap.CompressFormat.PNG, 100, output); }
        finally { frame.recycle(); }
        return "任务完成：庭院 → 活动列表 → 庭院。未进入活动、领取奖励或消耗体力。";
    }
    private static void click(DeviceSession session, ScreenRecognizer recognizer, BooleanSupplier running, String id) throws Exception {
        ScreenRecognizer.Match match = await(session, recognizer, running, id);
        if (!running.getAsBoolean()) throw new InterruptedException();
        session.requireBridge().tap(match.x, match.y);
        Thread.sleep(550);
    }
    private static ScreenRecognizer.Match await(DeviceSession session, ScreenRecognizer recognizer, BooleanSupplier running, String id) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 12000;
        while (running.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) {
            Bitmap frame = session.screenshot();
            List<ScreenRecognizer.Match> matches;
            try { matches = recognizer.recognize(frame, java.util.Collections.singleton(id)); } finally { frame.recycle(); }
            for (ScreenRecognizer.Match match : matches) if (match.id.equals(id) && match.matched()) return match;
            Thread.sleep(350);
        }
        if (!running.getAsBoolean()) throw new InterruptedException();
        throw new IllegalStateException("等待页面标志超时：" + id + "；已停止操作");
    }
}

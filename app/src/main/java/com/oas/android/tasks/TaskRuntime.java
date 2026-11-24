package com.oas.android.tasks;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import com.oas.android.DeviceSession;
import com.oas.android.vision.ScreenRecognizer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** One serialized, bounded task run. Every touch checks cancellation again. */
public final class TaskRuntime {
    public final Context context;
    public final DeviceSession session;
    public final ScreenRecognizer recognizer;
    public final JSONObject report = new JSONObject();
    public final JSONArray events = new JSONArray();
    private final BooleanSupplier running;
    private final long deadline;
    private int touches;
    private int touchLimit=100;
    public TaskRuntime(Context context, DeviceSession session, ScreenRecognizer recognizer,
            BooleanSupplier running, long timeout) throws Exception {
        this.context = context; this.session = session; this.recognizer = recognizer; this.running = running;
        deadline = SystemClock.elapsedRealtime() + timeout;
        report.put("startedAt", java.time.Instant.now().toString()); report.put("events", events);
    }
    public void check() throws InterruptedException {
        if (!running.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedException("用户停止");
        if (SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("任务达到总时限，已停止");
    }
    public void log(String message) {
        events.put(message);session.update(message);
        if(com.oas.android.BuildConfig.DEBUG)android.util.Log.i("OAS_TASK",message);
    }
    public Map<String, ScreenRecognizer.Match> scan(String... ids) throws Exception {
        check(); Bitmap frame = session.screenshot();
        try {
            Map<String, ScreenRecognizer.Match> hits = new LinkedHashMap<>();
            for (ScreenRecognizer.Match m : recognizer.recognize(frame, Arrays.asList(ids))) if (m.matched()) hits.put(m.id, m);
            return hits;
        } finally { frame.recycle(); }
    }
    public ScreenRecognizer.Match await(long timeout, String... ids) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        do {
            Map<String, ScreenRecognizer.Match> hits = scan(ids);
            for (String id : ids) if (hits.containsKey(id)) return hits.get(id);
            pause(300);
        } while (SystemClock.elapsedRealtime() < until);
        return null;
    }
    public void require(String id) throws Exception {
        if (await(10000, id) == null) throw new IllegalStateException("等待页面超时：" + id);
    }
    public void click(ScreenRecognizer.Match match) throws Exception {
        if (match == null) throw new IllegalStateException("未识别到按钮");
        check(); if (++touches > touchLimit) throw new IllegalStateException("达到操作次数上限");
        session.requireBridge().tap(match.x, match.y); log("点击：" + match.label); pause(650);
    }
    public void click(String id) throws Exception { click(await(6000, id)); }
    public void touchLimit(int limit) { if(limit<1||limit>2000)throw new IllegalArgumentException("触控上限无效");touchLimit=limit; }
    public void swipe(int x1,int y1,int x2,int y2,int duration)throws Exception{
        check();if(++touches>touchLimit)throw new IllegalStateException("达到操作次数上限");
        session.requireBridge().swipe(x1,y1,x2,y2,duration);log("列表滚动");pause(650);
    }
    public void region(String id) throws Exception {
        check();
        JSONObject root;
        try (java.io.InputStream input = context.getAssets().open("oas/catalog.json")) {
            root = new JSONObject(com.oas.android.MainActivity.readText(input));
        }
        JSONArray rules = root.getJSONArray("clicks");
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            if (!rule.getString("id").equals(id)) continue;
            JSONArray r = rule.getJSONArray("roi");
            click(new ScreenRecognizer.Match(id, rule.getString("label"), 1, 1,
                r.getInt(0) + r.getInt(2)/2, r.getInt(1) + r.getInt(3)/2)); return;
        }
        throw new IllegalArgumentException("Unknown click rule " + id);
    }
    public void pause(long millis) throws InterruptedException { check(); Thread.sleep(millis); check(); }
    public boolean capture(String name) {
        try {
            Bitmap frame = session.screenshot();
            try (java.io.FileOutputStream output = context.openFileOutput(name, 0)) { frame.compress(Bitmap.CompressFormat.PNG, 100, output); }
            finally { frame.recycle(); }
            return true;
        } catch (Exception e) { events.put("截图保存失败：" + e.getMessage()); return false; }
    }
    public String finish(String action, String status, String detail) throws Exception {
        report.put("action", action); report.put("status", status); report.put("detail", detail);
        report.put("finishedAt", java.time.Instant.now().toString()); report.put("touches", touches);
        boolean captured = capture("last-capture.png");
        report.put("screenshotAvailable", captured);
        if (captured && status.equals("failed")) java.nio.file.Files.copy(
            new java.io.File(context.getFilesDir(), "last-capture.png").toPath(),
            new java.io.File(context.getFilesDir(), "last-failure.png").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String result = report.toString(2);
        try (java.io.FileOutputStream output = context.openFileOutput("last-task.json", 0)) {
            output.write(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        context.getSharedPreferences("task_records", 0).edit().putString("last", result).apply();
        session.update(detail); return result;
    }
}

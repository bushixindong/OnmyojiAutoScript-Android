package com.oas.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.IBinder;
import com.oas.android.tasks.BattleTask;
import com.oas.android.tasks.DailyTasks;
import com.oas.android.tasks.TaskRuntime;
import com.oas.android.tasks.DungeonConfig;
import com.oas.android.tasks.DungeonTask;
import com.oas.android.tasks.Navigator;
import com.oas.android.engine.PythonHost;
import com.oas.android.engine.TaskEngine;
import com.oas.android.vision.TextDetector;
import com.oas.android.vision.ScreenRecognizer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** One foreground worker, one task owner, bounded runtime and persisted results. */
public final class MonitorService extends Service {
    public static final java.util.List<String> ACTIONS = java.util.Collections.unmodifiableList(Arrays.asList(
        "recognize", "navigate", "wait", "daily", "inspect", "mail", "affairs", "store", "income", "battle", "stop",
        "goto-main", "ocr-text", "dungeon", "dungeon-check", "engine", "engine-schedule"));
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private DeviceSession session;
    @Override public void onCreate() {
        super.onCreate(); session = ((OasApp) getApplication()).session;
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("oas_tasks", "OASA 任务", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent == null ? "" : intent.getAction();
        if ("stop".equals(action)) { running.set(false); TaskEngine.requestStop(); stopSelf(); return START_NOT_STICKY; }
        if (running.get()) { session.update("已有任务运行，请先停止或等待结束"); return START_NOT_STICKY; }
        Intent stop = new Intent(this, MonitorService.class).setAction("stop");
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "oas_tasks").setSmallIcon(R.drawable.ic_oas)
            .setContentTitle("OASA · 任务运行中").setContentText("点击 OASA 查看进度，随时可以停止")
            .setContentIntent(open).addAction(new Notification.Action.Builder(null, "停止", stopAction).build()).build());
        if (!ACTIONS.contains(action) || !session.taskActive.compareAndSet(false, true)) {
            session.update("任务无法启动：无效任务或上一任务仍在停止中"); stopSelf(); return START_NOT_STICKY;
        }
        running.set(true);
        String packageName = intent.getStringExtra("package");
        int rounds = getSharedPreferences("tasks", 0).getInt("rounds", 1);
        boolean prepare = getSharedPreferences("tasks", 0).getBoolean("prepare", false);
        String engineTask=intent.getStringExtra("engineTask");
        worker.submit(() -> execute(action, packageName, rounds, prepare,engineTask));
        return START_NOT_STICKY;
    }
    private void execute(String action, String packageName, int rounds, boolean prepare,String engineTask) {
        TaskRuntime task = null;
        try (RuntimePowerGuard power=new RuntimePowerGuard(this,session,packageName,7800000L);
             ScreenRecognizer recognizer = new ScreenRecognizer(this)) {
            DungeonConfig dungeon=null;
            if(action.equals("dungeon")||action.equals("dungeon-check")){
                android.content.SharedPreferences options=getSharedPreferences("tasks",0);
                dungeon=new DungeonConfig(options.getString("dungeon_kind","orochi"),options.getInt("dungeon_variant",0),
                    options.getInt("dungeon_layer",10),options.getInt("dungeon_rounds",1),options.getInt("dungeon_minutes",10),
                    options.getBoolean("dungeon_lock",true),options.getBoolean("dungeon_stop_loss",true),action.equals("dungeon-check"));
            }
            long timeout=action.startsWith("engine")?7200000:dungeon!=null&&!dungeon.inspect?dungeon.minutes*60000L+600000:action.equals("battle")||action.equals("wait")?600000:180000;
            task = new TaskRuntime(this, session, recognizer, running::get, timeout);
            task.report.put("package", packageName);
            task.check();
            if (session.primaryMode) { session.requireBridge().launch(packageName); task.pause(700); }
            String detail,status="finished";
            switch (action) {
                case "recognize": detail = recognize(recognizer); break;
                case "navigate":
                    detail = com.oas.android.tasks.CourtyardRoundTrip.run(this, session, recognizer, running::get);
                    write("last-navigation.txt", detail); break;
                case "wait": detail = waitResult(task); break;
                case "battle": detail = BattleTask.run(task, rounds, prepare); break;
                case "goto-main": new Navigator(task).go("main");detail="已通过已知页面路径返回庭院";break;
                case "dungeon": case "dungeon-check": detail=new DungeonTask(task,dungeon).run();break;
                case "ocr-text": detail=readText(task);break;
                case "engine":case "engine-schedule":
                    PythonHost host=new PythonHost(session,packageName);host.begin(running::get,timeout);
                    org.json.JSONObject result;
                    try{
                        com.chaquo.python.PyObject engine=TaskEngine.initialize(this,host);
                        result=new org.json.JSONObject((action.equals("engine-schedule")?engine.callAttr("schedule",host):engine.callAttr("run",engineTask,host)).toString());
                    }
                    finally{host.restoreOrientation();host.close();}
                    task.report.put("engine",result).put("engineTouches",host.touches());
                    detail=result.getString("detail");status=running.get()?result.getString("status"):"cancelled";break;
                default: detail = new DailyTasks(task, action.equals("inspect"), packageName).run(action); break;
            }
            task.finish(action, status, detail);
        } catch (Exception error) {
            boolean cancelled = error instanceof InterruptedException || !running.get();
            String detail = cancelled ? "任务已停止" : "任务停止：" + error.getMessage();
            android.util.Log.e("OAS", detail, error);
            boolean interrupted = Thread.interrupted();
            try {
                if (task != null) task.finish(action, cancelled ? "cancelled" : "failed", detail);
                else {
                    String fallback = new org.json.JSONObject().put("action", action).put("status", cancelled ? "cancelled" : "failed")
                        .put("detail", detail).put("screenshotAvailable", false).put("finishedAt", java.time.Instant.now().toString()).toString(2);
                    write("last-task.json", fallback);
                    getSharedPreferences("task_records", 0).edit().putString("last", fallback).apply();
                    session.update(detail);
                }
            } catch (Exception saving) { session.update(detail + "；记录保存失败：" + saving.getMessage()); }
            if (interrupted) Thread.currentThread().interrupt();
        } finally { running.set(false); session.taskActive.set(false); session.notifyStateChanged(); stopSelf(); }
    }
    private String recognize(ScreenRecognizer recognizer) throws Exception {
        Bitmap frame = session.screenshot();
        try {
            StringBuilder result = new StringBuilder("页面识别完成\n");
            for (ScreenRecognizer.Match match : recognizer.recognize(frame)) result.append(match).append('\n');
            try (java.io.FileOutputStream stream = openFileOutput("last-capture.png", 0)) { frame.compress(Bitmap.CompressFormat.PNG, 100, stream); }
            write("last-recognition.txt", result.toString()); return result.toString();
        } finally { frame.recycle(); }
    }
    private String readText(TaskRuntime task)throws Exception{
        Bitmap frame=session.screenshot();org.json.JSONArray results=new org.json.JSONArray();
        try(TextDetector detector=new TextDetector(this)){
            for(TextDetector.Box box:detector.read(frame)){
                task.check();results.put(new org.json.JSONObject().put("text",box.text.text).put("confidence",box.text.confidence)
                    .put("centerX",box.centerX()).put("centerY",box.centerY()));
            }
        }finally{frame.recycle();}
        task.report.put("text",results);return "画面文字识别完成，共 "+results.length()+" 处，详见任务结果";
    }
    private String waitResult(TaskRuntime task) throws Exception {
        String previous = ""; int confirmations = 0;
        task.log("只观察结算：不点击，最长等待 10 分钟");
        while (true) {
            java.util.Map<String, ScreenRecognizer.Match> hits = task.scan("I_FALSE", "I_WIN", "I_REWARD");
            String current = hits.containsKey("I_FALSE") ? "I_FALSE" : hits.containsKey("I_WIN") ? "I_WIN" : hits.containsKey("I_REWARD") ? "I_REWARD" : "";
            confirmations = !current.isEmpty() && current.equals(previous) ? confirmations + 1 : current.isEmpty() ? 0 : 1;
            previous = current;
            if (confirmations >= 2) return "结算观察完成：" + hits.get(current).label + "（连续两帧，不代表已完成收尾）";
            task.pause(800);
        }
    }
    private void write(String filename, String value) throws Exception {
        try (java.io.FileOutputStream output = openFileOutput(filename, 0)) { output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }
    @Override public void onDestroy() {
        running.set(false);
        TaskEngine.requestStop();
        // A stop can arrive before the executor starts; do not leave an abandoned owner lock.
        if (!worker.shutdownNow().isEmpty()) { session.taskActive.set(false); session.notifyStateChanged(); }
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

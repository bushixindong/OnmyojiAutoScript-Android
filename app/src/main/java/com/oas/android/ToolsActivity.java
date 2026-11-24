package com.oas.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import com.oas.android.vision.PreviewCoordinates;
import com.oas.android.vision.ScreenRecognizer;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ToolsActivity extends Activity {
    private static final int INK = Color.rgb(28, 27, 24), MUTED = Color.rgb(138, 133, 128);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final StringBuilder history = new StringBuilder();
    private DeviceSession session;
    private TextView status, output;
    private final Runnable refreshStatus = () -> { if (status != null) status.setText(session.message); };
    private EditText packageName;
    private EditText battleRounds;
    private EditText dungeonRounds,dungeonMinutes;
    private Spinner dungeonChoice,dungeonVariant,dungeonLayer;
    private ImageView preview;
    private Bitmap previewBitmap;
    private boolean destroyed;
    private int[] down;
    private long downAt;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        session = ((OasApp) getApplication()).session;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 242, 237));
        root.setFocusableInTouchMode(true);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom()); return insets; });
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        button(root,"‹ 返回",this::finish);
        text(root, "OAS / TOOLS", 11, MUTED, true);
        text(root, "高级工具", 30, INK, true);
        text(root, BuildConfig.VERSION_NAME + "  ·  Shizuku 免 root  ·  720p 虚拟屏", 13, MUTED, false);

        LinearLayout connection = card(root, "01  连接设备");
        status = text(connection, session.message, 15, INK, false);
        button(connection, "授权并连接 Shizuku", session::connect);
        button(connection, "打开 Shizuku", () -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (intent != null) startActivity(intent);
            else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
        });

        LinearLayout device = card(root, "02  创建运行环境");
        text(device, "独立创建 1280×720 虚拟屏，不修改手机主屏分辨率。", 13, MUTED, false);
        Spinner density = new Spinner(this);
        ArrayAdapter<String> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"240 DPI · 与 OAS / MuMu 常用设置一致", "160 DPI · MaaMeow 默认"});
        density.setAdapter(choices); device.addView(density);
        button(device, "创建 720p 虚拟屏", () -> {
            int dpi = density.getSelectedItemPosition() == 0 ? 240 : 160;
            run("创建虚拟屏", () -> {
                int id = session.requireBridge().createDisplay(dpi); session.primaryMode = false;
                return "虚拟屏已创建 · Display " + id + "\n" + session.requireBridge().status();
            });
        });
        button(device, "使用前台兼容模式", () -> run("前台兼容模式", () -> {
            session.requireBridge().usePrimaryDisplay(); session.primaryMode = true;
            return "已使用主屏，不更改分辨率。请在 1280×720 环境使用；识别任务会把游戏切到前台。";
        }));
        button(device, "打开触控测试画面", () -> run("测试画面", () -> session.requireBridge().launch(getPackageName())));
        packageName = new EditText(this); packageName.setSingleLine(); packageName.setTextSize(13);
        packageName.setText(getSharedPreferences("MainActivity",0).getString("package", detectGamePackage()));
        packageName.setHint("阴阳师应用包名"); device.addView(packageName);
        button(device, "启动 / 接管阴阳师", () -> {
            String pkg = packageName.getText().toString().trim(); getSharedPreferences("MainActivity",0).edit().putString("package", pkg).apply();
            run("启动阴阳师", () -> session.requireBridge().launch(pkg));
        });
        button(device, "释放虚拟屏", () -> {
            stopService(new Intent(this, MonitorService.class));
            run("释放虚拟屏", () -> { session.requireBridge().releaseDisplay(); return "虚拟屏已释放"; });
        });

        LinearLayout view = card(root, "03  画面与触控");
        preview = new ImageView(this); preview.setBackgroundColor(Color.rgb(20, 32, 30));
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER); preview.setContentDescription("虚拟屏截图预览");
        view.addView(preview, new LinearLayout.LayoutParams(-1, dp(190)));
        CheckBox gestures = new CheckBox(this); gestures.setText("允许在预览画面中点击和滑动"); gestures.setTextSize(13); view.addView(gestures);
        preview.setOnTouchListener((v, event) -> {
            if (!gestures.isChecked() || previewBitmap == null || session.primaryMode) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                down = PreviewCoordinates.map(v.getWidth(), v.getHeight(), event.getX(), event.getY()); downAt = android.os.SystemClock.uptimeMillis();
                if (down != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                return down != null;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP && down != null) {
                int[] start = down; down = null; v.getParent().requestDisallowInterceptTouchEvent(false);
                int[] end = PreviewCoordinates.map(v.getWidth(), v.getHeight(), event.getX(), event.getY());
                if (end != null) {
                    int duration = (int) Math.min(2000, Math.max(70, android.os.SystemClock.uptimeMillis() - downAt));
                    run("预览触控", () -> {
                        session.requireBridge().swipe(start[0], start[1], end[0], end[1], duration);
                        Thread.sleep(250); capture(); return "触控已发送 · " + start[0] + "," + start[1] + " → " + end[0] + "," + end[1];
                    });
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { down = null; v.getParent().requestDisallowInterceptTouchEvent(false); }
            return true;
        });
        button(view, "刷新截图", () -> {
            if (session.primaryMode) { loadLastCapture(); append("前台模式显示最近一次识别任务截图；要更新请运行页面识别。"); }
            else run("截图", () -> { capture(); return "已收到 1280×720 原始截图"; });
        });

        LinearLayout tasks = card(root, "04  OAS 识别与任务");
        button(tasks,"全部任务与完整配置",()->startActivity(new Intent(this,EngineActivity.class).putExtra("package",packageName.getText().toString().trim())));
        text(tasks, "先在默认庭院开始日常任务；资源识别、邮件和战斗结算使用原 OAS 规则。", 13, MUTED, false);
        taskOption(tasks, "affairs", "庭院事务 · 一键完成", true);
        taskOption(tasks, "mail", "邮件 · 收取并标记已读", true);
        taskOption(tasks, "store", "商城 · 免费签到", false);
        taskOption(tasks, "income", "资源 · 金币、勾玉、体力", false);
        button(tasks, "运行所选日常任务", () -> startTask("daily"));
        button(tasks, "仅检查所选日常页面（不领取）", () -> startTask("inspect"));
        button(tasks, "读取庭院资源", () -> startTask("income"));
        button(tasks, "识别画面文字", () -> startTask("ocr-text"));
        button(tasks, "返回庭院", () -> startTask("goto-main"));
        button(tasks, "识别当前页面", () -> {
            if (session.primaryMode) { startTask("recognize"); return; }
            run("页面识别", () -> {
            Bitmap frame = session.screenshot();
            try (ScreenRecognizer recognizer = new ScreenRecognizer(this)) {
                List<ScreenRecognizer.Match> matches = recognizer.recognize(frame);
                StringBuilder result = new StringBuilder(); boolean found = false;
                for (ScreenRecognizer.Match match : matches) { result.append(match).append('\n'); found |= match.matched(); }
                showPreview(frame); frame = null;
                return (found ? "识别结果\n" : "未匹配到已支持页面\n") + result;
            } finally { if (frame != null) frame.recycle(); }
            });
        });
        text(tasks, "战斗辅助只处理已进入的战斗，不负责进入副本或再次挑战。", 13, MUTED, false);
        taskOption(tasks, "prepare", "允许点击准备（可能开始当前战斗）", false);
        battleRounds = new EditText(this); battleRounds.setSingleLine();
        battleRounds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        battleRounds.setHint("辅助场数（1–100）");
        battleRounds.setText(String.valueOf(getSharedPreferences("tasks", 0).getInt("rounds", 1))); tasks.addView(battleRounds);
        button(tasks, "辅助当前战斗与结算", () -> startTask("battle"));
        button(tasks, "仅观察一次结算（不点击）", () -> startTask("wait"));
        button(tasks, "庭院活动列表往返（测试）", () -> startTask("navigate"));
        button(tasks, "停止任务", () -> { stopService(new Intent(this, MonitorService.class)); session.update("正在停止任务"); });
        button(tasks, "查看最近任务结果", this::loadTaskResult);
        text(tasks, "未知页面会停止并保留截图。已迁移范围与待测项目见说明，完整任务请从任务页配置与运行。", 12, MUTED, false);

        LinearLayout dungeons=card(root,"05  单人副本");
        text(dungeons,"运行会消耗体力或副本票；先用检查入口验证导航。使用当前式神和御魂配置。",13,MUTED,false);
        dungeonChoice=choice(dungeons,new String[]{"御魂 · 10–13 层","觉醒 · 1–10 层","御灵","业原火"});
        dungeonVariant=choice(dungeons,new String[]{"八岐大蛇"});
        dungeonLayer=choice(dungeons,new String[]{"10","11","12","13"});
        dungeonChoice.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onNothingSelected(android.widget.AdapterView<?> view){}
            @Override public void onItemSelected(android.widget.AdapterView<?> view,View row,int position,long id){
                String[][] variants={{"八岐大蛇"},{"火麒麟","风麒麟","水麒麟","雷麒麟"},{"暗神龙","暗白藏主","暗黑豹","暗孔雀"},{"贪","嗔","痴"}};
                dungeonVariant.setAdapter(new ArrayAdapter<>(ToolsActivity.this,android.R.layout.simple_spinner_dropdown_item,variants[position]));
                String[] layers=position==0?new String[]{"10","11","12","13"}:position==1?new String[]{"1","2","3","4","5","6","7","8","9","10"}:new String[]{"使用副本默认层数"};
                dungeonLayer.setAdapter(new ArrayAdapter<>(ToolsActivity.this,android.R.layout.simple_spinner_dropdown_item,layers));
                String current=new String[]{"orochi","evozone","goryou","sougenbi"}[position];
                android.content.SharedPreferences options=getSharedPreferences("tasks",0);
                if(current.equals(options.getString("dungeon_kind","orochi"))){
                    dungeonVariant.setSelection(Math.min(variants[position].length-1,Math.max(0,options.getInt("dungeon_variant",0))));
                    dungeonLayer.setSelection(Math.min(layers.length-1,Math.max(0,options.getInt("dungeon_layer",10)-(position==0?10:1))));
                }
            }
        });
        String savedKind=getSharedPreferences("tasks",0).getString("dungeon_kind","orochi");
        dungeonChoice.setSelection(Math.max(0,java.util.Arrays.asList("orochi","evozone","goryou","sougenbi").indexOf(savedKind)));
        text(dungeons,"场数（1–100）与时限（分钟，1–120）",12,MUTED,false);
        dungeonRounds=new EditText(this);dungeonRounds.setSingleLine();dungeonRounds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        dungeonRounds.setText(String.valueOf(getSharedPreferences("tasks",0).getInt("dungeon_rounds",1)));dungeons.addView(dungeonRounds);
        dungeonMinutes=new EditText(this);dungeonMinutes.setSingleLine();dungeonMinutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        dungeonMinutes.setText(String.valueOf(getSharedPreferences("tasks",0).getInt("dungeon_minutes",10)));dungeons.addView(dungeonMinutes);
        taskOption(dungeons,"dungeon_lock","锁定当前阵容",true);
        taskOption(dungeons,"dungeon_stop_loss","失败或胜负未确认时停止",true);
        button(dungeons,"仅检查副本入口（不挑战）",()->startDungeon(true));
        button(dungeons,"开始单人副本",()->startDungeon(false));
        button(dungeons,"停止任务",()->{stopService(new Intent(this,MonitorService.class));session.update("正在停止任务");});

        LinearLayout diagnostics = card(root, "运行记录");
        output = text(diagnostics, "准备就绪。先连接 Shizuku，再创建虚拟屏。", 12, MUTED, false); output.setTextIsSelectable(true);
        button(diagnostics, "检查设备状态", () -> run("设备状态", () -> session.requireBridge().status()));
        button(diagnostics, "导出诊断记录", () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE, "oas-android-diagnostics.txt"); startActivityForResult(intent, 5);
        });
        button(diagnostics, "开源组件与许可证", () -> run("开源声明", () -> {
            try (java.io.InputStream input = getAssets().open("THIRD_PARTY.txt")) { return readText(input); }
        }));
        button(diagnostics, "查看完整 AGPL 许可证", () -> run("完整许可证", () -> {
            try (java.io.InputStream input = getAssets().open("LICENSE.txt")) { return readText(input); }
        }));
        session.addListener(refreshStatus); refreshStatus.run();
        root.requestFocus();
    }

    private String detectGamePackage() {
        // Prefer a game actually installed on this device; retain any explicit saved selection.
        String[] candidates = {"com.netease.onmyoji", "com.netease.onmyoji.wyzymnqsd_cps",
                "com.netease.onmyoji.bili", "com.netease.onmyoji.mi", "com.netease.onmyoji.huawei",
                "com.netease.onmyoji.vivo", "com.netease.onmyoji.nearme.gamecenter",
                "com.netease.onmyoji.m4399", "com.tencent.tmgp.yys.zqb"};
        for (String candidate : candidates) {
            try { getPackageManager().getApplicationInfo(candidate, 0); return candidate; }
            catch (PackageManager.NameNotFoundException ignored) { }
        }
        return "com.netease.onmyoji";
    }

    private void startTask(String action) {
        try {
            if (busy.get() || session.taskActive.get()) { append("上一操作或任务仍在执行，请先停止或等待结束"); return; }
            session.requireBridge();
            if (action.equals("battle")) {
                int rounds;
                try { rounds = Integer.parseInt(battleRounds.getText().toString().trim()); }
                catch (NumberFormatException error) { append("辅助场数请输入 1–100"); return; }
                if (rounds < 1 || rounds > 100) { append("辅助场数请输入 1–100"); return; }
                getSharedPreferences("tasks", 0).edit().putInt("rounds", rounds).apply();
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
            startForegroundService(new Intent(this, MonitorService.class).setAction(action)
                    .putExtra("package", packageName.getText().toString().trim()));
            append("已启动任务，进度显示在连接状态；完成后可查看最近任务结果");
        } catch (Exception e) { append("无法开始：" + e.getMessage()); }
    }

    private void taskOption(LinearLayout parent, String key, String label, boolean fallback) {
        CheckBox option = new CheckBox(this); option.setText(label); option.setTextSize(13);
        option.setChecked(getSharedPreferences("tasks", 0).getBoolean(key, fallback));
        option.setOnCheckedChangeListener((view, checked) -> getSharedPreferences("tasks", 0).edit().putBoolean(key, checked).apply());
        parent.addView(option);
    }
    private Spinner choice(LinearLayout parent,String[] items){
        Spinner spinner=new Spinner(this);spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,items));parent.addView(spinner);return spinner;
    }
    private void startDungeon(boolean inspect){
        try{
            String kind=new String[]{"orochi","evozone","goryou","sougenbi"}[dungeonChoice.getSelectedItemPosition()];
            int layer=kind.equals("orochi")?dungeonLayer.getSelectedItemPosition()+10:kind.equals("evozone")?dungeonLayer.getSelectedItemPosition()+1:1;
            int rounds=Integer.parseInt(dungeonRounds.getText().toString().trim()),minutes=Integer.parseInt(dungeonMinutes.getText().toString().trim());
            android.content.SharedPreferences options=getSharedPreferences("tasks",0);
            new com.oas.android.tasks.DungeonConfig(kind,dungeonVariant.getSelectedItemPosition(),layer,rounds,minutes,
                options.getBoolean("dungeon_lock",true),options.getBoolean("dungeon_stop_loss",true),inspect);
            options.edit().putString("dungeon_kind",kind).putInt("dungeon_variant",dungeonVariant.getSelectedItemPosition())
                .putInt("dungeon_layer",layer).putInt("dungeon_rounds",rounds).putInt("dungeon_minutes",minutes).apply();
            startTask(inspect?"dungeon-check":"dungeon");
        }catch(NumberFormatException error){append("场数和分钟数请输入整数");}
        catch(Exception error){append(error.getMessage());}
    }
    private void loadTaskResult() {
        String last = getSharedPreferences("task_records", 0).getString("last", "尚无任务记录");
        append(last);
        try {
            if (new org.json.JSONObject(last).optBoolean("screenshotAvailable", false)) loadLastCapture();
        } catch (org.json.JSONException ignored) { }
    }

    private void loadLastCapture() {
        worker.submit(() -> {
            java.io.File file = new java.io.File(getFilesDir(), "last-capture.png");
            if (file.exists()) {
                Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bitmap != null) showPreview(bitmap);
            }
        });
    }

    @Override protected void onResume() { super.onResume(); if (session != null && session.primaryMode) loadLastCapture(); }

    private void capture() throws Exception { showPreview(session.screenshot()); }
    private void showPreview(Bitmap frame) {
        runOnUiThread(() -> {
            if (destroyed) { frame.recycle(); return; }
            Bitmap old = previewBitmap; previewBitmap = frame; preview.setImageBitmap(frame);
            if (old != null) old.recycle();
        });
    }
    private interface Operation { String run() throws Exception; }
    private void run(String label, Operation operation) {
        if (!busy.compareAndSet(false, true)) { append("上一项操作仍在执行，请稍候"); return; }
        if (!session.taskActive.compareAndSet(false, true)) { busy.set(false); append("任务仍在运行，请先停止或等待结束"); return; }
        append(label + "…");
        worker.submit(() -> {
            try { String result = operation.run(); runOnUiThread(() -> { if (!destroyed) append(result); }); }
            catch (Exception e) {
                android.util.Log.e("OAS", label, e);
                runOnUiThread(() -> { if (!destroyed) append(label + "失败：" + e.getMessage()); });
            } finally { session.taskActive.set(false); busy.set(false); }
        });
    }
    private void append(String text) {
        history.append(text).append("\n\n");
        if (history.length() > 20000) history.delete(0, history.length() - 16000);
        if (output != null) output.setText(history);
    }
    public static String readText(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
        return out.toString("UTF-8");
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 5 && result == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
                String report = "OAS Android " + BuildConfig.VERSION_NAME + "\n" + Build.MANUFACTURER + " " + Build.MODEL
                        + " / Android " + Build.VERSION.RELEASE + "\n" + session.message + "\n\n" + history;
                stream.write(report.getBytes(StandardCharsets.UTF_8)); append("诊断记录已导出");
            } catch (Exception e) { append("导出失败：" + e.getMessage()); }
        }
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setLineSpacing(dp(3), 1);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(8); parent.addView(text, params); return text;
    }
    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(18), dp(16), dp(12));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.rgb(249,247,243)); background.setCornerRadius(dp(12)); card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(12); parent.addView(card, params);
        TextView header=text(card,title+" ▸",17,INK,true);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);card.addView(body);body.setVisibility(View.GONE);
        header.setMinHeight(dp(48));header.setGravity(android.view.Gravity.CENTER_VERTICAL);header.setOnClickListener(v->{boolean expand=body.getVisibility()==View.GONE;body.setVisibility(expand?View.VISIBLE:View.GONE);header.setText(title+(expand?" ▾":" ▸"));});
        return body;
    }
    private void button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(14); button.setTextColor(INK);
        button.setOnClickListener(v -> action.run()); parent.addView(button, new LinearLayout.LayoutParams(-1, dp(48)));
        com.oas.android.ui.NativeFormStyle.apply(button,false);
    }
    @Override protected void onDestroy() {
        destroyed = true; session.removeListener(refreshStatus); worker.shutdownNow();
        if (preview != null) preview.setImageDrawable(null);
        if (previewBitmap != null) previewBitmap.recycle();
        super.onDestroy();
    }
}

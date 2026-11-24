package com.oas.android.tasks;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import com.oas.android.vision.LineOcr;
import com.oas.android.vision.ResourceNumber;
import com.oas.android.vision.ScreenRecognizer;
import com.oas.android.vision.TextDetector;
import com.oas.android.vision.DetectedNumber;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Native ports of the first DailyTrifles flows. No summons, purchases, gifts or social actions. */
public final class DailyTasks {
    private final TaskRuntime task;
    private final boolean inspect;
    private final String gamePackage;
    private final JSONArray results = new JSONArray();
    public DailyTasks(TaskRuntime task, boolean inspect, String gamePackage) throws Exception {
        this.task = task; this.inspect = inspect; this.gamePackage = gamePackage;
        task.report.put("inspectionOnly", inspect); task.report.put("daily", results);
    }
    public String run(String action) throws Exception {
        if (action.equals("income")) { income(); return "资源识别完成，请查看运行记录"; }
        if (action.equals("mail")) mail();
        else if (action.equals("affairs")) affairs();
        else if (action.equals("store")) store();
        else {
            SharedPreferences config = task.context.getSharedPreferences("tasks", 0);
            if (config.getBoolean("affairs", true)) affairs();
            if (config.getBoolean("mail", true)) mail();
            if (config.getBoolean("store", false)) store();
            if (config.getBoolean("income", false)) income();
        }
        return (inspect ? "日常检查结束（未点击领取或完成）" : "日常流程结束") + "，共 " + results.length() + " 项；各项结果见记录";
    }
    private void record(String id, String status, String detail) throws Exception {
        JSONObject result = new JSONObject().put("id", id).put("status", status).put("detail", detail)
            .put("date", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        results.put(result); task.log(detail);
        // Records are informational, never used to skip a different account in the same APK.
        if (status.equals("verified")) task.context.getSharedPreferences("task_records", 0).edit()
            .putString(gamePackage + ":" + id, result.toString()).apply();
    }
    private void main() throws Exception { new Navigator(task).go("main"); }
    private void back(String... pageIds) throws Exception {
        if (task.await(1500, "I_CHECK_MAIN") != null) return;
        if (task.await(2000, pageIds) == null) throw new IllegalStateException("无法确认返回路径，已停止");
        task.click("I_UI_BACK_YELLOW"); main();
    }
    private void mail() throws Exception {
        task.log("日常：检查邮件"); main();
        ScreenRecognizer.Match entry = task.await(3000, "I_DT_HARVEST_MAIL_COPY2", "I_HARVEST_MAIL", "I_HARVEST_MAIL_COPY");
        if (entry == null) { record("mail", "unavailable", "未找到邮件入口，未记录完成"); return; }
        task.click(entry);
        if (task.await(6000, "I_HARVEST_MAIL_TITLE", "I_HARVEST_MAIL_ALL", "I_READ_ALL_MAIL") == null)
            throw new IllegalStateException("未确认邮件页");
        if (inspect) {
            task.capture("last-inspection-mail.png"); backMail();
            record("mail", "checked", "邮件页检查通过，未领取或标记已读"); return;
        }
        boolean reward = false, attempted = false;
        ScreenRecognizer.Match all = task.await(1500, "I_HARVEST_MAIL_ALL");
        if (all != null) {
            attempted = true; task.click(all);
            for (int i = 0; i < 4; i++) {
                Map<String, ScreenRecognizer.Match> hits = task.scan("I_HARVEST_MAIL_CONFIRM", "I_UI_REWARD", "I_HARVEST_MAIL_TITLE");
                if (hits.containsKey("I_UI_REWARD")) { reward = true; task.region("C_RANDOM_BOTTOM"); }
                else if (hits.containsKey("I_HARVEST_MAIL_CONFIRM")) task.click(hits.get("I_HARVEST_MAIL_CONFIRM"));
                else if (hits.containsKey("I_HARVEST_MAIL_TITLE")) break;
                else task.pause(500);
            }
        }
        // Only mark read on a recognized mail page, at most once.
        if (task.await(2000, "I_HARVEST_MAIL_TITLE", "I_HARVEST_MAIL_ALL", "I_READ_ALL_MAIL") != null) {
            ScreenRecognizer.Match read = task.await(500, "I_READ_ALL_MAIL");
            if (read != null) task.click(read);
        }
        backMail();
        record("mail", reward ? "verified" : "checked", reward ? "邮件领取已观察到奖励并返回庭院" :
            attempted ? "邮件收取已尝试，未观察到奖励；可能无可领取邮件，未记录完成" : "邮件页检查结束，无可识别的领取按钮");
    }
    private void backMail() throws Exception {
        if (task.await(1000, "I_CHECK_MAIN") != null) return;
        if (task.await(2000, "I_HARVEST_MAIL_TITLE", "I_HARVEST_MAIL_ALL", "I_READ_ALL_MAIL") == null)
            throw new IllegalStateException("邮件页状态未知，已停止");
        // The original mail window is a red-close modal, not a yellow-back page.
        task.click("I_UI_BACK_RED"); main();
    }
    private void affairs() throws Exception {
        task.log("日常：庭院事务"); main();
        ScreenRecognizer.Match entry = task.await(3000, "I_ENTER_COURTYARD_AFFAIRS");
        if (entry == null) { record("affairs", "unavailable", "庭院事务入口未出现，跳过"); return; }
        task.click(entry); task.require("I_CHECK_COURTYARD_AFFAIRS");
        if (task.await(1500, "I_CHECK_IN_DAILY") == null) { task.click("I_ENTER_DAILY"); task.require("I_CHECK_IN_DAILY"); }
        if (inspect) {
            task.capture("last-inspection-affairs.png"); back("I_CHECK_COURTYARD_AFFAIRS", "I_CHECK_IN_DAILY");
            record("affairs", "checked", "庭院事务日常页检查通过，未点击一键完成"); return;
        }
        ScreenRecognizer.Match button = task.await(1000, "I_ONE_COMPLETE");
        if (button == null) {
            back("I_CHECK_COURTYARD_AFFAIRS", "I_CHECK_IN_DAILY");
            record("affairs", "checked", "无一键完成按钮，未记录完成"); return;
        }
        task.click(button);
        boolean reward = dismissReward();
        boolean disappeared = task.await(1200, "I_ONE_COMPLETE") == null &&
            task.await(1000, "I_CHECK_IN_DAILY") != null;
        back("I_CHECK_COURTYARD_AFFAIRS", "I_CHECK_IN_DAILY");
        record("affairs", reward || disappeared ? "verified" : "checked", reward || disappeared ?
            "庭院事务一键完成后已确认状态变化并返回" : "已点击一次一键完成，状态未确认；未记录完成");
    }
    private boolean dismissReward() throws Exception {
        boolean reward = false;
        for (int i = 0; i < 3; i++) {
            ScreenRecognizer.Match hit = task.await(i == 0 ? 3000 : 1000, "I_UI_REWARD");
            if (hit == null) break;
            reward = true; task.region("C_RANDOM_BOTTOM");
        }
        return reward;
    }
    private void store() throws Exception {
        task.log("日常：商城免费签到"); main();
        ScreenRecognizer.Match entry = task.await(1000, "I_MAIN_GOTO_MALL");
        if (entry == null) {
            // Conditional courtyard edge from OAS; never tap the scroll on an unknown page.
            task.require("I_CHECK_MAIN"); task.region("C_LOGIN_SCROLL_CLOSE_AREA");
            entry = task.await(2500, "I_MAIN_GOTO_MALL");
        }
        task.click(entry);
        ScreenRecognizer.Match mall = task.await(8000, "I_CHECK_MALL", "I_CHECK_MALL_RECOMMEND");
        if (mall == null) throw new IllegalStateException("商城页面未确认");
        if (mall.id.equals("I_CHECK_MALL_RECOMMEND")) { task.click("I_UI_BACK_YELLOW"); task.require("I_CHECK_MALL"); }
        task.click("I_ROOM_GIFT"); task.require("I_GIFT_RECOMMEND");
        task.click("I_GIFT_RECOMMEND"); task.pause(1000);
        if (inspect) {
            task.capture("last-inspection-store.png"); backStore();
            record("store", "checked", "礼包推荐页检查通过，未点击免费签到"); return;
        }
        ScreenRecognizer.Match sign = task.await(2000, "I_GIFT_SIGN");
        if (sign == null) { backStore(); record("store", "unavailable", "未找到免费签到，可能已领取；未记录完成"); return; }
        task.click(sign);
        boolean reward = dismissReward();
        // Never click an unknown confirmation or a price/currency button.
        if (!reward) throw new IllegalStateException("免费签到后未确认奖励，停止在当前页供检查");
        backStore(); record("store", "verified", "商城免费签到奖励已确认并返回庭院");
    }
    private void backStore() throws Exception {
        task.require("I_GIFT_RECOMMEND"); task.click("I_UI_BACK_YELLOW");
        task.require("I_CHECK_MALL"); task.click("I_UI_BACK_YELLOW"); main();
    }
    private void income() throws Exception {
        task.log("OCR：读取庭院金币、勾玉和体力"); main();
        JSONObject resources = new JSONObject(); boolean allValid = true;
        TextDetector detector=null;
        try (LineOcr ocr = new LineOcr(task.context)) {
            String[] ids = {"O_INCOME_GOLD", "O_INCOME_JADE", "O_INCOME_AP"};
            long[] maxima = {100000000000L, 100000000L, 1000000L};
            Bitmap first = task.session.screenshot();
            try {
                task.pause(350); Bitmap second = task.session.screenshot();
                try {
                    for (int i = 0; i < ids.length; i++) {
                        task.check(); LineOcr.Text a = ocr.read(first, ids[i]), b = ocr.read(second, ids[i]);
                        Long value = ResourceNumber.parse(a.text, maxima[i]), confirm = ResourceNumber.parse(b.text, maxima[i]);
                        boolean valid = a.confidence >= .85 && b.confidence >= .85 && value != null && value.equals(confirm);
                        JSONObject detection=null;
                        if(!valid){
                            if(detector==null)detector=new TextDetector(task.context);
                            java.util.List<TextDetector.Box> detectedA=detector.read(first,ids[i],task.context),detectedB=detector.read(second,ids[i],task.context);
                            Long detectedValue=detectedValue(detectedA,maxima[i]),detectedConfirm=detectedValue(detectedB,maxima[i]);
                            valid=detectedValue!=null&&detectedValue.equals(detectedConfirm);value=valid?detectedValue:null;
                            detection=new JSONObject().put("raw",detectedText(detectedA)).put("confirmation",detectedText(detectedB));
                        }
                        allValid &= valid;
                        resources.put(ids[i], new JSONObject().put("raw", a.text).put("confirmation", b.text)
                            .put("lineConfidence", Math.min(a.confidence, b.confidence)).put("detectionFallback",detection==null?JSONObject.NULL:detection)
                            .put("value", valid ? value : JSONObject.NULL));
                        task.log(ids[i] + "：" + (valid ? value : "未确认") + " · " + a);
                    }
                } finally { second.recycle(); }
            } finally { first.recycle(); }
        }finally{if(detector!=null)detector.close();}
        task.report.put("resources", resources);
        record("income", allValid ? "verified" : "partial", allValid ? "三项资源 OCR 已通过双帧校验" : "部分资源无法确认，已保留原文，不以零代替");
    }
    private static Long detectedValue(java.util.List<TextDetector.Box> boxes,long maximum){
        java.util.List<String> text=new java.util.ArrayList<>();java.util.List<Double> confidence=new java.util.ArrayList<>();
        for(TextDetector.Box box:boxes){text.add(box.text.text);confidence.add(box.text.confidence);}
        return DetectedNumber.parse(text,confidence,maximum);
    }
    private static String detectedText(java.util.List<TextDetector.Box> boxes){
        StringBuilder result=new StringBuilder();for(TextDetector.Box box:boxes)result.append(box.text).append(' ');return result.toString().trim();
    }
}

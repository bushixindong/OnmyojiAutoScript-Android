package com.oas.android;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.TextView;
import com.oas.android.vision.ScreenRecognizer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Runs OpenCV and the exported real OAS templates on the actual Android runtime. */
public final class RecognitionTestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); TextView view = new TextView(this); view.setText("Running OAS recognition replay…"); setContentView(view);
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            try (ScreenRecognizer recognizer = new ScreenRecognizer(this)) {
                JSONArray rules;
                try (InputStream input = getAssets().open("oas/catalog.json")) { rules = new JSONObject(MainActivity.readText(input)).getJSONArray("rules"); }
                for (int i = 0; i < rules.length(); i++) {
                    JSONObject rule = rules.getJSONObject(i); JSONArray roi = rule.getJSONArray("roi");
                    Bitmap frame = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888); frame.eraseColor(Color.rgb(35, 45, 55));
                    try {
                        try (InputStream input = getAssets().open("oas/" + rule.getString("file"))) {
                            Bitmap template = BitmapFactory.decodeStream(input);
                            new Canvas(frame).drawBitmap(template, roi.getInt(0), roi.getInt(1), null); template.recycle();
                        }
                        List<ScreenRecognizer.Match> matches = recognizer.recognize(frame, java.util.Collections.singleton(rule.getString("id")));
                        ScreenRecognizer.Match target = null;
                        for (ScreenRecognizer.Match match : matches) if (match.id.equals(rule.getString("id"))) target = match;
                        if (target == null || !target.matched() || target.score < .999) throw new AssertionError("Positive replay failed: " + rule.getString("id") + " " + target);
                        report.append("PASS ").append(target.id).append(" score=").append(target.score).append('\n');
                    } finally { frame.recycle(); }
                }
                Bitmap blank = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888); blank.eraseColor(Color.BLACK);
                try { for (ScreenRecognizer.Match match : recognizer.recognize(blank)) if (match.matched()) throw new AssertionError("Blank frame false positive: " + match); }
                finally { blank.recycle(); }
                report.append("PASS blank frame rejected by all rules\nALL PASS\n");
            } catch (Throwable error) { report.append("FAIL ").append(android.util.Log.getStackTraceString(error)); }
            try (FileOutputStream out = openFileOutput("recognition-test.txt", 0)) { out.write(report.toString().getBytes(StandardCharsets.UTF_8)); }
            catch (Exception e) { report.append("FAIL writing report: ").append(e); }
            android.util.Log.i("OAS_TEST", report.toString());
            runOnUiThread(() -> view.setText(report));
        }, "oas-recognition-test").start();
    }
}

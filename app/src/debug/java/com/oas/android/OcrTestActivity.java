package com.oas.android;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.TextView;
import com.oas.android.vision.LineOcr;
import com.oas.android.vision.TextDetector;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic fixtures compared against the original desktop OAS recognizer, no game interaction. */
public final class OcrTestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); TextView view = new TextView(this); view.setText("Running native OCR replay…"); setContentView(view);
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            try (LineOcr ocr = new LineOcr(this)) {
                JSONArray fixtures;
                try (java.io.InputStream input = getAssets().open("ocr-replay/expected.json")) { fixtures = new JSONArray(MainActivity.readText(input)); }
                for (int i = 0; i < fixtures.length(); i++) {
                    JSONObject fixture = fixtures.getJSONObject(i);
                    try (java.io.InputStream input = getAssets().open("ocr-replay/" + fixture.getString("file"))) {
                        Bitmap bitmap = BitmapFactory.decodeStream(input);
                        try {
                            LineOcr.Text result = ocr.readLine(bitmap);
                            if (!result.text.equals(fixture.getString("expected")) || Math.abs(result.confidence-fixture.getDouble("confidence")) > .02)
                                throw new AssertionError("Desktop/Android OCR mismatch: " + fixture + " -> " + result);
                            report.append("PASS ").append(result).append('\n');
                        } finally { bitmap.recycle(); }
                    }
                }
                try(TextDetector detector=new TextDetector(this)){
                    JSONArray detection;
                    try(java.io.InputStream input=getAssets().open("ocr-replay/det-expected.json")){detection=new JSONArray(MainActivity.readText(input));}
                    for(int i=0;i<detection.length();i++){
                        JSONObject fixture=detection.getJSONObject(i);JSONArray expected=fixture.getJSONArray("boxes");
                        Bitmap image;
                        try(java.io.InputStream input=getAssets().open("ocr-replay/"+fixture.getString("file"))){image=BitmapFactory.decodeStream(input);}
                        try{
                            java.util.List<TextDetector.Box> actual=detector.read(image);
                            if(actual.size()!=expected.length())throw new AssertionError("Detector count differs: "+fixture.getString("file")+" expected="+expected.length()+" actual="+actual.size());
                            for(int j=0;j<expected.length();j++){
                                JSONObject box=expected.getJSONObject(j);TextDetector.Box match=null;
                                for(TextDetector.Box candidate:actual)if(candidate.text.text.equals(box.getString("text")))match=candidate;
                                if(match==null)throw new AssertionError("Missing detection text: "+box);
                                JSONArray corners=box.getJSONArray("corners");
                                int x=(corners.getJSONArray(0).getInt(0)+corners.getJSONArray(2).getInt(0))/2;
                                int y=(corners.getJSONArray(0).getInt(1)+corners.getJSONArray(2).getInt(1))/2;
                                if(Math.abs(match.centerX()-x)>5||Math.abs(match.centerY()-y)>5)throw new AssertionError("Detection coordinate mismatch: "+box);
                                report.append("PASS detector ").append(match.text).append(" center=").append(match.centerX()).append(',').append(match.centerY()).append('\n');
                            }
                        }finally{image.recycle();}
                    }
                    Bitmap blank=Bitmap.createBitmap(720,400,Bitmap.Config.ARGB_8888);blank.eraseColor(android.graphics.Color.BLACK);
                    try{if(!detector.read(blank).isEmpty())throw new AssertionError("Detector false positive on black frame");}
                    finally{blank.recycle();}
                    report.append("PASS detector blank frame\n");
                }
                report.append("ALL PASS\n");
            } catch (Throwable error) { report.append("FAIL ").append(android.util.Log.getStackTraceString(error)); }
            try (java.io.FileOutputStream output = openFileOutput("ocr-test.txt", 0)) {
                output.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception error) { report.append(error); }
            runOnUiThread(() -> view.setText(report));
        }, "oas-ocr-replay").start();
    }
}

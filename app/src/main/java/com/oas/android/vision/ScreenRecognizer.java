package com.oas.android.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Same RGB TM_CCOEFF_NORMED, ROI and thresholds as the exported OAS RuleImage assets. */
public final class ScreenRecognizer implements AutoCloseable {
    public static final class Match {
        public final String id, label;
        public final double score, threshold;
        public final int x, y;
        public Match(String id, String label, double score, double threshold, int x, int y) {
            this.id = id; this.label = label; this.score = score; this.threshold = threshold;
            this.x = x; this.y = y;
        }
        public boolean matched() { return score >= threshold; }
        @Override public String toString() { return String.format(Locale.ROOT, "%s  %.3f / %.2f%s", label, score, threshold, matched() ? " ✓" : ""); }
    }
    private static final class Rule {
        String id, label;
        Rect roi;
        double threshold;
        Mat template;
    }
    private final List<Rule> rules = new ArrayList<>();

    public ScreenRecognizer(Context context) throws Exception {
        if (!OpenCVLoader.initLocal()) throw new IllegalStateException("无法加载 OpenCV");
        try (InputStream input = context.getAssets().open("oas/catalog.json")) {
            JSONObject root = new JSONObject(com.oas.android.MainActivity.readText(input));
            JSONArray entries = root.getJSONArray("rules");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject data = entries.getJSONObject(i);
                Rule rule = new Rule(); rule.id = data.getString("id"); rule.label = data.getString("label");
                rule.threshold = data.getDouble("threshold");
                JSONArray roi = data.getJSONArray("roi");
                rule.roi = new Rect(roi.getInt(0), roi.getInt(1), roi.getInt(2), roi.getInt(3));
                try (InputStream image = context.getAssets().open("oas/" + data.getString("file"))) {
                    Bitmap bitmap = BitmapFactory.decodeStream(image);
                    if (bitmap == null) throw new IllegalStateException("模板损坏：" + rule.id);
                    Mat rgba = new Mat();
                    try { Utils.bitmapToMat(bitmap, rgba); rule.template = new Mat(); Imgproc.cvtColor(rgba, rule.template, Imgproc.COLOR_RGBA2RGB); }
                    finally { rgba.release(); bitmap.recycle(); }
                }
                rules.add(rule);
            }
        } catch (Exception e) { close(); throw e; }
    }

    public synchronized List<Match> recognize(Bitmap bitmap) {
        return recognize(bitmap, null);
    }

    public synchronized List<Match> recognize(Bitmap bitmap, java.util.Collection<String> ids) {
        if (bitmap.getWidth() != 1280 || bitmap.getHeight() != 720) throw new IllegalArgumentException("识别仅接受 1280×720 原图");
        Mat rgba = new Mat(), rgb = new Mat();
        try {
            Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            List<Match> matches = new ArrayList<>();
            for (Rule rule : rules) {
                if (ids != null && !ids.contains(rule.id)) continue;
                Mat crop = new Mat(rgb, rule.roi), result = new Mat();
                try {
                    Imgproc.matchTemplate(crop, rule.template, result, Imgproc.TM_CCOEFF_NORMED);
                    Core.MinMaxLocResult peak = Core.minMaxLoc(result);
                    double score = peak.maxVal;
                    matches.add(new Match(rule.id, rule.label, Double.isFinite(score) ? score : -1, rule.threshold,
                        rule.roi.x + (int) peak.maxLoc.x + rule.template.cols() / 2,
                        rule.roi.y + (int) peak.maxLoc.y + rule.template.rows() / 2));
                } finally { crop.release(); result.release(); }
            }
            return matches;
        } finally { rgba.release(); rgb.release(); }
    }

    @Override public synchronized void close() { for (Rule rule : rules) rule.template.release(); rules.clear(); }
}

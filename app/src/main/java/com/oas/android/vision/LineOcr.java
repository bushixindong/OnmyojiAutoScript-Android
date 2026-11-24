package com.oas.android.vision;

import android.content.Context;
import android.graphics.Bitmap;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** Fixed-ROI single-line PaddleOCR recognizer; no text detection or RPC server required. */
public final class LineOcr implements AutoCloseable {
    public static final class Text {
        public final String text; public final double confidence;
        Text(String text, double confidence) { this.text = text; this.confidence = confidence; }
        @Override public String toString() { return text + " (" + String.format(java.util.Locale.ROOT, "%.3f", confidence) + ")"; }
    }
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final List<String> dictionary = new ArrayList<>();
    private final JSONArray rules;
    public LineOcr(Context context) throws Exception {
        if (!org.opencv.android.OpenCVLoader.initLocal()) throw new IllegalStateException("无法加载 OpenCV");
        try (InputStream input = context.getAssets().open("oas/catalog.json")) {
            rules = new JSONObject(com.oas.android.MainActivity.readText(input)).getJSONArray("ocr");
        }
        dictionary.add("");
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                context.getAssets().open("oas/models/ppocr_keys_v1.txt"), java.nio.charset.StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) dictionary.add(line);
        }
        dictionary.add(" ");
        try (InputStream input = context.getAssets().open("oas/models/ch_PP-OCRv2_rec_infer.onnx");
             OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384]; int n;
            while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
            session = environment.createSession(bytes.toByteArray(), options);
        }
    }
    public Text read(Bitmap frame, String id) throws Exception {
        if (frame.getWidth() != 1280 || frame.getHeight() != 720) throw new IllegalArgumentException("OCR 需要 1280×720 原图");
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            if (!id.equals(rule.getString("id"))) continue;
            JSONArray r = rule.getJSONArray("roi");
            Bitmap crop = Bitmap.createBitmap(frame, r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3));
            try { return readLine(crop); } finally { crop.recycle(); }
        }
        throw new IllegalArgumentException("Unknown OCR rule: " + id);
    }
    public synchronized Text readLine(Bitmap bitmap) throws Exception {
        int width = Math.max(1, (int) (32.0 * bitmap.getWidth() / bitmap.getHeight()));
        Mat rgba = new Mat(), rgb = new Mat(), resized = new Mat();
        try {
            Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            Imgproc.resize(rgb, resized, new Size(width, 32), 0, 0, Imgproc.INTER_LINEAR);
            byte[] pixels = new byte[width * 32 * 3]; resized.get(0, 0, pixels);
            float[] chw = new float[pixels.length]; int plane = width * 32;
            for (int p = 0; p < plane; p++) for (int c = 0; c < 3; c++)
                chw[c * plane + p] = ((pixels[p * 3 + c] & 255) / 255f - .5f) / .5f;
            try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(chw), new long[]{1,3,32,width});
                 OrtSession.Result output = session.run(java.util.Collections.singletonMap("x", tensor))) {
                float[][] steps = ((float[][][]) output.get(0).getValue())[0];
                StringBuilder text = new StringBuilder(); double confidence = 0; int count = 0, previous = -1;
                for (float[] step : steps) {
                    if (step.length != dictionary.size()) throw new IllegalStateException("OCR 字典与模型不匹配");
                    int best = 0; for (int j = 1; j < step.length; j++) if (step[j] > step[best]) best = j;
                    if (best != 0 && best != previous) { text.append(dictionary.get(best)); confidence += step[best]; count++; }
                    previous = best;
                }
                return new Text(text.toString(), count == 0 ? 0 : confidence / count);
            }
        } finally { rgba.release(); rgb.release(); resized.release(); }
    }
    @Override public void close() throws ai.onnxruntime.OrtException { session.close(); }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from PaddleOCR / ppocr-onnx DB preprocessing and rectangular postprocessing (Apache-2.0).
package com.oas.android.vision;

import android.content.Context;
import android.graphics.Bitmap;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/** DB text detection + existing line recognizer, CPU only. No RPC or desktop dependency. */
public final class TextDetector implements AutoCloseable {
    public static final class Box {
        public final Point[] corners;
        public final LineOcr.Text text;
        Box(Point[] corners, LineOcr.Text text) { this.corners = corners; this.text = text; }
        public int centerX() { return (int) ((corners[0].x + corners[2].x)/2); }
        public int centerY() { return (int) ((corners[0].y + corners[2].y)/2); }
    }
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final LineOcr ocr;
    public TextDetector(Context context) throws Exception {
        ocr = new LineOcr(context);
        try (java.io.InputStream input = context.getAssets().open("oas/models/ch_PP-OCRv3_det_infer.onnx");
             OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384]; int n;
            while ((n = input.read(buffer)) != -1) bytes.write(buffer,0,n);
            session = environment.createSession(bytes.toByteArray(), options);
        } catch (Exception error) { ocr.close(); throw error; }
    }
    public synchronized List<Box> read(Bitmap image) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        // Small list glyphs need upscaling for DB to separate rows. Bound large/long ROIs.
        double ratio = Math.min(1920.0 / Math.max(image.getWidth(), image.getHeight()),
            Math.max(1,320.0/Math.min(image.getWidth(),image.getHeight())));
        int width = Math.max(32, (int) Math.rint(image.getWidth()*ratio/32)*32);
        int height = Math.max(32, (int) Math.rint(image.getHeight()*ratio/32)*32);
        Mat rgba = new Mat(), rgb = new Mat(), resized = new Mat();
        try {
            Utils.bitmapToMat(image, rgba); Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB);
            Imgproc.resize(rgb,resized,new Size(width,height),0,0,Imgproc.INTER_LINEAR);
            byte[] pixels = new byte[width*height*3]; resized.get(0,0,pixels);
            float[] chw = new float[pixels.length], mean = {.485f,.456f,.406f}, std = {.229f,.224f,.225f};
            int plane = width*height;
            for(int p=0;p<plane;p++) for(int c=0;c<3;c++) chw[c*plane+p] = ((pixels[3*p+c]&255)/255f-mean[c])/std[c];
            try(OnnxTensor tensor=OnnxTensor.createTensor(environment,FloatBuffer.wrap(chw),new long[]{1,3,height,width});
                OrtSession.Result result=session.run(java.util.Collections.singletonMap(session.getInputNames().iterator().next(),tensor))) {
                float[][] map=((float[][][][])result.get(0).getValue())[0][0];
                return decode(image, rgba, map);
            }
        } finally { rgba.release();rgb.release();resized.release(); }
    }
    public List<Box> read(Bitmap frame,String ruleId,Context context)throws Exception{
        Roi roi=findRoi(context,ruleId);
        Bitmap crop=Bitmap.createBitmap(frame,roi.x,roi.y,roi.width,roi.height);
        try{return read(crop);}finally{crop.recycle();}
    }
    private static final class Roi {int x,y,width,height;}
    private static Roi findRoi(Context context,String id)throws Exception{
        org.json.JSONArray rules;
        try(java.io.InputStream input=context.getAssets().open("oas/catalog.json")){rules=new org.json.JSONObject(com.oas.android.MainActivity.readText(input)).getJSONArray("ocr");}
        for(int i=0;i<rules.length();i++){
            org.json.JSONObject rule=rules.getJSONObject(i);if(!rule.getString("id").equals(id))continue;
            org.json.JSONArray roi=rule.getJSONArray("roi");Roi result=new Roi();
            result.x=roi.getInt(0);result.y=roi.getInt(1);result.width=roi.getInt(2);result.height=roi.getInt(3);return result;
        }
        throw new IllegalArgumentException("OCR rule not found: "+id);
    }
    private List<Box> decode(Bitmap image, Mat original, float[][] map) throws Exception {
        int height=map.length,width=map[0].length;
        Mat probability=new Mat(height,width,CvType.CV_32FC1), binary=new Mat(height,width,CvType.CV_8UC1), hierarchy=new Mat();
        Mat kernel=Mat.ones(2,2,CvType.CV_8U);
        List<MatOfPoint> contours=new ArrayList<>();
        try {
            float[] values=new float[width*height]; byte[] mask=new byte[values.length];
            for(int y=0;y<height;y++) for(int x=0;x<width;x++) { int p=y*width+x; values[p]=map[y][x];mask[p]=(byte)(values[p]>.3?255:0); }
            probability.put(0,0,values);binary.put(0,0,mask);Imgproc.dilate(binary,binary,kernel);
            Imgproc.findContours(binary,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE);
            List<Box> boxes=new ArrayList<>();
            for(int i=0;i<Math.min(1000,contours.size());i++) {
                if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
                MatOfPoint2f contour=new MatOfPoint2f(contours.get(i).toArray());
                RotatedRect rect;
                try { rect=Imgproc.minAreaRect(contour); } finally { contour.release(); }
                if(Math.min(rect.size.width,rect.size.height)<3) continue;
                Point[] points=new Point[4];rect.points(points);
                Mat scoreMask=Mat.zeros(height,width,CvType.CV_8U);MatOfPoint polygon=new MatOfPoint(points);
                double score;
                try { Imgproc.fillPoly(scoreMask,java.util.Collections.singletonList(polygon),new Scalar(1));score=Core.mean(probability,scoreMask).val[0]; }
                finally { scoreMask.release();polygon.release(); }
                if(score<.6) continue;
                // Min-area bounding rectangle of an outward round buffer has this exact expansion.
                double distance=rect.size.area()*1.6/(2*(rect.size.width+rect.size.height));
                rect.size.width+=2*distance;rect.size.height+=2*distance;
                if(Math.min(rect.size.width,rect.size.height)<5) continue;
                rect.points(points);points=ordered(points);
                for(Point point:points) { point.x=Math.max(0,Math.min(image.getWidth()-1,Math.rint(point.x/width*image.getWidth())));point.y=Math.max(0,Math.min(image.getHeight()-1,Math.rint(point.y/height*image.getHeight()))); }
                int cropWidth=(int)Math.max(length(points[0],points[1]),length(points[2],points[3]));
                int cropHeight=(int)Math.max(length(points[0],points[3]),length(points[1],points[2]));
                if(cropWidth<=3||cropHeight<=3) continue;
                MatOfPoint2f src=new MatOfPoint2f(points),dst=new MatOfPoint2f(new Point(0,0),new Point(cropWidth,0),new Point(cropWidth,cropHeight),new Point(0,cropHeight));
                Mat transform=Imgproc.getPerspectiveTransform(src,dst),crop=new Mat();
                try {
                    Imgproc.warpPerspective(original,crop,transform,new Size(cropWidth,cropHeight),Imgproc.INTER_CUBIC,Core.BORDER_REPLICATE,new Scalar(0));
                    if(cropHeight/(double)cropWidth>=1.5) Core.rotate(crop,crop,Core.ROTATE_90_COUNTERCLOCKWISE);
                    Bitmap bitmap=Bitmap.createBitmap(crop.cols(),crop.rows(),Bitmap.Config.ARGB_8888);Utils.matToBitmap(crop,bitmap);
                    LineOcr.Text text;
                    try { text=ocr.readLine(bitmap); } finally { bitmap.recycle(); }
                    if(!text.text.trim().isEmpty()&&text.confidence>=.5) boxes.add(new Box(points,text));
                } finally { src.release();dst.release();transform.release();crop.release(); }
            }
            boxes.sort(Comparator.comparingInt(Box::centerY).thenComparingInt(Box::centerX));return boxes;
        } finally { probability.release();binary.release();hierarchy.release();kernel.release();for(MatOfPoint contour:contours)contour.release(); }
    }
    private static double length(Point a,Point b){return Math.hypot(a.x-b.x,a.y-b.y);}
    private static Point[] ordered(Point[] points){
        java.util.Arrays.sort(points,Comparator.comparingDouble(p->p.x));
        Point tl=points[0].y<points[1].y?points[0]:points[1],bl=points[0].y<points[1].y?points[1]:points[0];
        Point tr=points[2].y<points[3].y?points[2]:points[3],br=points[2].y<points[3].y?points[3]:points[2];
        return new Point[]{tl,tr,br,bl};
    }
    @Override public void close() throws ai.onnxruntime.OrtException { try { session.close(); } finally { ocr.close(); } }
}

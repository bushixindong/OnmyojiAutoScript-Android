package com.oas.android.tasks;

import android.graphics.Bitmap;
import com.oas.android.vision.TextDetector;
import com.oas.android.vision.ScreenRecognizer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/** Exact text selection in exported RuleList ROI, with a second-frame confirmation. */
public final class ListSelector {
    private final TaskRuntime task;
    private final TextDetector detector;
    public ListSelector(TaskRuntime task,TextDetector detector){this.task=task;this.detector=detector;}
    public void select(String listId,String target,String pageMarker)throws Exception{
        JSONArray lists;
        try(java.io.InputStream input=task.context.getAssets().open("oas/catalog.json")){
            lists=new JSONObject(com.oas.android.MainActivity.readText(input)).getJSONArray("lists");
        }
        JSONObject rule=null;
        for(int i=0;i<lists.length();i++)if(lists.getJSONObject(i).getString("id").equals(listId))rule=lists.getJSONObject(i);
        if(rule==null)throw new IllegalArgumentException("列表不存在："+listId);
        JSONArray items=rule.getJSONArray("items"),roi=rule.getJSONArray("roi"),size=rule.getJSONArray("size");
        int index=index(items,target);if(index<0)throw new IllegalArgumentException("不支持的列表选项："+target);
        for(int attempt=0;attempt<10;attempt++){
            task.require(pageMarker);List<TextDetector.Box> boxes=read(roi);
            TextDetector.Box match=find(boxes,target);
            if(match!=null){
                task.pause(250);TextDetector.Box confirmation=find(read(roi),target);
                if(confirmation!=null&&Math.abs(match.centerY()-confirmation.centerY())<12){
                    task.require(pageMarker);
                    task.click(new ScreenRecognizer.Match(listId,target,1,1,roi.getInt(0)+confirmation.centerX(),roi.getInt(1)+confirmation.centerY()));return;
                }
                continue;
            }
            int lowest=Integer.MAX_VALUE,highest=-1;
            for(TextDetector.Box box:boxes)if(box.text.confidence>=.7){int found=index(items,box.text.text.trim());if(found>=0){lowest=Math.min(lowest,found);highest=Math.max(highest,found);}}
            if(highest<0)throw new IllegalStateException("无法识别列表项，已停止；目标："+target);
            if(index>=lowest&&index<=highest)throw new IllegalStateException("列表中间选项未识别，已停止；目标："+target);
            boolean forward=index>highest;
            int x=roi.getInt(0)+roi.getInt(2)/2,y=roi.getInt(1)+roi.getInt(3)/2;
            int distance=Math.min(size.getInt(1)*2,roi.getInt(3)-30);
            task.require(pageMarker);task.swipe(x,y+(forward?distance/2:-distance/2),x,y+(forward?-distance/2:distance/2),400);
        }
        throw new IllegalStateException("列表滚动次数达到上限："+target);
    }
    private List<TextDetector.Box> read(JSONArray roi)throws Exception{
        task.check();Bitmap frame=task.session.screenshot();Bitmap crop=null;
        try{crop=Bitmap.createBitmap(frame,roi.getInt(0),roi.getInt(1),roi.getInt(2),roi.getInt(3));return detector.read(crop);}
        finally{if(crop!=null)crop.recycle();frame.recycle();}
    }
    private static TextDetector.Box find(List<TextDetector.Box> boxes,String target){
        for(TextDetector.Box box:boxes)if(box.text.confidence>=.8&&box.text.text.trim().equals(target))return box;return null;
    }
    private static int index(JSONArray items,String target)throws Exception{for(int i=0;i<items.length();i++)if(items.getString(i).equals(target))return i;return -1;}
}

package com.oas.android.tasks;

import com.oas.android.vision.ScreenRecognizer;
import java.util.*;
import android.os.SystemClock;

/** OAS page edges for the currently migrated areas. Unknown pages stop without a touch. */
public final class Navigator {
    private static final LinkedHashMap<String,String[]> PAGES=new LinkedHashMap<>();
    static {
        PAGES.put("orochi",new String[]{"I_OROCHI_CHECK_10","I_OROCHI_CHECK_11","I_OROCHI_CHECK_12","I_OROCHI_CHECK_13"});
        PAGES.put("awake_detail",new String[]{"I_EVOZONE_FIRE"});
        PAGES.put("goryou_detail",new String[]{"I_GR_FIRE"});
        PAGES.put("sougenbi",new String[]{"I_S_CHECK_SOUGENBI"});
        PAGES.put("affairs",new String[]{"I_CHECK_COURTYARD_AFFAIRS"});
        PAGES.put("mail",new String[]{"I_HARVEST_MAIL_TITLE"});
        PAGES.put("gift",new String[]{"I_GIFT_RECOMMEND"});
        PAGES.put("mall_recommend",new String[]{"I_CHECK_MALL_RECOMMEND"});
        PAGES.put("mall",new String[]{"I_CHECK_MALL"});
        PAGES.put("activity",new String[]{"I_CHECK_ACT_LIST"});
        PAGES.put("souls",new String[]{"I_CHECK_SOUL_ZONES"});
        PAGES.put("awake",new String[]{"I_CHECK_AWAKE"});
        PAGES.put("goryou",new String[]{"I_CHECK_GORYOU"});
        PAGES.put("exploration",new String[]{"I_CHECK_EXPLORATION"});
        PAGES.put("main",new String[]{"I_CHECK_MAIN"});
    }
    private final TaskRuntime task;
    private final PageGraph graph=new PageGraph();
    public Navigator(TaskRuntime task){
        this.task=task;
        graph.connect("main","exploration","I_MAIN_GOTO_EXPLORATION")
            .connect("exploration","main","I_UI_BACK_YELLOW")
            .connect("exploration","souls","I_EXPLORATION_GOTO_SOUL_ZONE")
            .connect("souls","exploration","I_UI_BACK_YELLOW")
            .connect("exploration","awake","I_EXPLORATION_GOTO_AWAKE_ZONE")
            .connect("awake","exploration","I_UI_BACK_YELLOW")
            .connect("exploration","goryou","I_EXPLORATION_GOTO_GORYOU_REALM")
            .connect("goryou","exploration","I_UI_BACK_YELLOW")
            .connect("souls","orochi","I_OROCHI").connect("orochi","exploration","I_UI_BACK_YELLOW")
            .connect("souls","sougenbi","I_S_SOUGENBI").connect("sougenbi","souls","I_UI_BACK_YELLOW")
            .connect("awake_detail","awake","I_UI_BACK_YELLOW").connect("goryou_detail","goryou","I_UI_BACK_YELLOW")
            .connect("main","activity","I_ACT_LIST_EXPAND").connect("activity","main","I_UI_BACK_YELLOW")
            .connect("affairs","main","I_UI_BACK_YELLOW").connect("mail","main","I_UI_BACK_RED")
            .connect("gift","mall","I_UI_BACK_YELLOW").connect("mall_recommend","mall","I_UI_BACK_YELLOW")
            .connect("mall","main","I_UI_BACK_YELLOW");
    }
    public String page()throws Exception{
        List<String> ids=new ArrayList<>();for(String[] values:PAGES.values())Collections.addAll(ids,values);
        Map<String,ScreenRecognizer.Match> hits=task.scan(ids.toArray(new String[0]));
        for(Map.Entry<String,String[]> page:PAGES.entrySet())for(String id:page.getValue())if(hits.containsKey(id))return page.getKey();
        return null;
    }
    public String stablePage(long timeout)throws Exception{
        long until=SystemClock.elapsedRealtime()+timeout;String last=null;int confirmations=0;
        do {
            String current=page();confirmations=current!=null&&current.equals(last)?confirmations+1:current==null?0:1;last=current;
            if(confirmations>=2)return current;task.pause(250);
        }while(SystemClock.elapsedRealtime()<until);
        throw new IllegalStateException("无法确认当前页面，已停止导航");
    }
    public void go(String destination)throws Exception{
        for(int count=0;count<12;count++){
            String current=stablePage(6000);if(current.equals(destination)){task.log("已到达："+destination);return;}
            PageGraph.Edge edge=graph.route(current,destination).get(0);
            task.log("导航："+current+" → "+edge.to);task.click(edge.click);
            long until=SystemClock.elapsedRealtime()+8000;boolean changed=false;
            do {String next=page();if(next!=null&&!next.equals(current)){changed=true;break;}task.pause(300);}while(SystemClock.elapsedRealtime()<until);
            if(!changed)throw new IllegalStateException("页面未切换："+current+" → "+edge.to);
        }
        throw new IllegalStateException("导航达到转换次数上限");
    }
}

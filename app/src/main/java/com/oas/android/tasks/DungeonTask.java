package com.oas.android.tasks;

import android.graphics.Bitmap;
import android.os.SystemClock;
import com.oas.android.vision.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Map;

/** Bounded solo ports. Group invitations, soul switching and buffs are not part of these variants. */
public final class DungeonTask {
    private final TaskRuntime task;
    private final DungeonConfig config;
    private final Navigator navigator;
    private String fire,lock,unlock,ticket;
    public DungeonTask(TaskRuntime task,DungeonConfig config){this.task=task;this.config=config;this.navigator=new Navigator(task);}
    public String run()throws Exception{
        task.touchLimit(Math.min(2000,200+15*config.rounds));
        task.report.put("dungeon",new JSONObject().put("kind",config.kind).put("variant",config.variant).put("layer",config.layer)
            .put("roundLimit",config.rounds).put("minutes",config.minutes).put("inspectionOnly",config.inspect));
        prepare();
        if(config.inspect){task.capture("last-inspection-dungeon.png");navigator.go("main");return "副本入口、类型及层数检查结束，未挑战或消耗资源";}
        setLock();
        long until=SystemClock.elapsedRealtime()+config.minutes*60000L;
        int completed=0,wins=0,losses=0;String reason="达到场数上限";
        JSONArray rounds=new JSONArray();task.report.put("battleRounds",rounds);
        try(LineOcr ocr=ticket==null?null:new LineOcr(task.context)){
            while(completed<config.rounds){
                task.check();if(SystemClock.elapsedRealtime()>=until){reason="达到时限，未发起下一场";break;}
                task.require(fire);
                if(ocr!=null&&!hasTicket(ocr)){reason="票数不足，未发起下一场";break;}
                task.log("单人副本第 "+(completed+1)+" / "+config.rounds+" 场");
                task.click(fire);
                if(!leftLobby())throw new IllegalStateException("挑战后页面未变化；可能资源不足，停止且不重复点击");
                BattleState state=BattleTask.runRound(task,fire);
                completed++;wins+=state.wins;losses+=state.losses;
                rounds.put(new JSONObject().put("round",completed).put("outcome",state.outcome.name()));
                task.report.put("rounds",completed).put("wins",wins).put("losses",losses).put("unknownOutcomes",completed-wins-losses);
                task.require(fire);
                if(config.stopOnLoss&&state.outcome!=BattleState.Outcome.WIN){reason="失败或胜负未确认，按设置停止";break;}
            }
        }
        task.report.put("stopReason",reason);navigator.go("main");
        return "单人副本结束："+completed+" 场，胜 "+wins+"、负 "+losses+"、未知 "+(completed-wins-losses)+"；"+reason;
    }
    private void prepare()throws Exception{
        switch(config.kind){
            case "orochi":
                navigator.go("orochi");fire="I_OROCHI_FIRE";lock="I_OROCHI_LOCK";unlock="I_OROCHI_UNLOCK";
                try(TextDetector detector=new TextDetector(task.context)){
                    String label=new String[]{"拾","悲","神","虚"}[config.layer-10];
                    new ListSelector(task,detector).select("Orochi.L_LAYER_LIST",label,fire);
                }
                task.require("I_OROCHI_CHECK_"+config.layer);break;
            case "evozone":
                navigator.go("awake");task.click(new String[]{"I_FIRE_KIRIN","I_WIND_KIRIN","I_WATER_KIRIN","I_LIGHTNING_KIRIN"}[config.variant]);
                fire="I_EVOZONE_FIRE";lock="I_EVOZONE_LOCK";unlock="I_EVOZONE_UNLOCK";task.require(fire);
                try(TextDetector detector=new TextDetector(task.context)){
                    new ListSelector(task,detector).select("EvoZone.L_LAYER_LIST",new String[]{"壹","贰","叁","肆","伍","陆","柒","捌","玖","拾"}[config.layer-1],fire);
                }
                break;
            case "goryou":
                navigator.go("goryou");task.region("C_GR_C_"+(config.variant+1));
                fire="I_GR_FIRE";lock="I_GR_LOCK";unlock="I_GR_UNLOCK";ticket="O_GR_TICKET";task.require(fire);break;
            case "sougenbi":
                navigator.go("sougenbi");fire="I_S_FIRE";lock="I_S_TEAM_LOCK";unlock="I_S_TEAM_UNLOCK";
                String suffix=new String[]{"GREED","ANGER","FOOLERY"}[config.variant];
                task.region("C_C_"+suffix);task.require("I_S_FIRE_"+suffix);ticket="O_S_"+suffix;break;
            default:throw new IllegalArgumentException("未知副本");
        }
        task.require(fire);
    }
    private void setLock()throws Exception{
        for(int attempt=0;attempt<3;attempt++){
            task.require(fire);Map<String,ScreenRecognizer.Match> hits=task.scan(lock,unlock);
            ScreenRecognizer.Match locked=hits.get(lock),unlocked=hits.get(unlock);
            if(locked==null&&unlocked==null)throw new IllegalStateException("无法确认阵容锁定状态");
            if(locked!=null&&unlocked!=null&&Math.abs(locked.score-unlocked.score)<.05)throw new IllegalStateException("阵容锁定图标识别冲突，已停止");
            boolean isLocked=locked!=null&&(unlocked==null||locked.score>unlocked.score);
            if(isLocked==config.lock)return;
            task.click(isLocked?locked:unlocked);
        }
        throw new IllegalStateException("阵容锁定状态未切换");
    }
    private boolean leftLobby()throws Exception{
        long until=SystemClock.elapsedRealtime()+10000;
        do{if(!task.scan(fire).containsKey(fire))return true;task.pause(300);}while(SystemClock.elapsedRealtime()<until);return false;
    }
    private boolean hasTicket(LineOcr ocr)throws Exception{
        Long previous=null;
        for(int attempt=0;attempt<2;attempt++){
            task.check();Bitmap frame=task.session.screenshot();LineOcr.Text value;
            try{value=ocr.read(frame,ticket);}finally{frame.recycle();}
            Long parsed=ResourceNumber.parse(value.text,1000000);
            if(parsed==null||value.confidence<.85)throw new IllegalStateException("票数无法确认："+value);
            if(attempt==1&&!parsed.equals(previous))throw new IllegalStateException("票数双帧不一致，停止");
            previous=parsed;task.pause(200);
        }
        return previous!=null&&previous>0;
    }
}

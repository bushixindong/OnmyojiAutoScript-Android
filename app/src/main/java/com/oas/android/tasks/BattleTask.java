package com.oas.android.tasks;

import android.os.SystemClock;
import com.oas.android.vision.ScreenRecognizer;
import java.util.Map;

/** Helps an already-started battle; does not enter a dungeon or start another challenge. */
public final class BattleTask {
    private BattleTask() { }
    public static String run(TaskRuntime task, int rounds, boolean prepare) throws Exception {
        if(rounds<1||rounds>100)throw new IllegalArgumentException("场次需在 1–100 之间");
        task.touchLimit(Math.min(2000,20+15*rounds));
        BattleState state=runState(task,rounds,prepare,null);
        return "已收尾 " + state.completed + " 场：胜 " + state.wins + "、负 " + state.losses + "、胜负未知 " + (state.completed-state.wins-state.losses);
    }
    public static BattleState runRound(TaskRuntime task,String exitMarker)throws Exception{
        return runState(task,1,true,exitMarker);
    }
    private static BattleState runState(TaskRuntime task,int rounds,boolean prepare,String exitMarker)throws Exception{
        BattleState state = new BattleState(rounds, prepare);
        long deadline=SystemClock.elapsedRealtime()+600000;
        task.log("战斗辅助：等待已有战斗，目标 " + rounds + " 场");
        while (true) {
            task.check();
            if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("战斗辅助等待超过 10 分钟，已停止");
            Map<String, ScreenRecognizer.Match> hits = task.scan("I_OVER_GHOST", "I_FALSE", "I_WIN", "I_DE_WIN",
                "I_REWARD", "I_REWARD_GOLD", "I_GB_SKIN_CONFIRM", "I_BATTLE_INFO", "I_PREPARE_HIGHLIGHT");
            BattleState.Page page = hits.containsKey("I_OVER_GHOST") ? BattleState.Page.OVERFLOW :
                hits.containsKey("I_FALSE") ? BattleState.Page.LOSS :
                hits.containsKey("I_WIN") || hits.containsKey("I_DE_WIN") ? BattleState.Page.WIN :
                hits.containsKey("I_REWARD") || hits.containsKey("I_REWARD_GOLD") || hits.containsKey("I_GB_SKIN_CONFIRM") ? BattleState.Page.REWARD :
                hits.containsKey("I_BATTLE_INFO") ? BattleState.Page.BATTLE :
                hits.containsKey("I_PREPARE_HIGHLIGHT") ? BattleState.Page.PREPARE : BattleState.Page.UNKNOWN;
            BattleState.Action action = state.step(page, SystemClock.elapsedRealtime(), false);
            task.report.put("rounds", state.completed).put("wins", state.wins).put("losses", state.losses)
                .put("unknownOutcomes", state.completed - state.wins - state.losses);
            if (action == BattleState.Action.STOP) throw new IllegalStateException("检测到御魂溢出，已停止；请手动处理");
            if (action == BattleState.Action.COMPLETE){
                if(exitMarker!=null)task.require(exitMarker);
                return state;
            }
            if (action == BattleState.Action.PREPARE) task.click(hits.get("I_PREPARE_HIGHLIGHT"));
            if (action == BattleState.Action.DISMISS) {
                if (hits.containsKey("I_GB_SKIN_CONFIRM")) task.click(hits.get("I_GB_SKIN_CONFIRM"));
                else task.region("C_RANDOM_BOTTOM");
            }
            task.pause(250);
        }
    }
}

package com.oas.android.tasks;

/** Validates before navigation or any resource-consuming action. Shared by UI and executor. */
public final class DungeonConfig {
    public final String kind;
    public final int variant,layer,rounds,minutes;
    public final boolean lock,stopOnLoss,inspect;
    public DungeonConfig(String kind,int variant,int layer,int rounds,int minutes,boolean lock,boolean stopOnLoss,boolean inspect){
        if(!java.util.Arrays.asList("orochi","evozone","goryou","sougenbi").contains(kind))throw new IllegalArgumentException("未知副本");
        int variants=kind.equals("orochi")?1:kind.equals("sougenbi")?3:4;
        if(variant<0||variant>=variants)throw new IllegalArgumentException("副本类型无效");
        if(kind.equals("orochi")&&(layer<10||layer>13))throw new IllegalArgumentException("御魂暂支持 10–13 层");
        if(kind.equals("evozone")&&(layer<1||layer>10))throw new IllegalArgumentException("觉醒层数需在 1–10");
        if(rounds<1||rounds>100)throw new IllegalArgumentException("副本场数需在 1–100");
        if(minutes<1||minutes>120)throw new IllegalArgumentException("副本时限需在 1–120 分钟");
        this.kind=kind;this.variant=variant;this.layer=layer;this.rounds=rounds;this.minutes=minutes;
        this.lock=lock;this.stopOnLoss=stopOnLoss;this.inspect=inspect;
    }
}

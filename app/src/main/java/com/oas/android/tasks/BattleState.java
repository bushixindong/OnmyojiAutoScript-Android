package com.oas.android.tasks;

/** Pure state machine: no touches on unknown pages, no invented wins from a reward-only frame. */
public final class BattleState {
    public enum Page { UNKNOWN, PREPARE, BATTLE, WIN, LOSS, REWARD, OVERFLOW }
    public enum Action { NONE, PREPARE, DISMISS, COMPLETE, STOP }
    public enum Outcome { UNKNOWN, WIN, LOSS }
    public final int limit;
    public int completed, wins, losses;
    public Outcome outcome = Outcome.UNKNOWN;
    private final boolean prepare;
    private boolean settlement, armed = true;
    private long absentSince = -1, lastTouch = -10000;
    public BattleState(int limit, boolean prepare) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("场次需在 1–100 之间");
        this.limit = limit; this.prepare = prepare;
    }
    public Action step(Page page, long now, boolean cancelled) {
        if (cancelled || page == Page.OVERFLOW) return Action.STOP;
        if (completed >= limit) return Action.COMPLETE;
        if (!settlement && (page == Page.PREPARE || page == Page.BATTLE)) armed = true;
        if (page == Page.WIN || page == Page.LOSS || page == Page.REWARD) {
            if (!armed) return Action.NONE;
            settlement = true; absentSince = -1;
            if (page == Page.LOSS) outcome = Outcome.LOSS;
            else if (page == Page.WIN && outcome != Outcome.LOSS) outcome = Outcome.WIN;
            if (now - lastTouch >= 800) { lastTouch = now; return Action.DISMISS; }
            return Action.NONE;
        }
        if (settlement) {
            if (absentSince < 0) absentSince = now;
            if (now - absentSince < 2500) return Action.NONE;
            completed++; if (outcome == Outcome.WIN) wins++; if (outcome == Outcome.LOSS) losses++;
            settlement = false; armed = false; absentSince = -1;
            if (completed >= limit) return Action.COMPLETE;
            outcome = Outcome.UNKNOWN;
        }
        if (page == Page.PREPARE && prepare && now - lastTouch >= 1200) { lastTouch = now; return Action.PREPARE; }
        return Action.NONE;
    }
}

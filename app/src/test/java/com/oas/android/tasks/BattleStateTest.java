package com.oas.android.tasks;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.oas.android.tasks.BattleState.Page.*;
import static com.oas.android.tasks.BattleState.Action.*;

public class BattleStateTest {
    @Test public void courtyardNeverCompletesAndUnknownNeverClicks() {
        BattleState state = new BattleState(1, true);
        for (int t = 0; t < 600000; t += 1000) assertEquals(NONE, state.step(UNKNOWN, t, false));
        assertEquals(0, state.completed);
    }
    @Test public void lossIsNotOverwrittenByReward() {
        BattleState state = new BattleState(1, false);
        assertEquals(DISMISS, state.step(LOSS, 0, false));
        assertEquals(DISMISS, state.step(REWARD, 1000, false));
        assertEquals(NONE, state.step(UNKNOWN, 1200, false));
        assertEquals(NONE, state.step(UNKNOWN, 3699, false));
        assertEquals(COMPLETE, state.step(UNKNOWN, 3700, false));
        assertEquals(1, state.losses); assertEquals(0, state.wins);
    }
    @Test public void rewardOnlyHasUnknownOutcomeAndDoesNotCountTwice() {
        BattleState state = new BattleState(2, false);
        state.step(REWARD, 0, false); state.step(UNKNOWN, 1000, false); state.step(UNKNOWN, 3500, false);
        assertEquals(1, state.completed); assertEquals(0, state.wins);
        state.step(REWARD, 4000, false); state.step(UNKNOWN, 5000, false); state.step(UNKNOWN, 8000, false);
        assertEquals(1, state.completed);
        state.step(BATTLE, 9000, false); state.step(WIN, 10000, false);
        state.step(UNKNOWN, 11000, false); assertEquals(COMPLETE, state.step(UNKNOWN, 13500, false));
        assertEquals(2, state.completed); assertEquals(1, state.wins);
        assertEquals(COMPLETE, state.step(REWARD, 14000, false));
    }
    @Test public void flickerRestartsExitTimerAndClicksAreThrottled() {
        BattleState state = new BattleState(1, false);
        assertEquals(DISMISS, state.step(WIN, 0, false));
        assertEquals(NONE, state.step(WIN, 400, false));
        state.step(UNKNOWN, 1000, false); state.step(REWARD, 2000, false);
        state.step(UNKNOWN, 2500, false); assertEquals(NONE, state.step(UNKNOWN, 4999, false));
        assertEquals(COMPLETE, state.step(UNKNOWN, 5000, false));
    }
    @Test public void cancellationOverflowAndPrepareOptIn() {
        assertEquals(STOP, new BattleState(1, true).step(WIN, 0, true));
        assertEquals(STOP, new BattleState(1, true).step(OVERFLOW, 0, false));
        assertEquals(NONE, new BattleState(1, false).step(BattleState.Page.PREPARE, 0, false));
        assertEquals(BattleState.Action.PREPARE, new BattleState(1, true).step(BattleState.Page.PREPARE, 0, false));
    }
}

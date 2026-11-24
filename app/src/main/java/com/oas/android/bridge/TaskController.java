// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from MaaMeow ActivityUtils: preserve existing game tasks when changing displays.
package com.oas.android.bridge;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class TaskController {
    private TaskController() { }

    @SuppressWarnings("deprecation")
    static ActivityManager.RunningTaskInfo find(String packageName) throws Exception {
        ActivityManager manager = (ActivityManager) new ShellContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(100)) {
            if ((task.topActivity != null && packageName.equals(task.topActivity.getPackageName()))
                    || (task.baseActivity != null && packageName.equals(task.baseActivity.getPackageName()))) return task;
        }
        return null;
    }

    static int displayOf(ActivityManager.RunningTaskInfo task) throws Exception {
        if (task == null) return -2;
        if (Build.VERSION.SDK_INT < 29) return -1;
        Field field = task.getClass().getField("displayId");
        return field.getInt(task);
    }

    static int displayOf(String packageName) throws Exception { return displayOf(find(packageName)); }

    @SuppressWarnings("deprecation")
    static boolean isTopOnDisplay(String packageName, int display) throws Exception {
        ActivityManager manager = (ActivityManager) new ShellContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningTaskInfo task : manager.getRunningTasks(100))
            if (displayOf(task) == display) return task.topActivity != null && packageName.equals(task.topActivity.getPackageName());
        return false;
    }

    static boolean moveExisting(String packageName, int target) throws Exception {
        ActivityManager.RunningTaskInfo task = find(packageName);
        if (task == null) return false;
        int current = displayOf(task);
        if (current < 0) throw new IllegalStateException("系统无法确认游戏所在屏幕，请先使用前台模式测试");
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "activity_task");
        Object manager = Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        Method move;
        try { move = manager.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class); }
        catch (NoSuchMethodException e) { move = manager.getClass().getMethod("moveStackToDisplay", int.class, int.class); }
        if (current != target) move.invoke(manager, task.id, target);
        manager.getClass().getMethod("setFocusedTask", int.class).invoke(manager, task.id);
        SystemClock.sleep(600);
        long deadline = SystemClock.uptimeMillis() + 2000;
        do {
            if (displayOf(packageName) == target) return true;
            SystemClock.sleep(150);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException("游戏未留在目标虚拟屏（可能在屏幕变化后退出），请导出诊断记录");
    }
}

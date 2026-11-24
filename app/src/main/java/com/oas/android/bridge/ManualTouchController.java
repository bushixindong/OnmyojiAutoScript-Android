// SPDX-License-Identifier: AGPL-3.0-only
// Stable pointer IDs and complete multi-touch events, following MaaMeow's preview input model.
package com.oas.android.bridge;

import android.content.Context;
import android.os.SystemClock;
import android.view.*;
import java.lang.reflect.Method;
import java.util.*;

final class ManualTouchController {
    private final LinkedHashMap<Integer,float[]> held=new LinkedHashMap<>();
    private long down;
    private int display=-1;
    private Object input;
    private Method inject,setDisplay;

    void accept(int action,int index,int[] ids,float[] xy,int target) throws Exception {
        if(action==MotionEvent.ACTION_CANCEL) {cancel();return;}
        if(ids==null||xy==null||ids.length<1||ids.length>10||xy.length!=ids.length*2||index<0||index>=ids.length)
            throw new IllegalArgumentException("无效的多点触控数据");
        Set<Integer> points=new HashSet<>();
        for(int id:ids)if(id<0||id>31||!points.add(id))throw new IllegalArgumentException("触点编号无效或重复");
        if(action==MotionEvent.ACTION_DOWN) {
            if(ids.length!=1)throw new IllegalArgumentException("首个按下必须只有一个触点");
            cancel();down=SystemClock.uptimeMillis();display=target;
        } else {
            if(held.isEmpty())return;
            if(display!=target) {cancel();throw new IllegalStateException("触控目标屏幕已改变");}
            Set<Integer> expected=new HashSet<>(held.keySet());
            if(action==MotionEvent.ACTION_POINTER_DOWN) {
                if(!expected.add(ids[index]))throw new IllegalArgumentException("触点已按下");
            } else if(action!=MotionEvent.ACTION_MOVE && action!=MotionEvent.ACTION_POINTER_UP && action!=MotionEvent.ACTION_UP)
                throw new IllegalArgumentException("不支持的触控类型");
            if(!expected.equals(points)||(action==MotionEvent.ACTION_UP&&ids.length!=1))throw new IllegalArgumentException("触控序列不连续");
        }
        emit(action,index,ids,xy);
        held.clear();for(int i=0;i<ids.length;i++)held.put(ids[i],new float[]{xy[i*2],xy[i*2+1]});
        if(action==MotionEvent.ACTION_POINTER_UP)held.remove(ids[index]);
        if(action==MotionEvent.ACTION_UP)held.clear();
        if(held.isEmpty())down=0;
    }
    void cancel() throws Exception {
        if(held.isEmpty())return;
        int[] ids=new int[held.size()];float[] xy=new float[ids.length*2];int index=0;
        for(Map.Entry<Integer,float[]> point:held.entrySet()) {ids[index]=point.getKey();xy[index*2]=point.getValue()[0];xy[index*2+1]=point.getValue()[1];index++;}
        try {emit(MotionEvent.ACTION_CANCEL,0,ids,xy);}
        finally {held.clear();down=0;display=-1;}
    }
    private void emit(int action,int index,int[] ids,float[] xy) throws Exception {
        if(input==null) {
            input=new ShellContext().getSystemService(Context.INPUT_SERVICE);
            inject=input.getClass().getMethod("injectInputEvent",InputEvent.class,int.class);
            setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);
        }
        MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[ids.length];
        for(int i=0;i<ids.length;i++) {
            props[i]=new MotionEvent.PointerProperties();props[i].id=ids[i];props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords();coords[i].x=xy[i*2];coords[i].y=xy[i*2+1];coords[i].pressure=1;coords[i].size=1;
        }
        int encoded=action;
        if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_POINTER_UP)encoded|=index<<MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),encoded,ids.length,props,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try {setDisplay.invoke(event,display);if(!Boolean.TRUE.equals(inject.invoke(input,event,2)))throw new IllegalStateException("系统拒绝多点触控");}
        finally {event.recycle();}
    }
}

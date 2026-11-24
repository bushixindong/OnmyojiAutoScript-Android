package com.oas.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import com.oas.android.engine.PythonHost;
import com.oas.android.engine.TaskEngine;
import org.json.JSONObject;

/** Shell-launched only. Requires the controlled Probe already running on this app's virtual display. */
public final class SystemAuditActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);TextView view=new TextView(this);view.setText("Checking raw engine transport and power/audio cleanup…");setContentView(view);
        new Thread(()->{
            String result;
            DeviceSession session=((OasApp)getApplication()).session;
            android.content.SharedPreferences options=getSharedPreferences("MainActivity",0);
            java.util.Map<String,?> before=options.getAll();
            try(PythonHost host=new PythonHost(session,"com.oas.android"){
                @Override public byte[] screenshot(){throw new AssertionError("Raw engine transport fell back to PNG");}
            }) {
                JSONObject report=new JSONObject();
                options.edit().putBoolean("keepAwake",true).putBoolean("autoMute",true).putBoolean("autoPanelOff",false).commit();
                int previous=new JSONObject(session.requireBridge().gameAudioState("com.oas.android")).getInt("mode");
                try(RuntimePowerGuard guard=new RuntimePowerGuard(this,session,"com.oas.android",20000)) {
                    if(!session.isGameMuted("com.oas.android")||!new JSONObject(session.requireBridge().systemStatus()).getBoolean("runtimeActive"))throw new AssertionError("Runtime guard did not acquire power/audio state");
                    TaskEngine.initialize(this,host);
                    report.put("pythonRaw",new JSONObject(com.chaquo.python.Python.getInstance().getModule("android_oas.system_probe").callAttr("run").toString()));
                }
                if(session.isGameMuted("com.oas.android")||new JSONObject(session.requireBridge().systemStatus()).getBoolean("runtimeActive"))throw new AssertionError("Runtime guard left power/audio state behind");
                if(new JSONObject(session.requireBridge().gameAudioState("com.oas.android")).getInt("mode")!=previous)throw new AssertionError("Audio permission changed after guard closed");
                report.put("runtimeCleanup",true).put("status","passed");result=report.toString();
            }catch(Throwable error){result="FAIL "+android.util.Log.getStackTraceString(error);}
            finally {
                android.content.SharedPreferences.Editor restore=options.edit();
                for(String key:new String[]{"keepAwake","autoMute","autoPanelOff"}) {
                    if(before.containsKey(key))restore.putBoolean(key,(Boolean)before.get(key));else restore.remove(key);
                }
                restore.commit();
            }
            try(java.io.FileOutputStream out=openFileOutput("system-audit.json",0)){out.write(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
            String text=result;view.post(()->view.setText(text));
        },"oas-system-audit").start();
    }
}

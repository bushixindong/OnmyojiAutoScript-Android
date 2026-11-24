package com.oas.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import com.chaquo.python.PyObject;
import com.oas.android.engine.*;
import org.json.*;

/** Imports all original task entries and validates their real options, without contacting a game. */
public final class EngineAuditActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);TextView view=new TextView(this);view.setText("Loading all original OAS tasks…");setContentView(view);
        new Thread(()->{
            String result;
            try{
                PythonHost host=new PythonHost(((OasApp)getApplication()).session,"com.netease.onmyoji"){
                    @Override public byte[] screenshot(){throw new AssertionError("Audit attempted a real screenshot");}
                    @Override public void tap(int x,int y){throw new AssertionError("Audit attempted a real touch");}
                    @Override public String shell(String command){throw new AssertionError("Audit attempted a shell query: "+command);}
                };
                PyObject engine=TaskEngine.initialize(this,host);
                JSONObject report=new JSONObject().put("entries",new JSONArray(engine.callAttr("audit").toString()));
                JSONArray forms=new JSONArray(),catalog=new JSONArray(engine.callAttr("catalog").toString());
                JSONArray cases=new JSONArray(engine.callAttr("form_cases").toString());
                JSONObject labels=new JSONObject(engine.callAttr("labels").toString());
                for(int i=0;i<catalog.length()+cases.length();i++){
                    boolean fixture=i>=catalog.length();JSONObject entry=fixture?cases.getJSONObject(i-catalog.length()):catalog.getJSONObject(i);if(entry.optBoolean("deferred"))continue;
                    String name=entry.getString(fixture?"task":"id");JSONObject item=new JSONObject().put("task",fixture?entry.getString("label"):name);
                    try{
                        JSONObject definition=fixture?entry.getJSONObject("definition"):new JSONObject(engine.callAttr("task_config",name).toString());
                        java.util.concurrent.FutureTask<String> read=new java.util.concurrent.FutureTask<>(()->{
                            LinearLayout container=new LinearLayout(this);container.setOrientation(LinearLayout.VERTICAL);
                            return new ConfigForm(this,container,definition.getJSONObject("schema"),definition.getJSONObject("value"),labels).read().toString();
                        });
                        runOnUiThread(read);String edited=read.get(30,java.util.concurrent.TimeUnit.SECONDS);
                        String normalized=engine.callAttr("validate_task_config",name,edited).toString();
                        boolean equal=engine.callAttr("equal_config",definition.getJSONObject("value").toString(),normalized).toBoolean();
                        if(!equal)throw new IllegalStateException("配置表单改变了原始值: "+edited+" => "+normalized);
                        item.put("status","passed");
                    }catch(Throwable error){item.put("status","failed").put("detail",android.util.Log.getStackTraceString(error));}
                    forms.put(item);
                }
                report.put("forms",forms);
                java.io.File fixtures=new java.io.File(getCacheDir(),"engine-fixtures");fixtures.mkdirs();
                for(String file:getAssets().list("ocr-replay")){
                    try(java.io.InputStream input=getAssets().open("ocr-replay/"+file);java.io.OutputStream output=new java.io.FileOutputStream(new java.io.File(fixtures,file))){byte[] bytes=new byte[16384];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);}
                }
                report.put("integration",new JSONArray(com.chaquo.python.Python.getInstance().getModule("android_oas.selftest").callAttr("run",fixtures.getAbsolutePath()).toString()));
                result=report.toString();
            }catch(Throwable error){result="FAIL "+android.util.Log.getStackTraceString(error);}
            try(java.io.FileOutputStream output=openFileOutput("engine-audit.json",0)){output.write(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            catch(Exception error){android.util.Log.e("OAS_ENGINE","audit report",error);}
            String report=result;view.post(()->view.setText(report));
        },"oas-full-engine-audit").start();
    }
}

package com.oas.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import com.chaquo.python.PyObject;
import com.oas.android.engine.*;
import org.json.*;
import java.util.concurrent.*;

/** Complete original task catalog and settings, rendered as native Android controls. */
public final class EngineActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private DeviceSession session;private PyObject engine;private JSONArray catalog;private JSONObject labels;
    private Spinner tasks;private LinearLayout fields;private TextView status;private ConfigForm form;private String selected,gamePackage,preset;
    private final Runnable refresh=()->{if(status!=null)status.setText(session.message);};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);session=((OasApp)getApplication()).session;gamePackage=getIntent().getStringExtra("package");
        if(gamePackage==null||gamePackage.isEmpty())gamePackage="com.netease.onmyoji";
        preset=getSharedPreferences("MainActivity",0).getString("activePreset","android");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(20,24,20,20);setContentView(root);
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(20,insets.getSystemWindowInsetTop()+16,20,insets.getSystemWindowInsetBottom()+12);return insets;});
        TextView title=new TextView(this);title.setText("全部 OAS 任务");title.setTextSize(24);root.addView(title);
        tasks=new Spinner(this);root.addView(tasks);status=new TextView(this);status.setText("首次加载完整任务与配置…");root.addView(status);
        ScrollView scroll=new ScrollView(this);fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);scroll.addView(fields);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);root.addView(actions);
        button(actions,"保存",()->save(false));button(actions,"运行",()->save(true));button(actions,"停止",()->{stopService(new Intent(this,MonitorService.class));session.update("正在停止任务");});
        session.addListener(refresh);
        Button schedule=new Button(this);schedule.setText("按原任务计划运行（最长两小时）");root.addView(schedule);
        schedule.setOnClickListener(view->{if(session.taskActive.get()){status.setText("已有任务运行");return;}try{session.requireBridge();startForegroundService(new Intent(this,MonitorService.class).setAction("engine-schedule").putExtra("package",gamePackage).putExtra("preset",preset));}catch(Exception error){status.setText(error.getMessage());}});
        if(session.taskActive.get()){status.setText("任务仍在运行，请先停止后再编辑配置");return;}
        worker.submit(()->{
            try{
                engine=TaskEngine.initialize(this,new PythonHost(session,gamePackage));catalog=new JSONArray(engine.callAttr("catalog").toString());labels=new JSONObject(engine.callAttr("labels").toString());
                String[] names=new String[catalog.length()];for(int i=0;i<names.length;i++)names[i]=catalog.getJSONObject(i).getString("label");
                runOnUiThread(()->{
                    tasks.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
                    tasks.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                        @Override public void onNothingSelected(android.widget.AdapterView<?> parent){}
                        @Override public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View row,int position,long id){load(position);}
                    });
                    status.setText("原任务流程已接入；配置后先按测试清单实测");
                });
            }catch(Throwable error){message("完整引擎加载失败："+error);}
        });
    }
    private void button(LinearLayout parent,String title,Runnable action){Button button=new Button(this);button.setText(title);button.setOnClickListener(view->action.run());parent.addView(button,new LinearLayout.LayoutParams(0,-2,1));}
    private void message(String text){runOnUiThread(()->status.setText(text));}
    private void load(int index){
        form=null;fields.removeAllViews();
        try{selected=catalog.getJSONObject(index).getString("id");}catch(Exception error){message(error.toString());return;}
        String task=selected;
        worker.submit(()->{
            try{JSONObject data=new JSONObject(engine.callAttr("task_config",task,preset).toString());
                runOnUiThread(()->{if(!task.equals(selected))return;try{form=new ConfigForm(this,fields,data.getJSONObject("schema"),data.getJSONObject("value"),labels);}catch(Exception error){status.setText(error.toString());}});
            }catch(Throwable error){message("配置加载失败："+error);}
        });
    }
    private void save(boolean run){
        if(form==null||selected==null)return;
        if(session.taskActive.get()){status.setText("任务仍在运行，请先停止或等待结束");return;}
        try{
            String data=form.read().toString(),task=selected;
            worker.submit(()->{
                try{
                    engine.callAttr("save_task_config",task,data,preset);
                    if(run){
                        boolean runnable=false;for(int i=0;i<catalog.length();i++)if(catalog.getJSONObject(i).getString("id").equals(task))runnable=catalog.getJSONObject(i).getBoolean("runnable");
                        if(!runnable){message("全局设置已保存");return;}
                        session.requireBridge();startForegroundService(new Intent(this,MonitorService.class).setAction("engine").putExtra("package",gamePackage).putExtra("engineTask",task).putExtra("preset",preset));
                        message("已启动任务，随时可以停止");
                    }else message("配置已校验并保存");
                }catch(Throwable error){message("配置或启动失败："+error.getMessage());}
            });
        }catch(Exception error){status.setText("配置输入无效："+error.getMessage());}
    }
    @Override protected void onDestroy(){session.removeListener(refresh);worker.shutdownNow();super.onDestroy();}
}

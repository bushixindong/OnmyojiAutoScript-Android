package com.oas.android;

import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.widget.TextView;
import com.oas.android.tasks.*;
import com.oas.android.vision.ScreenRecognizer;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** End-to-end native task replay through AIDL. All screenshots and touches stay in this fake device. */
public final class TaskReplayActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);TextView view=new TextView(this);view.setText("Running offline task flows…");setContentView(view);
        new Thread(()->{
            DeviceSession session=((OasApp)getApplication()).session;IDeviceBridge previous=session.bridge;boolean primary=session.primaryMode;
            StringBuilder report=new StringBuilder();boolean owner=session.taskActive.compareAndSet(false,true);
            if(!owner){view.post(()->view.setText("FAIL task already running"));return;}
            try(ScreenRecognizer recognizer=new ScreenRecognizer(this)){
                for(String kind:new String[]{"orochi","evozone","goryou","sougenbi"}){
                    try(FakeDevice device=new FakeDevice(kind,false)){
                        session.bridge=device;session.primaryMode=false;
                        TaskRuntime runtime=new TaskRuntime(this,session,recognizer,()->true,120000);
                        DungeonConfig config=new DungeonConfig(kind,kind.equals("orochi")?0:kind.equals("sougenbi")?2:3,kind.equals("orochi")?13:10,2,5,true,true,false);
                        String result=new DungeonTask(runtime,config).run();
                        if(device.challenges!=2||device.prepares!=2||device.rewards!=2||!device.page.equals("main")||runtime.report.getInt("rounds")!=2)
                            throw new AssertionError("Flow mismatch: "+kind+" "+result+" "+device.status());
                        report.append("PASS ").append(kind).append(" two rounds, layer/type, lock, reward, return main\n");
                        progress(report,view);
                    }
                }
                try(FakeDevice device=new FakeDevice("daily",false)){
                    session.bridge=device;
                    for(String action:new String[]{"mail","affairs","store","income"}){
                        TaskRuntime runtime=new TaskRuntime(this,session,recognizer,()->true,120000);
                        new DailyTasks(runtime,false,"offline.test").run(action);
                        String status=runtime.report.getJSONArray("daily").getJSONObject(0).getString("status");
                        if(!status.equals("verified")||!device.page.equals("main"))throw new AssertionError("Daily flow mismatch: "+action+" "+runtime.report);
                        report.append("PASS daily ").append(action).append(" verified and returned main\n");progress(report,view);
                    }
                    int claims=device.dailyClaims,read=device.readActions;
                    new DailyTasks(new TaskRuntime(this,session,recognizer,()->true,120000),true,"offline.test").run("mail");
                    if(device.dailyClaims!=claims||device.readActions!=read)throw new AssertionError("Mail inspection claimed/read");
                    report.append("PASS daily mail inspection does not claim or mark read\n");progress(report,view);
                }
                try(FakeDevice device=new FakeDevice("orochi",true)){
                    session.bridge=device;
                    TaskRuntime runtime=new TaskRuntime(this,session,recognizer,()->true,120000);
                    new DungeonTask(runtime,new DungeonConfig("orochi",0,13,2,5,true,true,false)).run();
                    if(device.challenges!=1||runtime.report.getInt("losses")!=1)throw new AssertionError("Loss did not stop after one battle");
                    report.append("PASS loss preserved across reward and stops next challenge\n");
                    progress(report,view);
                }
                try(FakeDevice device=new FakeDevice("orochi",false)){
                    session.bridge=device;
                    new DungeonTask(new TaskRuntime(this,session,recognizer,()->true,120000),new DungeonConfig("orochi",0,13,2,5,true,true,true)).run();
                    if(device.challenges!=0||!device.page.equals("main"))throw new AssertionError("Inspection started a battle");
                    AtomicBoolean running=new AtomicBoolean(false);TaskRuntime stopped=new TaskRuntime(this,session,recognizer,running::get,10000);
                    int touches=device.touches;
                    try{stopped.click(new ScreenRecognizer.Match("test","test",1,1,500,500));throw new AssertionError("Cancelled task clicked");}
                    catch(InterruptedException expected){}
                    if(device.touches!=touches)throw new AssertionError("Touch after cancellation");
                    device.page="unknown";
                    try{new Navigator(new TaskRuntime(this,session,recognizer,()->true,10000)).go("main");throw new AssertionError("Unknown page navigated");}
                    catch(IllegalStateException expected){}
                    if(device.touches!=touches)throw new AssertionError("Touch on unknown page");
                    report.append("PASS inspection does not challenge; cancellation and unknown page do not touch\n");
                }
                report.append("ALL PASS\n");
            }catch(Throwable error){report.append("FAIL ").append(android.util.Log.getStackTraceString(error));}
            finally{session.bridge=previous;session.primaryMode=primary;session.taskActive.set(false);}
            try(java.io.FileOutputStream output=openFileOutput("task-replay.txt",0)){output.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            catch(Exception error){report.append(error);}
            view.post(()->view.setText(report));
        },"oas-task-replay").start();
    }
    private void progress(StringBuilder report,TextView view){
        try(java.io.FileOutputStream output=openFileOutput("task-replay.txt",0)){output.write(report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(Exception error){android.util.Log.e("OAS_TEST","progress",error);}
        String text=report.toString();view.post(()->view.setText(text));
    }
    private final class FakeDevice extends IDeviceBridge.Stub implements AutoCloseable {
        final String kind;final boolean lose;
        final Map<String,Bitmap> images=new HashMap<>();final Map<String,JSONObject> rules=new HashMap<>();
        final List<String> visible=new ArrayList<>();JSONObject catalog;
        String page="main",rewardReturn;boolean locked,affairsDone,storeDone;int selectedLayer=10,variant;int challenges,prepares,rewards,touches,dailyClaims,readActions;
        FakeDevice(String kind,boolean lose)throws Exception{
            this.kind=kind;this.lose=lose;
            try(java.io.InputStream input=getAssets().open("oas/catalog.json")){catalog=new JSONObject(MainActivity.readText(input));}
            JSONArray entries=catalog.getJSONArray("rules");
            for(int i=0;i<entries.length();i++){
                JSONObject rule=entries.getJSONObject(i);String id=rule.getString("id");rules.put(id,rule);
                try(java.io.InputStream input=getAssets().open("oas/"+rule.getString("file"))){images.put(id,BitmapFactory.decodeStream(input));}
            }
        }
        @Override public synchronized ParcelFileDescriptor screenshot(){
            try{
                Bitmap frame=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);frame.eraseColor(Color.rgb(38,30,23));Canvas canvas=new Canvas(frame);visible.clear();
                switch(page){
                    case "main":
                        draw(canvas,"I_CHECK_MAIN","I_MAIN_GOTO_EXPLORATION");
                        if(kind.equals("daily")){
                            draw(canvas,"I_DT_HARVEST_MAIL_COPY2","I_ENTER_COURTYARD_AFFAIRS","I_MAIN_GOTO_MALL");
                            drawValue(canvas,"O_INCOME_GOLD");drawValue(canvas,"O_INCOME_JADE");drawValue(canvas,"O_INCOME_AP");
                        }
                        break;
                    case "exploration":draw(canvas,"I_CHECK_EXPLORATION","I_EXPLORATION_GOTO_SOUL_ZONE","I_EXPLORATION_GOTO_AWAKE_ZONE","I_EXPLORATION_GOTO_GORYOU_REALM","I_UI_BACK_YELLOW");break;
                    case "souls":draw(canvas,"I_CHECK_SOUL_ZONES","I_OROCHI","I_S_SOUGENBI","I_UI_BACK_YELLOW");break;
                    case "awake":draw(canvas,"I_CHECK_AWAKE","I_FIRE_KIRIN","I_WIND_KIRIN","I_WATER_KIRIN","I_LIGHTNING_KIRIN","I_UI_BACK_YELLOW");break;
                    case "goryou":draw(canvas,"I_CHECK_GORYOU","I_UI_BACK_YELLOW");break;
                    case "lobby":
                        draw(canvas,fire(),locked?lock():unlock(),"I_UI_BACK_YELLOW");
                        if(kind.equals("orochi")){draw(canvas,"I_OROCHI_CHECK_"+selectedLayer);drawList(canvas,"Orochi.L_LAYER_LIST");}
                        if(kind.equals("evozone"))drawList(canvas,"EvoZone.L_LAYER_LIST");
                        if(kind.equals("sougenbi"))draw(canvas,"I_S_CHECK_SOUGENBI","I_S_FIRE_"+new String[]{"GREED","ANGER","FOOLERY"}[variant]);
                        if(kind.equals("goryou")||kind.equals("sougenbi"))drawTicket(canvas);
                        break;
                    case "prepare":draw(canvas,"I_PREPARE_HIGHLIGHT");break;
                    case "result":draw(canvas,lose?"I_FALSE":"I_WIN");break;
                    case "reward":draw(canvas,"I_REWARD");break;
                    case "mail":draw(canvas,"I_HARVEST_MAIL_TITLE","I_HARVEST_MAIL_ALL","I_READ_ALL_MAIL","I_UI_BACK_RED");break;
                    case "affairs":draw(canvas,"I_CHECK_COURTYARD_AFFAIRS","I_ENTER_DAILY","I_UI_BACK_YELLOW");break;
                    case "daily_affairs":draw(canvas,"I_CHECK_COURTYARD_AFFAIRS","I_CHECK_IN_DAILY","I_UI_BACK_YELLOW");if(!affairsDone)draw(canvas,"I_ONE_COMPLETE");break;
                    case "mall_recommend":draw(canvas,"I_CHECK_MALL_RECOMMEND","I_UI_BACK_YELLOW");break;
                    case "mall":draw(canvas,"I_CHECK_MALL","I_ROOM_GIFT","I_UI_BACK_YELLOW");break;
                    case "gift":draw(canvas,"I_GIFT_RECOMMEND","I_UI_BACK_YELLOW");if(!storeDone)draw(canvas,"I_GIFT_SIGN");break;
                    case "daily_reward":draw(canvas,"I_UI_REWARD");break;
                    case "unknown":break;
                    default:throw new AssertionError("Unknown fake page");
                }
                byte[] bytes;
                try(java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){frame.compress(Bitmap.CompressFormat.PNG,100,output);bytes=output.toByteArray();}
                finally{frame.recycle();}
                ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
                new Thread(()->{try(ParcelFileDescriptor.AutoCloseOutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){output.write(bytes);}catch(Exception error){android.util.Log.e("OAS_TEST","fake pipe",error);}},"fake-screenshot").start();
                return pipe[0];
            }catch(Exception error){throw new IllegalStateException(error);}
        }
        void draw(Canvas canvas,String...ids)throws Exception{
            for(String id:ids){JSONObject rule=rules.get(id);JSONArray pos=rule.getJSONArray("reference_roi");canvas.drawBitmap(images.get(id),pos.getInt(0),pos.getInt(1),null);visible.add(id);}
        }
        void drawList(Canvas canvas,String id)throws Exception{
            JSONObject rule=find("lists",id);JSONArray roi=rule.getJSONArray("roi");
            try(java.io.InputStream input=getAssets().open("ocr-replay/det-list.png")){
                Bitmap image=BitmapFactory.decodeStream(input);try{canvas.drawBitmap(image,null,new Rect(roi.getInt(0),roi.getInt(1),roi.getInt(0)+roi.getInt(2),roi.getInt(1)+roi.getInt(3)),null);}finally{image.recycle();}
            }
        }
        void drawTicket(Canvas canvas)throws Exception{
            String id=kind.equals("goryou")?"O_GR_TICKET":"O_S_"+new String[]{"GREED","ANGER","FOOLERY"}[variant];
            drawValue(canvas,id);
        }
        void drawValue(Canvas canvas,String id)throws Exception{
            JSONArray roi=find("ocr",id).getJSONArray("roi");
            try(java.io.InputStream input=getAssets().open("ocr-replay/line-0.png")){
                Bitmap image=BitmapFactory.decodeStream(input);try{canvas.drawBitmap(image,null,new Rect(roi.getInt(0),roi.getInt(1),roi.getInt(0)+roi.getInt(2),roi.getInt(1)+roi.getInt(3)),null);}finally{image.recycle();}
            }
        }
        JSONObject find(String group,String id)throws Exception{JSONArray entries=catalog.getJSONArray(group);for(int i=0;i<entries.length();i++)if(entries.getJSONObject(i).getString("id").equals(id))return entries.getJSONObject(i);throw new IllegalArgumentException(id);}
        boolean contains(JSONArray roi,int x,int y)throws Exception{return x>=roi.getInt(0)&&y>=roi.getInt(1)&&x<roi.getInt(0)+roi.getInt(2)&&y<roi.getInt(1)+roi.getInt(3);}
        String hit(int x,int y)throws Exception{
            for(String id:visible){JSONArray roi=rules.get(id).getJSONArray("reference_roi");if(contains(roi,x,y))return id;}
            return null;
        }
        @Override public synchronized void tap(int x,int y){
            try{
                touches++;String hit=hit(x,y);
                if(kind.equals("daily")){
                    if(page.equals("main")){
                        if("I_DT_HARVEST_MAIL_COPY2".equals(hit)){page="mail";return;}
                        if("I_ENTER_COURTYARD_AFFAIRS".equals(hit)){page="affairs";return;}
                        if("I_MAIN_GOTO_MALL".equals(hit)){page="mall_recommend";return;}
                    }
                    if(page.equals("mail")){
                        if("I_HARVEST_MAIL_ALL".equals(hit)){rewardReturn="mail";page="daily_reward";dailyClaims++;return;}
                        if("I_READ_ALL_MAIL".equals(hit)){readActions++;return;}
                        if("I_UI_BACK_RED".equals(hit)){page="main";return;}
                    }
                    if(page.equals("affairs")&&"I_ENTER_DAILY".equals(hit)){page="daily_affairs";return;}
                    if(page.equals("daily_affairs")&&"I_ONE_COMPLETE".equals(hit)){rewardReturn="daily_affairs";page="daily_reward";affairsDone=true;dailyClaims++;return;}
                    if(page.equals("mall")&&"I_ROOM_GIFT".equals(hit)){page="gift";return;}
                    if(page.equals("gift")){
                        if("I_GIFT_RECOMMEND".equals(hit))return;
                        if("I_GIFT_SIGN".equals(hit)){rewardReturn="gift";page="daily_reward";storeDone=true;dailyClaims++;return;}
                    }
                    if(page.equals("daily_reward")&&contains(find("clicks","C_RANDOM_BOTTOM").getJSONArray("roi"),x,y)){page=rewardReturn;return;}
                    if("I_UI_BACK_YELLOW".equals(hit)){
                        if(page.equals("daily_affairs")||page.equals("affairs")||page.equals("mall")){page="main";return;}
                        if(page.equals("mall_recommend")||page.equals("gift")){page="mall";return;}
                    }
                }
                if(page.equals("main")&&"I_MAIN_GOTO_EXPLORATION".equals(hit)){page="exploration";return;}
                if(page.equals("exploration")){
                    if("I_EXPLORATION_GOTO_SOUL_ZONE".equals(hit)){page="souls";return;}
                    if("I_EXPLORATION_GOTO_AWAKE_ZONE".equals(hit)){page="awake";return;}
                    if("I_EXPLORATION_GOTO_GORYOU_REALM".equals(hit)){page="goryou";return;}
                }
                if(page.equals("souls")&&("I_OROCHI".equals(hit)||"I_S_SOUGENBI".equals(hit))){page="lobby";return;}
                if(page.equals("awake")&&hit!=null&&hit.endsWith("KIRIN")){page="lobby";return;}
                if(page.equals("goryou"))for(int i=1;i<=4;i++)if(contains(find("clicks","C_GR_C_"+i).getJSONArray("roi"),x,y)){variant=i-1;page="lobby";return;}
                if(page.equals("lobby")){
                    if(fire().equals(hit)){challenges++;page="prepare";return;}
                    if(lock().equals(hit)||unlock().equals(hit)){locked=!locked;return;}
                    if(kind.equals("orochi")||kind.equals("evozone")){
                        JSONArray roi=find("lists",kind.equals("orochi")?"Orochi.L_LAYER_LIST":"EvoZone.L_LAYER_LIST").getJSONArray("roi");
                        if(contains(roi,x,y)){selectedLayer=kind.equals("orochi")?13:10;return;}
                    }
                    if(kind.equals("sougenbi"))for(int i=0;i<3;i++)if(contains(find("clicks","C_C_"+new String[]{"GREED","ANGER","FOOLERY"}[i]).getJSONArray("roi"),x,y)){variant=i;return;}
                }
                if(page.equals("prepare")&&"I_PREPARE_HIGHLIGHT".equals(hit)){prepares++;page="result";return;}
                if((page.equals("result")||page.equals("reward"))&&contains(find("clicks","C_RANDOM_BOTTOM").getJSONArray("roi"),x,y)){
                    if(page.equals("result"))page="reward";else{page="lobby";rewards++;}return;
                }
                if("I_UI_BACK_YELLOW".equals(hit)){
                    if(page.equals("exploration")){page="main";return;}
                    if(page.equals("souls")||page.equals("awake")||page.equals("goryou")){page="exploration";return;}
                    if(page.equals("lobby")){page=kind.equals("orochi")?"exploration":kind.equals("evozone")?"awake":kind.equals("goryou")?"goryou":"souls";return;}
                }
                throw new IllegalStateException("Unexpected fake touch "+x+","+y+" on "+page+" hit="+hit);
            }catch(Exception error){throw new IllegalStateException(error);}
        }
        String fire(){return kind.equals("orochi")?"I_OROCHI_FIRE":kind.equals("evozone")?"I_EVOZONE_FIRE":kind.equals("goryou")?"I_GR_FIRE":"I_S_FIRE";}
        String lock(){return kind.equals("orochi")?"I_OROCHI_LOCK":kind.equals("evozone")?"I_EVOZONE_LOCK":kind.equals("goryou")?"I_GR_LOCK":"I_S_TEAM_LOCK";}
        String unlock(){return kind.equals("orochi")?"I_OROCHI_UNLOCK":kind.equals("evozone")?"I_EVOZONE_UNLOCK":kind.equals("goryou")?"I_GR_UNLOCK":"I_S_TEAM_UNLOCK";}
        @Override public void swipe(int x1,int y1,int x2,int y2,int duration){throw new IllegalStateException("Fixture should not require scrolling");}
        @Override public String status(){return kind+" "+page+" challenges="+challenges+" rewards="+rewards;}
        @Override public int createDisplay(int dpi){throw new UnsupportedOperationException();}
        @Override public void usePrimaryDisplay(){throw new UnsupportedOperationException();}
        @Override public Bundle rawFrameBuffer(){return null;}
        @Override public void setPreviewSurface(android.view.Surface surface,long owner){throw new UnsupportedOperationException();}
        @Override public void clearPreviewSurface(long owner){throw new UnsupportedOperationException();}
        @Override public String previewStatus(){throw new UnsupportedOperationException();}
        @Override public void previewTouch(int action,int x,int y,long owner){throw new UnsupportedOperationException();}
        @Override public void previewPointers(int action,int index,int[] ids,float[] xy,long owner){throw new UnsupportedOperationException();}
        @Override public String systemStatus(){throw new UnsupportedOperationException();}
        @Override public void configureRuntime(boolean active,boolean awake,boolean fps){}
        @Override public void configureFrameRates(int capture,int preview){}
        @Override public boolean setDisplayPower(boolean on){throw new UnsupportedOperationException();}
        @Override public String gameAudioState(String pkg){throw new UnsupportedOperationException();}
        @Override public boolean setGameMuted(String pkg,boolean mute,int previous){throw new UnsupportedOperationException();}
        @Override public void releaseDisplay(){throw new UnsupportedOperationException();}
        @Override public String launch(String pkg){throw new UnsupportedOperationException();}
        @Override public void spawnShellBridge(String apkPath,String token){throw new UnsupportedOperationException();}
        @Override public void destroy(){}
        @Override public void pressAndDrag(int a,int b,int c,int d,int hold,int duration){throw new UnsupportedOperationException();}
        @Override public void resizeDisplay(int width,int height){throw new UnsupportedOperationException();}
        @Override public void drawPath(int[] coordinates,int interval){throw new UnsupportedOperationException();}
        @Override public android.os.ParcelFileDescriptor readTaskFile(String path){throw new UnsupportedOperationException();}
        @Override public String shell(String[] arguments){throw new UnsupportedOperationException();}
        @Override public void stopPackage(String pkg){throw new UnsupportedOperationException();}
        @Override public void keyEvent(int code){throw new UnsupportedOperationException();}
        @Override public void inputText(String text){throw new UnsupportedOperationException();}
        @Override public void selectAllText(){throw new UnsupportedOperationException();}
        @Override public void close(){for(Bitmap image:images.values())image.recycle();}
    }
}

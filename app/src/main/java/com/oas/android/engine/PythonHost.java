package com.oas.android.engine;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import com.oas.android.DeviceSession;
import org.json.*;
import java.util.function.BooleanSupplier;

/** Only the platform boundary is Android-specific; original task decisions stay in Python. */
public class PythonHost implements AutoCloseable {
    private final com.oas.android.bridge.RawFrameReader rawReader=new com.oas.android.bridge.RawFrameReader();
    protected final DeviceSession session;
    private final String gamePackage;
    private BooleanSupplier running=()->true;
    private long deadline=Long.MAX_VALUE;
    private int touches;
    private boolean portrait;
    public PythonHost(DeviceSession session,String gamePackage){this.session=session;this.gamePackage=gamePackage;}
    public void begin(BooleanSupplier running,long timeoutMillis){this.running=running;this.deadline=SystemClock.elapsedRealtime()+timeoutMillis;touches=0;}
    public boolean isRunning(){return running.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline&&!Thread.currentThread().isInterrupted();}
    protected void check(){if(!isRunning())throw new IllegalStateException("任务停止或达到运行时限");}
    private void touching(){check();if(++touches>100000)throw new IllegalStateException("达到触控次数上限");}
    public String gamePackage(){return gamePackage;}
    public byte[] screenshotRgb()throws Exception{check();return rawReader.read(session.requireBridge());}
    @Override public void close(){rawReader.close();}
    public byte[] screenshot()throws Exception{
        check();try(ParcelFileDescriptor fd=session.requireBridge().screenshot();ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd);java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){
            byte[] bytes=new byte[16384];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);return output.toByteArray();
        }
    }
    public void tap(int x,int y)throws Exception{touching();session.requireBridge().tap(x,y);}
    public void swipe(int x1,int y1,int x2,int y2,int duration)throws Exception{touching();session.requireBridge().swipe(x1,y1,x2,y2,duration);}
    public void pressAndDrag(int x1,int y1,int x2,int y2,int hold,int duration)throws Exception{touching();session.requireBridge().pressAndDrag(x1,y1,x2,y2,hold,duration);}
    public void drawPath(String path,int interval)throws Exception{
        touching();JSONArray points=new JSONArray(path);int[] coordinates=new int[points.length()*2];
        for(int i=0;i<points.length();i++){coordinates[2*i]=points.getJSONArray(i).getInt(0);coordinates[2*i+1]=points.getJSONArray(i).getInt(1);}
        session.requireBridge().drawPath(coordinates,interval);
    }
    public void pullFile(String source,String destination)throws Exception{
        check();try(ParcelFileDescriptor fd=session.requireBridge().readTaskFile(source);java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd);java.io.OutputStream output=new java.io.FileOutputStream(destination)){
            byte[] buffer=new byte[16384];int count;long total=0;
            while((count=input.read(buffer))!=-1){check();total+=count;if(total>64L*1024*1024)throw new IllegalArgumentException("任务文件超过 64 MB");output.write(buffer,0,count);}
        }
    }
    public void launch(String name)throws Exception{check();session.requireBridge().launch(name);}
    public void stopPackage(String name)throws Exception{check();session.requireBridge().stopPackage(name);}
    public boolean isPortrait(){return portrait;}
    public void setPortrait(boolean value)throws Exception{check();session.requireBridge().resizeDisplay(value?720:1280,value?1280:720);portrait=value;}
    public void restoreOrientation(){
        if(!portrait)return;
        try{session.requireBridge().resizeDisplay(1280,720);portrait=false;}catch(Exception error){android.util.Log.e("OAS_ENGINE","restore virtual display",error);}
    }
    public String shell(String arguments)throws Exception{
        check();JSONArray values=new JSONArray(arguments);String[] argv=new String[values.length()];for(int i=0;i<argv.length;i++)argv[i]=values.getString(i);return session.requireBridge().shell(argv);
    }
    public String currentPackage()throws Exception{return new JSONObject(session.requireBridge().status()).optString("gamePackage");}
    public boolean isGameForeground()throws Exception{return new JSONObject(session.requireBridge().status()).optBoolean("isForeground");}
    public void keyEvent(String code)throws Exception{
        touching();int value;
        try{value=Integer.parseInt(code);}catch(NumberFormatException ignored){value=android.view.KeyEvent.keyCodeFromString(code.startsWith("KEYCODE_")?code:"KEYCODE_"+code);}
        session.requireBridge().keyEvent(value);
    }
    public void inputText(String text)throws Exception{touching();session.requireBridge().inputText(text);}
    public void replaceText(String text)throws Exception{touching();session.requireBridge().selectAllText();session.requireBridge().inputText(text);}
    public void log(String text){session.update(text);android.util.Log.i("OAS_ENGINE",text);}
    public int touches(){return touches;}
}

// SPDX-License-Identifier: AGPL-3.0-only
// Per-package AppOps with per-player fallback, following MaaMeow GameAudioMuteController.
package com.oas.android.bridge;

import android.app.AppOpsManager;
import android.content.Context;
import android.media.*;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;

final class GameAudio implements AutoCloseable {
    private final Context context;
    private final Handler handler;
    private final Map<String,Record> records=new HashMap<>();
    private Object appops;
    private AudioManager audio;
    private AudioManager.AudioPlaybackCallback callback;
    private static final class Record {
        final int uid,previous;boolean players;
        Record(int uid,int previous){this.uid=uid;this.previous=previous;}
    }
    GameAudio(Handler handler) throws Exception {
        if(android.os.Process.myUid()!=2000)throw new SecurityException("GameAudio is shell-only");
        context=new ShellContext();this.handler=handler;
    }
    private int uid(String pkg) throws Exception {
        ShellApis.packageName(pkg);int uid=context.getPackageManager().getApplicationInfo(pkg,0).uid;
        if(uid<10000)throw new IllegalArgumentException("只能静音用户应用");return uid;
    }
    private int mode(String pkg,int uid) throws Exception {
        AppOpsManager manager=(AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);
        return manager.checkOpNoThrow("android:play_audio",uid,pkg);
    }
    private void mode(String pkg,int uid,int mode) throws Exception {
        if(mode<0||mode>4)throw new IllegalArgumentException("无效的音频授权状态");
        if(appops==null)appops=ShellApis.service("appops","com.android.internal.app.IAppOpsService");
        int code=AppOpsManager.class.getField("OP_PLAY_AUDIO").getInt(null);
        ShellApis.invoke(appops.getClass().getMethod("setMode",int.class,int.class,String.class,int.class),appops,code,uid,pkg,mode);
    }
    String state(String pkg) throws Exception {
        int uid=uid(pkg);Record record=records.get(pkg);
        return new JSONObject().put("package",pkg).put("uid",uid).put("mode",mode(pkg,uid)).put("muted",record!=null)
            .put("method",record==null?"":record.players?"播放器音量":"应用音频权限").toString();
    }
    boolean set(String pkg,boolean muted,int previous) throws Exception {
        int uid=uid(pkg);
        if(!muted) {
            Record record=records.get(pkg);
            if(record!=null && record.players)apply(uid,1f);
            mode(pkg,uid,previous);
            if(mode(pkg,uid)!=previous)throw new IllegalStateException("游戏声音权限恢复失败");
            records.remove(pkg);stopCallbackIfUnused();return true;
        }
        if(records.containsKey(pkg))return true;
        Record record=new Record(uid,previous);records.put(pkg,record);
        try {
            mode(pkg,uid,AppOpsManager.MODE_IGNORED);
            if(mode(pkg,uid)==AppOpsManager.MODE_IGNORED)return true;
        }catch(Exception error) {android.util.Log.i("OAS","Audio AppOps unavailable, trying player volume",error);}
        try {
            // Restore only PLAY_AUDIO, preserving every other application permission.
            mode(pkg,uid,previous);
            ensureCallback();record.players=true;apply(uid,0f);return true;
        }catch(Exception error) {
            try{mode(pkg,uid,previous);}catch(Exception restore){error.addSuppressed(restore);}
            records.remove(pkg);stopCallbackIfUnused();throw error;
        }
    }
    // These hidden player handles are only accessed by the shell UserService, never the app UID.
    @android.annotation.SuppressLint("SoonBlockedPrivateApi")
    private int playerUid(AudioPlaybackConfiguration config) throws Exception {
        try {return ((Number)config.getClass().getDeclaredMethod("getClientUid").invoke(config)).intValue();}
        catch(NoSuchMethodException missing){Field f=config.getClass().getDeclaredField("mClientUid");f.setAccessible(true);return f.getInt(config);}
    }
    @android.annotation.SuppressLint("SoonBlockedPrivateApi")
    private Object player(AudioPlaybackConfiguration config) throws Exception {
        Method method=config.getClass().getDeclaredMethod("getIPlayer");method.setAccessible(true);return method.invoke(config);
    }
    private void volume(AudioPlaybackConfiguration config,float level) throws Exception {
        Object player=player(config);if(player!=null)player.getClass().getMethod("setVolume",float.class).invoke(player,level);
    }
    @android.annotation.SuppressLint("SoonBlockedPrivateApi")
    private void apply(int uid,float level) throws Exception {
        if(audio==null)audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        // Verify the fallback exists even if the game has not created a player yet.
        AudioPlaybackConfiguration.class.getDeclaredMethod("getIPlayer");
        for(AudioPlaybackConfiguration config:audio.getActivePlaybackConfigurations())if(playerUid(config)==uid)volume(config,level);
    }
    private void ensureCallback() {
        if(callback!=null)return;
        if(audio==null)audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        callback=new AudioManager.AudioPlaybackCallback() {
            @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                for(AudioPlaybackConfiguration config:configs)try {
                    int uid=playerUid(config);
                    for(Record record:records.values())if(record.uid==uid&&record.players){volume(config,0f);break;}
                }catch(Exception error){android.util.Log.w("OAS","Per-player mute",error);}
            }
        };
        audio.registerAudioPlaybackCallback(callback,handler);
    }
    private void stopCallbackIfUnused() {
        for(Record record:records.values())if(record.players)return;
        if(callback!=null){audio.unregisterAudioPlaybackCallback(callback);callback=null;}
    }
    @Override public void close() {
        for(Map.Entry<String,Record> entry:new ArrayList<>(records.entrySet()))try {set(entry.getKey(),false,entry.getValue().previous);}
        catch(Exception error){android.util.Log.w("OAS","Audio restore requires reconnect",error);}
    }
}

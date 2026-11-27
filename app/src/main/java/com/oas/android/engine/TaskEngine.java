package com.oas.android.engine;

import android.content.Context;
import com.chaquo.python.*;
import com.chaquo.python.android.AndroidPlatform;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.zip.*;

/** Installs only the audited source pack in app-private storage, never a user's desktop config. */
public final class TaskEngine {
    private static PyObject module;
    private TaskEngine(){}
    public static synchronized PyObject initialize(Context context,PythonHost host)throws Exception{
        File root=new File(context.getFilesDir(),"oas-engine");
        JSONObject manifest;
        try(InputStream input=context.getAssets().open("runtime/manifest.json")){manifest=new JSONObject(read(input));}
        String digest=manifest.getString("archiveSha256");File marker=new File(root,".source-sha256");
        File hotMarker=new File(root,".hot-update.json");
        boolean installed=false;
        if(hotMarker.isFile())try{
            JSONObject hot=new JSONObject(read(new FileInputStream(hotMarker)));
            int baseCode=hot.optInt("baseApkVersionCode",-1);
            String hotSha=hot.optString("archiveSha256","");
            if(baseCode==com.oas.android.BuildConfig.VERSION_CODE && !hotSha.isEmpty() && marker.isFile() && read(new FileInputStream(marker)).trim().equals(hotSha)){
                installed=true;
            }
        }catch(Exception ignored){}
        if(!installed && marker.isFile())try(InputStream input=new FileInputStream(marker)){installed=read(input).trim().equals(digest);}
        if(!installed){
            if(hotMarker.isFile())hotMarker.delete();
            root.mkdirs();
            try(InputStream raw=context.getAssets().open("runtime/oas-runtime.zip");java.security.DigestInputStream checked=new java.security.DigestInputStream(raw,MessageDigest.getInstance("SHA-256"));ZipInputStream archive=new ZipInputStream(checked)){
                ZipEntry entry;String prefix=root.getCanonicalPath()+File.separator;
                while((entry=archive.getNextEntry())!=null){
                    String name=entry.getName();
                    if(name.startsWith("config/")||name.startsWith("log/"))continue;
                    File target=new File(root,name);if(!target.getCanonicalPath().startsWith(prefix))throw new SecurityException("Source archive path escaped app storage");
                    if(entry.isDirectory())target.mkdirs();else{
                        target.getParentFile().mkdirs();try(OutputStream output=new FileOutputStream(target)){byte[] bytes=new byte[16384];int n;while((n=archive.read(bytes))!=-1)output.write(bytes,0,n);}
                    }
                }
                // ZipInputStream may leave central-directory bytes unread; verify the complete asset separately.
            }
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            try(InputStream input=context.getAssets().open("runtime/oas-runtime.zip")){byte[] bytes=new byte[16384];int n;while((n=input.read(bytes))!=-1)hash.update(bytes,0,n);}
            StringBuilder actual=new StringBuilder();for(byte b:hash.digest())actual.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            if(!digest.contentEquals(actual))throw new SecurityException("Runtime source checksum mismatch");
            try(OutputStream output=new FileOutputStream(new File(root,"runtime-manifest.json"))){output.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            try(OutputStream output=new FileOutputStream(marker)){output.write(digest.getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
        }
        if(!Python.isStarted())Python.start(new AndroidPlatform(context.getApplicationContext()));
        if(module==null)module=Python.getInstance().getModule("android_oas.bootstrap");
        File mirrorDir=context.getExternalFilesDir("error-logs");
        module.callAttr("initialize",root.getAbsolutePath(),host,mirrorDir!=null?mirrorDir.getAbsolutePath():"");return module;
    }
    public static void requestStop(){
        PyObject current=module;
        if(current!=null){
            try{current.callAttr("request_stop");}catch(Throwable ignored){}
        }
    }
    public static void wakeSchedule() {
        PyObject current = module;
        if (current != null) current.callAttr("wake_schedule");
    }
    private static String read(InputStream input)throws IOException{ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] bytes=new byte[16384];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);return output.toString("UTF-8");}
}

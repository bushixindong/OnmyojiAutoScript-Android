// SPDX-License-Identifier: AGPL-3.0-only
package com.oas.android.bridge;

import android.os.Build;
import java.nio.ByteBuffer;

public final class NativeFrames {
    static {
        try { System.loadLibrary("oas_frames"); }
        catch(UnsatisfiedLinkError appProcess) {
            // Shizuku/app_process class loaders do not necessarily inherit the APK native search path.
            try {
                android.content.pm.ApplicationInfo info=new ShellContext().getPackageManager().getApplicationInfo("com.oas.android",0);
                java.io.File extracted=new java.io.File(info.nativeLibraryDir,"liboas_frames.so");
                if(extracted.isFile()) System.load(extracted.getPath());
                else System.load(info.sourceDir+"!/lib/"+(android.os.Process.is64Bit()?Build.SUPPORTED_64_BIT_ABIS[0]:Build.SUPPORTED_32_BIT_ABIS[0])+"/liboas_frames.so");
            } catch(Exception | UnsatisfiedLinkError error) { error.addSuppressed(appProcess); throw new ExceptionInInitializerError(error); }
        }
    }
    private NativeFrames() { }
    public static native int storeSize();
    public static native void initialize(ByteBuffer store);
    public static native void publish(ByteBuffer store,ByteBuffer rgba,int width,int height,int stride,long now);
    public static native byte[] read(ByteBuffer store,long now);
    public static native int[] spawnShellBridge(String apkPath,String token);
    public static native void stopShellBridge(int pid,int writeFd);
}

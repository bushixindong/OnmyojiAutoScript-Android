package com.oas.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Debug APK only. Existing ADB shell can attach the same device service for emulator QA. */
public final class DebugBridgeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != 2000 && uid != 0) throw new SecurityException("ADB shell required");
        DeviceSession session = ((OasApp) getContext().getApplicationContext()).session;
        Bundle reply = new Bundle();
        if ("get-bridge".equals(method)) {
            // Borrow the actual Shizuku service; do not create or substitute a debug backend.
            reply.putBinder("bridge", session.requireBridge().asBinder()); return reply;
        }
        if ("task".equals(method)) {
            if (!MonitorService.ACTIONS.contains(arg))
                throw new IllegalArgumentException("Unknown task");
            android.content.Intent task = new android.content.Intent(getContext(), MonitorService.class).setAction(arg);
            if (extras != null) task.putExtra("package", extras.getString("package"));
            if ("stop".equals(arg)) getContext().stopService(task);
            else getContext().startForegroundService(task);
            reply.putBoolean("started", true); return reply;
        }
        if (!"attach".equals(method) || extras == null) throw new IllegalArgumentException("Unknown debug operation");
        session.attachDebug(extras.getBinder("bridge"));
        reply.putBoolean("attached", true); return reply;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}

package com.oas.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** One-time token-verified receiver for a Root-demoted shell UID 2000 DeviceBridge. */
public final class BridgeAttachProvider extends ContentProvider {
    private static final ConcurrentHashMap<String, CompletableFuture<IBinder>> PENDING = new ConcurrentHashMap<>();

    static CompletableFuture<IBinder> expect(String token) {
        CompletableFuture<IBinder> future = new CompletableFuture<>();
        PENDING.put(token, future);
        return future;
    }

    static void cancel(String token) {
        CompletableFuture<IBinder> future = PENDING.remove(token);
        if (future != null) future.cancel(true);
    }

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (Binder.getCallingUid() != 2000) throw new SecurityException("Shell UID 2000 required");
        if (!"attach".equals(method) || extras == null) throw new IllegalArgumentException("Unsupported operation");
        String token = extras.getString("token");
        IBinder bridge = extras.getBinder("bridge");
        if (token == null || bridge == null) throw new IllegalArgumentException("Missing bridge handshake");
        CompletableFuture<IBinder> waiter = PENDING.remove(token);
        if (waiter == null) throw new SecurityException("Invalid or expired handshake token");
        waiter.complete(bridge);
        Bundle reply = new Bundle();
        reply.putBoolean("attached", true);
        return reply;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}

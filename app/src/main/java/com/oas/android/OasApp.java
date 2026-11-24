package com.oas.android;
import android.app.Application;
public final class OasApp extends Application {
    public DeviceSession session;
    @Override public void onCreate() { super.onCreate(); session = new DeviceSession(this); }
}

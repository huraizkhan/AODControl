package com.huraiz.aodcontrol;

import android.app.Application;

public class AodControlApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ShizukuBridge.init(this);
    }
}

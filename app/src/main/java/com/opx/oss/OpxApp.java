package com.opx.oss;

import android.app.Application;

/** Application entry point; later phases attach the Termux runtime and the WiFi engine here. */
public class OpxApp extends Application {

    private static OpxApp instance;

    public static OpxApp get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }
}

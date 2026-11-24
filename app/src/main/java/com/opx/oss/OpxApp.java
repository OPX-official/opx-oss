package com.opx.oss;

import android.app.Application;

/** Application entry point; later phases attach the WiFi engine to the already running runtime. */
public class OpxApp extends Application {

    private static OpxApp instance;

    public static OpxApp get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // The Settings screen offers a log level; it has to be in effect before anything logs.
        AppLog.load(this);

        // The engine belongs to the app, not to the terminal screen. Starting it here means any
        // entry point that creates the process (launcher, shortcut, notification, restored task)
        // warms the bundled userland immediately, so no feature ever depends on the terminal
        // having been opened. OpxService.start() is idempotent and MainActivity calls it as well.
        OpxService.start(this);
    }
}

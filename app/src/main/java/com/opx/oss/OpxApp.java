package com.opx.oss;

import android.app.Application;

/** Application entry point: the engine lives in this process, so it starts with the app. */
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

        // Plugging the adapter in asks for its permission and brings the interface up from any
        // screen (and from none): the WiFi screen is not the entry point for that any more.
        UsbAutoConnect.start(this);

        // Nothing to install and nothing to download: the engine is part of the APK.
        OpxService.start(this);
    }
}

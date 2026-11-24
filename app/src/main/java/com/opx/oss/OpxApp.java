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

        // Plugging the adapter in asks for its permission and brings the interface up from any
        // screen (and from none): the WiFi screen is not the entry point for that any more.
        UsbAutoConnect.start(this);

        // Do not start the engine before first-run setup. The service would see no installed
        // runtime and could start the network download before the intro has collected its
        // permissions. MainActivity starts it for an installed/intro-complete app, and the
        // intro's download slide calls OpxService.beginDownload() exactly once.
        boolean introSeen = getSharedPreferences("opx", MODE_PRIVATE)
                .getBoolean("intro_seen", false);
        boolean downloadStarted = getSharedPreferences("opx", MODE_PRIVATE)
                .getBoolean("download_allowed", false);
        if (introSeen || downloadStarted || OpxRuntime.isInstalled(this)) OpxService.start(this);
    }
}

package com.opx.oss;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The USB adapter's doorbell: whichever screen is open — or no screen at all, intro slides
 * included — plugging the card in asks Android for the device permission and, once granted,
 * brings wifi4's interface up without a trip to the WiFi screen.
 * <p>
 * Two receivers feed it: {@link UsbAttachReceiver}, declared in the manifest so a plugged card is
 * seen even when the process was not alive, and a runtime registration from {@link OpxApp} so the
 * broadcast always reaches a live app on Android 13+, where a receiver's export flag matters.
 * Both call {@link #handle}, which de-duplicates the one attach event they can both see.
 */
public final class UsbAutoConnect {

    static final String ACTION_PERMISSION = "com.opx.oss.action.USB_PERMISSION";

    private static final Set<String> requested = new HashSet<>();
    /** Devices whose prompt was declined in this process: no prompt on every screen change. */
    private static final Set<String> denied = new HashSet<>();
    private static long lastAttachAt;
    /** Both receivers see the same unplug: one teardown per event is enough. */
    private static long lastDetachAt;
    private static boolean registered;

    private UsbAutoConnect() {
    }

    /** Called once from the Application: register for attach/detach and check what is plugged in. */
    public static synchronized void start(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!registered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            filter.addAction(ACTION_PERMISSION);
            try {
                ContextCompat.registerReceiver(app, new Receiver(), filter,
                        ContextCompat.RECEIVER_EXPORTED);
                registered = true;
            } catch (RuntimeException e) {
                AppLog.error("UsbAutoConnect", e);
            }
        }
        // The card may already be plugged in from before this process started. First-run
        // setup owns the prompt, so nothing appears behind the intro.
        if (!IntroActivity.isShowing() && introSeen(app)) ensurePermission(app);
    }

    private static boolean introSeen(Context context) {
        return context.getSharedPreferences("opx", Context.MODE_PRIVATE)
                .getBoolean("intro_seen", false);
    }

    /** The first launch's slides are over (or the app came back to a screen): ask if needed. */
    public static void resume(Context context) {
        if (context == null || IntroActivity.isShowing()) return;
        Context app = context.getApplicationContext();
        if (!introSeen(app)) return;
        ensurePermission(app);
    }

    /**
     * The intro's permissions slide asks for the adapter too. Attach broadcasts normally wait
     * for the intro, but this explicit call lets first-run setup collect the USB grant in the
     * same place as location and storage.
     */
    public static void requestNow(Context context) {
        if (context == null) return;
        ensurePermission(context.getApplicationContext());
    }

    /** One entry point for both receivers: attach, detach and the permission answer. */
    public static void handle(Context context, Intent intent) {
        if (context == null || intent == null) return;
        Context app = context.getApplicationContext();
        String action = intent.getAction();
        if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            long now = System.currentTimeMillis();
            if (now - lastDetachAt < 1500) return;
            lastDetachAt = now;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) requested.remove(device.getDeviceName());
            AppLog.info("UsbAutoConnect", "adapter detached");
            UsbHostBridge.onDeviceDetached();
            if (WifiBridge.get(app).isRunning()) {
                WifiBridge.get(app).send(json("usb_lost"));
            }
            return;
        }
        if (ACTION_PERMISSION.equals(action)) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) requested.remove(device.getDeviceName());
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (!granted && device != null) {
                synchronized (denied) {
                    denied.add(device.getDeviceName());
                }
            }
            AppLog.info("UsbAutoConnect", "permission " + (granted ? "granted" : "denied"));
            if (granted && device != null) {
                synchronized (denied) {
                    denied.remove(device.getDeviceName());
                }
            }
            // Never trust the extra alone: the permission is the one UsbManager reports.
            UsbManager grantedBy = (UsbManager) app.getSystemService(Context.USB_SERVICE);
            if (granted && grantedBy != null && device != null
                    && grantedBy.hasPermission(device)) {
                connect(app);
            } else {
                // A denied prompt is not an error state: the WiFi screen still offers its own
                // prompt when the user taps Scan, and replugging asks again.
                UsbManager manager = (UsbManager) app.getSystemService(Context.USB_SERVICE);
                if (manager != null && device != null && manager.hasPermission(device)) {
                    connect(app);
                }
            }
            return;
        }
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            // Both receivers can see the same attach; one prompt per plug is enough.
            long now = System.currentTimeMillis();
            if (now - lastAttachAt < 1500) return;
            lastAttachAt = now;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                synchronized (denied) {
                    denied.remove(device.getDeviceName());
                }
            }
            AppLog.info("UsbAutoConnect", "adapter attached"
                    + (device == null ? "" : ": " + device.getDeviceName()));
            // The intro owns the first launch; its end (MainActivity.onResume) asks instead.
            if (IntroActivity.isShowing()) return;
            ensurePermission(app);
        }
    }

    /** Asks for every attached device that does not hold the permission yet. */
    private static void ensurePermission(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) return;
        Map<String, UsbDevice> devices = manager.getDeviceList();
        if (devices == null || devices.isEmpty()) return;
        boolean any = false;
        for (UsbDevice device : devices.values()) {
            // Only wifi4's own chips (and their ZeroCD stubs) get a Wi-Fi permission prompt;
            // a keyboard or thumb drive on the same OTG hub is none of this app's business.
            if (!UsbIds.known(context, device)) continue;
            if (manager.hasPermission(device)) {
                any = true;
                continue;
            }
            synchronized (denied) {
                if (denied.contains(device.getDeviceName())) continue;
            }
            request(context, manager, device);
        }
        if (any) connect(context);
    }

    private static void request(Context context, UsbManager manager, UsbDevice device) {
        String key = device.getDeviceName();
        synchronized (requested) {
            if (!requested.add(key)) return;
        }
        try {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The platform fills the answer into the intent's extras; immutable can't take it.
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getBroadcast(context, key.hashCode(),
                    new Intent(context, UsbAttachReceiver.class)
                            .setAction(ACTION_PERMISSION)
                            .setPackage(context.getPackageName()),
                    flags);
            manager.requestPermission(device, pending);
            AppLog.info("UsbAutoConnect", "asking for permission: " + key);
        } catch (RuntimeException e) {
            synchronized (requested) {
                requested.remove(key);
            }
            AppLog.error("UsbAutoConnect", e);
        }
    }

    /**
     * Once the runtime is ready, start the engine and tell it the interface may come up now. The
     * engine answers {@code usb_attached} or {@code usb_waiting}, which the WiFi screen shows.
     */
    private static void connect(Context context) {
        OpxService.runWhenReady(context, () -> {
            try {
                WifiBridge bridge = WifiBridge.get(context);
                bridge.start();
                bridge.send(json("usb_attach"));
            } catch (RuntimeException e) {
                AppLog.error("UsbAutoConnect", e);
            }
        });
    }

    private static JSONObject json(String command) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("cmd", command);
        } catch (org.json.JSONException ignored) {
            // A one-key object cannot fail to build.
        }
        return payload;
    }

    /** The runtime receiver; the manifest one is {@link UsbAttachReceiver}. */
    private static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            handle(context, intent);
        }
    }
}

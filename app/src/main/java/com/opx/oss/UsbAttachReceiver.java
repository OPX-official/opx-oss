package com.opx.oss;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The manifest-declared half of the adapter doorbell: Android can deliver an attach (or a USB
 * permission answer) to this class even when no activity exists yet. The work itself lives in
 * {@link UsbAutoConnect}, so both receivers take exactly the same path.
 */
public class UsbAttachReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        UsbAutoConnect.handle(context, intent);
    }
}

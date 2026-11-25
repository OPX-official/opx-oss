package com.opx.oss.engine.dot11;

/** Channel-number helpers: the 20 MHz global operating class, band and DFS (802.11-2020 E-4). */
public final class Chan {

    private Chan() {
    }

    /** The global operating class for a 20 MHz channel, or 0 when the channel is unmapped. */
    public static int operatingClass(int channel) {
        if (channel >= 1 && channel <= 13) return 81;
        if (channel == 14) return 82;
        if (channel >= 36 && channel <= 48) return 115;
        if (channel >= 52 && channel <= 64) return 118;
        if (channel >= 100 && channel <= 144) return 121;
        if (channel >= 149 && channel <= 177) return 125;
        return 0;
    }

    /** True for a 5 GHz DFS channel (UNII-2 52-64, UNII-2e 100-144). */
    public static boolean isDfs(int channel) {
        return (channel >= 52 && channel <= 64) || (channel >= 100 && channel <= 144);
    }

    /** True when both channels sit in the same band (2.4 GHz <= 14, 5 GHz above). */
    public static boolean sameBand(int a, int b) {
        return (a <= 14) == (b <= 14);
    }
}

package com.nikonpreset.usbprobe.ncp;

import java.nio.charset.StandardCharsets;

/** Hand-authored test bytes only; no user's NCP, curve or camera preset is embedded here. */
public final class NcpCompatibilityFixtures {
    private NcpCompatibilityFixtures() {}

    public static byte[] ncp() {
        byte[] bytes = new byte[638];
        bytes[0] = 'N'; bytes[1] = 'C'; bytes[2] = 'P';
        bytes[7] = 1; bytes[11] = 36;
        bytes[12] = '0'; bytes[13] = '1'; bytes[14] = '0'; bytes[15] = '0';
        byte[] name = "HOST NCP".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, bytes, 16, name.length);
        bytes[36] = 0x03; bytes[37] = (byte) 0xc2; // Explicit legacy base maps to wire base 2.
        bytes[38] = 0; bytes[39] = (byte) 0xff;
        bytes[40] = (byte) 0x82; // Sharpening 2; quarter-unit wire value 8.
        bytes[41] = 1; bytes[42] = 1; // Supported compatibility subset; explicit output adaptations are zero.
        bytes[43] = 0x7f; bytes[44] = (byte) 0x83; // Saturation -1 and hue +3.
        bytes[45] = bytes[46] = bytes[47] = (byte) 0xff;
        bytes[51] = 2; bytes[54] = 2; bytes[55] = 0x42; // Record 2, 578-byte curve.
        System.arraycopy(curve(), 0, bytes, 56, 578);
        return bytes;
    }

    public static byte[] expectedData(int middleQuarter, int clarityQuarter) {
        byte[] bytes = new byte[614];
        bytes[0] = 2;
        byte[] name = "HOST NCP".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, bytes, 2, name.length);
        byte[] explicit = {0, 0, 0, 8, (byte) middleQuarter, (byte) clarityQuarter, 0, 0, -4, 12, 0, 0, 0, 1};
        System.arraycopy(explicit, 0, bytes, 22, explicit.length);
        System.arraycopy(curve(), 0, bytes, 36, 578);
        return bytes;
    }

    public static byte[] curve() {
        byte[] bytes = new byte[578];
        bytes[0] = 0x49; bytes[1] = 0x30;
        bytes[3] = bytes[5] = (byte) 0xff;
        bytes[6] = 1; bytes[8] = 2;
        bytes[11] = bytes[12] = (byte) 0xff;
        for (int i = 0; i <= 256; i++) {
            int value = Math.min(i * 128, 32767);
            bytes[64 + 2 * i] = (byte) (value >>> 8);
            bytes[65 + 2 * i] = (byte) value;
        }
        return bytes;
    }
}

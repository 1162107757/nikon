package com.nikonpreset.usbprobe.updates;

import java.net.URI;

/** Checks shared by network metadata, persistent state and the installer boundary. */
public final class UpdatePolicy {
    private UpdatePolicy() {}
    public static final long MAX_APK_BYTES = 4L * 1024 * 1024;
    public static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    public static boolean newer(long installed, long candidate) { return installed > 0 && candidate > installed; }
    public static boolean compatible(int sdk, int minSdk) { return minSdk >= 26 && sdk >= minSdk; }
    public static boolean trustedUrl(String base, String value, String id) {
        try {
            URI origin = new URI(base), target = new URI(value);
            return "https".equals(target.getScheme()) && origin.getHost().equals(target.getHost())
                    && target.getPort() == origin.getPort() && target.getUserInfo() == null
                    && target.getRawQuery() == null && target.getRawFragment() == null
                    && id.matches("[0-9a-fA-F-]{36}")
                    && target.getRawPath().equals(origin.getRawPath() + "/releases/" + id + "/file");
        } catch (Exception ignored) { return false; }
    }
    public static int percent(long received, long total) {
        if (total <= 0 || received <= 0) return 0;
        return (int) Math.min(100L, Math.round(Math.min(received, total) * 100d / total));
    }
}

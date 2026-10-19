package com.nikonpreset.usbprobe.connection;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.PowerManager;

/** Read actual OS protection settings; a running foreground service alone is not a Doze exemption. */
public final class ConnectionProtection {
    static final String CHANNEL = "camera-connection";
    private ConnectionProtection() { }

    public static boolean batteryExempt(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static boolean notificationsVisible(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    public static String settingsLabel(Context context) {
        if (!batteryExempt(context)) return "省电受限";
        return notificationsVisible(context) ? "已开启" : "通知未开启";
    }
}

package com.nikonpreset.usbprobe.connection;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.widget.Toast;
import com.nikonpreset.usbprobe.MainActivity;
import com.nikonpreset.usbprobe.R;

/** Visible connected-device service; connections outlive the Activity, not explicit disconnect. */
public final class CameraConnectionService extends Service {
    private static final String CHANNEL = "camera-connection";
    private static final String DISCONNECT = "com.nikonpreset.usbprobe.DISCONNECT_CAMERA";
    private static final int NOTIFICATION_ID = 41;
    private CameraConnectionRuntime runtime;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private boolean idleStop, initialized;
    private final Runnable observer = this::update;

    @Override public void onCreate() {
        super.onCreate(); runtime = CameraConnectionRuntime.get(this);
        try {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "相机连接", NotificationManager.IMPORTANCE_LOW));
        Notification initial = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION_ID, initial);
        PowerManager power = getSystemService(PowerManager.class);
        cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nikon:camera-connection");
        cpu.setReferenceCounted(false); cpu.acquire();
        WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (manager != null) try {
            // Modern low-latency locks have foreground/screen restrictions; background
            // lifetime comes from the connected-device service and its CPU wake lock.
            int mode = Build.VERSION.SDK_INT >= 34 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifi = manager.createWifiLock(mode, "nikon:camera-wifi"); wifi.setReferenceCounted(false); wifi.acquire();
        } catch (RuntimeException optionalLockUnavailable) { wifi = null; }
        runtime.addObserver(observer); runtime.startHeartbeat(); initialized = true; update();
        } catch (RuntimeException failed) {
            runtime.serviceFailed(failed); releaseLocks(); stopSelf();
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && DISCONNECT.equals(intent.getAction())) {
            if (runtime.hasOperations()) Toast.makeText(this, "相机任务进行中，请完成后断开", Toast.LENGTH_SHORT).show();
            else runtime.disconnect();
        }
        update(); return START_NOT_STICKY;
    }
    private void update() {
        if (!initialized) return;
        if (!runtime.needsService()) { idleStop = true; stopSelf(); return; }
        // Once this instance has requested an idle stop, do not turn it into an
        // unexpected-stop cleanup when a newer connection arrives before onDestroy.
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_camera)
                .setContentTitle("相机连接保持中").setContentText("切到后台或锁屏后继续维护连接")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE);
        if (runtime != null && !runtime.hasOperations()) {
            PendingIntent disconnect = PendingIntent.getService(this, 1,
                    new Intent(this, CameraConnectionService.class).setAction(DISCONNECT),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "断开连接", disconnect).build());
        }
        return builder.build();
    }
    @Override public void onDestroy() {
        if (runtime != null) {
            runtime.removeObserver(observer); runtime.stopHeartbeat();
            if (!idleStop) runtime.abortConnections();
            else if (runtime.needsService()) {
                try { startForegroundService(new Intent(this, CameraConnectionService.class)); }
                catch (RuntimeException failed) { runtime.serviceFailed(failed); }
            }
        }
        releaseLocks();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    private void releaseLocks() {
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (cpu != null && cpu.isHeld()) cpu.release();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

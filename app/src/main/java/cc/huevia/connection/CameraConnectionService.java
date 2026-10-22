package cc.huevia.connection;

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
import cc.huevia.MainActivity;
import cc.huevia.R;
import cc.huevia.album.CameraWifiNetwork;

/** Visible connected-device service; connections outlive the Activity, not explicit disconnect. */
public final class CameraConnectionService extends Service {
    private static final String CHANNEL = ConnectionProtection.CHANNEL;
    private static final String DISCONNECT = "cc.huevia.DISCONNECT_CAMERA";
    private static final String STOP_LIVE = "cc.huevia.STOP_PHOTO_LIVE";
    private static final int NOTIFICATION_ID = 41;
    private CameraConnectionRuntime runtime;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private CameraWifiRetention wifiRetention;
    private boolean idleStop, initialized, photoTypesTimedOut, photoTimeoutStop;
    private final Runnable observer = this::scheduleUpdate;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean liveUpdatePending;
    private final Runnable photoLiveObserver = this::scheduleUpdate;
    private void scheduleUpdate() {
        if (liveUpdatePending) return;
        liveUpdatePending = true;
        main.postDelayed(() -> { liveUpdatePending = false; update(); }, 500);
    }

    @Override public void onCreate() {
        super.onCreate(); runtime = CameraConnectionRuntime.get(this);
        try {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "相机连接", NotificationManager.IMPORTANCE_LOW));
        Notification initial = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, initial, foregroundTypes());
        else startForeground(NOTIFICATION_ID, initial);
        PowerManager power = getSystemService(PowerManager.class);
        cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nikon:camera-connection");
        cpu.setReferenceCounted(false); cpu.acquire();
        wifiRetention = new CameraWifiRetention(this);
        wifiRetention.setRequired(!runtime.wifiSession.usesSpecificRoute() && runtime.hasWirelessServiceWork() ? CameraWifiNetwork.socketNetwork() : null);
        WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (manager != null) try {
            // Android 14 also maps HIGH_PERF to LOW_LATENCY. This lock is only a
            // foreground latency optimization; the service-owned NetworkRequest
            // above retains no-Internet Wi-Fi independently of Activity visibility.
            int mode = Build.VERSION.SDK_INT >= 34 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifi = manager.createWifiLock(mode, "nikon:camera-wifi"); wifi.setReferenceCounted(false); wifi.acquire();
        } catch (RuntimeException optionalLockUnavailable) { wifi = null; }
        runtime.addObserver(observer); runtime.album.addPhotoLiveObserver(photoLiveObserver);
        runtime.photoPresets.addObserver(photoLiveObserver); runtime.photoSaver.addObserver(photoLiveObserver);
        runtime.startHeartbeat(); initialized = true; update();
        } catch (RuntimeException failed) {
            runtime.serviceFailed(failed); releaseLocks(); stopSelf();
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("photo-task", false)) { photoTypesTimedOut = false; photoTimeoutStop = false; }
        if (intent != null && DISCONNECT.equals(intent.getAction())) {
            if (runtime.hasOperations() || runtime.photoPresets.state().active || runtime.album.isDeletingDownloadedPhotos()) Toast.makeText(this, "照片任务进行中，请完成或取消后断开", Toast.LENGTH_SHORT).show();
            else runtime.disconnect();
        }
        if (intent != null && STOP_LIVE.equals(intent.getAction())) runtime.album.stopPhotoLive();
        update(); return START_NOT_STICKY;
    }
    private void update() {
        if (!initialized) return;
        wifiRetention.setRequired(!runtime.wifiSession.usesSpecificRoute() && runtime.hasWirelessServiceWork() ? CameraWifiNetwork.socketNetwork() : null);
        if (photoTypesTimedOut && !runtime.hasCameraServiceWork()) { photoTimeoutStop = true; idleStop = true; stopSelf(); return; }
        if (!runtime.needsService()) { idleStop = true; stopSelf(); return; }
        // Once this instance has requested an idle stop, do not turn it into an
        // unexpected-stop cleanup when a newer connection arrives before onDestroy.
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), foregroundTypes());
            else getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        } catch (RuntimeException unavailable) { runtime.serviceFailed(unavailable); stopSelf(); }
    }
    private int foregroundTypes() {
        int types = runtime.hasCameraServiceWork() ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : 0;
        if (photoTypesTimedOut) return types == 0 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : types;
        if (runtime.photoPresets.state().active) types |= Build.VERSION.SDK_INT >= 35
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        if (runtime.hasPhotoTasks() && (runtime.photoSaver.isBusy() || !runtime.photoPresets.state().active)) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }
        return types == 0 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : types;
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        cc.huevia.album.PhotoLiveState live = runtime == null ? null : runtime.album.photoLiveState();
        boolean liveActive = live != null && (live.enabled || live.starting || live.receiving);
        cc.huevia.album.PhotoPresetBatchState batch = runtime == null ? null : runtime.photoPresets.state();
        boolean processing = batch != null && batch.active;
        boolean deleting = runtime != null && runtime.album.isDeletingDownloadedPhotos();
        String title = deleting ? "正在删除照片" : processing ? "正在套用预设" : runtime != null && runtime.photoSaver.isBusy() && !liveActive
                ? "正在保存到相册" : liveActive ? "照片直播" : "相机连接保持中";
        String summary = liveActive ? (live.starting ? "正在准备接收新照片" : live.receiving ? "正在保存原图" : "等待拍摄")
                + " · 已保存 " + live.completed + " 张" + (live.queued > 0 ? " · 待传 " + live.queued + " 张" : "")
                : "切到后台或锁屏后继续维护连接";
        if (deleting) summary = "正在移除手机内文件和系统相册副本";
        else if (processing) summary = batch.presetName + " · 已完成 " + batch.completed + " / " + batch.total
                + (liveActive ? " · 照片直播继续接收" : "");
        else if (runtime != null && runtime.photoSaver.isBusy() && !liveActive) summary = "原图保留，完成后可在系统相册查看";
        if (!ConnectionProtection.batteryExempt(this)) summary += " · 锁屏保护未开启";
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_camera)
                .setContentTitle(title).setContentText(summary)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE);
        if (runtime != null && !runtime.hasOperations() && !processing && !deleting) {
            if (live != null && live.enabled) {
                PendingIntent stop = PendingIntent.getService(this, 2,
                        new Intent(this, CameraConnectionService.class).setAction(STOP_LIVE),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.addAction(new Notification.Action.Builder(null, "停止直播", stop).build());
            }
            PendingIntent disconnect = PendingIntent.getService(this, 1,
                    new Intent(this, CameraConnectionService.class).setAction(DISCONNECT),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "断开连接", disconnect).build());
        }
        return builder.build();
    }
    @Override public void onTimeout(int startId, int foregroundServiceType) {
        photoTypesTimedOut = true;
        runtime.photoServiceTimedOut();
        if (runtime.hasCameraServiceWork()) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else { photoTimeoutStop = true; idleStop = true; stopSelf(); }
    }
    @Override public void onDestroy() {
        if (runtime != null) {
            runtime.removeObserver(observer); runtime.stopHeartbeat();
            runtime.album.removePhotoLiveObserver(photoLiveObserver);
            runtime.photoPresets.removeObserver(photoLiveObserver); runtime.photoSaver.removeObserver(photoLiveObserver);
            if (!idleStop) runtime.abortConnections();
            else if (!photoTimeoutStop && runtime.needsService()) {
                try { startForegroundService(new Intent(this, CameraConnectionService.class)); }
                catch (RuntimeException failed) { runtime.serviceFailed(failed); }
            }
        }
        releaseLocks();
        main.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    private void releaseLocks() {
        if (wifiRetention != null) wifiRetention.close();
        try { if (wifi != null && wifi.isHeld()) wifi.release(); }
        finally { if (cpu != null && cpu.isHeld()) cpu.release(); }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

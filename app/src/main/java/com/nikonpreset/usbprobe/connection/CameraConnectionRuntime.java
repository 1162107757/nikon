package com.nikonpreset.usbprobe.connection;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import com.nikonpreset.usbprobe.album.AlbumController;
import com.nikonpreset.usbprobe.usb.UsbPictureControlProbe;
import com.nikonpreset.usbprobe.usb.WirelessPictureControl;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Application-owned connections. No Activity, View or Activity callback is retained here. */
public final class CameraConnectionRuntime {
    public static final Object PERSISTENCE_LOCK = new Object();
    private static CameraConnectionRuntime instance;
    public static synchronized CameraConnectionRuntime get(Context context) {
        if (instance == null) instance = new CameraConnectionRuntime(context.getApplicationContext());
        return instance;
    }

    public static final class HeldConnection {
        public final WirelessPictureControl owner;
        public final String host, model;
        public final int port;
        HeldConnection(WirelessPictureControl owner, String host, int port, String model) {
            this.owner = owner; this.host = host; this.port = port; this.model = model;
        }
    }
    public static final class ImportStatus {
        public final String heading, detail, presetName, camera, sourceSha256, pendingJournalJson;
        public final int target;
        public final boolean verified, uncertain;
        public ImportStatus(String heading, String detail, String presetName, String camera, int target,
                            boolean verified, boolean uncertain, String sourceSha256) {
            this(heading, detail, presetName, camera, target, verified, uncertain, sourceSha256, "");
        }
        public ImportStatus(String heading, String detail, String presetName, String camera, int target,
                            boolean verified, boolean uncertain, String sourceSha256, String pendingJournalJson) {
            this.heading = heading; this.detail = detail; this.presetName = presetName;
            this.camera = camera; this.target = target; this.verified = verified;
            this.uncertain = uncertain; this.sourceSha256 = sourceSha256;
            this.pendingJournalJson = pendingJournalJson == null ? "" : pendingJournalJson;
        }
    }
    public final class OperationLease implements AutoCloseable {
        private final long epoch;
        private WirelessPictureControl owner;
        private boolean released;
        private OperationLease(long epoch) { this.epoch = epoch; }
        public boolean isActive() {
            synchronized (CameraConnectionRuntime.this) { return !released && epoch == generation; }
        }
        public boolean bind(WirelessPictureControl connection) {
            synchronized (CameraConnectionRuntime.this) {
                if (released || epoch != generation || (owner != null && owner != connection)) { connection.close(); return false; }
                owner = connection; return true;
            }
        }
        @Override public void close() {
            synchronized (CameraConnectionRuntime.this) {
                if (released) return;
                released = true; leases.remove(this);
            }
            changed();
        }
    }

    private final Context context;
    public final ExecutorService worker = Executors.newSingleThreadExecutor();
    public final ExecutorService cameraWorker = Executors.newSingleThreadExecutor();
    public final AlbumController album;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Runnable> observers = new HashSet<>();
    private final Set<OperationLease> leases = new HashSet<>();
    private final AtomicBoolean heartbeatPending = new AtomicBoolean();
    private HeldConnection prepared;
    private long generation;
    private int cleanupTasks;
    private volatile String notice = "", verifiedReport = "";
    private volatile long noticeRevision;
    private volatile WirelessPictureControl verifiedOwner;
    private volatile ImportStatus importStatus;
    private boolean heartbeatRunning;

    private CameraConnectionRuntime(Context context) {
        this.context = context;
        album = new AlbumController(context);
        album.setLifecycleObserver(this::changed);
    }
    /** Called from a user-visible action before queueing network work. */
    public OperationLease beginOperation() {
        OperationLease lease;
        synchronized (this) { lease = new OperationLease(generation); leases.add(lease); }
        try { context.startForegroundService(new Intent(context, CameraConnectionService.class)); }
        catch (RuntimeException failure) { lease.close(); throw failure; }
        changed(); return lease;
    }
    public synchronized boolean hold(OperationLease lease, WirelessPictureControl owner, String host, int port, String model) {
        if (lease == null || lease.released || lease.epoch != generation || lease.owner != owner) { owner.close(); return false; }
        prepared = new HeldConnection(owner, host, port, model);
        changed(); return true;
    }
    public synchronized HeldConnection held() { return prepared; }
    public synchronized void forget(WirelessPictureControl owner) {
        if (prepared != null && prepared.owner == owner) prepared = null;
        changed();
    }
    public synchronized boolean hasOperations() { return !leases.isEmpty() || cleanupTasks > 0; }
    public synchronized boolean needsService() {
        return !leases.isEmpty() || cleanupTasks > 0 || prepared != null || album.isConnected() || album.isConnecting();
    }
    public void addObserver(Runnable observer) { synchronized (this) { observers.add(observer); } }
    public void removeObserver(Runnable observer) { synchronized (this) { observers.remove(observer); } }
    public String notice() { return notice; }
    public long noticeRevision() { return noticeRevision; }
    public ImportStatus importStatus() { return importStatus; }
    public void publishImportStatus(ImportStatus status) { importStatus = status; changed(); }
    public void clearImportStatus() { importStatus = null; }
    public void beginImport() { verifiedOwner = null; verifiedReport = ""; importStatus = null; }
    public void recordVerifiedImport(WirelessPictureControl owner, String json) {
        verifiedOwner = owner; verifiedReport = json;
    }
    public boolean hasVerifiedImport(WirelessPictureControl owner) { return verifiedOwner == owner; }
    public void saveConnectionFailure(WirelessPictureControl owner, UsbPictureControlProbe.Result partial) {
        saveConnectionReport(owner, partial, "postImportConnectionFailure");
    }
    private void saveConnectionReport(WirelessPictureControl owner, UsbPictureControlProbe.Result partial, String field) {
        synchronized (PERSISTENCE_LOCK) {
        try {
            JSONObject data = verifiedOwner == owner && !verifiedReport.isEmpty()
                    ? new JSONObject(verifiedReport).put(field, partial.toJson()) : partial.toJson();
            AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "last-report.json"));
            FileOutputStream output = null;
            try {
                output = file.startWrite(); output.write(data.toString(2).getBytes(StandardCharsets.UTF_8));
                output.getFD().sync(); file.finishWrite(output);
            } catch (Exception failure) { if (output != null) file.failWrite(output); }
        } catch (Exception ignored) { }
        }
    }
    public void changed() {
        main.post(() -> {
            ArrayList<Runnable> snapshot;
            synchronized (this) { snapshot = new ArrayList<>(observers); }
            for (Runnable observer : snapshot) observer.run();
        });
    }
    public void startHeartbeat() {
        synchronized (this) { if (heartbeatRunning) return; heartbeatRunning = true; }
        main.postDelayed(heartbeat, 10_000);
    }
    public void stopHeartbeat() {
        synchronized (this) { heartbeatRunning = false; }
        main.removeCallbacks(heartbeat);
    }
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            synchronized (CameraConnectionRuntime.this) { if (!heartbeatRunning) return; }
            if (!hasOperations() && heartbeatPending.compareAndSet(false, true)) {
                HeldConnection held = held();
                if (held != null) cameraWorker.execute(() -> {
                    try {
                        if (hasOperations() || held() != held) return;
                        held.owner.keepPreparedAlive();
                    } catch (Exception error) {
                        if (error instanceof UsbPictureControlProbe.ProbeException
                                && WirelessPictureControl.isRecoverableKeepAliveResponse(((UsbPictureControlProbe.ProbeException) error).responseCode)
                                && held.owner.isPrepared()) {
                            UsbPictureControlProbe.Result partial = ((UsbPictureControlProbe.ProbeException) error).partialResult;
                            if (partial != null) {
                                saveConnectionReport(held.owner, partial, "postImportKeepAliveWarning");
                                synchronized (CameraConnectionRuntime.this) { if (prepared == held) noticeRevision++; }
                                changed();
                            }
                            return;
                        }
                        if (error instanceof UsbPictureControlProbe.ProbeException) {
                            UsbPictureControlProbe.Result partial = ((UsbPictureControlProbe.ProbeException) error).partialResult;
                            if (partial != null) saveConnectionFailure(held.owner, partial);
                        }
                        synchronized (CameraConnectionRuntime.this) {
                            if (prepared == held) {
                                prepared = null;
                                notice = verifiedOwner == held.owner
                                        ? "当前连接已断开，已读回核对的预设仍已保存，无需重复导入。"
                                        : "相机连接已断开，请重新连接后继续。";
                                noticeRevision++;
                            }
                        }
                        changed();
                    } finally { heartbeatPending.set(false); }
                });
                else if (album.isConnected()) {
                    final com.nikonpreset.usbprobe.album.ConnectionInfo identity = album.connectionInfo();
                    album.keepAlive(new AlbumController.Callback<Void>() {
                    @Override public void onSuccess(Void ignored) { heartbeatPending.set(false); }
                    @Override public void onError(String error) {
                        heartbeatPending.set(false);
                        if (hasOperations() || held() != null || album.isConnecting() || album.isConnected()) return;
                        if (album.connectionInfo() != null && album.connectionInfo() != identity) return;
                        notice = "相机连接已断开，请重新连接后继续。"; noticeRevision++; changed();
                    }
                    });
                }
                else heartbeatPending.set(false);
            }
            main.postDelayed(this, 10_000);
        }
    };
    /** Explicit disconnect or service destruction; never reconnects or retries a write. */
    public void disconnect() {
        disconnect(true);
    }
    public void serviceFailed(Exception failure) {
        notice = "后台连接保护未能启动，请重新打开 App 后连接。"; noticeRevision++;
        disconnect(false);
    }
    public void abortConnections() { disconnect(false); }
    private void disconnect(boolean graceful) {
        Set<WirelessPictureControl> owners = new HashSet<>();
        synchronized (this) {
            generation++;
            if (prepared != null) owners.add(prepared.owner);
            prepared = null;
            for (OperationLease lease : leases) { if (lease.owner != null) owners.add(lease.owner); lease.released = true; }
            leases.clear();
        }
        album.disconnect();
        for (WirelessPictureControl owner : owners) {
            if (graceful && owner.isPrepared()) {
                synchronized (this) { cleanupTasks++; }
                cameraWorker.execute(() -> {
                    try { owner.releasePrepared(); } catch (Exception ignored) { }
                    finally { owner.close(); synchronized (CameraConnectionRuntime.this) { cleanupTasks--; } changed(); }
                });
            }
            else owner.close();
        }
        changed();
    }
}

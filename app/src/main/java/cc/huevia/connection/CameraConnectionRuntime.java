package cc.huevia.connection;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import cc.huevia.album.AlbumController;
import cc.huevia.album.ConnectionInfo;
import cc.huevia.album.PtpIpClient;
import cc.huevia.album.PhotoLiveGallery;
import cc.huevia.album.PhotoAlbumSaver;
import cc.huevia.album.PhotoPresetBatch;
import cc.huevia.album.PhotoProjectPresets;
import cc.huevia.album.PhotoStars;
import cc.huevia.usb.UsbPictureControlProbe;
import cc.huevia.usb.WirelessPictureControl;
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
import java.util.concurrent.atomic.AtomicReference;

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
        private final boolean photoTask;
        private WirelessPictureControl owner;
        private boolean released;
        private OperationLease(long epoch, boolean photoTask) { this.epoch = epoch; this.photoTask = photoTask; }
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
    public final PhotoAlbumSaver photoSaver;
    public final PhotoPresetBatch photoPresets;
    public final PhotoProjectPresets projectPresets;
    public final CameraWifiSession wifiSession;
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
    private volatile boolean preparingAlbum;
    private volatile String albumPreparationError = "";
    private long albumRequest;
    private Object attemptedAlbumIdentity;
    private final AtomicReference<PtpIpClient> pendingAlbumHandoff = new AtomicReference<>();
    private boolean heartbeatRunning;
    private String downloadRecoveryOwner;
    private boolean photoPresetActive;
    private long observedWifiConnection = -1, invalidatedWifiConnection = -1;

    private CameraConnectionRuntime(Context context) {
        this.context = context;
        // Queue runtime reconciliation so construction and NetworkCallback delivery
        // never call into a half-initialized album or retain an Activity listener.
        wifiSession = new CameraWifiSession(context, () -> main.post(this::wifiNetworkChanged));
        album = new AlbumController(context);
        photoSaver = PhotoAlbumSaver.get(context);
        photoPresets = new PhotoPresetBatch(context, album);
        projectPresets = new PhotoProjectPresets(context, album, photoPresets, new PhotoProjectPresets.WorkHost() {
            @Override public AutoCloseable beginPhotoWork() { return beginPhotoOperation(); }
            @Override public void onChanged() { changed(); }
        });
        album.setPhotoLiveOriginalObserver(projectPresets::onOriginalSaved);
        album.setOriginalDownloadIdentityObserver(PhotoStars.get(context)::bindDownloaded);
        album.setPhotoLiveAsyncPublisher(photoSaver::save);
        album.setOriginalDownloadObserver(photo -> photoSaver.save(photo, new AlbumController.Callback<String>() {
            @Override public void onSuccess(String uri) { }
            @Override public void onError(String error) { }
        }));
        photoPresets.setLifecycleObserver(() -> {
            boolean active = photoPresets.isActive();
            if (active != photoPresetActive) { photoPresetActive = active; changed(); }
        });
        album.setLifecycleObserver(this::changed);
    }
    private void wifiNetworkChanged() {
        final long token = wifiSession.token();
        if (wifiSession.isConnected()) observedWifiConnection = token;
        if (!wifiSession.isRouteUnavailable() || invalidatedWifiConnection == token) { changed(); return; }
        invalidatedWifiConnection = token;
        Set<WirelessPictureControl> owners = new HashSet<>();
        final PtpIpClient handoff;
        final boolean hadAlbum = album.hasLiveSession() || album.isConnecting() || preparingAlbum
                || album.photoLiveState().enabled || album.photoLiveState().starting || album.photoLiveState().receiving;
        synchronized (this) {
            // No generation-wide disconnect: local saving, effects and unrelated
            // USB leases continue. Only handles bound to wireless owners retire.
            if (prepared != null) { owners.add(prepared.owner); prepared = null; }
            java.util.Iterator<OperationLease> iterator = leases.iterator();
            while (iterator.hasNext()) {
                OperationLease lease = iterator.next();
                if (lease.owner != null) { owners.add(lease.owner); lease.released = true; iterator.remove(); }
            }
            handoff = pendingAlbumHandoff.getAndSet(null);
            if (hadAlbum || handoff != null || !owners.isEmpty()) {
                albumRequest++; preparingAlbum = false; attemptedAlbumIdentity = null;
                albumPreparationError = "相机 Wi-Fi 已断开，请重新连接后继续。";
            }
        }
        if (handoff != null) handoff.close();
        for (WirelessPictureControl owner : owners) owner.close(); // socket close only; never CloseSession.
        if (hadAlbum) album.disconnectForConnectionLoss(); // retires old I/O; unfinished read-only downloads remain retryable.
        if (hadAlbum || handoff != null || !owners.isEmpty() || observedWifiConnection == token) {
            notice = "相机 Wi-Fi 已断开，请确认相机热点仍已开启后重新连接。已保存照片和本地处理任务保留。";
            noticeRevision++;
            cc.huevia.album.ConnectionTrace.mark("WIFI_RUNTIME_HANDLES_INVALIDATED");
        }
        changed();
    }
    /** Owner observation shares the same lifetime as the memory-only download plan. */
    public boolean observeDownloadRecoveryOwner(String owner) {
        String current = owner == null ? "" : owner;
        final boolean changed;
        synchronized (this) {
            changed = downloadRecoveryOwner != null && !downloadRecoveryOwner.equals(current);
            downloadRecoveryOwner = current;
        }
        if (changed) album.clearDownloadRecovery();
        return changed;
    }

    /** Called from a user-visible action before queueing network work. */
    public OperationLease beginOperation() {
        return beginOperation(false);
    }
    /** Local photo work uses its own foreground type and never suppresses camera heartbeats. */
    public OperationLease beginPhotoOperation() {
        return beginOperation(true);
    }
    private OperationLease beginOperation(boolean photoTask) {
        OperationLease lease;
        synchronized (this) { lease = new OperationLease(generation, photoTask); leases.add(lease); }
        try { context.startForegroundService(new Intent(context, CameraConnectionService.class).putExtra("photo-task", photoTask)); }
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
    public synchronized boolean hasOperations() {
        if (cleanupTasks > 0) return true;
        for (OperationLease lease : leases) if (!lease.photoTask) return true;
        return false;
    }
    public synchronized boolean hasPhotoTasks() {
        if (photoSaver.isBusy() || photoPresets.isActive() || album.isDeletingDownloadedPhotos()
                || projectPresets != null && projectPresets.hasWork()) return true;
        for (OperationLease lease : leases) if (lease.photoTask) return true;
        return false;
    }
    public synchronized boolean hasCameraServiceWork() {
        return wifiSession.isActive() || hasOperations() || prepared != null || album.hasLiveSession() || album.isConnecting()
                || album.photoLiveState().starting || album.photoLiveState().receiving;
    }
    /** Camera Wi-Fi is retained for the physical session, including idle time between photos. */
    public synchronized boolean hasWirelessServiceWork() {
        return wifiSession.isActive() || prepared != null || pendingAlbumHandoff.get() != null || preparingAlbum
                || album.hasLiveSession() || album.isConnecting();
    }
    public synchronized boolean needsService() {
        return hasCameraServiceWork() || hasPhotoTasks();
    }
    public boolean isPreparingAlbum() { return preparingAlbum; }
    public String albumPreparationError() { return albumPreparationError; }

    /** Start from a user-visible destination; all ownership and completion live outside the Activity. */
    public boolean prepareAlbumFromCurrent(boolean retry) {
        final HeldConnection source;
        synchronized (this) {
            if (preparingAlbum || album.isConnecting() || album.isConnected()) return true;
            if (hasOperations() || (importStatus != null && importStatus.uncertain)) return false;
            source = prepared;
            Object identity = source == null ? album.sessionConnectionInfo() : source.owner;
            if (!retry && identity == attemptedAlbumIdentity && !albumPreparationError.isEmpty()) return false;
            if (album.hasLiveSession()) {
                if (!retry && !album.browseError().isEmpty()) return false;
            } else {
                if (source == null || !source.owner.isPrepared()) return false;
            }
            attemptedAlbumIdentity = identity;
        }
        final OperationLease lease;
        try { lease = beginOperation(); }
        catch (RuntimeException failure) { albumPreparationError = "无法准备照片浏览：" + failure.getMessage(); changed(); return false; }
        if (source != null && !lease.bind(source.owner)) { lease.close(); return false; }
        final long request;
        synchronized (this) {
            if (!lease.isActive() || preparingAlbum || (source != null && prepared != source)) { lease.close(); return false; }
            preparingAlbum = true; albumPreparationError = "";
            request = ++albumRequest;
        }
        changed();
        final AtomicBoolean settled = new AtomicBoolean();
        AlbumController.Callback<ConnectionInfo> callback = new AlbumController.Callback<ConnectionInfo>() {
            private void finish(String error) {
                if (!settled.compareAndSet(false, true)) return;
                synchronized (CameraConnectionRuntime.this) {
                    if (request == albumRequest) {
                        preparingAlbum = false; albumPreparationError = error;
                        if (source != null && prepared == source && !source.owner.isPrepared()) prepared = null;
                    }
                }
                lease.close(); changed();
            }
            @Override public void onSuccess(ConnectionInfo value) { finish(""); }
            @Override public void onError(String error) { finish(error == null ? "照片读取未完成，请重试。" : error); }
        };
        try {
            cameraWorker.execute(() -> {
                PtpIpClient moved = null;
                try {
                    if (!lease.isActive()) { callback.onError("连接操作已取消。"); return; }
                    if (source == null) { album.retryBrowse(callback); return; }
                    synchronized (CameraConnectionRuntime.this) {
                        if (prepared != source || request != albumRequest) throw new java.io.IOException("相机连接已变化，请重新打开相册。");
                    }
                    moved = source.owner.transferPreparedToAlbum();
                    synchronized (CameraConnectionRuntime.this) {
                        if (!lease.isActive() || request != albumRequest) throw new java.io.IOException("连接操作已取消。");
                        if (!pendingAlbumHandoff.compareAndSet(null, moved)) throw new java.io.IOException("已有照片连接正在准备。");
                        if (prepared == source) prepared = null;
                    }
                    changed();
                    boolean accepted = album.connectFromHandoff(moved, source.host, source.port, callback);
                    if (accepted) pendingAlbumHandoff.compareAndSet(moved, null);
                    else if (pendingAlbumHandoff.compareAndSet(moved, null)) moved.close();
                    moved = null; // The controller, or the rejected handoff's cleanup, owns completion.
                } catch (Exception failure) {
                    if (moved != null) {
                        pendingAlbumHandoff.compareAndSet(moved, null); moved.close();
                    }
                    callback.onError(failure.getMessage());
                }
            });
        } catch (RuntimeException failure) { callback.onError(failure.getMessage()); }
        return true;
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
                else if (album.hasLiveSession()) {
                    final cc.huevia.album.ConnectionInfo identity = album.sessionConnectionInfo();
                    album.keepAlive(new AlbumController.Callback<Void>() {
                    @Override public void onSuccess(Void ignored) { heartbeatPending.set(false); }
                    @Override public void onError(String error) {
                        heartbeatPending.set(false);
                        if (hasOperations() || held() != null || album.isConnecting() || album.hasLiveSession()) return;
                        if (album.sessionConnectionInfo() != null && album.sessionConnectionInfo() != identity) return;
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
    /** A media-processing time limit must never terminate a healthy camera session. */
    public void photoServiceTimedOut() {
        projectPresets.pause("自动套用达到系统后台时限，已保存原图和效果保留，请手动重试未完成项。");
        photoPresets.cancel();
        synchronized (this) {
            java.util.Iterator<OperationLease> iterator = leases.iterator();
            while (iterator.hasNext()) {
                OperationLease lease = iterator.next();
                if (lease.photoTask) { lease.released = true; iterator.remove(); }
            }
            notice = "照片处理达到系统后台时限，已停止剩余处理。已保存照片和相机连接保留。";
            noticeRevision++;
        }
        changed();
    }
    private void disconnect(boolean graceful) {
        final long wifiToken = wifiSession.token();
        if (!graceful) {
            projectPresets.pause("后台服务已结束，自动套用未完成项已暂停，原图保留，请手动重试。");
            photoPresets.cancel();
        }
        Set<WirelessPictureControl> owners = new HashSet<>();
        synchronized (this) {
            generation++;
            albumRequest++; preparingAlbum = false; albumPreparationError = ""; attemptedAlbumIdentity = null;
            if (prepared != null) owners.add(prepared.owner);
            prepared = null;
            for (OperationLease lease : leases) { if (lease.owner != null) owners.add(lease.owner); lease.released = true; }
            leases.clear();
        }
        PtpIpClient pending = pendingAlbumHandoff.getAndSet(null);
        if (pending != null) pending.close();
        if (graceful) album.disconnect();
        else album.disconnectForConnectionLoss();
        final java.util.concurrent.atomic.AtomicInteger pendingOwners = new java.util.concurrent.atomic.AtomicInteger(owners.size());
        if (owners.isEmpty()) wifiSession.disconnect(wifiToken);
        for (WirelessPictureControl owner : owners) {
            if (graceful && owner.isPrepared()) {
                synchronized (this) { cleanupTasks++; }
                cameraWorker.execute(() -> {
                    try { owner.releasePrepared(); } catch (Exception ignored) { }
                    finally {
                        owner.close();
                        if (pendingOwners.decrementAndGet() == 0) wifiSession.disconnect(wifiToken);
                        synchronized (CameraConnectionRuntime.this) { cleanupTasks--; } changed();
                    }
                });
            }
            else { owner.close(); if (pendingOwners.decrementAndGet() == 0) wifiSession.disconnect(wifiToken); }
        }
        changed();
    }
}

package com.nikonpreset.usbprobe.album;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import com.nikonpreset.usbprobe.ptp.PtpDeviceInfo;
import com.nikonpreset.usbprobe.ptp.PtpObjectHandles;
import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import com.nikonpreset.usbprobe.ptp.PtpResponseException;
import com.nikonpreset.usbprobe.ptp.PtpProtocolException;
import com.nikonpreset.usbprobe.ptp.PtpStorageIds;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Serialized asynchronous wireless browsing. Callbacks run on the main thread. */
public final class AlbumController implements AutoCloseable {
    public static final int DEFAULT_PORT = 15740;
    public interface Callback<T> { void onSuccess(T result); void onError(String message); }
    public interface DownloadCallback extends Callback<DownloadedPhoto> { void onProgress(long received, long total); }
    public interface RawDownloadCallback extends Callback<RawPhotoDownload> {
        void onProgress(long received, long total);
        /** Discovery and local verification are real stages, never fake byte percentages. */
        default void onStage(String stage) { }
        default void onDiscoveryProgress(int checked, int total) { }
    }
    public interface PreviewCallback extends Callback<PhotoPreview> { void onProgress(long received, long total); }
    public interface PhotoLivePublisher { String publish(DownloadedPhoto photo) throws Exception; }
    public interface PhotoLiveAsyncPublisher { void publish(DownloadedPhoto photo, Callback<String> callback); }
    public interface PhotoLiveProjectSetup { void configure(PhotoLiveProject project) throws Exception; }
    public interface PhotoLiveOriginalObserver { void onOriginalSaved(String projectId, String identity, DownloadedPhoto photo); }
    public interface OriginalDownloadIdentityObserver { void onDownloaded(String identity, DownloadedPhoto photo); }
    private interface Work<T> { T run() throws Exception; }
    private static final int MAX_HANDLES = 20000;
    private static final long MAX_PHOTO_BYTES = 512L * 1024 * 1024;
    private static final int MAX_PREVIEW_BYTES = 4 * 1024 * 1024;
    private final Context context;
    private final CameraWifiNetwork wifi;
    private final PhotoPreviewCache previews;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService localDeletes = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "photo-local-delete"); thread.setDaemon(true); return thread; });
    private final ExecutorService receiptWorker = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "photo-live-receipts"); thread.setDaemon(true); return thread; });
    private volatile boolean deletingDownloadedPhotos;
    private final Set<Runnable> photoLibraryObservers = new LinkedHashSet<>();
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger previewGeneration = new AtomicInteger();
    private final Object lifecycle = new Object();
    private final Set<BrowseCompletion> pendingBrowses = new HashSet<>();
    private volatile PtpIpClient active;
    private volatile boolean closed;
    private volatile boolean transferring;
    private volatile boolean connecting;
    private volatile Runnable connectionListener;
    private volatile Runnable lifecycleObserver;
    private volatile ConnectionInfo cachedConnectionInfo;
    private volatile ConnectionInfo sessionConnectionInfo;
    private volatile String browseError = "";
    private long[] handles = new long[0]; // Worker-owned snapshot.
    private String session = "";
    private PtpDeviceInfo deviceInfo;
    private boolean largeThumbnailUnavailable; // Worker-owned, reset for each new camera session.
    private final ScheduledExecutorService liveClock = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "camera-photo-live-clock"); thread.setDaemon(true); return thread;
    });
    private volatile PhotoLiveState liveState = new PhotoLiveState(false, false, false, 0, 0, 0, 0, "", 0, Collections.emptyList());
    private volatile Runnable photoLiveListener;
    private final Set<Runnable> photoLiveObservers = new HashSet<>();
    private volatile PhotoLivePublisher photoLivePublisher;
    private volatile PhotoLiveAsyncPublisher photoLiveAsyncPublisher;
    private volatile PhotoLiveOriginalObserver photoLiveOriginalObserver;
    private volatile java.util.function.Consumer<DownloadedPhoto> originalDownloadObserver;
    private PhotoOriginalStore completedOriginals;
    private RawPhotoStore completedRaw;
    private final ExecutorService rawLocalWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "camera-raw-local"); thread.setDaemon(true); return thread;
    });
    private long[] rawHandles = new long[0];
    private final Map<Long, PtpObjectInfo> rawMetadata = new LinkedHashMap<>();
    private String rawSnapshotSession = "";
    private boolean rawInventoryComplete;
    private final AtomicBoolean rawReadReserved = new AtomicBoolean();
    private volatile OriginalDownloadIdentityObserver originalDownloadIdentityObserver;
    private LiveRun liveRun;
    private long liveRevision;
    private PhotoLiveProjectStore liveProjects; // Worker-owned local project journal.
    private PhotoLiveReceiptStore liveReceipts;
    private long receiptTimestamp;

    public PhotoLiveState photoLiveState() { return liveState; }
    public void setPhotoLiveListener(Runnable listener) { synchronized (lifecycle) { photoLiveListener = listener; } }
    public void clearPhotoLiveListener(Runnable expected) { synchronized (lifecycle) { if (photoLiveListener == expected) photoLiveListener = null; } }
    public void addPhotoLiveObserver(Runnable observer) { if (observer != null) synchronized (lifecycle) { photoLiveObservers.add(observer); } }
    public void removePhotoLiveObserver(Runnable observer) { synchronized (lifecycle) { photoLiveObservers.remove(observer); } }
    public void setPhotoLivePublisher(PhotoLivePublisher publisher) { photoLivePublisher = publisher; }
    /** Dispatches completed originals to a separate local queue without holding the camera worker. */
    public void setPhotoLiveAsyncPublisher(PhotoLiveAsyncPublisher publisher) { photoLiveAsyncPublisher = publisher; }
    public void setPhotoLiveOriginalObserver(PhotoLiveOriginalObserver observer) { photoLiveOriginalObserver = observer; }
    /** Enqueues local publication independently of Activity callbacks and the camera worker. */
    public void setOriginalDownloadObserver(java.util.function.Consumer<DownloadedPhoto> observer) { originalDownloadObserver = observer; }
    public void setOriginalDownloadIdentityObserver(OriginalDownloadIdentityObserver observer) { originalDownloadIdentityObserver = observer; }
    public boolean isDeletingDownloadedPhotos() { return deletingDownloadedPhotos; }
    public void addPhotoLibraryObserver(Runnable observer) { if (observer != null) synchronized (lifecycle) { photoLibraryObservers.add(observer); } }
    public void removePhotoLibraryObserver(Runnable observer) { synchronized (lifecycle) { photoLibraryObservers.remove(observer); } }

    /** Deletes only private completed JPEGs and their own published copies. Never issues a camera command. */
    public void deleteDownloadedPhotos(List<DownloadedPhoto> photos, Callback<PhotoDeleteResult> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        boolean nullPhoto = false;
        if (photos != null) for (DownloadedPhoto photo : photos) nullPhoto |= photo == null;
        if (photos == null || photos.isEmpty() || photos.size() > 1000 || nullPhoto) {
            main.post(() -> callback.onError("请选择 1–1000 张已下载照片。")); return;
        }
        final PhotoLocalTasks.Lease lease;
        synchronized (lifecycle) {
            boolean publishing = false;
            if (liveRun != null) for (LiveCandidate item : liveRun.recent.values()) publishing |= item.galleryPublishing;
            if (closed || deletingDownloadedPhotos || connecting || transferring || liveRun != null && (liveRun.enabled || liveRun.workQueued || liveRun.current != null || publishing)) {
                main.post(() -> callback.onError("请等待照片接收、保存或处理完成后再删除。")); return;
            }
            try { lease = PhotoLocalTasks.delete(); }
            catch (IOException busy) { main.post(() -> callback.onError(browseFailureText(busy))); return; }
            deletingDownloadedPhotos = true;
        }
        List<DownloadedPhoto> selected = Collections.unmodifiableList(new ArrayList<>(photos));
        notifyStateChanged();
        try { localDeletes.execute(() -> {
            PhotoDeleteResult result;
            try {
                try { result = new PhotoAlbumDeletion(context).delete(selected); }
                catch (Exception | OutOfMemoryError failure) {
                    List<PhotoDeleteResult.Failure> failed = new ArrayList<>();
                    for (DownloadedPhoto photo : selected) failed.add(new PhotoDeleteResult.Failure(photo, failure.getMessage() == null ? "照片删除未完成，请重试。" : failure.getMessage()));
                    result = new PhotoDeleteResult(Collections.emptyList(), failed);
                }
                Set<String> removed = new HashSet<>();
                for (DownloadedPhoto photo : result.deletedPhotos) try { removed.add(photo.file().getCanonicalPath()); } catch (IOException ignored) { }
                synchronized (lifecycle) {
                    if (liveRun != null) {
                        int count = 0; java.util.Iterator<LiveCandidate> entries = liveRun.recent.values().iterator();
                        while (entries.hasNext()) {
                            LiveCandidate item = entries.next();
                            if (item.saved != null && removed.contains(item.saved.file().getAbsolutePath())) { entries.remove(); count++; }
                        }
                        if (count > 0) { liveRun.completed = Math.max(0, liveRun.completed - count); publishLiveLocked(liveRun); }
                    }
                }
            } finally {
                synchronized (lifecycle) { deletingDownloadedPhotos = false; }
                lease.close(); notifyStateChanged();
                main.post(() -> {
                    List<Runnable> observers; synchronized (lifecycle) { observers = new ArrayList<>(photoLibraryObservers); }
                    for (Runnable observer : observers) try { observer.run(); } catch (RuntimeException ignored) { }
                });
            }
            PhotoDeleteResult finished = result;
            main.post(() -> callback.onSuccess(finished));
        }); } catch (java.util.concurrent.RejectedExecutionException stopped) {
            synchronized (lifecycle) { deletingDownloadedPhotos = false; } lease.close(); notifyStateChanged();
            main.post(() -> callback.onError("本地照片删除队列已关闭。"));
        }
    }

    public void listPhotoLiveProjects(Callback<List<PhotoLiveProject>> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        if (closed) { main.post(() -> callback.onError("相册已关闭。")); return; }
        try { worker.execute(() -> {
            try {
                List<PhotoLiveProject> projects;
                try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.use()) { projects = projectStore().list(); }
                main.post(() -> { if (!closed) callback.onSuccess(projects); });
            } catch (Exception failure) { main.post(() -> { if (!closed) callback.onError(browseFailureText(failure)); }); }
        }); } catch (java.util.concurrent.RejectedExecutionException failure) { main.post(() -> callback.onError("相册已关闭。")); }
    }

    /** Local worker operation; never stops reception or changes camera ownership. */
    public void renamePhotoLiveProject(String id, String name, Callback<PhotoLiveProject> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        if (closed) { main.post(() -> callback.onError("相册已关闭。")); return; }
        try { worker.execute(() -> {
            try {
                PhotoLiveProject renamed;
                try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.use()) { renamed = projectStore().rename(id, name); }
                synchronized (lifecycle) {
                    LiveRun run = liveRun;
                    if (run != null && run.projectId.equals(renamed.id)) {
                        run.projectName = renamed.name;
                        for (LiveCandidate item : run.recent.values()) if (item.saved != null) {
                            item.saved = new DownloadedPhoto(item.saved.file(), item.saved.filename(), renamed.id, renamed.name);
                        }
                        publishLiveLocked(run);
                    }
                }
                main.post(() -> { if (!closed) callback.onSuccess(renamed); });
            } catch (Exception failure) { main.post(() -> { if (!closed) callback.onError(browseFailureText(failure)); }); }
        }); } catch (java.util.concurrent.RejectedExecutionException failure) { main.post(() -> callback.onError("相册已关闭。")); }
    }

    private synchronized PhotoLiveProjectStore projectStore() throws IOException {
        if (liveProjects == null) liveProjects = new PhotoLiveProjectStore(new File(context.getFilesDir(), "photo-live-projects"), downloadDirectory());
        return liveProjects;
    }

    private synchronized PhotoLiveReceiptStore receiptStore() throws IOException {
        if (liveReceipts == null) liveReceipts = new PhotoLiveReceiptStore(new File(context.getFilesDir(), "photo-live-receipts"), downloadDirectory());
        return liveReceipts;
    }

    /** Pure computation over the current camera/session descriptor; never reads from the camera. */
    public String stablePhotoIdentity(PhotoItem photo) {
        synchronized (lifecycle) {
            if (photo == null || !isConnected() || deviceInfo == null || !session.equals(photo.session)) return "";
            return PhotoLiveLedger.identity(cameraKey(active, deviceInfo), photo.handle(), photo.objectInfo());
        }
    }

    private static String cameraKey(PtpIpClient client, PtpDeviceInfo info) {
        String id = client.cameraIdentity();
        return (id.isEmpty() || id.matches("0+") ? client.connectedHost() + ":" + client.connectedPort() : id)
                + "\n" + info.manufacturer() + "\n" + info.model();
    }
    private static boolean trustedCamera(PtpIpClient client) {
        String id = client.cameraIdentity(); return id.matches("[0-9a-fA-F]{32}") && !id.matches("0+");
    }

    private final class LiveRun {
        final PtpIpClient client; final int generation; final String camera, photoSession;
        final boolean trustedCamera;
        final PhotoLiveProjectSetup setup;
        final LiveStartCompletion start;
        final LinkedHashMap<Long, Long> references = new LinkedHashMap<>();
        final Set<Long> initializedStorages = new HashSet<>(), unavailableStorages = new HashSet<>();
        final LinkedHashMap<Long, String> known = new LinkedHashMap<>();
        final Set<Long> baselineHandles = new HashSet<>();
        final LinkedHashSet<Long> inspect = new LinkedHashSet<>(), hints = new LinkedHashSet<>();
        final Map<Long, Integer> metadataAttempts = new LinkedHashMap<>();
        final Map<Long, Long> metadataReady = new LinkedHashMap<>();
        final ArrayDeque<LiveCandidate> queue = new ArrayDeque<>();
        final LinkedHashMap<String, LiveCandidate> recent = new LinkedHashMap<>();
        final ArrayDeque<PhotoLiveReceipt> resumeReceipts = new ArrayDeque<>();
        final Map<String, String> receiptSignatures = new LinkedHashMap<>();
        final Map<String, Long> receiptCreatedTimes = new LinkedHashMap<>();
        final Map<String, PhotoLiveReceipt> receiptUpdates = new LinkedHashMap<>();
        boolean receiptWriteQueued;
        long receiptRetryAfter;
        String receiptError = "";
        boolean enabled = true, starting = true, baselineRead, workQueued, wakeAgain, retryMetadata, storageWaiting;
        int completed, scanCursor, failures; long received, total, lastScan, due;
        String error = ""; LiveCandidate current; Long idleMetadata; PhotoLiveLedger ledger; ScheduledFuture<?> timer;
        String projectId = "", projectName;
        LiveRun(PtpIpClient client, int generation, String projectName, Callback<Void> callback) {
            this(client, generation, projectName, null, callback);
        }
        LiveRun(PtpIpClient client, int generation, String projectName, PhotoLiveProjectSetup setup, Callback<Void> callback) {
            this.client = client; this.generation = generation; this.photoSession = session;
            this.projectName = projectName == null ? "" : projectName.trim();
            this.setup = setup; camera = cameraKey(client, deviceInfo); trustedCamera = trustedCamera(client);
            start = new LiveStartCompletion(this, callback);
        }
    }
    private static final class LiveCandidate {
        final String identity; final PhotoItem photo;
        long createdAt = System.currentTimeMillis(), updatedAt;
        DownloadedPhoto saved; String status = "queued", error = "", galleryUri = "", galleryError = "", transferStage = "";
        boolean previewDone, galleryRetry, galleryPublishing, projectRecorded, originalNotified; int attempts; long ready;
        LiveCandidate(String identity, PhotoItem photo) { this.identity = identity; this.photo = photo; }
        PhotoLiveState.LivePhoto snapshot() {
            return new PhotoLiveState.LivePhoto(identity, photo, saved, status, error, galleryUri, galleryError);
        }
    }
    private final class LiveStartCompletion {
        final LiveRun run; final Callback<Void> callback; final AtomicBoolean done = new AtomicBoolean();
        LiveStartCompletion(LiveRun run, Callback<Void> callback) { this.run = run; this.callback = callback; }
        void complete(Exception failure) {
            if (!done.compareAndSet(false, true)) return;
            main.post(() -> {
                if (failure != null) callback.onError(browseFailureText(failure));
                else if (closed || liveRun != run || !run.enabled) callback.onError("照片直播已停止。");
                else callback.onSuccess(null);
            });
        }
    }
    private static final class LiveCancelled extends IOException { LiveCancelled() { super("照片直播已停止或连接已切换。"); } }
    private static final class LiveChanged extends IOException { LiveChanged() { super("照片内容已变化，等待重新读取。"); } }
    private static class LiveWait extends IOException { LiveWait(String text) { super(text); } }
    private static final class LiveStorageUnavailable extends LiveWait {
        LiveStorageUnavailable() { super("相机存储暂不可用，正在等待存储卡。"); }
    }
    private static final class LiveQueueFull extends LiveWait {
        LiveQueueFull() { this("直播待传队列已满，正在按顺序接收。"); }
        LiveQueueFull(String message) { super(message); }
    }
    private static final class LiveLocalFailure extends IOException { LiveLocalFailure(IOException error) { super(error.getMessage(), error); } }

    /** Capture existing handle inventories without reading every old object's metadata. */
    public void startPhotoLive(Callback<Void> callback) {
        startPhotoLive(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new java.util.Date()), callback);
    }

    /** Create a durable named project before accepting any new photographs. */
    public void startPhotoLive(String projectName, Callback<Void> callback) {
        startPhotoLive(projectName, null, callback);
    }

    public void startPhotoLive(String projectName, PhotoLiveProjectSetup setup, Callback<Void> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        final LiveRun run;
        synchronized (lifecycle) {
            if (rawReadReserved.get()) { main.post(() -> callback.onError("RAW 原文件正在处理中，请完成后再开始照片直播。")); return; }
            if (closed || transferring || deletingDownloadedPhotos || !isConnected()) {
                main.post(() -> callback.onError("请先连接可浏览照片的相机。")); return;
            }
            if (liveRun != null && liveRun.enabled) {
                main.post(() -> callback.onError("照片直播已开启或正在准备。")); return;
            }
            run = new LiveRun(active, generation.get(), projectName, setup, callback); liveRun = run;
            listenForLiveObjects(run);
            publishLiveLocked(run);
        }
        scheduleLive(run, 0);
    }

    private void listenForLiveObjects(LiveRun run) {
        run.client.setOnObjectAdded(handle -> {
            synchronized (lifecycle) {
                if (!liveOwned(run) || !run.enabled) return;
                if (run.hints.size() < 512) run.hints.add(handle);
            }
            scheduleLive(run, 0);
        });
    }

    /** Reads local history independently of camera ownership, including stopped and failed entries. */
    public void listPhotoLiveReceipts(String projectId, Callback<List<PhotoLiveReceipt>> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        if (closed) { main.post(() -> callback.onError("相册已关闭。")); return; }
        try { receiptWorker.execute(() -> {
            try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.use()) {
                PhotoLiveProject project = projectStore().get(projectId);
                Map<String, PhotoLiveReceipt> receipts = new LinkedHashMap<>();
                for (PhotoLiveReceipt receipt : receiptStore().list(projectId, project.name)) receipts.put(receipt.identity, receipt.archived());
                if (receipts.isEmpty()) for (DownloadedPhoto saved : project.photos) {
                    PtpObjectInfo info = new PtpObjectInfo.Builder().objectFormat(0x3801).filename(saved.filename()).compressedSize(saved.byteSize()).build();
                    String file = saved.file().getName();
                    String identity = file.matches("live-[0-9a-f]{64}__.*") ? file.substring(5, 69) : PhotoLiveLedger.identity("legacy\n" + project.id, 1, info);
                    long time = Math.max(project.createdAt, saved.file().lastModified());
                    receipts.put(identity, new PhotoLiveReceipt(project.id, identity, "saved", new PhotoItem("", 1, info), saved.file().isFile() ? saved : null, "", time));
                }
                synchronized (lifecycle) {
                    LiveRun run = liveRun;
                    if (run != null && projectId.equals(run.projectId)) for (LiveCandidate item : run.recent.values()) {
                        PhotoLiveReceipt receipt = receiptSnapshotLocked(run, item, false);
                        receipts.put(receipt.identity, run.enabled ? receipt : receipt.archived());
                    }
                }
                List<PhotoLiveReceipt> result = new ArrayList<>(receipts.values());
                result.sort(Comparator.comparingLong((PhotoLiveReceipt item) -> item.updatedAt).reversed().thenComparing(item -> item.identity));
                List<PhotoLiveReceipt> immutable = Collections.unmodifiableList(result);
                main.post(() -> { if (!closed) callback.onSuccess(immutable); });
            } catch (Exception failure) { main.post(() -> { if (!closed) callback.onError(browseFailureText(failure)); }); }
        }); } catch (java.util.concurrent.RejectedExecutionException failure) { main.post(() -> callback.onError("相册已关闭。")); }
    }

    /** User-requested continuation only. A new connection never starts an old project implicitly. */
    public void resumePhotoLive(String projectId, Callback<Void> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        final PtpIpClient owner; final int request;
        synchronized (lifecycle) {
            if (rawReadReserved.get()) { main.post(() -> callback.onError("RAW 原文件正在处理中，请完成后再继续照片直播。")); return; }
            if (closed || connecting || transferring || deletingDownloadedPhotos || !isConnected()) {
                main.post(() -> callback.onError("请先连接可浏览照片的相机。")); return;
            }
            if (liveRun != null && (liveRun.enabled || liveRun.workQueued || liveRun.current != null)) {
                main.post(() -> callback.onError("请先停止当前直播并等待原图接收完成。")); return;
            }
            owner = active; request = generation.get();
        }
        try { worker.execute(() -> {
            try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.use()) {
                synchronized (lifecycle) { requireCurrentSession(owner, request); }
                PhotoLiveProject project = projectStore().get(projectId);
                PhotoLiveReceiptStore.Binding binding = receiptStore().binding(projectId);
                if (!trustedCamera(owner) || !binding.trusted || !binding.camera.equals(cameraKey(owner, deviceInfo))) {
                    throw new IOException("当前相机与项目机身身份不一致，未接收任何照片。请连接原相机；无法核对身份时请新建项目。");
                }
                List<PhotoLiveReceipt> receipts = receiptWorker.submit(() -> receiptStore().list(projectId, project.name)).get();
                final LiveRun run;
                synchronized (lifecycle) {
                    requireCurrentSession(owner, request);
                    if (liveRun != null && (liveRun.enabled || liveRun.workQueued || liveRun.current != null)) throw new IOException("已有直播正在处理，请稍后重试。");
                    Map<String, PhotoLiveReceipt> observed = new LinkedHashMap<>();
                    for (PhotoLiveReceipt receipt : receipts) observed.put(receipt.identity, receipt);
                    if (liveRun != null && projectId.equals(liveRun.projectId)) for (LiveCandidate item : liveRun.recent.values()) {
                        PhotoLiveReceipt receipt = receiptSnapshotLocked(liveRun, item, false); observed.put(receipt.identity, receipt);
                    }
                    run = new LiveRun(owner, request, project.name, null, callback);
                    run.projectId = project.id; run.completed = project.photoCount;
                    for (PhotoLiveReceipt receipt : observed.values()) {
                        receiptTimestamp = Math.max(receiptTimestamp, receipt.updatedAt);
                        run.receiptCreatedTimes.put(receipt.identity, receipt.createdAt);
                        if (!receipt.status.equals("saved") || receipt.saved != null && !receipt.error.isEmpty()) run.resumeReceipts.add(receipt);
                    }
                    liveRun = run; listenForLiveObjects(run); publishLiveLocked(run);
                }
                scheduleLive(run, 0);
            } catch (Exception failure) { main.post(() -> callback.onError(browseFailureText(failure))); }
        }); } catch (java.util.concurrent.RejectedExecutionException failure) { main.post(() -> callback.onError("相册已关闭。")); }
    }

    /** Does not interrupt a framed read. Queued receives stop and any in-flight work drains on the worker. */
    public void stopPhotoLive() { stopPhotoLive(null, ""); }
    private void stopPhotoLive(PtpIpClient expected, String error) {
        synchronized (lifecycle) {
            if (liveRun == null || expected != null && liveRun.client != expected) return;
            stopLiveRun(liveRun, error);
        }
    }
    private void stopLiveRun(LiveRun expected, String error) {
        LiveRun run;
        synchronized (lifecycle) {
            run = liveRun;
            if (run == null || run != expected) return;
            run.enabled = false; run.starting = false; run.error = error;
            run.client.setOnObjectAdded(null);
            if (run.timer != null) { run.timer.cancel(false); run.timer = null; }
            for (LiveCandidate item : run.queue) if (item.saved == null) item.status = "stopped";
            run.queue.clear(); run.hints.clear();
            publishLiveLocked(run);
        }
        run.start.complete(new IOException(error.isEmpty() ? "照片直播已停止。" : error));
    }

    /** Explicit retry never reconnects; completed originals retry only their separate gallery publication. */
    public void retryPhotoLive() {
        LiveRun run;
        synchronized (lifecycle) {
            run = liveRun; if (run == null || !run.enabled || !liveOwned(run)) return;
            run.error = ""; run.failures = 0;
            run.retryMetadata = true;
            for (LiveCandidate item : run.recent.values()) {
                if (item == run.current || run.queue.contains(item) || item.galleryPublishing) continue;
                if (item.saved != null && !item.galleryError.isEmpty()) item.galleryRetry = true;
                else if (item.saved == null && item.status.equals("error")) {
                    if (run.queue.size() >= 128) break;
                    item.status = "queued"; item.error = ""; item.attempts = 0; item.ready = 0; run.queue.add(item);
                }
            }
            publishLiveLocked(run);
        }
        scheduleLive(run, 0);
    }

    private boolean liveOwned(LiveRun run) {
        return !closed && !transferring && active == run.client && generation.get() == run.generation
                && run.client.isOpen() && !run.client.isProtocolUnconfirmed();
    }
    private void requireLive(LiveRun run) throws LiveCancelled {
        synchronized (lifecycle) { if (!run.enabled || liveRun != run || !liveOwned(run)) throw new LiveCancelled(); }
    }
    private void publishLiveLocked(LiveRun run) {
        if (liveRun != run) return;
        trimLiveRecent(run);
        queueReceiptWritesLocked(run);
        List<PhotoLiveState.LivePhoto> photos = new ArrayList<>();
        for (LiveCandidate item : run.recent.values()) photos.add(item.snapshot());
        Collections.reverse(photos);
        LiveCandidate current = run.current;
        String stage = current == null || current.saved == null && !run.enabled ? "" : current.transferStage;
        if (stage.isEmpty()) {
            current = null;
            // Local publication may finish after the camera worker has moved on. Prefer the latest pending save.
            for (LiveCandidate item : run.recent.values()) if (item.galleryPublishing) current = item;
            if (current != null) stage = "saving";
            else if (run.enabled && !run.queue.isEmpty() && "preview".equals(run.queue.peekFirst().status)) {
                current = run.queue.peekFirst(); stage = "preview";
            }
        }
        long total = current == null ? 0 : "receiving".equals(stage) ? run.total : current.photo.byteSize();
        long received = "saving".equals(stage) ? total : "receiving".equals(stage) ? run.received : 0;
        liveState = new PhotoLiveState(run.enabled, run.starting, run.current != null,
                run.queue.size(), run.completed, received, total, run.receiptError.isEmpty() ? run.error
                        : run.error + (run.error.isEmpty() ? "" : "\n") + run.receiptError,
                ++liveRevision, photos, run.projectId, run.projectName, stage,
                current == null ? "" : current.identity, current == null ? "" : current.photo.filename());
        main.post(() -> {
            List<Runnable> observers;
            synchronized (lifecycle) { observers = new ArrayList<>(photoLiveObservers); }
            Runnable listener = photoLiveListener;
            if (listener != null) try { listener.run(); } catch (RuntimeException ignored) { }
            for (Runnable observer : observers) try { observer.run(); } catch (RuntimeException ignored) { }
        });
    }

    private PhotoLiveReceipt receiptSnapshotLocked(LiveRun run, LiveCandidate item, boolean changed) {
        if (changed || item.updatedAt == 0) item.updatedAt = receiptTimestamp = Math.max(System.currentTimeMillis(), receiptTimestamp + 1);
        String error = item.error;
        if (!item.galleryError.isEmpty()) error += (error.isEmpty() ? "" : "\n") + item.galleryError;
        else if (item.galleryPublishing) error += (error.isEmpty() ? "" : "\n") + "系统相册保存待核对，可继续接收项目后重试。";
        return new PhotoLiveReceipt(run.projectId, item.identity, item.status, item.photo, item.saved, error, item.createdAt, item.updatedAt);
    }

    /** Only structural state changes enqueue metadata; byte progress does not touch disk. */
    private void queueReceiptWritesLocked(LiveRun run) {
        if (run.projectId.isEmpty()) return;
        for (LiveCandidate item : run.recent.values()) {
            String signature = item.status + "\n" + item.error + "\n" + item.galleryError + "\n" + item.galleryPublishing
                    + "\n" + (item.saved == null ? "" : item.saved.file().getAbsolutePath());
            if (!signature.equals(run.receiptSignatures.get(item.identity))) {
                run.receiptSignatures.put(item.identity, signature);
                run.receiptUpdates.put(item.identity, receiptSnapshotLocked(run, item, true));
            }
        }
        if (run.receiptUpdates.isEmpty() || run.receiptWriteQueued || System.nanoTime() < run.receiptRetryAfter) return;
        run.receiptWriteQueued = true;
        final List<PhotoLiveReceipt> updates = new ArrayList<>(run.receiptUpdates.values()); run.receiptUpdates.clear();
        try { receiptWorker.execute(() -> {
            boolean written = false;
            boolean notifyReceiptStatus = false;
            try { receiptStore().write(run.projectId, run.camera, run.trustedCamera, updates); written = true; }
            catch (Exception failure) {
                synchronized (lifecycle) {
                    notifyReceiptStatus = true;
                    run.receiptError = "接收记录保存待重试：" + browseFailureText(failure);
                    run.receiptRetryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    for (PhotoLiveReceipt receipt : updates) run.receiptUpdates.putIfAbsent(receipt.identity, receipt);
                }
            } finally {
                synchronized (lifecycle) {
                    run.receiptWriteQueued = false;
                    if (written) {
                        notifyReceiptStatus = !run.receiptError.isEmpty(); run.receiptError = "";
                        if (!run.receiptUpdates.isEmpty()) queueReceiptWritesLocked(run);
                    }
                }
                if (notifyReceiptStatus) main.post(() -> { synchronized (lifecycle) { if (!closed && liveRun == run) publishLiveLocked(run); } });
            }
        }); } catch (java.util.concurrent.RejectedExecutionException stopped) { run.receiptWriteQueued = false; }
    }

    /** Retire only successful history. Outstanding originals and gallery failures keep their retry identity. */
    private void trimLiveRecent(LiveRun run) {
        if (run.recent.size() <= 120) return;
        java.util.Iterator<LiveCandidate> entries = run.recent.values().iterator();
        while (run.recent.size() > 120 && entries.hasNext()) {
            LiveCandidate item = entries.next();
            if (item != run.current && !run.queue.contains(item) && item.saved != null
                    && !item.galleryPublishing && item.galleryError.isEmpty() && item.error.isEmpty()) entries.remove();
        }
    }
    private void scheduleLive(LiveRun run, long delayMillis) {
        synchronized (lifecycle) {
            if (liveRun != run || !run.enabled || !liveOwned(run)) return;
            long due = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
            if (run.timer != null && run.due <= due) return;
            if (run.timer != null) run.timer.cancel(false);
            run.due = due;
            try {
                run.timer = liveClock.schedule(() -> {
                    synchronized (lifecycle) {
                        run.timer = null;
                        if (!run.enabled || liveRun != run || !liveOwned(run)) return;
                        if (run.workQueued) { run.wakeAgain = true; return; }
                        run.workQueued = true;
                    }
                    try { worker.execute(() -> runLiveWork(run)); }
                    catch (java.util.concurrent.RejectedExecutionException stopped) { stopLiveRun(run, "照片直播已关闭。"); }
                }, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException stopped) { stopLiveRun(run, "照片直播已关闭。"); }
        }
    }
    private void runLiveWork(LiveRun run) {
        long nextDelay = 3000;
        try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.use()) {
            requireLive(run);
            synchronized (lifecycle) {
                if (run.retryMetadata) { run.metadataAttempts.clear(); run.metadataReady.clear(); run.retryMetadata = false; }
            }
            if (run.ledger == null) {
                try { run.ledger = new PhotoLiveLedger(downloadDirectory()); }
                catch (IOException local) { throw new LiveLocalFailure(local); }
            }
            if (run.projectId.isEmpty()) {
                try {
                    PhotoLiveProject project = projectStore().create(run.projectName);
                    synchronized (lifecycle) { run.projectId = project.id; run.projectName = project.name; }
                    receiptStore().bind(project.id, run.camera, run.trustedCamera);
                    if (run.setup != null) run.setup.configure(project);
                    synchronized (lifecycle) { publishLiveLocked(run); }
                } catch (Exception local) { throw new LiveLocalFailure(local instanceof IOException ? (IOException) local : new IOException(browseFailureText(local), local)); }
            }
            if (!run.baselineRead) {
                scanLiveReferences(run);
                run.baselineHandles.addAll(run.references.keySet());
                run.baselineRead = true;
            }
            restoreLiveReceipts(run);
            if (run.starting) {
                synchronized (lifecycle) { run.starting = false; run.error = ""; publishLiveLocked(run); }
                run.start.complete(null);
            }
            long now = System.nanoTime();
            if (now - run.lastScan >= TimeUnit.SECONDS.toNanos(3)) scanLiveReferences(run);
            synchronized (lifecycle) {
                for (long handle : run.hints) {
                    // Delayed events for the captured inventory must not create a large
                    // metadata queue. Its first descriptor is sampled only while idle.
                    if (!run.baselineHandles.contains(handle) || run.known.containsKey(handle)) run.inspect.add(handle);
                }
                run.hints.clear();
            }
            int inspected = 0;
            while (!run.inspect.isEmpty() && inspected++ < 12) {
                requireLive(run); long handle = run.inspect.iterator().next();
                run.inspect.remove(handle);
                Long storage = run.references.get(handle);
                if (storage != null && run.unavailableStorages.contains(storage)) continue;
                Long ready = run.metadataReady.get(handle);
                if (ready != null && ready > System.nanoTime()) { run.inspect.add(handle); continue; }
                try {
                    inspectLiveObject(run, handle);
                    run.metadataReady.remove(handle); run.metadataAttempts.remove(handle);
                } catch (IOException wait) {
                    if (!(wait instanceof LiveWait) && (!(wait instanceof PtpResponseException)
                            || !retryableLiveResponse((PtpResponseException) wait))
                            || !run.client.isOpen() || run.client.isProtocolUnconfirmed()) throw wait;
                    if (wait instanceof LiveQueueFull) {
                        // Local backpressure is not a camera failure. Revisit when a queue slot drains.
                        run.metadataReady.put(handle, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250));
                    } else {
                        int attempts = run.metadataAttempts.getOrDefault(handle, 0) + 1;
                        run.metadataAttempts.put(handle, attempts);
                        run.metadataReady.put(handle, attempts >= 4 ? Long.MAX_VALUE
                                : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500L << attempts));
                    }
                    run.inspect.add(handle);
                    synchronized (lifecycle) { run.error = browseFailureText(wait); publishLiveLocked(run); }
                }
            }
            LiveCandidate candidate = null;
            synchronized (lifecycle) {
                for (LiveCandidate saved : run.recent.values()) if (saved.galleryRetry && !saved.galleryPublishing) {
                    saved.galleryRetry = false; candidate = saved; break;
                }
                if (candidate == null && !run.queue.isEmpty()) {
                    java.util.Iterator<LiveCandidate> waiting = run.queue.iterator();
                    while (waiting.hasNext()) {
                        LiveCandidate next = waiting.next();
                        if (run.unavailableStorages.contains(next.photo.storageId())) continue;
                        if (next.ready <= System.nanoTime()) { candidate = next; waiting.remove(); }
                        break;
                    }
                }
                if (candidate != null) {
                    run.current = candidate; run.received = 0; run.total = candidate.photo.byteSize();
                    candidate.transferStage = candidate.saved != null ? "saving" : candidate.previewDone ? "receiving" : "preview";
                    publishLiveLocked(run);
                }
            }
            if (candidate != null) processLiveCandidate(run, candidate);
            else if (run.inspect.isEmpty() && run.queue.isEmpty() && run.idleMetadata != null) {
                long handle = run.idleMetadata; run.idleMetadata = null;
                // One low-priority descriptor per reconciliation, never an eager scan
                // of the whole card. A framed read still drains before the next event.
                Long ready = run.metadataReady.get(handle);
                if (ready == null || ready <= System.nanoTime()) {
                    try {
                        inspectLiveObject(run, handle);
                        run.metadataReady.remove(handle); run.metadataAttempts.remove(handle);
                    } catch (PtpResponseException wait) {
                        if (!retryableLiveResponse(wait)) throw wait;
                        run.metadataReady.put(handle, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
                    } catch (LiveWait wait) {
                        run.metadataReady.put(handle, System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
                    }
                }
            }
            synchronized (lifecycle) {
                run.failures = 0;
                if (!run.inspect.isEmpty() || !run.queue.isEmpty() || !run.resumeReceipts.isEmpty()) nextDelay = 250;
            }
        } catch (LiveCancelled cancelled) {
            // Ownership and stop are cancellation barriers, never a reason to close the replacement owner.
        } catch (Exception failure) {
            boolean healthy;
            synchronized (lifecycle) {
                if (!run.enabled || liveRun != run) return;
                healthy = liveOwned(run); run.error = browseFailureText(failure); run.failures++;
                if (liveStorageUnavailable(failure)) run.storageWaiting = true;
            }
            boolean invalidSession = failure instanceof PtpResponseException
                    && (((PtpResponseException) failure).responseCode() == 0x2003 || ((PtpResponseException) failure).responseCode() == 0x2004);
            if (failure instanceof LiveLocalFailure) stopLiveRun(run, browseFailureText(failure));
            else if (!healthy || invalidSession || !(failure instanceof PtpResponseException) && !(failure instanceof LiveWait)) {
                if (healthy) run.client.abortProtocol(new IOException("照片直播读取：" + browseFailureText(failure), failure));
                stopLiveRun(run, browseFailureText(failure));
            } else if (failure instanceof PtpResponseException && !retryableLiveResponse((PtpResponseException) failure)
                    || run.starting && run.failures >= 4 && !liveStorageUnavailable(failure)) {
                stopLiveRun(run, browseFailureText(failure));
            } else {
                nextDelay = Math.min(30000, 500L << Math.min(6, run.failures));
                synchronized (lifecycle) { publishLiveLocked(run); }
            }
        } finally {
            synchronized (lifecycle) {
                run.workQueued = false;
                if (run.current != null) { if (!run.enabled && run.current.saved == null) run.current.status = "stopped"; run.current = null; publishLiveLocked(run); }
                if (run.wakeAgain) { run.wakeAgain = false; nextDelay = Math.min(nextDelay, 250); }
            }
            scheduleLive(run, nextDelay);
        }
    }

    /** Revalidates only explicitly unfinished records; the card baseline remains handle-only. */
    private void restoreLiveReceipts(LiveRun run) throws IOException {
        synchronized (lifecycle) {
            if (!run.resumeReceipts.isEmpty() && run.recent.size() >= 256) {
                java.util.Iterator<LiveCandidate> archived = run.recent.values().iterator();
                while (archived.hasNext() && run.recent.size() > 200) {
                    LiveCandidate item = archived.next();
                    if (item != run.current && !item.galleryPublishing && !run.queue.contains(item)
                            && (item.status.equals("error") || item.status.equals("stopped"))) archived.remove();
                }
            }
        }
        int remaining = Math.min(12, run.resumeReceipts.size());
        while (remaining-- > 0 && run.queue.size() < 128 && run.recent.size() < 256) {
            requireLive(run);
            PhotoLiveReceipt receipt = run.resumeReceipts.removeFirst();
            if (receipt.saved == null && !run.initializedStorages.contains(receipt.photo.storageId())) {
                run.resumeReceipts.addLast(receipt); continue;
            }
            PhotoItem photo = new PhotoItem(run.photoSession, receipt.photo.handle(), receipt.photo.objectInfo());
            LiveCandidate item = new LiveCandidate(receipt.identity, photo); item.createdAt = receipt.createdAt;
            if (receipt.saved != null) {
                item.saved = new DownloadedPhoto(receipt.saved.file(), receipt.saved.filename(), run.projectId, run.projectName);
                item.status = "saved"; item.galleryRetry = true;
            } else {
                boolean receive = false;
                try {
                    PtpObjectInfo info = liveInfo(run, photo.handle());
                    if (!receipt.identity.equals(liveIdentity(run, photo.handle(), info))) {
                        item.status = "error"; item.error = "相机照片已变化，未下载同编号的其他照片。";
                    } else if (photo.byteSize() > MAX_PHOTO_BYTES) {
                        item.status = "error"; item.error = "照片超过本版 512 MB 下载上限。";
                    } else {
                        receive = true;
                    }
                } catch (PtpResponseException unavailable) {
                    if (unavailable.responseCode() == 0x2009) { item.status = "error"; item.error = "相机中已找不到这张照片。"; }
                    else if (retryableLiveResponse(unavailable)) {
                        run.resumeReceipts.addLast(receipt);
                        synchronized (lifecycle) { run.error = browseFailureText(unavailable); publishLiveLocked(run); }
                        break;
                    } else throw unavailable;
                }
                if (receive) synchronized (lifecycle) {
                    requireLive(run);
                    if (run.queue.size() >= 128) { run.resumeReceipts.addFirst(receipt); break; }
                    run.known.put(photo.handle(), receipt.identity); run.queue.addLast(item);
                }
            }
            synchronized (lifecycle) { requireLive(run); run.recent.put(item.identity, item); publishLiveLocked(run); }
        }
    }

    private void scanLiveReferences(LiveRun run) throws IOException {
        requireLive(run);
        long[] storages = PtpStorageIds.parse(run.client.getData(0x1004, 4096));
        if (storages.length > 64) throw new IOException("相机存储空间数量超过直播上限。");
        LinkedHashMap<Long, Long> observed = new LinkedHashMap<>();
        Set<Long> available = new HashSet<>(), unavailable = new HashSet<>();
        for (long storage : storages) {
            requireLive(run); byte[] data;
            try {
                try { data = run.client.getData(0x1007, 1024 * 1024, storage, 0x3801, 0); }
                catch (PtpResponseException error) {
                    if (error.responseCode() != 0x2014) throw error;
                    data = run.client.getData(0x1007, 1024 * 1024, storage, 0, 0);
                }
            } catch (PtpResponseException storageError) {
                if (storageError.responseCode() != 0x2013) throw storageError;
                unavailable.add(storage);
                continue; // An empty slot must not stop live reception from the other card.
            }
            available.add(storage);
            for (long handle : PtpObjectHandles.parse(data)) {
                if (handle == 0 || handle == 0xffffffffL) throw new IOException("照片直播索引无效。");
                Long previous = observed.put(handle, storage);
                if (previous != null && previous != storage) throw new IOException("相机在不同存储空间返回重复照片索引。");
                if (observed.size() > MAX_HANDLES) throw new IOException("照片直播最多读取 20000 个对象。");
            }
        }
        requireLive(run);
        // Do not forget old identities during a temporary card outage. Otherwise recovery
        // would make already-present photographs look like newly captured objects.
        for (Map.Entry<Long, Long> previous : run.references.entrySet()) if (!available.contains(previous.getValue())) {
            unavailable.add(previous.getValue());
            Long other = observed.putIfAbsent(previous.getKey(), previous.getValue());
            if (other != null && !other.equals(previous.getValue())) throw new IOException("相机在不同存储空间返回重复照片索引。");
            if (observed.size() > MAX_HANDLES) throw new IOException("照片直播最多读取 20000 个对象。");
        }
        Set<Long> recovered = new HashSet<>(run.unavailableStorages); recovered.retainAll(available);
        run.unavailableStorages.clear(); run.unavailableStorages.addAll(unavailable);
        if (available.isEmpty()) throw new LiveStorageUnavailable();
        for (Map.Entry<Long, Long> entry : observed.entrySet()) {
            if (run.baselineRead && !run.initializedStorages.contains(entry.getValue()) && available.contains(entry.getValue())) {
                run.baselineHandles.add(entry.getKey()); // First successful inventory of a previously unavailable slot is its baseline.
            }
            if (recovered.contains(entry.getValue())) {
                run.metadataAttempts.remove(entry.getKey()); run.metadataReady.remove(entry.getKey());
            }
        }
        run.initializedStorages.addAll(available);
        if (run.initializedStorages.size() > MAX_HANDLES) throw new IOException("相机存储标识变化超过直播上限。");
        run.references.clear(); run.references.putAll(observed); run.lastScan = System.nanoTime();
        synchronized (lifecycle) {
            if (run.storageWaiting) { run.storageWaiting = false; run.error = ""; run.failures = 0; publishLiveLocked(run); }
        }
        if (run.baselineRead) {
            run.baselineHandles.retainAll(observed.keySet());
            run.known.keySet().retainAll(observed.keySet());
            run.metadataReady.keySet().retainAll(observed.keySet());
            run.metadataAttempts.keySet().retainAll(observed.keySet());
            for (long handle : observed.keySet()) if (!run.known.containsKey(handle) && !run.baselineHandles.contains(handle)
                    && available.contains(observed.get(handle))) run.inspect.add(handle);
            // Baseline descriptors are optional idle work, not a queue in front of new photos.
            List<Long> keys = new ArrayList<>();
            for (Map.Entry<Long, Long> entry : observed.entrySet()) if (available.contains(entry.getValue())) keys.add(entry.getKey());
            run.idleMetadata = keys.isEmpty() ? null : keys.get(run.scanCursor % keys.size());
            if (!keys.isEmpty()) run.scanCursor = (run.scanCursor + 1) % keys.size();
        }
    }
    private PtpObjectInfo liveInfo(LiveRun run, long handle) throws IOException {
        requireLive(run); PtpObjectInfo info = PtpObjectInfo.parse(run.client.getData(0x1008, 65536, handle));
        Long storage = run.references.get(handle);
        if (storage != null && storage != info.storageId()) throw new PtpProtocolException("照片存储位置与相机索引不一致。");
        return info;
    }
    private String liveIdentity(LiveRun run, long handle, PtpObjectInfo info) { return PhotoLiveLedger.identity(run.camera, handle, info); }
    private void inspectLiveObject(LiveRun run, long handle) throws IOException {
        PtpObjectInfo info;
        try { info = liveInfo(run, handle); }
        catch (PtpResponseException missing) {
            if (missing.responseCode() == 0x2009 && !run.references.containsKey(handle)) return;
            throw missing;
        }
        String identity = liveIdentity(run, handle, info), previous = run.known.get(handle);
        if (!run.initializedStorages.contains(info.storageId())) {
            // An event cannot establish the baseline of a previously unreadable card.
            // First inventory that slot, so delayed old events cannot import old photos.
            run.lastScan = 0;
            throw new LiveWait("正在确认新可用存储卡的照片列表。");
        }
        if (previous == null && run.baselineHandles.contains(handle)) { run.known.put(handle, identity); return; }
        if (identity.equals(previous)) return;
        if (info.objectFormat() != 0x3801) { run.known.put(handle, identity); return; }
        if (info.compressedSize() < 4) throw new LiveWait("照片尚未写完，等待相机完成。");
        Long created = run.receiptCreatedTimes.get(identity);
        if (created == null && !run.projectId.isEmpty()) {
            PhotoLiveReceipt previousReceipt = receiptStore().get(run.projectId, identity, run.projectName);
            if (previousReceipt != null) created = previousReceipt.createdAt;
        }
        synchronized (lifecycle) {
            if (run.recent.containsKey(identity)) { run.known.put(handle, identity); return; }
            if (run.queue.size() >= 128) throw new LiveQueueFull();
            trimLiveRecent(run);
            if (run.recent.size() >= 256) throw new LiveQueueFull("直播待处理记录已满，请先重试未保存的照片。");
            PhotoItem photo = new PhotoItem(run.photoSession, handle, info);
            LiveCandidate candidate = new LiveCandidate(identity, photo);
            if (created != null) candidate.createdAt = created;
            run.receiptCreatedTimes.put(identity, candidate.createdAt);
            run.known.put(handle, identity);
            if (photo.byteSize() > MAX_PHOTO_BYTES) { candidate.status = "error"; candidate.error = "照片超过本版 512 MB 下载上限。"; }
            else run.queue.addLast(candidate);
            run.recent.put(identity, candidate);
            publishLiveLocked(run);
        }
    }

    private static boolean retryableLiveResponse(PtpResponseException response) {
        return response.responseCode() == 0x2002 || response.responseCode() == 0x2019 || response.responseCode() == 0x2009 || response.responseCode() == 0x2013;
    }
    private static boolean liveStorageUnavailable(Exception failure) {
        return failure instanceof LiveStorageUnavailable || failure instanceof PtpResponseException
                && ((PtpResponseException) failure).responseCode() == 0x2013;
    }
    private void processLiveCandidate(LiveRun run, LiveCandidate item) throws Exception {
        try {
            requireLive(run);
            if (item.saved != null) { publishLiveGallery(run, item); return; }
            PtpObjectInfo before = liveInfo(run, item.photo.handle());
            if (!item.identity.equals(liveIdentity(run, item.photo.handle(), before))) throw new LiveChanged();
            PhotoOriginalStore originals = originalStore();
            String completionIdentity = run.trustedCamera ? item.identity : PhotoLiveLedger.identity(run.camera + "\n" + run.photoSession, item.photo.handle(), item.photo.objectInfo());
            DownloadedPhoto existing = originals.find(completionIdentity, safeJpegName(item.photo.filename()), item.photo.byteSize());
            if (existing != null) { liveSaved(run, item, existing); return; }
            if (!item.previewDone) {
                try {
                    previews.invalidate(item.photo);
                    CameraThumbnail preview = readBestThumbnail(item.photo);
                    File temporary = previews.createTemporary();
                    try {
                        try (FileOutputStream file = new FileOutputStream(temporary)) { file.write(preview.bytes); file.getFD().sync(); }
                        previews.publish(item.photo, temporary, preview.large).close();
                    } finally { previews.discardTemporary(temporary); }
                } catch (IOException previewError) {
                    if (!run.client.isOpen() || run.client.isProtocolUnconfirmed()
                            || previewError instanceof PtpProtocolException && !(previewError instanceof PtpResponseException)
                            || previewError instanceof PtpResponseException && (retryableLiveResponse((PtpResponseException) previewError)
                            || ((PtpResponseException) previewError).responseCode() == 0x2003 || ((PtpResponseException) previewError).responseCode() == 0x2004)) throw previewError;
                    item.error = "预览暂不可用：" + browseFailureText(previewError);
                }
                requireLive(run);
                synchronized (lifecycle) {
                    item.previewDone = true; item.status = "preview";
                    item.ready = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250); run.queue.addFirst(item);
                    run.current = null; publishLiveLocked(run);
                }
                return;
            }
            if (item.photo.byteSize() > MAX_PHOTO_BYTES) throw new IOException("照片超过本版 512 MB 下载上限。");
            synchronized (lifecycle) { item.status = "receiving"; item.transferStage = "receiving"; publishLiveLocked(run); }
            File temporary = File.createTempFile("live-receiving-", ".part", downloadDirectory());
            try {
                long[] last = {0};
                try (FileOutputStream file = new FileOutputStream(temporary)) {
                    run.client.readTo(0x1009, file, MAX_PHOTO_BYTES, item.photo.byteSize(), (received, total) -> {
                        long now = System.nanoTime();
                        if (received == total || now - last[0] >= TimeUnit.MILLISECONDS.toNanos(200)) {
                            last[0] = now;
                            synchronized (lifecycle) { run.received = received; run.total = total; publishLiveLocked(run); }
                        }
                    }, item.photo.handle());
                    synchronized (lifecycle) { item.transferStage = "saving"; publishLiveLocked(run); }
                    file.getFD().sync();
                }
                requireLive(run);
                PtpObjectInfo after = liveInfo(run, item.photo.handle());
                if (!item.identity.equals(liveIdentity(run, item.photo.handle(), after))) throw new LiveChanged();
                PhotoPreviewCache.validateJpeg(temporary, item.photo.byteSize());
                DownloadedPhoto completed = originals.commit(completionIdentity, temporary, safeJpegName(item.photo.filename()));
                liveSaved(run, item, completed);
            } finally { if (temporary.exists()) temporary.delete(); }
        } catch (LiveCancelled cancelled) { throw cancelled; }
        catch (Exception failure) {
            if (!run.client.isOpen() || run.client.isProtocolUnconfirmed()
                    || failure instanceof PtpProtocolException && !(failure instanceof PtpResponseException)
                    || failure instanceof PtpResponseException && (((PtpResponseException) failure).responseCode() == 0x2003
                    || ((PtpResponseException) failure).responseCode() == 0x2004)) throw failure;
            synchronized (lifecycle) {
                item.transferStage = "";
                item.attempts++; item.error = browseFailureText(failure); run.error = item.error;
                if (failure instanceof LiveChanged) {
                    item.status = "error"; run.inspect.add(item.photo.handle());
                } else if (failure instanceof PtpResponseException && retryableLiveResponse((PtpResponseException) failure)
                        && item.attempts < 4 && run.enabled) {
                    item.status = "queued"; item.ready = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500L << item.attempts);
                    run.queue.addLast(item);
                } else item.status = "error";
                publishLiveLocked(run);
            }
        }
    }
    private void liveSaved(LiveRun run, LiveCandidate item, DownloadedPhoto saved) {
        rememberJpegRawReference(run.camera, run.trustedCamera, item.photo, saved);
        synchronized (lifecycle) {
            if (item.saved == null) run.completed++;
            item.saved = new DownloadedPhoto(saved.file(), saved.filename(), run.projectId, run.projectName);
            item.status = "saved"; item.transferStage = "saving"; item.error = ""; run.error = "";
            publishLiveLocked(run);
        }
        publishLiveGallery(run, item);
        synchronized (lifecycle) { publishLiveLocked(run); }
    }
    private void publishLiveGallery(LiveRun run, LiveCandidate item) {
        synchronized (lifecycle) { if (item.saved == null || item.galleryPublishing) return; }
        if (item.saved != null && !item.projectRecorded) {
            try { projectStore().record(run.projectId, item.identity, item.saved); item.projectRecorded = true; item.galleryError = ""; }
            catch (Exception failure) {
                synchronized (lifecycle) {
                    item.galleryError = "项目分类待重试，原图已保留：" + browseFailureText(failure);
                    item.transferStage = ""; publishLiveLocked(run);
                }
                return;
            }
        }
        PhotoLiveOriginalObserver observer = photoLiveOriginalObserver;
        if (observer != null && !item.originalNotified) {
            item.originalNotified = true;
            try { observer.onOriginalSaved(run.projectId, item.identity, item.saved); }
            catch (RuntimeException ignored) { /* Effect queues must never invalidate an acquired original. */ }
        }
        PhotoLiveAsyncPublisher asynchronous = photoLiveAsyncPublisher;
        if (asynchronous != null) {
            DownloadedPhoto saved;
            synchronized (lifecycle) {
                if (item.galleryPublishing) return;
                item.galleryPublishing = true; item.galleryRetry = false; item.galleryError = "";
                saved = item.saved; publishLiveLocked(run);
            }
            AtomicBoolean settled = new AtomicBoolean();
            Callback<String> completion = new Callback<String>() {
                private void finish(String uri, String error) {
                    if (!settled.compareAndSet(false, true)) return;
                    boolean current;
                    synchronized (lifecycle) {
                        item.galleryPublishing = false; item.transferStage = "";
                        if (error == null && uri != null && !uri.trim().isEmpty()) {
                            item.galleryUri = uri; item.galleryError = "";
                        } else item.galleryError = "系统相册未保存，原图已保留："
                                + (error == null || error.isEmpty() ? "系统相册尚未确认保存完成，请重试。" : error);
                        current = !closed && liveRun == run;
                        if (current) publishLiveLocked(run);
                    }
                    if (current) scheduleLive(run, 0);
                }
                @Override public void onSuccess(String uri) { finish(uri, null); }
                @Override public void onError(String error) { finish(null, error); }
            };
            try { asynchronous.publish(saved, completion); }
            catch (Exception failure) { completion.onError(browseFailureText(failure)); }
            return;
        }
        PhotoLivePublisher publisher = photoLivePublisher;
        if (publisher != null && item.saved != null) {
            try {
                String uri = publisher.publish(item.saved);
                if (uri == null || uri.trim().isEmpty()) throw new IOException("系统相册尚未确认保存完成，请重试。");
                item.galleryUri = uri; item.galleryError = "";
            }
            catch (Exception failure) { item.galleryError = "系统相册未保存，原图已保留：" + browseFailureText(failure); }
        }
        synchronized (lifecycle) { item.transferStage = ""; publishLiveLocked(run); }
    }

    public AlbumController(Context context) {
        this.context = context.getApplicationContext();
        wifi = new CameraWifiNetwork(this.context);
        previews = new PhotoPreviewCache(new File(this.context.getCacheDir(), "camera-album-preview"), 2,
                16L * 1024 * 1024);
    }

    /** A suggestion only. The UI must allow editing and confirming the camera address. */
    public String suggestHost() {
        return wifi.suggestHost();
    }

    public void connect(String host, int port, Callback<ConnectionInfo> callback) {
        connect(host, port, callback, true);
    }

    /** Opens a camera session without requiring photo storage or Picture Control capabilities. */
    public void connectCamera(String host, int port, Callback<ConnectionInfo> callback) {
        connect(host, port, callback, false);
    }

    private void connect(String host, int port, Callback<ConnectionInfo> callback, boolean browse) {
        if (callback == null) throw new IllegalArgumentException("callback");
        disconnect();
        final int connectionGeneration = generation.get();
        synchronized (lifecycle) {
            if (closed) { main.post(() -> callback.onError("相册已关闭，未开始连接。")); return; }
            connecting = true;
        }
        notifyStateChanged();
        submit(connectionGeneration, callback, () -> {
            try {
            PtpIpClient client = new PtpIpClient(wifi.connector(host.trim(), port));
            observeClient(client, connectionGeneration);
            synchronized (lifecycle) {
                if (closed || connectionGeneration != generation.get()) { client.close(); throw new IOException("无线连接已取消。"); }
                active = client;
            }
            try {
                byte[] info = client.connect(host.trim(), port, wifi.clientGuid(), "Preset Album");
                if (browse) return readConnectionSnapshot(client, host.trim(), port, info, connectionGeneration, false);
                PtpDeviceInfo parsed = readCameraIdentity(client, info, connectionGeneration);
                ConnectionInfo connected = new ConnectionInfo(parsed.model(), host.trim(), port, 0);
                synchronized (lifecycle) {
                    requireCurrentSession(client, connectionGeneration);
                    handles = new long[0]; session = "";
                    cachedConnectionInfo = null; sessionConnectionInfo = connected; browseError = "";
                }
                return connected;
            } catch (Exception error) { client.close(); throw error; }
            } finally {
                synchronized (lifecycle) {
                    if (connectionGeneration == generation.get()) connecting = false;
                }
                notifyStateChanged();
            }
        });
    }

    public boolean isConnected() {
        PtpIpClient client = active;
        return !closed && !connecting && !transferring && cachedConnectionInfo != null && client != null && client.isOpen();
    }
    public boolean isConnecting() { return !closed && connecting; }

    /** A claimed camera session may stay live even when its photo inventory is unavailable. */
    public boolean hasLiveSession() {
        PtpIpClient client = active;
        return !closed && !transferring && sessionConnectionInfo != null && client != null
                && client.isOpen() && !client.isProtocolUnconfirmed();
    }

    public ConnectionInfo sessionConnectionInfo() { return hasLiveSession() ? sessionConnectionInfo : null; }
    public String browseError() { return browseError; }

    /** Complete metadata for the current owned session; no network query or reconnect. */
    public ConnectionInfo connectionInfo() { return isConnected() ? cachedConnectionInfo : null; }

    /** True transfers ownership immediately, including the queued interval; false leaves it with the caller. */
    public boolean connectFromHandoff(PtpIpClient transferred, String host, int port,
                                      Callback<ConnectionInfo> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        final int request;
        synchronized (lifecycle) {
            String address = host == null ? "" : host.trim();
            if (closed || connecting || transferring || (active != null && active.isOpen())
                    || transferred == null || !transferred.isPendingAlbumHandoff()
                    || !address.equals(transferred.connectedHost()) || port != transferred.connectedPort()) {
                main.post(() -> callback.onError("当前相册不能接管该会话；移交句柄仍由调用方持有。"));
                return false;
            }
            request = generation.incrementAndGet(); previewGeneration.incrementAndGet();
            active = transferred; connecting = true;
            cachedConnectionInfo = null; sessionConnectionInfo = null; browseError = "";
            observeClient(transferred, request);
        }
        notifyStateChanged();
        queueExistingSessionBrowse(transferred, host.trim(), port, request, true, callback);
        return true;
    }

    /** Re-enumerates only this controller's existing camera session, without reconnecting. */
    public void retryBrowse(Callback<ConnectionInfo> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        stopPhotoLive();
        final PtpIpClient client;
        final ConnectionInfo held;
        final int request;
        synchronized (lifecycle) {
            held = sessionConnectionInfo;
            if (!hasLiveSession() || connecting || held == null) {
                main.post(() -> callback.onError("当前相机会话不可重新读取；本次未重新连接。"));
                return;
            }
            client = active; request = generation.incrementAndGet(); previewGeneration.incrementAndGet();
            connecting = true; cachedConnectionInfo = null; browseError = "";
            observeClient(client, request);
        }
        notifyStateChanged();
        queueExistingSessionBrowse(client, held.host(), held.port(), request, false, callback);
    }

    private void queueExistingSessionBrowse(PtpIpClient client, String host, int port, int request,
                                           boolean claim, Callback<ConnectionInfo> callback) {
        BrowseCompletion completion = new BrowseCompletion(client, request, callback);
        synchronized (lifecycle) {
            if (closed || request != generation.get() || active != client) {
                completion.complete(null, new IOException("相册读取已取消或会话已移交。"));
                client.close();
                return;
            }
            pendingBrowses.add(completion);
        }
        Runnable browse = () -> {
            ConnectionInfo connected = null;
            Exception failure = null;
            try {
                synchronized (lifecycle) { requireCurrentSession(client, request); }
                if (claim) client.claimTransferredAlbumSession();
                synchronized (lifecycle) {
                    requireCurrentSession(client, request);
                    if (sessionConnectionInfo == null) sessionConnectionInfo = new ConnectionInfo("", host, port, 0);
                }
                handles = new long[0]; session = ""; deviceInfo = null;
                connected = readConnectionSnapshot(client, host, port, client.readAlbumDeviceInfo(), request, true);
            } catch (Exception error) {
                failure = error;
                boolean preserve = false;
                synchronized (lifecycle) {
                    if (!closed && request == generation.get() && active == client) {
                        cachedConnectionInfo = null;
                        browseError = browseFailureText(error);
                        preserve = sessionConnectionInfo != null && client.isOpen() && !client.isProtocolUnconfirmed()
                                && (error instanceof BrowseUnavailableException || recoverableBrowseResponse(error));
                        if (!preserve) { active = null; sessionConnectionInfo = null; }
                    }
                }
                if (!preserve) client.close();
            } finally {
                synchronized (lifecycle) { if (request == generation.get()) connecting = false; }
                notifyStateChanged();
            }
            completion.complete(connected, failure);
        };
        try { worker.execute(browse); }
        catch (java.util.concurrent.RejectedExecutionException rejected) {
            synchronized (lifecycle) {
                if (active == client && request == generation.get()) {
                    active = null; connecting = false; cachedConnectionInfo = null; sessionConnectionInfo = null;
                    browseError = "相册已关闭，未开始读取。";
                }
            }
            client.close(); notifyStateChanged(); completion.complete(null, rejected);
        }
    }

    /** Completion survives shutdownNow discarding a queued task and is delivered exactly once. */
    private final class BrowseCompletion {
        private final PtpIpClient client;
        private final int request;
        private final Callback<ConnectionInfo> callback;
        private final AtomicBoolean completed = new AtomicBoolean();
        BrowseCompletion(PtpIpClient client, int request, Callback<ConnectionInfo> callback) {
            this.client = client; this.request = request; this.callback = callback;
        }
        void complete(ConnectionInfo result, Exception error) {
            if (!completed.compareAndSet(false, true)) return;
            synchronized (lifecycle) { pendingBrowses.remove(this); }
            main.post(() -> {
                if (closed || request != generation.get()) callback.onError("相册读取已取消或会话已移交。");
                else if (error != null) callback.onError(browseFailureText(error));
                else if (active != client || !isConnected()) callback.onError("相机会话已断开，照片未就绪。");
                else callback.onSuccess(result);
            });
        }
    }

    private ConnectionInfo readConnectionSnapshot(PtpIpClient client, String host, int port, byte[] info,
                                                   int request, boolean preserveSession) throws IOException {
        PtpDeviceInfo parsed = readCameraIdentity(client, info, request);
        synchronized (lifecycle) {
            requireCurrentSession(client, request);
            if (preserveSession) sessionConnectionInfo = new ConnectionInfo(parsed.model(), host, port, 0);
        }
        for (int op : new int[] {0x1004, 0x1007, 0x1008, 0x1009}) if (!parsed.supportsOperation(op)) {
            throw new BrowseUnavailableException("相机未提供当前相册需要的照片读取能力。");
        }
        LinkedHashSet<Long> collected = new LinkedHashSet<>();
        long[] storages = PtpStorageIds.parse(client.getData(0x1004, 4096));
        if (storages.length > 64) throw new IOException("相机存储空间数量超过浏览上限。");
        for (long storage : storages) {
            byte[] entries;
            try {
                try { entries = client.getData(0x1007, 1024 * 1024, storage, 0x3801, 0); }
                catch (PtpResponseException unsupportedFormat) {
                    if (unsupportedFormat.responseCode() != 0x2014) throw unsupportedFormat;
                    entries = client.getData(0x1007, 1024 * 1024, storage, 0, 0);
                }
            } catch (PtpResponseException storageError) {
                if (storageError.responseCode() == 0x2013) continue;
                throw storageError;
            }
            for (long handle : PtpObjectHandles.parse(entries)) {
                if (handle == 0 || handle == 0xffffffffL) throw new IOException("相机返回了无效照片索引。");
                collected.add(handle);
                if (collected.size() > MAX_HANDLES) throw new IOException("相机对象过多，本版一次最多浏览 20000 个对象。");
            }
        }
        long[] observedHandles = new long[collected.size()]; int i = 0;
        for (long handle : collected) observedHandles[i++] = handle;
        ConnectionInfo connected = new ConnectionInfo(parsed.model(), host, port, observedHandles.length);
        synchronized (lifecycle) {
            requireCurrentSession(client, request);
            handles = observedHandles; session = UUID.randomUUID().toString();
            cachedConnectionInfo = connected; sessionConnectionInfo = connected; browseError = "";
        }
        return connected;
    }

    private PtpDeviceInfo readCameraIdentity(PtpIpClient client, byte[] info, int request) throws IOException {
        PtpDeviceInfo parsed = PtpDeviceInfo.parseRedacted(info);
        if (!parsed.manufacturer().toLowerCase(Locale.ROOT).contains("nikon")) {
            throw new IOException("这不是已支持的尼康无线相机。");
        }
        synchronized (lifecycle) {
            requireCurrentSession(client, request);
            deviceInfo = parsed; largeThumbnailUnavailable = false;
        }
        return parsed;
    }

    private void requireCurrentSession(PtpIpClient client, int request) throws IOException {
        if (closed || request != generation.get() || active != client || !client.isOpen() || client.isProtocolUnconfirmed()) {
            throw new IOException("无线连接已取消或相机已断开。");
        }
    }

    private void observeClient(PtpIpClient client, int request) {
        client.setOnClosed(() -> deliver(request, () -> {
            if (active == client) {
                stopPhotoLive(client, "相机连接已断开，照片直播已停止。");
                cachedConnectionInfo = null; sessionConnectionInfo = null;
                notifyStateChanged();
            }
        }));
    }

    private static boolean recoverableBrowseResponse(Exception error) {
        if (!(error instanceof PtpResponseException)) return false;
        int code = ((PtpResponseException) error).responseCode();
        return code != 0x2003 && code != 0x2004;
    }
    private static String browseFailureText(Exception error) {
        return error.getMessage() == null ? "照片暂不可用。" : error.getMessage();
    }
    private static final class BrowseUnavailableException extends IOException {
        BrowseUnavailableException(String message) { super(message); }
    }

    /** Application lifecycle notification is independent of the currently attached UI. */
    public void setLifecycleObserver(Runnable observer) { lifecycleObserver = observer; }

    /** One bounded read on the existing album worker; cancellation still delivers its completion. */
    public void keepAlive(Callback<Void> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        final int request = generation.get();
        if (closed || transferring) {
            main.post(() -> callback.onError("相册连接已关闭或正在移交，未重新连接。"));
            return;
        }
        try {
            worker.execute(() -> {
                Exception failure = null;
                PtpIpClient operationClient = null;
                try {
                    if (closed || transferring || request != generation.get()) {
                        throw new IOException("相册连接已切换，未发送保活请求。");
                    }
                    PtpIpClient client = requireClient();
                    operationClient = client;
                    if (!hasLiveSession()) {
                        throw new IOException("相册会话尚未接管，未发送保活请求。");
                    }
                    if (cachedConnectionInfo != null && deviceInfo != null && deviceInfo.supportsOperation(0x1004)) {
                        long[] storages = PtpStorageIds.parse(client.getData(0x1004, 4096));
                        if (storages.length > 64) throw new IOException("相机存储空间数量超过浏览上限。");
                    } else {
                        PtpDeviceInfo observed = PtpDeviceInfo.parseRedacted(client.readAlbumDeviceInfo());
                        if (!observed.manufacturer().toLowerCase(Locale.ROOT).contains("nikon")) {
                            throw new IOException("相机身份已改变，已停止保活。");
                        }
                    }
                } catch (Exception error) {
                    failure = error;
                    boolean terminate = false;
                    if (!recoverableBrowseResponse(error) && operationClient != null) {
                        synchronized (lifecycle) {
                            if (!closed && !transferring && request == generation.get() && active == operationClient) {
                                cachedConnectionInfo = null; sessionConnectionInfo = null; connecting = false;
                                terminate = true;
                            }
                        }
                    }
                    if (terminate) { operationClient.close(); notifyStateChanged(); }
                }
                final Exception error = failure;
                main.post(() -> {
                    if (error != null) {
                        String message = error.getMessage();
                        callback.onError(message == null ? "相册只读保活未完成。" : message);
                    } else if (closed || transferring || request != generation.get() || !hasLiveSession()) {
                        callback.onError("相册连接已切换，未重新连接。");
                    } else callback.onSuccess(null);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            main.post(() -> callback.onError("相册已关闭，未发送保活请求。"));
        }
    }

    /** Drains an in-flight read before moving the owned connection; never disconnects/reconnects. */
    public void transferForPictureControl(Callback<PtpIpClient> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        if (rawReadReserved.get()) { main.post(() -> { if (!closed) callback.onError("RAW 原文件正在处理中，请完成后再导入预设；相机连接会继续保留。"); }); return; }
        stopPhotoLive();
        final int request;
        synchronized (lifecycle) {
            if (rawReadReserved.get()) { main.post(() -> callback.onError("RAW 原文件正在处理中，请完成后再导入预设；相机连接会继续保留。")); return; }
            if (closed || connecting || transferring || !hasLiveSession()) {
                main.post(() -> callback.onError("当前相册连接不可移交，请重新连接相机。"));
                return;
            }
            transferring = true;
            previewGeneration.incrementAndGet();
            request = generation.incrementAndGet();
        }
        notifyStateChanged();
        try {
            worker.execute(() -> {
                PtpIpClient transferred = null;
                try {
                    synchronized (lifecycle) {
                        if (closed || request != generation.get()) return;
                        PtpIpClient source = active;
                        if (source == null || !source.isOpen()) throw new IOException("相册连接已断开，未重新连接。");
                        transferred = source.transferToPictureControl();
                        active = null;
                        cachedConnectionInfo = null; sessionConnectionInfo = null; browseError = "";
                        handles = new long[0]; session = ""; deviceInfo = null;
                        transferring = false;
                    }
                    final PtpIpClient owned = transferred;
                    main.post(() -> {
                        if (closed || request != generation.get()) owned.close();
                        else {
                            try { callback.onSuccess(owned); }
                            catch (RuntimeException failure) { owned.close(); throw failure; }
                        }
                    });
                    notifyStateChanged();
                } catch (Exception failure) {
                    if (transferred != null) transferred.close();
                    synchronized (lifecycle) { if (request == generation.get()) transferring = false; }
                    notifyStateChanged();
                    String message = failure.getMessage();
                    deliver(request, () -> callback.onError(message == null ? "无法移交相册连接，未开始导入。" : message));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            synchronized (lifecycle) { if (request == generation.get()) transferring = false; }
            notifyStateChanged();
            callback.onError("相册已关闭，未开始导入。");
        }
    }
    /** Main-thread state notification for the currently attached UI only. */
    public void setConnectionListener(Runnable listener) {
        synchronized (lifecycle) { connectionListener = listener; }
    }

    /** An obsolete panel must not detach a newer panel's listener. */
    public boolean clearConnectionListener(Runnable expected) {
        synchronized (lifecycle) {
            if (connectionListener != expected) return false;
            connectionListener = null;
            return true;
        }
    }

    private void notifyStateChanged() {
        main.post(() -> {
            Runnable observer = lifecycleObserver;
            if (observer != null) try { observer.run(); } catch (RuntimeException ignored) { }
            Runnable listener = closed ? null : connectionListener;
            if (listener != null) try { listener.run(); } catch (RuntimeException ignored) { }
        });
    }

    // UI browsing has a separate cancellation boundary from connection ownership and live reception.
    private final AtomicInteger browseGeneration = new AtomicInteger();

    /** Retires queued page/thumbnail work; an already sent PTP operation drains its response. */
    public void cancelBrowseRequests() { browseGeneration.incrementAndGet(); }

    private void requireBrowseRequest(int request) throws IOException {
        if (request != browseGeneration.get()) throw new IOException("相册浏览任务已取消。");
    }

    /** nextOffset is a snapshot cursor, not a camera-side page number. */
    public void loadPage(int offset, int limit, Callback<PhotoPage> callback) {
        final int requestedGeneration = generation.get();
        final int browseRequest = browseGeneration.get();
        submit(callback, () -> {
            requireBrowseRequest(browseRequest);
            PtpIpClient client = requireClient();
            if (offset < 0 || offset > handles.length || limit < 1 || limit > 60) throw new IOException("照片分页参数无效。");
            List<PhotoItem> photos = new ArrayList<>();
            int cursor = offset, scanned = 0;
            while (cursor < handles.length && photos.size() < limit && scanned++ < 300) {
                requireBrowseRequest(browseRequest);
                if (requestedGeneration != generation.get()) throw new IOException("照片读取已交还相机连接。");
                long handle = handles[cursor++];
                PtpObjectInfo info;
                try { info = PtpObjectInfo.parse(client.getData(0x1008, 65536, handle)); }
                catch (PtpResponseException error) { if (error.responseCode() == 0x2009) continue; throw error; }
                requireBrowseRequest(browseRequest);
                if (PhotoFormat.of(info) == PhotoFormat.JPEG) photos.add(new PhotoItem(session, handle, info));
            }
            requireBrowseRequest(browseRequest);
            return new PhotoPage(photos, cursor, handles.length);
        });
    }

    public void loadThumbnail(PhotoItem photo, Callback<byte[]> callback) {
        final int browseRequest = browseGeneration.get();
        submit(callback, () -> {
            requireBrowseRequest(browseRequest);
            byte[] thumbnail = readBestThumbnail(photo, browseRequest).bytes;
            requireBrowseRequest(browseRequest);
            return thumbnail;
        });
    }

    /** An explicit NEF inventory. JPEG browsing and automatic live reception are unchanged. */
    public void loadRawPage(int offset, int limit, Callback<PhotoPage> callback) {
        final int request = generation.get(), browseRequest = browseGeneration.get();
        submitRaw(request, callback, () -> {
            requireRawIdle(); requireBrowseRequest(browseRequest);
            if (offset < 0 || limit < 1 || limit > 60) throw new IOException("RAW 分页参数无效。");
            PtpIpClient client = requireClient();
            ensureRawInventory(client, request, offset == 0);
            if (offset > rawHandles.length) throw new IOException("RAW 列表已变化，请重新读取。");
            List<PhotoItem> items = new ArrayList<>(); int cursor = offset, scanned = 0;
            while (cursor < rawHandles.length && items.size() < limit && scanned++ < 300) {
                requireBrowseRequest(browseRequest); requireRawCurrent(client, request);
                long handle = rawHandles[cursor++]; PtpObjectInfo info = rawInfo(client, handle);
                if (info != null && PhotoFormat.of(info) == PhotoFormat.NEF) items.add(new PhotoItem(session, handle, info));
            }
            requireBrowseRequest(browseRequest); requireRawCurrent(client, request);
            return new PhotoPage(items, cursor, rawHandles.length);
        });
    }

    /** Explicit selection from the RAW list; never applies a preset or rewrites NEF data. */
    public void downloadRaw(PhotoItem raw, RawDownloadCallback callback) {
        final int request = generation.get();
        submitRaw(request, callback, () -> {
            requireRawIdle();
            PtpIpClient client = requirePhoto(raw);
            if (!raw.isRaw()) throw new IOException("请选择相机 RAW 列表中的 NEF 原文件。");
            return prepareRaw(client, raw, null, "", request, callback);
        });
    }

    /** Only an unambiguous pair from this same identified camera can be selected automatically. */
    public void downloadRawForJpeg(PhotoItem jpeg, RawDownloadCallback callback) {
        final int request = generation.get();
        submitRaw(request, callback, () -> {
            requireRawIdle(); PtpIpClient client = requirePhoto(jpeg);
            if (jpeg.format() != PhotoFormat.JPEG) throw new IOException("请选择相机中的 JPG 照片，或在 RAW 列表明确选择 NEF。");
            verifyDownloadDescriptor(client, jpeg);
            String source = rawSourceIdentity(client, jpeg);
            throw new RawContinuation(true, () -> {
                RawPhotoDownload saved = rawStore().find(source);
                if (saved != null) { deliver(request, () -> callback.onProgress(saved.byteSize(), saved.byteSize())); return saved; }
                throw new RawContinuation(false, () -> discoverRawForJpeg(client, jpeg, source, request, callback), null);
            }, null);
        });
    }

    /** A local JPG must carry exact saved camera evidence; displayed filenames never authorize pairing. */
    public void downloadRawForLocal(DownloadedPhoto original, RawDownloadCallback callback) {
        final int request = generation.get();
        submitRaw(request, callback, () -> {
            throw new RawContinuation(true, () -> {
                RawJpegReferenceStore.Reference reference = jpegRawReferences().find(original);
                if (reference == null) throw new IOException("这张 JPG 没有可核对的相机来源记录。请在相机 RAW 文件列表明确选择 NEF 原文件。");
                RawPhotoDownload saved = rawStore().find(reference.sourceIdentity);
                if (saved != null) { deliver(request, () -> callback.onProgress(saved.byteSize(), saved.byteSize())); return saved; }
                throw new RawContinuation(false, () -> {
                    requireRawIdle(); PtpIpClient client = requireClient();
                    if (!trustedCamera(client) || !reference.camera.equals(cameraKey(client, deviceInfo)))
                        throw new IOException("请连接拍摄这张照片的原相机，或在 RAW 文件列表明确选择 NEF 原文件。");
                    PhotoItem jpeg = new PhotoItem(session, reference.handle, reference.info);
                    return discoverRawForJpeg(client, jpeg, reference.sourceIdentity, request, callback);
                }, null);
            }, null);
        });
    }

    private RawPhotoDownload discoverRawForJpeg(PtpIpClient client, PhotoItem jpeg, String source, int request, RawDownloadCallback callback) throws Exception {
            requireRawCurrent(client, request); verifyDownloadDescriptor(client, jpeg);
            deliver(request, () -> callback.onStage("discovery"));
            ensureRawInventory(client, request, true);
            if (!rawInventoryComplete) throw new IOException("部分存储卡暂不可用，无法唯一核对 RAW。请在 RAW 文件列表明确选择原文件。");
            List<PhotoItem> rawFiles = new ArrayList<>();
            final int metadataTotal = rawHandles.length;
            for (int i = 0; i < metadataTotal; i++) {
                requireRawCurrent(client, request); long handle = rawHandles[i]; PtpObjectInfo info = rawInfo(client, handle);
                if (info != null && PhotoFormat.of(info) == PhotoFormat.NEF) rawFiles.add(new PhotoItem(session, handle, info));
                final int checked = i + 1;
                if (checked % 20 == 0 || checked == metadataTotal) deliver(request, () -> callback.onDiscoveryProgress(checked, metadataTotal));
            }
            if (!rawInventoryComplete) throw new IOException("RAW 列表有文件暂不可读，未完成全部文件核对。请在 RAW 文件列表明确选择原文件。");
            verifyDownloadDescriptor(client, jpeg);
            PhotoItem raw = RawPhotoPairing.unique(jpeg.objectInfo(), rawFiles, trustedCamera(client));
            return prepareRaw(client, raw, jpeg, source, request, callback);
    }

    /** Local completed originals survive connection changes and app restarts. */
    public void listRawDownloads(Callback<List<RawPhotoDownload>> callback) {
        submitRawLocal(callback, () -> rawStore().listForDisplay());
    }
    public void findRawDownload(String sourceIdentity, Callback<RawPhotoDownload> callback) {
        submitRawLocal(callback, () -> rawStore().find(sourceIdentity));
    }
    /** Uses the system photo collection directly; no document/path picker is opened. */
    public void publishRaw(RawPhotoDownload photo, Callback<String> callback) {
        submitRawLocal(callback, () -> new RawPhotoPublisher(context).publish(photo));
    }

    private RawPhotoDownload prepareRaw(PtpIpClient client, PhotoItem raw, PhotoItem jpeg, String alias, int request, RawDownloadCallback callback) throws Exception {
            requireRawCurrent(client, request); verifyDownloadDescriptor(client, raw);
            if (raw.byteSize() < 32 || raw.byteSize() > MAX_PHOTO_BYTES) throw new IOException("NEF 原文件大小无效，本版最多下载 512 MB。");
            String identity = rawSourceIdentity(client, raw); RawPhotoStore store = rawStore();
            throw new RawContinuation(true, () -> {
                RawPhotoDownload existing = store.find(identity);
                if (existing != null) {
                    deliver(request, () -> callback.onProgress(existing.byteSize(), existing.byteSize()));
                    return alias.isEmpty() ? existing : store.alias(alias, existing);
                }
                throw new RawContinuation(false, () -> receiveRaw(client, raw, jpeg, alias, identity, store, request, callback), null);
            }, null);
    }
    private RawPhotoDownload receiveRaw(PtpIpClient client, PhotoItem raw, PhotoItem jpeg, String alias, String identity,
                                       RawPhotoStore store, int request, RawDownloadCallback callback) throws Exception {
            requireRawCurrent(client, request); verifyDownloadDescriptor(client, raw);
            if (jpeg != null) verifyDownloadDescriptor(client, jpeg);
            PhotoLocalTasks.Lease lease = PhotoLocalTasks.use(); File temporary;
            try { temporary = store.createTemporary(); } catch (Exception failure) { lease.close(); throw failure; }
            final Runnable cleanup = () -> { lease.close(); if (temporary.exists()) try { java.nio.file.Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) { } };
            boolean deferred = false;
            try {
                deliver(request, () -> callback.onStage("receiving")); long[] last = {0};
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    client.readTo(0x1009, output, MAX_PHOTO_BYTES, raw.byteSize(), (received, total) -> {
                        long now = System.nanoTime();
                        if (received == total || now - last[0] >= TimeUnit.MILLISECONDS.toNanos(200)) {
                            last[0] = now; deliver(request, () -> callback.onProgress(received, total));
                        }
                    }, raw.handle());
                    output.getFD().sync();
                }
                deliver(request, () -> callback.onStage("verifying"));
                requireRawCurrent(client, request); verifyDownloadDescriptor(client, raw);
                if (jpeg != null) verifyDownloadDescriptor(client, jpeg);
                // Full-file hashing and persistence run off the camera worker. The
                // current session can continue its ordinary read-only keepalive.
                deferred = true;
                throw new RawContinuation(true, () -> {
                    try {
                        PhotoFormat.validateNef(temporary, raw.byteSize());
                        RawPhotoDownload result = store.commit(identity, temporary, raw.filename());
                        return alias.isEmpty() ? result : store.alias(alias, result);
                    } finally { cleanup.run(); }
                }, cleanup);
            } finally { if (!deferred) cleanup.run(); }
    }

    private void ensureRawInventory(PtpIpClient client, int request, boolean refresh) throws IOException {
        requireRawCurrent(client, request);
        if (!refresh && rawSnapshotSession.equals(session)) return;
        long[] storages = PtpStorageIds.parse(client.getData(0x1004, 4096));
        if (storages.length > 64) throw new IOException("RAW 存储空间数量超过上限。");
        LinkedHashSet<Long> all = new LinkedHashSet<>(); boolean complete = true;
        for (long storage : storages) {
            requireRawCurrent(client, request); byte[] entries;
            try { entries = client.getData(0x1007, 1024 * 1024, storage, 0, 0); }
            catch (PtpResponseException unavailable) { if (unavailable.responseCode() != 0x2013) throw unavailable; complete = false; continue; }
            for (long handle : PtpObjectHandles.parse(entries)) {
                if (handle == 0 || handle == 0xffffffffL || !all.add(handle)) throw new IOException("RAW 索引有无效或重复对象，请刷新相机。");
                if (all.size() > MAX_HANDLES) throw new IOException("相机对象过多，RAW 一次最多浏览 20000 个对象。");
            }
        }
        requireRawCurrent(client, request); rawHandles = new long[all.size()]; int i = 0;
        for (long handle : all) rawHandles[i++] = handle;
        rawMetadata.clear(); rawSnapshotSession = session; rawInventoryComplete = complete;
    }
    private PtpObjectInfo rawInfo(PtpIpClient client, long handle) throws IOException {
        PtpObjectInfo known = rawMetadata.get(handle); if (known != null) return known;
        try {
            PtpObjectInfo info = PtpObjectInfo.parse(client.getData(0x1008, 65536, handle)); rawMetadata.put(handle, info); return info;
        } catch (PtpResponseException missing) { if (missing.responseCode() == 0x2009) { rawInventoryComplete = false; return null; } throw missing; }
    }
    private String rawSourceIdentity(PtpIpClient client, PhotoItem photo) {
        String camera = cameraKey(client, deviceInfo);
        if (!trustedCamera(client)) camera += "\n" + session;
        return PhotoLiveLedger.identity(camera, photo.handle(), photo.objectInfo());
    }
    private void requireRawIdle() throws IOException {
        synchronized (lifecycle) {
            if (liveRun != null && (liveRun.enabled || liveRun.workQueued || liveRun.current != null))
                throw new IOException("请先停止照片直播，再下载 RAW 原文件；相机连接会继续保留。");
            if (connecting || deletingDownloadedPhotos) throw new IOException("照片任务正在处理中，请完成后再读取 RAW。");
        }
    }
    private void requireRawCurrent(PtpIpClient client, int request) throws IOException {
        if (closed || generation.get() != request || active != client || !client.isOpen()) throw new IOException("RAW 读取已停止，请检查相机连接后重试。");
    }
    private synchronized RawPhotoStore rawStore() throws IOException {
        if (completedRaw == null) {
            File parent = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            File directory = new File(parent == null ? context.getFilesDir() : parent, "camera-raw");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建本机 RAW 原文件目录。");
            completedRaw = new RawPhotoStore(directory);
        }
        return completedRaw;
    }
    private RawJpegReferenceStore jpegRawReferences() throws IOException {
        return new RawJpegReferenceStore(context.getFilesDir(), context.getExternalFilesDir(Environment.DIRECTORY_PICTURES));
    }
    private void rememberJpegRawReference(String camera, boolean trusted, PhotoItem photo, DownloadedPhoto saved) {
        if (!trusted) return;
        try { rawLocalWorker.execute(() -> {
            try { jpegRawReferences().bind(camera, true, photo, saved); }
            catch (Exception ignored) { /* Optional RAW evidence can never invalidate a completed JPEG. */ }
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    private static final class RawContinuation extends Exception {
        final boolean local; final Work<?> work; final Runnable rejected;
        RawContinuation(boolean local, Work<?> work, Runnable rejected) { this.local = local; this.work = work; this.rejected = rejected; }
    }
    public boolean isRawBusy() { return rawReadReserved.get(); }
    private <T> void submitRaw(int request, Callback<T> callback, Work<T> work) {
        if (callback == null) throw new IllegalArgumentException("callback");
        synchronized (lifecycle) {
            if (closed || transferring || !rawReadReserved.compareAndSet(false, true)) {
                main.post(() -> { if (!closed) callback.onError("RAW 或相机任务正在处理中，请稍后重试。"); }); return;
            }
        }
        runRawStep(request, callback, work, false, null);
    }
    @SuppressWarnings("unchecked")
    private <T> void runRawStep(int request, Callback<T> callback, Work<?> work, boolean local, Runnable rejected) {
        try { (local ? rawLocalWorker : worker).execute(() -> {
            try {
                if (!local && (closed || generation.get() != request)) throw new IOException("RAW 操作已取消，请重新读取。");
                T value = (T) work.run(); rawReadReserved.set(false);
                main.post(() -> {
                    if (closed) return;
                    if (generation.get() == request) callback.onSuccess(value);
                    else callback.onError("RAW 操作已结束；已完成的原文件会保留在本机。");
                });
            } catch (RawContinuation next) { runRawStep(request, callback, next.work, next.local, next.rejected); }
            catch (Exception failure) {
                rawReadReserved.set(false); main.post(() -> { if (!closed) callback.onError(failure.getMessage() == null ? "RAW 操作未完成，请重试。" : failure.getMessage()); });
            }
        }); } catch (java.util.concurrent.RejectedExecutionException stopped) {
            if (rejected != null) rejected.run(); rawReadReserved.set(false);
            main.post(() -> { if (!closed) callback.onError("RAW 操作已停止。"); });
        }
    }
    private <T> void submitRawLocal(Callback<T> callback, Work<T> work) {
        if (callback == null) throw new IllegalArgumentException("callback");
        try { rawLocalWorker.execute(() -> {
            try { T result = work.run(); main.post(() -> { if (!closed) callback.onSuccess(result); }); }
            catch (Exception failure) { main.post(() -> { if (!closed) callback.onError(failure.getMessage() == null ? "RAW 原文件操作未完成，应用内副本仍保留。" : failure.getMessage()); }); }
        }); } catch (java.util.concurrent.RejectedExecutionException stopped) { main.post(() -> callback.onError("RAW 本地任务已停止。")); }
    }

    /** Reads only an already published preview. A cache miss never creates a camera request. */
    public void loadCachedPreview(PhotoItem photo, Callback<PhotoPreview> callback) {
        submit(callback, () -> previews.acquire(photo));
    }

    /** Prefers advertised Nikon GetLargeThumb, with GetThumb fallback; never requests the original. */
    public void loadPreview(PhotoItem photo, PreviewCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        final int requestedGeneration = generation.get();
        final int previewRequest = previewGeneration.incrementAndGet();
        PreviewCallback current = new PreviewCallback() {
            @Override public void onSuccess(PhotoPreview result) {
                if (previewRequest == previewGeneration.get()) callback.onSuccess(result);
                else result.close();
            }
            @Override public void onError(String message) {
                if (previewRequest == previewGeneration.get()) callback.onError(message);
            }
            @Override public void onProgress(long received, long total) {
                if (previewRequest == previewGeneration.get()) callback.onProgress(received, total);
            }
        };
        submit(requestedGeneration, current, () -> {
            if (previewRequest != previewGeneration.get()) throw new IOException("照片预览已取消。");
            PhotoPreview cached = previews.acquire(photo);
            if (cached != null) return cached;
            CameraThumbnail thumbnail = readBestThumbnail(photo);
            byte[] jpeg = thumbnail.bytes;
            File temporary = previews.createTemporary();
            try {
                try (FileOutputStream file = new FileOutputStream(temporary)) {
                    file.write(jpeg);
                    file.getFD().sync();
                }
                deliver(requestedGeneration, () -> current.onProgress(jpeg.length, jpeg.length));
                return previews.publish(photo, temporary, thumbnail.large);
            } finally { previews.discardTemporary(temporary); }
        });
    }

    /** Cancels queued/display callbacks; an in-flight PTP read finishes to keep the session usable. */
    public void cancelPreview() { previewGeneration.incrementAndGet(); }

    public void download(PhotoItem photo, DownloadCallback callback) {
        final int requestedGeneration = generation.get();
        submit(requestedGeneration, callback, () -> {
            try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.use()) {
            if (photo == null) throw new IOException("照片信息无效，请刷新相机照片列表。");
            if (photo.byteSize() > MAX_PHOTO_BYTES) throw new IOException("照片超过本版 512 MB 下载上限。");
            PtpIpClient client = requirePhoto(photo);
            verifyDownloadDescriptor(client, photo);
            final String originalIdentity;
            final String completionIdentity;
            final String referenceCamera;
            final boolean referenceTrusted;
            synchronized (lifecycle) {
                originalIdentity = active == client && deviceInfo != null && session.equals(photo.session)
                        ? PhotoLiveLedger.identity(cameraKey(client, deviceInfo), photo.handle(), photo.objectInfo()) : "";
                completionIdentity = originalIdentity.isEmpty() || trustedCamera(client) ? originalIdentity
                        : PhotoLiveLedger.identity(cameraKey(client, deviceInfo) + "\n" + photo.session, photo.handle(), photo.objectInfo());
                referenceTrusted = deviceInfo != null && trustedCamera(client);
                referenceCamera = referenceTrusted ? cameraKey(client, deviceInfo) : "";
            }
            File directory = downloadDirectory();
            String display = safeJpegName(photo.filename());
            PhotoOriginalStore originals = originalStore();
            DownloadedPhoto alreadySaved = originals.find(completionIdentity, display, photo.byteSize());
            if (alreadySaved != null) {
                deliver(requestedGeneration, () -> callback.onProgress(alreadySaved.byteSize(), alreadySaved.byteSize()));
                rememberJpegRawReference(referenceCamera, referenceTrusted, photo, alreadySaved);
                notifyOriginalDownload(originalIdentity, alreadySaved);
                return alreadySaved;
            }
            File temporary = File.createTempFile("receiving-", ".part", directory);
            boolean completed = false;
            try {
                final long[] last = {0};
                try (FileOutputStream file = new FileOutputStream(temporary)) {
                    client.readTo(0x1009, file, MAX_PHOTO_BYTES, photo.byteSize(), (received, total) -> {
                        long now = System.nanoTime();
                        if (received == total || now - last[0] >= 200000000L) {
                            last[0] = now;
                            deliver(requestedGeneration, () -> callback.onProgress(received, total));
                        }
                    }, photo.handle());
                    file.getFD().sync();
                }
                PhotoPreviewCache.validateJpeg(temporary, photo.byteSize() > 0 ? photo.byteSize() : temporary.length());
                verifyDownloadDescriptor(client, photo);
                DownloadedPhoto saved = originals.commit(completionIdentity, temporary, display);
                completed = true;
                rememberJpegRawReference(referenceCamera, referenceTrusted, photo, saved);
                notifyOriginalDownload(originalIdentity, saved);
                return saved;
            } finally {
                if (!completed && temporary.exists()) temporary.delete();
            }
            }
        });
    }

    private void notifyOriginalDownload(String identity, DownloadedPhoto saved) {
        OriginalDownloadIdentityObserver identityObserver = originalDownloadIdentityObserver;
        if (identityObserver != null && !identity.isEmpty()) try { identityObserver.onDownloaded(identity, saved); }
        catch (RuntimeException publicationUnavailable) { /* A verified original remains valid if local metadata cannot be saved. */ }
        java.util.function.Consumer<DownloadedPhoto> observer = originalDownloadObserver;
        if (observer != null) try { observer.accept(saved); }
        catch (RuntimeException publicationUnavailable) { /* The completed original remains available for a local gallery retry. */ }
    }
    private static void verifyDownloadDescriptor(PtpIpClient client, PhotoItem photo) throws IOException {
        PtpObjectInfo current = PtpObjectInfo.parse(client.getData(0x1008, 65536, photo.handle()));
        if (!Arrays.equals(current.toBytes(), photo.objectInfo().toBytes())) throw new IOException("相机上的照片已变化，请刷新列表后重新下载。");
    }

    public void listDownloads(Callback<List<DownloadedPhoto>> callback) {
        submit(callback, () -> {
            File[] files = downloadDirectory().listFiles(file -> file.isFile() && jpegFilename(file.getName()));
            if (files == null) throw new IOException("无法读取已下载照片目录。");
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            List<DownloadedPhoto> photos = new ArrayList<>();
            for (File file : files) {
                String name = file.getName(); int marker = name.indexOf("__");
                photos.add(new DownloadedPhoto(file, marker < 0 ? name : name.substring(marker + 2)));
            }
            return Collections.unmodifiableList(photos);
        });
    }

    /** Disconnect cancels network activity; queued callbacks from the old generation are suppressed. */
    public void disconnect() {
        stopPhotoLive();
        cancelPreview();
        generation.incrementAndGet();
        List<BrowseCompletion> cancelled;
        synchronized (lifecycle) {
            transferring = false; connecting = false; cachedConnectionInfo = null; sessionConnectionInfo = null; browseError = "";
            PtpIpClient client = active; active = null; if (client != null) client.close();
            cancelled = new ArrayList<>(pendingBrowses);
        }
        for (BrowseCompletion completion : cancelled) {
            completion.complete(null, new IOException("相册读取已取消。"));
        }
        notifyStateChanged();
    }
    @Override public void close() {
        closed = true; disconnect(); liveClock.shutdownNow(); worker.shutdownNow(); localDeletes.shutdown(); rawLocalWorker.shutdown();
        synchronized (lifecycle) {
            LiveRun run = liveRun;
            if (run != null && !run.projectId.isEmpty()) {
                List<PhotoLiveReceipt> finalReceipts = new ArrayList<>();
                for (LiveCandidate item : run.recent.values()) finalReceipts.add(receiptSnapshotLocked(run, item, true).archived());
                try { receiptWorker.execute(() -> { try { receiptStore().write(run.projectId, run.camera, run.trustedCamera, finalReceipts); } catch (IOException ignored) { } }); }
                catch (java.util.concurrent.RejectedExecutionException ignored) { }
            }
        }
        receiptWorker.shutdown();
        if (Looper.myLooper() != Looper.getMainLooper() && !Thread.currentThread().getName().equals("photo-live-receipts")) {
            try { receiptWorker.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        previews.close();
    }

    private <T> void submit(Callback<T> callback, Work<T> work) {
        submit(generation.get(), callback, work);
    }
    private <T> void submit(int request, Callback<T> callback, Work<T> work) {
        if (callback == null) throw new IllegalArgumentException("callback");
        if (closed || transferring) return;
        try {
            worker.execute(() -> {
                if (closed || request != generation.get()) return;
                try {
                    T result = work.run();
                    if (closed) discard(result);
                    else main.post(() -> {
                        if (!closed && request == generation.get()) callback.onSuccess(result);
                        else discard(result);
                    });
                }
                catch (Exception error) {
                    String message = error instanceof PtpResponseException
                            ? "相机未完成照片读取（" + String.format(Locale.ROOT, "%04X", ((PtpResponseException) error).responseCode()) + "），请检查相机状态。"
                            : error.getMessage();
                    deliver(request, () -> callback.onError(message == null ? "无线相册操作未完成。" : message));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    private void deliver(int request, Runnable callback) { if (!closed) main.post(() -> { if (!closed && request == generation.get()) callback.run(); }); }
    private static void discard(Object result) { if (result instanceof PhotoPreview) ((PhotoPreview) result).close(); }
    private PtpIpClient requireClient() throws IOException { PtpIpClient c = active; if (c == null || !c.isOpen()) throw new IOException("无线连接已断开，请重新连接相机。"); return c; }
    private PtpIpClient requirePhoto(PhotoItem photo) throws IOException {
        PtpIpClient c = requireClient();
        if (photo == null || !session.equals(photo.session)) throw new IOException("照片来自旧的连接，请刷新列表后再操作。");
        return c;
    }
    private File downloadDirectory() throws IOException {
        File parent = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (parent == null) parent = new File(context.getFilesDir(), "pictures");
        File directory = new File(parent, "camera-album");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建本机照片目录。");
        return directory;
    }
    private PhotoOriginalStore originalStore() throws IOException {
        if (completedOriginals == null) completedOriginals = new PhotoOriginalStore(downloadDirectory());
        return completedOriginals;
    }
    private byte[] readThumbnail(PhotoItem photo) throws IOException {
        PtpIpClient client = requirePhoto(photo);
        if (!deviceInfo.supportsOperation(0x100a)) {
            throw new IOException("相机未提供缩略图读取能力，可选择下载原图后查看。");
        }
        byte[] jpeg = client.getData(0x100a, MAX_PREVIEW_BYTES, photo.handle());
        if (!completeJpeg(jpeg)) {
            throw new IOException("相机没有返回完整的 JPEG 缩略图，可选择下载原图后查看。");
        }
        return jpeg;
    }

    private CameraThumbnail readBestThumbnail(PhotoItem photo) throws IOException {
        return readBestThumbnail(photo, null);
    }

    private CameraThumbnail readBestThumbnail(PhotoItem photo, Integer browseRequest) throws IOException {
        if (browseRequest != null) requireBrowseRequest(browseRequest);
        PtpIpClient client = requirePhoto(photo);
        if (deviceInfo.supportsOperation(0x90c4) && !largeThumbnailUnavailable) {
            try {
                byte[] large = client.getLargeThumbnail(photo.handle());
                if (browseRequest != null) requireBrowseRequest(browseRequest);
                if (completeJpeg(large)) return new CameraThumbnail(large, true);
                throw new IOException("相机返回的高清 JPEG 预览不完整，请重试预览或下载原图。");
            } catch (PtpResponseException response) {
                int code = response.responseCode();
                if (code != 0x2005 && code != 0x2010) throw response;
                // Do not repeatedly ask a camera which explicitly rejects the advertised operation.
                if (code == 0x2005) largeThumbnailUnavailable = true;
            }
        }
        if (browseRequest != null) requireBrowseRequest(browseRequest);
        return new CameraThumbnail(readThumbnail(photo), false);
    }

    private static boolean completeJpeg(byte[] jpeg) {
        return jpeg.length >= 4 && (jpeg[0] & 255) == 255 && (jpeg[1] & 255) == 216
                && (jpeg[jpeg.length - 2] & 255) == 255 && (jpeg[jpeg.length - 1] & 255) == 217;
    }

    private static final class CameraThumbnail {
        final byte[] bytes;
        final boolean large;
        CameraThumbnail(byte[] bytes, boolean large) { this.bytes = bytes; this.large = large; }
    }
    private static String safeJpegName(String name) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < name.length() && safe.length() < 100; i++) {
            char c = name.charAt(i); safe.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        if (safe.length() == 0) safe.append("photo");
        String result = safe.toString();
        if (!jpegFilename(result)) result += ".jpg";
        return result;
    }
    private static boolean jpegFilename(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }
}

package cc.huevia.album;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import cc.huevia.library.PresetLibrary;
import cc.huevia.library.PresetRecord;
import cc.huevia.library.PresetDownloadTasks;
import cc.huevia.ncp.PresetPreviewProfiles;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime-owned local effect queue. Original reception never waits for disk journaling or rendering. */
public final class PhotoProjectPresets {
    /** Transient rendering state; durable task records remain untouched by pixel-progress events. */
    public static final class Processing {
        public final String projectId, identity, filename, presetName, stage;
        public final double progress;
        private Processing(PhotoProjectPresetStore.Task task, double progress, String stage) {
            this.projectId = task.projectId; this.identity = task.identity; this.filename = task.sourceDisplayName;
            this.presetName = task.presetName; this.progress = Math.max(0, Math.min(.99, progress));
            this.stage = stage == null ? "套用预设" : stage;
        }
    }
    public interface WorkHost {
        AutoCloseable beginPhotoWork();
        void onChanged();
    }

    private final Context context;
    private final AlbumController album;
    private final PhotoPresetBatch batch;
    private final WorkHost host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "project-preset-queue"); thread.setDaemon(true); return thread;
    });
    private final Object initialization = new Object();
    private final AtomicBoolean drainQueued = new AtomicBoolean();
    private final AtomicInteger originalLookups = new AtomicInteger();
    private final Set<Runnable> observers = new LinkedHashSet<>();
    private final Runnable batchChanged = this::wake;
    private final Runnable originalProgress = this::originalChanged;
    private final Map<String, String> enqueueErrors = new LinkedHashMap<>();
    private final Map<String, String> projectNames = new LinkedHashMap<>();
    private volatile PhotoProjectPresetStore store;
    private volatile Map<String, String> statuses = Collections.emptyMap();
    private volatile Set<String> retryable = Collections.emptySet(), pending = Collections.emptySet();
    private volatile boolean paused, working;
    private volatile Processing processing;
    private volatile int queuedCount, failedCount;
    private volatile String error = "", initializationError = "", pauseReason = "";
    private PhotoProjectPresetStore.Task active;
    private AutoCloseable serviceLease;
    private PhotoLocalTasks.Lease localLease;
    private boolean projectLookup;
    private String rebuiltError;

    public PhotoProjectPresets(Context context, AlbumController album, PhotoPresetBatch batch, WorkHost host) {
        if (context == null || album == null || batch == null || host == null) throw new IllegalArgumentException("Missing project preset runtime");
        Context application = context.getApplicationContext(); this.context = application == null ? context : application;
        this.album = album; this.batch = batch; this.host = host;
        batch.addObserver(batchChanged); album.addPhotoLiveObserver(originalProgress);
        album.addPhotoLibraryObserver(originalProgress);
        worker.execute(() -> {
            try { requireStore(); rebuild(); compensateAll(); wake(); }
            catch (Exception failure) { fail(failure); }
        });
    }

    public void listPresets(AlbumController.Callback<List<PresetRecord>> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        worker.execute(() -> {
            try { List<PresetRecord> result = new PresetLibrary(context.getFilesDir()).list(); main.post(() -> callback.onSuccess(result)); }
            catch (Exception failure) { main.post(() -> callback.onError(message(failure))); }
        });
    }

    public static final class PresetDeletion {
        public final PhotoProjectPresetStore.RemovalReferences references;
        public final List<String> projectNames;
        PresetDeletion(PhotoProjectPresetStore.RemovalReferences references, List<String> names) {
            this.references = references; this.projectNames = Collections.unmodifiableList(names);
        }
    }

    public static final class PresetDeletionResult {
        public final PresetLibrary.RemoveResult removal;
        public final int clearedDefaults, retryTasks;
        PresetDeletionResult(PresetLibrary.RemoveResult removal, int cleared, int retryTasks) {
            this.removal = removal; this.clearedDefaults = cleared; this.retryTasks = retryTasks;
        }
    }

    public void inspectPresetDeletion(String presetId, AlbumController.Callback<PresetDeletion> callback) {
        album.listPhotoLiveProjects(new AlbumController.Callback<List<PhotoLiveProject>>() {
            @Override public void onSuccess(List<PhotoLiveProject> projects) {
                worker.execute(() -> {
                    try {
                        requireDeletionIdle();
                        if (PresetDownloadTasks.shared().isActive(presetId)) throw new IOException("这份预设正在下载或保存，请完成后再删除。");
                        PhotoProjectPresetStore.RemovalReferences references = requireStore().inspectRemoval(presetId);
                        List<String> names = new ArrayList<>();
                        for (PhotoProjectPresetStore.Config config : references.configs) {
                            String name = "项目 " + config.projectId.substring(0, 8);
                            for (PhotoLiveProject project : projects) if (project.id.equals(config.projectId)) { name = project.name; break; }
                            names.add(name);
                        }
                        PresetDeletion result = new PresetDeletion(references, names); main.post(() -> callback.onSuccess(result));
                    } catch (Exception failure) { main.post(() -> callback.onError(message(failure))); }
                });
            }
            @Override public void onError(String text) { callback.onError(text); }
        });
    }

    /** Run in the same serial queue as defaults and automatic processing; recheck confirmation. */
    public void removeLocalPreset(PresetLibrary library, PresetDeletion confirmed, AlbumController.Callback<PresetDeletionResult> callback) {
        worker.execute(() -> {
            int cleared = 0;
            try {
                requireDeletionIdle();
                if (library == null || confirmed == null) throw new IOException("请重新选择要删除的预设。");
                if (PresetDownloadTasks.shared().isActive(confirmed.references.presetId)) throw new IOException("这份预设正在下载或保存，请完成后再删除。");
                if (library.findBySha256(confirmed.references.presetId) == null) throw new IOException("这份本地预设已不存在。");
                cleared = requireStore().clearPresetReferences(confirmed.references);
                PresetLibrary.RemoveResult removed = library.remove(confirmed.references.presetId);
                // Runtime completion must clear process download state even if its Activity died.
                PresetDownloadTasks.shared().forget(confirmed.references.presetId);
                PresetDeletionResult result = new PresetDeletionResult(removed, cleared, confirmed.references.failedTasks);
                rebuild(true); main.post(() -> callback.onSuccess(result));
            } catch (Exception failure) {
                rebuild(true);
                String text = (cleared > 0 ? "已清除 " + cleared + " 个项目的默认预设，但本地预设未删除。" : "") + message(failure);
                main.post(() -> callback.onError(text));
            }
        });
    }

    private void requireDeletionIdle() throws IOException {
        PhotoLiveState live = album.photoLiveState();
        if (live.enabled || live.starting || live.receiving) throw new IOException("请先停止照片直播，再删除本地预设。");
        if (batch.isActive() || active != null || originalLookups.get() > 0)
            throw new IOException("照片正在套用预设或登记效果，请等待完成后再删除。");
    }

    public void validatePreset(String id, AlbumController.Callback<PresetRecord> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        worker.execute(() -> {
            try { PresetRecord preset = validate(id); main.post(() -> callback.onSuccess(preset)); }
            catch (Exception failure) { main.post(() -> callback.onError(message(failure))); }
        });
    }

    public void setProjectPreset(String projectId, String presetId, AlbumController.Callback<Void> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        final String selected = presetId == null ? "" : presetId;
        album.listPhotoLiveProjects(new AlbumController.Callback<List<PhotoLiveProject>>() {
            @Override public void onSuccess(List<PhotoLiveProject> projects) {
                boolean present = false;
                for (PhotoLiveProject project : projects) if (project.id.equals(projectId)) { present = true; break; }
                if (!present) { callback.onError("拍摄项目已不存在，请重新选择。"); return; }
                worker.execute(() -> {
                    try {
                        PresetRecord preset = selected.isEmpty() ? null : validate(selected);
                        requireStore().setConfig(projectId, selected, preset == null ? "" : preset.displayName);
                        error = ""; rebuild(true); main.post(() -> callback.onSuccess(null));
                    } catch (Exception failure) { fail(failure); main.post(() -> callback.onError(message(failure))); }
                });
            }
            @Override public void onError(String text) { callback.onError(text); }
        });
    }

    /** Immutable cached state only. An uninitialized runtime has no enabled default. */
    public PhotoProjectPresetStore.Config config(String projectId) {
        PhotoProjectPresetStore current = store;
        return current == null ? new PhotoProjectPresetStore.Config(projectId, "", "", 0) : current.config(projectId);
    }

    /** The controller runs this once after creating the named project, before accepting originals. */
    public AlbumController.PhotoLiveProjectSetup setupForNewProject(String presetId, String presetName) {
        final String selected = presetId == null ? "" : presetId;
        return project -> {
            if (selected.isEmpty()) return;
            PresetRecord preset = validate(selected);
            requireStore().setConfig(project.id, preset.id, preset.displayName);
            main.post(this::changed);
        };
    }

    /** Camera-worker callback: capture only the current immutable default and object reference. */
    public void onOriginalSaved(String projectId, String identity, DownloadedPhoto original) {
        PhotoProjectPresetStore current = store;
        if (current == null || original == null) return; // Startup compensation covers initialization gaps.
        final PhotoProjectPresetStore.Config fixed;
        try { fixed = current.config(projectId); }
        catch (RuntimeException invalid) { return; }
        if (fixed.presetId.isEmpty()) return;
        originalLookups.incrementAndGet();
        worker.execute(() -> album.listPhotoLiveReceipts(projectId, new AlbumController.Callback<List<PhotoLiveReceipt>>() {
            @Override public void onSuccess(List<PhotoLiveReceipt> receipts) {
                worker.execute(() -> {
                    try {
                    for (PhotoLiveReceipt receipt : receipts) if (receipt.identity.equals(identity)) {
                        if (receipt.createdAt < fixed.enabledSince) return;
                        try { enqueue(fixed, identity, original); rebuild(); wake(); }
                        catch (Exception failure) { enqueueFailed(projectId, identity, failure); }
                        return;
                    }
                    enqueueFailed(projectId, identity, new IOException("接收时间尚未确认，未回溯套用旧照片，请稍后重试。"));
                    } finally { originalLookups.decrementAndGet(); }
                });
            }
            @Override public void onError(String text) { worker.execute(() -> { originalLookups.decrementAndGet(); enqueueFailed(projectId, identity, new IOException(text)); }); }
        }));
    }

    /** Explicit user retry only: failed jobs retain their original preset and source snapshot. */
    public void retryProject(String projectId) {
        worker.execute(() -> {
            try {
                PhotoProjectPresetStore current = requireStore();
                paused = false; pauseReason = "";
                for (PhotoProjectPresetStore.Task task : current.tasks(projectId)) if ("error".equals(task.status)
                        || "processing".equals(task.status) && (active == null || !active.key().equals(task.key()))) {
                    current.update(task.withStatus("queued", "", ""));
                }
                error = ""; rebuild(); compensate(projectId); wake();
            } catch (Exception failure) { fail(failure); }
        });
    }

    public String statusFor(String projectId, String identity) { return statuses.getOrDefault(pair(projectId, identity), ""); }
    /** Receipt filters read typed cached state; no file access or localized-text parsing. */
    public boolean needsRetry(String projectId, String identity) { return retryable.contains(pair(projectId, identity)); }
    public boolean isPending(String projectId, String identity) { return pending.contains(pair(projectId, identity)); }
    public String error() { return error; }
    public boolean hasWork() { return working; }
    public int queuedCount() { return queuedCount; }
    public int failedCount() { return failedCount; }
    public Processing processing() { return processing; }
    /** -1 means this receipt is not the actively rendered photo; queued/failed photos have no fake fraction. */
    public double progressFor(String projectId, String identity) {
        Processing value = processing;
        return value != null && value.projectId.equals(projectId) && value.identity.equals(identity) ? value.progress : -1;
    }
    public synchronized void addObserver(Runnable observer) { if (observer != null) observers.add(observer); }
    public synchronized void removeObserver(Runnable observer) { observers.remove(observer); }

    /** Service failure/timeout is terminal until a user's retry; it must not start the next item. */
    public void pause(String reason) {
        paused = true; pauseReason = clean(reason == null || reason.isEmpty() ? "自动套用已停止，请稍后重试。" : reason);
        worker.execute(() -> {
            try {
                PhotoProjectPresetStore current = store;
                if (current != null) for (PhotoProjectPresetStore.Task task : current.allTasks()) if ("queued".equals(task.status)) {
                    current.update(task.withStatus("error", "", pauseReason));
                }
            } catch (Exception failure) { fail(failure); }
            rebuild(); releaseIfIdle();
        });
    }

    private PhotoProjectPresetStore requireStore() throws IOException {
        PhotoProjectPresetStore current = store;
        if (current != null) return current;
        synchronized (initialization) {
            if (store != null) return store;
            if (!initializationError.isEmpty()) throw new IOException(initializationError);
            try { store = new PhotoProjectPresetStore(new File(context.getFilesDir(), "photo-project-presets")); return store; }
            catch (Exception failure) { initializationError = message(failure); throw new IOException(initializationError, failure); }
        }
    }

    private PresetRecord validate(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw new IOException("请选择本地已下载的预设。");
        PresetLibrary library = new PresetLibrary(context.getFilesDir()); PresetRecord preset = library.findBySha256(id);
        if (preset == null) throw new IOException("本地预设不存在，请重新添加。");
        try { PresetPreviewProfiles.fromPreset(library.readPreset(preset.id)); }
        catch (IOException unsupported) { throw new IOException("此预设暂不支持照片效果。" + unsupported.getMessage(), unsupported); }
        return preset;
    }

    private void enqueue(PhotoProjectPresetStore.Config fixed, String identity, DownloadedPhoto original) throws IOException {
        PhotoProjectPresetStore current = requireStore();
        // A resumed/repeated original cannot acquire a new preset after the default changed.
        for (PhotoProjectPresetStore.Task previous : current.tasks(fixed.projectId)) if (previous.identity.equals(identity)) return;
        File source = owned(original.file(), original.file().getName(), -1);
        if (!original.projectName().isEmpty()) projectNames.put(fixed.projectId, original.projectName());
        String display = basename(original.filename(), source.getName());
        PhotoProjectPresetStore.Task task = new PhotoProjectPresetStore.Task(fixed.projectId, identity, fixed.presetId,
                fixed.presetName, source.getName(), display, source.length(), "queued", "", "", System.currentTimeMillis());
        current.queue(task); enqueueErrors.remove(pair(fixed.projectId, identity));
        if (paused) current.update(task.withStatus("error", "", pauseReason));
    }

    /** Startup-only gap recovery uses first discovery, never file mtime or a renamed preset. */
    private void compensateAll() {
        projectLookup = true;
        album.listPhotoLiveProjects(new AlbumController.Callback<List<PhotoLiveProject>>() {
            @Override public void onSuccess(List<PhotoLiveProject> projects) {
                worker.execute(() -> {
                    projectLookup = false;
                    for (PhotoLiveProject project : projects) { projectNames.put(project.id, project.name); compensate(project.id); }
                    wake();
                });
            }
            @Override public void onError(String text) { worker.execute(() -> { projectLookup = false; fail(new IOException(text)); pause(text); }); }
        });
    }

    private void compensate(String projectId) {
        PhotoProjectPresetStore current = store;
        if (current == null) return;
        PhotoProjectPresetStore.Config fixed = current.config(projectId);
        if (fixed.presetId.isEmpty()) return;
        album.listPhotoLiveReceipts(projectId, new AlbumController.Callback<List<PhotoLiveReceipt>>() {
            @Override public void onSuccess(List<PhotoLiveReceipt> receipts) {
                worker.execute(() -> {
                    PhotoProjectPresetStore.Config actual = store.config(projectId);
                    if (!actual.presetId.equals(fixed.presetId) || actual.enabledSince != fixed.enabledSince) return;
                    for (PhotoLiveReceipt receipt : receipts) if (receipt.saved != null && receipt.createdAt >= fixed.enabledSince) {
                        try { enqueue(fixed, receipt.identity, receipt.saved); }
                        catch (Exception failure) { fail(failure); }
                    }
                    rebuild(); wake();
                });
            }
            @Override public void onError(String text) { fail(new IOException(text)); }
        });
    }

    /** Byte progress is not an effect-queue mutation. Only a waiting queue needs a new drain. */
    private void originalChanged() {
        if (queuedCount > 0 && !paused) wake();
    }

    private void wake() {
        if (!drainQueued.compareAndSet(false, true)) return;
        worker.execute(() -> { drainQueued.set(false); drain(); });
    }

    private void drain() {
        PhotoProjectPresetStore current = store;
        if (current == null || active != null || paused) { releaseIfIdle(); return; }
        PhotoProjectPresetStore.Task next = null;
        for (PhotoProjectPresetStore.Task task : current.allTasks()) if ("queued".equals(task.status)) { next = task; break; }
        if (next == null) { rebuild(); releaseIfIdle(); return; }
        if (!retainWork()) return;
        if (!projectNames.containsKey(next.projectId)) { loadProjectNames(); return; }
        // Local rendering is independent of the camera worker. Let a receive burst finish first.
        PhotoLiveState live = album.photoLiveState();
        if (batch.isActive() || live.starting || live.receiving || live.queued > 0) return;
        try {
            File file = resolve(next);
            DownloadedPhoto source = new DownloadedPhoto(file, next.sourceDisplayName, next.projectId, projectNames.get(next.projectId));
            PhotoProjectPresetStore.Task processing = next.withStatus("processing", "", "");
            current.update(processing); active = processing;
            this.processing = new Processing(processing, 0, "准备原图"); rebuild();
            batch.startTracked(Collections.singletonList(PhotoPresetBatch.Source.local(source)), processing.presetId,
                    new AlbumController.Callback<Void>() {
                        @Override public void onSuccess(Void ignored) { }
                        @Override public void onError(String text) { worker.execute(() -> startFailed(processing, text)); }
                    }, new AlbumController.Callback<PhotoPresetBatchState>() {
                        @Override public void onSuccess(PhotoPresetBatchState result) { worker.execute(() -> finished(processing, result)); }
                        @Override public void onError(String text) { worker.execute(() -> finishedError(processing, text)); }
                    }, result -> worker.execute(() -> updateProgress(processing, result)));
        } catch (Exception failure) {
            try { current.update(next.withStatus("error", "", message(failure))); }
            catch (Exception persistence) { fail(persistence); paused = true; }
            fail(failure); rebuild(); wake();
        }
    }

    private void loadProjectNames() {
        if (projectLookup) return;
        projectLookup = true;
        album.listPhotoLiveProjects(new AlbumController.Callback<List<PhotoLiveProject>>() {
            @Override public void onSuccess(List<PhotoLiveProject> projects) {
                worker.execute(() -> {
                    projectLookup = false;
                    for (PhotoLiveProject project : projects) projectNames.put(project.id, project.name);
                    try {
                        for (PhotoProjectPresetStore.Task task : store.allTasks()) if ("queued".equals(task.status) && !projectNames.containsKey(task.projectId)) {
                            store.update(task.withStatus("error", "", "拍摄项目已不存在，原图保留，未重新创建项目。"));
                        }
                    } catch (Exception failure) { fail(failure); paused = true; }
                    rebuild(); wake();
                });
            }
            @Override public void onError(String text) { worker.execute(() -> { projectLookup = false; fail(new IOException(text)); pause(text); }); }
        });
    }

    private void enqueueFailed(String projectId, String identity, Exception failure) {
        enqueueErrors.put(pair(projectId, identity), "效果登记失败 · " + shortMessage(message(failure)));
        fail(failure); rebuild();
    }

    private void startFailed(PhotoProjectPresetStore.Task task, String text) {
        if (active == null || !active.key().equals(task.key())) return;
        active = null; processing = null;
        boolean busy = text != null && (text.contains("已有批量任务") || text.contains("正在删除"));
        try { requireStore().update(task.withStatus(busy && !paused ? "queued" : "error", "", busy && !paused ? "" : clean(paused ? pauseReason : text))); }
        catch (Exception failure) { fail(failure); paused = true; }
        rebuild(); releaseIfIdle();
        if (busy && !paused) main.postDelayed(this::wake, 500); else wake();
    }

    private void finished(PhotoProjectPresetStore.Task task, PhotoPresetBatchState result) {
        if (active == null || !active.key().equals(task.key())) return;
        try {
            if (result.completed == 1 && result.failed == 0 && result.cancelled == 0 && result.results.size() == 1) {
                DownloadedPhoto effect = result.results.get(0); owned(effect.file(), effect.file().getName(), -1);
                requireStore().update(task.withStatus("saved", effect.file().getName(), ""));
            } else {
                String detail = !result.error.isEmpty() ? result.error : result.cancelled > 0 ? "自动套用已停止，原图已保留，请手动重试。" : "效果未完成保存，原图已保留，请手动重试。";
                requireStore().update(task.withStatus("error", "", clean(detail)));
            }
        } catch (Exception failure) { fail(failure); paused = true; }
        active = null; processing = null; rebuild(); releaseIfIdle(); wake();
    }

    private void finishedError(PhotoProjectPresetStore.Task task, String text) {
        if (active == null || !active.key().equals(task.key())) return;
        try { requireStore().update(task.withStatus("error", "", clean(text))); }
        catch (Exception failure) { fail(failure); paused = true; }
        active = null; processing = null; rebuild(); releaseIfIdle(); wake();
    }

    private void updateProgress(PhotoProjectPresetStore.Task task, PhotoPresetBatchState state) {
        // Exact attempt identity prevents an old callback from updating a retried receipt or manual job.
        if (active != task) return;
        Processing previous = processing;
        double fraction = Math.min(.99, state.currentProgress);
        if (previous != null && fraction < previous.progress) return;
        if (previous != null && fraction == previous.progress && previous.stage.equals(state.currentStage)) return;
        processing = new Processing(task, fraction, state.currentStage); changed();
    }

    private boolean retainWork() {
        if (serviceLease != null) return true;
        try {
            localLease = PhotoLocalTasks.use(); serviceLease = host.beginPhotoWork(); return true;
        } catch (Exception failure) {
            if (localLease != null) { localLease.close(); localLease = null; }
            fail(failure); pause(message(failure)); return false;
        }
    }

    private void releaseIfIdle() {
        if (active != null || !paused && queuedCount > 0) return;
        AutoCloseable held = serviceLease; serviceLease = null;
        if (localLease != null) { localLease.close(); localLease = null; }
        if (held != null) try { held.close(); } catch (Exception ignored) { }
    }

    private void rebuild() { rebuild(false); }

    private void rebuild(boolean force) {
        Map<String, String> result = new LinkedHashMap<>(enqueueErrors); int queued = 0;
        Set<String> retries = new LinkedHashSet<>(enqueueErrors.keySet()), awaiting = new LinkedHashSet<>();
        PhotoProjectPresetStore current = store;
        if (current != null) for (PhotoProjectPresetStore.Task task : current.allTasks()) {
            String text, identity = pair(task.projectId, task.identity); retries.remove(identity); awaiting.remove(identity);
            switch (task.status) {
                case "queued": queued++; awaiting.add(identity); text = "效果待处理 · " + task.presetName; break;
                case "processing":
                    if (active == null || !active.key().equals(task.key())) { retries.add(identity); text = "效果记录待重试 · " + task.presetName; }
                    else { awaiting.add(identity); text = "正在套用 · " + task.presetName; }
                    break;
                case "saved": text = "已套用 · " + task.presetName; break;
                default: retries.add(identity); text = "效果待重试 · " + shortMessage(task.error); break;
            }
            result.put(identity, text);
        }
        boolean nextWorking = active != null || !paused && queued > 0;
        boolean different = force || !statuses.equals(result) || !retryable.equals(retries) || !pending.equals(awaiting)
                || queuedCount != queued || failedCount != retries.size() || working != nextWorking || !error.equals(rebuiltError);
        statuses = Collections.unmodifiableMap(result); retryable = Collections.unmodifiableSet(retries);
        pending = Collections.unmodifiableSet(awaiting); queuedCount = queued; failedCount = retries.size();
        working = nextWorking; rebuiltError = error;
        if (different) changed();
    }

    private File resolve(PhotoProjectPresetStore.Task task) throws IOException {
        File external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File[] parents = {external == null ? null : new File(external, "camera-album"), new File(new File(context.getFilesDir(), "pictures"), "camera-album")};
        for (File parent : parents) if (parent != null) {
            File candidate = new File(parent, task.sourceFileName);
            if (Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return owned(candidate, task.sourceFileName, task.sourceBytes);
        }
        throw new IOException("原图已不存在，未重新下载或覆盖，请检查本机照片。");
    }

    private File owned(File source, String name, long expected) throws IOException {
        if (source == null || name == null || !name.equals(source.getName()) || !name.toLowerCase(java.util.Locale.ROOT).matches(".+\\.jpe?g")) throw new IOException("原图文件名无效。");
        File actual = source.getCanonicalFile(); File external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File internal = new File(new File(context.getFilesDir(), "pictures"), "camera-album").getCanonicalFile();
        if (!source.getAbsoluteFile().equals(actual) || Files.isSymbolicLink(source.toPath()) || !Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)
                || !internal.equals(actual.getParentFile()) && (external == null || !new File(external, "camera-album").getCanonicalFile().equals(actual.getParentFile()))) throw new IOException("只能自动处理应用内已完成的原图。");
        long bytes = actual.length();
        if (bytes < 4 || bytes > 512L * 1024 * 1024 || expected >= 0 && bytes != expected) throw new IOException("原图大小已变化，未生成或覆盖效果。");
        return actual;
    }

    private void fail(Exception failure) {
        String text = message(failure);
        if (!text.equals(error)) { error = text; changed(); }
    }
    private void changed() {
        main.post(() -> {
            host.onChanged(); List<Runnable> snapshot;
            synchronized (this) { snapshot = new ArrayList<>(observers); }
            for (Runnable observer : snapshot) try { observer.run(); } catch (RuntimeException ignored) { }
        });
    }
    private static String pair(String projectId, String identity) { return projectId + ':' + identity; }
    private static String basename(String input, String fallback) {
        if (input == null || input.isEmpty() || input.contains("/") || input.contains("\\") || input.contains(":")) return fallback;
        for (int i = 0; i < input.length(); i++) if (Character.isISOControl(input.charAt(i))) return fallback;
        return input;
    }
    private static String shortMessage(String text) { return text.length() > 64 ? text.substring(0, 64) + "…" : text; }
    private static String message(Exception failure) { return clean(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()); }
    private static String clean(String text) {
        String value = text == null ? "自动套用未完成，请稍后重试。" : text.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (value.isEmpty()) value = "自动套用未完成，请稍后重试。";
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }
}

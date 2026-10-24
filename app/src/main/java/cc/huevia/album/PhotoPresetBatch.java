package cc.huevia.album;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import cc.huevia.library.PresetLibrary;
import cc.huevia.library.PresetRecord;
import cc.huevia.ncp.PresetPreviewProfile;
import cc.huevia.ncp.PresetPreviewProfiles;
import cc.huevia.ncp.StrengthPreviewProfile;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Application-owned serial local processing; camera worker is used only to acquire remote originals. */
public final class PhotoPresetBatch {
    public static final int MAX_BATCH = 100;
    /** One immutable per-photo export choice. A null draft preserves the selected saved version. */
    public static final class Edit {
        public final Source source;
        public final PhotoEditStore.Draft draft;
        public final String owner;
        /** Non-null on accepted/retry entries, so later preference changes cannot retarget them. */
        public final PhotoSaveTarget saveTarget;
        public final boolean forceNewCopy;
        public final PhotoExportSpec exportSpec;
        /** Byte identity frozen during the caller's worker preparation; draft inputs carry their own root identity. */
        public final String expectedSourceSha;
        /** Empty is a legitimate guest scope; accountBound distinguishes it from legacy unbound callers. */
        public final String accountScope;
        public final boolean accountBound;
        private boolean publicationCompleted;
        private Edit(Source source, PhotoEditStore.Draft draft, String owner) {
            this(source, draft, owner, null, false, PhotoExportSpec.full(), "");
        }
        private Edit(Source source, PhotoEditStore.Draft draft, String owner, PhotoSaveTarget target, boolean forceNewCopy, PhotoExportSpec spec, String expectedSha) {
            this(source, draft, owner, target, forceNewCopy, spec, expectedSha, "", false);
        }
        private Edit(Source source, PhotoEditStore.Draft draft, String owner, PhotoSaveTarget target, boolean forceNewCopy, PhotoExportSpec spec, String expectedSha, String accountScope, boolean accountBound) {
            if (source == null || owner == null || spec == null || expectedSha == null || !expectedSha.isEmpty() && !expectedSha.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("source/owner/spec/sha");
            if (accountScope == null) throw new IllegalArgumentException("accountScope");
            this.source = source; this.draft = draft; this.owner = owner;
            this.saveTarget = target; this.forceNewCopy = forceNewCopy;
            this.exportSpec = spec; this.expectedSourceSha = expectedSha;
            this.accountScope = accountScope; this.accountBound = accountBound;
        }
        public static Edit saved(Source source) { return new Edit(source, null, ""); }
        public static Edit saved(Source source, PhotoExportSpec spec) { return saved(source, spec, ""); }
        public static Edit saved(Source source, PhotoExportSpec spec, String expectedSha) { return new Edit(source, null, "", null, false, spec, expectedSha); }
        public static Edit draft(Source source, PhotoEditStore.Draft draft, String owner) {
            if (draft == null) throw new IllegalArgumentException("draft"); return new Edit(source, draft, owner);
        }
        public static Edit draft(Source source, PhotoEditStore.Draft draft, String owner, PhotoExportSpec spec) {
            if (draft == null) throw new IllegalArgumentException("draft"); return new Edit(source, draft, owner, null, false, spec, "");
        }
        /** An already confirmed settings panel must not ask for destination a second time. */
        public Edit withDestination(PhotoSaveTarget target, boolean forceNewCopy) {
            if (target == null) throw new IllegalArgumentException("target");
            if (saveTarget != null) return this; // An accepted retry already owns destination and publication progress.
            return copy(target, forceNewCopy, accountScope, accountBound);
        }
        /** Binds once. Reapplying current UI settings to a retry cannot move its original scope. */
        public Edit withAccount(String scope) {
            if (scope == null) throw new IllegalArgumentException("scope");
            if (accountBound) return this;
            if (draft != null && !owner.equals(scope)) throw new IllegalArgumentException("编辑草稿和任务账号不符。");
            return copy(saveTarget, forceNewCopy, scope, true);
        }
        private Edit copy(PhotoSaveTarget target, boolean force, String scope, boolean bound) {
            Edit result = new Edit(source, draft, owner, target, force, exportSpec, expectedSourceSha, scope, bound);
            result.publicationCompleted = publicationCompleted; return result;
        }
        private Edit freeze(PhotoSaveTarget target, boolean forceNewCopy) {
            return saveTarget == null ? copy(target, forceNewCopy, accountScope, accountBound) : this;
        }
    }
    public static final class Source {
        final PhotoItem remote; final DownloadedPhoto local;
        private Source(PhotoItem remote, DownloadedPhoto local) { this.remote = remote; this.local = local; }
        public static Source remote(PhotoItem photo) { if (photo == null) throw new IllegalArgumentException("photo"); return new Source(photo, null); }
        public static Source local(DownloadedPhoto photo) { if (photo == null) throw new IllegalArgumentException("photo"); return new Source(null, photo); }
        public String filename() { return remote == null ? local.filename() : remote.filename(); }
        public boolean requiresCamera() { return remote != null; }
        public DownloadedPhoto localPhoto() { return local; }
        public PhotoItem remotePhoto() { return remote; }
        String key() { return remote == null ? "file:" + local.file().getAbsolutePath() : remote.session + ":" + remote.storageId() + ":" + remote.handle() + ":" + remote.byteSize() + ":" + remote.captureDate(); }
    }
    private static final int MAGIC = 0x50424531;
    private final Context context; private final AlbumController album;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "photo-preset-batch"); thread.setDaemon(true); return thread; });
    private final Set<Runnable> observers = new LinkedHashSet<>(); private Runnable lifecycleObserver;
    private final Map<String, DownloadedPhoto> acquired = new LinkedHashMap<>();
    private volatile PhotoPresetBatchState state = new PhotoPresetBatchState(false, 0, 0, 0, 0, 0, "", "", "", Collections.emptyList(), Collections.emptyList());
    private Job current;
    private volatile List<Edit> retryEdits = Collections.emptyList();
    private volatile PhotoSaveTarget retrySaveTarget = PhotoSaveTarget.PHONE_ALBUM;
    private volatile boolean retryForceNewCopy;
    public List<Edit> retryEdits() { return retryEdits; }
    public PhotoSaveTarget retrySaveTarget() { return retrySaveTarget; }
    public boolean retryForceNewCopy() { return retryForceNewCopy; }
    private static final class Job {
        List<Edit> edits;
        final List<Edit> retry = new ArrayList<>();
        final List<Source> sources; final String preset; final PhotoLocalTasks.Lease lease; final AtomicBoolean cancel = new AtomicBoolean();
        final PhotoSaveTarget saveTarget; final boolean forceNewCopy;
        final PhotoExportSpec exportSpec;
        final AlbumController.Callback<PhotoPresetBatchState> finished;
        final Consumer<PhotoPresetBatchState> progress;
        final List<String> errors = new ArrayList<>(); final List<DownloadedPhoto> results = new ArrayList<>();
        final Map<String, Outcome> inputOutcomes = new LinkedHashMap<>(), outcomes = new LinkedHashMap<>();
        final List<PhotoPresetBatchState.Item> items = new ArrayList<>();
        boolean currentMerged;
        Supplier<String> currentAccount;
        final Set<String> stoppedAccounts = new LinkedHashSet<>();
        int completed, failed, cancelled, reused, merged, index; double fraction; long lastProgress; String filename = "", presetName = "", error = "", stage = "准备照片";
        Job(List<Source> sources, String preset, PhotoLocalTasks.Lease lease, AlbumController.Callback<PhotoPresetBatchState> finished,
            Consumer<PhotoPresetBatchState> progress, PhotoSaveTarget saveTarget, boolean forceNewCopy) {
            this(sources, preset, lease, finished, progress, saveTarget, forceNewCopy, PhotoExportSpec.full());
        }
        Job(List<Source> sources, String preset, PhotoLocalTasks.Lease lease, AlbumController.Callback<PhotoPresetBatchState> finished,
            Consumer<PhotoPresetBatchState> progress, PhotoSaveTarget saveTarget, boolean forceNewCopy, PhotoExportSpec spec) {
            this.sources = sources; this.preset = preset; this.lease = lease; this.finished = finished; this.progress = progress;
            this.saveTarget = saveTarget; this.forceNewCopy = forceNewCopy; this.exportSpec = spec;
            for (int i = 0; i < sources.size(); i++) { Source source = sources.get(i); items.add(new PhotoPresetBatchState.Item(i, source.key(), source.filename(), "waiting", "", 0, 0, 0)); }
        }
    }
    private static final class Outcome {
        final String error;
        final boolean reused;
        final int width, height, quality;
        Outcome(String error) { this(error, false, 0, 0, 0); }
        Outcome(String error, boolean reused, int width, int height, int quality) { this.error = error; this.reused = reused; this.width = width; this.height = height; this.quality = quality; }
    }
    private static final class Derived {
        final DownloadedPhoto photo; final boolean reused;
        final PhotoExportSpec.Resolved spec;
        Derived(DownloadedPhoto photo, boolean reused, PhotoExportSpec.Resolved spec) { this.photo = photo; this.reused = reused; this.spec = spec; }
    }
    private static final class AcquisitionTimeout extends IOException { AcquisitionTimeout(String text) { super(text); } }
    private static final class AccountChanged extends IOException { AccountChanged() { super("账号已变化或暂无法核对；已停止此账号的后续处理，已完成照片保留，切回原账号后可重试。"); } }
    public PhotoPresetBatch(Context context, AlbumController album) {
        if (context == null || album == null) throw new IllegalArgumentException("context/album");
        Context application = context.getApplicationContext(); this.context = application == null ? context : application; this.album = album;
    }
    public PhotoPresetBatchState state() {
        PhotoPresetBatchState snapshot = state; List<DownloadedPhoto> present = new ArrayList<>();
        for (DownloadedPhoto photo : snapshot.results) if (photo.file().isFile()) present.add(photo);
        return present.size() == snapshot.results.size() ? snapshot : new PhotoPresetBatchState(snapshot.active, snapshot.total, snapshot.completed, snapshot.failed,
                snapshot.cancelled, snapshot.progress, snapshot.currentFilename, snapshot.presetName, snapshot.error, snapshot.errors, present, snapshot.reused, snapshot.merged,
                snapshot.currentNumber, snapshot.currentProgress, snapshot.currentStage, snapshot.items, snapshot.cancelRequested);
    }
    /** Synchronous local lookup/migration; call on the image worker, never the UI/camera worker.
     * Returns the immediate input of this effect, or null for ordinary/unmatched photos. */
    public DownloadedPhoto sourceFor(DownloadedPhoto effect) throws IOException {
        return sourceFor(effect, () -> false);
    }
    public DownloadedPhoto sourceFor(DownloadedPhoto effect, BooleanSupplier cancel) throws IOException {
        if (cancel == null) throw new IllegalArgumentException("cancel");
        return sources().find(effect, this::committedPresetShas, cancel);
    }
    /** Resolve all verified effect links before changing a preset. Never processes an untraceable effect as an original. */
    public DownloadedPhoto processingOriginalFor(DownloadedPhoto photo) throws IOException {
        return processingOriginalFor(photo, () -> false);
    }
    public DownloadedPhoto processingOriginalFor(DownloadedPhoto photo, BooleanSupplier cancel) throws IOException {
        if (cancel == null) throw new IllegalArgumentException("cancel");
        return sources().processingOriginalFor(photo, this::committedPresetShas, cancel);
    }
    private List<String> committedPresetShas() throws IOException {
        List<String> presetShas = new ArrayList<>();
        for (PresetRecord preset : new PresetLibrary(context.getFilesDir()).list()) presetShas.add(preset.sha256);
        return presetShas;
    }
    private PhotoPresetSources sources() throws IOException {
        return new PhotoPresetSources(context.getFilesDir(), context.getExternalFilesDir(Environment.DIRECTORY_PICTURES));
    }
    public synchronized void addObserver(Runnable observer) { if (observer != null) observers.add(observer); }
    public synchronized void removeObserver(Runnable observer) { observers.remove(observer); }
    public synchronized void setLifecycleObserver(Runnable observer) { lifecycleObserver = observer; }
    public void listPresets(AlbumController.Callback<List<PresetRecord>> callback) {
        worker.execute(() -> {
            try { List<PresetRecord> records = new PresetLibrary(context.getFilesDir()).list(); main.post(() -> callback.onSuccess(records)); }
            catch (Exception failure) { main.post(() -> callback.onError(message(failure))); }
        });
    }
    public void start(List<Source> sources, String presetId, AlbumController.Callback<Void> callback) {
        startTracked(sources, presetId, callback, null);
    }
    /** Atomically claims an idle batch and returns this job's terminal snapshot, independent of later jobs. */
    public void startTracked(List<Source> sources, String presetId, AlbumController.Callback<Void> callback,
                             AlbumController.Callback<PhotoPresetBatchState> finished) {
        startTracked(sources, presetId, callback, finished, null);
    }
    /** Every progress snapshot belongs to this accepted job, even if a later job replaces global state. */
    public void startTracked(List<Source> sources, String presetId, AlbumController.Callback<Void> callback,
                             AlbumController.Callback<PhotoPresetBatchState> finished, Consumer<PhotoPresetBatchState> progress) {
        startTracked(sources, presetId, PhotoSaveTarget.PHONE_ALBUM, false, callback, finished, progress);
    }
    public void startTracked(List<Source> sources, String presetId, PhotoSaveTarget target, boolean forceNewCopy,
                             AlbumController.Callback<Void> callback, AlbumController.Callback<PhotoPresetBatchState> finished) {
        startTracked(sources, presetId, target, forceNewCopy, callback, finished, null);
    }
    public void startTracked(List<Source> sources, String presetId, PhotoSaveTarget target, boolean forceNewCopy,
                             AlbumController.Callback<Void> callback, AlbumController.Callback<PhotoPresetBatchState> finished,
                             Consumer<PhotoPresetBatchState> progress) {
        startTracked(sources, presetId, PhotoExportSpec.full(), target, forceNewCopy, callback, finished, progress);
    }
    public void startTracked(List<Source> sources, String presetId, PhotoExportSpec spec, PhotoSaveTarget target, boolean forceNewCopy,
                             AlbumController.Callback<Void> callback, AlbumController.Callback<PhotoPresetBatchState> finished,
                             Consumer<PhotoPresetBatchState> progress) {
        if (callback == null) throw new IllegalArgumentException("callback");
        if (target == null || spec == null) throw new IllegalArgumentException("target/spec");
        final Job job;
        synchronized (this) {
            if (state.active) { main.post(() -> callback.onError("已有批量任务正在处理。")); return; }
            if (sources == null || sources.isEmpty() || sources.size() > MAX_BATCH || hasNull(sources)) {
                main.post(() -> callback.onError("请选择 1–" + MAX_BATCH + " 张照片；超出上限请分批处理。")); return;
            }
            if (presetId == null || !presetId.matches("[0-9a-f]{64}")) { main.post(() -> callback.onError("请选择本地已下载的预设。")); return; }
            PhotoLocalTasks.Lease lease;
            try { lease = PhotoLocalTasks.use(); }
            catch (IOException busy) { main.post(() -> callback.onError(message(busy))); return; }
            job = new Job(Collections.unmodifiableList(new ArrayList<>(sources)), presetId, lease, finished, progress, target, forceNewCopy, spec); current = job;
            publish(job, true); // Synchronous active state is visible to the runtime's foreground-service lease.
        }
        main.post(() -> callback.onSuccess(null)); worker.execute(() -> run(job));
    }
    public synchronized void cancel() { if (current != null && state.active && current.cancel.compareAndSet(false, true)) publish(current, true); }
    /** Export a frozen set of independent drafts without replacing them with one shared preset. */
    public void startEdits(List<Edit> edits, AlbumController.Callback<Void> callback,
                           AlbumController.Callback<PhotoPresetBatchState> finished) {
        startEdits(edits, PhotoSaveTarget.PHONE_ALBUM, false, callback, finished);
    }
    public void startEdits(List<Edit> edits, PhotoSaveTarget target, boolean forceNewCopy,
                           AlbumController.Callback<Void> callback, AlbumController.Callback<PhotoPresetBatchState> finished) {
        startEdits(edits, target, forceNewCopy, callback, finished, null);
    }
    public void startEdits(List<Edit> edits, PhotoSaveTarget target, boolean forceNewCopy,
                           AlbumController.Callback<Void> callback, AlbumController.Callback<PhotoPresetBatchState> finished,
                           Supplier<String> currentAccount) {
        if (callback == null) throw new IllegalArgumentException("callback");
        if (target == null) throw new IllegalArgumentException("target");
        final Job job;
        synchronized (this) {
            if (state.active) { main.post(() -> callback.onError("已有照片任务正在处理。")); return; }
            if (edits == null || edits.isEmpty() || edits.size() > MAX_BATCH || edits.stream().anyMatch(java.util.Objects::isNull)) {
                main.post(() -> callback.onError("请选择 1–" + MAX_BATCH + " 张照片。")); return;
            }
            boolean bound = edits.stream().anyMatch(edit -> edit.accountBound);
            if (bound) {
                if (currentAccount == null) { main.post(() -> callback.onError("无法核对任务账号，请重新打开导出设置。")); return; }
                String account;
                try { account = currentAccount.get(); }
                catch (RuntimeException unavailable) { main.post(() -> callback.onError("账号状态暂无法核对，请重试。")); return; }
                for (Edit edit : edits) if (edit.accountBound && !edit.accountScope.equals(account)) { main.post(() -> callback.onError("账号已变化，请切回原账号后重试。")); return; }
            }
            PhotoLocalTasks.Lease lease;
            try { lease = PhotoLocalTasks.use(); } catch (IOException error) { main.post(() -> callback.onError(message(error))); return; }
            List<Source> sources = new ArrayList<>(); for (Edit edit : edits) sources.add(edit.source);
            job = new Job(Collections.unmodifiableList(sources), "", lease, finished, null, target, forceNewCopy);
            List<Edit> frozen = new ArrayList<>(); for (Edit edit : edits) frozen.add(edit.freeze(target, forceNewCopy));
            job.edits = Collections.unmodifiableList(frozen); job.presetName = "各照片独立设置";
            job.currentAccount = currentAccount;
            retryEdits = Collections.emptyList(); current = job; publish(job, true);
        }
        main.post(() -> callback.onSuccess(null)); worker.execute(() -> run(job));
    }
    /** In-memory busy check; unlike state(), this does not stat result files. */
    public boolean isActive() { return state.active; }
    private static boolean hasNull(List<Source> sources) { for (Source source : sources) if (source == null) return true; return false; }
    private synchronized void publish(Job job, boolean active) {
        if (current != job) return;
        PhotoPresetBatchState value = snapshot(job, active); state = value;
        List<Runnable> snapshot = new ArrayList<>(observers); Runnable lifecycle = lifecycleObserver;
        main.post(() -> { if (job.progress != null) try { job.progress.accept(value); } catch (RuntimeException ignored) { }
            if (lifecycle != null) try { lifecycle.run(); } catch (RuntimeException ignored) { }
            for (Runnable observer : snapshot) try { observer.run(); } catch (RuntimeException ignored) { } });
    }
    private static PhotoPresetBatchState snapshot(Job job, boolean active) {
        return new PhotoPresetBatchState(active, job.sources.size(), job.completed, job.failed, job.cancelled,
                (job.index + job.fraction) / job.sources.size(), job.filename, job.presetName, job.error, job.errors, job.results, job.reused, job.merged,
                active ? Math.min(job.index + 1, job.sources.size()) : 0, job.fraction, job.stage, job.items, job.cancel.get());
    }
    private void run(Job job) {
        if (job.edits != null) { runEdits(job); return; }
        runLegacy(job);
    }
    private void runEdits(Job job) {
        try {
            for (; job.index < job.edits.size(); job.index++, job.fraction = 0) {
                if (job.cancel.get()) break;
                Edit edit = job.edits.get(job.index); job.filename = edit.source.filename(); job.stage = "准备照片"; beginItem(job); publish(job, true);
                try {
                    checkAccount(job, edit);
                    PhotoPresetRenderer.check(job.cancel::get);
                    DownloadedPhoto input = acquire(job, edit.source);
                    DownloadedPhoto original = edit.draft == null ? input : processingOriginalFor(input, job.cancel::get);
                    String sha = hash(owned(original), job.cancel::get); progress(job, .25, "核对编辑设置");
                    if (edit.draft != null && !sha.equals(edit.draft.sourceSha)) throw new IOException("原图已变化，未套用旧编辑设置。");
                    if (edit.draft == null && !edit.expectedSourceSha.isEmpty() && !sha.equals(edit.expectedSourceSha)) throw new IOException("所选照片已变化，未使用变化后的内容导出。");
                    checkAccount(job, edit);
                    Outcome outcome;
                    // An existing encoded effect is a selected saved version. The full default preserves
                    // those bytes/receipts; an ordinary camera/import JPEG must really be encoded at Q95.
                    if (edit.draft == null && edit.exportSpec.mode == PhotoExportSpec.Mode.FULL && canPreserveFullEffect(input, job.cancel::get)) {
                        progress(job, .95, edit.saveTarget == PhotoSaveTarget.APP_ONLY ? "保留在应用" : "保存到系统相册");
                        PhotoPresetRenderer.Dimensions dimensions = PhotoPresetRenderer.dimensions(owned(original), neutralProfile());
                        checkAccount(job, edit);
                        if (edit.saveTarget == PhotoSaveTarget.PHONE_ALBUM) new PhotoLiveGallery(context).publish(original, edit.forceNewCopy && !edit.publicationCompleted);
                        edit.publicationCompleted = true; job.results.add(original);
                        outcome = new Outcome("", true, dimensions.width, dimensions.height, 95);
                    } else {
                        PresetPreviewProfile profile = edit.draft == null ? neutralProfile() : edit.draft.profile();
                        String effectSha = edit.draft == null ? neutralPresetSha() : edit.draft.effectPresetSha();
                        PhotoPresetRenderer.Dimensions dimensions = PhotoPresetRenderer.dimensions(owned(original), profile);
                        PhotoExportSpec.Resolved resolved = edit.exportSpec.resolve(dimensions.width, dimensions.height);
                        String key = key(sha, effectSha, PhotoPresetRenderer.modelVersion(profile, resolved)) + ":" + edit.saveTarget + ":" + edit.forceNewCopy;
                        outcome = job.outcomes.get(key);
                        if (outcome == null) {
                            outcome = processUnique(job, original, sha, effectSha, edit.draft == null ? "已保存照片" : edit.draft.label(), profile, edit.exportSpec, edit.saveTarget, edit.forceNewCopy && !edit.publicationCompleted, edit);
                            job.outcomes.put(key, outcome);
                        } else { job.merged++; job.currentMerged = true; }
                    }
                    // A later draft-history write may fail after MediaStore confirmed the copy.
                    // Retrying that frozen item must verify/reuse it instead of creating another extra copy.
                    if (outcome.error.isEmpty()) edit.publicationCompleted = true;
                    checkAccount(job, edit);
                    if (outcome.error.isEmpty() && edit.draft != null) new PhotoEditStore(context.getFilesDir(), edit.owner).markExported(edit.draft);
                    if (!outcome.error.isEmpty()) job.retry.add(edit);
                    completeInput(job, outcome);
                } catch (OutOfMemoryError memory) {
                    job.retry.add(edit); fail(job, "完整尺寸处理内存不足；原图与编辑草稿已保留。");
                } catch (Exception error) {
                    if (cancelled(job, error)) { job.cancel.set(true); break; }
                    job.retry.add(edit); fail(job, message(error));
                }
                job.fraction = 1; job.stage = "本张导出完成"; publish(job, true);
            }
        } finally {
            if (job.cancel.get()) {
                job.cancelled = job.sources.size() - job.completed - job.failed;
                cancelRemainingItems(job);
                for (int i = job.index; i < job.edits.size(); i++) job.retry.add(job.edits.get(i));
            }
            retryEdits = Collections.unmodifiableList(new ArrayList<>(job.retry));
            retrySaveTarget = job.saveTarget; retryForceNewCopy = job.forceNewCopy;
            if (!job.cancel.get()) job.index = job.sources.size();
            job.fraction = 0; job.filename = ""; job.stage = job.cancel.get() ? "已取消，未完成项可重试" : job.failed > 0 ? "部分照片待重试" : "导出完成";
            job.lease.close(); publish(job, false);
            if (job.finished != null) { PhotoPresetBatchState terminal = snapshot(job, false); main.post(() -> job.finished.onSuccess(terminal)); }
        }
    }
    private void runLegacy(Job job) {
        try {
            PresetLibrary library = new PresetLibrary(context.getFilesDir()); PresetRecord preset = library.findBySha256(job.preset);
            if (preset == null) throw new IOException("本地预设不存在，请重新选择。");
            byte[] bytes = library.readPreset(preset.id); PresetPreviewProfile profile;
            try { profile = PresetPreviewProfiles.fromPreset(bytes); }
            catch (IOException unsupported) { throw new IOException("此预设暂不支持照片处理。" + unsupported.getMessage(), unsupported); }
            job.presetName = preset.displayName; publish(job, true);
            for (; job.index < job.sources.size(); job.index++, job.fraction = 0) {
                if (job.cancel.get()) break;
                Source source = job.sources.get(job.index); job.filename = source.filename(); job.fraction = 0; job.stage = "准备原图"; beginItem(job); publish(job, true);
                String contentKey = null;
                try {
                    PhotoPresetRenderer.check(job.cancel::get);
                    Outcome outcome = job.inputOutcomes.get(source.key());
                    if (outcome != null) {
                        job.merged++; job.currentMerged = true;
                    } else {
                        DownloadedPhoto original = processingOriginalFor(acquire(job, source), job.cancel::get);
                        progress(job, .20, "校验原图");
                        String sourceSha = hash(owned(original), job.cancel::get);
                        progress(job, .25, "准备套用");
                        PhotoPresetRenderer.Dimensions dimensions = PhotoPresetRenderer.dimensions(owned(original), profile);
                        contentKey = key(sourceSha, preset.sha256, PhotoPresetRenderer.modelVersion(profile, job.exportSpec.resolve(dimensions.width, dimensions.height)));
                        outcome = job.outcomes.get(contentKey);
                        if (outcome != null) { job.merged++; job.currentMerged = true; }
                        else {
                            outcome = processUnique(job, original, sourceSha, preset, profile);
                            job.outcomes.put(contentKey, outcome);
                        }
                        job.inputOutcomes.put(source.key(), outcome);
                    }
                    completeInput(job, outcome);
                } catch (OutOfMemoryError memory) {
                    Outcome outcome = new Outcome("完整尺寸处理内存不足，未缩小图片；原图已保留。");
                    rememberOutcome(job, source, contentKey, outcome); completeInput(job, outcome);
                } catch (Exception failure) {
                    if (cancelled(job, failure)) { job.cancel.set(true); break; }
                    Outcome outcome = new Outcome(message(failure));
                    rememberOutcome(job, source, contentKey, outcome); completeInput(job, outcome);
                }
                job.fraction = 1; job.stage = "本张处理完成"; publish(job, true);
            }
        } catch (Exception failure) {
            if (cancelled(job, failure)) job.cancel.set(true);
            else { job.error = message(failure); job.errors.add(job.error); for (int i = job.index; i < job.sources.size(); i++) item(job, i, "failed", job.error, 0, 0, 0); job.failed += job.sources.size() - job.index; job.index = job.sources.size(); }
        } finally {
            if (job.cancel.get()) { job.cancelled = job.sources.size() - job.completed - job.failed; cancelRemainingItems(job); }
            if (!job.cancel.get()) { job.index = job.sources.size(); job.fraction = 0; }
            job.filename = ""; job.stage = job.cancel.get() ? "已取消" : job.failed > 0 ? "处理完成，部分照片待重试" : "处理完成";
            job.lease.close(); publish(job, false);
            if (job.finished != null) {
                PhotoPresetBatchState terminal = snapshot(job, false);
                main.post(() -> job.finished.onSuccess(terminal));
            }
        }
    }
    private Outcome processUnique(Job job, DownloadedPhoto original, String sourceSha, PresetRecord preset, PresetPreviewProfile profile) throws IOException {
        return processUnique(job, original, sourceSha, preset.sha256, preset.displayName, profile, job.exportSpec, job.saveTarget, job.forceNewCopy, null);
    }
    private Outcome processUnique(Job job, DownloadedPhoto original, String sourceSha, String presetSha, String presetName, PresetPreviewProfile profile,
                                  PhotoExportSpec spec, PhotoSaveTarget target, boolean forceNewCopy, Edit edit) throws IOException {
        String warning = "";
        PhotoPresetRenderer.check(job.cancel::get);
        progress(job, .25, "套用预设");
        Derived derived = derive(job, original, sourceSha, presetSha, presetName, profile, spec);
        job.results.add(derived.photo); if (derived.reused) job.reused++;
        checkAccount(job, edit);
        progress(job, .96, target == PhotoSaveTarget.APP_ONLY ? "保留在应用" : "保存到相册");
        checkAccount(job, edit);
        try { if (target == PhotoSaveTarget.PHONE_ALBUM) new PhotoLiveGallery(context).publish(derived.photo, forceNewCopy); }
        catch (IOException failure) {
            if (cancelled(job, failure)) throw failure;
            warning += (warning.isEmpty() ? "" : "；") + "效果图已保留，系统相册保存待重试：" + message(failure);
        }
        return new Outcome(warning, derived.reused, derived.spec.width, derived.spec.height, derived.spec.quality);
    }
    private static void checkAccount(Job job, Edit edit) throws AccountChanged {
        if (edit == null || !edit.accountBound) return;
        if (job.stoppedAccounts.contains(edit.accountScope)) throw new AccountChanged();
        String current = null;
        try { if (job.currentAccount != null) current = job.currentAccount.get(); }
        catch (RuntimeException unavailable) { /* Uncertainty cannot authorize a write under another account. */ }
        if (!edit.accountScope.equals(current)) { job.stoppedAccounts.add(edit.accountScope); throw new AccountChanged(); }
    }
    private static boolean cancelled(Job job, Exception failure) {
        if (failure instanceof AcquisitionTimeout) return false;
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        return job.cancel.get() || Thread.currentThread().isInterrupted()
                || failure instanceof PhotoPresetRenderer.Cancelled || failure instanceof InterruptedIOException || failure instanceof InterruptedException;
    }
    private static void rememberOutcome(Job job, Source source, String key, Outcome outcome) {
        job.inputOutcomes.put(source.key(), outcome); if (key != null) job.outcomes.put(key, outcome);
    }
    private static void item(Job job, int index, String status, String error, int width, int height, int quality) {
        if (index < 0 || index >= job.items.size()) return;
        PhotoPresetBatchState.Item previous = job.items.get(index);
        job.items.set(index, new PhotoPresetBatchState.Item(index, previous.key, previous.filename, status, error, width, height, quality));
    }
    private static void beginItem(Job job) { job.currentMerged = false; item(job, job.index, "processing", "", 0, 0, 0); }
    private static void cancelRemainingItems(Job job) {
        for (int i = 0; i < job.items.size(); i++) { String status = job.items.get(i).status; if (status.equals("waiting") || status.equals("processing")) item(job, i, "cancelled", "已停止，未完成项可重试。", 0, 0, 0); }
    }
    private void completeInput(Job job, Outcome outcome) {
        if (outcome.error.isEmpty()) { job.completed++; item(job, job.index, outcome.reused || job.currentMerged ? "reused" : "completed", "", outcome.width, outcome.height, outcome.quality); }
        else fail(job, outcome.error);
    }
    private void fail(Job job, String message) { job.failed++; job.error = message; job.errors.add(job.filename + "：" + message); item(job, job.index, "failed", message, 0, 0, 0); }
    private synchronized void progress(Job job, double fraction, String stage) {
        if (current != job || !state.active) return; // A late download callback cannot revive an expired batch.
        if (!Double.isFinite(fraction)) return;
        double next = Math.max(0, Math.min(.99, fraction));
        if (next < job.fraction) return;
        boolean stageChanged = !stage.equals(job.stage);
        job.fraction = next; job.stage = stage; long now = System.nanoTime();
        if (stageChanged || now - job.lastProgress >= TimeUnit.MILLISECONDS.toNanos(150)) { job.lastProgress = now; publish(job, true); }
    }
    private DownloadedPhoto acquire(Job job, Source source) throws Exception {
        if (source.local != null) { owned(source.local); return source.local; }
        DownloadedPhoto cached = acquired.get(source.key());
        if (cached != null) {
            if (!cached.file().isFile()) acquired.remove(source.key());
            else { owned(cached); return cached; }
        }
        CountDownLatch finished = new CountDownLatch(1); DownloadedPhoto[] result = {null}; String[] error = {""};
        final int inputIndex = job.index;
        progress(job, 0, "读取原图");
        AtomicLong activity = new AtomicLong(System.nanoTime());
        album.download(source.remote, new AlbumController.DownloadCallback() {
            public void onSuccess(DownloadedPhoto photo) { result[0] = photo; finished.countDown(); }
            public void onError(String message) { error[0] = message; finished.countDown(); }
            public void onProgress(long received, long total) {
                activity.set(System.nanoTime());
                synchronized (PhotoPresetBatch.this) {
                    if (job.index != inputIndex || !"读取原图".equals(job.stage) || finished.getCount() == 0) return;
                    progress(job, total > 0 ? .20 * Math.max(0, Math.min(1, received / (double) total)) : 0, "读取原图");
                }
            }
        });
        long start = System.nanoTime();
        while (!finished.await(250, TimeUnit.MILLISECONDS)) {
            // Cancel does not close the shared camera or abandon a framed read. Its
            // callback drains before this worker stops and the service lease releases.
            if (!album.hasLiveSession()) throw new IOException("相机连接已断开，未取得完整原图。");
            if (System.nanoTime() - activity.get() > TimeUnit.MINUTES.toNanos(2) || System.nanoTime() - start > TimeUnit.MINUTES.toNanos(30)) {
                job.cancel.set(true); throw new AcquisitionTimeout("原图接收等待超时，已停止后续批量任务；在途相机读取由原连接完成。");
            }
        }
        if (result[0] == null) throw new IOException(error[0].isEmpty() ? "未取得完整原图。" : error[0]);
        owned(result[0]); acquired.put(source.key(), result[0]);
        if (acquired.size() > 200) acquired.remove(acquired.keySet().iterator().next());
        return result[0];
    }
    private Derived derive(Job job, DownloadedPhoto original, String sourceSha, String presetSha, String presetName, PresetPreviewProfile profile, PhotoExportSpec spec) throws IOException {
        File source = owned(original);
        PhotoPresetRenderer.Dimensions dimensions = PhotoPresetRenderer.dimensions(source, profile);
        PhotoExportSpec.Resolved resolved = spec.resolve(dimensions.width, dimensions.height);
        String model = PhotoPresetRenderer.modelVersion(profile, resolved);
        String key = key(sourceSha, presetSha, model); File directory = source.getParentFile();
        String display = stem(original.filename()) + "_" + stem(presetName) + ".JPG";
        // The private path is content-addressed only: display-name changes or selecting
        // identical bytes from another local file cannot create a second derived copy.
        File target = new File(directory, "effect-" + key + "__Effect.JPG").getCanonicalFile();
        if (!directory.equals(target.getParentFile())) throw new IOException("效果图保存路径无效。");
        File records = new File(context.getFilesDir(), "photo-preset-results").getCanonicalFile();
        if (!context.getFilesDir().getCanonicalFile().equals(records.getParentFile()) || !records.isDirectory() && !records.mkdirs()) throw new IOException("无法保存效果图记录。");
        AtomicFile record = new AtomicFile(new File(records, key + ".record"));
        OutputRecord previous = readRecord(record);
        File internal = new File(new File(context.getFilesDir(), "pictures"), "camera-album").getCanonicalFile();
        File externalPictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File external = externalPictures == null ? null : new File(externalPictures, "camera-album").getCanonicalFile();
        java.util.Set<File> candidates = new java.util.LinkedHashSet<>();
        candidates.add(directory); candidates.add(internal); if (external != null) candidates.add(external);
        for (File candidateDirectory : candidates) {
            File expected = new File(candidateDirectory, target.getName()), candidate = expected.getCanonicalFile();
            if (!expected.equals(candidate) || Files.isSymbolicLink(expected.toPath()) || !candidateDirectory.equals(candidate.getParentFile())) throw new IOException("效果图保存路径无效。");
            if (!candidate.exists()) continue;
            PhotoPresetRenderer.check(job.cancel::get);
            verify(record, candidate, job.cancel::get);
            progress(job, .92, "复用已有效果");
            DownloadedPhoto result = new DownloadedPhoto(candidate, display, original.projectId(), original.projectName());
            sources().bind(result, original, presetSha, presetName, model, job.cancel::get); return new Derived(result, true, resolved);
        }
        File temporary = File.createTempFile("effect-processing-", ".part", directory);
        try {
            new PhotoPresetRenderer(context.getAssets()).render(source, temporary, profile, spec, job.cancel::get, value -> progress(job, .25 + .60 * value, value < .80 ? "套用预设" : "生成效果图"));
            PhotoPresetRenderer.check(job.cancel::get);
            progress(job, .86, "校验效果图");
            if (!sourceSha.equals(hash(source, job.cancel::get))) throw new IOException("原图在处理时发生变化，未发布效果图。");
            long bytes = temporary.length(); String sha = hash(temporary, job.cancel::get);
            if (previous != null && (previous.bytes != bytes || !previous.sha.equals(sha))) throw new IOException("效果图提交记录与重新处理结果不符，未覆盖。");
            sources().rememberModel(key, model);
            if (previous == null) writeRecord(record, bytes, sha);
            progress(job, .90, "保存效果图");
            if (target.exists()) throw new IOException("效果图目标已存在，未覆盖。");
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { if (!temporary.renameTo(target)) throw new IOException("效果图提交未完成，原图已保留。"); }
            verify(record, target, job.cancel::get); DownloadedPhoto result = new DownloadedPhoto(target, display, original.projectId(), original.projectName());
            progress(job, .94, "记录效果图");
            sources().bind(result, original, presetSha, presetName, model, job.cancel::get); return new Derived(result, false, resolved);
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    private File owned(DownloadedPhoto photo) throws IOException {
        if (photo == null || photo.file() == null) throw new IOException("原图不存在。");
        File file = photo.file().getCanonicalFile(), internal = new File(new File(context.getFilesDir(), "pictures"), "camera-album").getCanonicalFile();
        File external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (!internal.equals(file.getParentFile()) && (external == null || !new File(external, "camera-album").getCanonicalFile().equals(file.getParentFile()))) throw new IOException("只能处理应用内已完成的原图。");
        if (file.length() > 512L * 1024 * 1024) throw new IOException("原图超过 512 MB 上限。");
        PhotoPreviewCache.validateJpeg(file, file.length()); return file;
    }
    /** Worker-only lookup for the settings panel. Verifies output bytes and never creates an image/public copy. */
    public DownloadedPhoto findResult(Edit edit) throws IOException {
        if (edit == null) throw new IllegalArgumentException("edit");
        if (edit.source.local == null) return null;
        DownloadedPhoto original = edit.draft == null ? edit.source.local : processingOriginalFor(edit.source.local);
        File source = owned(original); String sha = hash(source, () -> false);
        String expected = edit.draft == null ? edit.expectedSourceSha : edit.draft.sourceSha;
        if (!expected.isEmpty() && !expected.equals(sha)) throw new IOException("照片已变化，请重新打开导出设置。");
        if (edit.draft == null && edit.exportSpec.mode == PhotoExportSpec.Mode.FULL && canPreserveFullEffect(original, () -> false)) return original;
        PresetPreviewProfile profile = edit.draft == null ? neutralProfile() : edit.draft.profile();
        PhotoPresetRenderer.Dimensions dimensions = PhotoPresetRenderer.dimensions(source, profile);
        String model = PhotoPresetRenderer.modelVersion(profile, edit.exportSpec.resolve(dimensions.width, dimensions.height));
        String key = key(sha, edit.draft == null ? neutralPresetSha() : edit.draft.effectPresetSha(), model);
        java.util.Set<File> directories = new java.util.LinkedHashSet<>(); directories.add(source.getParentFile());
        directories.add(new File(new File(context.getFilesDir(), "pictures"), "camera-album").getCanonicalFile());
        File external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (external != null) directories.add(new File(external, "camera-album").getCanonicalFile());
        AtomicFile record = new AtomicFile(new File(new File(context.getFilesDir(), "photo-preset-results"), key + ".record"));
        for (File directory : directories) {
            File expectedFile = new File(directory, "effect-" + key + "__Effect.JPG"), candidate = expectedFile.getCanonicalFile();
            if (!expectedFile.equals(candidate) || Files.isSymbolicLink(expectedFile.toPath())) throw new IOException("效果图保存路径无效。");
            if (!candidate.exists()) continue;
            verify(record, candidate, () -> false);
            return new DownloadedPhoto(candidate, stem(original.filename()) + "_" + stem(edit.draft == null ? "已保存照片" : edit.draft.label()) + ".JPG", original.projectId(), original.projectName());
        }
        return null;
    }
    private boolean canPreserveFullEffect(DownloadedPhoto photo, BooleanSupplier cancel) throws IOException {
        if (!isSavedEffect(photo)) return false;
        File source = owned(photo);
        if (Files.isSymbolicLink(photo.file().toPath())) throw new IOException("效果图保存路径无效。");
        String key = source.getName().substring(7, 71);
        File records = new File(context.getFilesDir(), "photo-preset-results").getCanonicalFile();
        if (!context.getFilesDir().getCanonicalFile().equals(records.getParentFile())) throw new IOException("效果图记录路径无效。");
        AtomicFile record = checkedLedger(records, key + ".record"), model = checkedLedger(records, key + ".model");
        // A valid selected historical JPEG may outlive its completion/model ledger. That proves
        // neither Q95 nor reuse: freeze and read its own bytes, then genuinely encode a new result.
        // Source path/read/cancellation failures stay outside this narrowly scoped ledger fallback.
        String sha = hash(source, cancel); OutputRecord completed;
        try { completed = readRecord(record); }
        catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (IOException unavailable) { PhotoPresetRenderer.check(cancel); return false; }
        if (completed == null || completed.bytes != source.length() || !completed.sha.equals(sha)) return false;
        if (!model.getBaseFile().exists() && !new File(model.getBaseFile().getPath() + ".bak").exists()) return true; // Pre-model-ledger versions only encoded Q95.
        if (model.getBaseFile().length() > 1024) return false;
        try (DataInputStream input = new DataInputStream(model.openRead())) {
            if (input.readInt() != 0x50454d31) return false;
            String value = input.readUTF();
            if (value.isEmpty() || value.length() > 256 || input.read() != -1) return false;
            PhotoPresetRenderer.check(cancel);
            return value.endsWith(":full-jpeg95-srgb-v1");
        } catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (PhotoPresetRenderer.Cancelled cancelled) { throw cancelled; }
        catch (IOException unavailable) { PhotoPresetRenderer.check(cancel); return false; }
    }
    private static AtomicFile checkedLedger(File directory, String name) throws IOException {
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            File expected = new File(directory, name + suffix), resolved = expected.getCanonicalFile();
            if (!expected.equals(resolved) || !directory.equals(resolved.getParentFile()) || Files.isSymbolicLink(expected.toPath())) throw new IOException("效果图记录路径无效。");
        }
        return new AtomicFile(new File(directory, name));
    }
    static String key(String sourceSha, String presetSha) throws IOException {
        return key(sourceSha, presetSha, PhotoPresetRenderer.MODEL_VERSION);
    }
    /** Only restore a saved recipe when its full frozen identity names this exact derived version. */
    public static boolean matchesSavedDraft(DownloadedPhoto photo,PhotoEditStore.Draft draft)throws IOException{
        if (photo == null || draft == null) return false;
        String name = photo.file().getName(), model = PhotoPresetRenderer.modelVersion(draft.profile());
        if (name.equals("effect-" + key(draft.sourceSha, draft.effectPresetSha(), model) + "__Effect.JPG")) return true;
        if (!isSavedEffect(photo)) return false;
        PhotoPresetRenderer.Dimensions dimensions = PhotoPresetRenderer.dimensions(photo.file(), neutralProfile());
        // The strict SHA-derived filename, not JPEG quantization guesses, proves which frozen recipe produced this version.
        for (int quality = 70; quality <= 100; quality++) {
            String exported = PhotoPresetRenderer.exportModel(model, PhotoExportSpec.Resolved.identity(dimensions.width, dimensions.height, quality));
            if (name.equals("effect-" + key(draft.sourceSha, draft.effectPresetSha(), exported) + "__Effect.JPG")) return true;
        }
        return false;
    }
    private static PresetPreviewProfile neutralProfile() { return cc.huevia.ncp.AdjustedPreviewProfile.of(null, cc.huevia.ncp.PhotoAdjustments.NONE); }
    private static String neutralPresetSha() { return PhotoEditStore.hash("huevia-manual-original-v1".getBytes(StandardCharsets.UTF_8)); }
    public static boolean isSavedEffect(DownloadedPhoto photo){return photo!=null&&PhotoPresetSources.isEffect(photo.file());}
    static String key(String sourceSha, String presetSha, String model) throws IOException {
        if (!sourceSha.matches("[0-9a-f]{64}") || !presetSha.matches("[0-9a-f]{64}")) throw new IOException("效果图身份无效。");
        if (model == null || model.isEmpty() || model.length() > 512 || model.indexOf('\n') >= 0 || model.indexOf('\r') >= 0) throw new IOException("效果图模型身份无效。");
        return hex(digest().digest((sourceSha + "\n" + presetSha + "\n" + model).getBytes(StandardCharsets.UTF_8)));
    }
    private static void writeRecord(AtomicFile file, long bytes, String sha) throws IOException {
        FileOutputStream output = null;
        try {
            output = file.startWrite(); DataOutputStream data = new DataOutputStream(output); data.writeInt(MAGIC); data.writeLong(bytes); data.writeUTF(sha);
            data.flush(); output.getFD().sync(); file.finishWrite(output); output = null;
            OutputRecord actual = readRecord(file);
            if (actual == null || actual.bytes != bytes || !actual.sha.equals(sha)) throw new IOException("效果图完成记录尚未确认保存。");
        } finally { if (output != null) file.failWrite(output); }
    }
    private static final class OutputRecord { final long bytes; final String sha; OutputRecord(long bytes, String sha) { this.bytes = bytes; this.sha = sha; } }
    private static OutputRecord readRecord(AtomicFile file) throws IOException {
        File base = file.getBaseFile(); if (!base.exists() && !new File(base.getPath() + ".bak").exists()) return null;
        if (base.exists() && base.length() > 256) throw new IOException("效果图完成记录无效。");
        try (DataInputStream input = new DataInputStream(file.openRead())) {
            if (input.readInt() != MAGIC) throw new IOException("效果图完成记录损坏，未覆盖。");
            long bytes = input.readLong(); String sha = input.readUTF();
            if (bytes < 4 || bytes > 512L * 1024 * 1024 || !sha.matches("[0-9a-f]{64}") || input.read() != -1) throw new IOException("效果图完成记录无效。");
            return new OutputRecord(bytes, sha);
        }
    }
    private static void verify(AtomicFile record, File target, BooleanSupplier cancel) throws IOException {
        OutputRecord actual = readRecord(record);
        if (actual == null || actual.bytes != target.length() || !actual.sha.equals(hash(target, cancel))) throw new IOException("已保存的效果图校验不符，未覆盖。");
        PhotoPreviewCache.validateJpeg(target, target.length());
    }
    private static String stem(String value) { String clean = value == null ? "Photo" : value.replaceAll("\\.[Jj][Pp][Ee]?[Gg]$", "").replaceAll("[^\\p{L}\\p{N}_-]", "_"); return clean.isEmpty() ? "Photo" : clean.substring(0, Math.min(40, clean.length())); }
    private static String hash(File file, BooleanSupplier cancel) throws IOException {
        MessageDigest digest = digest(); byte[] buffer = new byte[65536]; PhotoPresetRenderer.check(cancel);
        try (FileInputStream input = new FileInputStream(file)) {
            int count; while ((count = input.read(buffer)) != -1) { PhotoPresetRenderer.check(cancel); digest.update(buffer, 0, count); }
        }
        PhotoPresetRenderer.check(cancel); return hex(digest.digest());
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static String hex(byte[] data) { StringBuilder value = new StringBuilder(64); for (byte item : data) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255)); return value.toString(); }
    private static String message(Throwable failure) { String value = failure.getMessage(); return value == null || value.isEmpty() ? "照片处理未完成，原图已保留。" : value; }
}

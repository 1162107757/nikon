package com.nikonpreset.usbprobe.album;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import com.nikonpreset.usbprobe.library.PresetLibrary;
import com.nikonpreset.usbprobe.library.PresetRecord;
import com.nikonpreset.usbprobe.ncp.PresetPreviewProfile;
import com.nikonpreset.usbprobe.ncp.PresetPreviewProfiles;
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

/** Application-owned serial local processing; camera worker is used only to acquire remote originals. */
public final class PhotoPresetBatch {
    public static final int MAX_BATCH = 100;
    public static final class Source {
        final PhotoItem remote; final DownloadedPhoto local;
        private Source(PhotoItem remote, DownloadedPhoto local) { this.remote = remote; this.local = local; }
        public static Source remote(PhotoItem photo) { if (photo == null) throw new IllegalArgumentException("photo"); return new Source(photo, null); }
        public static Source local(DownloadedPhoto photo) { if (photo == null) throw new IllegalArgumentException("photo"); return new Source(null, photo); }
        public String filename() { return remote == null ? local.filename() : remote.filename(); }
        public boolean requiresCamera() { return remote != null; }
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
    private static final class Job {
        final List<Source> sources; final String preset; final PhotoLocalTasks.Lease lease; final AtomicBoolean cancel = new AtomicBoolean();
        final AlbumController.Callback<PhotoPresetBatchState> finished;
        final Consumer<PhotoPresetBatchState> progress;
        final List<String> errors = new ArrayList<>(); final List<DownloadedPhoto> results = new ArrayList<>();
        final Map<String, Outcome> inputOutcomes = new LinkedHashMap<>(), outcomes = new LinkedHashMap<>();
        int completed, failed, cancelled, reused, merged, index; double fraction; long lastProgress; String filename = "", presetName = "", error = "", stage = "准备照片";
        Job(List<Source> sources, String preset, PhotoLocalTasks.Lease lease, AlbumController.Callback<PhotoPresetBatchState> finished,
            Consumer<PhotoPresetBatchState> progress) {
            this.sources = sources; this.preset = preset; this.lease = lease; this.finished = finished; this.progress = progress;
        }
    }
    private static final class Outcome {
        final String error;
        Outcome(String error) { this.error = error; }
    }
    private static final class Derived {
        final DownloadedPhoto photo; final boolean reused;
        Derived(DownloadedPhoto photo, boolean reused) { this.photo = photo; this.reused = reused; }
    }
    private static final class AcquisitionTimeout extends IOException { AcquisitionTimeout(String text) { super(text); } }
    public PhotoPresetBatch(Context context, AlbumController album) {
        if (context == null || album == null) throw new IllegalArgumentException("context/album");
        Context application = context.getApplicationContext(); this.context = application == null ? context : application; this.album = album;
    }
    public PhotoPresetBatchState state() {
        PhotoPresetBatchState snapshot = state; List<DownloadedPhoto> present = new ArrayList<>();
        for (DownloadedPhoto photo : snapshot.results) if (photo.file().isFile()) present.add(photo);
        return present.size() == snapshot.results.size() ? snapshot : new PhotoPresetBatchState(snapshot.active, snapshot.total, snapshot.completed, snapshot.failed,
                snapshot.cancelled, snapshot.progress, snapshot.currentFilename, snapshot.presetName, snapshot.error, snapshot.errors, present, snapshot.reused, snapshot.merged,
                snapshot.currentNumber, snapshot.currentProgress, snapshot.currentStage);
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
        if (callback == null) throw new IllegalArgumentException("callback");
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
            job = new Job(Collections.unmodifiableList(new ArrayList<>(sources)), presetId, lease, finished, progress); current = job;
            publish(job, true); // Synchronous active state is visible to the runtime's foreground-service lease.
        }
        main.post(() -> callback.onSuccess(null)); worker.execute(() -> run(job));
    }
    public synchronized void cancel() { if (current != null) { current.cancel.set(true); } }
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
                active ? Math.min(job.index + 1, job.sources.size()) : 0, job.fraction, job.stage);
    }
    private void run(Job job) {
        try {
            PresetLibrary library = new PresetLibrary(context.getFilesDir()); PresetRecord preset = library.findBySha256(job.preset);
            if (preset == null) throw new IOException("本地预设不存在，请重新选择。");
            byte[] bytes = library.readPreset(preset.id); PresetPreviewProfile profile;
            try { profile = PresetPreviewProfiles.fromPreset(bytes); }
            catch (IOException unsupported) { throw new IOException("此预设暂不支持照片处理。" + unsupported.getMessage(), unsupported); }
            job.presetName = preset.displayName; publish(job, true);
            for (; job.index < job.sources.size(); job.index++, job.fraction = 0) {
                if (job.cancel.get()) break;
                Source source = job.sources.get(job.index); job.filename = source.filename(); job.fraction = 0; job.stage = "准备原图"; publish(job, true);
                String contentKey = null;
                try {
                    PhotoPresetRenderer.check(job.cancel::get);
                    Outcome outcome = job.inputOutcomes.get(source.key());
                    if (outcome != null) {
                        job.merged++;
                    } else {
                        DownloadedPhoto original = processingOriginalFor(acquire(job, source), job.cancel::get);
                        progress(job, .20, "校验原图");
                        String sourceSha = hash(owned(original), job.cancel::get);
                        progress(job, .25, "准备套用");
                        contentKey = key(sourceSha, preset.sha256, PhotoPresetRenderer.modelVersion(profile));
                        outcome = job.outcomes.get(contentKey);
                        if (outcome != null) job.merged++;
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
            else { job.error = message(failure); job.errors.add(job.error); job.failed += job.sources.size() - job.index; job.index = job.sources.size(); }
        } finally {
            if (job.cancel.get()) job.cancelled = job.sources.size() - job.completed - job.failed;
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
        String warning = "";
        try { new PhotoLiveGallery(context).publish(original); }
        catch (IOException failure) {
            if (cancelled(job, failure)) throw failure;
            warning = "原图系统相册保存待重试：" + message(failure);
        }
        PhotoPresetRenderer.check(job.cancel::get);
        progress(job, .25, "套用预设");
        Derived derived = derive(job, original, sourceSha, preset, profile);
        job.results.add(derived.photo); if (derived.reused) job.reused++;
        progress(job, .96, "保存到相册");
        try { new PhotoLiveGallery(context).publish(derived.photo); }
        catch (IOException failure) {
            if (cancelled(job, failure)) throw failure;
            warning += (warning.isEmpty() ? "" : "；") + "效果图已保留，系统相册保存待重试：" + message(failure);
        }
        return new Outcome(warning);
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
    private void completeInput(Job job, Outcome outcome) { if (outcome.error.isEmpty()) job.completed++; else fail(job, outcome.error); }
    private void fail(Job job, String message) { job.failed++; job.error = message; job.errors.add(job.filename + "：" + message); }
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
    private Derived derive(Job job, DownloadedPhoto original, String sourceSha, PresetRecord preset, PresetPreviewProfile profile) throws IOException {
        File source = owned(original);
        String model = PhotoPresetRenderer.modelVersion(profile);
        String key = key(sourceSha, preset.sha256, model); File directory = source.getParentFile();
        String display = stem(original.filename()) + "_" + stem(preset.displayName) + ".JPG";
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
            sources().bind(result, original, preset.sha256, preset.displayName, model, job.cancel::get); return new Derived(result, true);
        }
        File temporary = File.createTempFile("effect-processing-", ".part", directory);
        try {
            new PhotoPresetRenderer().render(source, temporary, profile, job.cancel::get, value -> progress(job, .25 + .60 * value, value < .80 ? "套用预设" : "生成效果图"));
            PhotoPresetRenderer.check(job.cancel::get);
            progress(job, .86, "校验效果图");
            if (!sourceSha.equals(hash(source, job.cancel::get))) throw new IOException("原图在处理时发生变化，未发布效果图。");
            long bytes = temporary.length(); String sha = hash(temporary, job.cancel::get);
            if (previous != null && (previous.bytes != bytes || !previous.sha.equals(sha))) throw new IOException("效果图提交记录与重新处理结果不符，未覆盖。");
            if (previous == null) writeRecord(record, bytes, sha);
            progress(job, .90, "保存效果图");
            if (target.exists()) throw new IOException("效果图目标已存在，未覆盖。");
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { if (!temporary.renameTo(target)) throw new IOException("效果图提交未完成，原图已保留。"); }
            verify(record, target, job.cancel::get); DownloadedPhoto result = new DownloadedPhoto(target, display, original.projectId(), original.projectName());
            progress(job, .94, "记录效果图");
            sources().bind(result, original, preset.sha256, preset.displayName, model, job.cancel::get); return new Derived(result, false);
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
    static String key(String sourceSha, String presetSha) throws IOException {
        return key(sourceSha, presetSha, PhotoPresetRenderer.MODEL_VERSION);
    }
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

package cc.huevia.album;

import android.content.Context;
import cc.huevia.library.PresetLibrary;
import cc.huevia.library.PresetRecord;
import cc.huevia.ncp.NcpCompatibilityFixtures;
import cc.huevia.ncp.PresetPreviewProfiles;
import cc.huevia.ptp.PtpObjectInfo;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real queue/store/batch control with explicit local catalog, gallery and service facades. */
public final class PhotoProjectPresetsHostTest {
    private static final byte[] JPEG = {(byte) 255, (byte) 216, 10, 20, 30, 40, (byte) 255, (byte) 217};
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("project-auto-");
        try {
            originalByteProgressNeverPublishesAnUnchangedEffectQueue(root.resolve("no-op-progress"));
            automaticProgressBelongsToTheCurrentReceipt(root.resolve("automatic-progress"));
            trackedSnapshotDoesNotBecomeTheNextJob(root.resolve("tracked"));
            queuedPresetIsFixedAndManualBatchKeepsOwnership(root.resolve("manual"));
            failedPublicationRequiresExplicitRetry(root.resolve("retry"));
            discoveryCutoffAndInterruptedRecovery(root.resolve("recover"));
            changedSourceAndServiceFailureDoNotLoop(root.resolve("guard"));
            np3AutomaticQueueRetainsOriginalAndReuses(root.resolve("np3"));
            appOnlyReceiptSurvivesFailureRetryAndRestart(root.resolve("app-only"));
            System.out.println("PASS: " + checks + " project automatic-preset checks");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static void automaticProgressBelongsToTheCurrentReceipt(Path root) throws Exception {
        Fixture f = new Fixture(root); f.configure(f.a); DownloadedPhoto source = f.original("AUTO-PROGRESS.JPG"); f.seed(source, f.a);
        String identity = "8".repeat(64); f.receipt(identity, source, f.auto.config(f.id).enabledSince);
        CountDownLatch publication = new CountDownLatch(1), release = new CountDownLatch(1);
        List<PhotoProjectPresets.Processing> values = new java.util.concurrent.CopyOnWriteArrayList<>();
        f.auto.addObserver(() -> { PhotoProjectPresets.Processing value = f.auto.processing(); if (value != null) values.add(value); });
        PhotoLiveGallery.beforePublish = photo -> {
            if (!photo.file().getName().startsWith("effect-")) return;
            publication.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("publication latch timeout"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        };
        try {
            f.auto.onOriginalSaved(f.id, identity, source);
            check(publication.await(10, TimeUnit.SECONDS), "Automatic rendering reaches the effect gallery-publication boundary");
            until(() -> f.auto.progressFor(f.id, identity) >= .96);
            PhotoProjectPresets.Processing active = f.auto.processing();
            check(active != null && active.projectId.equals(f.id) && active.identity.equals(identity) && active.filename.equals(source.filename())
                    && active.presetName.equals(f.a.displayName), "Automatic progress carries the exact project, receipt, filename and queued preset");
            check(active.progress < 1 && active.stage.equals("保存到相册") && f.auto.isPending(f.id, identity), "100% is reserved until gallery publication and task journal finish");
            check(f.auto.progressFor(UUID.randomUUID().toString(), identity) == -1 && f.auto.progressFor(f.id, "9".repeat(64)) == -1,
                    "Another project or queued receipt does not inherit the current photo's percentage");
            double previous = -1;
            for (PhotoProjectPresets.Processing value : values) {
                check(value.progress >= previous && value.progress >= 0 && value.progress < 1, "Automatic progress events remain monotonic and bounded before persistence");
                previous = value.progress;
            }
            release.countDown(); until(() -> f.auto.statusFor(f.id, identity).startsWith("已套用")); f.idle();
            check(f.auto.processing() == null && f.auto.progressFor(f.id, identity) == -1, "Saved automatic jobs clear transient progress and retain their durable saved state");
            check(Arrays.equals(Files.readAllBytes(source.file().toPath()), JPEG), "Live progress never mutates original bytes");
            f.batch.start(List.of(PhotoPresetBatch.Source.remote(f.remote("MANUAL-PROGRESS.JPG"))), f.a.id, new Result<>());
            until(() -> f.album.pending != null); f.album.pending.onProgress(1, 2); f.barrier();
            check(f.auto.processing() == null && f.auto.progressFor(f.id, identity) == -1, "A later manual batch does not become live automatic progress");
            f.album.complete(source); f.idle();
        } finally { release.countDown(); PhotoLiveGallery.beforePublish = null; }
    }
    private static void np3AutomaticQueueRetainsOriginalAndReuses(Path root) throws Exception {
        Fixture f = new Fixture(root);
        PresetRecord np3 = new PresetLibrary(f.context.getFilesDir()).importFile("AUTO.NP3", identityNp3()).record;
        f.configure(np3);
        check(f.auto.config(f.id).presetId.equals(np3.id), "An NP3 preset passes the shared parser and can be selected for automatic project processing");
        DownloadedPhoto source = f.original("NP3-AUTO.JPG"); File effect = f.seed(source, np3);
        byte[] sourceBefore = Files.readAllBytes(source.file().toPath()), outputBefore = Files.readAllBytes(effect.toPath());
        String identity = "7".repeat(64); f.receipt(identity, source, f.auto.config(f.id).enabledSince);
        PhotoLiveGallery.failDerived = true; f.auto.onOriginalSaved(f.id, identity, source);
        until(() -> f.auto.needsRetry(f.id, identity)); f.idle();
        check(f.auto.failedCount() == 1 && f.batch.state().reused == 1 && effect.isFile(), "NP3 automatic publication failure retains a verified reused output and explicit retry state");
        DownloadedPhoto linked = new DownloadedPhoto(effect, "NP3 effect.JPG", f.id, "Auto QA");
        check(new PhotoPresetBatch(f.context, f.album).sourceFor(linked).file().equals(source.file().getCanonicalFile()), "The automatic NP3 cache hit persists the exact original using its separate model identity");
        f.configure(f.b); PhotoLiveGallery.failDerived = false; f.auto.retryProject(f.id);
        until(() -> f.auto.statusFor(f.id, identity).startsWith("已套用")); f.idle();
        check(f.auto.statusFor(f.id, identity).contains(np3.displayName) && f.batch.state().reused == 1, "Retry keeps the queued NP3 snapshot after the project default changes to NCP");
        int rows = PhotoLiveGallery.rows.size(); f.auto.onOriginalSaved(f.id, identity, source); f.barrier(); f.idle();
        check(PhotoLiveGallery.rows.size() == rows && rows == 1 && f.album.downloads == 0, "Repeated automatic NP3 events neither duplicate publications nor download originals");
        check(Arrays.equals(sourceBefore, Files.readAllBytes(source.file().toPath())) && Arrays.equals(outputBefore, Files.readAllBytes(effect.toPath())) && f.work.open.get() == 0,
                "NP3 automatic reuse and retry preserve source/output bytes and release the service lease");
    }

    /** Minimal hand-authored Flexible Color identity preset for automatic-queue dispatch checks. */
    private static byte[] identityNp3() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(output); data.write(new byte[]{'N','C','P',0});
        for (int index = 1; index <= 32; index++) {
            byte[] payload = new byte[index == 1 ? 4 : index == 2 || index == 32 ? 20 : index == 31 ? 28 : 2];
            if (index == 1) payload = "0310".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            else if (index == 2) System.arraycopy("AUTO NP3".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, payload, 0, 8);
            else if (index == 3) payload[1] = 0x20;
            else if (index == 31) { Arrays.fill(payload, 0, 24, (byte)0x80); payload[24] = payload[25] = payload[26] = 1; }
            else if (index == 32) {
                for (int offset = 0; offset < 12; offset += 4) { payload[offset] = (byte)0x80; payload[offset+2] = payload[offset+3] = (byte)0x80; }
                payload[12] = payload[13] = payload[14] = 1; payload[16] = (byte)0xb2; payload[17] = 1; payload[18] = (byte)0x80; payload[19] = 1;
            } else if (index != 4) {
                payload[0] = (byte)(index == 6 ? 0x88 : index == 7 ? 0x82 : index == 22 ? 0x84 : index >= 25 || index == 20 ? 0x80 : 0xff);
                payload[1] = (byte)(index == 12 || index == 13 ? 0 : index == 21 ? 10 : index >= 6 && index <= 11 || index == 14 || index >= 22 && index <= 24 ? 4 : 1);
            }
            data.writeInt(index * 0x100); data.writeInt(payload.length); data.write(payload);
        }
        data.writeInt(0); data.flush(); return output.toByteArray();
    }

    private static void originalByteProgressNeverPublishesAnUnchangedEffectQueue(Path root) throws Exception {
        Fixture f = new Fixture(root); f.barrier(); f.barrier(); f.idle();
        AtomicInteger observers = new AtomicInteger(); f.auto.addObserver(observers::incrementAndGet);
        int hostBefore = f.work.changed.get();
        for (int i = 0; i < 500; i++) { f.album.notifyLiveProgress(); f.album.notifyLibraryChanged(); }
        f.barrier(); f.barrier();
        check(observers.get() == 0 && f.work.changed.get() == hostBefore,
                "Original byte progress and unchanged library notifications cannot rebuild an idle effect queue or rerender its UI");
        f.configure(f.a);
        check(observers.get() > 0 && f.work.changed.get() > hostBefore,
                "An empty project's default preset change still notifies observers without a task-status change");
        int configured = observers.get();
        for (int i = 0; i < 500; i++) f.album.notifyLiveProgress();
        f.barrier(); f.barrier();
        check(observers.get() == configured && !f.auto.hasWork() && f.work.open.get() == 0,
                "A configured idle project remains quiet during transfer progress and never acquires a service lease");
    }

    private static void trackedSnapshotDoesNotBecomeTheNextJob(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto source = f.original("source.JPG"); f.seed(source, f.a);
        PhotoItem remote = f.remote("MANUAL.JPG"); AtomicBoolean nextStarted = new AtomicBoolean();
        f.batch.addObserver(() -> {
            if (!f.batch.isActive() && nextStarted.compareAndSet(false, true)) f.batch.start(List.of(PhotoPresetBatch.Source.remote(remote)), f.a.id, new Result<>());
        });
        Result<PhotoPresetBatchState> terminal = new Result<>();
        f.batch.startTracked(List.of(PhotoPresetBatch.Source.local(source)), f.a.id, new Result<>(), terminal);
        PhotoPresetBatchState own = terminal.await();
        check(own.completed == 1 && own.results.size() == 1 && !own.active, "Tracked completion owns the finished local job snapshot");
        check(f.batch.isActive() && f.batch.state().results.isEmpty(), "A later manual job can replace global state before the tracked callback without corrupting its result");
        AtomicInteger rejectedFinished = new AtomicInteger(); Result<Void> rejected = new Result<>();
        f.batch.startTracked(List.of(PhotoPresetBatch.Source.local(source)), f.a.id, rejected, new AlbumController.Callback<>() {
            public void onSuccess(PhotoPresetBatchState value) { rejectedFinished.incrementAndGet(); }
            public void onError(String error) { rejectedFinished.incrementAndGet(); }
        });
        check(rejected.error != null && rejectedFinished.get() == 0, "Busy rejection does not emit a terminal callback for someone else's job");
        until(() -> f.album.pending != null); f.album.complete(source); f.idle();
        check(own.completed == 1 && own.results.size() == 1, "The immutable tracked result survives subsequent completion");
    }

    private static void queuedPresetIsFixedAndManualBatchKeepsOwnership(Path root) throws Exception {
        Fixture f = new Fixture(root); f.configure(f.a);
        DownloadedPhoto source = f.original("AUTO.JPG"), manual = f.original("MANUAL.JPG"); f.seed(source, f.a);
        f.receipt("1".repeat(64), source, f.auto.config(f.id).enabledSince);
        f.batch.start(List.of(PhotoPresetBatch.Source.remote(f.remote("MANUAL.JPG"))), f.a.id, new Result<>());
        until(() -> f.album.pending != null);
        f.auto.onOriginalSaved(f.id, "1".repeat(64), source);
        until(() -> f.auto.isPending(f.id, "1".repeat(64)));
        check(f.batch.isActive() && f.album.downloads == 1, "Automatic local work waits for a manual acquisition without replacing or cancelling it");
        check(!f.auto.needsRetry(f.id, "1".repeat(64)), "Receipt state distinguishes waiting for a manual job from a retryable failure");
        f.configure(f.b);
        check(f.auto.statusFor(f.id, "1".repeat(64)).contains(f.a.displayName), "Changing the default leaves the already-queued preset snapshot unchanged");
        f.album.complete(manual); until(() -> f.auto.statusFor(f.id, "1".repeat(64)).startsWith("已套用")); f.idle();
        check(f.auto.statusFor(f.id, "1".repeat(64)).contains(f.a.displayName), "The automatic task finishes with the original preset, not the new default");
        check(!f.auto.isPending(f.id, "1".repeat(64)) && !f.auto.needsRetry(f.id, "1".repeat(64)), "A completed effect leaves neither pending nor retry receipt filters");
        int rows = PhotoLiveGallery.rows.size(); f.auto.onOriginalSaved(f.id, "1".repeat(64), source); f.barrier(); f.idle();
        check(PhotoLiveGallery.rows.size() == rows && f.album.downloads == 1, "A repeated original after a default change creates no new effect, publication or remote download");
        check(Arrays.equals(Files.readAllBytes(source.file().toPath()), JPEG) && f.work.open.get() == 0, "Original bytes are retained and automatic work releases its service reservation");
    }

    private static void failedPublicationRequiresExplicitRetry(Path root) throws Exception {
        Fixture f = new Fixture(root); f.configure(f.a); DownloadedPhoto source = f.original("RETRY.JPG"); File effect = f.seed(source, f.a);
        String identity = "2".repeat(64); f.receipt(identity, source, f.auto.config(f.id).enabledSince);
        PhotoLiveGallery.failDerived = true; f.auto.onOriginalSaved(f.id, identity, source);
        until(() -> f.auto.needsRetry(f.id, identity)); f.idle();
        check(effect.isFile() && f.auto.failedCount() == 1, "Publication failure retains the complete effect and an explicit retry state");
        check(!f.auto.isPending(f.id, identity), "A publication failure is retryable without being reported as ongoing processing");
        check(f.auto.processing() == null && f.auto.progressFor(f.id, identity) == -1, "An automatic publication error clears transient rendering percentages");
        int attempts = f.work.started.get(); f.auto.onOriginalSaved(f.id, identity, source); f.barrier(); f.idle();
        check(f.work.started.get() == attempts, "Repeated original events do not automatically retry an error task");
        PhotoLiveGallery.failDerived = false; f.auto.retryProject(f.id);
        until(() -> f.auto.statusFor(f.id, identity).startsWith("已套用")); f.idle();
        check(PhotoLiveGallery.rows.size() == 1 && Arrays.equals(Files.readAllBytes(effect.toPath()), JPEG), "Explicit retry reuses the complete derived copy and publishes without duplicating rows");
        check(f.album.downloads == 0 && Arrays.equals(Files.readAllBytes(source.file().toPath()), JPEG), "Automatic publication retry never reads the camera or modifies the original");
    }

    private static void discoveryCutoffAndInterruptedRecovery(Path root) throws Exception {
        Fixture f = new Fixture(root.resolve("cutoff")); f.configure(f.a);
        DownloadedPhoto old = f.original("OLD.JPG"); f.seed(old, f.a); String oldIdentity = "3".repeat(64);
        f.receipt(oldIdentity, old, f.auto.config(f.id).enabledSince - 1);
        f.auto.onOriginalSaved(f.id, oldIdentity, old); f.barrier();
        check(f.auto.statusFor(f.id, oldIdentity).isEmpty() && !f.auto.hasWork(), "An already-discovered photo is not retrospectively processed when its original finishes after enablement");

        Fixture restored = new Fixture(root.resolve("death"), false); DownloadedPhoto source = restored.original("RECOVER.JPG"); restored.seed(source, restored.a);
        PhotoProjectPresetStore seed = new PhotoProjectPresetStore(new File(restored.context.getFilesDir(), "photo-project-presets"));
        seed.setConfig(restored.id, restored.b.id, restored.b.displayName);
        PhotoProjectPresetStore.Task queued = new PhotoProjectPresetStore.Task(restored.id, "4".repeat(64), restored.a.id, restored.a.displayName,
                source.file().getName(), source.filename(), JPEG.length, "queued", "", "", 1);
        seed.queue(queued); seed.update(queued.withStatus("processing", "", "")); restored.start();
        until(() -> restored.auto.statusFor(restored.id, "4".repeat(64)).startsWith("已套用")); restored.idle();
        check(restored.auto.config(restored.id).presetId.equals(restored.b.id) && restored.auto.statusFor(restored.id, "4".repeat(64)).contains(restored.a.displayName),
                "A recovered processing task resumes its persisted preset despite a different current default");
        check(restored.album.downloads == 0 && PhotoLiveGallery.rows.size() == 1, "Process-recovery work uses owned local bytes and existing effect publication");
    }

    private static void changedSourceAndServiceFailureDoNotLoop(Path root) throws Exception {
        Fixture bad = new Fixture(root.resolve("source"), false); DownloadedPhoto source = bad.original("CHANGED.JPG");
        PhotoProjectPresetStore store = new PhotoProjectPresetStore(new File(bad.context.getFilesDir(), "photo-project-presets"));
        store.queue(new PhotoProjectPresetStore.Task(bad.id, "5".repeat(64), bad.a.id, bad.a.displayName, source.file().getName(), source.filename(), JPEG.length + 1, "queued", "", "", 1));
        bad.start(); until(() -> bad.auto.statusFor(bad.id, "5".repeat(64)).startsWith("效果待重试")); bad.idle();
        check(bad.auto.failedCount() == 1 && bad.album.downloads == 0 && Arrays.equals(Files.readAllBytes(source.file().toPath()), JPEG), "A size-mismatched source fails without render, redownload or overwrite");

        Fixture denied = new Fixture(root.resolve("service")); denied.configure(denied.a); DownloadedPhoto original = denied.original("SERVICE.JPG"); denied.seed(original, denied.a);
        denied.receipt("6".repeat(64), original, denied.auto.config(denied.id).enabledSince); denied.work.reject = true;
        denied.auto.onOriginalSaved(denied.id, "6".repeat(64), original);
        until(() -> denied.auto.statusFor(denied.id, "6".repeat(64)).startsWith("效果待重试")); denied.idle();
        check(!denied.batch.isActive() && denied.auto.error().contains("service unavailable"), "Service startup failure records a readable error without starting pixels or looping the queue");
        try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.delete()) { check(true, "Failed service startup releases the local source reservation"); }
        denied.work.reject = false; denied.auto.retryProject(denied.id);
        until(() -> denied.auto.statusFor(denied.id, "6".repeat(64)).startsWith("已套用")); denied.idle();
        check(denied.work.open.get() == 0, "User retry after service recovery completes and releases the new reservation");
    }

    private static void appOnlyReceiptSurvivesFailureRetryAndRestart(Path root) throws Exception {
        Fixture f = new Fixture(root.resolve("retry")); f.configure(f.a); DownloadedPhoto source = f.original("LOCAL-LIVE.JPG"); f.seed(source, f.a);
        String identity = "a".repeat(64); long accepted = f.auto.config(f.id).enabledSince;
        f.album.receipts.put(f.id, List.of(new PhotoLiveReceipt(f.id, identity, "saved", f.remote(source.filename()), source, "", accepted, accepted, PhotoSaveTarget.APP_ONLY)));
        PhotoLiveGallery.publications.clear(); PhotoLiveGallery.failDerived = true; f.work.reject = true;
        f.auto.onOriginalSaved(f.id, identity, source); until(() -> f.auto.needsRetry(f.id, identity)); f.idle();
        PhotoProjectPresetStore disk = new PhotoProjectPresetStore(new File(f.context.getFilesDir(), "photo-project-presets"));
        PhotoProjectPresetStore.Task failed = disk.tasks(f.id).get(0);
        check(failed.saveTarget == PhotoSaveTarget.APP_ONLY && failed.status.equals("error"), "Automatic task freezes the receive receipt's App-only destination before failure");
        f.configure(f.b); f.work.reject = false; f.auto.retryProject(f.id); until(() -> f.auto.statusFor(f.id, identity).startsWith("已套用")); f.idle();
        check(PhotoLiveGallery.publications.isEmpty() && PhotoLiveGallery.rows.isEmpty() && f.batch.state().completed == 1,
                "Explicit retry keeps App-only destination despite changed project defaults and unavailable phone publisher");
        disk = new PhotoProjectPresetStore(new File(f.context.getFilesDir(), "photo-project-presets"));
        check(disk.tasks(f.id).get(0).saveTarget == PhotoSaveTarget.APP_ONLY && disk.tasks(f.id).get(0).presetId.equals(f.a.id),
                "Completed automatic task retains its accepted destination and preset in the durable journal");
        Fixture restored = new Fixture(root.resolve("restart"), false); DownloadedPhoto incoming = restored.original("RESTORE-LOCAL.JPG"); restored.seed(incoming, restored.a);
        PhotoProjectPresetStore seed = new PhotoProjectPresetStore(new File(restored.context.getFilesDir(), "photo-project-presets"));
        PhotoProjectPresetStore.Task task = new PhotoProjectPresetStore.Task(restored.id, "b".repeat(64), restored.a.id, restored.a.displayName,
                incoming.file().getName(), incoming.filename(), JPEG.length, "queued", "", "", 1, PhotoSaveTarget.APP_ONLY);
        seed.queue(task); seed.update(task.withStatus("processing", "", "")); PhotoLiveGallery.publications.clear(); restored.start();
        until(() -> restored.auto.statusFor(restored.id, "b".repeat(64)).startsWith("已套用")); restored.idle();
        check(restored.batch.state().completed == 1 && PhotoLiveGallery.publications.isEmpty() && PhotoLiveGallery.rows.isEmpty(),
                "Process restart replays only local rendering and never promotes an App-only task into a phone export");
        check(Arrays.equals(JPEG, Files.readAllBytes(source.file().toPath())) && Arrays.equals(JPEG, Files.readAllBytes(incoming.file().toPath()))
                        && f.album.downloads == 0 && restored.album.downloads == 0, "Automatic destination recovery preserves originals without camera reads");
    }
    private static final class Fixture {
        final Context context; final AlbumController album = new AlbumController(); final PhotoPresetBatch batch; final Work work = new Work();
        final String id = UUID.randomUUID().toString(); final Path originals; final PresetRecord a, b; PhotoProjectPresets auto;
        Fixture(Path root) throws Exception { this(root, true); }
        Fixture(Path root, boolean start) throws Exception {
            Files.createDirectories(root); context = new Context(root.toFile()); originals = root.resolve("external/Pictures/camera-album"); Files.createDirectories(originals);
            PresetLibrary library = new PresetLibrary(context.getFilesDir()); a = library.importFile("A.NCP", NcpCompatibilityFixtures.ncp()).record;
            byte[] bytes = NcpCompatibilityFixtures.ncp(); System.arraycopy("AUTO TWO".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, 16, 8);
            b = library.importFile("B.NCP", bytes).record; batch = new PhotoPresetBatch(context, album);
            album.projects.add(new PhotoLiveProject(id, "Auto QA", System.currentTimeMillis(), Collections.emptyList(), 0));
            PhotoLiveGallery.rows.clear(); PhotoLiveGallery.failDerived = false; if (start) start();
        }
        void start() throws Exception { auto = new PhotoProjectPresets(context, album, batch, work); barrier(); }
        void configure(PresetRecord preset) throws Exception { Result<Void> result = new Result<>(); auto.setProjectPreset(id, preset.id, result); result.await(); }
        void barrier() throws Exception { Result<PresetRecord> result = new Result<>(); auto.validatePreset(a.id, result); result.await(); }
        DownloadedPhoto original(String name) throws Exception { Path file = originals.resolve(name); Files.write(file, JPEG); return new DownloadedPhoto(file.toFile(), name, id, "Auto QA"); }
        PhotoItem remote(String name) { return new PhotoItem("session", 43, new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801).compressedSize(JPEG.length).filename(name).build()); }
        void receipt(String identity, DownloadedPhoto source, long created) { album.receipts.put(id, List.of(new PhotoLiveReceipt(id, identity, "saved", remote(source.filename()), source, "", created, created))); }
        File seed(DownloadedPhoto source, PresetRecord preset) throws Exception {
            String model = PhotoPresetRenderer.modelVersion(PresetPreviewProfiles.fromPreset(new PresetLibrary(context.getFilesDir()).readPreset(preset.id)));
            String key = PhotoPresetBatch.key(hex(MessageDigest.getInstance("SHA-256").digest(JPEG)), preset.sha256, model); File output = originals.resolve("effect-" + key + "__Effect.JPG").toFile(); Files.write(output.toPath(), JPEG);
            Path records = context.getFilesDir().toPath().resolve("photo-preset-results"); Files.createDirectories(records);
            try (DataOutputStream data = new DataOutputStream(Files.newOutputStream(records.resolve(key + ".record")))) { data.writeInt(0x50424531); data.writeLong(JPEG.length); data.writeUTF(hex(MessageDigest.getInstance("SHA-256").digest(JPEG))); }
            return output;
        }
        void idle() throws Exception { until(() -> !batch.isActive() && !auto.hasWork() && work.open.get() == 0); }
    }
    private static final class Work implements PhotoProjectPresets.WorkHost {
        final AtomicInteger open = new AtomicInteger(), started = new AtomicInteger(), changed = new AtomicInteger(); volatile boolean reject;
        public AutoCloseable beginPhotoWork() { if (reject) throw new IllegalStateException("service unavailable"); started.incrementAndGet(); open.incrementAndGet(); return open::decrementAndGet; }
        public void onChanged() { changed.incrementAndGet(); }
    }
    private static final class Result<T> implements AlbumController.Callback<T> {
        final CountDownLatch done = new CountDownLatch(1); volatile T value; volatile String error;
        public void onSuccess(T result) { value = result; done.countDown(); }
        public void onError(String text) { error = text; done.countDown(); }
        T await() throws Exception { if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("callback timeout"); if (error != null) throw new AssertionError(error); return value; }
    }
    private static void until(BooleanSupplier ready) throws Exception { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15); while (!ready.getAsBoolean()) { if (System.nanoTime() > end) throw new AssertionError("queue timeout"); Thread.sleep(10); } }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte value : bytes) out.append(String.format("%02x", value & 255)); return out.toString(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}

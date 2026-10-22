package cc.huevia.album;

import android.content.Context;
import android.content.ContentResolver;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the production publisher/queue against a fault-injecting, file-backed Android provider double. */
public final class PhotoAlbumSaverHostTest {
    private static int checks;
    // Includes an EXIF-shaped payload so byte-for-byte checks catch any recompression or metadata stripping.
    private static final byte[] JPEG = {(byte) 255, (byte) 216, (byte) 255, (byte) 225, 0, 10, 'E', 'x', 'i', 'f', 0, 0, 1, 2, (byte) 255, (byte) 217};

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("photo-album-save-test-");
        try {
            savesExactOriginalAndPreservesProjectFolder(root.resolve("normal"));
            recoversInsertAndCommitGapsAcrossViews(root.resolve("gaps"));
            failedPublicationRetriesLocallyAndNeverOverwritesPublicOriginal(root.resolve("retry"));
            rejectsUnownedOrIncompleteSources(root.resolve("invalid"));
            legacyPermissionScanAndRetryPreserveOneFile(root.resolve("legacy"));
            deletionFollowsJournalAcrossProjectViewsAndUserMoves(root.resolve("delete-modern"));
            deletionRecoversMarkersAndPreservesModifiedOrForeignCopies(root.resolve("delete-recovery"));
            legacyDeletionUsesExactJournalAndPermissions(root.resolve("delete-legacy"));
            deletionLeaseExcludesPublication(root.resolve("delete-lease"));
            asynchronousQueueCoalescesAndReportsOnMain(root.resolve("queue"));
            publicationStatusAndLegacyMarkerRecoveryAreReadOnly(root.resolve("status"));
            sameEffectAliasesReusePublicationWithoutMergingDifferentPresets(root.resolve("effect-alias"), 35);
            sameEffectAliasesReusePublicationWithoutMergingDifferentPresets(root.resolve("effect-alias-legacy"), 28);
            System.out.println("PASS: " + checks + " photo album saver checks");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) throw new AssertionError("cleanup escaped test directory");
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void savesExactOriginalAndPreservesProjectFolder(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile()); context.permissionGranted = false;
        DownloadedPhoto original = original(context, "live-first__DSC001.JPG"); long modified = original.file().lastModified();
        PhotoLiveProjectStore store = store(context); PhotoLiveProject project = store.create("活动初名");
        DownloadedPhoto member = member(original, project); store.record(project.id, "1".repeat(64), member);
        PhotoLiveGallery gallery = new PhotoLiveGallery(context); String uri = gallery.publish(member);
        equal(context.resolver.inserts, 1); equal(context.resolver.writes, 1); equal(context.resolver.rows.size(), 1);
        ContentResolver.Row row = context.resolver.rows.values().iterator().next(); equal(row.pending, 0);
        yes(Arrays.equals(Files.readAllBytes(row.file.toPath()), JPEG)); equal(original.file().lastModified(), modified);
        String path = row.path; yes(path.contains("活动初名-"));
        PhotoLiveProject renamed = store.rename(project.id, "活动改名");
        equal(gallery.publish(member(original, renamed)), uri); equal(gallery.publish(original), uri);
        equal(context.resolver.inserts, 1); equal(context.resolver.writes, 1);
        DownloadedPhoto next = original(context, "live-second__DSC002.JPG"); gallery.publish(member(next, renamed));
        equal(context.resolver.inserts, 1); // A second private alias with the exact same JPEG bytes reuses its existing publication.
        for (ContentResolver.Row existing : context.resolver.rows.values()) equal(existing.path, path);
        yes(Arrays.equals(Files.readAllBytes(original.file().toPath()), JPEG)); equal(store.get(project.id).photoCount, 1);
        // A user move/rename of an already public photo retains its URI and never causes a duplicate.
        row.name = "用户重命名.jpg"; row.path = "Pictures/用户相册/";
        equal(gallery.publish(member(original, renamed)), uri); equal(context.resolver.inserts, 1);
    }

    private static void recoversInsertAndCommitGapsAcrossViews(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile()); PhotoLiveGallery gallery = new PhotoLiveGallery(context);
        DownloadedPhoto original = original(context, "live-gap__DSC003.JPG"); PhotoLiveProject project = store(context).create("项目视图");
        context.resolver.failAfterInsert = true; fails(() -> gallery.publish(member(original, project)));
        equal(context.resolver.inserts, 1); equal(context.resolver.rows.size(), 1);
        ContentResolver.Row pending = context.resolver.rows.values().iterator().next(); equal(pending.pending, 1);
        String originalPath = pending.path;
        // Retrying from the general downloads view has no project metadata, but must recover the original row.
        String uri = gallery.publish(original); equal(context.resolver.inserts, 1); equal(context.resolver.rows.size(), 1);
        equal(pending.path, originalPath); equal(pending.pending, 0); yes(Arrays.equals(Files.readAllBytes(pending.file.toPath()), JPEG));
        // Model an unavailable final journal write after the public row has become visible.
        Path journal = context.getFilesDir().toPath().resolve("photo-live-gallery");
        try (var records = Files.list(journal)) { for (Path path : records.filter(path -> path.toString().endsWith(".record")).toList()) Files.delete(path); }
        equal(new PhotoLiveGallery(context).publish(original), uri); equal(context.resolver.inserts, 1); equal(context.resolver.writes, 1);
    }

    private static void failedPublicationRetriesLocallyAndNeverOverwritesPublicOriginal(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile()); PhotoLiveGallery gallery = new PhotoLiveGallery(context);
        DownloadedPhoto original = original(context, "manual-retry.jpg"); context.resolver.failBeforePublish = true;
        fails(() -> gallery.publish(original)); equal(context.resolver.rows.size(), 0); equal(context.resolver.deletes, 1);
        yes(Arrays.equals(Files.readAllBytes(original.file().toPath()), JPEG));
        String uri = gallery.publish(original); equal(context.resolver.rows.size(), 1); equal(context.resolver.inserts, 2);
        ContentResolver.Row row = context.resolver.rows.values().iterator().next();
        byte[] edited = JPEG.clone(); edited[12] = 44; Files.write(row.file.toPath(), edited);
        fails(() -> gallery.publish(original)); equal(context.resolver.inserts, 2); equal(context.resolver.deletes, 1);
        yes(Arrays.equals(Files.readAllBytes(row.file.toPath()), edited));
        Files.write(row.file.toPath(), JPEG); row.owner = "another.application";
        fails(() -> gallery.publish(original)); equal(context.resolver.inserts, 2); equal(context.resolver.rows.size(), 1);
        row.owner = context.getPackageName(); equal(gallery.publish(original), uri);
    }

    private static void rejectsUnownedOrIncompleteSources(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile()); PhotoLiveGallery gallery = new PhotoLiveGallery(context);
        Path outside = root.resolve("other.jpg"); Files.write(outside, JPEG);
        fails(() -> gallery.publish(new DownloadedPhoto(outside.toFile(), "other.jpg")));
        DownloadedPhoto incomplete = original(context, "incomplete.jpg"); Files.write(incomplete.file().toPath(), new byte[] {(byte) 255, (byte) 216, 1, 2});
        fails(() -> gallery.publish(incomplete));
        DownloadedPhoto nonJpeg = original(context, "original.nef"); fails(() -> gallery.publish(nonJpeg));
        fails(() -> gallery.publish(null)); equal(context.resolver.inserts, 0);
        yes(Arrays.equals(Files.readAllBytes(outside), JPEG));
    }

    private static void legacyPermissionScanAndRetryPreserveOneFile(Path root) throws Exception {
        Build.VERSION.SDK_INT = 28; Context context = new Context(root.toFile()); Environment.setPublicPicturesForTest(root.resolve("public/Pictures").toFile());
        PhotoLiveGallery gallery = new PhotoLiveGallery(context); DownloadedPhoto original = original(context, "legacy__DSC004.jpg");
        context.permissionGranted = false; fails(() -> gallery.publish(original)); equal(context.scanRequests, 0);
        yes(!Files.exists(root.resolve("public"))); yes(Arrays.equals(Files.readAllBytes(original.file().toPath()), JPEG));
        context.permissionGranted = true; context.scanReturnsNull = true;
        PhotoLiveProjectStore store = store(context); PhotoLiveProject project = store.create("旧设备项目");
        fails(() -> gallery.publish(member(original, project))); equal(context.scanRequests, 1);
        List<Path> publicFiles = publicJpegs(root.resolve("public")); equal(publicFiles.size(), 1);
        Path target = publicFiles.get(0); yes(Arrays.equals(Files.readAllBytes(target), JPEG));
        PhotoLiveProject renamed = store.rename(project.id, "后来改名"); context.scanReturnsNull = false;
        String uri = new PhotoLiveGallery(context).publish(original); yes(uri.startsWith("content://media/external/images/media/"));
        equal(publicJpegs(root.resolve("public")).size(), 1); equal(context.resolver.rows.size(), 1);
        equal(gallery.publish(member(original, renamed)), uri); equal(context.scanRequests, 2);
        byte[] changed = JPEG.clone(); changed[13] = 77; Files.write(target, changed);
        fails(() -> gallery.publish(original)); yes(Arrays.equals(Files.readAllBytes(target), changed));
        equal(publicJpegs(root.resolve("public")).size(), 1); yes(Arrays.equals(Files.readAllBytes(original.file().toPath()), JPEG));
    }

    private static void asynchronousQueueCoalescesAndReportsOnMain(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile()); PhotoAlbumSaver saver = PhotoAlbumSaver.get(context);
        yes(saver == PhotoAlbumSaver.get(context)); DownloadedPhoto first = original(context, "queue-first.jpg"), second = original(context, "queue-second.jpg");
        context.resolver.writeStarted = new CountDownLatch(1); context.resolver.allowWrite = new CountDownLatch(1);
        AtomicInteger changes = new AtomicInteger(); AtomicBoolean observersOnMain = new AtomicBoolean(true);
        Runnable observer = () -> { changes.incrementAndGet(); if (Looper.myLooper() != Looper.getMainLooper()) observersOnMain.set(false); };
        saver.addObserver(observer); Result one = new Result(), same = new Result(), two = new Result();
        saver.save(first, one); yes(context.resolver.writeStarted.await(5, TimeUnit.SECONDS)); yes(saver.isBusy());
        saver.save(new DownloadedPhoto(first.file().getCanonicalFile(), first.filename()), same); saver.save(second, two);
        yes(!one.done() && !same.done() && !two.done()); equal(context.resolver.inserts, 1);
        context.resolver.allowWrite.countDown(); String firstUri = one.value(); equal(same.value(), firstUri); equal(two.value(), firstUri);
        yes(one.onMain && same.onMain && two.onMain); equal(one.calls.get(), 1); equal(same.calls.get(), 1); equal(two.calls.get(), 1);
        equal(context.resolver.inserts, 1); equal(context.resolver.writes, 1); yes(!saver.isBusy());
        awaitMain(); yes(changes.get() >= 2); yes(observersOnMain.get());
        saver.removeObserver(observer); int before = changes.get();
        Result repeat = new Result(); saver.save(first, repeat); equal(repeat.value(), firstUri); awaitMain(); equal(changes.get(), before);
        equal(context.resolver.inserts, 1); equal(context.resolver.writes, 1);
        // A provider failure completes every waiter, clears busy, and retries only the completed local file.
        DownloadedPhoto retry = original(context, "queue-error.jpg"); context.resolver.nullQuery = true;
        Result failed = new Result(); saver.save(retry, failed); yes(!failed.error().isEmpty()); yes(failed.onMain); equal(failed.calls.get(), 1); yes(!saver.isBusy());
        context.resolver.nullQuery = false; Result recovered = new Result(); saver.save(retry, recovered);
        equal(recovered.value(), firstUri); equal(context.resolver.inserts, 1); yes(Arrays.equals(Files.readAllBytes(retry.file().toPath()), JPEG));
        Result invalid = new Result(); saver.save(null, invalid); yes(!invalid.error().isEmpty()); yes(invalid.onMain); yes(!saver.isBusy());
        context.resolver.failReadMemory = true; Result memory = new Result(); saver.save(first, memory);
        yes(memory.error().contains("内存")); yes(memory.onMain); equal(memory.calls.get(), 1); yes(!saver.isBusy());
        try (PhotoLocalTasks.Lease available = PhotoLocalTasks.delete()) { yes(Arrays.equals(Files.readAllBytes(first.file().toPath()), JPEG)); }
        Result afterMemory = new Result(); saver.save(first, afterMemory); equal(afterMemory.value(), firstUri); yes(!saver.isBusy());
    }

    private static void publicationStatusAndLegacyMarkerRecoveryAreReadOnly(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile()); PhotoLiveGallery gallery = new PhotoLiveGallery(context);
        DownloadedPhoto original = original(context, "legacy__DSC_0500.JPG");
        equal(gallery.publicationStatus(original).status, PhotoLiveGallery.PublicationStatus.MISSING); equal(context.resolver.inserts, 0);
        String uri = gallery.publish(original); ContentResolver.Row row = context.resolver.rows.values().iterator().next();
        yes(row.name.startsWith("Huevia_DSC_0500_")); yes(row.name.length() < 90);
        int writes = context.resolver.writes, inserts = context.resolver.inserts;
        PhotoLiveGallery.PublicationState saved = gallery.publicationStatus(original);
        equal(saved.status, PhotoLiveGallery.PublicationStatus.PUBLISHED); equal(saved.uri, uri);
        equal(context.resolver.writes, writes); equal(context.resolver.inserts, inserts);
        row.name = "user-changed.jpg"; row.path = "Pictures/Moved/"; equal(gallery.publicationStatus(original).uri, uri);
        context.resolver.nullQuery = true; equal(gallery.publicationStatus(original).status, PhotoLiveGallery.PublicationStatus.UNAVAILABLE); context.resolver.nullQuery = false;
        context.resolver.delete(android.net.Uri.parse(uri), null, null);
        equal(gallery.publicationStatus(original).status, PhotoLiveGallery.PublicationStatus.MISSING);
        String restored = gallery.publish(original); yes(!restored.equals(uri)); equal(context.resolver.inserts, inserts + 1);
        // Recover an old pre-Huevia pending row whose insert response was lost, without introducing a second row.
        context.resolver.delete(android.net.Uri.parse(restored), null, null);
        context.resolver.failAfterInsert = true; fails(() -> gallery.publish(original));
        row = context.resolver.rows.values().iterator().next();
        String sha = PhotoOriginalStore.contentId(original.file());
        String id = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((original.file().getCanonicalPath() + "\0" + sha).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        row.name = "DSC0500-LIVE-" + id + ".jpg";
        int before = context.resolver.inserts;
        equal(gallery.publicationStatus(original).status, PhotoLiveGallery.PublicationStatus.PENDING);
        gallery.publish(original); equal(context.resolver.inserts, before); equal(context.resolver.rows.size(), 1);
        equal(gallery.publicationStatus(original).status, PhotoLiveGallery.PublicationStatus.PUBLISHED);
        yes(Arrays.equals(Files.readAllBytes(original.file().toPath()), JPEG));
    }

    private static void sameEffectAliasesReusePublicationWithoutMergingDifferentPresets(Path root, int sdk) throws Exception {
        Build.VERSION.SDK_INT = sdk; Context context = new Context(root.toFile()); Environment.setPublicPicturesForTest(root.resolve("public/Pictures").toFile());
        PhotoLiveGallery gallery = new PhotoLiveGallery(context); DownloadedPhoto original = original(context, "DSC_0600.JPG");
        byte[] output = JPEG.clone(); output[12] ^= 17;
        DownloadedPhoto first = effect(context, original, "a".repeat(64), "Film", output);
        Path internal = context.getFilesDir().toPath().resolve("pictures/camera-album"); Files.createDirectories(internal);
        Path otherPath = internal.resolve(first.file().getName()); Files.copy(first.file().toPath(), otherPath);
        DownloadedPhoto alias = new DownloadedPhoto(otherPath.toFile(), first.filename());
        String uri = gallery.publish(first); int writes = context.resolver.writes, inserts = context.resolver.inserts, scans = context.scanRequests;
        PhotoLiveGallery.PublicationState aliasStatus = gallery.publicationStatus(alias);
        equal(aliasStatus.status, PhotoLiveGallery.PublicationStatus.PUBLISHED); equal(aliasStatus.uri, uri);
        equal(gallery.publish(alias), uri); equal(context.resolver.rows.size(), 1); equal(context.resolver.writes, writes); equal(context.resolver.inserts, inserts); equal(context.scanRequests, scans);
        DownloadedPhoto differentPreset = effect(context, original, "b".repeat(64), "Other", output);
        String distinct = gallery.publish(differentPreset);
        yes(!distinct.equals(uri)); equal(context.resolver.rows.size(), 2); equal(gallery.publicationStatus(differentPreset).uri, distinct);
        // The same-key alias is still usable when the first private path disappears after publication.
        Files.delete(first.file().toPath()); equal(gallery.publicationStatus(alias).uri, uri); equal(gallery.publish(alias), uri); equal(context.resolver.rows.size(), 2);
        int deletes = context.resolver.deletes; deletePublished(gallery, alias);
        equal(context.resolver.deletes, deletes + 1); equal(context.resolver.rows.size(), 1); equal(gallery.publicationStatus(differentPreset).uri, distinct);
    }

    private static DownloadedPhoto effect(Context context, DownloadedPhoto original, String presetSha, String presetName, byte[] output) throws Exception {
        String sourceSha = PhotoOriginalStore.contentId(original.file());
        String key = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((sourceSha + "\n" + presetSha + "\n" + PhotoPresetRenderer.MODEL_VERSION).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Path file = original.file().getParentFile().toPath().resolve("effect-" + key + "__Effect.JPG"); Files.write(file, output);
        Path records = context.getFilesDir().toPath().resolve("photo-preset-results"); Files.createDirectories(records);
        try (var data = new java.io.DataOutputStream(Files.newOutputStream(records.resolve(key + ".record")))) { data.writeInt(0x50424531); data.writeLong(output.length); data.writeUTF(PhotoOriginalStore.contentId(file.toFile())); }
        DownloadedPhoto effect = new DownloadedPhoto(file.toFile(), "DSC_0600_" + presetName + ".JPG");
        new PhotoPresetSources(context.getFilesDir(), context.getExternalFilesDir("Pictures")).bind(effect, original, presetSha, presetName, () -> false);
        return effect;
    }

    private static void deletionFollowsJournalAcrossProjectViewsAndUserMoves(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile()); PhotoLiveGallery gallery = new PhotoLiveGallery(context);
        DownloadedPhoto original = original(context, "delete__Camera.JPG"); PhotoLiveProject project = store(context).create("项目里的照片");
        String uri = gallery.publish(member(original, project)); ContentResolver.Row row = context.resolver.rows.values().iterator().next();
        row.name = "Moved-and-renamed.jpg"; row.path = "Pictures/用户移动/";
        deletePublished(gallery, original);
        equal(context.resolver.rows.size(), 0); equal(context.resolver.deletes, 1); yes(!row.file.exists());
        yes(Arrays.equals(Files.readAllBytes(original.file().toPath()), JPEG));
        // The same source is deletable from either project metadata or the unclassified downloads view.
        deletePublished(gallery, member(original, project)); equal(context.resolver.deletes, 1);
        try (var files = Files.list(context.getFilesDir().toPath().resolve("photo-live-gallery"))) {
            yes(files.anyMatch(file -> file.toString().endsWith(".record")));
        }
        // No permanent tombstone: an explicit future save of an extant source remains possible.
        String savedAgain = gallery.publish(original); yes(!savedAgain.equals(uri)); equal(context.resolver.rows.size(), 1);
        deletePublished(gallery, original); equal(context.resolver.rows.size(), 0);
        DownloadedPhoto neverPublished = original(context, "never-published.jpg");
        deletePublished(gallery, neverPublished); yes(neverPublished.file().isFile()); equal(context.resolver.deletes, 2);
    }

    private static void deletionRecoversMarkersAndPreservesModifiedOrForeignCopies(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile()); PhotoLiveGallery gallery = new PhotoLiveGallery(context);
        DownloadedPhoto original = original(context, "delete-gap__Camera.JPG"); gallery.publish(original);
        ContentResolver.Row row = context.resolver.rows.values().iterator().next();
        // Losing the URI journal after a committed insert still leaves the exact deterministic marker.
        try (var files = Files.list(context.getFilesDir().toPath().resolve("photo-live-gallery"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".record")).toList()) Files.delete(file);
        }
        byte[] changed = JPEG.clone(); changed[12] ^= 77; Files.write(row.file.toPath(), changed);
        fails(() -> deletePublished(gallery, original)); equal(context.resolver.rows.size(), 1);
        yes(Arrays.equals(Files.readAllBytes(row.file.toPath()), changed)); yes(original.file().isFile());
        Files.write(row.file.toPath(), JPEG); row.owner = "different.application";
        fails(() -> deletePublished(gallery, original)); equal(context.resolver.rows.size(), 1); yes(original.file().isFile());
        row.owner = context.getPackageName(); context.resolver.failDelete = true;
        fails(() -> deletePublished(gallery, original)); equal(context.resolver.rows.size(), 1); yes(original.file().isFile());
        context.resolver.deleteNoOp = true;
        fails(() -> deletePublished(gallery, original)); equal(context.resolver.rows.size(), 1); yes(original.file().isFile());
        context.resolver.deleteNoOp = false; deletePublished(gallery, original); equal(context.resolver.rows.size(), 0);
        yes(Arrays.equals(Files.readAllBytes(original.file().toPath()), JPEG));
        DownloadedPhoto changedSource = original(context, "changed-app-source.jpg"); gallery.publish(changedSource);
        ContentResolver.Row oldCopy = context.resolver.rows.values().iterator().next();
        Files.write(changedSource.file().toPath(), changed);
        fails(() -> deletePublished(gallery, changedSource));
        yes(oldCopy.file.isFile()); yes(changedSource.file().isFile()); equal(context.resolver.rows.size(), 1);
        Files.write(changedSource.file().toPath(), JPEG); deletePublished(gallery, changedSource); equal(context.resolver.rows.size(), 0);
        DownloadedPhoto duplicates = original(context, "duplicate-rows.jpg"); gallery.publish(duplicates);
        ContentResolver.Row first = context.resolver.rows.values().iterator().next();
        android.content.ContentValues duplicate = new android.content.ContentValues();
        duplicate.put("_display_name", first.name.substring(0, first.name.length() - 4) + " (1).jpg");
        duplicate.put("relative_path", first.path); duplicate.put("is_pending", 0);
        context.resolver.insert(android.provider.MediaStore.Images.Media.getContentUri("external_primary"), duplicate);
        ContentResolver.Row second = context.resolver.rows.values().stream().filter(item -> item != first).findFirst().orElseThrow();
        Files.write(second.file.toPath(), changed); int deletedBefore = context.resolver.deletes;
        fails(() -> deletePublished(gallery, duplicates)); equal(context.resolver.deletes, deletedBefore); equal(context.resolver.rows.size(), 2);
        Files.write(second.file.toPath(), JPEG); deletePublished(gallery, duplicates); equal(context.resolver.rows.size(), 0);
        yes(duplicates.file().isFile());
        // An insert response lost before the writer opens its public file must fail safely, then recover when bytes exist.
        DownloadedPhoto pending = original(context, "delete-pending.jpg"); context.resolver.failAfterInsert = true;
        fails(() -> gallery.publish(pending)); ContentResolver.Row pendingRow = context.resolver.rows.values().iterator().next();
        fails(() -> deletePublished(gallery, pending)); yes(pending.file().isFile());
        Files.write(pendingRow.file.toPath(), JPEG); deletePublished(gallery, pending); equal(context.resolver.rows.size(), 0); yes(pending.file().isFile());
    }

    private static void legacyDeletionUsesExactJournalAndPermissions(Path root) throws Exception {
        Build.VERSION.SDK_INT = 28; Context context = new Context(root.toFile()); Environment.setPublicPicturesForTest(root.resolve("public/Pictures").toFile());
        PhotoLiveGallery gallery = new PhotoLiveGallery(context); DownloadedPhoto original = original(context, "legacy-delete.jpg");
        String uri = gallery.publish(original); ContentResolver.Row row = context.resolver.rows.values().iterator().next();
        Path moved = root.resolve("public/Pictures/用户移动/Changed-name.jpg"); Files.createDirectories(moved.getParent()); Files.move(row.file.toPath(), moved); row.file = moved.toFile();
        context.permissionGranted = false; fails(() -> deletePublished(gallery, original)); yes(Files.exists(moved)); yes(original.file().isFile());
        context.permissionGranted = true; byte[] changed = JPEG.clone(); changed[12] ^= 55; Files.write(moved, changed);
        fails(() -> deletePublished(gallery, original)); yes(Files.exists(moved)); equal(context.resolver.rows.size(), 1);
        Files.write(moved, JPEG); deletePublished(gallery, original); yes(!Files.exists(moved)); equal(context.resolver.rows.size(), 0);
        deletePublished(gallery, original); yes(original.file().isFile());
        // Recover a scanner row whose callback was lost: its exact journal path, not merely its name, is authoritative.
        DownloadedPhoto pending = original(context, "legacy-scan-gap.jpg"); context.scanReturnsNull = true;
        fails(() -> gallery.publish(pending)); Path publicFile = publicJpegs(root.resolve("public")).get(0);
        context.resolver.scan(publicFile.toFile()); deletePublished(gallery, pending);
        equal(context.resolver.rows.size(), 0); yes(!Files.exists(publicFile)); yes(pending.file().isFile());
        DownloadedPhoto untracked = original(context, "untracked.jpg");
        Path unrelated = root.resolve("public/Pictures/unrelated.jpg"); Files.write(unrelated, JPEG);
        UriForTest.add(context, unrelated); int rows = context.resolver.rows.size();
        deletePublished(gallery, untracked); equal(context.resolver.rows.size(), rows); yes(Files.exists(unrelated));
        context.scanReturnsNull = false; DownloadedPhoto beforeUpgrade = original(context, "before-upgrade.jpg"); gallery.publish(beforeUpgrade);
        int afterPublish = context.resolver.rows.size(); Build.VERSION.SDK_INT = 35;
        deletePublished(gallery, beforeUpgrade); equal(context.resolver.rows.size(), afterPublish - 1); yes(beforeUpgrade.file().isFile());
    }

    private static final class UriForTest { static void add(Context context, Path file) { context.resolver.scan(file.toFile()); } }

    private static void deletionLeaseExcludesPublication(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile()); PhotoLiveGallery gallery = new PhotoLiveGallery(context);
        DownloadedPhoto photo = original(context, "lease.jpg");
        try (PhotoLocalTasks.Lease use = PhotoLocalTasks.use()) { fails(() -> { try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.delete()) { } }); }
        try (PhotoLocalTasks.Lease deleting = PhotoLocalTasks.delete()) {
            fails(() -> gallery.publish(photo)); equal(context.resolver.inserts, 0);
            gallery.deletePublished(photo); yes(photo.file().isFile());
        }
        gallery.publish(photo); equal(context.resolver.inserts, 1);
        deletePublished(gallery, photo); equal(context.resolver.rows.size(), 0); yes(photo.file().isFile());
    }

    private static void deletePublished(PhotoLiveGallery gallery, DownloadedPhoto photo) throws Exception {
        try (PhotoLocalTasks.Lease deleting = PhotoLocalTasks.delete()) { gallery.deletePublished(photo); }
    }

    private static PhotoLiveProjectStore store(Context context) throws Exception {
        File originals = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "camera-album"); originals.mkdirs();
        return new PhotoLiveProjectStore(new File(context.getFilesDir(), "photo-live-projects"), originals);
    }
    private static DownloadedPhoto original(Context context, String name) throws Exception {
        Path directory = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "camera-album").toPath(); Files.createDirectories(directory);
        Path file = directory.resolve(name); Files.write(file, JPEG); return new DownloadedPhoto(file.toFile(), name);
    }
    private static DownloadedPhoto member(DownloadedPhoto photo, PhotoLiveProject project) { return new DownloadedPhoto(photo.file(), photo.filename(), project.id, project.name); }
    private static List<Path> publicJpegs(Path root) throws Exception { try (var paths = Files.walk(root)) { return paths.filter(path -> path.toString().endsWith(".jpg")).toList(); } }
    private static void awaitMain() throws Exception { CountDownLatch idle = new CountDownLatch(1); new Handler(Looper.getMainLooper()).post(idle::countDown); yes(idle.await(5, TimeUnit.SECONDS)); }
    private static final class Result implements AlbumController.Callback<String> {
        final CountDownLatch completed = new CountDownLatch(1); final AtomicInteger calls = new AtomicInteger();
        volatile String value, error; volatile boolean onMain;
        public void onSuccess(String uri) { calls.incrementAndGet(); onMain = Looper.myLooper() == Looper.getMainLooper(); value = uri; completed.countDown(); }
        public void onError(String message) { calls.incrementAndGet(); onMain = Looper.myLooper() == Looper.getMainLooper(); error = message; completed.countDown(); }
        boolean done() { return completed.getCount() == 0; }
        String value() throws Exception { if (!completed.await(10, TimeUnit.SECONDS)) throw new AssertionError("save callback missing"); if (error != null) throw new AssertionError(error); return value; }
        String error() throws Exception { if (!completed.await(10, TimeUnit.SECONDS)) throw new AssertionError("save callback missing"); if (error == null) throw new AssertionError("expected failure"); return error; }
    }
    private interface Action { void run() throws Exception; }
    private static void fails(Action action) throws Exception { try { action.run(); throw new AssertionError("expected IOException"); } catch (IOException expected) { checks++; } }
    private static void yes(boolean condition) { if (!condition) throw new AssertionError("check " + (checks + 1)); checks++; }
    private static void equal(Object actual, Object expected) { if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected); checks++; }
}

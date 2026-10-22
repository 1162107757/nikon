package cc.huevia.album;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Production NEF store/publisher against the existing file-backed fault-injecting Android provider double. */
public final class RawPhotoStorePublisherHostTest {
    private static int checks;
    private static final String A = "a".repeat(64), B = "b".repeat(64), C = "c".repeat(64);

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("raw-original-host-");
        try {
            immutableStoreDeduplicatesAndRestoresAliases(root.resolve("store"));
            storeRejectsPartialForeignChangedAndConflictingSources(root.resolve("invalid"));
            displayListDoesNotRehashEveryOriginal(root.resolve("display"));
            deterministicDestinationRecoversCommitGap(root.resolve("commit-gap"));
            publisherPreservesOriginalBytesAndReusesOneRow(root.resolve("publish"));
            publisherRecoversInsertPublishAndJournalGaps(root.resolve("provider-gaps"));
            publisherNeverOverwritesModifiedOrForeignRows(root.resolve("foreign"));
            pendingRowsAndChangingSourcesRemainUnpublished(root.resolve("pending"));
            publicationLeaseBlocksConcurrentDeletion(root.resolve("lease"));
            legacyPermissionScanAndRetryKeepOneNef(root.resolve("legacy"));
            interruptedOperationsNeverCommit(root.resolve("interrupt"));
            System.out.println("PASS: " + checks + " RAW original store/publication checks");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) throw new AssertionError("cleanup escaped test root");
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void immutableStoreDeduplicatesAndRestoresAliases(Path root) throws Exception {
        RawPhotoStore store = new RawPhotoStore(root.resolve("camera-raw").toFile());
        byte[] original = nef(1); File part = part(store, original);
        equal(store.list().size(), 0); yes(store.find(A) == null);
        RawPhotoDownload first = store.commit(A, part, "DSC_1234.NEF");
        yes(!part.exists()); equal(first.filename(), "DSC_1234.NEF"); equal(first.byteSize(), (long) original.length);
        equal(first.sha256(), RawPhotoStore.fingerprint(first.file())); equal(first.sourceIdentity(), A);
        yes(Arrays.equals(Files.readAllBytes(first.file().toPath()), original)); yes(first.savedAt() > 0);
        RawPhotoDownload again = store.commit(A, part(store, original), "renamed.NEF");
        equal(again.file(), first.file()); equal(again.filename(), first.filename()); equal(again.savedAt(), first.savedAt());
        RawPhotoDownload differentIdentity = store.commit(B, part(store, original), "other.NEF");
        equal(differentIdentity.file(), first.file()); equal(differentIdentity.sourceIdentity(), B);
        RawPhotoDownload alias = store.alias(C, differentIdentity); equal(alias.file(), first.file()); equal(alias.sourceIdentity(), C);
        RawPhotoStore reopened = new RawPhotoStore(root.resolve("camera-raw").toFile());
        for (String identity : new String[] {A, B, C}) {
            RawPhotoDownload restored = reopened.find(identity); equal(restored.sourceIdentity(), identity);
            equal(restored.sha256(), first.sha256()); equal(restored.savedAt(), first.savedAt()); yes(reopened.contains(restored));
        }
        equal(reopened.list().size(), 1); equal(reopened.list().get(0).file(), first.file());
        fails(() -> reopened.list().clear());
        RawPhotoDownload second = store.commit("d".repeat(64), part(store, nef(2)), "../bad:name.jpg");
        yes(!second.filename().contains("/") && !second.filename().contains(":")); yes(second.filename().endsWith(".NEF"));
        fails(() -> store.alias(A, second)); equal(store.find(A).sha256(), first.sha256());
        equal(reopened.list().size(), 2);
    }

    private static void storeRejectsPartialForeignChangedAndConflictingSources(Path root) throws Exception {
        RawPhotoStore store = new RawPhotoStore(root.resolve("camera-raw").toFile()); byte[] bytes = nef(3);
        RawPhotoDownload original = store.commit(A, part(store, bytes), "DSC.NEF");
        File conflict = part(store, nef(4)); fails(() -> store.commit(A, conflict, "DSC.NEF")); yes(conflict.exists());
        fails(() -> store.commit("session-handle-3", part(store, bytes), "bad.NEF"));
        File outside = root.resolve("outside.part").toFile(); Files.write(outside.toPath(), bytes);
        fails(() -> store.commit(B, outside, "bad.NEF")); yes(Arrays.equals(Files.readAllBytes(outside.toPath()), bytes));
        File renamedJpeg = part(store, new byte[] {(byte) 255, (byte) 216, 0, 0, (byte) 255, (byte) 217});
        fails(() -> store.commit(B, renamedJpeg, "renamed.NEF")); yes(store.find(B) == null);
        File incomplete = part(store, Arrays.copyOf(bytes, bytes.length - 1)); fails(() -> store.commit(B, incomplete, "partial.NEF"));
        File oversized = store.createTemporary();
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(oversized, "rw")) { file.setLength(PhotoFormat.MAX_RAW_BYTES + 1); }
        fails(() -> store.commit(B, oversized, "oversized.NEF")); Files.delete(oversized.toPath());
        RawPhotoDownload madeUp = new RawPhotoDownload(original.file(), original.filename(), original.byteSize() + 1,
                original.sha256(), original.savedAt(), original.sourceIdentity());
        yes(!store.contains(madeUp)); fails(() -> store.alias(B, madeUp));
        Files.write(original.file().toPath(), nef(5)); fails(() -> store.find(A)); fails(store::list);
        yes(Arrays.equals(Files.readAllBytes(original.file().toPath()), nef(5))); yes(store.find(B) == null);
        Files.write(original.file().toPath(), bytes); yes(store.find(A) != null);
        try {
            Path symbolic = root.resolve("symlink"); Files.createSymbolicLink(symbolic, root.resolve("camera-raw"));
            fails(() -> new RawPhotoStore(symbolic.toFile()));
        } catch (UnsupportedOperationException | IOException unavailable) { /* Windows may not grant symlink creation to test users. */ }
    }

    private static void displayListDoesNotRehashEveryOriginal(Path root) throws Exception {
        RawPhotoStore store = new RawPhotoStore(root.resolve("camera-raw").toFile());
        RawPhotoDownload saved = store.commit(A, part(store, nef(1)), "DSC.NEF");
        equal(store.listForDisplay().size(), 1);
        byte[] changed = nef(1); changed[0] = 0;
        Files.write(saved.file().toPath(), changed);
        // Display uses bounded durable metadata, not a scan of every large NEF; it does not authorize export.
        equal(store.listForDisplay().get(0).sha256(), saved.sha256());
        fails(() -> store.find(A)); fails(() -> store.contains(saved));
        Files.write(saved.file().toPath(), Arrays.copyOf(changed, changed.length - 1));
        equal(store.listForDisplay().size(), 0);
        Files.delete(saved.file().toPath()); equal(store.listForDisplay().size(), 0);
    }

    private static void deterministicDestinationRecoversCommitGap(Path root) throws Exception {
        RawPhotoStore store = new RawPhotoStore(root.resolve("camera-raw").toFile()); byte[] bytes = nef(6);
        RawPhotoDownload old = store.commit(A, part(store, bytes), "DSC006.NEF");
        Path records = old.file().toPath().getParent().resolve(".raw-originals");
        Files.delete(records.resolve("source-" + A + ".record"));
        equal(store.list().size(), 0); yes(store.find(A) == null);
        equal(store.commit(B, part(store, bytes), "DSC006.NEF").file(), old.file()); equal(store.list().size(), 1);
        yes(store.find(A) != null); yes(store.find(B) != null);
        try (var files = Files.list(records)) {
            for (Path path : files.filter(path -> path.toString().endsWith(".record")).toList()) Files.delete(path);
        }
        equal(store.list().size(), 0); yes(store.find(A) == null);
        RawPhotoDownload recovered = store.commit(A, part(store, bytes), "DSC006.NEF");
        equal(recovered.file(), old.file()); equal(recovered.sha256(), old.sha256()); equal(store.list().size(), 1);
        try (var files = Files.list(old.file().toPath().getParent())) {
            equal(files.filter(path -> path.getFileName().toString().endsWith(".NEF")).count(), 1L);
        }
        Files.write(root.resolve("camera-raw/unindexed.NEF"), nef(7));
        Files.write(root.resolve("camera-raw/.raw-interrupted.part"), Arrays.copyOf(bytes, 25));
        equal(store.list().size(), 1);
    }

    private static void publisherPreservesOriginalBytesAndReusesOneRow(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile());
        RawPhotoDownload raw = raw(context, A, 8); long changed = raw.file().lastModified();
        RawPhotoPublisher publisher = new RawPhotoPublisher(context); String uri = publisher.publish(raw);
        ContentResolver.Row row = row(context); equal(row.pending, 0); equal(row.path, "Pictures/Huevia/RAW/");
        yes(row.name.endsWith(".NEF")); equal(context.resolver.inserts, 1); equal(context.resolver.writes, 1);
        yes(Arrays.equals(Files.readAllBytes(row.file.toPath()), nef(8)));
        equal(raw.file().lastModified(), changed); equal(new RawPhotoPublisher(context).publish(raw), uri);
        RawPhotoStore store = new RawPhotoStore(raw.file().getParentFile()); RawPhotoDownload alias = store.alias(B, raw);
        equal(publisher.publish(alias), uri); equal(context.resolver.inserts, 1); equal(context.resolver.writes, 1);
        row.name = "用户改名.NEF"; row.path = "Pictures/我的拍摄/";
        equal(publisher.publish(raw), uri); equal(context.resolver.inserts, 1);
        File external = root.resolve("external.NEF").toFile(); Files.write(external.toPath(), nef(8));
        RawPhotoDownload foreign = new RawPhotoDownload(external, "external.NEF", raw.byteSize(), raw.sha256(), raw.savedAt(), A);
        fails(() -> publisher.publish(foreign)); equal(context.resolver.inserts, 1);
        fails(() -> publisher.publish(null));
    }

    private static void publisherRecoversInsertPublishAndJournalGaps(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile()); RawPhotoDownload raw = raw(context, A, 9);
        RawPhotoPublisher publisher = new RawPhotoPublisher(context);
        context.resolver.failAfterInsert = true; fails(() -> publisher.publish(raw));
        equal(context.resolver.inserts, 1); equal(row(context).pending, 1);
        context.resolver.failBeforePublish = true; fails(() -> new RawPhotoPublisher(context).publish(raw));
        equal(context.resolver.inserts, 1); equal(context.resolver.deletes, 0); equal(row(context).pending, 1);
        String uri = new RawPhotoPublisher(context).publish(raw); equal(row(context).pending, 0); equal(context.resolver.inserts, 1);
        Path journals = context.getFilesDir().toPath().resolve("raw-photo-publications");
        try (var files = Files.list(journals)) {
            for (Path path : files.filter(path -> path.toString().endsWith(".record")).toList()) Files.delete(path);
        }
        equal(new RawPhotoPublisher(context).publish(raw), uri); equal(context.resolver.inserts, 1); equal(context.resolver.writes, 2);
        context.resolver.nullQuery = true; fails(() -> publisher.publish(raw));
        context.resolver.nullQuery = false; equal(publisher.publish(raw), uri); equal(context.resolver.inserts, 1);
        yes(Arrays.equals(Files.readAllBytes(raw.file().toPath()), nef(9)));
    }

    private static void publisherNeverOverwritesModifiedOrForeignRows(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile()); RawPhotoDownload raw = raw(context, A, 10);
        RawPhotoPublisher publisher = new RawPhotoPublisher(context); publisher.publish(raw); ContentResolver.Row row = row(context);
        byte[] edited = nef(11); Files.write(row.file.toPath(), edited);
        fails(() -> publisher.publish(raw)); equal(context.resolver.inserts, 1); equal(context.resolver.deletes, 0); equal(context.resolver.writes, 1);
        yes(Arrays.equals(Files.readAllBytes(row.file.toPath()), edited));
        Files.write(row.file.toPath(), nef(10)); row.owner = "another.app"; fails(() -> publisher.publish(raw));
        equal(context.resolver.inserts, 1); equal(context.resolver.writes, 1); equal(context.resolver.deletes, 0);
        row.owner = context.getPackageName(); publisher.publish(raw);
        Files.write(raw.file().toPath(), nef(12)); fails(() -> publisher.publish(raw)); equal(context.resolver.writes, 1);
        yes(Arrays.equals(Files.readAllBytes(row.file.toPath()), nef(10)));
    }

    private static void publicationLeaseBlocksConcurrentDeletion(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile()); RawPhotoDownload raw = raw(context, A, 13);
        context.resolver.writeStarted = new CountDownLatch(1); context.resolver.allowWrite = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread publish = new Thread(() -> { try { new RawPhotoPublisher(context).publish(raw); } catch (Throwable problem) { failed.set(problem); } });
        publish.start(); yes(context.resolver.writeStarted.await(5, TimeUnit.SECONDS));
        fails(PhotoLocalTasks::delete); context.resolver.allowWrite.countDown(); publish.join(10000); yes(!publish.isAlive());
        if (failed.get() != null) throw new AssertionError(failed.get());
        try (PhotoLocalTasks.Lease deletion = PhotoLocalTasks.delete()) { fails(() -> new RawPhotoPublisher(context).publish(raw)); }
        equal(context.resolver.inserts, 1); equal(row(context).pending, 0);
    }

    private static void pendingRowsAndChangingSourcesRemainUnpublished(Path root) throws Exception {
        Build.VERSION.SDK_INT = 35; Context context = new Context(root.toFile()); RawPhotoDownload raw = raw(context, A, 17);
        RawPhotoPublisher publisher = new RawPhotoPublisher(context);
        context.resolver.failBeforePublish = true; fails(() -> publisher.publish(raw));
        ContentResolver.Row pending = row(context); equal(pending.pending, 1); int writes = context.resolver.writes;
        pending.path = "Pictures/其他目录/"; fails(() -> publisher.publish(raw)); equal(context.resolver.writes, writes);
        pending.path = "Pictures/Huevia/RAW/"; pending.owner = "another.app";
        fails(() -> publisher.publish(raw)); equal(context.resolver.writes, writes); equal(context.resolver.deletes, 0);
        pending.owner = context.getPackageName(); publisher.publish(raw); equal(pending.pending, 0);

        Context changing = new Context(root.resolve("changing").toFile()); RawPhotoDownload next = raw(changing, B, 18);
        changing.resolver.writeStarted = new CountDownLatch(1); changing.resolver.allowWrite = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { new RawPhotoPublisher(changing).publish(next); } catch (Throwable problem) { failed.set(problem); } });
        worker.start(); yes(changing.resolver.writeStarted.await(5, TimeUnit.SECONDS));
        Files.write(next.file().toPath(), nef(19)); changing.resolver.allowWrite.countDown(); worker.join(10000);
        yes(!worker.isAlive()); yes(failed.get() instanceof IOException); equal(row(changing).pending, 1); equal(changing.resolver.updates, 0);
        yes(Arrays.equals(Files.readAllBytes(next.file().toPath()), nef(19)));
        Files.write(next.file().toPath(), nef(18)); new RawPhotoPublisher(changing).publish(next);
        equal(changing.resolver.inserts, 1); equal(row(changing).pending, 0);
        yes(Arrays.equals(Files.readAllBytes(row(changing).file.toPath()), nef(18)));
    }

    private static void legacyPermissionScanAndRetryKeepOneNef(Path root) throws Exception {
        Build.VERSION.SDK_INT = 28; Context context = new Context(root.toFile()); Environment.setPublicPicturesForTest(root.resolve("public/Pictures").toFile());
        RawPhotoDownload raw = raw(context, A, 14); RawPhotoPublisher publisher = new RawPhotoPublisher(context);
        context.permissionGranted = false; fails(() -> publisher.publish(raw)); equal(context.scanRequests, 0); yes(!Files.exists(root.resolve("public")));
        context.permissionGranted = true; context.scanReturnsNull = true; fails(() -> publisher.publish(raw)); equal(context.scanRequests, 1);
        Path target;
        try (var files = Files.walk(root.resolve("public"))) { target = files.filter(path -> path.toString().endsWith(".NEF")).findFirst().orElseThrow(); }
        yes(Arrays.equals(Files.readAllBytes(target), nef(14)));
        context.scanReturnsNull = false; String uri = new RawPhotoPublisher(context).publish(raw);
        equal(new RawPhotoPublisher(context).publish(raw), uri); equal(context.scanRequests, 2); equal(context.resolver.rows.size(), 1);
        Files.write(target, nef(15)); fails(() -> publisher.publish(raw)); equal(context.resolver.rows.size(), 1);
        yes(Arrays.equals(Files.readAllBytes(target), nef(15))); yes(Arrays.equals(Files.readAllBytes(raw.file().toPath()), nef(14)));
        Build.VERSION.SDK_INT = 35;
    }

    private static void interruptedOperationsNeverCommit(Path root) throws Exception {
        RawPhotoStore store = new RawPhotoStore(root.resolve("camera-raw").toFile()); File part = part(store, nef(16));
        Thread.currentThread().interrupt();
        try { fails(() -> store.commit(A, part, "DSC.NEF")); yes(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        yes(store.find(A) == null); equal(store.list().size(), 0); yes(part.exists());
    }

    private static RawPhotoDownload raw(Context context, String key, int marker) throws Exception {
        File pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        RawPhotoStore store = new RawPhotoStore(new File(pictures, "camera-raw"));
        return store.commit(key, part(store, nef(marker)), "DSC_001.NEF");
    }
    private static File part(RawPhotoStore store, byte[] bytes) throws Exception { File file = store.createTemporary(); Files.write(file.toPath(), bytes); return file; }
    private static ContentResolver.Row row(Context context) { return context.resolver.rows.values().iterator().next(); }

    /** A bounded TIFF/EP NEF-shaped structural fixture, not a claim of decodable real camera sensor data. */
    private static byte[] nef(int marker) {
        ByteBuffer data = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        data.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8); data.putShort((short) 6);
        entry(data, 0x010f, 2, 6, 88); entry(data, 0x0100, 4, 1, 2); entry(data, 0x0101, 4, 1, 2);
        entry(data, 0x0106, 3, 1, 32803); entry(data, 0x0111, 4, 1, 100); entry(data, 0x0117, 4, 1, 28);
        data.putInt(0); data.position(88); data.put(new byte[] {'N', 'I', 'K', 'O', 'N', 0});
        data.position(100); for (int i = 0; i < 28; i++) data.put((byte) (marker + i)); return data.array();
    }
    private static void entry(ByteBuffer data, int tag, int type, int count, int value) { data.putShort((short) tag).putShort((short) type).putInt(count).putInt(value); }
    private static void yes(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    private static void equal(Object actual, Object expected) { checks++; if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("check " + checks + ": " + actual + " != " + expected); }
    private static void fails(Action action) throws Exception { checks++; try { action.run(); } catch (IOException | UnsupportedOperationException expected) { return; } throw new AssertionError("expected failure " + checks); }
    private interface Action { void run() throws Exception; }
}

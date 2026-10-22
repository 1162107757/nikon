package cc.huevia.album;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Pure Java persistence and I/O-failure checks; no Android or camera dependencies. */
public final class PhotoStarStoreTest {
    private static int checks;
    private static final String R1 = remote(1), R2 = remote(2), F1 = local(1), F2 = local(2);
    private interface Work { void run() throws Exception; }
    private static final class Record {
        final int flag; final List<String> keys;
        Record(int flag, String... keys) { this.flag = flag; this.keys = Arrays.asList(keys); }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("photo-stars-");
        try {
            persistenceAndSnapshotIsolation(root.resolve("normal"));
            bidirectionalAliasesAndMerging(root.resolve("aliases"));
            stableKeysNeverReadPhotoFiles(root.resolve("keys"));
            invalidInputsAndCorruptionAreExplicit(root.resolve("corruption"));
            replacementFailureRetainsBothSnapshots(root.resolve("atomic"));
            readsNeverWaitForDiskWriter(root.resolve("nonblocking"));
            concurrentInstancesDoNotLoseUpdates(root.resolve("threads"));
            keyLimitIsEnforcedBeforeCommit(root.resolve("limit"));
            System.out.println("PASS: " + checks + " photo-star store checks");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) throw new AssertionError("cleanup escaped test root");
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void persistenceAndSnapshotIsolation(Path directory) throws Exception {
        PhotoStarStore store = new PhotoStarStore(directory.toFile());
        yes(store.snapshot().isEmpty()); yes(!store.isStarred(R1));
        store.setStarred(R1, false); yes(!Files.exists(directory.resolve(PhotoStarStore.FILE_NAME)));
        store.setStarred(R1, true); Set<String> first = store.snapshot(); equal(first, Set.of(R1));
        rejects(() -> first.clear());
        byte[] original = Files.readAllBytes(directory.resolve(PhotoStarStore.FILE_NAME));
        store.setStarred(R1, true); yes(Arrays.equals(original, Files.readAllBytes(directory.resolve(PhotoStarStore.FILE_NAME))));
        equal(new PhotoStarStore(directory.toFile()).snapshot(), Set.of(R1));
        store.setStarred(F1, true); equal(first, Set.of(R1)); equal(store.snapshot(), Set.of(R1, F1));
        store.setStarred(R1, false); equal(store.snapshot(), Set.of(F1));
        store.setStarred(F1, false); equal(new PhotoStarStore(directory.toFile()).snapshot(), Set.of());
        yes(Files.isRegularFile(directory.resolve(PhotoStarStore.FILE_NAME)));
        // Reader methods keep the last known state even if external disk data disappears or becomes invalid.
        store.setStarred(R2, true); byte[] committed = Files.readAllBytes(directory.resolve(PhotoStarStore.FILE_NAME));
        Files.delete(directory.resolve(PhotoStarStore.FILE_NAME)); equal(store.snapshot(), Set.of(R2)); yes(store.isStarred(R2));
        fails(() -> store.setStarred(R1, true)); equal(store.snapshot(), Set.of(R2));
        Files.write(directory.resolve(PhotoStarStore.FILE_NAME), committed);
        Files.write(directory.resolve("photo-stars-abandoned.part"), new byte[] {1, 2, 3});
        equal(new PhotoStarStore(directory.toFile()).snapshot(), Set.of(R2));
    }

    private static void bidirectionalAliasesAndMerging(Path directory) throws Exception {
        PhotoStarStore store = new PhotoStarStore(directory.toFile());
        store.setStarred(R1, true); store.bindLocal(R1, F1);
        equal(store.snapshot(), Set.of(R1, F1)); yes(store.isStarred(F1));
        store.setStarred(F1, false); equal(store.snapshot(), Set.of());
        PhotoStarStore reopened = new PhotoStarStore(directory.toFile()); reopened.setStarred(F1, true);
        equal(reopened.snapshot(), Set.of(R1, F1)); reopened.setStarred(R1, false); equal(reopened.snapshot(), Set.of());
        reopened.setStarred(F2, true); reopened.bindLocal(R2, F2); equal(reopened.snapshot(), Set.of(R2, F2));
        reopened.bindLocal(R1, F2); equal(reopened.snapshot(), Set.of(R1, R2, F1, F2));
        byte[] before = Files.readAllBytes(directory.resolve(PhotoStarStore.FILE_NAME));
        reopened.bindLocal(R2, F1); yes(Arrays.equals(before, Files.readAllBytes(directory.resolve(PhotoStarStore.FILE_NAME))));
        reopened.setStarred(R2, false); equal(reopened.snapshot(), Set.of());
        PhotoStarStore finalStore = new PhotoStarStore(directory.toFile()); finalStore.setStarred(F1, true);
        equal(finalStore.snapshot(), Set.of(R1, R2, F1, F2));
        finalStore.setStarred(F2, false); equal(new PhotoStarStore(directory.toFile()).snapshot(), Set.of());
        Path second = directory.resolve("bound-before-star"); PhotoStarStore initiallyEmpty = new PhotoStarStore(second.toFile());
        initiallyEmpty.bindLocal(R1, F1); equal(initiallyEmpty.snapshot(), Set.of());
        new PhotoStarStore(second.toFile()).setStarred(R1, true);
        equal(new PhotoStarStore(second.toFile()).snapshot(), Set.of(R1, F1));
    }

    private static void stableKeysNeverReadPhotoFiles(Path root) throws Exception {
        String first = PhotoStarStore.localKey(root.resolve("one/unique__测试.JPG").toFile());
        String relocated = PhotoStarStore.localKey(root.resolve("two/unique__测试.JPG").toFile());
        equal(first, relocated); yes(first.matches("f:[0-9a-f]{64}"));
        yes(!first.equals(PhotoStarStore.localKey(root.resolve("one/other__测试.JPG").toFile())));
        yes(!first.equals(PhotoStarStore.localKey(root.resolve("one/unique__测试.jpg").toFile())));
        equal(PhotoStarStore.remoteKey("a".repeat(64)), "r:" + "a".repeat(64));
        rejects(() -> PhotoStarStore.remoteKey("A".repeat(64))); rejects(() -> PhotoStarStore.remoteKey("a".repeat(63)));
        rejects(() -> PhotoStarStore.remoteKey(null)); rejects(() -> PhotoStarStore.localKey(null));
        rejects(() -> PhotoStarStore.localKey(new File("."))); rejects(() -> PhotoStarStore.localKey(new File("bad\n.JPG")));
        yes(!Files.exists(root));
    }

    private static void invalidInputsAndCorruptionAreExplicit(Path root) throws Exception {
        PhotoStarStore store = new PhotoStarStore(root.toFile()); store.setStarred(R1, true);
        byte[] valid = Files.readAllBytes(root.resolve(PhotoStarStore.FILE_NAME));
        for (String invalid : Arrays.asList(null, "", "r:1", "x:" + "a".repeat(64), "f:" + "Z".repeat(64))) {
            rejects(() -> store.setStarred(invalid, true)); rejects(() -> store.isStarred(invalid));
        }
        rejects(() -> store.bindLocal(F1, R1)); rejects(() -> store.bindLocal(R1, R2));
        yes(Arrays.equals(valid, Files.readAllBytes(root.resolve(PhotoStarStore.FILE_NAME))));
        List<byte[]> corrupt = new ArrayList<>();
        for (int length : new int[] {0, 1, 7, 39, 43, valid.length - 1}) corrupt.add(Arrays.copyOf(valid, length));
        byte[] badMagic = valid.clone(); badMagic[0] ^= 1; corrupt.add(badMagic);
        byte[] badChecksum = valid.clone(); badChecksum[badChecksum.length - 1] ^= 1; corrupt.add(badChecksum);
        byte[] badLength = valid.clone(); badLength[7] ^= 1; corrupt.add(badLength);
        corrupt.add(record(new Record(2, R1))); corrupt.add(record(new Record(0, R1)));
        corrupt.add(record(new Record(1, R1, R1))); corrupt.add(record(new Record(1, R2), new Record(1, R1)));
        corrupt.add(record(new Record(1, F1, R1), new Record(1, F2, R1)));
        corrupt.add(record(new Record(1, R1, R2))); corrupt.add(record(new Record(1, F1, F2)));
        corrupt.add(record(new Record(1, "x:" + "1".repeat(64))));
        corrupt.add(framed(payload(data -> data.writeInt(-1)))); corrupt.add(framed(payload(data -> data.writeInt(100001))));
        corrupt.add(framed(payload(data -> { data.writeInt(1); data.writeByte(1); data.writeInt(100001); })));
        corrupt.add(framed(payload(data -> { data.writeInt(1); data.writeByte(1); data.writeInt(0); })));
        corrupt.add(framed(payload(data -> { data.writeInt(0); data.writeByte(1); })));
        int index = 0;
        for (byte[] bytes : corrupt) {
            Path dir = root.resolve("invalid-" + index++); Files.createDirectories(dir); Path file = dir.resolve(PhotoStarStore.FILE_NAME); Files.write(file, bytes);
            fails(() -> new PhotoStarStore(dir.toFile())); yes(Arrays.equals(bytes, Files.readAllBytes(file)));
        }
        Files.write(root.resolve(PhotoStarStore.FILE_NAME), badChecksum);
        fails(() -> store.setStarred(R2, true)); equal(store.snapshot(), Set.of(R1));
        yes(Arrays.equals(badChecksum, Files.readAllBytes(root.resolve(PhotoStarStore.FILE_NAME))));
        Files.write(root.resolve(PhotoStarStore.FILE_NAME), valid);
        Path nonFile = root.resolve("non-file"); Files.createDirectories(nonFile.resolve(PhotoStarStore.FILE_NAME));
        fails(() -> new PhotoStarStore(nonFile.toFile())); yes(Files.isDirectory(nonFile.resolve(PhotoStarStore.FILE_NAME)));
    }

    private static void replacementFailureRetainsBothSnapshots(Path directory) throws Exception {
        PhotoStarStore normal = new PhotoStarStore(directory.toFile()); normal.setStarred(R1, true);
        byte[] before = Files.readAllBytes(directory.resolve(PhotoStarStore.FILE_NAME));
        PhotoStarStore failing = new PhotoStarStore(directory.toFile(), (temporary, target) -> {
            yes(temporary.isFile() && temporary.length() >= 44); yes(Arrays.equals(before, Files.readAllBytes(target.toPath())));
            throw new IOException("synthetic atomic replacement failure");
        });
        Set<String> memory = failing.snapshot();
        for (Work operation : List.<Work>of(() -> failing.setStarred(R2, true), () -> failing.bindLocal(R1, F1), () -> failing.setStarred(R1, false))) {
            fails(operation); yes(failing.snapshot() == memory); equal(failing.snapshot(), Set.of(R1));
            yes(Arrays.equals(before, Files.readAllBytes(directory.resolve(PhotoStarStore.FILE_NAME))));
            equal(new PhotoStarStore(directory.toFile()).snapshot(), Set.of(R1));
            try (var files = Files.list(directory)) { yes(files.noneMatch(path -> path.getFileName().toString().endsWith(".part"))); }
        }
    }

    private static void readsNeverWaitForDiskWriter(Path directory) throws Exception {
        new PhotoStarStore(directory.toFile()).setStarred(R1, true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        PhotoStarStore store = new PhotoStarStore(directory.toFile(), (temporary, target) -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("writer gate timed out"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var write = pool.submit(() -> { store.setStarred(R2, true); return null; });
            yes(entered.await(5, TimeUnit.SECONDS));
            equal(pool.submit(store::snapshot).get(1, TimeUnit.SECONDS), Set.of(R1));
            yes(pool.submit(() -> store.isStarred(R1)).get(1, TimeUnit.SECONDS));
            release.countDown(); write.get(5, TimeUnit.SECONDS); equal(store.snapshot(), Set.of(R1, R2));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private static void concurrentInstancesDoNotLoseUpdates(Path directory) throws Exception {
        PhotoStarStore first = new PhotoStarStore(directory.toFile()), second = new PhotoStarStore(directory.toFile());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { for (int i = 10; i < 30; i++) first.setStarred(remote(i), true); return null; });
            var b = pool.submit(() -> { for (int i = 30; i < 50; i++) second.setStarred(remote(i), true); return null; });
            a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
            PhotoStarStore all = new PhotoStarStore(directory.toFile()); yes(all.snapshot().size() == 40);
            for (int i = 10; i < 50; i++) yes(all.isStarred(remote(i)));
            first.setStarred(remote(10), true); equal(first.snapshot(), all.snapshot());
        } finally { pool.shutdownNow(); }
    }

    private static void keyLimitIsEnforcedBeforeCommit(Path directory) throws Exception {
        Files.createDirectories(directory); byte[] full = framed(payload(data -> {
            data.writeInt(100000);
            for (int i = 0; i < 100000; i++) { data.writeByte(1); data.writeInt(1); data.write(remote(i).getBytes(StandardCharsets.US_ASCII)); }
        }));
        Files.write(directory.resolve(PhotoStarStore.FILE_NAME), full);
        PhotoStarStore store = new PhotoStarStore(directory.toFile()); yes(store.snapshot().size() == 100000);
        fails(() -> store.setStarred(remote(100000), true)); yes(store.snapshot().size() == 100000);
        yes(Arrays.equals(full, Files.readAllBytes(directory.resolve(PhotoStarStore.FILE_NAME))));
    }

    private interface Payload { void write(DataOutputStream data) throws Exception; }
    private static byte[] payload(Payload build) throws Exception { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (DataOutputStream data = new DataOutputStream(bytes)) { build.write(data); } return bytes.toByteArray(); }
    private static byte[] record(Record... records) throws Exception { return framed(payload(data -> {
        data.writeInt(records.length); for (Record record : records) { data.writeByte(record.flag); data.writeInt(record.keys.size()); for (String key : record.keys) data.write(key.getBytes(StandardCharsets.US_ASCII)); }
    })); }
    private static byte[] framed(byte[] payload) throws Exception { return payload(data -> { data.writeInt(0x50535431); data.writeInt(payload.length); data.write(payload); data.write(MessageDigest.getInstance("SHA-256").digest(payload)); }); }
    private static String remote(int i) { String number = Integer.toHexString(i); return "r:" + "0".repeat(64 - number.length()) + number; }
    private static String local(int i) { return "f:" + remote(i).substring(2); }
    private static void fails(Work work) throws Exception { try { work.run(); throw new AssertionError("Expected IOException"); } catch (IOException expected) { checks++; } }
    private static void rejects(Work work) throws Exception { try { work.run(); throw new AssertionError("Expected invalid argument or immutable result"); } catch (IllegalArgumentException | UnsupportedOperationException expected) { checks++; } }
    private static void yes(boolean value) { if (!value) throw new AssertionError("check " + checks + " failed"); checks++; }
    private static void equal(Object actual, Object expected) { if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual); checks++; }
}

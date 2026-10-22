package cc.huevia.album;

import cc.huevia.ptp.PtpObjectInfo;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Production metadata journal against synthetic local files; no controller/camera operations. */
public final class PhotoLiveReceiptStoreTest {
    private static final String P1 = "00000001-0000-0000-0000-000000000001", P2 = "00000002-0000-0000-0000-000000000002";
    private static final String CAMERA = "nikon:synthetic-device-123", OTHER_CAMERA = "nikon:other-device-456";
    private static int checks;
    private interface Work { void run() throws Exception; }
    private interface Payload { void write(DataOutputStream out) throws Exception; }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("photo-live-receipts-");
        try {
            entriesSurviveReopeningAndKeepTheirFirstTime(root.resolve("reopen"));
            bindingAndProjectsStaySeparate(root.resolve("projects"));
            invalidIdentityAndOriginalReferencesCannotCommit(root.resolve("identity"));
            damagedFilesCannotBeReadOrOverwritten(root.resolve("corruption"));
            replacementFailuresLeaveThePriorReceipt(root.resolve("atomic"));
            concurrentWorkersRetainEachOthersEntries(root.resolve("workers"));
            sharedOriginalCompletionsUseVerifiedBytesAndPreserveTheirName(root.resolve("shared"));
            System.out.println("PASS: " + checks + " photo-live receipt store checks");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) throw new AssertionError("cleanup escaped test root"); Files.deleteIfExists(path);
            } }
        }
    }

    private static void sharedOriginalCompletionsUseVerifiedBytesAndPreserveTheirName(Path root) throws Exception {
        Path records = root.resolve("records"), photos = root.resolve("photos"); Files.createDirectories(photos);
        PhotoOriginalStore originals = new PhotoOriginalStore(photos.toFile());
        Path temporary = Files.createTempFile(photos, "receive-", ".part"); Files.write(temporary, jpeg());
        DownloadedPhoto original = originals.commit("a".repeat(64), temporary.toFile(), "First_source.JPG");
        PhotoLiveReceiptStore store = new PhotoLiveReceiptStore(records.toFile(), photos.toFile());
        PhotoItem first = photo(41, "Camera_name.JPG"), second = photo(42, "Alias_name.JPG");
        store.write(P1, CAMERA, true, List.of(receipt(P1, first, "saved", original, "", 100, 200), receipt(P1, second, "saved", original, "", 101, 201)));
        List<PhotoLiveReceipt> entries = new PhotoLiveReceiptStore(records.toFile(), photos.toFile()).list(P1, "Project");
        equal(entries.size(), 2); yes(entries.stream().allMatch(item -> item.saved != null && item.saved.file().equals(original.file())));
        yes(entries.stream().allMatch(item -> item.saved.filename().equals("First_source.JPG")));
        byte[] changed = jpeg(); changed[10] ^= 1; Files.write(original.file().toPath(), changed);
        List<PhotoLiveReceipt> invalid = store.list(P1, "Project");
        yes(invalid.stream().allMatch(item -> item.saved == null && item.error.contains("变化")));
        yes(Arrays.equals(changed, Files.readAllBytes(original.file().toPath())));
        Files.write(original.file().toPath(), new byte[] {(byte) 255, (byte) 216, 4});
        yes(store.list(P1, "Project").stream().allMatch(item -> item.saved == null && !item.error.isEmpty()));
    }

    private static void entriesSurviveReopeningAndKeepTheirFirstTime(Path root) throws Exception {
        Path records = root.resolve("records"), photos = root.resolve("photos"); Files.createDirectories(photos);
        PhotoLiveReceiptStore store = new PhotoLiveReceiptStore(records.toFile(), photos.toFile());
        PhotoItem first = photo(41, "DSC_0041.JPG"), second = photo(42, "DSC_0042.JPG");
        PhotoLiveReceipt queued = receipt(P1, first, "queued", null, "", 100, 100);
        store.write(P1, CAMERA, true, List.of(queued));
        PhotoLiveReceipt receiving = receipt(P1, first, "receiving", null, "", 100, 200);
        store.write(P1, CAMERA, true, List.of(receiving, receipt(P1, second, "error", null, "读取暂时失败", 110, 180)));
        PhotoLiveReceiptStore reopened = new PhotoLiveReceiptStore(records.toFile(), photos.toFile());
        List<PhotoLiveReceipt> history = reopened.list(P1, "第一场拍摄"); equal(history.size(), 2);
        PhotoLiveReceipt restored = history.get(0); equal(restored.identity, queued.identity); equal(restored.createdAt, 100L); equal(restored.updatedAt, 200L);
        equal(restored.status, "receiving"); equal(restored.captureDate, first.captureDate()); equal(restored.byteSize, first.byteSize());
        PhotoLiveReceipt archived = restored.archived(); equal(archived.status, "stopped"); equal(archived.createdAt, 100L); equal(archived.updatedAt, 200L); equal(restored.status, "receiving");
        rejects(() -> history.clear());
        byte[] before = Files.readAllBytes(file(records, queued));
        store.write(P1, CAMERA, true, List.of(receipt(P1, first, "queued", null, "", 100, 150)));
        yes(Arrays.equals(before, Files.readAllBytes(file(records, queued))));
        fails(() -> store.write(P1, CAMERA, true, List.of(receipt(P1, first, "error", null, "时间被修改", 101, 300))));
        yes(Arrays.equals(before, Files.readAllBytes(file(records, queued))));
        DownloadedPhoto saved = saved(photos, queued.identity, first.filename()); byte[] original = Files.readAllBytes(saved.file().toPath());
        store.write(P1, CAMERA, true, List.of(receipt(P1, first, "saved", saved, "", 100, 400)));
        PhotoLiveReceipt completed = new PhotoLiveReceiptStore(records.toFile(), photos.toFile()).list(P1, "重命名后的项目").get(0);
        equal(completed.createdAt, 100L); equal(completed.updatedAt, 400L); equal(completed.status, "saved"); yes(completed.saved != null);
        equal(completed.saved.projectId(), P1); equal(completed.saved.projectName(), "重命名后的项目"); equal(completed.saved.filename(), first.filename());
        yes(Arrays.equals(original, Files.readAllBytes(saved.file().toPath())));
        Files.delete(saved.file().toPath()); PhotoLiveReceipt missing = store.list(P1, "项目").get(0);
        yes(missing.saved == null); yes(!missing.error.isEmpty()); equal(missing.status, "saved"); equal(missing.createdAt, 100L);
    }

    private static void bindingAndProjectsStaySeparate(Path root) throws Exception {
        Path records = root.resolve("records"), photos = root.resolve("photos"); Files.createDirectories(photos);
        PhotoLiveReceiptStore store = new PhotoLiveReceiptStore(records.toFile(), photos.toFile());
        yes(store.list(P1, "未开始项目").isEmpty());
        PhotoItem photo = photo(8, "DSC_0008.JPG"); store.write(P1, CAMERA, true, List.of(receipt(P1, photo, "error", null, "失败", 10, 20)));
        store.bind(P1, CAMERA, true); equal(store.binding(P1).camera, CAMERA); yes(store.binding(P1).trusted);
        byte[] camera = Files.readAllBytes(records.resolve(P1).resolve("camera"));
        fails(() -> store.bind(P1, OTHER_CAMERA, true)); fails(() -> store.bind(P1, CAMERA, false));
        yes(Arrays.equals(camera, Files.readAllBytes(records.resolve(P1).resolve("camera"))));
        PhotoLiveReceipt other = new PhotoLiveReceipt(P2, PhotoLiveLedger.identity(OTHER_CAMERA, photo.handle(), photo.objectInfo()), "queued", photo, null, "", 30, 30);
        store.write(P2, OTHER_CAMERA, false, List.of(other)); equal(store.list(P2, "另一个项目").size(), 1); equal(store.list(P1, "原项目").size(), 1);
        equal(store.binding(P2).camera, OTHER_CAMERA); yes(!store.binding(P2).trusted);
        fails(() -> store.write(P1, CAMERA, true, List.of(other))); equal(store.list(P1, "原项目").get(0).createdAt, 10L);
        fails(() -> store.bind("../outside", CAMERA, true)); fails(() -> store.bind(P1, "", true));
        yes(!Files.exists(root.resolve("outside")));
    }

    private static void invalidIdentityAndOriginalReferencesCannotCommit(Path root) throws Exception {
        Path records = root.resolve("records"), photos = root.resolve("photos"); Files.createDirectories(photos);
        PhotoLiveReceiptStore store = new PhotoLiveReceiptStore(records.toFile(), photos.toFile()); PhotoItem photo = photo(12, "DSC_0012.JPG");
        PhotoLiveReceipt valid = receipt(P1, photo, "error", null, "已保留的错误", 10, 20); store.write(P1, CAMERA, true, List.of(valid));
        byte[] before = Files.readAllBytes(file(records, valid));
        for (String identity : new String[] {"a".repeat(64), "A".repeat(64), "short", null}) {
            PhotoLiveReceipt invalid = new PhotoLiveReceipt(P1, identity, "error", photo, null, "坏标识", 10, 30);
            fails(() -> store.write(P1, CAMERA, true, List.of(invalid)));
        }
        for (long handle : new long[] {0, 0xffffffffL}) {
            PhotoItem invalid = photo(handle, "INVALID.JPG"); fails(() -> store.write(P1, CAMERA, true, List.of(receipt(P1, invalid, "queued", null, "", 10, 30))));
        }
        PhotoItem changed = photo(photo.handle(), "CHANGED.JPG");
        fails(() -> store.write(P1, CAMERA, true, List.of(new PhotoLiveReceipt(P1, valid.identity, "queued", changed, null, "", 10, 30))));
        fails(() -> store.write(P1, CAMERA, true, List.of(receipt(P1, photo, "unknown", null, "", 10, 30))));
        fails(() -> store.write(P1, CAMERA, true, List.of(receipt(P1, photo, "error", null, "坏时间", -1, 30))));
        fails(() -> store.write(P1, CAMERA, true, List.of(receipt(P1, photo, "error", null, "时间倒退", 10, 9))));
        DownloadedPhoto foreign = saved(root.resolve("outside"), valid.identity, photo.filename());
        fails(() -> store.write(P1, CAMERA, true, List.of(receipt(P1, photo, "saved", foreign, "", 10, 30))));
        Path wrong = photos.resolve("wrong-prefix.JPG"); Files.write(wrong, jpeg());
        fails(() -> store.write(P1, CAMERA, true, List.of(receipt(P1, photo, "saved", new DownloadedPhoto(wrong.toFile(), photo.filename()), "", 10, 30))));
        yes(Arrays.equals(before, Files.readAllBytes(file(records, valid)))); equal(store.list(P1, "项目").size(), 1);
    }

    private static void damagedFilesCannotBeReadOrOverwritten(Path root) throws Exception {
        Path records = root.resolve("records"), photos = root.resolve("photos"); Files.createDirectories(photos);
        PhotoLiveReceiptStore store = new PhotoLiveReceiptStore(records.toFile(), photos.toFile()); PhotoItem photo = photo(4, "DSC_0004.JPG");
        PhotoLiveReceipt receipt = receipt(P1, photo, "error", null, "原错误", 5, 10); store.write(P1, CAMERA, true, List.of(receipt));
        Path file = file(records, receipt); byte[] valid = Files.readAllBytes(file); List<byte[]> invalid = new ArrayList<>();
        for (int length : new int[] {0, 10, 31, valid.length - 1}) invalid.add(Arrays.copyOf(valid, length));
        byte[] badHash = valid.clone(); badHash[badHash.length - 1] ^= 1; invalid.add(badHash);
        invalid.add(encoded(receipt, "another-project", receipt.identity, receipt.status, receipt.createdAt, receipt.updatedAt, false));
        invalid.add(encoded(receipt, P1, "a".repeat(64), receipt.status, receipt.createdAt, receipt.updatedAt, false));
        invalid.add(encoded(receipt, P1, receipt.identity, "unknown", receipt.createdAt, receipt.updatedAt, false));
        invalid.add(encoded(receipt, P1, receipt.identity, receipt.status, -1, receipt.updatedAt, false));
        invalid.add(encoded(receipt, P1, receipt.identity, receipt.status, receipt.createdAt, receipt.updatedAt, true));
        for (byte[] damaged : invalid) {
            Files.write(file, damaged); fails(() -> store.list(P1, "项目"));
            fails(() -> store.write(P1, CAMERA, true, List.of(receipt(P1, photo, "queued", null, "", 5, 100))));
            yes(Arrays.equals(damaged, Files.readAllBytes(file)));
        }
        Files.write(file, valid); Path cameraFile = records.resolve(P1).resolve("camera"); byte[] camera = Files.readAllBytes(cameraFile), damaged = camera.clone(); damaged[damaged.length - 1] ^= 1;
        Files.write(cameraFile, damaged); fails(() -> store.binding(P1)); fails(() -> store.list(P1, "项目"));
        fails(() -> store.write(P1, CAMERA, true, List.of(receipt(P1, photo, "queued", null, "", 5, 100))));
        yes(Arrays.equals(damaged, Files.readAllBytes(cameraFile))); yes(Arrays.equals(valid, Files.readAllBytes(file)));
        Files.write(cameraFile, camera);
        Path foreignName = file.resolveSibling("unexpected.receipt"); Files.move(file, foreignName); fails(() -> store.list(P1, "项目"));
        yes(Files.exists(foreignName));
    }

    private static void replacementFailuresLeaveThePriorReceipt(Path root) throws Exception {
        Path records = root.resolve("records"), photos = root.resolve("photos"); Files.createDirectories(photos);
        PhotoLiveReceiptStore normal = new PhotoLiveReceiptStore(records.toFile(), photos.toFile()); PhotoItem photo = photo(8, "DSC_0008.JPG");
        PhotoLiveReceipt original = receipt(P1, photo, "error", null, "原错误", 10, 20); normal.write(P1, CAMERA, true, List.of(original));
        byte[] committed = Files.readAllBytes(file(records, original));
        PhotoLiveReceiptStore failing = new PhotoLiveReceiptStore(records.toFile(), photos.toFile(), (source, target) -> {
            yes(source.isFile() && source.length() > 32); yes(Arrays.equals(committed, Files.readAllBytes(target.toPath()))); throw new IOException("synthetic atomic replacement failure");
        });
        fails(() -> failing.write(P1, CAMERA, true, List.of(receipt(P1, photo, "receiving", null, "", 10, 30))));
        yes(Arrays.equals(committed, Files.readAllBytes(file(records, original))));
        PhotoLiveReceipt reopened = new PhotoLiveReceiptStore(records.toFile(), photos.toFile()).list(P1, "项目").get(0);
        equal(reopened.status, "error"); equal(reopened.error, "原错误"); equal(reopened.createdAt, 10L); equal(reopened.updatedAt, 20L);
        try (var files = Files.list(records.resolve(P1))) { yes(files.noneMatch(path -> path.getFileName().toString().startsWith("receipt-")
                && (path.getFileName().toString().endsWith(".part") || path.getFileName().toString().endsWith(".tmp")))); }
    }

    private static void concurrentWorkersRetainEachOthersEntries(Path root) throws Exception {
        Path records = root.resolve("records"), photos = root.resolve("photos"); Files.createDirectories(photos);
        PhotoLiveReceiptStore first = new PhotoLiveReceiptStore(records.toFile(), photos.toFile()), second = new PhotoLiveReceiptStore(records.toFile(), photos.toFile());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { for (int i = 1; i <= 10; i++) first.write(P1, CAMERA, true, List.of(receipt(P1, photo(i, "FIRST" + i + ".JPG"), "error", null, "失败", i, i))); return null; });
            var b = pool.submit(() -> { for (int i = 11; i <= 20; i++) second.write(P1, CAMERA, true, List.of(receipt(P1, photo(i, "SECOND" + i + ".JPG"), "queued", null, "", i, i))); return null; });
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS); List<PhotoLiveReceipt> all = new PhotoLiveReceiptStore(records.toFile(), photos.toFile()).list(P1, "项目");
            equal(all.size(), 20); equal(all.get(0).updatedAt, 20L); equal(all.get(19).updatedAt, 1L);
        } finally { pool.shutdownNow(); }
    }

    private static PhotoItem photo(long handle, String name) { return new PhotoItem("synthetic-session", handle, new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801).compressedSize(32).imagePixWidth(6000).imagePixHeight(4000).filename(name).captureDate("20261017T140000").build()); }
    private static PhotoLiveReceipt receipt(String project, PhotoItem photo, String status, DownloadedPhoto saved, String error, long created, long updated) { return new PhotoLiveReceipt(project, PhotoLiveLedger.identity(CAMERA, photo.handle(), photo.objectInfo()), status, photo, saved, error, created, updated); }
    private static Path file(Path records, PhotoLiveReceipt receipt) { return records.resolve(receipt.projectId).resolve(receipt.identity + ".receipt"); }
    private static DownloadedPhoto saved(Path directory, String identity, String name) throws Exception { Files.createDirectories(directory); Path file = directory.resolve("live-" + identity + "__" + name); Files.write(file, jpeg()); return new DownloadedPhoto(file.toFile(), name); }
    private static byte[] jpeg() { byte[] bytes = new byte[32]; Arrays.fill(bytes, (byte) 7); bytes[0] = (byte) 0xff; bytes[1] = (byte) 0xd8; bytes[30] = (byte) 0xff; bytes[31] = (byte) 0xd9; return bytes; }
    private static byte[] encoded(PhotoLiveReceipt receipt, String project, String identity, String status, long created, long updated, boolean extra) throws Exception {
        return payload(out -> {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (DataOutputStream data = new DataOutputStream(bytes)) {
                byte[] info = receipt.photo.objectInfo().toBytes(); data.writeInt(0x504c5231); data.writeUTF(project); data.writeUTF(identity); data.writeLong(receipt.photo.handle()); data.writeInt(info.length); data.write(info);
                data.writeUTF(status); data.writeLong(updated); data.writeLong(created); data.writeUTF(receipt.error); data.writeUTF(""); if (extra) data.writeByte(1);
            }
            out.write(bytes.toByteArray()); out.write(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        });
    }
    private static byte[] payload(Payload build) throws Exception { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (DataOutputStream out = new DataOutputStream(bytes)) { build.write(out); } return bytes.toByteArray(); }
    private static void fails(Work work) throws Exception { try { work.run(); throw new AssertionError("Expected IOException"); } catch (IOException expected) { checks++; } }
    private static void rejects(Work work) throws Exception { try { work.run(); throw new AssertionError("Expected immutable history"); } catch (UnsupportedOperationException expected) { checks++; } }
    private static void yes(boolean value) { if (!value) throw new AssertionError("check " + checks + " failed"); checks++; }
    private static void equal(Object actual, Object expected) { if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual); checks++; }
}

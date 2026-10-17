package com.nikonpreset.usbprobe.album;

import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** File-level regression coverage for completed previews, session identity and active-file lifetime. */
public final class PhotoPreviewCacheTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("photo-preview-cache-test-");
        try {
            onlyCompletedJpegsArePublished(root.resolve("validation"));
            thumbnailLengthIsIndependentOfOriginal(root.resolve("thumbnail-size"));
            largePreviewSourceSurvivesPinAndCache(root.resolve("large-source"));
            cameraSessionAndMetadataMustMatch(root.resolve("identity"));
            leastRecentlyUsedUnpinnedPhotoIsEvicted(root.resolve("lru"));
            byteLimitAndActiveReaderProtection(root.resolve("limits"));
            corruptEntryIsDiscardedAndCloseWaitsForReader(root.resolve("lifetime"));
            staleFilesAreCleanedWithoutTouchingOtherFiles(root.resolve("cleanup"));
            anotherControllerCannotCleanActiveFiles(root.resolve("shared"));
            System.out.println("PASS: " + checks + " photo preview cache checks");
        } finally {
            // This directory was allocated by this test and every deletion stays below it.
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    if (!file.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
                        throw new AssertionError("cleanup escaped test directory");
                    }
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static void onlyCompletedJpegsArePublished(Path directory) throws Exception {
        try (PhotoPreviewCache cache = new PhotoPreviewCache(directory.toFile(), 2, 100)) {
            PhotoItem photo = photo("a", 1, 12);
            check(cache.acquire(photo) == null, "A cache miss never exposes a file");
            File incomplete = cache.createTemporary();
            Files.write(incomplete.toPath(), new byte[] {(byte) 0xff, (byte) 0xd8, 0});
            check(cache.acquire(photo) == null, "An in-progress receive remains invisible");
            reject(() -> cache.publish(photo, incomplete), "Truncated receive rejected");
            check(cache.acquire(photo) == null, "Failed receives never become visible");
            Files.write(incomplete.toPath(), new byte[12]);
            reject(() -> cache.publish(photo, incomplete), "Non-JPEG receive rejected");
            byte[] noEndMarker = jpeg(12); noEndMarker[11] = 0;
            Files.write(incomplete.toPath(), noEndMarker);
            reject(() -> cache.publish(photo, incomplete), "Missing JPEG end marker rejected");
            Files.write(incomplete.toPath(), jpeg(12));
            try (PhotoPreview preview = cache.publish(photo, incomplete)) {
                check(preview.file().exists(), "Published preview is a completed file");
                check(preview.file().getName().endsWith(".jpg"), "Completed file uses JPEG suffix");
                check(!incomplete.exists(), "Publication consumes the part file");
                check(preview.byteSize() == 12 && preview.filename().equals("DSC_1.JPG"), "Metadata preserved");
            }
            File outside = Files.createTempFile(directory.getParent(), "outside-", ".part").toFile();
            try {
                Files.write(outside.toPath(), jpeg(12));
                reject(() -> cache.publish(photo, outside), "Foreign temporary files rejected");
                check(outside.exists(), "Rejected foreign file was not deleted");
            } finally { outside.delete(); }
        }
    }

    private static void cameraSessionAndMetadataMustMatch(Path directory) throws Exception {
        try (PhotoPreviewCache cache = new PhotoPreviewCache(directory.toFile(), 2, 100)) {
            PhotoItem original = photo("old-session", 7, 12);
            try (PhotoPreview ignored = put(cache, original)) { }
            check(cache.acquire(photo("new-session", 7, 12)) == null, "Reused camera handle from new session is a miss");
            check(cache.acquire(photo("old-session", 8, 12)) == null, "Different handle is a miss");
            check(cache.acquire(photo("old-session", 7, 16)) == null, "Changed object size is a miss");
            PhotoItem otherStorage = new PhotoItem("old-session", 7, new PtpObjectInfo.Builder()
                    .storageId(2).objectFormat(0x3801).compressedSize(12).filename("DSC_7.JPG").build());
            check(cache.acquire(otherStorage) == null, "Different storage is a miss");
            try (PhotoPreview same = cache.acquire(photo("old-session", 7, 12))) {
                check(same != null && same.file().exists(), "Matching immutable metadata can reuse cache offline");
            }
            reject(() -> cache.acquire(null), "Null photo rejected");
            reject(() -> cache.acquire(photo("", 1, 12)), "Missing session rejected");
            reject(() -> cache.acquire(photo("old-session", 0, 12)), "Invalid handle rejected");
            check(cache.acquire(photo("old-session", 1, 0)) == null, "Unknown original size remains a valid cache miss");
        }
    }

    private static void largePreviewSourceSurvivesPinAndCache(Path directory) throws Exception {
        try (PhotoPreviewCache cache = new PhotoPreviewCache(directory.toFile(), 2, 100)) {
            PhotoItem photo = photo("large-source", 42, 1000);
            File file = cache.createTemporary(); Files.write(file.toPath(), jpeg(40));
            try (PhotoPreview first = cache.publish(photo, file, true);
                    PhotoPreview again = cache.acquire(photo)) {
                check(first.isLargeThumbnail() && again.isLargeThumbnail(), "Large preview provenance survives cache acquisition");
                check(first.byteSize() == 40 && again.file().equals(first.file()), "Large preview remains bounded and pinned independently of original");
            }
            try (PhotoPreview small = put(cache, photo("small-source", 43, 12))) {
                check(!small.isLargeThumbnail(), "Legacy GetThumb cache entries do not claim large-preview provenance");
            }
        }
    }

    private static void thumbnailLengthIsIndependentOfOriginal(Path directory) throws Exception {
        try (PhotoPreviewCache cache = new PhotoPreviewCache(directory.toFile(), 2, 100)) {
            PhotoItem large = new PhotoItem("a", 1, new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801)
                    .compressedSize(100L * 1024 * 1024).thumbCompressedSize(10)
                    .thumbPixWidth(320).thumbPixHeight(200).filename("large.JPG").build());
            File file = cache.createTemporary();
            Files.write(file.toPath(), jpeg(12));
            try (PhotoPreview preview = cache.publish(large, file)) {
                check(preview.byteSize() == 12, "Cache records framed thumbnail size, independent of original and advertised thumb lengths");
                check(large.thumbnailByteSize() == 10 && large.thumbnailWidth() == 320 && large.thumbnailHeight() == 200,
                        "Reported thumbnail metadata is available separately from original metadata");
            }
            try (PhotoPreview cached = cache.acquire(large)) {
                check(cached != null && cached.byteSize() == 12, "Large originals can reuse a small complete thumbnail");
            }
            PhotoItem changedThumbnail = new PhotoItem("a", 1, new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801)
                    .compressedSize(100L * 1024 * 1024).thumbCompressedSize(16)
                    .thumbPixWidth(640).thumbPixHeight(400).filename("large.JPG").build());
            check(cache.acquire(changedThumbnail) == null, "Changed thumbnail metadata cannot reuse a prior entry");
            PhotoItem unknownSize = photo("a", 2, 0);
            File unknown = cache.createTemporary(); Files.write(unknown.toPath(), jpeg(8));
            try (PhotoPreview preview = cache.publish(unknownSize, unknown)) {
                check(preview.byteSize() == 8, "Unknown original size does not block a complete framed thumbnail");
            }
        }
    }

    private static void leastRecentlyUsedUnpinnedPhotoIsEvicted(Path directory) throws Exception {
        try (PhotoPreviewCache cache = new PhotoPreviewCache(directory.toFile(), 2, 100)) {
            PhotoItem a = photo("a", 1, 12), b = photo("a", 2, 12), c = photo("a", 3, 12);
            File aFile, bFile;
            try (PhotoPreview preview = put(cache, a)) { aFile = preview.file(); }
            try (PhotoPreview preview = put(cache, b)) { bFile = preview.file(); }
            try (PhotoPreview touchA = cache.acquire(a)) { check(touchA != null, "Read touches recency"); }
            try (PhotoPreview ignored = put(cache, c)) { }
            check(aFile.exists() && !bFile.exists(), "Oldest unused entry removed from disk");
            check(cache.acquire(b) == null, "Evicted metadata no longer hits");
            check(completedCount(directory) == 2, "File count stays bounded");
        }
    }

    private static void byteLimitAndActiveReaderProtection(Path directory) throws Exception {
        try (PhotoPreviewCache cache = new PhotoPreviewCache(directory.toFile(), 2, 24)) {
            PhotoItem a = photo("a", 1, 12), b = photo("a", 2, 12), c = photo("a", 3, 16);
            PhotoPreview activeA = put(cache, a);
            PhotoPreview activeB = put(cache, b);
            File pending = cache.createTemporary(); Files.write(pending.toPath(), jpeg(16));
            reject(() -> cache.publish(c, pending), "Pinned readers cannot be evicted to make room");
            check(activeA.file().exists() && activeB.file().exists(), "Both displayed files survive failed insertion");
            activeA.close(); activeA.close();
            activeB.close();
            try (PhotoPreview ready = cache.publish(c, pending)) {
                check(ready.byteSize() == 16, "Released entries can be evicted");
                check(completedCount(directory) == 1, "Byte limit can evict more than one entry");
                check(!activeA.file().exists() && !activeB.file().exists(), "Unused files removed");
            }
            File tooLarge = cache.createTemporary(); Files.write(tooLarge.toPath(), jpeg(28));
            reject(() -> cache.publish(photo("a", 9, 28), tooLarge), "Single over-limit entry rejected");
            cache.discardTemporary(tooLarge);
        }
    }

    private static void corruptEntryIsDiscardedAndCloseWaitsForReader(Path directory) throws Exception {
        PhotoPreviewCache cache = new PhotoPreviewCache(directory.toFile(), 2, 100);
        PhotoItem photo = photo("a", 1, 12);
        PhotoPreview first = put(cache, photo);
        File invalid = first.file(); first.close();
        Files.write(invalid.toPath(), new byte[12]);
        check(cache.acquire(photo) == null && !invalid.exists(), "Corrupted completed cache is revalidated and discarded");
        PhotoPreview displayed = put(cache, photo);
        PhotoPreview secondReader = cache.acquire(photo);
        File held = displayed.file();
        cache.close();
        check(held.exists(), "Controller close preserves a file still displayed");
        displayed.close(); displayed.close();
        check(held.exists(), "Repeated close does not release another reader");
        secondReader.close();
        check(!held.exists(), "Final reader releases retired file");
        reject(cache::createTemporary, "Closed cache cannot create new receives");
    }

    private static void staleFilesAreCleanedWithoutTouchingOtherFiles(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path part = directory.resolve("preview-old.part"), complete = directory.resolve("preview-old.jpg");
        Path unrelated = directory.resolve("notes.txt");
        Files.write(part, jpeg(12)); Files.write(complete, jpeg(12)); Files.writeString(unrelated, "keep");
        try (PhotoPreviewCache cache = new PhotoPreviewCache(directory.toFile(), 2, 100)) {
            File pending = cache.createTemporary();
            check(!Files.exists(part) && !Files.exists(complete), "Previous process cache is cleaned");
            check(Files.exists(unrelated), "Unrelated files remain untouched");
            cache.discardTemporary(pending);
        }
    }

    private static void anotherControllerCannotCleanActiveFiles(Path directory) throws Exception {
        PhotoPreviewCache first = new PhotoPreviewCache(directory.toFile(), 2, 100);
        PhotoPreview visible = put(first, photo("first", 1, 12));
        File receiving = first.createTemporary();
        Files.write(receiving.toPath(), jpeg(12));
        try (PhotoPreviewCache second = new PhotoPreviewCache(directory.toFile(), 2, 100)) {
            check(second.acquire(photo("second", 1, 12)) == null, "A new controller has no metadata for another session");
            check(visible.file().exists(), "New controller initialization preserves a displayed file");
            check(receiving.exists(), "New controller initialization preserves an in-flight receive");
            try (PhotoPreview own = put(second, photo("second", 1, 12))) {
                check(!own.file().equals(visible.file()), "Controllers publish distinct file paths");
            }
        }
        check(visible.file().exists(), "Closing another controller does not delete a displayed file");
        first.close();
        check(visible.file().exists(), "Closing owning controller defers deletion until viewer releases");
        first.discardTemporary(receiving);
        visible.close();
        check(!receiving.exists() && !visible.file().exists(), "All owning references release their files");
    }

    private static PhotoPreview put(PhotoPreviewCache cache, PhotoItem photo) throws IOException {
        File file = cache.createTemporary();
        Files.write(file.toPath(), jpeg((int) photo.byteSize()));
        return cache.publish(photo, file);
    }
    private static PhotoItem photo(String session, long handle, long size) {
        return new PhotoItem(session, handle, new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801)
                .compressedSize(size).filename("DSC_" + handle + ".JPG").build());
    }
    private static byte[] jpeg(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0xff; bytes[1] = (byte) 0xd8;
        bytes[size - 2] = (byte) 0xff; bytes[size - 1] = (byte) 0xd9;
        return bytes;
    }
    private static long completedCount(Path directory) throws IOException {
        try (var files = Files.list(directory)) { return files.filter(path -> path.toString().endsWith(".jpg")).count(); }
    }
    private interface Throwing { void run() throws Exception; }
    private static void reject(Throwing work, String message) throws Exception {
        try { work.run(); throw new AssertionError(message); }
        catch (IOException expected) { check(expected.getMessage() != null, message); }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}

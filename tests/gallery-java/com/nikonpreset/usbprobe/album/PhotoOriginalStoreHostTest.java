package com.nikonpreset.usbprobe.album;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Exercises real byte identity, durable completion and crash recovery without camera or provider doubles. */
public final class PhotoOriginalStoreHostTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("photo-original-index-");
        try {
            repeatedAndCrossEntryReuse(root.resolve("reuse"));
            oldAliasesAreRetainedAndEffectsAreNotOriginals(root.resolve("legacy"));
            contentAndRecordChangesAreNeverOverwritten(root.resolve("changes"));
            renameJournalGapAndExplicitMissingCopyRecover(root.resolve("recovery"));
            legacyCameraAliasesCannotRebindTheCanonicalOriginal(root.resolve("migration"));
            System.out.println("PASS: " + checks + " original byte identity checks");
        } finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
    private static void repeatedAndCrossEntryReuse(Path root) throws Exception {
        Files.createDirectories(root); PhotoOriginalStore store = new PhotoOriginalStore(root.toFile()); byte[] jpeg = jpeg(7);
        DownloadedPhoto first = store.commit("a".repeat(64), temporary(root, jpeg), "DSC_0123.JPG"); long date = first.file().lastModified();
        check(Arrays.equals(jpeg, Files.readAllBytes(first.file().toPath())), "Original bytes including EXIF payload are untouched");
        DownloadedPhoto same = new PhotoOriginalStore(root.toFile()).find("a".repeat(64), "renamed.JPG", jpeg.length);
        check(first.file().equals(same.file()) && same.filename().equals("DSC_0123.JPG"), "Cold lookup preserves the first immutable original and display name");
        DownloadedPhoto across = store.commit("b".repeat(64), temporary(root, jpeg), "another-event.JPG");
        check(across.file().equals(first.file()) && originals(root) == 1 && date == first.file().lastModified(), "Cross-entry identity aliases reuse exact bytes without duplicating or rewriting a file");
        check(store.find("b".repeat(64), "another-event.JPG", jpeg.length).file().equals(first.file()), "Both camera identities remain durable aliases");
        DownloadedPhoto distinct = store.commit("c".repeat(64), temporary(root, jpeg(9)), "DSC_0123.JPG");
        check(!distinct.file().equals(first.file()) && originals(root) == 2, "Identical filenames with different bytes remain distinct photos");
    }
    private static void oldAliasesAreRetainedAndEffectsAreNotOriginals(Path root) throws Exception {
        Files.createDirectories(root); byte[] jpeg = jpeg(18); Path old = root.resolve("1700000000-legacy__DSC_A.JPG"), effect = root.resolve("effect-" + "d".repeat(64) + "__Effect.JPG");
        Files.write(old, jpeg); Files.write(effect, jpeg); long date = old.toFile().lastModified();
        PhotoOriginalStore store = new PhotoOriginalStore(root.toFile()); DownloadedPhoto reused = store.commit("e".repeat(64), temporary(root, jpeg), "DSC_A.JPG");
        check(reused.file().equals(old.toFile()) && date == old.toFile().lastModified() && Files.isRegularFile(effect), "Legacy originals and effects stay at their existing paths");
        Files.delete(old); DownloadedPhoto newOriginal = store.commit("f".repeat(64), temporary(root, jpeg), "DSC_A.JPG");
        check(!newOriginal.file().equals(effect.toFile()) && newOriginal.file().isFile(), "An effect with byte-identical contents is never selected as an original");
    }
    private static void contentAndRecordChangesAreNeverOverwritten(Path root) throws Exception {
        Files.createDirectories(root); PhotoOriginalStore store = new PhotoOriginalStore(root.toFile());
        DownloadedPhoto original = store.commit("1".repeat(64), temporary(root, jpeg(21)), "safe.JPG");
        byte[] changed = jpeg(23); Files.write(original.file().toPath(), changed);
        fail(() -> store.find("1".repeat(64), "safe.JPG", changed.length));
        check(Arrays.equals(changed, Files.readAllBytes(original.file().toPath())), "Changed bytes remain untouched after a rejected completion lookup");
        Files.write(root.resolve(".photo-originals/camera-" + "1".repeat(64) + ".record"), new byte[] {1, 2});
        fail(() -> store.find("1".repeat(64), "safe.JPG", changed.length));
        File invalid = temporary(root, new byte[] {(byte) 255, (byte) 216, 2}); fail(() -> store.commit("2".repeat(64), invalid, "bad.JPG"));
        check(originals(root) == 1, "Damaged journals and incomplete JPEGs do not generate another completed original");
    }
    private static void renameJournalGapAndExplicitMissingCopyRecover(Path root) throws Exception {
        Files.createDirectories(root); PhotoOriginalStore store = new PhotoOriginalStore(root.toFile()); byte[] jpeg = jpeg(31);
        DownloadedPhoto original = store.commit("3".repeat(64), temporary(root, jpeg), "crash.JPG");
        try (var paths = Files.list(root.resolve(".photo-originals"))) { for (Path path : paths.toList()) Files.delete(path); }
        DownloadedPhoto recovered = new PhotoOriginalStore(root.toFile()).commit("3".repeat(64), temporary(root, jpeg), "crash.JPG");
        check(recovered.file().equals(original.file()) && originals(root) == 1, "A completed rename with missing journals is recovered by verified content");
        Files.delete(recovered.file().toPath()); check(store.find("3".repeat(64), "crash.JPG", jpeg.length) == null, "An intentionally removed original is available for an explicit re-download");
        DownloadedPhoto restored = store.commit("3".repeat(64), temporary(root, jpeg), "crash.JPG");
        check(restored.file().equals(original.file()) && originals(root) == 1, "A missing app original is restored once without stale completion shortcuts");
    }
    private static void legacyCameraAliasesCannotRebindTheCanonicalOriginal(Path root) throws Exception {
        Files.createDirectories(root); byte[] jpeg = jpeg(43); PhotoLiveLedger ledger = new PhotoLiveLedger(root.toFile());
        File first = ledger.destination("4".repeat(64), "First.JPG"), second = ledger.destination("5".repeat(64), "Second.JPG");
        Files.write(first.toPath(), jpeg); Files.write(second.toPath(), jpeg); ledger.complete("4".repeat(64), first, jpeg.length); ledger.complete("5".repeat(64), second, jpeg.length);
        PhotoOriginalStore store = new PhotoOriginalStore(root.toFile());
        check(store.find("4".repeat(64), "First.JPG", jpeg.length).file().equals(first), "The first legacy camera completion retains its original path");
        check(store.find("5".repeat(64), "Second.JPG", jpeg.length).file().equals(second), "Another legacy alias keeps its own project-safe path");
        DownloadedPhoto future = store.commit("6".repeat(64), temporary(root, jpeg), "Future.JPG");
        check(future.file().equals(first) && first.isFile() && second.isFile(), "Reading another legacy identity never rebinds the established canonical content target or removes aliases");
    }
    private static File temporary(Path root, byte[] bytes) throws IOException { Path file = Files.createTempFile(root, "receiving-", ".part"); Files.write(file, bytes); return file.toFile(); }
    private static byte[] jpeg(int seed) { return new byte[] {(byte) 255, (byte) 216, (byte) 255, (byte) 225, 0, 10, 'E', 'x', 'i', 'f', 0, (byte) seed, 1, 2, (byte) 255, (byte) 217}; }
    private static long originals(Path root) throws IOException { try (var files = Files.list(root)) { return files.filter(path -> path.toString().endsWith(".JPG") && !path.getFileName().toString().startsWith("effect-")).count(); } }
    private interface Action { void run() throws Exception; }
    private static void fail(Action action) throws Exception { try { action.run(); throw new AssertionError("expected rejection"); } catch (IOException expected) { checks++; } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}

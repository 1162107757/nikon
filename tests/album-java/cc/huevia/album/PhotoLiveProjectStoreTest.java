package cc.huevia.album;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Pure Java project catalog checks. Does not connect a camera or touch Android MediaStore. */
public final class PhotoLiveProjectStoreTest {
    private static int checks;
    private static final byte[] JPEG = {(byte) 255, (byte) 216, 10, 20, 30, 40, (byte) 255, (byte) 217};
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("photo-live-project-test-");
        try {
            classificationSurvivesRecreation(root.resolve("normal"));
            allProjectsIncludingEmptyOnesSurviveRepeatedRecreation(root.resolve("all-projects"));
            renameKeepsIdentityOrderEntriesAndOriginals(root.resolve("rename"));
            renameFailureAndNoOpKeepCommittedRecords(root.resolve("rename-errors"));
            rejectsInvalidInputAndRootEscape(root.resolve("invalid"));
            corruptionAndFailedWritesPreserveRecordsAndOriginals(root.resolve("damage"));
            interruptedSnapshotRecoversCommittedBackup(root.resolve("recovery"));
            deletionRemovesAllReferencesBeforeUnlink(root.resolve("delete"));
            System.out.println("PASS: " + checks + " photo-live project store checks");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) throw new AssertionError("cleanup escaped test directory");
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void classificationSurvivesRecreation(Path root) throws Exception {
        File originals = root.resolve("camera-album").toFile(); Files.createDirectories(originals.toPath());
        File catalog = root.resolve("projects").toFile(); PhotoLiveProjectStore store = new PhotoLiveProjectStore(catalog, originals);
        PhotoLiveProject first = store.create("  生日聚会  "), second = store.create("生日聚会");
        yes(!first.id.equals(second.id)); equal(first.name, "生日聚会"); equal(first.photoCount, 0);
        equal(store.list().get(0).id, second.id); yes(second.createdAt > first.createdAt);
        DownloadedPhoto one = photo(originals, "live-one.jpg"), two = photo(originals, "live-two.jpg");
        equal(one.projectId(), ""); equal(one.projectName(), "");
        store.record(first.id, identity('1'), one); store.record(first.id, identity('2'), two); store.record(first.id, identity('1'), one);
        store.record(second.id, identity('1'), one);
        PhotoLiveProject snapshot = store.get(first.id); equal(snapshot.photoCount, 2); equal(snapshot.photos.get(0).filename(), "live-two.jpg");
        equal(snapshot.photos.get(1).projectId(), first.id); equal(snapshot.photos.get(1).projectName(), first.name);
        equal(store.get(second.id).photoCount, 1); equal(store.get(second.id).photos.get(0).file(), one.file().getCanonicalFile());
        yes(Arrays.equals(Files.readAllBytes(one.file().toPath()), JPEG)); yes(Arrays.equals(Files.readAllBytes(two.file().toPath()), JPEG));
        try { snapshot.photos.clear(); throw new AssertionError("mutable project photos"); } catch (UnsupportedOperationException expected) { checks++; }
        try { store.list().clear(); throw new AssertionError("mutable project list"); } catch (UnsupportedOperationException expected) { checks++; }
        store = new PhotoLiveProjectStore(catalog, originals); equal(store.list().size(), 2); equal(store.get(first.id).photoCount, 2);
        equal(store.get(first.id).photos.get(0).projectId(), first.id); equal(store.get(first.id).photos.get(1).filename(), one.filename());
        String folder1 = PhotoLiveProjectStore.galleryFolder(first.id, first.name), folder2 = PhotoLiveProjectStore.galleryFolder(second.id, second.name);
        yes(!folder1.equals(folder2)); yes(folder1.endsWith(first.id.substring(0, 8))); yes(!folder1.contains("/")); yes(!folder1.contains("\\"));
        String safe = PhotoLiveProjectStore.galleryFolder(first.id, "海边：夏天 ★"); yes(!safe.contains(":")); yes(!safe.contains("★"));
        PhotoLiveProject unicode = store.create("😀".repeat(60)); equal(unicode.name.codePointCount(0, unicode.name.length()), 60);
        final PhotoLiveProjectStore current = store; fails(() -> current.create("😀".repeat(61)));
    }

    private static void allProjectsIncludingEmptyOnesSurviveRepeatedRecreation(Path root) throws Exception {
        File originals = root.resolve("camera-album").toFile(); Files.createDirectories(originals.toPath());
        File catalog = root.resolve("projects").toFile(); PhotoLiveProjectStore store = new PhotoLiveProjectStore(catalog, originals);
        List<PhotoLiveProject> created = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) created.add(store.create(i % 3 == 0 ? "同名项目" : "历史项目 " + i));
        DownloadedPhoto one = photo(originals, "first-project.jpg"), two = photo(originals, "later-project.jpg");
        store.record(created.get(2).id, identity('1'), one); store.record(created.get(6).id, identity('2'), two);
        byte[][] committed = new byte[created.size()][];
        for (int i = 0; i < created.size(); i++) committed[i] = Files.readAllBytes(catalog.toPath().resolve(created.get(i).id + ".project"));
        // No in-memory controller/run state is carried over: every pass opens a fresh catalog object.
        for (int reopen = 0; reopen < 3; reopen++) {
            store = new PhotoLiveProjectStore(catalog, originals);
            List<PhotoLiveProject> restored = store.list(); equal(restored.size(), created.size());
            for (int position = 0; position < created.size(); position++) {
                int originalIndex = created.size() - position - 1;
                PhotoLiveProject previous = created.get(originalIndex), project = restored.get(position);
                equal(project.id, previous.id); equal(project.name, previous.name); equal(project.createdAt, previous.createdAt);
                equal(project.photoCount, originalIndex == 2 || originalIndex == 6 ? 1 : 0);
                yes(Arrays.equals(Files.readAllBytes(catalog.toPath().resolve(project.id + ".project")), committed[originalIndex]));
                if (project.photoCount == 1) {
                    equal(project.photos.get(0).projectId(), project.id);
                    equal(project.photos.get(0).file(), (originalIndex == 2 ? one : two).file().getCanonicalFile());
                }
            }
            equal(store.get(created.get(0).id).photoCount, 0); // Oldest empty project is retained, not just the latest/current run.
            equal(store.get(created.get(3).id).name, "同名项目");
        }
        yes(Arrays.equals(Files.readAllBytes(one.file().toPath()), JPEG)); yes(Arrays.equals(Files.readAllBytes(two.file().toPath()), JPEG));
    }

    private static void rejectsInvalidInputAndRootEscape(Path root) throws Exception {
        File originals = root.resolve("camera-album").toFile(); Files.createDirectories(originals.toPath());
        PhotoLiveProjectStore store = new PhotoLiveProjectStore(root.resolve("projects").toFile(), originals);
        for (String invalid : new String[] {"", "  ", ".", "..", "a/../../b", "a\\b", "a\nb", "\nedge", "edge\t", "a\u0000b", "a\u202eb", "\ud800", "a".repeat(61)}) fails(() -> store.create(invalid));
        fails(() -> store.create(null)); equal(store.list().size(), 0);
        PhotoLiveProject project = store.create("现场"); DownloadedPhoto original = photo(originals, "one.jpg");
        for (String id : new String[] {"", "../escape", "..\\escape", "/escape", "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF", "123"}) {
            fails(() -> store.get(id)); fails(() -> store.record(id, identity('a'), original));
        }
        fails(() -> store.get("00000000-0000-0000-0000-000000000000"));
        for (String id : new String[] {"", "../bad", "a".repeat(63), "G".repeat(64)}) fails(() -> store.record(project.id, id, original));
        fails(() -> store.record(project.id, null, original)); fails(() -> store.record(project.id, identity('a'), null));
        Path outside = root.resolve("outside.jpg"); Files.write(outside, JPEG);
        fails(() -> store.record(project.id, identity('b'), new DownloadedPhoto(outside.toFile(), "outside.jpg")));
        fails(() -> store.record(project.id, identity('c'), new DownloadedPhoto(original.file(), "../evil.jpg")));
        File nested = new File(originals, "nested"); Files.createDirectories(nested.toPath()); File nestedPhoto = new File(nested, "nested.jpg"); Files.write(nestedPhoto.toPath(), JPEG);
        fails(() -> store.record(project.id, identity('d'), new DownloadedPhoto(nestedPhoto, "nested.jpg")));
        equal(store.get(project.id).photoCount, 0); yes(Arrays.equals(Files.readAllBytes(outside), JPEG)); yes(original.file().isFile());
        fails(() -> PhotoLiveProjectStore.galleryFolder("../escape", "name")); fails(() -> PhotoLiveProjectStore.galleryFolder(project.id, "../../other"));
    }

    private static void renameKeepsIdentityOrderEntriesAndOriginals(Path root) throws Exception {
        File originals = root.resolve("camera-album").toFile(); Files.createDirectories(originals.toPath());
        File catalog = root.resolve("projects").toFile(); PhotoLiveProjectStore store = new PhotoLiveProjectStore(catalog, originals);
        PhotoLiveProject first = store.create("原名称"), second = store.create("同名可用");
        equal(first.totalBytes, 0L); equal(second.totalBytes, 0L);
        DownloadedPhoto one = photo(originals, "one.jpg"), two = photo(originals, "two.jpg");
        byte[] different = {(byte) 255, (byte) 216, 1, 2, 3, 4, 5, 6, (byte) 255, (byte) 217};
        Files.write(two.file().toPath(), different);
        store.record(first.id, identity('1'), one); store.record(first.id, identity('2'), two);
        Path record = catalog.toPath().resolve(first.id + ".project"); byte[] entries = entryPayload(Files.readAllBytes(record));
        long oneModified = one.file().lastModified(), twoModified = two.file().lastModified();
        PhotoLiveProject renamed = store.rename(first.id, "  同名可用  ");
        equal(renamed.id, first.id); equal(renamed.createdAt, first.createdAt); equal(renamed.name, "同名可用");
        equal(renamed.photoCount, 2); equal(renamed.totalBytes, (long) JPEG.length + different.length);
        equal(new PhotoLiveProjectFolders(catalog).bind(first.id, renamed.name), PhotoLiveProjectStore.galleryFolder(first.id, "原名称"));
        equal(store.list().get(0).id, second.id); equal(store.list().get(1).id, first.id);
        equal(renamed.photos.get(0).file(), two.file().getCanonicalFile()); equal(renamed.photos.get(1).file(), one.file().getCanonicalFile());
        for (DownloadedPhoto photo : renamed.photos) { equal(photo.projectId(), first.id); equal(photo.projectName(), "同名可用"); }
        yes(Arrays.equals(entries, entryPayload(Files.readAllBytes(record))));
        yes(Arrays.equals(Files.readAllBytes(one.file().toPath()), JPEG)); yes(Arrays.equals(Files.readAllBytes(two.file().toPath()), different));
        equal(one.file().lastModified(), oneModified); equal(two.file().lastModified(), twoModified);
        equal(first.name, "原名称"); equal(first.photoCount, 0); // Earlier immutable snapshots do not mutate.
        String unicode = "夏日人像 📷";
        store.rename(first.id, unicode);
        store = new PhotoLiveProjectStore(catalog, originals); PhotoLiveProject restored = store.get(first.id);
        equal(restored.name, unicode); equal(restored.createdAt, first.createdAt); equal(restored.totalBytes, renamed.totalBytes);
        equal(new PhotoLiveProjectFolders(catalog).bind(first.id, restored.name), PhotoLiveProjectStore.galleryFolder(first.id, "原名称"));
        yes(Arrays.equals(entries, entryPayload(Files.readAllBytes(record))));
        PhotoLiveProject empty = store.rename(second.id, unicode); equal(empty.photoCount, 0); equal(empty.totalBytes, 0L);
        equal(empty.id, second.id); equal(empty.createdAt, second.createdAt);
        // Capacity is persisted metadata: missing/changed source files do not trigger UI file stats or silently alter totals.
        Files.delete(one.file().toPath()); Files.write(two.file().toPath(), JPEG);
        equal(store.get(first.id).totalBytes, renamed.totalBytes);
        equal(store.rename(first.id, "本地文件暂不可用").totalBytes, renamed.totalBytes);
        equal(store.get(first.id).photoCount, 2); yes(Arrays.equals(entries, entryPayload(Files.readAllBytes(record))));
    }

    private static void renameFailureAndNoOpKeepCommittedRecords(Path root) throws Exception {
        File originals = root.resolve("camera-album").toFile(); Files.createDirectories(originals.toPath());
        File catalog = root.resolve("projects").toFile(); PhotoLiveProjectStore store = new PhotoLiveProjectStore(catalog, originals);
        PhotoLiveProject project = store.create("保留名称"); DownloadedPhoto one = photo(originals, "one.jpg"); store.record(project.id, identity('1'), one);
        Path record = catalog.toPath().resolve(project.id + ".project"); byte[] committed = Files.readAllBytes(record);
        Files.setLastModifiedTime(record, java.nio.file.attribute.FileTime.fromMillis(1234567890000L)); long modified = Files.getLastModifiedTime(record).toMillis();
        equal(store.rename(project.id, "  保留名称  ").name, project.name);
        yes(Arrays.equals(Files.readAllBytes(record), committed)); equal(Files.getLastModifiedTime(record).toMillis(), modified);
        for (String invalid : new String[] {null, "", "  ", ".", "../escape", "a\\b", "a/b", "A\nB", "\nedge", "x\u0000y", "\u202e", "\ud800", "x".repeat(61)}) {
            fails(() -> store.rename(project.id, invalid)); yes(Arrays.equals(Files.readAllBytes(record), committed));
            equal(store.get(project.id).name, project.name);
        }
        fails(() -> store.rename("../outside", "合法名称")); fails(() -> store.rename("00000000-0000-0000-0000-000000000000", "合法名称"));
        Path part = Path.of(record + ".part"); Files.createDirectories(part); Files.writeString(part.resolve("keep"), "occupied");
        fails(() -> store.rename(project.id, "写入失败")); yes(Arrays.equals(Files.readAllBytes(record), committed));
        Files.delete(part.resolve("keep")); Files.delete(part);
        Path folder = catalog.toPath().resolve(project.id + ".folder"); byte[] invalidBinding = {1, 2, 3}; Files.write(folder, invalidBinding);
        fails(() -> store.rename(project.id, "目录绑定失败")); yes(Arrays.equals(Files.readAllBytes(record), committed));
        yes(Arrays.equals(Files.readAllBytes(folder), invalidBinding)); equal(store.get(project.id).name, project.name);
        byte[] damaged = committed.clone(); damaged[damaged.length - 1] ^= 1; Files.write(record, damaged);
        fails(() -> store.rename(project.id, "不能覆盖损坏记录")); yes(Arrays.equals(Files.readAllBytes(record), damaged));
        yes(Arrays.equals(Files.readAllBytes(one.file().toPath()), JPEG));
    }

    /** Compare the exact serialized entry section, excluding only editable header fields and checksum. */
    private static byte[] entryPayload(byte[] encoded) throws IOException {
        try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(encoded))) {
            equal(input.readInt(), 0x504c5031); int length = input.readInt(); byte[] payload = new byte[length]; input.readFully(payload);
            try (var data = new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload))) {
                data.readUTF(); data.readUTF(); data.readLong(); return data.readAllBytes();
            }
        }
    }

    private static void corruptionAndFailedWritesPreserveRecordsAndOriginals(Path root) throws Exception {
        File originals = root.resolve("camera-album").toFile(); Files.createDirectories(originals.toPath());
        File catalog = root.resolve("projects").toFile(); PhotoLiveProjectStore store = new PhotoLiveProjectStore(catalog, originals);
        PhotoLiveProject project = store.create("项目记录"); DownloadedPhoto one = photo(originals, "one.jpg"), two = photo(originals, "two.jpg");
        store.record(project.id, identity('1'), one); Path record = catalog.toPath().resolve(project.id + ".project"); byte[] committed = Files.readAllBytes(record);
        fails(() -> store.record(project.id, identity('1'), two));
        store.record(project.id, identity('2'), one);
        equal(store.get(project.id).photoCount, 1); yes(Arrays.equals(Files.readAllBytes(record), committed));
        yes(Arrays.equals(Files.readAllBytes(record), committed)); equal(store.get(project.id).photoCount, 1);
        byte[] changed = JPEG.clone(); changed[2]++;
        Files.write(one.file().toPath(), changed); fails(() -> store.record(project.id, identity('1'), one));
        yes(Arrays.equals(Files.readAllBytes(one.file().toPath()), changed)); yes(Arrays.equals(Files.readAllBytes(record), committed));
        Files.delete(one.file().toPath()); fails(() -> store.record(project.id, identity('1'), one)); equal(store.get(project.id).photoCount, 1);
        Files.write(one.file().toPath(), JPEG);
        Path blocked = catalog.toPath().resolve(project.id + ".project.part"); Files.createDirectories(blocked); Files.writeString(blocked.resolve("keep"), "occupied");
        fails(() -> store.record(project.id, identity('2'), two)); yes(Arrays.equals(Files.readAllBytes(record), committed)); yes(Arrays.equals(Files.readAllBytes(two.file().toPath()), JPEG));
        Files.delete(blocked.resolve("keep")); Files.delete(blocked);
        byte[] damaged = committed.clone(); damaged[damaged.length - 1] ^= 1; Files.write(record, damaged);
        fails(() -> store.get(project.id)); fails(store::list); fails(() -> store.record(project.id, identity('2'), two)); fails(() -> store.create("新项目"));
        yes(Arrays.equals(Files.readAllBytes(record), damaged)); yes(Arrays.equals(Files.readAllBytes(one.file().toPath()), JPEG)); yes(Arrays.equals(Files.readAllBytes(two.file().toPath()), JPEG));
    }

    private static void interruptedSnapshotRecoversCommittedBackup(Path root) throws Exception {
        File originals = root.resolve("camera-album").toFile(); Files.createDirectories(originals.toPath());
        File catalog = root.resolve("projects").toFile(); PhotoLiveProjectStore store = new PhotoLiveProjectStore(catalog, originals);
        PhotoLiveProject project = store.create("恢复"); DownloadedPhoto one = photo(originals, "one.jpg"); store.record(project.id, identity('1'), one);
        Path base = catalog.toPath().resolve(project.id + ".project"), backup = Path.of(base + ".bak"), part = Path.of(base + ".part"); byte[] committed = Files.readAllBytes(base);
        Files.move(base, backup); Files.write(part, new byte[] {1, 2});
        PhotoLiveProjectStore restored = new PhotoLiveProjectStore(catalog, originals); equal(restored.list().size(), 1); equal(restored.get(project.id).photoCount, 1);
        yes(Files.exists(base)); yes(!Files.exists(backup)); yes(!Files.exists(part)); yes(Arrays.equals(Files.readAllBytes(base), committed));
        Files.write(backup, committed); Files.write(part, new byte[] {3, 4}); equal(restored.get(project.id).photoCount, 1); yes(!Files.exists(backup)); yes(!Files.exists(part));
        String orphan = "00000000-0000-0000-0000-000000000001"; Path orphanPart = catalog.toPath().resolve(orphan + ".project.part"); Files.write(orphanPart, new byte[] {1});
        equal(restored.list().size(), 1); yes(!Files.exists(orphanPart)); yes(Arrays.equals(Files.readAllBytes(one.file().toPath()), JPEG));
        Files.write(base, new byte[] {1, 2, 3}); Files.write(backup, committed); fails(() -> restored.get(project.id));
        equal(Files.size(base), 3L); yes(Arrays.equals(Files.readAllBytes(backup), committed)); yes(one.file().isFile());
    }

    private static DownloadedPhoto photo(File directory, String name) throws IOException { File file = new File(directory, name); Files.write(file.toPath(), JPEG); return new DownloadedPhoto(file, name); }
    private static void deletionRemovesAllReferencesBeforeUnlink(Path root) throws Exception {
        File originals = root.resolve("camera-album").toFile(); Files.createDirectories(originals.toPath()); File catalog = root.resolve("projects").toFile();
        PhotoLiveProjectStore store = new PhotoLiveProjectStore(catalog, originals); PhotoLiveProject a = store.create("A"), b = store.create("B");
        DownloadedPhoto source = photo(originals, "source.JPG"), kept = photo(originals, "kept.JPG");
        store.record(a.id, identity('1'), source); store.record(a.id, identity('2'), kept); store.record(b.id, identity('3'), source);
        byte[] before = Files.readAllBytes(catalog.toPath().resolve(a.id + ".project"));
        StringBuilder sha = new StringBuilder(); for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(JPEG)) sha.append(String.format("%02x", value & 255));
        try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.delete()) {
            fails(() -> store.removeFile(source.file(), JPEG.length, "f".repeat(64)));
            yes(Arrays.equals(before, Files.readAllBytes(catalog.toPath().resolve(a.id + ".project"))));
            store.removeFile(source.file(), JPEG.length, sha.toString());
            equal(store.get(a.id).photoCount, 1); equal(store.get(b.id).photoCount, 0); equal(store.get(a.id).totalBytes, (long) JPEG.length);
            equal(store.get(a.id).createdAt, a.createdAt); equal(store.get(b.id).name, b.name); yes(source.file().isFile()); yes(kept.file().isFile());
            Files.delete(source.file().toPath());
            PhotoLiveProjectStore restored = new PhotoLiveProjectStore(catalog, originals); equal(restored.list().size(), 2); equal(restored.get(a.id).photos.get(0).filename(), kept.filename()); equal(restored.get(b.id).photoCount, 0);
            byte[] committed = Files.readAllBytes(catalog.toPath().resolve(a.id + ".project")); restored.removeFile(source.file(), JPEG.length, sha.toString());
            yes(Arrays.equals(committed, Files.readAllBytes(catalog.toPath().resolve(a.id + ".project"))));
        }
    }
    private static String identity(char value) { return String.valueOf(value).repeat(64); }
    private static void equal(Object actual, Object expected) { if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected); checks++; }
    private static void yes(boolean value) { if (!value) throw new AssertionError("condition failed"); checks++; }
    private interface Attempt { void run() throws Exception; }
    private static void fails(Attempt attempt) throws Exception { try { attempt.run(); } catch (IOException expected) { checks++; return; } throw new AssertionError("expected IOException"); }
}

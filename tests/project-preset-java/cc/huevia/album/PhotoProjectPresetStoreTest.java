package cc.huevia.album;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import cc.huevia.album.PhotoProjectPresetStore.Config;
import cc.huevia.album.PhotoProjectPresetStore.RemovalReferences;
import cc.huevia.album.PhotoProjectPresetStore.Task;

/** Durable defaults and original-preserving automatic-processing metadata, without Android. */
public final class PhotoProjectPresetStoreTest {
    private static final String P1 = "00000001-0000-0000-0000-000000000001", P2 = "00000002-0000-0000-0000-000000000002";
    private static final String N1 = hash(1), N2 = hash(2);
    private static int checks;
    private interface Work { void run() throws Exception; }
    private interface Payload { void write(DataOutputStream data) throws Exception; }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("project-presets-");
        try {
            defaultsDoNotRewriteExistingTasks(root.resolve("defaults"));
            dedupUpdatesAndProcessingRecovery(root.resolve("tasks"));
            invalidArgumentsAndStaleUpdates(root.resolve("invalid"));
            corruptionNeverResetsHistory(root.resolve("corruption"));
            atomicFailureRetainsCommittedMemoryAndDisk(root.resolve("atomic"));
            readersStayIndependentOfWriterIo(root.resolve("nonblocking"));
            multipleInstancesMergeMutations(root.resolve("concurrent"));
            taskLimitPreservesExistingJournal(root.resolve("limit"));
            removalClearsOnlyConfirmedDefaultsAndPreservesHistory(root.resolve("remove"));
            removalRejectsChangedReferencesAndActiveTasks(root.resolve("remove-stale"));
            removalAtomicFailurePreservesDefaults(root.resolve("remove-failure"));
            System.out.println("PASS: " + checks + " project-preset store checks");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) throw new AssertionError("cleanup escaped test root"); Files.deleteIfExists(path);
            } }
        }
    }

    private static void defaultsDoNotRewriteExistingTasks(Path directory) throws Exception {
        PhotoProjectPresetStore store = new PhotoProjectPresetStore(directory.toFile());
        Config empty = store.config(P1); equal(empty.presetId, ""); equal(empty.presetName, ""); yes(empty.enabledSince == 0);
        store.queue(task(P1, 1, N1, "queued", "", "", 10)); List<Task> original = store.tasks(P1);
        long before = System.currentTimeMillis(); Config first = store.setConfig(P1, N1, "柔和胶片");
        yes(first.enabledSince >= before && first.enabledSince <= System.currentTimeMillis()); equal(store.tasks(P1).size(), 1); equal(store.tasks(P1).get(0).presetId, N1);
        byte[] committed = Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME));
        equal(store.setConfig(P1, N1, "柔和胶片").enabledSince, first.enabledSince);
        yes(Arrays.equals(committed, Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME))));
        equal(store.setConfig(P1, N1, "新名称").enabledSince, first.enabledSince);
        Config second = store.setConfig(P1, N2, "另一预设"); equal(second.presetId, N2); yes(second.enabledSince > 0);
        equal(store.tasks(P1).size(), 1); equal(store.tasks(P1).get(0).presetId, N1); equal(original.get(0).presetId, N1);
        store.setConfig(P2, N1, "第二个项目");
        Config disabled = store.setConfig(P1, "", ""); equal(disabled.enabledSince, 0L); equal(store.config(P1).presetId, "");
        PhotoProjectPresetStore reopened = new PhotoProjectPresetStore(directory.toFile()); equal(reopened.config(P1).presetId, ""); equal(reopened.config(P2).presetName, "第二个项目");
        equal(reopened.tasks(P1).size(), 1); rejects(() -> original.clear()); rejects(() -> reopened.allTasks().clear());
    }

    private static void dedupUpdatesAndProcessingRecovery(Path directory) throws Exception {
        PhotoProjectPresetStore store = new PhotoProjectPresetStore(directory.toFile());
        Task first = task(P1, 1, N1, "queued", "", "", 10); store.queue(first);
        byte[] before = Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME));
        equal(store.queue(first).key(), first.key()); yes(Arrays.equals(before, Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME))));
        store.queue(task(P1, 1, N2, "queued", "", "", 11)); store.queue(task(P2, 1, N1, "queued", "", "", 12));
        equal(store.tasks(P1).size(), 2); equal(store.tasks(P2).size(), 1); equal(store.allTasks().size(), 3);
        Task processing = first.withStatus("processing", "", ""); yes(processing.updatedAt > first.updatedAt); store.update(processing);
        equal(store.tasks(P1).get(1).status, "processing");
        // Config writes and other mutations must not treat an actively processing task as an interrupted one.
        store.setConfig(P1, N1, "选定预设"); equal(find(store, first.key()).status, "processing");
        PhotoProjectPresetStore recovered = new PhotoProjectPresetStore(directory.toFile()); Task queued = find(recovered, first.key());
        equal(queued.status, "queued"); equal(queued.updatedAt, processing.updatedAt); equal(queued.sourceFileName, first.sourceFileName);
        byte[] normalized = Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME));
        equal(find(new PhotoProjectPresetStore(directory.toFile()), first.key()).status, "queued");
        yes(Arrays.equals(normalized, Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME))));
        Task failed = queued.withStatus("error", "", "模拟渲染错误"); recovered.update(failed); equal(find(recovered, first.key()).error, "模拟渲染错误");
        Task retry = failed.withStatus("processing", "", ""); recovered.update(retry); Task saved = retry.withStatus("saved", "effect-unique.JPG", ""); recovered.update(saved);
        equal(recovered.queue(first).status, "saved"); equal(recovered.allTasks().size(), 3);
        equal(find(new PhotoProjectPresetStore(directory.toFile()), first.key()).resultFileName, "effect-unique.JPG");
        fails(() -> recovered.update(saved.withStatus("queued", "", ""))); equal(find(recovered, first.key()).status, "saved");
    }

    private static void invalidArgumentsAndStaleUpdates(Path directory) throws Exception {
        PhotoProjectPresetStore store = new PhotoProjectPresetStore(directory.toFile()); Task initial = task(P1, 1, N1, "queued", "", "", 10); store.queue(initial);
        rejects(() -> store.config("bad")); rejects(() -> store.tasks("bad")); rejects(() -> store.setConfig(P1, "x", "名字"));
        rejects(() -> store.setConfig(P1, "", "关闭时不应保留名称")); rejects(() -> store.queue(null));
        for (String path : new String[] {"../photo.JPG", "a/b.JPG", "C:\\photo.JPG", ".", "..", "bad\n.JPG"}) rejects(() -> new Task(P1, hash(1), N1, "预设", path, "原图.JPG", 20, "queued", "", "", 1));
        rejects(() -> task(P1, 1, N1, "unknown", "", "", 1)); rejects(() -> task(P1, 1, N1, "saved", "", "", 1));
        rejects(() -> task(P1, 1, N1, "queued", "some.JPG", "", 1)); rejects(() -> task(P1, 1, N1, "error", "", "", 1));
        rejects(() -> task(P1, 1, N1, "saved", "unique-1.JPG", "", 1));
        rejects(() -> new Config(P1, N1, "预设", 0)); rejects(() -> store.queue(initial.withStatus("processing", "", "")));
        Task changed = new Task(P1, hash(1), N1, "预设", "other.JPG", "原图1.JPG", 20, "queued", "", "", 11);
        equal(store.queue(changed).sourceFileName, initial.sourceFileName); equal(store.tasks(P1).size(), 1);
        fails(() -> store.update(changed)); fails(() -> store.update(task(P2, 1, N1, "queued", "", "", 20)));
        store.update(task(P1, 1, N1, "processing", "", "", 20));
        fails(() -> store.update(initial)); equal(find(store, initial.key()).status, "processing");
    }

    private static void corruptionNeverResetsHistory(Path root) throws Exception {
        PhotoProjectPresetStore store = new PhotoProjectPresetStore(root.toFile()); store.queue(task(P1, 1, N1, "queued", "", "", 1));
        byte[] valid = Files.readAllBytes(root.resolve(PhotoProjectPresetStore.FILE_NAME)); List<byte[]> invalid = new ArrayList<>();
        for (int length : new int[] {0, 1, 7, 39, 47, valid.length - 1}) invalid.add(Arrays.copyOf(valid, length));
        byte[] checksum = valid.clone(); checksum[checksum.length - 1] ^= 1; invalid.add(checksum);
        byte[] magic = valid.clone(); magic[0] ^= 1; invalid.add(magic); byte[] length = valid.clone(); length[7] ^= 1; invalid.add(length);
        invalid.add(framed(payload(data -> data.writeInt(-1)))); invalid.add(framed(payload(data -> data.writeInt(1025))));
        invalid.add(framed(payload(data -> { data.writeInt(0); data.writeInt(20001); })));
        invalid.add(framed(payload(data -> { data.writeInt(0); data.writeInt(0); data.writeByte(1); })));
        Task first = task(P1, 1, N1, "queued", "", "", 1), second = task(P1, 2, N1, "queued", "", "", 2);
        invalid.add(journal(first, first)); invalid.add(journal(second, first));
        invalid.add(framed(payload(data -> { data.writeInt(0); data.writeInt(1); writeTask(data, first, "../escape.JPG", "queued"); })));
        invalid.add(framed(payload(data -> { data.writeInt(0); data.writeInt(1); writeTask(data, first, first.sourceFileName, "unsupported"); })));
        invalid.add(framed(payload(data -> { data.writeInt(1); data.writeUTF(P1); data.writeUTF(""); data.writeUTF(""); data.writeLong(0); data.writeInt(0); })));
        int i = 0;
        for (byte[] broken : invalid) {
            Path dir = root.resolve("bad-" + i++); Files.createDirectories(dir); Path file = dir.resolve(PhotoProjectPresetStore.FILE_NAME); Files.write(file, broken);
            fails(() -> new PhotoProjectPresetStore(dir.toFile())); yes(Arrays.equals(broken, Files.readAllBytes(file)));
        }
        Files.write(root.resolve(PhotoProjectPresetStore.FILE_NAME), checksum); equal(store.tasks(P1).size(), 1);
        fails(() -> store.setConfig(P1, N1, "预设")); equal(store.config(P1).presetId, ""); equal(store.tasks(P1).size(), 1);
        yes(Arrays.equals(checksum, Files.readAllBytes(root.resolve(PhotoProjectPresetStore.FILE_NAME))));
        Files.write(root.resolve(PhotoProjectPresetStore.FILE_NAME), valid); Files.delete(root.resolve(PhotoProjectPresetStore.FILE_NAME));
        fails(() -> store.queue(second)); equal(store.tasks(P1).size(), 1);
    }

    private static void atomicFailureRetainsCommittedMemoryAndDisk(Path directory) throws Exception {
        PhotoProjectPresetStore normal = new PhotoProjectPresetStore(directory.toFile()); Task first = task(P1, 1, N1, "queued", "", "", 1); normal.queue(first);
        byte[] before = Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME));
        PhotoProjectPresetStore failing = new PhotoProjectPresetStore(directory.toFile(), (temporary, target) -> {
            yes(temporary.isFile() && temporary.length() > 48); yes(Arrays.equals(before, Files.readAllBytes(target.toPath()))); throw new IOException("synthetic replacement failure");
        });
        List<Task> memory = failing.allTasks();
        for (Work action : List.<Work>of(() -> failing.setConfig(P1, N1, "预设"), () -> failing.queue(task(P1, 2, N1, "queued", "", "", 2)), () -> failing.update(first.withStatus("processing", "", "")))) {
            fails(action); yes(failing.allTasks() == memory); equal(failing.allTasks().size(), 1); equal(failing.config(P1).presetId, "");
            yes(Arrays.equals(before, Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME))));
            try (var files = Files.list(directory)) { yes(files.noneMatch(file -> file.getFileName().toString().endsWith(".part"))); }
        }
        normal.update(first.withStatus("processing", "", "")); byte[] interrupted = Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME));
        fails(() -> new PhotoProjectPresetStore(directory.toFile(), (temporary, target) -> { throw new IOException("recovery cannot commit"); }));
        yes(Arrays.equals(interrupted, Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME))));
    }

    private static void readersStayIndependentOfWriterIo(Path directory) throws Exception {
        new PhotoProjectPresetStore(directory.toFile()).queue(task(P1, 1, N1, "queued", "", "", 1));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        PhotoProjectPresetStore store = new PhotoProjectPresetStore(directory.toFile(), (temporary, target) -> {
            entered.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("writer gate timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var write = pool.submit(() -> store.setConfig(P1, N1, "预设")); yes(entered.await(5, TimeUnit.SECONDS));
            equal(pool.submit(() -> store.config(P1).presetId).get(1, TimeUnit.SECONDS), "");
            equal(pool.submit(() -> store.tasks(P1).size()).get(1, TimeUnit.SECONDS), 1);
            release.countDown(); write.get(5, TimeUnit.SECONDS); equal(store.config(P1).presetId, N1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private static void multipleInstancesMergeMutations(Path directory) throws Exception {
        PhotoProjectPresetStore first = new PhotoProjectPresetStore(directory.toFile()), second = new PhotoProjectPresetStore(directory.toFile());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { first.setConfig(P1, N1, "一"); for (int i = 1; i <= 10; i++) first.queue(task(P1, i, N1, "queued", "", "", i)); return null; });
            var b = pool.submit(() -> { second.setConfig(P2, N2, "二"); for (int i = 1; i <= 10; i++) second.queue(task(P2, i, N2, "queued", "", "", i)); return null; });
            a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
            PhotoProjectPresetStore all = new PhotoProjectPresetStore(directory.toFile()); equal(all.allTasks().size(), 20);
            equal(all.tasks(P1).size(), 10); equal(all.tasks(P2).size(), 10); equal(all.config(P1).presetId, N1); equal(all.config(P2).presetId, N2);
        } finally { pool.shutdownNow(); }
    }

    private static void taskLimitPreservesExistingJournal(Path directory) throws Exception {
        Files.createDirectories(directory); byte[] full = framed(payload(data -> {
            data.writeInt(0); data.writeInt(20000); for (int i = 0; i < 20000; i++) { Task task = task(P1, i, N1, "queued", "", "", i); writeTask(data, task, task.sourceFileName, task.status); }
        }));
        Files.write(directory.resolve(PhotoProjectPresetStore.FILE_NAME), full);
        PhotoProjectPresetStore store = new PhotoProjectPresetStore(directory.toFile()); equal(store.allTasks().size(), 20000);
        fails(() -> store.queue(task(P1, 20000, N1, "queued", "", "", 20000))); equal(store.allTasks().size(), 20000);
        yes(Arrays.equals(full, Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME))));
    }

    private static Task task(String project, int identity, String preset, String status, String result, String error, long updated) {
        return new Task(project, hash(identity), preset, "预设", "unique-" + identity + ".JPG", "原图" + identity + ".JPG", 20, status, result, error, updated);
    }

    private static void removalClearsOnlyConfirmedDefaultsAndPreservesHistory(Path directory) throws Exception {
        PhotoProjectPresetStore store = new PhotoProjectPresetStore(directory.toFile());
        store.setConfig(P1, N1, "待删除预设"); store.setConfig(P2, N2, "保留预设");
        Task error = task(P1, 10, N1, "queued", "", "", 10); store.queue(error);
        error = error.withStatus("error", "", "原图保留，调色失败"); store.update(error);
        Task saved = task(P1, 11, N1, "queued", "", "", 11); store.queue(saved);
        saved = saved.withStatus("saved", "finished-effect.JPG", ""); store.update(saved);
        Task other = task(P2, 12, N2, "queued", "", "", 12); store.queue(other);
        List<Task> history = store.allTasks(); RemovalReferences references = store.inspectRemoval(N1);
        equal(references.presetId, N1); equal(references.configs.size(), 1); equal(references.configs.get(0).projectId, P1); equal(references.failedTasks, 1);
        equal(references.configs.get(0).presetName, "待删除预设"); rejects(() -> references.configs.clear());
        equal(store.clearPresetReferences(references), 1); equal(store.config(P1).presetId, ""); equal(store.config(P1).enabledSince, 0L);
        equal(store.config(P2).presetId, N2); equal(store.tasks(P1).size(), 2); equal(store.allTasks().size(), 3);
        for (Task before : history) {
            Task after = find(store, before.key()); equal(after.status, before.status); equal(after.error, before.error);
            equal(after.sourceFileName, before.sourceFileName); equal(after.resultFileName, before.resultFileName); equal(after.updatedAt, before.updatedAt);
        }
        PhotoProjectPresetStore reopened = new PhotoProjectPresetStore(directory.toFile());
        equal(reopened.config(P1).presetId, ""); equal(reopened.config(P2).presetId, N2);
        equal(find(reopened, error.key()).error, error.error); equal(find(reopened, saved.key()).resultFileName, "finished-effect.JPG");
        equal(find(reopened, other.key()).status, "queued"); equal(reopened.inspectRemoval(N1).failedTasks, 1);
        byte[] beforeNoOp = Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME));
        equal(reopened.clearPresetReferences(reopened.inspectRemoval(N1)), 0);
        yes(Arrays.equals(beforeNoOp, Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME))));
        rejects(() -> reopened.inspectRemoval("invalid")); rejects(() -> reopened.clearPresetReferences(null));
    }

    private static void removalRejectsChangedReferencesAndActiveTasks(Path directory) throws Exception {
        PhotoProjectPresetStore store = new PhotoProjectPresetStore(directory.toFile());
        RemovalReferences empty = store.inspectRemoval(N1); equal(empty.configs.size(), 0); equal(empty.failedTasks, 0);
        store.setConfig(P1, N1, "先确认"); byte[] committed = Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME));
        fails(() -> store.clearPresetReferences(empty)); equal(store.config(P1).presetId, N1);
        yes(Arrays.equals(committed, Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME))));
        RemovalReferences beforeRename = store.inspectRemoval(N1); store.setConfig(P1, N1, "改名后");
        fails(() -> store.clearPresetReferences(beforeRename)); equal(store.config(P1).presetName, "改名后");
        RemovalReferences beforeAddedDefault = store.inspectRemoval(N1);
        PhotoProjectPresetStore second = new PhotoProjectPresetStore(directory.toFile()); second.setConfig(P2, N1, "另一个页面添加默认");
        fails(() -> store.clearPresetReferences(beforeAddedDefault));
        PhotoProjectPresetStore check = new PhotoProjectPresetStore(directory.toFile()); equal(check.config(P1).presetId, N1); equal(check.config(P2).presetId, N1);
        RemovalReferences beforeChangedPreset = store.inspectRemoval(N1); second.setConfig(P1, N2, "改用另一预设");
        fails(() -> store.clearPresetReferences(beforeChangedPreset));
        equal(new PhotoProjectPresetStore(directory.toFile()).config(P1).presetId, N2);
        RemovalReferences beforeQueued = store.inspectRemoval(N1); Task pending = task(P2, 21, N1, "queued", "", "", 20); second.queue(pending);
        fails(() -> store.inspectRemoval(N1)); fails(() -> store.clearPresetReferences(beforeQueued));
        second.update(pending.withStatus("processing", "", ""));
        fails(() -> store.inspectRemoval(N1)); fails(() -> store.clearPresetReferences(beforeQueued));
        Task failed = pending.withStatus("error", "", "不能丢失的错误记录");
        failed = new Task(failed.projectId, failed.identity, failed.presetId, failed.presetName, failed.sourceFileName,
                failed.sourceDisplayName, failed.sourceBytes, failed.status, failed.resultFileName, failed.error, Long.MAX_VALUE);
        second.update(failed); fails(() -> store.clearPresetReferences(beforeQueued));
        RemovalReferences withError = store.inspectRemoval(N1); equal(withError.failedTasks, 1);
        Task another = task(P2, 22, N1, "queued", "", "", 22); second.queue(another); second.update(another.withStatus("error", "", "第二个错误"));
        fails(() -> store.clearPresetReferences(withError)); equal(store.inspectRemoval(N1).failedTasks, 2);
        equal(store.clearPresetReferences(store.inspectRemoval(N1)), 1);
        PhotoProjectPresetStore reopened = new PhotoProjectPresetStore(directory.toFile()); equal(reopened.config(P2).presetId, ""); equal(reopened.config(P1).presetId, N2);
        equal(find(reopened, pending.key()).error, "不能丢失的错误记录"); equal(find(reopened, another.key()).error, "第二个错误");
    }

    private static void removalAtomicFailurePreservesDefaults(Path directory) throws Exception {
        PhotoProjectPresetStore store = new PhotoProjectPresetStore(directory.toFile());
        store.setConfig(P1, N1, "不可丢失的配置"); store.setConfig(P2, N1, "同一预设配置");
        Task task = task(P1, 31, N1, "queued", "", "", 31); store.queue(task); store.update(task.withStatus("saved", "preserved-result.JPG", ""));
        byte[] before = Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME));
        PhotoProjectPresetStore failing = new PhotoProjectPresetStore(directory.toFile(), (temporary, target) -> { throw new IOException("synthetic removal commit failure"); });
        RemovalReferences references = failing.inspectRemoval(N1); List<Task> history = failing.allTasks();
        fails(() -> failing.clearPresetReferences(references)); equal(failing.config(P1).presetId, N1); equal(failing.config(P2).presetId, N1);
        yes(failing.allTasks() == history); equal(find(failing, task.key()).resultFileName, "preserved-result.JPG");
        yes(Arrays.equals(before, Files.readAllBytes(directory.resolve(PhotoProjectPresetStore.FILE_NAME))));
        try (var files = Files.list(directory)) { yes(files.noneMatch(path -> path.getFileName().toString().endsWith(".part"))); }
        PhotoProjectPresetStore reopened = new PhotoProjectPresetStore(directory.toFile()); equal(reopened.config(P1).presetName, "不可丢失的配置"); equal(reopened.config(P2).presetId, N1);
        Files.write(directory.resolve(PhotoProjectPresetStore.FILE_NAME), new byte[]{1, 2, 3});
        fails(() -> failing.inspectRemoval(N1)); fails(() -> failing.clearPresetReferences(references));
        equal(failing.config(P1).presetId, N1); equal(find(failing, task.key()).resultFileName, "preserved-result.JPG");
    }
    private static Task find(PhotoProjectPresetStore store, String key) { return store.allTasks().stream().filter(task -> task.key().equals(key)).findFirst().orElseThrow(); }
    private static String hash(int value) { String number = Integer.toHexString(value); return "0".repeat(64 - number.length()) + number; }
    private static void writeTask(DataOutputStream data, Task task, String source, String status) throws Exception {
        data.writeUTF(task.projectId); data.writeUTF(task.identity); data.writeUTF(task.presetId); data.writeUTF(task.presetName); data.writeUTF(source); data.writeUTF(task.sourceDisplayName); data.writeLong(task.sourceBytes);
        data.writeUTF(status); data.writeUTF(task.resultFileName); data.writeUTF(task.error); data.writeLong(task.updatedAt);
    }
    private static byte[] journal(Task... tasks) throws Exception { return framed(payload(data -> { data.writeInt(0); data.writeInt(tasks.length); for (Task task : tasks) writeTask(data, task, task.sourceFileName, task.status); })); }
    private static byte[] payload(Payload build) throws Exception { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (DataOutputStream data = new DataOutputStream(bytes)) { build.write(data); } return bytes.toByteArray(); }
    private static byte[] framed(byte[] payload) throws Exception { return payload(data -> { data.writeInt(0x50505031); data.writeInt(payload.length); data.write(payload); data.write(MessageDigest.getInstance("SHA-256").digest(payload)); }); }
    private static void fails(Work work) throws Exception { try { work.run(); throw new AssertionError("Expected IOException"); } catch (IOException expected) { checks++; } }
    private static void rejects(Work work) throws Exception { try { work.run(); throw new AssertionError("Expected invalid input or immutable list"); } catch (IllegalArgumentException | UnsupportedOperationException expected) { checks++; } }
    private static void yes(boolean value) { if (!value) throw new AssertionError("check " + checks + " failed"); checks++; }
    private static void equal(Object actual, Object expected) { if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual); checks++; }
}

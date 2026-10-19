package com.nikonpreset.usbprobe.album;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Pure Java checks for rename-safe, immutable MediaStore directory bindings. */
public final class PhotoLiveProjectFoldersTest {
    private static final String FIRST = "00000001-0000-0000-0000-000000000001", SECOND = "00000002-0000-0000-0000-000000000002";
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length == 5 && args[0].equals("--bind")) {
            Path gate = Path.of(args[4]); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(gate) && System.nanoTime() < deadline) Thread.sleep(10);
            if (!Files.exists(gate)) throw new AssertionError("process gate timed out");
            System.out.println("BOUND:" + new PhotoLiveProjectFolders(new File(args[1])).bind(args[2], args[3])); return;
        }
        Path root = Files.createTempDirectory("photo-live-folders-test-");
        try {
            firstBindingSurvivesNamesAndRecreation(root.resolve("normal"));
            interruptedBindingRecoversWithoutChangingCommittedPath(root.resolve("interrupt"));
            corruptionAndFailedWritesNeverRebind(root.resolve("damage"));
            rejectsInvalidNamesPathsAndForeignRecords(root.resolve("invalid"));
            concurrentInstancesChooseOneFolder(root.resolve("threads"));
            concurrentProcessesChooseOneFolder(root.resolve("processes"));
            System.out.println("PASS: " + checks + " photo-live project folder checks");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) throw new AssertionError("cleanup escaped test directory");
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void firstBindingSurvivesNamesAndRecreation(Path root) throws Exception {
        PhotoLiveProjectFolders folders = new PhotoLiveProjectFolders(root.toFile());
        String original = folders.bind(FIRST, "  夏日：人像 📷  ");
        equal(original, PhotoLiveProjectStore.galleryFolder(FIRST, "夏日：人像 📷"));
        yes(!original.contains(":")); yes(!original.contains("/")); yes(!original.contains("\\"));
        byte[] committed = Files.readAllBytes(root.resolve(FIRST + ".folder"));
        for (String renamed : new String[] {"旅行", "生日聚会", "😀".repeat(60)}) {
            equal(new PhotoLiveProjectFolders(root.toFile()).bind(FIRST, renamed), original);
            yes(Arrays.equals(committed, Files.readAllBytes(root.resolve(FIRST + ".folder"))));
        }
        String other = folders.bind(SECOND, "夏日：人像 📷");
        yes(!other.equals(original)); yes(other.endsWith(SECOND.substring(0, 8)));
        equal(new PhotoLiveProjectFolders(root.toFile()).bind(SECOND, "更名"), other);
        String symbols = folders.bind("00000003-0000-0000-0000-000000000003", "😀".repeat(60));
        equal(symbols, "项目-00000003");
        yes(!Files.exists(root.resolve(FIRST + ".folder.part")));
    }

    private static void interruptedBindingRecoversWithoutChangingCommittedPath(Path root) throws Exception {
        PhotoLiveProjectFolders folders = new PhotoLiveProjectFolders(root.toFile());
        String original = folders.bind(FIRST, "首次名称");
        Path base = root.resolve(FIRST + ".folder"), pending = root.resolve(FIRST + ".folder.part");
        byte[] committed = Files.readAllBytes(base);
        // Crash after fsync, before the initial rename: recover the complete intent rather than choosing a new name.
        Files.move(base, pending);
        equal(new PhotoLiveProjectFolders(root.toFile()).bind(FIRST, "后续名称"), original);
        yes(Arrays.equals(committed, Files.readAllBytes(base))); yes(!Files.exists(pending));
        // A later abandoned temporary file cannot displace a valid committed binding.
        Files.write(pending, new byte[] {1, 2, 3});
        equal(folders.bind(FIRST, "另外名称"), original);
        yes(Arrays.equals(committed, Files.readAllBytes(base))); yes(!Files.exists(pending));
        Files.write(pending, record(FIRST, "不同意图", PhotoLiveProjectStore.galleryFolder(FIRST, "不同意图")));
        equal(folders.bind(FIRST, "其他"), original);
        yes(Arrays.equals(committed, Files.readAllBytes(base))); yes(!Files.exists(pending));
    }

    private static void corruptionAndFailedWritesNeverRebind(Path root) throws Exception {
        PhotoLiveProjectFolders folders = new PhotoLiveProjectFolders(root.toFile());
        String original = folders.bind(FIRST, "旧名"); Path base = root.resolve(FIRST + ".folder");
        byte[] committed = Files.readAllBytes(base), damaged = committed.clone(); damaged[damaged.length - 1] ^= 1;
        Files.write(base, damaged);
        Path pending = root.resolve(FIRST + ".folder.part"); Files.write(pending, committed);
        fails(() -> folders.bind(FIRST, "新名"));
        yes(Arrays.equals(damaged, Files.readAllBytes(base))); yes(Arrays.equals(committed, Files.readAllBytes(pending)));
        Files.write(base, committed); Files.delete(pending);
        // A non-file at either namespace is an explicit error, never a recursive delete or replacement.
        Files.createDirectory(pending); fails(() -> folders.bind(FIRST, "新名"));
        yes(Files.isDirectory(pending)); yes(Arrays.equals(committed, Files.readAllBytes(base)));
        Files.delete(pending); equal(folders.bind(FIRST, "新名"), original);
        Path secondPending = root.resolve(SECOND + ".folder.part"); Files.write(secondPending, new byte[] {9, 8});
        fails(() -> folders.bind(SECOND, "不会重建"));
        yes(Arrays.equals(new byte[] {9, 8}, Files.readAllBytes(secondPending))); yes(!Files.exists(root.resolve(SECOND + ".folder")));
        Files.delete(secondPending); Files.createDirectory(secondPending); fails(() -> folders.bind(SECOND, "不会重建"));
        yes(Files.isDirectory(secondPending)); yes(Arrays.equals(committed, Files.readAllBytes(base)));
        Files.delete(secondPending); Files.createDirectory(root.resolve(SECOND + ".folder"));
        fails(() -> folders.bind(SECOND, "不会替换")); yes(Files.isDirectory(root.resolve(SECOND + ".folder")));
        Path blocked = root.resolve("blocked"); Files.createDirectories(blocked.resolve(".folders.lock"));
        fails(() -> new PhotoLiveProjectFolders(blocked.toFile()).bind(FIRST, "无法锁定"));
        yes(!Files.exists(blocked.resolve(FIRST + ".folder")));
    }

    private static void rejectsInvalidNamesPathsAndForeignRecords(Path root) throws Exception {
        PhotoLiveProjectFolders folders = new PhotoLiveProjectFolders(root.toFile());
        for (String id : new String[] {null, "", "../escape", "..\\escape", "/escape", "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF", "123"}) {
            fails(() -> folders.bind(id, "项目"));
        }
        for (String name : new String[] {null, "", "  ", ".", "..", "a/../../b", "a\\b", "a\nb", "\nedge", "edge\t", "a\u0000b", "a\u202eb", "\ud800", "a".repeat(61)}) {
            fails(() -> folders.bind(FIRST, name));
        }
        yes(!Files.exists(root.resolve(FIRST + ".folder")));
        fails(() -> new PhotoLiveProjectFolders(null));
        Path nonDirectory = root.resolve("file"); Files.write(nonDirectory, new byte[] {1});
        fails(() -> new PhotoLiveProjectFolders(nonDirectory.toFile()));
        Path base = root.resolve(FIRST + ".folder");
        for (byte[] invalid : new byte[][] {
                record(SECOND, "其他", PhotoLiveProjectStore.galleryFolder(SECOND, "其他")),
                record(FIRST, "正常", "../escape"), record(FIRST, "正常", "其他-00000001"),
                record(FIRST, "a\nb", "ab-00000001"), new byte[1025]}) {
            Files.write(base, invalid); fails(() -> folders.bind(FIRST, "不得覆盖"));
            yes(Arrays.equals(invalid, Files.readAllBytes(base)));
        }
        Files.delete(base);
        // A child symlink must not cause publication to read or alter someone else's binding.
        Path outside = root.resolveSibling(root.getFileName() + "-outside");
        Files.write(outside, record(FIRST, "外部", PhotoLiveProjectStore.galleryFolder(FIRST, "外部")));
        try {
            try { Files.createSymbolicLink(base, outside); }
            catch (UnsupportedOperationException | IOException | SecurityException unavailable) { return; }
            byte[] external = Files.readAllBytes(outside); fails(() -> folders.bind(FIRST, "不得越界"));
            yes(Files.isSymbolicLink(base)); yes(Arrays.equals(external, Files.readAllBytes(outside)));
        } finally { Files.deleteIfExists(base); Files.deleteIfExists(outside); }
    }

    private static void concurrentInstancesChooseOneFolder(Path root) throws Exception {
        Files.createDirectories(root); var executor = Executors.newFixedThreadPool(8); CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                String name = "线程 " + i;
                futures.add(executor.submit(() -> { start.await(); return new PhotoLiveProjectFolders(root.toFile()).bind(FIRST, name); }));
            }
            start.countDown(); String chosen = futures.get(0).get(15, TimeUnit.SECONDS);
            for (var future : futures) equal(future.get(15, TimeUnit.SECONDS), chosen);
            equal(new PhotoLiveProjectFolders(root.toFile()).bind(FIRST, "改名后重开"), chosen);
        } finally { executor.shutdownNow(); yes(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private static void concurrentProcessesChooseOneFolder(Path root) throws Exception {
        Files.createDirectories(root); Path gate = root.resolve("start"); List<Process> processes = new ArrayList<>();
        File runtime = new File(System.getProperty("java.home"), "bin/java.exe");
        if (!runtime.isFile()) runtime = new File(System.getProperty("java.home"), "bin/java");
        try {
            for (int i = 0; i < 4; i++) processes.add(new ProcessBuilder(runtime.getAbsolutePath(), "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"),
                    PhotoLiveProjectFoldersTest.class.getName(), "--bind", root.toString(), FIRST, "进程 " + i, gate.toString())
                    .redirectErrorStream(true).start());
            Files.write(gate, new byte[] {1}); String chosen = null;
            for (Process process : processes) {
                yes(process.waitFor(20, TimeUnit.SECONDS)); equal(process.exitValue(), 0);
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String bound = output.lines().filter(line -> line.startsWith("BOUND:")).findFirst().orElseThrow(() -> new AssertionError(output)).substring(6);
                if (chosen == null) chosen = bound; else equal(bound, chosen);
            }
            equal(new PhotoLiveProjectFolders(root.toFile()).bind(FIRST, "改名"), chosen);
        } finally {
            for (Process process : processes) if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    private static byte[] record(String id, String name, String folder) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(payload)) { data.writeUTF(id); data.writeUTF(name); data.writeUTF(folder); }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(encoded)) {
            data.writeInt(0x504c4631); data.writeInt(payload.size()); data.write(payload.toByteArray());
            data.write(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()));
        }
        return encoded.toByteArray();
    }

    private interface IoAction { void run() throws Exception; }
    private static void fails(IoAction action) throws Exception { try { action.run(); throw new AssertionError("expected IOException"); } catch (IOException expected) { checks++; } }
    private static void yes(boolean value) { if (!value) throw new AssertionError("check " + (checks + 1)); checks++; }
    private static void equal(Object actual, Object expected) { if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected); checks++; }
}

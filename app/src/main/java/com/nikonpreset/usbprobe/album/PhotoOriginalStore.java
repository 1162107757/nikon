package com.nikonpreset.usbprobe.album;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/** Worker-only original completion index. Camera metadata is a lookup hint; verified JPEG bytes are identity. */
public final class PhotoOriginalStore {
    private static final Object LOCK = new Object();
    private static final int MAGIC = 0x504f5331, MAX_FILES = 20000;
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private final File directory, records;
    private final PhotoLiveLedger legacy;

    public PhotoOriginalStore(File cameraAlbumDirectory) throws IOException {
        File expected = cameraAlbumDirectory.getAbsoluteFile(); directory = expected.getCanonicalFile();
        if (!expected.equals(directory) || Files.isSymbolicLink(expected.toPath()) || !directory.isDirectory()) throw new IOException("原图目录无效。");
        File expectedRecords = new File(directory, ".photo-originals"); records = expectedRecords.getCanonicalFile();
        if (!expectedRecords.equals(records) || Files.isSymbolicLink(expectedRecords.toPath()) || !directory.equals(records.getParentFile()) || !records.isDirectory() && !records.mkdir()) throw new IOException("无法创建原图完成记录。");
        legacy = new PhotoLiveLedger(directory);
    }

    /** Reuses a completed original only after checking the exact recorded bytes, including across a new session. */
    public DownloadedPhoto find(String cameraIdentity, String displayName, long expectedBytes) throws IOException {
        if (cameraIdentity == null || cameraIdentity.isEmpty()) return null;
        checkIdentity(cameraIdentity);
        synchronized (LOCK) {
            Record record = read(record("camera-" + cameraIdentity));
            if (record == null) {
                // Migrate old live completion evidence without renaming or moving any existing original.
                DownloadedPhoto old = legacy.completed(cameraIdentity, legacy.destination(cameraIdentity, safeName(displayName)), displayName, expectedBytes);
                if (old == null) return null;
                record = new Record(old.file().getName(), old.filename(), old.byteSize(), contentId(old.file()));
                write(record("camera-" + cameraIdentity), record);
                Record canonical = read(record("content-" + record.sha));
                if (canonical == null || !source(canonical.name).exists()) write(record("content-" + record.sha), record);
                else if (canonical.bytes != record.bytes || !record.sha.equals(contentId(source(canonical.name)))) throw new IOException("原图完成索引发生变化，未更改已有照片关联。");
            }
            File source = source(record.name);
            if (!source.exists()) return null;
            if (expectedBytes > 0 && expectedBytes != record.bytes || source.length() != record.bytes || !record.sha.equals(contentId(source))) {
                throw new IOException("已保存原图与完成记录不符，未覆盖已有照片。");
            }
            return new DownloadedPhoto(source, record.displayName);
        }
    }

    /** Commits a complete received temporary file, reusing an existing byte-identical original if available. */
    public DownloadedPhoto commit(String cameraIdentity, File temporary, String displayName) throws IOException {
        if (cameraIdentity != null && !cameraIdentity.isEmpty()) checkIdentity(cameraIdentity);
        synchronized (LOCK) {
            File input = owned(temporary);
            if (!input.getName().endsWith(".part")) throw new IOException("仅可提交本次接收的原图临时文件。");
            String sha = contentId(input); long bytes = input.length();
            Record existing = read(record("content-" + sha)); File target = null;
            if (existing != null) {
                File previous = source(existing.name);
                if (previous.exists()) {
                    if (previous.length() != bytes || !sha.equals(contentId(previous))) throw new IOException("已保存原图发生变化，未覆盖已有照片。");
                    target = previous;
                }
            }
            if (target == null) {
                // A bounded legacy scan only considers completed originals of the exact length, never effect outputs.
                File[] candidates = directory.listFiles(file -> file.isFile() && isOriginalName(file.getName()) && file.length() == bytes);
                if (candidates == null) throw new IOException("无法核对已有原图。");
                if (candidates.length > MAX_FILES) throw new IOException("原图数量过多，未继续生成重复副本。");
                Arrays.sort(candidates, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
                for (File candidate : candidates) {
                    File checked;
                    try { checked = owned(candidate); }
                    catch (IOException invalid) { continue; }
                    try { if (sha.equals(contentId(checked))) { target = checked; break; } }
                    catch (IOException changedLegacy) { if (Thread.currentThread().isInterrupted()) throw changedLegacy; /* Unrelated damaged legacy files are preserved. */ }
                }
            }
            String name = safeName(displayName);
            if (target == null) {
                target = source("original-" + sha + "__" + name);
                if (target.exists()) {
                    if (target.length() != bytes || !sha.equals(contentId(target))) throw new IOException("原图目标内容不符，未覆盖已有照片。");
                } else {
                    // A deterministic destination recovers a process stop after rename but before the journal commit.
                    Files.move(input.toPath(), target.toPath());
                }
            }
            if (target.length() != bytes || !sha.equals(contentId(target))) throw new IOException("原图在保存过程中发生变化，未确认完成记录。");
            Record completed = new Record(target.getName(), existing != null && existing.name.equals(target.getName()) ? existing.displayName : name, bytes, sha);
            write(record("content-" + sha), completed);
            if (cameraIdentity != null && !cameraIdentity.isEmpty()) write(record("camera-" + cameraIdentity), completed);
            if (input.exists()) Files.delete(input.toPath());
            return new DownloadedPhoto(target, completed.displayName);
        }
    }

    /** Association writers may use a shared legacy path only when the exact completion index proves its bytes. */
    boolean containsCompleted(File file, String sha) throws IOException {
        synchronized (LOCK) {
            File source = owned(file); Record record = read(record("content-" + sha));
            return record != null && record.name.equals(source.getName()) && record.bytes == source.length() && record.sha.equals(sha);
        }
    }

    /** Full, read-only JPEG identity. Intended for bounded local workers, never the UI thread. */
    public static String contentId(File file) throws IOException {
        if (file == null || !file.getAbsoluteFile().equals(file.getCanonicalFile()) || Files.isSymbolicLink(file.toPath()) || !file.isFile()) throw new IOException("原图文件无效。");
        long bytes = file.length(), modified = file.lastModified();
        if (bytes < 4 || bytes > MAX_BYTES) throw new IOException("原图大小无效。");
        PhotoPreviewCache.validateJpeg(file, bytes);
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        long total = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("原图核对已停止。");
                total += count; if (total > bytes) throw new IOException("原图内容发生变化。");
                digest.update(buffer, 0, count);
            }
        }
        if (bytes != total || bytes != file.length() || modified != file.lastModified()) throw new IOException("原图内容发生变化。");
        char[] chars = new char[64], alphabet = "0123456789abcdef".toCharArray(); byte[] hash = digest.digest();
        for (int i = 0; i < hash.length; i++) { chars[i * 2] = alphabet[(hash[i] >>> 4) & 15]; chars[i * 2 + 1] = alphabet[hash[i] & 15]; }
        return new String(chars);
    }

    private File owned(File file) throws IOException {
        File expected = file.getAbsoluteFile(), canonical = expected.getCanonicalFile();
        if (!expected.equals(canonical) || Files.isSymbolicLink(expected.toPath()) || !directory.equals(canonical.getParentFile()) || !canonical.isFile()) throw new IOException("原图路径归属不符。");
        return canonical;
    }
    private File source(String name) throws IOException {
        if (name == null || !new File(name).getName().equals(name) || !isOriginalName(name)) throw new IOException("原图完成记录路径无效。");
        File expected = new File(directory, name), canonical = expected.getCanonicalFile();
        if (!expected.equals(canonical) || Files.isSymbolicLink(expected.toPath()) || !directory.equals(canonical.getParentFile())) throw new IOException("原图完成记录路径无效。");
        return canonical;
    }
    private File record(String name) throws IOException {
        File expected = new File(records, name + ".record"), canonical = expected.getCanonicalFile();
        if (!expected.equals(canonical) || Files.isSymbolicLink(expected.toPath()) || !records.equals(canonical.getParentFile())) throw new IOException("原图完成记录路径无效。");
        return canonical;
    }
    private static boolean isOriginalName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.jpe?g") && !name.matches("(?i)effect-[0-9a-f]{64}__Effect\\.JPG");
    }
    private static String safeName(String name) {
        String clean = name == null ? "Photo.JPG" : name.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        if (clean.length() > 120) clean = clean.substring(0, 116) + ".JPG";
        if (!clean.toLowerCase(Locale.ROOT).matches(".+\\.jpe?g")) clean += ".JPG";
        return clean;
    }
    private static void checkIdentity(String identity) throws IOException { if (!identity.matches("[0-9a-f]{64}")) throw new IOException("相机照片标识无效。"); }
    private static Record read(File file) throws IOException {
        if (!file.exists()) return null;
        if (!file.isFile() || file.length() > 2048) throw new IOException("原图完成记录无效。");
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            if (input.readInt() != MAGIC) throw new IOException("原图完成记录格式无效。");
            String name = input.readUTF(), display = input.readUTF(); long bytes = input.readLong(); String sha = input.readUTF();
            if (name.length() > 256 || display.length() > 256 || bytes < 4 || bytes > MAX_BYTES || !sha.matches("[0-9a-f]{64}") || input.read() != -1) throw new IOException("原图完成记录内容无效。");
            return new Record(name, display, bytes, sha);
        }
    }
    private static void write(File record, Record value) throws IOException {
        File temporary = File.createTempFile(".original-record-", ".part", record.getParentFile());
        try {
            try (FileOutputStream file = new FileOutputStream(temporary); DataOutputStream output = new DataOutputStream(file)) {
                output.writeInt(MAGIC); output.writeUTF(value.name); output.writeUTF(value.displayName); output.writeLong(value.bytes); output.writeUTF(value.sha); output.flush(); file.getFD().sync();
            }
            try { Files.move(temporary.toPath(), record.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary.toPath(), record.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } finally { if (temporary.exists()) Files.delete(temporary.toPath()); }
    }
    private static final class Record {
        final String name, displayName, sha; final long bytes;
        Record(String name, String displayName, long bytes, String sha) { this.name = name; this.displayName = displayName; this.bytes = bytes; this.sha = sha; }
    }
}

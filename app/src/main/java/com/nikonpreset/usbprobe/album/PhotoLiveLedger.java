package com.nikonpreset.usbprobe.album;

import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Worker-owned durable completion records. Stable destination also recovers rename/record crash gaps. */
final class PhotoLiveLedger {
    private static final int MAGIC = 0x504c5631, MAX_RECORDS = 20000;
    private final File directory, journal;
    private final Map<String, Record> records = new LinkedHashMap<>();
    private boolean loaded;
    private static final class Record {
        final String name, sha; final long bytes;
        Record(String name, long bytes, String sha) { this.name = name; this.bytes = bytes; this.sha = sha; }
    }
    PhotoLiveLedger(File directory) throws IOException {
        this.directory = directory.getCanonicalFile(); journal = new File(this.directory, ".photo-live-completed");
    }
    static String identity(String camera, long handle, PtpObjectInfo info) {
        MessageDigest digest = digest();
        digest.update((camera + "\n" + info.storageId() + "\n" + handle + "\n").getBytes(StandardCharsets.UTF_8));
        digest.update(info.toBytes());
        return hex(digest.digest());
    }
    File destination(String key, String safeFilename) throws IOException {
        if (!key.matches("[0-9a-f]{64}")) throw new IOException("直播照片标识无效。");
        File result = new File(directory, "live-" + key + "__" + safeFilename).getCanonicalFile();
        if (!directory.equals(result.getParentFile())) throw new IOException("直播保存路径无效。");
        return result;
    }
    DownloadedPhoto completed(String key, File destination, String filename, long expected) throws IOException {
        load();
        Record record = records.get(key);
        if (record == null && !destination.exists()) return null;
        if (record != null && !record.name.equals(destination.getName())) throw new IOException("直播完成记录与目标不匹配。");
        if (!destination.isFile()) return null; // A user-deleted local file may be received again explicitly.
        PhotoPreviewCache.validateJpeg(destination, expected);
        String observed = sha256(destination);
        if (record != null && (record.bytes != expected || !record.sha.equals(observed))) {
            throw new IOException("已保存的直播照片发生变化；未覆盖，请检查本机文件。");
        }
        if (record == null) record(key, destination, expected, observed);
        return new DownloadedPhoto(destination, filename);
    }
    void complete(String key, File destination, long expected) throws IOException {
        load(); PhotoPreviewCache.validateJpeg(destination, expected);
        record(key, destination, expected, sha256(destination));
    }
    private void record(String key, File destination, long bytes, String sha) throws IOException {
        if (!records.containsKey(key) && records.size() >= MAX_RECORDS) throw new IOException("直播完成记录已达到本版上限。");
        Record previous = records.put(key, new Record(destination.getName(), bytes, sha));
        File temporary = new File(directory, ".photo-live-completed.part");
        try {
            try (FileOutputStream file = new FileOutputStream(temporary);
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(file))) {
                output.writeInt(MAGIC); output.writeInt(records.size());
                for (Map.Entry<String, Record> entry : records.entrySet()) {
                    output.writeUTF(entry.getKey()); output.writeUTF(entry.getValue().name);
                    output.writeLong(entry.getValue().bytes); output.writeUTF(entry.getValue().sha);
                }
                output.flush(); file.getFD().sync();
            }
            try { Files.move(temporary.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), journal.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            if (previous == null) records.remove(key); else records.put(key, previous);
            throw failure;
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    private void load() throws IOException {
        if (loaded) return;
        if (!journal.exists()) { loaded = true; return; }
        if (journal.length() > 16L * 1024 * 1024) throw new IOException("直播完成记录超过上限。");
        Map<String, Record> observed = new LinkedHashMap<>();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(journal)))) {
            if (input.readInt() != MAGIC) throw new IOException("直播完成记录格式无效。");
            int count = input.readInt();
            if (count < 0 || count > MAX_RECORDS) throw new IOException("直播完成记录数量无效。");
            for (int i = 0; i < count; i++) {
                String key = input.readUTF(), name = input.readUTF(); long bytes = input.readLong(); String sha = input.readUTF();
                if (!key.matches("[0-9a-f]{64}") || !sha.matches("[0-9a-f]{64}") || bytes < 4 || bytes > 512L * 1024 * 1024 || name.length() > 256
                        || !new File(name).getName().equals(name) || observed.containsKey(key)) {
                    throw new IOException("直播完成记录内容无效。");
                }
                observed.put(key, new Record(name, bytes, sha));
            }
            if (input.read() != -1) throw new IOException("直播完成记录含多余内容。");
        }
        records.putAll(observed); loaded = true;
    }
    private static String sha256(File file) throws IOException {
        MessageDigest digest = digest(); byte[] buffer = new byte[65536];
        try (FileInputStream input = new FileInputStream(file)) {
            int length; while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        return hex(digest.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}

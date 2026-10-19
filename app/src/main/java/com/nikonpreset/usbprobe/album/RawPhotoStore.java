package com.nikonpreset.usbprobe.album;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Worker-only immutable NEF originals and durable completion evidence, independent of JPEG/effect storage. */
public final class RawPhotoStore {
    private static final Object LOCK = new Object();
    private static final int MAGIC = 0x52415731, MAX_RECORDS = 20000;
    private final File directory, records, lockFile;

    public RawPhotoStore(File directory) throws IOException {
        if (directory == null) throw new IOException("缺少 RAW 原图目录。");
        this.directory = checkedDirectory(directory, true);
        records = checkedDirectory(new File(this.directory, ".raw-originals"), true);
        lockFile = checkedChild(records, ".store.lock");
        if (lockFile.exists() && !lockFile.isFile()) throw new IOException("RAW 原图索引锁无效。");
    }

    public File createTemporary() throws IOException {
        synchronized (LOCK) {
            checkDirectories();
            return File.createTempFile(".raw-", ".part", directory).getCanonicalFile();
        }
    }

    /** A source key is a SHA-256 of stable camera/card/object metadata, never a session handle alone. */
    public RawPhotoDownload find(String sourceIdentity) throws IOException {
        checkHash(sourceIdentity);
        synchronized (LOCK) {
            checkDirectories();
            try (RandomAccessFile lock = new RandomAccessFile(lockFile, "rw"); FileLock ignored = lock.getChannel().lock()) {
                Record record = read(record("source-" + sourceIdentity));
                if (record == null) return null;
                File file = source(record.sha);
                if (!file.exists()) return null;
                Record content = read(record("content-" + record.sha));
                requireSameContent(record, content);
                verify(file, record);
                return download(file, record, sourceIdentity);
            }
        }
    }

    /** Publishes only a complete verified temporary file; existing originals are never overwritten. */
    public RawPhotoDownload commit(String sourceIdentity, File temporary, String displayName) throws IOException {
        checkHash(sourceIdentity);
        synchronized (LOCK) {
            checkDirectories();
            try (RandomAccessFile lock = new RandomAccessFile(lockFile, "rw"); FileLock ignored = lock.getChannel().lock()) {
                File input = ownedTemporary(temporary);
                String sha = fingerprint(input); long bytes = input.length();
                try (RandomAccessFile received = new RandomAccessFile(input, "rw")) { received.getFD().sync(); }
                Record previousSource = read(record("source-" + sourceIdentity));
                if (previousSource != null && (!sha.equals(previousSource.sha) || bytes != previousSource.bytes)) {
                    throw new IOException("相机 RAW 照片标识已关联其他内容，未覆盖已有原图。");
                }
                Record completed = read(record("content-" + sha));
                if (completed != null && completed.bytes != bytes) throw new IOException("RAW 原图完成记录不符。");
                File target = source(sha);
                if (target.exists()) {
                    if (target.length() != bytes || !sha.equals(fingerprint(target))) throw new IOException("已保存 RAW 原图发生变化，未覆盖已有照片。");
                } else {
                    // The same-directory move makes only a complete .NEF visible. A deterministic name
                    // recovers a process death after the move but before the completion record is written.
                    try { Files.move(input.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException unsupported) { Files.move(input.toPath(), target.toPath()); }
                }
                if (completed == null) {
                    completed = new Record(safeName(displayName), bytes, sha,
                            Math.max(1, target.lastModified()), sourceIdentity);
                }
                verify(target, completed);
                write(record("content-" + sha), completed);
                // Also finish the first source association after a crash, even if this retry
                // reaches the same bytes through another camera-object identity.
                write(record("source-" + completed.sourceIdentity), completed);
                Record associated = new Record(completed.name, completed.bytes, completed.sha, completed.savedAt, sourceIdentity);
                write(record("source-" + sourceIdentity), associated);
                if (input.exists()) Files.delete(input.toPath());
                return download(target, associated, sourceIdentity);
            }
        }
    }

    /** Adds a proven JPEG-to-NEF association without another download or a second physical RAW file. */
    public RawPhotoDownload alias(String sourceIdentity, RawPhotoDownload existing) throws IOException {
        checkHash(sourceIdentity);
        if (existing == null) throw new IOException("缺少已保存 RAW 原图。");
        checkHash(existing.sourceIdentity()); checkHash(existing.sha256());
        synchronized (LOCK) {
            checkDirectories();
            try (RandomAccessFile lock = new RandomAccessFile(lockFile, "rw"); FileLock ignored = lock.getChannel().lock()) {
                Record original = read(record("source-" + existing.sourceIdentity()));
                Record content = read(record("content-" + existing.sha256()));
                if (original == null) throw new IOException("RAW 原图缺少来源完成记录。");
                requireSameContent(original, content);
                File file = source(original.sha);
                if (!file.equals(existing.file()) || existing.byteSize() != original.bytes || !existing.sha256().equals(original.sha)
                        || existing.savedAt() != original.savedAt || !existing.filename().equals(original.name)) throw new IOException("RAW 原图关联目标不符。");
                verify(file, original);
                Record associated = new Record(original.name, original.bytes, original.sha, original.savedAt, sourceIdentity);
                write(record("source-" + sourceIdentity), associated);
                return download(file, associated, sourceIdentity);
            }
        }
    }

    /** Returns content-deduplicated, completed originals; partial files and unindexed orphans stay hidden. */
    public List<RawPhotoDownload> list() throws IOException {
        return list(true);
    }

    /** Presentation snapshot only. Every export/find still verifies the complete source bytes. */
    public List<RawPhotoDownload> listForDisplay() throws IOException {
        return list(false);
    }

    private List<RawPhotoDownload> list(boolean verifyBytes) throws IOException {
        synchronized (LOCK) {
            checkDirectories();
            try (RandomAccessFile lock = new RandomAccessFile(lockFile, "rw"); FileLock ignored = lock.getChannel().lock()) {
                File[] entries = records.listFiles(file -> file.getName().matches("content-[0-9a-f]{64}\\.record"));
                if (entries == null || entries.length > MAX_RECORDS) throw new IOException("无法读取 RAW 原图完成记录。");
                List<RawPhotoDownload> result = new ArrayList<>();
                for (File entry : entries) {
                    Record value = read(record(entry.getName().substring(0, entry.getName().length() - 7)));
                    if (value == null || !entry.getName().equals("content-" + value.sha + ".record")) throw new IOException("RAW 原图索引内容不符。");
                    Record associated = read(record("source-" + value.sourceIdentity));
                    if (associated == null) continue; // Commit stopped before the source association became durable.
                    requireSameContent(associated, value);
                    File source = source(value.sha);
                    if (!source.exists()) continue;
                    if (verifyBytes) verify(source, value);
                    else if (!source.isFile() || source.length() != value.bytes) continue;
                    result.add(download(source, value, value.sourceIdentity));
                }
                result.sort(Comparator.comparingLong(RawPhotoDownload::savedAt).reversed().thenComparing(RawPhotoDownload::sha256));
                return java.util.Collections.unmodifiableList(result);
            }
        }
    }

    /** Checks completion evidence as well as exact bytes before publishing an app-owned original. */
    public boolean contains(RawPhotoDownload photo) throws IOException {
        if (photo == null) return false;
        checkHash(photo.sha256()); checkHash(photo.sourceIdentity());
        RawPhotoDownload actual = find(photo.sourceIdentity());
        return actual != null && actual.file().equals(photo.file()) && actual.byteSize() == photo.byteSize()
                && actual.sha256().equals(photo.sha256()) && actual.savedAt() == photo.savedAt() && actual.filename().equals(photo.filename());
    }

    /** Full NEF structural validation plus SHA-256; never decodes, rewrites EXIF or recompresses RAW. */
    public static String fingerprint(File file) throws IOException {
        if (file == null || !file.getAbsoluteFile().equals(file.getCanonicalFile())
                || Files.isSymbolicLink(file.toPath()) || !file.isFile()) throw new IOException("RAW 原图文件无效。");
        long bytes = file.length(), modified = file.lastModified();
        if (bytes < 8 || bytes > PhotoFormat.MAX_RAW_BYTES) throw new IOException("RAW 原图大小无效。");
        PhotoFormat.validateNef(file, bytes);
        MessageDigest digest = digest(); long total = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("RAW 原图核对已停止。");
                total += count; if (total > bytes) throw new IOException("RAW 原图内容发生变化。");
                digest.update(buffer, 0, count);
            }
        }
        if (total != bytes || file.length() != bytes || file.lastModified() != modified) throw new IOException("RAW 原图内容发生变化。");
        return hex(digest.digest());
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static String hex(byte[] value) {
        char[] result = new char[value.length * 2], digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length; i++) { result[i * 2] = digits[(value[i] >>> 4) & 15]; result[i * 2 + 1] = digits[value[i] & 15]; }
        return new String(result);
    }
    private static RawPhotoDownload download(File file, Record value, String sourceIdentity) {
        return new RawPhotoDownload(file, value.name, value.bytes, value.sha, value.savedAt, sourceIdentity);
    }
    private static void requireSameContent(Record value, Record content) throws IOException {
        if (content == null || !value.sha.equals(content.sha) || value.bytes != content.bytes || value.savedAt != content.savedAt
                || !value.name.equals(content.name)) throw new IOException("RAW 原图关联记录不完整。");
    }
    private static void verify(File file, Record value) throws IOException {
        if (!file.isFile() || file.length() != value.bytes || !value.sha.equals(fingerprint(file))) {
            throw new IOException("RAW 原图与完成记录不符，未覆盖已有照片。");
        }
    }
    private void checkDirectories() throws IOException {
        if (!directory.equals(checkedDirectory(directory, false)) || !records.equals(checkedDirectory(records, false))) throw new IOException("RAW 原图目录归属不符。");
        checkedChild(records, lockFile.getName());
    }
    private static File checkedDirectory(File value, boolean create) throws IOException {
        File expected = value.getAbsoluteFile(), canonical = expected.getCanonicalFile();
        if (!expected.equals(canonical) || Files.isSymbolicLink(expected.toPath())) throw new IOException("RAW 原图目录无效。");
        if (create && !canonical.isDirectory() && !canonical.mkdirs() && !canonical.isDirectory()) throw new IOException("无法创建 RAW 原图目录。");
        if (!canonical.isDirectory()) throw new IOException("RAW 原图目录不可用。");
        return canonical;
    }
    private static File checkedChild(File parent, String name) throws IOException {
        File expected = new File(parent, name), canonical = expected.getCanonicalFile();
        if (!expected.equals(canonical) || !parent.equals(canonical.getParentFile()) || Files.isSymbolicLink(expected.toPath())) throw new IOException("RAW 原图路径归属不符。");
        return canonical;
    }
    private File source(String sha) throws IOException { checkHash(sha); return checkedChild(directory, "raw-" + sha + ".NEF"); }
    private File record(String id) throws IOException {
        if (!id.matches("(?:source|content)-[0-9a-f]{64}")) throw new IOException("RAW 原图索引路径无效。");
        return checkedChild(records, id + ".record");
    }
    private File ownedTemporary(File value) throws IOException {
        if (value == null || !directory.equals(value.getAbsoluteFile().getParentFile())) throw new IOException("RAW 临时文件归属不符。");
        File checked = checkedChild(directory, value.getName());
        if (!value.getAbsoluteFile().equals(checked) || !checked.isFile() || !checked.getName().matches("\\.raw-.+\\.part")) throw new IOException("仅可提交本次接收的 RAW 临时文件。");
        return checked;
    }
    private static void checkHash(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IOException("RAW 原图标识无效。");
    }
    private static String safeName(String value) {
        String name = value == null ? "Photo.NEF" : value.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        if (!PhotoFormat.isNefName(name)) name += ".NEF";
        if (name.length() > 100) name = name.substring(0, 96) + ".NEF";
        return name;
    }
    private static Record read(File file) throws IOException {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || file.length() > 2048) throw new IOException("RAW 原图完成记录无效。");
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            if (input.readInt() != MAGIC) throw new IOException("RAW 原图完成记录格式无效。");
            String name = input.readUTF(); long bytes = input.readLong(); String sha = input.readUTF();
            long savedAt = input.readLong(); String identity = input.readUTF();
            checkHash(sha); checkHash(identity);
            if (name.length() > 100 || !PhotoFormat.isNefName(name) || !safeName(name).equals(name)
                    || bytes < 8 || bytes > PhotoFormat.MAX_RAW_BYTES || savedAt < 1 || input.read() != -1) throw new IOException("RAW 原图完成记录内容无效。");
            return new Record(name, bytes, sha, savedAt, identity);
        }
    }
    private static void write(File file, Record value) throws IOException {
        Record existing = read(file);
        if (existing != null) {
            if (!existing.name.equals(value.name) || existing.bytes != value.bytes || !existing.sha.equals(value.sha)
                    || existing.savedAt != value.savedAt || !existing.sourceIdentity.equals(value.sourceIdentity)) throw new IOException("RAW 原图完成记录冲突，未覆盖已有记录。");
            return;
        }
        File temporary = File.createTempFile(".raw-record-", ".part", file.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary); DataOutputStream data = new DataOutputStream(output)) {
                data.writeInt(MAGIC); data.writeUTF(value.name); data.writeLong(value.bytes); data.writeUTF(value.sha);
                data.writeLong(value.savedAt); data.writeUTF(value.sourceIdentity); data.flush(); output.getFD().sync();
            }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary.toPath(), file.toPath()); }
            Record actual = read(file);
            if (actual == null || !actual.sha.equals(value.sha)) throw new IOException("RAW 原图完成记录未确认落盘。");
        } finally { if (temporary.exists()) Files.delete(temporary.toPath()); }
    }
    private static final class Record {
        final String name, sha, sourceIdentity; final long bytes, savedAt;
        Record(String name, long bytes, String sha, long savedAt, String sourceIdentity) {
            this.name = name; this.bytes = bytes; this.sha = sha; this.savedAt = savedAt; this.sourceIdentity = sourceIdentity;
        }
    }
}

package com.nikonpreset.usbprobe.library;

import com.nikonpreset.usbprobe.ncp.NcpFile;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Private content-addressed library. Call disk operations on a worker thread. */
public final class PresetLibrary {
    private final File root;
    private final Map<String, PresetRecord> records = new LinkedHashMap<>();
    private final List<String> diagnostics = new ArrayList<>();

    public static final class AddResult {
        public final PresetRecord record;
        /** False means the exact same content already existed; its metadata is preserved. */
        public final boolean added;
        AddResult(PresetRecord record, boolean added) { this.record = record; this.added = added; }
    }

    public PresetLibrary(File appFilesDir) throws IOException {
        root = new File(appFilesDir, "preset-library");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建本地预设库。");
        load(); migrateLegacy(appFilesDir);
    }

    public synchronized List<PresetRecord> list() {
        List<PresetRecord> result = new ArrayList<>(records.values());
        result.sort(Comparator.comparingLong((PresetRecord p) -> p.addedAt).reversed().thenComparing(p -> p.id));
        return Collections.unmodifiableList(result);
    }

    public synchronized List<String> diagnostics() { return Collections.unmodifiableList(new ArrayList<>(diagnostics)); }
    public synchronized PresetRecord findBySha256(String hash) {
        return hash == null ? null : records.get(hash.toLowerCase(Locale.ROOT));
    }

    public synchronized AddResult importFile(String originalFileName, byte[] bytes) throws IOException {
        String name = safeFileName(originalFileName);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".ncp")) throw new IOException("请选择 NCP 文件。");
        return add(bytes, name, "", "phone", "", "");
    }

    synchronized AddResult addDownloaded(OnlinePreset online, byte[] bytes) throws IOException {
        if (bytes.length != online.byteLength || !LibraryFiles.sha256(bytes).equals(online.sha256))
            throw new IOException("下载文件的大小或 SHA-256 与目录不符，未加入本地库。");
        return add(bytes, safeFileName(online.name + ".ncp"), online.name, "online", "", online.description);
    }

    private AddResult add(byte[] input, String originalName, String displayName, String source,
                          String preview, String description) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("添加预设已取消。");
        if (input == null) throw new IOException("NCP 文件为空。");
        if (input.length > NcpFile.MAX_BYTES) throw new IOException("NCP 文件超过 1 MB。");
        byte[] bytes = input.clone();
        String version;
        try { version = NcpFile.validate(bytes); }
        catch (IllegalArgumentException e) { throw new IOException(e.getMessage(), e); }
        String hash = LibraryFiles.sha256(bytes);
        PresetRecord existing = records.get(hash);
        if (existing != null) { readNcp(existing.id); return new AddResult(existing, false); }
        if (records.size() >= 5000) throw new IOException("本地预设数量超过本版上限。");
        String name = displayName.isEmpty() ? registrationName(bytes, originalName) : displayName;
        PresetRecord record = new PresetRecord(name, originalName, hash, source, preview, description,
                version, bytes.length, System.currentTimeMillis());
        File data = dataFile(hash);
        if (data.exists()) {
            byte[] previous = LibraryFiles.read(data, NcpFile.MAX_BYTES);
            if (!LibraryFiles.sha256(previous).equals(hash)) throw new IOException("已有本地文件校验失败，未覆盖。");
        } else LibraryFiles.atomicWrite(data, bytes);
        // Metadata is the commit marker. An interrupted/failed add can leave only an ignored data orphan.
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("添加预设已取消。");
        saveMetadata(record);
        records.put(hash, record);
        return new AddResult(record, true);
    }

    public synchronized byte[] readNcp(String id) throws IOException {
        String hash = LibraryFiles.requireHash(id);
        PresetRecord record = records.get(hash);
        if (record == null) throw new IOException("本地预设不存在，请重新添加。");
        byte[] bytes = LibraryFiles.read(dataFile(hash), NcpFile.MAX_BYTES);
        if (bytes.length != record.byteLength || !LibraryFiles.sha256(bytes).equals(hash))
            throw new IOException("本地预设校验失败，请重新添加原文件。");
        try { NcpFile.validate(bytes); }
        catch (IllegalArgumentException e) { throw new IOException("本地预设结构检查失败。", e); }
        return bytes;
    }

    public synchronized PresetRecord attachPreview(String id, String localUri) throws IOException {
        String hash = LibraryFiles.requireHash(id);
        PresetRecord previous = records.get(hash);
        if (previous == null) throw new IOException("本地预设不存在。");
        String uri = localUri == null ? "" : localUri.trim();
        if (!uri.isEmpty()) {
            try {
                URI parsed = URI.create(uri);
                if (uri.length() > 4096 || !("content".equalsIgnoreCase(parsed.getScheme())
                        || "file".equalsIgnoreCase(parsed.getScheme()))) throw new IllegalArgumentException();
                for (int i = 0; i < uri.length(); i++) if (uri.charAt(i) < 0x20) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw new IOException("请选择手机中的真实样片。", e); }
        }
        PresetRecord updated = new PresetRecord(previous.displayName, previous.originalFileName, hash,
                previous.source, uri, previous.description, previous.ncpVersion, previous.byteLength, previous.addedAt);
        saveMetadata(updated); records.put(hash, updated); return updated;
    }

    private void saveMetadata(PresetRecord record) throws IOException {
        LibraryFiles.atomicWrite(new File(root, record.id + ".json"),
                LibraryJson.stringify(record.toMap()).getBytes(StandardCharsets.UTF_8));
    }
    private File dataFile(String hash) { return new File(root, hash + ".ncp"); }

    private void load() throws IOException {
        File[] files = root.listFiles((dir, name) -> name.matches("[0-9a-f]{64}\\.json"));
        if (files == null) throw new IOException("无法读取本地预设库。");
        if (files.length > 5000) throw new IOException("本地预设数量超过本版上限。");
        for (File file : files) {
            try {
                PresetRecord record = PresetRecord.fromMap(LibraryJson.object(LibraryJson.parse(
                        LibraryFiles.utf8(LibraryFiles.read(file, 32 * 1024)))));
                if (!file.getName().equals(record.id + ".json")) throw new IOException("元数据文件名不符。");
                records.put(record.id, record);
                try { readNcp(record.id); }
                catch (IOException e) { records.remove(record.id); throw e; }
            } catch (IOException | RuntimeException e) { diagnostics.add("一个本地预设无法恢复：" + e.getMessage()); }
        }
    }

    private void migrateLegacy(File appFilesDir) {
        File selected = new File(appFilesDir, "selected.ncp");
        if (!selected.isFile()) return;
        try {
            byte[] bytes = LibraryFiles.read(selected, NcpFile.MAX_BYTES);
            String originalName = "旧版预设.ncp";
            File metadata = new File(appFilesDir, "preset.json");
            if (metadata.isFile()) {
                Map<String, Object> old = LibraryJson.object(LibraryJson.parse(
                        LibraryFiles.utf8(LibraryFiles.read(metadata, 32 * 1024))));
                String oldHash = LibraryJson.string(old, "sha256", false, 64);
                if (!oldHash.isEmpty() && !LibraryFiles.sha256(bytes).equals(LibraryFiles.requireHash(oldHash)))
                    throw new IOException("旧版文件与记录校验值不符。");
                String oldName = LibraryJson.string(old, "fileName", false, 240);
                if (!oldName.isEmpty()) originalName = safeFileName(oldName);
            }
            AddResult migrated = add(bytes, originalName, "", "legacy", "", "");
            if (migrated.added) diagnostics.add("已将旧版选中的 NCP 加入本地预设库；原文件保留。");
        } catch (IOException | RuntimeException e) { diagnostics.add("旧版预设迁移未完成：" + e.getMessage()); }
    }

    private static String safeFileName(String input) throws IOException {
        if (input == null) throw new IOException("文件名为空。");
        String name = input.replace('\\', '/'); name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.length() > 240) throw new IOException("文件名为空或过长。");
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) < 0x20) throw new IOException("文件名含控制字符。");
        return name;
    }
    private static String registrationName(byte[] bytes, String fileName) {
        int offset = 4;
        // NcpFile.validate already bounded these records; unknown records may precede record 1.
        while (offset + 8 <= bytes.length) {
            long id = uint32be(bytes, offset);
            if (id == 0) break;
            int length = (int) uint32be(bytes, offset + 4); offset += 8;
            if (id == 1) {
                int start = offset + 4, limit = start + 20, end = start;
                while (end < limit && bytes[end] != 0 && bytes[end] >= 0x20 && bytes[end] <= 0x7e) end++;
                if (end > start && (end == limit || bytes[end] == 0)) {
                    String name = new String(bytes, start, end - start, StandardCharsets.US_ASCII).trim();
                    if (!name.isEmpty()) return name;
                }
                break;
            }
            offset += length;
        }
        String fallback = fileName.toLowerCase(Locale.ROOT).endsWith(".ncp")
                ? fileName.substring(0, fileName.length() - 4).trim() : fileName.trim();
        return fallback.isEmpty() ? "NCP 预设" : fallback;
    }
    private static long uint32be(byte[] b, int o) {
        return ((long) (b[o] & 255) << 24) | ((long) (b[o + 1] & 255) << 16)
                | ((long) (b[o + 2] & 255) << 8) | (b[o + 3] & 255);
    }
}

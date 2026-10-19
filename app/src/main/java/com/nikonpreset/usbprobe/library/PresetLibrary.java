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
    private static final Object DISK_LOCK = new Object();
    private final File root;
    private final Map<String, PresetRecord> records = new LinkedHashMap<>();
    private final List<String> diagnostics = new ArrayList<>();

    public static final class AddResult {
        public final PresetRecord record;
        /** False means the exact same content already existed; its metadata is preserved. */
        public final boolean added;
        AddResult(PresetRecord record, boolean added) { this.record = record; this.added = added; }
    }

    public static final class RemoveResult {
        public final PresetRecord record;
        public final List<String> cleanupWarnings;
        RemoveResult(PresetRecord record, List<String> warnings) {
            this.record = record; this.cleanupWarnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }
    }

    public PresetLibrary(File appFilesDir) throws IOException {
        root = new File(appFilesDir, "preset-library");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建本地预设库。");
        synchronized (DISK_LOCK) { load(); recoverRemovedCopies(); migrateLegacy(appFilesDir); }
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
        String lower = name.toLowerCase(Locale.ROOT);
        String format = lower.endsWith(".ncp") ? PresetFile.NCP : lower.endsWith(".np3") ? PresetFile.NP3 : "";
        if (format.isEmpty()) throw new IOException("请选择 NCP 或 NP3 文件。");
        return add(bytes, name, "", "phone", "", "", "", format);
    }

    synchronized AddResult addDownloaded(OnlinePreset online, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length != online.byteLength || !LibraryFiles.sha256(bytes).equals(online.sha256))
            throw new IOException("下载文件的大小或 SHA-256 与目录不符，未加入本地库。");
        String extension = PresetFile.extension(online.fileFormat);
        String name = online.name.toLowerCase(Locale.ROOT).endsWith(extension) ? online.name : online.name + extension;
        return add(bytes, safeFileName(name), online.name, "online", "", online.description, online.category, online.fileFormat);
    }

    private AddResult add(byte[] input, String originalName, String displayName, String source,
                          String preview, String description, String category, String expectedFormat) throws IOException {
        synchronized (DISK_LOCK) { return addLocked(input, originalName, displayName, source, preview, description, category, expectedFormat); }
    }

    private AddResult addLocked(byte[] input, String originalName, String displayName, String source,
                          String preview, String description, String category, String expectedFormat) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("添加预设已取消。");
        if (input == null) throw new IOException("预设文件为空。");
        if (input.length > NcpFile.MAX_BYTES) throw new IOException("预设文件超过 1 MB。");
        byte[] bytes = input.clone();
        PresetFile file = PresetFile.validate(bytes);
        if (!file.fileFormat.equals(PresetFile.requireFormat(expectedFormat)))
            throw new IOException("预设实际格式与文件扩展名或在线目录不符，未加入本地库。");
        String hash = LibraryFiles.sha256(bytes);
        PresetRecord existing = records.get(hash);
        // Same bytes are one local item. Keep all existing metadata, including an explicitly
        // cleared/custom category, rather than silently replacing it with publisher metadata.
        if (existing != null && !removedFile(hash).exists()) { readPreset(existing.id); return new AddResult(existing, false); }
        if (records.size() >= 5000) throw new IOException("本地预设数量超过本版上限。");
        String name = displayName.isEmpty() ? (PresetFile.NP3.equals(file.fileFormat)
                ? (file.registrationName.isEmpty() ? fallbackName(originalName) : file.registrationName)
                : registrationName(bytes, originalName)) : displayName;
        PresetRecord record = new PresetRecord(name, originalName, hash, source, preview, description,
                file.version, bytes.length, System.currentTimeMillis(), PresetCategories.normalize(category), file.fileFormat);
        File data = dataFile(hash, file.fileFormat);
        if (data.exists()) {
            byte[] previous = LibraryFiles.read(data, NcpFile.MAX_BYTES);
            if (!LibraryFiles.sha256(previous).equals(hash)) throw new IOException("已有本地文件校验失败，未覆盖。");
        } else LibraryFiles.atomicWrite(data, bytes);
        // Metadata is the commit marker. An interrupted/failed add can leave only an ignored data orphan.
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("添加预设已取消。");
        saveMetadata(record, true);
        // Explicitly adding verified bytes may restore a previously removed preset. Legacy
        // migration never clears this durable removal marker automatically.
        java.nio.file.Files.deleteIfExists(removedFile(hash).toPath());
        records.put(hash, record);
        return new AddResult(record, true);
    }

    /** Exact source bytes; rendering and camera import apply their own capability checks. */
    public synchronized byte[] readPreset(String id) throws IOException {
        String hash = LibraryFiles.requireHash(id);
        if (removedFile(hash).exists()) throw new IOException("这份本地预设已删除，请重新下载或添加。");
        PresetRecord record = records.get(hash);
        if (record == null) throw new IOException("本地预设不存在，请重新添加。");
        byte[] bytes = LibraryFiles.read(dataFile(hash, record.fileFormat), NcpFile.MAX_BYTES);
        if (bytes.length != record.byteLength || !LibraryFiles.sha256(bytes).equals(hash))
            throw new IOException("本地预设校验失败，请重新添加原文件。");
        PresetFile file = PresetFile.validate(bytes);
        if (!file.fileFormat.equals(record.fileFormat) || !file.version.equals(record.ncpVersion))
            throw new IOException("本地预设的格式或版本与记录不符，请重新添加原文件。");
        return bytes;
    }

    /** Camera conversion stays restricted to the researched legacy NCP 0100 format. */
    public synchronized byte[] readNcp(String id) throws IOException {
        PresetRecord record = records.get(LibraryFiles.requireHash(id));
        if (record == null) throw new IOException("本地预设不存在，请重新添加。");
        if (!PresetFile.NCP.equals(record.fileFormat))
            throw new IOException("此操作仅支持旧版 NCP；NP3 可在相册中选择受支持的配方进行手机调色。");
        return readPreset(record.id);
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
                previous.source, uri, previous.description, previous.ncpVersion, previous.byteLength, previous.addedAt,
                previous.category, previous.fileFormat);
        saveMetadata(updated); records.put(hash, updated); return updated;
    }

    /** Worker-thread API. Commit metadata atomically before publishing the new immutable record. */
    public synchronized PresetRecord setCategory(String id, String category) throws IOException {
        String hash = LibraryFiles.requireHash(id);
        PresetRecord previous = records.get(hash);
        if (previous == null) throw new IOException("本地预设不存在。");
        String normalized = PresetCategories.normalize(category);
        if (normalized.equals(previous.category)) return previous;
        PresetRecord updated = new PresetRecord(previous.displayName, previous.originalFileName, hash,
                previous.source, previous.previewUri, previous.description, previous.ncpVersion,
                previous.byteLength, previous.addedAt, normalized, previous.fileFormat);
        saveMetadata(updated); records.put(hash, updated); return updated;
    }

    /** Cloud metadata applies only to already verified source bytes; sample pictures remain device-local. */
    public synchronized PresetRecord updatePersonalMetadata(String id,Map<String,Object> data)throws IOException {
        PresetRecord previous=records.get(LibraryFiles.requireHash(id));if(previous==null)throw new IOException("预设文件未保存。");
        String name=LibraryJson.string(data,"displayName",true,240),description=LibraryJson.string(data,"description",false,2000);
        String category=PresetCategories.normalize(LibraryJson.string(data,"category",false,24));
        if(previous.displayName.equals(name)&&previous.description.equals(description)&&previous.category.equals(category))return previous;
        PresetRecord next=new PresetRecord(name,previous.originalFileName,id,previous.source,previous.previewUri,description,previous.ncpVersion,previous.byteLength,previous.addedAt,category,previous.fileFormat);
        saveMetadata(next);records.put(id,next);return next;
    }

    private void saveMetadata(PresetRecord record) throws IOException { saveMetadata(record, false); }
    private void saveMetadata(PresetRecord record, boolean explicitAdd) throws IOException {
        synchronized (DISK_LOCK) {
            if (!explicitAdd && removedFile(record.id).exists()) throw new IOException("这份本地预设已删除，未保存更改。");
            LibraryFiles.atomicWrite(new File(root, record.id + ".json"),
                    LibraryJson.stringify(record.toMap()).getBytes(StandardCharsets.UTF_8));
        }
    }
    private File dataFile(String hash, String format) throws IOException { return new File(root, hash + PresetFile.extension(format)); }
    private File removedFile(String hash) { return new File(root, "removed-" + hash + ".json"); }
    public synchronized boolean wasRemoved(String id)throws IOException{return removedFile(LibraryFiles.requireHash(id)).isFile();}

    /** Worker-only refresh after a process-owned download completed through another Activity. */
    public synchronized void refresh() throws IOException {
        synchronized (DISK_LOCK) {
            Map<String, PresetRecord> previous = new LinkedHashMap<>(records); List<String> oldDiagnostics = new ArrayList<>(diagnostics);
            records.clear(); diagnostics.clear();
            try { load(); }
            catch (IOException failure) { records.clear(); records.putAll(previous); diagnostics.clear(); diagnostics.addAll(oldDiagnostics); throw failure; }
        }
    }

    /**
     * A durable removal marker is committed before cleanup. A crash cannot resurrect metadata or
     * the legacy selected.ncp. External source images and generated photographs are never deleted.
     */
    public synchronized RemoveResult remove(String id) throws IOException {
        synchronized (DISK_LOCK) {
            // Other runtime owners may have added/attached a shared sample since this instance loaded.
            refresh();
            String hash = LibraryFiles.requireHash(id); PresetRecord record = records.get(hash);
            if (record == null) throw new IOException("这份本地预设已不存在。");
            if (java.nio.file.Files.isSymbolicLink(root.toPath())) throw new IOException("本地预设目录异常，未删除文件。");
            Map<String, Object> marker = new LinkedHashMap<>(); marker.put("schemaVersion", 1); marker.put("record", record.toMap());
            LibraryFiles.atomicWrite(removedFile(hash), LibraryJson.stringify(marker).getBytes(StandardCharsets.UTF_8));
            records.remove(hash);
            return new RemoveResult(record, cleanupRemovedFiles(record));
        }
    }

    private List<String> cleanupRemovedFiles(PresetRecord record) {
        List<String> warnings = new ArrayList<>();
        cleanupFile(new File(root, record.id + ".json"), warnings);
        try { cleanupFile(dataFile(record.id, record.fileFormat), warnings); } catch (IOException error) { warnings.add("预设文件清理未完成。"); }
        File appFiles = root.getParentFile(); File samples = new File(appFiles, "preset-samples");
        if (!java.nio.file.Files.isSymbolicLink(samples.toPath())) {
            File[] owned = samples.listFiles(file -> file.getName().matches(record.id + "-[0-9]+\\.image(?:\\.bak|\\.new)?"));
            if (owned != null) for (File file : owned) if (!sampleIsShared(file)) cleanupFile(file, warnings);
        }
        try {
            if (record.previewUri.startsWith("file:")) {
                File file = new File(URI.create(record.previewUri)); File parent = file.getCanonicalFile().getParentFile();
                File previewCache = new File(appFiles, "online-presets/previews");
                boolean ownCache = !java.nio.file.Files.isSymbolicLink(previewCache.toPath()) && parent.equals(previewCache.getCanonicalFile())
                        && file.getName().matches("[0-9a-f]{64}\\.image");
                boolean ownSample = !java.nio.file.Files.isSymbolicLink(samples.toPath()) && parent.equals(samples.getCanonicalFile())
                        && file.getName().matches("[0-9a-f]{64}-[0-9]+\\.image");
                if ((ownCache || ownSample) && !sampleIsShared(file)) cleanupFile(file, warnings);
            }
        } catch (IOException | IllegalArgumentException ignored) { /* Not a proven app-owned sample. */ }
        return warnings;
    }

    private boolean sampleIsShared(File file) {
        try {
            File actual = file.getCanonicalFile();
            for (PresetRecord other : records.values()) if (other.previewUri.startsWith("file:")) {
                try { if (new File(URI.create(other.previewUri)).getCanonicalFile().equals(actual)) return true; }
                catch (IllegalArgumentException ignored) { }
            }
        } catch (IOException uncertain) { return true; }
        return false;
    }

    private static void cleanupFile(File file, List<String> warnings) {
        try { java.nio.file.Files.deleteIfExists(file.toPath()); }
        catch (IOException | SecurityException error) { warnings.add("部分本地副本暂未清理，将在下次打开时重试。"); }
    }

    private void recoverRemovedCopies() {
        File[] markers = root.listFiles((dir, name) -> name.matches("removed-[0-9a-f]{64}\\.json"));
        if (markers == null) return;
        for (File marker : markers) try {
            Map<String, Object> data = LibraryJson.object(LibraryJson.parse(LibraryFiles.utf8(LibraryFiles.read(marker, 32 * 1024))));
            if (LibraryJson.integer(data, "schemaVersion") != 1) throw new IOException("删除记录版本无效。");
            PresetRecord record = PresetRecord.fromMap(LibraryJson.object(data.get("record")));
            if (!marker.getName().equals("removed-" + record.id + ".json")) throw new IOException("删除记录身份不符。");
            diagnostics.addAll(cleanupRemovedFiles(record));
        } catch (IOException | RuntimeException failure) { diagnostics.add("一份已删除预设的本地清理记录暂不可读取。"); }
    }

    private void load() throws IOException {
        File[] files = root.listFiles((dir, name) -> name.matches("[0-9a-f]{64}\\.json"));
        if (files == null) throw new IOException("无法读取本地预设库。");
        if (files.length > 5000) throw new IOException("本地预设数量超过本版上限。");
        for (File file : files) {
            try {
                if (removedFile(file.getName().substring(0, 64)).exists()) continue;
                PresetRecord record = PresetRecord.fromMap(LibraryJson.object(LibraryJson.parse(
                        LibraryFiles.utf8(LibraryFiles.read(file, 32 * 1024)))));
                if (!file.getName().equals(record.id + ".json")) throw new IOException("元数据文件名不符。");
                records.put(record.id, record);
                try { readPreset(record.id); }
                catch (IOException e) { records.remove(record.id); throw e; }
            } catch (IOException | RuntimeException e) { diagnostics.add("一个本地预设无法恢复：" + e.getMessage()); }
        }
    }

    private void migrateLegacy(File appFilesDir) {
        File selected = new File(appFilesDir, "selected.ncp");
        if (!selected.isFile()) return;
        try {
            byte[] bytes = LibraryFiles.read(selected, NcpFile.MAX_BYTES);
            if (removedFile(LibraryFiles.sha256(bytes)).exists()) return;
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
            AddResult migrated = add(bytes, originalName, "", "legacy", "", "", "", PresetFile.NCP);
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
        return fallbackName(fileName);
    }
    private static String fallbackName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        String fallback = lower.endsWith(".ncp") || lower.endsWith(".np3")
                ? fileName.substring(0, fileName.length() - 4).trim() : fileName.trim();
        return fallback.isEmpty() ? "未命名预设" : fallback;
    }
    private static long uint32be(byte[] b, int o) {
        return ((long) (b[o] & 255) << 24) | ((long) (b[o + 1] & 255) << 16)
                | ((long) (b[o + 2] & 255) << 8) | (b[o + 3] & 255);
    }
}

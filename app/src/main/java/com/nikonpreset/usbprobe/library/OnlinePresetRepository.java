package com.nikonpreset.usbprobe.library;

import com.nikonpreset.usbprobe.ncp.NcpFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Configurable HTTPS catalog. No endpoint or preset content is bundled or invented. Worker-thread API. */
public final class OnlinePresetRepository {
    public static final int MAX_MANIFEST_BYTES = 512 * 1024;
    public static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;
    private final File root;
    private final PresetLibrary library;
    private final SecureHttpClient http;
    private String endpoint = "";
    private String configurationWarning = "";

    public interface ProgressListener {
        /** totalBytes is -1 when unknown; may throw to cancel without adding a preset. */
        void onProgress(long downloadedBytes, long totalBytes) throws IOException;
    }

    public static final class CatalogResult {
        /** ok, cached, not_configured, no_cache, error */
        public final String status, message;
        public final List<OnlinePreset> presets;
        public final boolean fromCache;
        CatalogResult(String status, String message, List<OnlinePreset> presets, boolean cached) {
            this.status = status; this.message = message;
            this.presets = Collections.unmodifiableList(new ArrayList<>(presets)); this.fromCache = cached;
        }
    }

    public OnlinePresetRepository(File appFilesDir, PresetLibrary library) throws IOException {
        this(appFilesDir, library, new SecureHttpClient());
    }
    OnlinePresetRepository(File appFilesDir, PresetLibrary library, SecureHttpClient http) throws IOException {
        this.library = library; this.http = http; root = new File(appFilesDir, "online-presets");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建在线目录缓存。");
        File config = new File(root, "config.json");
        if (config.isFile()) {
            try {
                Map<String, Object> m = LibraryJson.object(LibraryJson.parse(LibraryFiles.utf8(LibraryFiles.read(config, 8192))));
                if (LibraryJson.integer(m, "schemaVersion") != 1) throw new IOException("配置版本不受支持。");
                String value = LibraryJson.string(m, "endpoint", false, 4096);
                if (!value.isEmpty()) SecureHttpClient.httpsUri(value);
                endpoint = value;
            } catch (IOException | RuntimeException e) { configurationWarning = "在线库地址配置无法读取，请重新配置。"; }
        }
    }

    public synchronized String getEndpoint() { return endpoint; }
    public synchronized void setEndpoint(String value) throws IOException {
        String next = value == null ? "" : value.trim();
        if (!next.isEmpty()) SecureHttpClient.httpsUri(next);
        Map<String, Object> config = new LinkedHashMap<>(); config.put("schemaVersion", 1); config.put("endpoint", next);
        LibraryFiles.atomicWrite(new File(root, "config.json"), LibraryJson.stringify(config).getBytes(StandardCharsets.UTF_8));
        endpoint = next; configurationWarning = "";
    }

    public CatalogResult fetchCatalog() {
        String source;
        synchronized (this) { source = endpoint; }
        if (source.isEmpty()) return result("not_configured", configurationWarning.isEmpty()
                ? "尚未配置在线预设库地址。可配置 HTTPS 目录，或使用我的本地。" : configurationWarning, false);
        try {
            byte[] bytes = http.get(source, MAX_MANIFEST_BYTES, -1, "application/json", null);
            Map<String, Object> manifest = LibraryJson.object(LibraryJson.parse(LibraryFiles.utf8(bytes)));
            List<OnlinePreset> items = parseManifest(manifest);
            Map<String, Object> cache = new LinkedHashMap<>();
            cache.put("schemaVersion", 1); cache.put("endpoint", source);
            Map<String, Object> cleanManifest = new LinkedHashMap<>();
            List<Object> cleanItems = new ArrayList<>();
            for (OnlinePreset item : items) cleanItems.add(item.toMap());
            cleanManifest.put("version", 1); cleanManifest.put("presets", cleanItems);
            cache.put("fetchedAt", System.currentTimeMillis()); cache.put("manifest", cleanManifest);
            LibraryFiles.atomicWrite(new File(root, "catalog.json"), LibraryJson.stringify(cache).getBytes(StandardCharsets.UTF_8));
            return new CatalogResult("ok", items.isEmpty() ? "在线预设库暂无内容。" : "在线预设库已更新。", items, false);
        } catch (IOException | RuntimeException e) {
            return result("error", "在线库暂不可用：" + e.getMessage() + " 已有本地预设仍可使用。", false);
        }
    }

    /** Explicitly open the previous cache. It may belong to an earlier configured endpoint. */
    public CatalogResult cachedCatalog() {
        File file = new File(root, "catalog.json");
        if (!file.isFile()) return result("no_cache", "尚无已缓存的在线目录，请联网加载或使用我的本地。", true);
        try {
            Map<String, Object> cache = LibraryJson.object(LibraryJson.parse(LibraryFiles.utf8(
                    LibraryFiles.read(file, MAX_MANIFEST_BYTES + 32 * 1024))));
            if (LibraryJson.integer(cache, "schemaVersion") != 1) throw new IOException("缓存版本不受支持。");
            SecureHttpClient.httpsUri(LibraryJson.string(cache, "endpoint", true, 4096));
            return new CatalogResult("cached", "正在显示上次缓存的目录；未下载文件仍需联网获取。",
                    parseManifest(LibraryJson.object(cache.get("manifest"))), true);
        } catch (IOException | RuntimeException e) { return result("error", "在线目录缓存不可用，请重新加载。", true); }
    }

    public PresetLibrary.AddResult download(OnlinePreset preset, ProgressListener listener) throws IOException {
        if (preset == null) throw new IOException("尚未选择在线预设。");
        PresetRecord existing = library.findBySha256(preset.sha256);
        if (existing != null) {
            if (existing.byteLength != preset.byteLength) throw new IOException("目录声明的文件大小与本地副本不符。");
            library.readNcp(existing.id);
            if (listener != null) listener.onProgress(preset.byteLength, preset.byteLength);
            return new PresetLibrary.AddResult(existing, false);
        }
        byte[] bytes = http.get(preset.fileUrl, NcpFile.MAX_BYTES, preset.byteLength,
                "application/octet-stream", listener);
        // Validate the complete response, digest and NCP envelope before the commit marker is written.
        return library.addDownloaded(preset, bytes);
    }

    /** Optional sample download. Failure must not be interpreted as an NCP download/import failure. */
    public File downloadPreview(OnlinePreset preset) throws IOException {
        if (preset == null || preset.previewUrl.isEmpty()) throw new IOException("此预设暂无配套样片。");
        String key = LibraryFiles.sha256(preset.previewUrl.getBytes(StandardCharsets.UTF_8));
        File file = new File(root, "previews/" + key + ".image");
        boolean validCache = false;
        if (file.isFile()) {
            try { PreviewImages.validate(LibraryFiles.read(file, MAX_PREVIEW_BYTES)); validCache = true; }
            catch (IOException ignored) { /* Fetch again; do not expose a corrupt cache. */ }
        }
        if (!validCache) {
            byte[] bytes = http.get(preset.previewUrl, MAX_PREVIEW_BYTES, -1, "image/png,image/jpeg,image/webp,image/gif", null);
            PreviewImages.validate(bytes);
            LibraryFiles.atomicWrite(file, bytes);
        }
        PresetRecord record = library.findBySha256(preset.sha256);
        if (record != null && record.previewUri.isEmpty()) library.attachPreview(record.id, file.toURI().toString());
        return file;
    }

    static List<OnlinePreset> parseManifest(Map<String, Object> manifest) throws IOException {
        if (LibraryJson.integer(manifest, "version") != 1) throw new IOException("在线目录版本不受支持。");
        Object raw = manifest.get("presets");
        if (!(raw instanceof List) || ((List<?>) raw).size() > 256)
            throw new IOException("presets 必须为不超过 256 项的数组。");
        List<OnlinePreset> result = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (Object item : (List<?>) raw) {
            OnlinePreset preset = OnlinePreset.fromMap(LibraryJson.object(item));
            if (!ids.add(preset.id)) throw new IOException("在线目录包含重复预设 ID。");
            result.add(preset);
        }
        return result;
    }

    private static CatalogResult result(String status, String message, boolean cache) {
        return new CatalogResult(status, message, Collections.emptyList(), cache);
    }
}

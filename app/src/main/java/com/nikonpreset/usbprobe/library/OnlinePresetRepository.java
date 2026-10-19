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

/** HTTPS catalog. The app explicitly opts into its managed endpoint. Worker-thread API. */
public final class OnlinePresetRepository {
    public static final int MAX_MANIFEST_BYTES = 512 * 1024;
    public static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;
    private static final long MAX_RECIPE_CACHE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_RECIPE_CACHE_FILES = 256;
    private static final Object CONFIGURATION_LOCK = new Object();
    private static final Object RECIPE_CACHE_LOCK = new Object();
    private final Object[] recipeLocks = new Object[16];
    private final File root;
    private final PresetLibrary library;
    private final SecureHttpClient http;
    private String endpoint = "";
    private String configurationWarning = "";
    private String managedEndpoint = "";
    private boolean configurationObserved;

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

    public static final class PageResult {
        public final List<OnlinePreset> presets;
        public final long total;
        public final int page, pageSize;
        public final boolean hasMore;
        public final List<String> categories;
        PageResult(List<OnlinePreset> presets, long total, int page, int pageSize, boolean hasMore, List<String> categories) {
            this.presets = Collections.unmodifiableList(new ArrayList<>(presets)); this.total = total;
            this.page = page; this.pageSize = pageSize; this.hasMore = hasMore;
            this.categories = Collections.unmodifiableList(new ArrayList<>(categories));
        }
    }

    /** Product community paging leaves the legacy manifest and its explicit offline cache intact. */
    public PageResult fetchPage(int page, int pageSize, String category) throws IOException {
        if (page < 1 || page > 100000 || pageSize < 1 || pageSize > 100) throw new IOException("分页参数无效。");
        String source;
        synchronized (this) { source = endpoint; if (managedEndpoint.isEmpty() || source.isEmpty()) throw new IOException("请先启用内置在线预设库。"); }
        java.net.URI endpointUri = SecureHttpClient.httpsUri(source);
        String path = endpointUri.getPath();
        if (!path.endsWith("/catalog.json")) throw new IOException("在线预设分页地址无效。");
        String normalized = PresetCategories.normalize(category);
        String address = endpointUri.resolve(path.substring(0,path.length()-"catalog.json".length()) + "community/feed").toString()
                + "?page=" + page + "&pageSize=" + pageSize + "&category=" + java.net.URLEncoder.encode(normalized, StandardCharsets.UTF_8);
        Map<String,Object> result = LibraryJson.object(LibraryJson.parse(LibraryFiles.utf8(http.get(address,MAX_MANIFEST_BYTES,-1,"application/json",null))));
        Object rows = result.get("items");
        if (!(rows instanceof List) || ((List<?>)rows).size() > pageSize) throw new IOException("在线预设分页响应无效。");
        List<OnlinePreset> presets = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for(Object row:(List<?>)rows) { OnlinePreset item=OnlinePreset.fromMap(LibraryJson.object(row)); if(!ids.add(item.id))throw new IOException("在线分页存在重复预设。");presets.add(item); }
        long total=LibraryJson.integer(result,"total"), actualPage=LibraryJson.integer(result,"page"), actualSize=LibraryJson.integer(result,"pageSize");
        if(total<0||total>9007199254740991L||actualPage!=page||actualSize!=pageSize||!(result.get("hasMore") instanceof Boolean))throw new IOException("在线预设分页响应无效。");
        List<String> categories = new ArrayList<>();Object categoryRows=result.get("categories");
        if(categoryRows!=null){if(!(categoryRows instanceof List)||((List<?>)categoryRows).size()>100)throw new IOException("在线分类响应无效。");for(Object item:(List<?>)categoryRows){if(!(item instanceof String))throw new IOException("在线分类响应无效。");String value=PresetCategories.normalize((String)item);if(!categories.contains(value))categories.add(value);}}
        return new PageResult(presets,total,page,pageSize,Boolean.TRUE.equals(result.get("hasMore")),categories);
    }

    public OnlinePresetRepository(File appFilesDir, PresetLibrary library) throws IOException {
        this(appFilesDir, library, new SecureHttpClient());
    }
    OnlinePresetRepository(File appFilesDir, PresetLibrary library, SecureHttpClient http) throws IOException {
        this.library = library; this.http = http; root = new File(appFilesDir, "online-presets");
        for (int index = 0; index < recipeLocks.length; index++) recipeLocks[index] = new Object();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建在线目录缓存。");
        synchronized (CONFIGURATION_LOCK) { readConfiguration(); }
    }

    private void readConfiguration() {
        File config = new File(root, "config.json");
        if (config.exists()) {
            configurationObserved = true; endpoint = ""; configurationWarning = "";
            try {
                if (!config.isFile()) throw new IOException("在线库配置不是文件。");
                Map<String, Object> m = LibraryJson.object(LibraryJson.parse(LibraryFiles.utf8(LibraryFiles.read(config, 8192))));
                if (LibraryJson.integer(m, "schemaVersion") != 1) throw new IOException("配置版本不受支持。");
                String value = LibraryJson.string(m, "endpoint", false, 4096);
                if (!value.isEmpty()) SecureHttpClient.httpsUri(value);
                endpoint = value;
            } catch (IOException | RuntimeException e) { configurationWarning = "在线库地址配置无法读取，请重新配置。"; }
        }
    }

    public synchronized String getEndpoint() { return endpoint; }

    /**
     * Product-owned catalog: replace legacy custom/disabled settings, preserving downloaded presets.
     * A failed migration leaves this instance closed to all endpoints until a successful retry.
     * Cache reads in this mode never expose a directory from a different service.
     */
    public synchronized void useManagedEndpoint(String value) throws IOException {
        String next = value == null ? "" : value.trim();
        if (next.isEmpty()) throw new IOException("内置在线库地址不能为空。");
        SecureHttpClient.httpsUri(next);
        synchronized (CONFIGURATION_LOCK) {
            managedEndpoint = next;
            endpoint = "";
            configurationWarning = "在线预设暂时无法启用，请稍后重新加载。本地预设仍可使用。";
            File config = new File(root, "config.json");
            boolean unchanged = false;
            if (config.exists()) {
                if (!config.isFile()) throw new IOException("在线库配置不是文件。");
                // Read failures must surface; only a readable legacy document may be replaced.
                byte[] bytes = LibraryFiles.read(config, 8192);
                try {
                    Map<String, Object> saved = LibraryJson.object(LibraryJson.parse(LibraryFiles.utf8(bytes)));
                    unchanged = LibraryJson.integer(saved, "schemaVersion") == 1
                            && next.equals(LibraryJson.string(saved, "endpoint", false, 4096));
                } catch (IOException | RuntimeException ignored) { /* Replace readable legacy settings. */ }
            }
            if (!unchanged) writeConfiguration(next);
            endpoint = next;
            configurationWarning = "";
            configurationObserved = true;
        }
    }

    /** Initialize only a never-configured installation. Explicitly disabled or unreadable settings are retained. */
    public synchronized boolean initializeDefaultEndpoint(String value) throws IOException {
        synchronized (CONFIGURATION_LOCK) {
            // Another Activity/repository may have saved a choice after this instance was constructed.
            if (new File(root, "config.json").exists()) { readConfiguration(); return false; }
            if (configurationObserved || !configurationWarning.isEmpty()) return false;
            String next = value == null ? "" : value.trim();
            if (next.isEmpty()) throw new IOException("默认在线库地址不能为空。");
            setEndpoint(next);
            return true;
        }
    }

    public synchronized void setEndpoint(String value) throws IOException {
        String next = value == null ? "" : value.trim();
        if (!next.isEmpty()) SecureHttpClient.httpsUri(next);
        if (!managedEndpoint.isEmpty() && !managedEndpoint.equals(next))
            throw new IOException("此在线库由应用统一管理。");
        synchronized (CONFIGURATION_LOCK) {
            writeConfiguration(next);
            endpoint = next; configurationWarning = ""; configurationObserved = true;
        }
    }

    private void writeConfiguration(String next) throws IOException {
        Map<String, Object> config = new LinkedHashMap<>(); config.put("schemaVersion", 1); config.put("endpoint", next);
        LibraryFiles.atomicWrite(new File(root, "config.json"), LibraryJson.stringify(config).getBytes(StandardCharsets.UTF_8));
    }

    public CatalogResult fetchCatalog() {
        String source, warning;
        synchronized (this) { source = endpoint; warning = configurationWarning; }
        if (source.isEmpty()) return result("not_configured", warning.isEmpty()
                ? "尚未配置在线预设库地址。可配置 HTTPS 目录，或使用我的本地。" : warning, false);
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

    /** Explicit cache read. Managed catalogs only expose a cache from that managed endpoint. */
    public CatalogResult cachedCatalog() {
        String managed, source, warning;
        synchronized (this) { managed = managedEndpoint; source = endpoint; warning = configurationWarning; }
        if (!managed.isEmpty() && source.isEmpty()) return result("no_cache", warning, true);
        File file = new File(root, "catalog.json");
        if (!file.isFile()) return result("no_cache", "尚无已缓存的在线目录，请联网加载或使用我的本地。", true);
        try {
            Map<String, Object> cache = LibraryJson.object(LibraryJson.parse(LibraryFiles.utf8(
                    LibraryFiles.read(file, MAX_MANIFEST_BYTES + 32 * 1024))));
            if (LibraryJson.integer(cache, "schemaVersion") != 1) throw new IOException("缓存版本不受支持。");
            String cachedEndpoint = LibraryJson.string(cache, "endpoint", true, 4096);
            SecureHttpClient.httpsUri(cachedEndpoint);
            if (!managed.isEmpty() && !managed.equals(cachedEndpoint))
                return result("no_cache", "尚无内置在线预设库的缓存，请联网加载。本地预设仍可使用。", true);
            return new CatalogResult("cached", "正在显示上次缓存的目录；未下载文件仍需联网获取。",
                    parseManifest(LibraryJson.object(cache.get("manifest"))), true);
        } catch (IOException | RuntimeException e) { return result("error", "在线目录缓存不可用，请重新加载。", true); }
    }

    public PresetLibrary.AddResult download(OnlinePreset preset, ProgressListener listener) throws IOException {
        if (preset == null) throw new IOException("尚未选择在线预设。");
        PresetRecord existing = library.findBySha256(preset.sha256);
        if (existing != null) {
            if (existing.byteLength != preset.byteLength) throw new IOException("目录声明的文件大小与本地副本不符。");
            if (!existing.fileFormat.equals(preset.fileFormat)) throw new IOException("目录声明的预设格式与本地副本不符。");
            library.readPreset(existing.id);
            if (listener != null) listener.onProgress(preset.byteLength, preset.byteLength);
            return new PresetLibrary.AddResult(existing, false);
        }
        byte[] bytes = readCachedRecipe(preset);
        if (bytes == null) bytes = http.get(preset.fileUrl, NcpFile.MAX_BYTES, preset.byteLength,
                "application/octet-stream", listener);
        else if (listener != null) listener.onProgress(bytes.length, bytes.length);
        // Validate the complete response, digest and declared container before writing the commit marker.
        return library.addDownloaded(preset, bytes);
    }

    /**
     * Worker-only, read-only recipe access for online colour previews. This cache is not the
     * user's library: viewing a card must never create a PresetRecord or change download state.
     */
    public byte[] readForPreview(OnlinePreset preset) throws IOException { return readForPreview(preset, null); }

    public byte[] readForPreview(OnlinePreset preset, ProgressListener listener) throws IOException {
        if (preset == null) throw new IOException("尚未选择在线预设。");
        Object lock = recipeLocks[(preset.sha256.hashCode() & Integer.MAX_VALUE) % recipeLocks.length];
        synchronized (lock) {
            if (listener != null) listener.onProgress(0, preset.byteLength);
            PresetRecord existing = library.findBySha256(preset.sha256);
            if (existing != null) {
                byte[] local = library.readPreset(existing.id);
                validateRecipe(preset, local);
                if (listener != null) listener.onProgress(local.length, local.length);
                return local;
            }
            byte[] bytes = readCachedRecipe(preset);
            if (bytes == null) {
                bytes = http.get(preset.fileUrl, NcpFile.MAX_BYTES, preset.byteLength, "application/octet-stream", listener);
                validateRecipe(preset, bytes);
                synchronized (RECIPE_CACHE_LOCK) {
                    LibraryFiles.atomicWrite(recipeFile(preset), bytes);
                    trimRecipeCache(recipeFile(preset));
                }
            }
            if (listener != null) listener.onProgress(bytes.length, bytes.length);
            return bytes;
        }
    }

    private File recipeFile(OnlinePreset preset) {
        return new File(root, "recipes/" + preset.sha256 + (PresetFile.NP3.equals(preset.fileFormat) ? ".np3" : ".ncp"));
    }

    private byte[] readCachedRecipe(OnlinePreset preset) throws IOException {
        synchronized (RECIPE_CACHE_LOCK) {
            File file = recipeFile(preset);
            if (!file.isFile()) return null;
            try {
                byte[] bytes = LibraryFiles.read(file, NcpFile.MAX_BYTES);
                validateRecipe(preset, bytes);
                file.setLastModified(System.currentTimeMillis());
                return bytes;
            } catch (IOException invalidCache) { return null; }
        }
    }

    private static void validateRecipe(OnlinePreset preset, byte[] bytes) throws IOException {
        if (bytes.length != preset.byteLength || !LibraryFiles.sha256(bytes).equals(preset.sha256))
            throw new IOException("在线预设的大小或 SHA-256 与目录不符，未生成效果。");
        if (!PresetFile.validate(bytes).fileFormat.equals(preset.fileFormat))
            throw new IOException("在线预设实际格式与目录不符，未生成效果。");
    }

    /** Only recognized cache payloads are candidates; user files and metadata are never touched. */
    private void trimRecipeCache(File keep) {
        File[] files = keep.getParentFile().listFiles(file -> file.isFile() && file.getName().matches("[0-9a-f]{64}\\.(ncp|np3)"));
        if (files == null) return;
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        long bytes = 0; for (File file : files) bytes += file.length();
        int count = files.length;
        for (File file : files) {
            if (bytes <= MAX_RECIPE_CACHE_BYTES && count <= MAX_RECIPE_CACHE_FILES) break;
            if (file.equals(keep)) continue;
            long length = file.length();
            if (file.delete()) { bytes -= length; count--; }
        }
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

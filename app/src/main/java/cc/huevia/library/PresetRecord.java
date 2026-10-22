package cc.huevia.library;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** A locally committed preset. Presence says nothing about rendering or a completed camera import. */
public final class PresetRecord {
    public final String id, displayName, originalFileName, sha256, source, previewUri, description, ncpVersion, category, fileFormat;
    public final int byteLength;
    public final long addedAt;

    PresetRecord(String name, String originalName, String hash, String source, String preview,
                 String description, String version, int length, long addedAt) {
        this(name, originalName, hash, source, preview, description, version, length, addedAt, "");
    }
    PresetRecord(String name, String originalName, String hash, String source, String preview,
                 String description, String version, int length, long addedAt, String category) {
        this(name, originalName, hash, source, preview, description, version, length, addedAt, category, PresetFile.NCP);
    }
    PresetRecord(String name, String originalName, String hash, String source, String preview,
                 String description, String version, int length, long addedAt, String category, String fileFormat) {
        this.id = hash; this.displayName = name; this.originalFileName = originalName;
        this.sha256 = hash; this.source = source; this.previewUri = preview;
        this.description = description; this.ncpVersion = version; this.category = category; this.fileFormat = fileFormat;
        this.byteLength = length; this.addedAt = addedAt;
    }
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", 1); map.put("id", id); map.put("displayName", displayName);
        map.put("originalFileName", originalFileName); map.put("sha256", sha256); map.put("source", source);
        map.put("previewUri", previewUri); map.put("description", description); map.put("ncpVersion", ncpVersion);
        map.put("byteLength", byteLength); map.put("addedAt", addedAt); map.put("category", category); map.put("fileFormat", fileFormat);
        return map;
    }
    static PresetRecord fromMap(Map<String, Object> m) throws IOException {
        if (LibraryJson.integer(m, "schemaVersion") != 1) throw new IOException("本地预设元数据版本不受支持。");
        String hash = LibraryFiles.requireHash(LibraryJson.string(m, "sha256", true, 64));
        if (!hash.equals(LibraryJson.string(m, "id", true, 64))) throw new IOException("预设 ID 与校验值不符。");
        long length = LibraryJson.integer(m, "byteLength");
        if (length < 1 || length > cc.huevia.ncp.NcpFile.MAX_BYTES) throw new IOException("预设大小无效。");
        long addedAt = LibraryJson.integer(m, "addedAt");
        if (addedAt < 0) throw new IOException("预设时间无效。");
        return new PresetRecord(LibraryJson.string(m, "displayName", true, 240),
                LibraryJson.string(m, "originalFileName", true, 240), hash,
                LibraryJson.string(m, "source", true, 24), LibraryJson.string(m, "previewUri", false, 4096),
                LibraryJson.string(m, "description", false, 2000), LibraryJson.string(m, "ncpVersion", true, 16),
                (int) length, addedAt, PresetCategories.fromMap(m), PresetFile.formatFromMap(m));
    }
}

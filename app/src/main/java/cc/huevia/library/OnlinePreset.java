package cc.huevia.library;

import cc.huevia.ncp.NcpFile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated immutable catalog item; compatibility labels are publisher claims, not device verification. */
public final class OnlinePreset {
    public final String id, name, description, fileUrl, sha256, previewUrl, category;
    public final String authorName, source, fileFormat;
    public final int byteLength;
    public final List<String> compatibleModels;

    private OnlinePreset(String id, String name, String description, String fileUrl, String hash,
                         int length, String previewUrl, List<String> models, String category, String authorName, String source, String fileFormat) {
        this.id = id; this.name = name; this.description = description; this.fileUrl = fileUrl;
        this.sha256 = hash; this.byteLength = length; this.previewUrl = previewUrl; this.category = category;
        this.compatibleModels = Collections.unmodifiableList(new ArrayList<>(models));
        this.authorName = authorName; this.source = source; this.fileFormat = fileFormat;
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); m.put("description", description); m.put("fileUrl", fileUrl);
        m.put("sha256", sha256); m.put("byteLength", byteLength); m.put("previewUrl", previewUrl);
        m.put("compatibleModels", compatibleModels); m.put("category", category); m.put("authorName", authorName); m.put("source", source);
        m.put("fileFormat", fileFormat); return m;
    }

    public static OnlinePreset fromMap(Map<String, Object> m) throws IOException {
        String fileUrl = LibraryJson.string(m, "fileUrl", true, 4096);
        SecureHttpClient.httpsUri(fileUrl);
        String preview = LibraryJson.string(m, "previewUrl", false, 4096);
        if (!preview.isEmpty()) SecureHttpClient.httpsUri(preview);
        long length = LibraryJson.integer(m, "byteLength");
        if (length < 8 || length > NcpFile.MAX_BYTES) throw new IOException("在线预设大小须为 8 字节至 1 MB。");
        Object modelsValue = m.get("compatibleModels");
        List<String> models = new ArrayList<>();
        if (modelsValue != null) {
            if (!(modelsValue instanceof List) || ((List<?>) modelsValue).size() > 32)
                throw new IOException("compatibleModels 必须为不超过 32 项的字符串数组。");
            for (Object model : (List<?>) modelsValue) {
                if (!(model instanceof String) || ((String) model).trim().isEmpty() || ((String) model).length() > 80)
                    throw new IOException("compatibleModels 包含无效机型名称。");
                for (int i = 0; i < ((String) model).length(); i++) if (((String) model).charAt(i) < 0x20)
                    throw new IOException("机型名称包含控制字符。");
                models.add((String) model);
            }
        }
        String author = LibraryJson.string(m, "authorName", false, 160);
        if (author.isEmpty() && m.get("author") instanceof Map) {
            @SuppressWarnings("unchecked") Map<String,Object> authorMap = (Map<String,Object>)m.get("author");
            author = LibraryJson.string(authorMap, "nickname", false, 160);
        }
        return new OnlinePreset(LibraryJson.string(m, "id", true, 128), LibraryJson.string(m, "name", true, 160),
                LibraryJson.string(m, "description", false, 2000), fileUrl,
                LibraryFiles.requireHash(LibraryJson.string(m, "sha256", true, 64)), (int) length, preview, models,
                PresetCategories.fromMap(m), author, LibraryJson.string(m, "source", false, 24), PresetFile.formatFromMap(m));
    }
}

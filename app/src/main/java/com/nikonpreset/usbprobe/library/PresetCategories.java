package com.nikonpreset.usbprobe.library;

import java.io.IOException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Shared category contract. Empty storage values are presented as 未分类 by the UI. */
public final class PresetCategories {
    public static final int MAX_CODE_POINTS = 24;
    public static final String ALL = "全部";
    public static final String UNCATEGORIZED = "未分类";
    public static final List<String> SUGGESTED = Collections.unmodifiableList(Arrays.asList(
            "人像", "风景", "胶片", "黑白", "日常", "夜景"));

    private PresetCategories() {}

    /** Reject original controls, then NFC + ECMAScript trim, with at most 24 Unicode code points. */
    public static String normalize(String input) throws IOException {
        if (input == null) return "";
        for (int i = 0; i < input.length();) {
            int cp = input.codePointAt(i);
            if (Character.isISOControl(cp)) throw new IOException("分类不能包含控制字符。");
            if (cp >= 0xd800 && cp <= 0xdfff) throw new IOException("分类包含无效文字。");
            i += Character.charCount(cp);
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFC);
        int start = 0, end = normalized.length();
        while (start < end && isTrimSpace(normalized.codePointAt(start)))
            start += Character.charCount(normalized.codePointAt(start));
        while (start < end && isTrimSpace(normalized.codePointBefore(end)))
            end -= Character.charCount(normalized.codePointBefore(end));
        String value = normalized.substring(start, end);
        if (value.codePointCount(0, value.length()) > MAX_CODE_POINTS)
            throw new IOException("分类最多可输入 24 个字。");
        if (ALL.equals(value) || UNCATEGORIZED.equals(value))
            throw new IOException("“全部”和“未分类”是筛选名称，请使用其他分类名。");
        return value;
    }

    static String fromMap(Map<String, Object> map) throws IOException {
        if (!map.containsKey("category")) return ""; // Catalogs and local metadata created before categories.
        Object value = map.get("category");
        if (value == null) return "";
        if (!(value instanceof String)) throw new IOException("分类必须是文字。");
        return normalize((String) value);
    }

    public static String label(String category) {
        return category == null || category.isEmpty() ? UNCATEGORIZED : category;
    }

    private static boolean isTrimSpace(int cp) {
        // Keep this in sync with the server's ECMAScript String.trim, not Java String.trim/strip.
        return cp >= 0x09 && cp <= 0x0d || cp == 0x20 || cp == 0xa0 || cp == 0x1680
                || cp >= 0x2000 && cp <= 0x200a || cp == 0x2028 || cp == 0x2029
                || cp == 0x202f || cp == 0x205f || cp == 0x3000 || cp == 0xfeff;
    }
}

package cc.huevia.album;

import cc.huevia.ptp.PtpObjectInfo;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Conservative matching only within one verified camera inventory. Never a filename-only guess. */
final class RawPhotoPairing {
    /** Automatic pairing stopped safely; the user may explicitly choose an inventory entry. */
    static final class SelectionRequiredException extends IOException {
        SelectionRequiredException(String message) { super(message); }
    }

    static PhotoItem unique(PtpObjectInfo jpeg, List<PhotoItem> rawFiles, boolean trustedCamera) throws IOException {
        return unique(jpeg, rawFiles, trustedCamera, rawFiles.size());
    }
    static PhotoItem unique(PtpObjectInfo jpeg, List<PhotoItem> rawFiles, boolean trustedCamera, int objectCount) throws IOException {
        int nefCount = 0;
        for (PhotoItem raw : rawFiles) if (raw != null && raw.isRaw()) nefCount++;
        String inventory = inventorySummary(nefCount, objectCount);
        if (!trustedCamera) throw new SelectionRequiredException(inventory + "当前相机未提供可核对的设备身份，请在 RAW 文件列表明确选择原文件。");
        if (nefCount == 0) throw new SelectionRequiredException(inventory + "当前连接未列出 NEF 原文件。请在相机确认 RAW 文件后刷新 RAW 文件列表；这不代表存储卡没有 RAW。");
        if (jpeg == null || PhotoFormat.of(jpeg) != PhotoFormat.JPEG || !captureDate(jpeg.captureDate())) throw unmatched(inventory);
        String stem = stem(jpeg.filename()); PhotoItem match = null;
        for (PhotoItem raw : rawFiles) if (raw != null && raw.isRaw() && stem.equals(stem(raw.filename()))) {
            // Even a differently dated second object is ambiguous: camera names wrap,
            // multiple cards can contain independent histories, and no file is guessed.
            if (match != null) throw new SelectionRequiredException(inventory + "相机中有多个同名 RAW 文件，请在 RAW 文件列表明确选择原文件。");
            match = raw;
        }
        if (match == null) throw new SelectionRequiredException(inventory + "已找到 NEF，但无法对应这张 JPG 的文件名，请在 RAW 文件列表明确选择原文件。");
        PtpObjectInfo raw = match.objectInfo();
        if (!jpeg.captureDate().equals(raw.captureDate())) throw unmatched(inventory);
        if (jpeg.storageId() == raw.storageId() && jpeg.parentObject() != raw.parentObject()) throw unmatched(inventory);
        if (jpeg.sequenceNumber() != 0 && raw.sequenceNumber() != 0 && jpeg.sequenceNumber() != raw.sequenceNumber()) throw unmatched(inventory);
        // Cross-card pairs are allowed only after an all-card inventory proves
        // global uniqueness, identical capture time, and this same trusted device.
        return match;
    }
    private static boolean captureDate(String value) {
        if (value == null || !value.matches("[0-9]{8}T[0-9]{6}(?:\\.[0-9]+)?(?:Z|[+-][0-9]{4})?")) return false;
        try {
            java.time.LocalDateTime.parse(value.substring(0, 15), java.time.format.DateTimeFormatter
                    .ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT).withResolverStyle(java.time.format.ResolverStyle.STRICT));
            return true;
        } catch (java.time.DateTimeException invalid) { return false; }
    }
    private static String stem(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.'); return (dot < 0 ? name : name.substring(0, dot)).toUpperCase(Locale.ROOT);
    }
    static String inventorySummary(int nefCount, int objectCount) {
        return "本次核对 " + Math.max(0, objectCount) + " 个对象，列出 " + Math.max(0, nefCount) + " 个 NEF。";
    }
    private static IOException unmatched(String inventory) { return new SelectionRequiredException(inventory + "无法唯一核对这张照片的 RAW 拍摄信息，请在 RAW 文件列表明确选择原文件。"); }
}

package com.nikonpreset.usbprobe.album;

import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Conservative matching only within one verified camera inventory. Never a filename-only guess. */
final class RawPhotoPairing {
    static PhotoItem unique(PtpObjectInfo jpeg, List<PhotoItem> rawFiles, boolean trustedCamera) throws IOException {
        if (!trustedCamera) throw new IOException("当前相机未提供可核对的设备身份，请在 RAW 文件列表明确选择原文件。");
        if (jpeg == null || PhotoFormat.of(jpeg) != PhotoFormat.JPEG || !captureDate(jpeg.captureDate())) throw unmatched();
        String stem = stem(jpeg.filename()); PhotoItem match = null;
        for (PhotoItem raw : rawFiles) if (raw != null && raw.isRaw() && stem.equals(stem(raw.filename()))) {
            // Even a differently dated second object is ambiguous: camera names wrap,
            // multiple cards can contain independent histories, and no file is guessed.
            if (match != null) throw new IOException("相机中有多个同名 RAW 文件，请在 RAW 文件列表明确选择原文件。");
            match = raw;
        }
        if (match == null) throw new IOException("相机中未找到这张照片的 NEF 原文件。请确认拍摄时已开启 RAW 或 RAW+JPG，或在 RAW 文件列表选择。");
        PtpObjectInfo raw = match.objectInfo();
        if (!jpeg.captureDate().equals(raw.captureDate())) throw unmatched();
        if (jpeg.storageId() == raw.storageId() && jpeg.parentObject() != raw.parentObject()) throw unmatched();
        if (jpeg.sequenceNumber() != 0 && raw.sequenceNumber() != 0 && jpeg.sequenceNumber() != raw.sequenceNumber()) throw unmatched();
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
    private static IOException unmatched() { return new IOException("无法唯一核对这张照片的 RAW 拍摄信息，请在 RAW 文件列表明确选择原文件。"); }
}

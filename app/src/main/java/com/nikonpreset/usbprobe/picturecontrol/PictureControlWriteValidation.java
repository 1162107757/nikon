package com.nikonpreset.usbprobe.picturecontrol;

import java.io.IOException;

/** Transport-independent strict validation shared by wired and wireless registrations. */
public final class PictureControlWriteValidation {
    private PictureControlWriteValidation() { }

    /** Strict write validation, separate from the permissive read-only snapshot decoder. */
    public static PictureControlData validateConvertedData(byte[] bytes) throws IOException {
        PictureControlData data = PictureControlData.parse(bytes);
        int base = data.basePictureControl();
        if (base < 1 || base > 6) throw new IOException("登记数据仅支持已核实的基础优化校准 1–6。");
        if (data.registrationName().trim().isEmpty()) throw new IOException("登记名称不能为空。");
        for (char c : data.registrationName().toCharArray()) {
            if (c < 32 || c > 126) throw new IOException("登记名称必须是可打印 ASCII 字符。");
        }
        if (data.applyLevel() != 0 || data.quickSharpFlag() > 1) throw new IOException("不支持的优化校准控制标志。");
        boolean quickAuto = data.quickSharpFlag() == 1 && data.quickSharp() == -128;
        if (!quickAuto) range(data.quickSharp(), -2, 2, "QuickSharp");
        rangeOrAuto(data.sharpening(), -12, 36, quickAuto, "Sharpening");
        rangeOrAuto(data.middleRangeSharpening(), -20, 20, quickAuto, "MiddleRangeSharpening");
        rangeOrAuto(data.clarity(), -20, 20, quickAuto, "Clarity");
        rangeOrAuto(data.contrast(), -12, 12, true, "Contrast");
        range(data.brightness(), -6, 6, "Brightness");
        if (base == 4) {
            if (data.saturation() != 0 || data.hue() != 0) throw new IOException("单色优化校准的色彩字段必须为零。");
            range(data.filterEffects(), 0, 4, "FilterEffects");
            range(data.toning(), 0, 9, "Toning");
            range(data.toningDensity(), 0, 28, "ToningDensity");
        } else {
            rangeOrAuto(data.saturation(), -12, 12, true, "Saturation");
            range(data.hue(), -12, 12, "Hue");
            if (data.filterEffects() != 0 || data.toning() != 0 || data.toningDensity() != 0) {
                throw new IOException("彩色基础优化校准不支持单色滤镜或调色字段。");
            }
        }
        int expectedLength = data.customCurveEnabled() ? 614 : 36;
        if (bytes.length != expectedLength) throw new IOException("曲线标志与登记数据长度不匹配。");
        if (data.customCurveEnabled() && bytes[36] == 0 && bytes[37] == 0) {
            throw new IOException("自定义曲线缺少有效的主机头标识。");
        }
        return data;
    }

    private static void rangeOrAuto(int value, int min, int max, boolean auto, String field) throws IOException {
        if (auto && value == -128) return;
        range(value, min, max, field);
    }
    private static void range(int value, int min, int max, String field) throws IOException {
        if (value < min || value > max) throw new IOException(field + " 超出已核实范围。");
    }
}

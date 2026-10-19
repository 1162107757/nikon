package com.nikonpreset.usbprobe.ncp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic phone-only approximation for the researched legacy NCP curve/colour subset.
 *
 * <p>This is not Nikon's image pipeline or a reproduction of the selected base Picture Control.
 * The app treats the 257 curve samples as a normalized tone table in encoded display RGB,
 * applies it independently to each channel, then approximates saturation and hue in HSV.
 * The app-specific mappings are saturation factor {@code 1 + step / 6.0} and hue rotation
 * {@code step * 4} degrees. They are deliberately documented assumptions, not recovered
 * Nikon parameter semantics. No linear-light conversion, curve-header Gamma adjustment,
 * base-profile colour transform, sharpening, clarity or spatial processing is performed.
 * The original NCP and template pixels are never modified.</p>
 */
public final class NcpPreviewProfile implements PresetPreviewProfile {
    /** Include this in any persistent thumbnail cache key when changing the approximation. */
    public static final String MODEL_VERSION = "encoded-rgb-tone-hsv-v1";
    public static final int MAX_PIXELS = 4 * 1024 * 1024;
    private static final int FILE_BYTES = 638;
    private static final int CURVE_SAMPLES_OFFSET = 56 + 64;
    private static final int SAMPLE_MAX = 32767;

    private final int[] samples;
    private final int[] tone = new int[256];
    private final String registrationName;
    private final int basePictureControl, sharpening, saturation, hue;

    private NcpPreviewProfile(byte[] verified, String name, int base) {
        registrationName = name;
        basePictureControl = base;
        sharpening = (verified[40] & 255) - 0x80;
        saturation = (verified[43] & 255) - 0x80;
        hue = (verified[44] & 255) - 0x80;
        samples = new int[257];
        for (int i = 0; i < samples.length; i++) {
            int offset = CURVE_SAMPLES_OFFSET + 2 * i;
            samples[i] = ((verified[offset] & 255) << 8) | (verified[offset + 1] & 255);
        }
        for (int i = 0; i < tone.length; i++) tone[i] = channel(mapTone(i / 255.0));
    }

    /** Reject unverified structures rather than inventing a replacement photographic effect. */
    public static NcpPreviewProfile fromNcp(byte[] original) throws IOException {
        if (original == null || original.length != FILE_BYTES) {
            throw new IOException("模板模拟仅支持已验证的 638 字节 NCP 0100 子集。");
        }
        byte[] snapshot = original.clone();
        try { NcpFile.validate(snapshot); }
        catch (IllegalArgumentException malformed) { throw unsupported(malformed.getMessage()); }
        if (uint32be(snapshot, 4) != 1 || uint32be(snapshot, 8) != 36
                || uint32be(snapshot, 48) != 2 || uint32be(snapshot, 52) != 578
                || uint32be(snapshot, 634) != 0) {
            throw unsupported("暂不支持此 NCP 记录顺序、长度或附加记录。");
        }
        String name = readName(snapshot);
        int base = mapBase(uint16be(snapshot, 36));
        // Byte 38 records whether the preset was modified; it is not a tone/colour parameter.
        // The read-only model uses the stored curve, saturation and hue regardless of that flag.
        // Keep this separate from the camera converter, whose stricter write safety is unchanged.
        if ((snapshot[39] & 255) != 0xff) throw unsupported("此 NCP 的保留字节组合尚未验证。");
        int sharpening = (snapshot[40] & 255) - 0x80;
        int saturation = (snapshot[43] & 255) - 0x80;
        int hue = (snapshot[44] & 255) - 0x80;
        if (sharpening < 0 || sharpening > 9) throw unsupported("锐化必须为 0–9；本版不支持 Auto 或其它特殊值。");
        if ((snapshot[41] & 255) != 1 || (snapshot[42] & 255) != 1)
            throw unsupported("仅支持对比度和亮度都使用自定义曲线的 NCP。");
        if (saturation < -3 || saturation > 3 || hue < -3 || hue > 3)
            throw unsupported("饱和度和色相必须为 -3–3；本版不支持 Auto 或其它特殊值。");
        if ((snapshot[45] & 255) != 0xff || (snapshot[46] & 255) != 0xff || (snapshot[47] & 255) != 0xff)
            throw unsupported("本版仅支持单色滤镜、调色及浓度均为 N/A 的彩色 NCP。");
        if ((snapshot[56] & 255) != 0x49 || (snapshot[57] & 255) != 0x30)
            throw unsupported("暂不支持此自定义曲线头标识。");
        if ((snapshot[62] & 255) > 20 || (snapshot[63] & 255) > 100)
            throw unsupported("曲线 Gamma 头字段超出已核实范围。");
        if ((snapshot[64] & 255) < 2 || (snapshot[64] & 255) > 20)
            throw unsupported("曲线控制点数量必须为 2–20。");
        for (int i = 56 + 49; i < CURVE_SAMPLES_OFFSET; i++) {
            if (snapshot[i] != 0) throw unsupported("暂不支持曲线头保留区包含非零内容的 NCP。");
        }
        for (int i = CURVE_SAMPLES_OFFSET; i < 634; i += 2) {
            if (uint16be(snapshot, i) > SAMPLE_MAX)
                throw unsupported("曲线采样值超出 0–32767，未修改或截断原曲线。");
        }
        return new NcpPreviewProfile(snapshot, name, base);
    }

    private static String readName(byte[] source) throws IOException {
        int end = 16;
        while (end < 36 && source[end] != 0) {
            int value = source[end] & 255;
            if (value < 0x20 || value > 0x7e) throw unsupported("预设名称必须使用可打印 ASCII 字符。");
            end++;
        }
        if (end == 36) throw unsupported("预设名称必须在 19 个字符内以 NUL 终止。");
        for (int i = end; i < 36; i++) {
            if (source[i] != 0) throw unsupported("暂不支持名称终止符后包含非零填充的 NCP。");
        }
        String name = new String(source, 16, end - 16, StandardCharsets.US_ASCII);
        if (name.trim().isEmpty()) throw unsupported("预设名称不能为空。");
        return name;
    }
    private static int mapBase(int legacyBase) throws IOException {
        switch (legacyBase) {
            case 0x0001: return 1;
            case 0x03c2: return 2;
            case 0x00c3: return 3;
            case 0x0486: return 5;
            case 0x04c7: return 6;
            default: throw unsupported("本版仅支持 Standard、Neutral、Vivid、Portrait、Landscape 基础类型。");
        }
    }
    private static int uint16be(byte[] bytes, int offset) { return ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255); }
    private static long uint32be(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 255) << 24) | ((long) (bytes[offset + 1] & 255) << 16)
                | ((long) (bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }
    private static IOException unsupported(String message) { return new IOException("此预设暂不支持模板模拟。" + message); }

    public String registrationName() { return registrationName; }
    public String modelVersion() { return MODEL_VERSION; }
    /** Metadata only; this base profile's proprietary colour transform is not applied. */
    public int basePictureControl() { return basePictureControl; }
    /** Metadata only; no sharpening is applied to the template. */
    public int sharpening() { return sharpening; }
    public int saturation() { return saturation; }
    public int hue() { return hue; }

    /** Linear interpolation of the raw normalized samples, without inferred Gamma processing. */
    public double mapTone(double normalized) {
        if (!Double.isFinite(normalized) || normalized < 0 || normalized > 1) {
            throw new IllegalArgumentException("Tone input must be finite and within 0..1.");
        }
        double position = normalized * 256;
        int lower = (int) position;
        if (lower == 256) return samples[256] / (double) SAMPLE_MAX;
        double fraction = position - lower;
        return (samples[lower] + (samples[lower + 1] - samples[lower]) * fraction) / SAMPLE_MAX;
    }

    /** Apply the phone approximation to one unpremultiplied ARGB pixel, retaining its alpha. */
    public int applyArgb(int argb) {
        int alpha = argb & 0xff000000;
        int red = tone[(argb >>> 16) & 255], green = tone[(argb >>> 8) & 255], blue = tone[argb & 255];
        if (saturation == 0 && hue == 0) return alpha | (red << 16) | (green << 8) | blue;

        double r = red / 255.0, g = green / 255.0, b = blue / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double delta = max - min;
        // Achromatic pixels remain achromatic, even when the preset has a hue adjustment.
        if (delta == 0) return alpha | (red << 16) | (green << 8) | blue;
        double angle;
        if (max == r) angle = 60 * ((g - b) / delta);
        else if (max == g) angle = 60 * ((b - r) / delta + 2);
        else angle = 60 * ((r - g) / delta + 4);
        angle = (angle + hue * 4 + 360) % 360;
        double hsvSaturation = Math.min(1, delta / max * (1 + saturation / 6.0));
        double chroma = max * hsvSaturation;
        double x = chroma * (1 - Math.abs((angle / 60) % 2 - 1));
        double m = max - chroma;
        double outRed, outGreen, outBlue;
        int sector = (int) (angle / 60);
        switch (sector) {
            case 0: outRed = chroma; outGreen = x; outBlue = 0; break;
            case 1: outRed = x; outGreen = chroma; outBlue = 0; break;
            case 2: outRed = 0; outGreen = chroma; outBlue = x; break;
            case 3: outRed = 0; outGreen = x; outBlue = chroma; break;
            case 4: outRed = x; outGreen = 0; outBlue = chroma; break;
            default: outRed = chroma; outGreen = 0; outBlue = x; break;
        }
        return alpha | (channel(outRed + m) << 16) | (channel(outGreen + m) << 8) | channel(outBlue + m);
    }

    /** Return a new exact-sized ARGB array. Dimensions and allocation are bounded before work. */
    public int[] render(int[] argb, int width, int height) {
        long pixels = (long) width * height;
        if (argb == null || width <= 0 || height <= 0 || pixels > MAX_PIXELS
                || pixels != argb.length) {
            throw new IllegalArgumentException("Template dimensions must match 1.." + MAX_PIXELS + " pixels.");
        }
        int[] output = new int[argb.length];
        for (int i = 0; i < argb.length; i++) output[i] = applyArgb(argb[i]);
        return output;
    }

    private static int channel(double normalized) {
        return Math.max(0, Math.min(255, (int) Math.round(normalized * 255)));
    }
}

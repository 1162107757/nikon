package com.nikonpreset.usbprobe.ncp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * A deliberately approximate, immutable phone renderer for NP3 0310 Flexible Color.
 *
 * <p>Container/parameter research: ssssota/nikon-flexible-color-picture-control,
 * src/{base,offset,deserialize,types}.ts. This independently implemented reader checks
 * TLV order, lengths, types, ranges and inactive fields before using their values.
 * It does not use Nikon's proprietary RAW conversion or colour science.</p>
 *
 * <p>The input is unpremultiplied, encoded display RGB (normally an already rendered
 * JPEG). The app first applies a 257-sample tone table independently to RGB channels.
 * A stored custom table is linearly interpolated; its editor control points/levels/
 * Gamma metadata are validated and are not applied again to its baked samples.
 * Without a custom curve, our tone model is contrast about 0.5 (slope 2^(contrast/100)),
 * then highlights/shadows and white/black lifts, weighted by smooth endpoint masks.
 * All adjustments use the original value, and the final result is clamped to 0..1.</p>
 *
 * <p>Colour is approximated in HSL: saturation is multiplied by 1+saturation/100.
 * Eight Color Blender anchors (0,30,60,120,180,240,270,300 degrees) are linearly
 * interpolated around the hue circle; hue moves by value*0.3 degrees, chroma scales
 * saturation by 1+value/100, and brightness moves lightness by value/200 times its
 * distance from the nearest endpoint. Achromatic pixels have no hue-specific edit.
 * Three Color Grading zones then use normalized Gaussian weights over encoded RGB
 * luma; balance shifts luma by balance/250 and blending changes sigma from .12 to .47.
 * A zone adds a zero-luma RGB tint of strength chroma/500 and a neutral brightness lift
 * of brightness/200. Negative grading chroma reverses the tint. The three RGB offsets
 * are precomputed at 1025 luma positions and linearly interpolated per pixel to avoid
 * exponential functions in full-size image loops. These formulas are
 * explicit app assumptions, not recovered Nikon rendering semantics.</p>
 *
 * <p>Sharpness, mid-range sharpness and clarity are retained as metadata only: no
 * spatial processing is performed. No camera base transform, RAW white balance,
 * linear-light processing, auto adjustment or creative Picture Control is invented.
 * Non-neutral unresearched fields are rejected rather than silently discarded.
 * A successful parse is not a claim of colour equivalence with NX Studio.</p>
 */
public final class Np3PreviewProfile implements PresetPreviewProfile {
    public static final String MODEL_VERSION = "np3-0310-encoded-hsl-zones-v1";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final double[] ANCHORS = {0, 30, 60, 120, 180, 240, 270, 300, 360};
    private final String registrationName;
    private final double sharpening, midRangeSharpening, clarity;
    private final int[] samples;
    private final int[] basic = new int[5];
    private final int[] tone = new int[256];
    private final int[][] blender = new int[8][3];
    private final double[][] grade = new double[3][3];
    private final double[][] gradingTable;
    private final int saturation, blending, balance;
    private final boolean hasGrading;

    private Np3PreviewProfile(byte[][] fields, byte[] curve, String name) throws IOException {
        registrationName = name;
        sharpening = signed(fields[6], 0, -12, 36, "锐化") / 4.0;
        clarity = signed(fields[7], 0, -20, 20, "清晰度") / 4.0;
        midRangeSharpening = signed(fields[22], 0, -20, 20, "中范围锐化") / 4.0;
        samples = curve == null ? null : parseCurve(curve);
        for (int i = 0; i < basic.length; i++) {
            byte[] field = fields[25 + i];
            require(field[1] == 1, "基础色调字段类型无效。");
            if (samples != null) require((field[0] & 255) == 1, "曲线和基础色调参数的启用状态不一致。");
            else basic[i] = signed(field, 0, -100, 100, "基础色调（不支持 Auto）");
        }
        require(fields[30][1] == 1, "饱和度类型无效。");
        saturation = signed(fields[30], 0, -100, 100, "饱和度（不支持 Auto）");
        for (int i = 0; i < 8; i++) for (int j = 0; j < 3; j++)
            blender[i][j] = signed(fields[31], i * 3 + j, -100, 100, "混色器");
        tail(fields[31], 24);
        tail(fields[32], 12);
        boolean active = false;
        for (int i = 0; i < 3; i++) {
            int offset = i * 4;
            int upper = fields[32][offset] & 255;
            require((upper & 0xf0) == 0x80, "调色器色相编码无效。");
            int hue = ((upper & 15) << 8) | (fields[32][offset + 1] & 255);
            require(hue <= 360, "调色器色相超出 0–360。");
            int chroma = signed(fields[32], offset + 2, -100, 100, "调色器浓度");
            int brightness = signed(fields[32], offset + 3, -100, 100, "调色器亮度");
            // Precompute a luma-neutral tint for this zone (highlight, midtone, shadow).
            int rgb = hsl(hue, 1, .5);
            double r = ((rgb >>> 16) & 255) / 255.0, g = ((rgb >>> 8) & 255) / 255.0, b = (rgb & 255) / 255.0;
            double luma = .2126 * r + .7152 * g + .0722 * b;
            grade[i][0] = (r - luma) * chroma / 500.0 + brightness / 200.0;
            grade[i][1] = (g - luma) * chroma / 500.0 + brightness / 200.0;
            grade[i][2] = (b - luma) * chroma / 500.0 + brightness / 200.0;
            active |= chroma != 0 || brightness != 0;
        }
        require(fields[32][17] == 1 && fields[32][19] == 1, "调色器混合或平衡字段类型无效。");
        blending = signed(fields[32], 16, 0, 100, "调色器混合");
        balance = signed(fields[32], 18, -100, 100, "调色器平衡");
        hasGrading = active;
        gradingTable = active ? new double[3][1025] : null;
        if (active) {
            double sigma = .12 + .35 * blending / 100.0;
            for (int i = 0; i <= 1024; i++) {
                double luma = clamp(i / 1024.0 + balance / 250.0);
                double h = gaussian(luma - 1, sigma), m = gaussian(luma - .5, sigma), s = gaussian(luma, sigma);
                double total = h + m + s;
                for (int channel = 0; channel < 3; channel++)
                    gradingTable[channel][i] = (grade[0][channel] * h + grade[1][channel] * m + grade[2][channel] * s) / total;
            }
        }
        for (int i = 0; i < tone.length; i++) tone[i] = channel(mapTone(i / 255.0));
    }

    /** Read a bounded snapshot; malformed and unsupported data always fails explicitly. */
    public static Np3PreviewProfile fromNp3(byte[] original) throws IOException {
        require(original != null && original.length >= 392 && original.length <= MAX_BYTES,
                "NP3 文件过短或超过 1 MB。");
        byte[] data = original.clone();
        require(data[0] == 'N' && data[1] == 'C' && data[2] == 'P' && data[3] == 0,
                "NP3 签名无效。");
        byte[][] fields = new byte[33][];
        int offset = 4;
        for (int index = 1; index <= 32; index++) {
            require(data.length - offset >= 8, "NP3 基础记录被截断。");
            long id = u32(data, offset), length = u32(data, offset + 4);
            int expected = index == 1 ? 4 : index == 2 || index == 32 ? 20 : index == 31 ? 28 : 2;
            require(id == index * 0x100L && length == expected, "NP3 基础记录的顺序或长度不受支持。");
            offset += 8;
            require(length <= data.length - offset, "NP3 记录内容被截断。");
            fields[index] = java.util.Arrays.copyOfRange(data, offset, offset + expected);
            offset += expected;
        }
        require("0310".equals(new String(fields[1], StandardCharsets.US_ASCII)), "仅支持 NP3 0310。");
        String name = name(fields[2]);
        require(u16(fields[3], 0) == 0x20, "仅支持 Flexible Color 基底；创意、Auto 或其它基底尚未支持。");
        exact(fields[4], 0, 0, "修改状态");
        exact(fields[5], 255, 1, "调整状态");
        for (int index : new int[] {6, 7, 22})
            require(fields[index][1] == 4, "锐化或清晰度字段类型无效。");
        for (int index : new int[] {8, 9, 10, 11, 14, 23, 24}) exact(fields[index], 255, 4, "未启用的传统调整");
        for (int index : new int[] {12, 13}) exact(fields[index], 255, 0, "单色调整");
        for (int index = 15; index <= 19; index++) exact(fields[index], 255, 1, "未启用的传统色彩调整");
        // 0xff is the inactive marker and 0x80 the neutral value. A few real recipes
        // change this unresearched field; do not pretend we know its visual meaning.
        require(fields[20][1] == 1 && ((fields[20][0] & 255) == 255 || (fields[20][0] & 255) == 128),
                "包含尚未支持的非中性色彩字段（0x1400）。");
        exact(fields[21], 255, 10, "创意效果强度");
        byte[] curve = null;
        boolean note = false, ended = false;
        while (offset < data.length) {
            require(data.length - offset >= 4, "NP3 附加记录被截断。");
            long id = u32(data, offset); offset += 4;
            if (id == 0) {
                require(offset == data.length, "NP3 终止标记后包含数据。");
                ended = true; break;
            }
            require(data.length - offset >= 4, "NP3 附加记录缺少长度。");
            long length = u32(data, offset); offset += 4;
            require(length <= data.length - offset, "NP3 附加记录长度无效。");
            if (id == 0x10100 && !note && curve == null && length >= 16 && length <= 248 && length % 2 == 0) {
                validateComment(data, offset, (int) length); note = true;
            } else if (id == 2 && curve == null && length == 578) {
                curve = java.util.Arrays.copyOfRange(data, offset, offset + 578);
            } else throw unsupported("包含未知、重复或长度无效的 NP3 附加记录。");
            offset += (int) length;
        }
        require(ended, "NP3 缺少终止标记。");
        return new Np3PreviewProfile(fields, curve, name);
    }

    public String registrationName() { return registrationName; }
    public String modelVersion() { return MODEL_VERSION; }
    /** Metadata only; no spatial processing is performed. */
    public double sharpening() { return sharpening; }
    /** Metadata only; no spatial processing is performed. */
    public double midRangeSharpening() { return midRangeSharpening; }
    /** Metadata only; no spatial processing is performed. */
    public double clarity() { return clarity; }
    public boolean hasCustomCurve() { return samples != null; }

    /** Final phone tone mapping; custom samples and basic controls are mutually exclusive. */
    public double mapTone(double normalized) {
        if (!Double.isFinite(normalized) || normalized < 0 || normalized > 1)
            throw new IllegalArgumentException("Tone input must be finite and within 0..1.");
        if (samples != null) {
            double position = normalized * 256;
            int lower = (int) position;
            if (lower == 256) return samples[256] / 32767.0;
            return (samples[lower] + (samples[lower + 1] - samples[lower]) * (position - lower)) / 32767.0;
        }
        double x = normalized;
        double upper = smooth(x), lower = smooth(1 - x);
        return clamp((x - .5) * Math.pow(2, basic[0] / 100.0) + .5
                + basic[1] / 250.0 * upper * (1 - x)
                + basic[2] / 250.0 * lower * x
                + basic[3] / 500.0 * x * x
                + basic[4] / 500.0 * (1 - x) * (1 - x));
    }

    /** Transform one unpremultiplied ARGB pixel, preserving alpha exactly. */
    public int applyArgb(int argb) {
        int alpha = argb & 0xff000000;
        double r = tone[(argb >>> 16) & 255] / 255.0;
        double g = tone[(argb >>> 8) & 255] / 255.0;
        double b = tone[argb & 255] / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double delta = max - min, light = (max + min) / 2;
        if (delta > 0) {
            double hue;
            if (max == r) hue = 60 * ((g - b) / delta);
            else if (max == g) hue = 60 * ((b - r) / delta + 2);
            else hue = 60 * ((r - g) / delta + 4);
            hue = (hue + 360) % 360;
            double sat = delta / (1 - Math.abs(2 * light - 1));
            int sector = 0;
            while (sector < 7 && hue >= ANCHORS[sector + 1]) sector++;
            int next = (sector + 1) % 8;
            double mix = (hue - ANCHORS[sector]) / (ANCHORS[sector + 1] - ANCHORS[sector]);
            double hueShift = mix(blender[sector][0], blender[next][0], mix);
            double chroma = mix(blender[sector][1], blender[next][1], mix);
            double brightness = mix(blender[sector][2], blender[next][2], mix);
            hue = (hue + hueShift * .3 + 360) % 360;
            sat = clamp(sat * (1 + saturation / 100.0) * (1 + chroma / 100.0));
            light = clamp(light + brightness / 200.0 * (1 - Math.abs(2 * light - 1)));
            int rgb = hsl(hue, sat, light);
            r = ((rgb >>> 16) & 255) / 255.0;
            g = ((rgb >>> 8) & 255) / 255.0;
            b = (rgb & 255) / 255.0;
        }
        if (hasGrading) {
            double position = clamp(.2126 * r + .7152 * g + .0722 * b) * 1024;
            int lower = Math.min(1023, (int) position);
            double fraction = position - lower;
            r += mix(gradingTable[0][lower], gradingTable[0][lower + 1], fraction);
            g += mix(gradingTable[1][lower], gradingTable[1][lower + 1], fraction);
            b += mix(gradingTable[2][lower], gradingTable[2][lower + 1], fraction);
        }
        return alpha | (channel(r) << 16) | (channel(g) << 8) | channel(b);
    }

    private static int[] parseCurve(byte[] curve) throws IOException {
        require(curve[0] == 0x49 && curve[1] == 0x30, "自定义曲线标识无效。");
        require((curve[2] & 255) < (curve[3] & 255) && (curve[4] & 255) <= (curve[5] & 255), "曲线编辑器端点无效。");
        require((curve[6] & 255) <= 20 && (curve[7] & 255) <= 100, "曲线 Gamma 元数据超出已知范围。");
        int count = curve[8] & 255;
        require(count <= 20, "自定义曲线最多支持 20 个控制点。");
        int previous = -1;
        for (int i = 0; i < count; i++) {
            int x = curve[9 + i * 2] & 255;
            require(x > previous, "曲线控制点横坐标须严格递增。"); previous = x;
        }
        for (int i = 9 + count * 2; i < 64; i++) require(curve[i] == 0, "自定义曲线包含未知保留数据。");
        int[] samples = new int[257];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = u16(curve, 64 + i * 2);
            require(samples[i] <= 32767, "曲线采样值超出 0–32767。");
        }
        return samples;
    }

    private static String name(byte[] bytes) throws IOException {
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            int value = bytes[end] & 255;
            require(value >= 32 && value <= 126, "NP3 名称须为可打印 ASCII。"); end++;
        }
        require(end > 0 && end <= 19, "NP3 名称须为 1–19 字符并以 NUL 终止。");
        for (int i = end; i < bytes.length; i++) require(bytes[i] == 0, "NP3 名称包含非零填充。");
        String name = new String(bytes, 0, end, StandardCharsets.US_ASCII);
        require(!name.trim().isEmpty(), "NP3 名称不能为空。"); return name;
    }
    private static void validateComment(byte[] data, int offset, int length) throws IOException {
        int end = offset;
        while (end < offset + length && data[end] != 0) end++;
        require(end < offset + length, "NP3 备注缺少终止符。");
        for (int i = end; i < offset + length; i++) require(data[i] == 0, "NP3 备注填充无效。");
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data, offset, end - offset));
        } catch (CharacterCodingException error) { throw unsupported("NP3 备注不是有效 UTF-8。"); }
    }
    private static int signed(byte[] data, int offset, int min, int max, String label) throws IOException {
        int result = (data[offset] & 255) - 128;
        require(result >= min && result <= max, label + "超出支持范围。"); return result;
    }
    private static void exact(byte[] field, int first, int second, String label) throws IOException {
        require((field[0] & 255) == first && (field[1] & 255) == second, "尚不支持此" + label + "参数。");
    }
    private static void tail(byte[] field, int offset) throws IOException {
        require(field[offset] == 1 && field[offset + 1] == 1 && field[offset + 2] == 1 && field[offset + 3] == 0,
                "混色或调色字段类型无效。");
    }
    private static long u32(byte[] data, int offset) {
        return ((long) (data[offset] & 255) << 24) | ((long) (data[offset + 1] & 255) << 16)
                | ((long) (data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
    }
    private static int u16(byte[] data, int offset) { return ((data[offset] & 255) << 8) | (data[offset + 1] & 255); }
    private static void require(boolean condition, String message) throws IOException { if (!condition) throw unsupported(message); }
    private static IOException unsupported(String message) { return new IOException("此 NP3 暂不支持手机模拟。" + message); }
    private static double mix(double a, double b, double fraction) { return a + (b - a) * fraction; }
    private static double smooth(double value) { return value * value * (3 - 2 * value); }
    private static double gaussian(double distance, double sigma) { return Math.exp(-distance * distance / (2 * sigma * sigma)); }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static int channel(double value) { return (int) Math.round(clamp(value) * 255); }
    private static int hsl(double hue, double saturation, double light) {
        hue = ((hue % 360) + 360) % 360;
        double c = (1 - Math.abs(2 * light - 1)) * saturation;
        double x = c * (1 - Math.abs((hue / 60) % 2 - 1)), m = light - c / 2;
        double r, g, b;
        switch ((int) (hue / 60)) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x; break;
        }
        return (channel(r + m) << 16) | (channel(g + m) << 8) | channel(b + m);
    }
}

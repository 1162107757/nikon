package cc.huevia.ncp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Strict, immutable NP3 0310 Monochrome and Deep Tone Monochrome subset reader.
 *
 * <p>The accepted records are independently audited from the real Dreamy pair:
 * base 0x0655 with verified red/green Filter Effect, or audited Scott base 0x064d
 * with Filter Effect Off. Both require neutral Toning, a baked 257-sample curve
 * and inactive legacy/Flexible controls. Auto, other bases,
 * other filters, Toning, Quick sharp and no-curve operation remain unsupported.
 * Curve editor points/levels/Gamma are validated metadata, never reapplied.</p>
 *
 * <p>Deep Tone phone approximation: decode sRGB, mix nonnegative red/green-filter weights,
 * encode sRGB, apply a shared 1.35-power Deep Tone response, then the stored curve.
 * The compact model was frozen using one controlled indoor NX source before a
 * second-source check. These weights and the response are app assumptions, not
 * recovered Nikon algorithms or complete calibration. The original curve follows
 * monochrome conversion; its editor Gamma is already baked into its samples.
 * Shared spatial processing runs after the point transform.</p>
 *
 * <p>Standard Monochrome Off uses encoded-sRGB Rec.709 gray g, followed by the
 * bounded monotone response q/(q+.48*(1-g)), q=pow(g,1.92), then the original
 * sampled curve. The two response parameters were selected using controlled
 * DSC_6182 NX exports and frozen before DSC_6184 validation; channel weights
 * were fixed, not fitted. This is a separate app approximation, not Nikon's
 * recovered base transform. Deep Tone's previous numeric path is unchanged.</p>
 */
public final class Np3MonochromePreviewProfile implements PresetPreviewProfile {
    public static final String MODEL_VERSION = "np3-0310-deep-tone-monochrome-spatial-v1";
    public static final String STANDARD_MODEL_VERSION = "np3-0310-monochrome-spatial-v1";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final double[] LINEAR = new double[256];
    static {
        for (int i = 0; i < LINEAR.length; i++) {
            double value = i / 255.0;
            LINEAR[i] = value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
        }
    }
    private static final byte[] NEUTRAL_GRADING = {
        (byte)128,0,(byte)128,(byte)128, (byte)128,0,(byte)128,(byte)128,
        (byte)128,0,(byte)128,(byte)128, 1,1,1,0, (byte)178,1,(byte)128,1
    };
    private final String registrationName;
    private final boolean standard;
    /** Verified raw Filter Effect byte: standard Off 0x80 or Deep Tone red/green 0x83/0x84. */
    private final int filterEffect;
    private final double sharpening, midRangeSharpening, clarity;
    private final int[] samples;

    private Np3MonochromePreviewProfile(byte[][] fields, byte[] curve, String name) throws IOException {
        registrationName = name;
        standard = (fields[3][1] & 255) == 0x4d;
        filterEffect = fields[12][0] & 255;
        sharpening = signed(fields[6], -12, 36, "锐化") / 4.0;
        clarity = signed(fields[7], -20, 20, "清晰度") / 4.0;
        midRangeSharpening = signed(fields[22], -20, 20, "中范围锐化") / 4.0;
        samples = parseCurve(curve);
    }

    /** Read a bounded defensive snapshot; every unknown record or active control fails. */
    public static Np3MonochromePreviewProfile fromNp3(byte[] original) throws IOException {
        require(original != null && original.length >= 392 && original.length <= MAX_BYTES,
                "NP3 文件过短或超过 1 MB。");
        byte[] data = original.clone();
        require(data[0] == 'N' && data[1] == 'C' && data[2] == 'P' && data[3] == 0,
                "NP3 签名无效。");
        byte[][] fields = new byte[33][];
        int offset = 4;
        for (int index = 1; index <= 32; index++) {
            require(data.length - offset >= 8, "NP3 基础记录被截断。");
            long id = u32(data,offset), length = u32(data,offset + 4);
            int expected = index == 1 ? 4 : index == 2 || index == 32 ? 20 : index == 31 ? 28 : 2;
            require(id == index * 0x100L && length == expected, "NP3 基础记录的顺序或长度不受支持。");
            offset += 8;
            require(length <= data.length - offset, "NP3 记录内容被截断。");
            fields[index] = Arrays.copyOfRange(data,offset,offset + expected);
            offset += expected;
        }
        require("0310".equals(new String(fields[1],StandardCharsets.US_ASCII)), "仅支持 NP3 0310。");
        String name = name(fields[2]);
        require(fields[3][0] == 6 && ((fields[3][1] & 255) == 0x55 || (fields[3][1] & 255) == 0x4d),
                "仅支持已验证的单色或深色调单色基底。");
        boolean standard = (fields[3][1] & 255) == 0x4d;
        exact(fields[4],0,0,"修改状态"); exact(fields[5],255,1,"调整状态");
        for (int index : new int[] {6,7,22}) require(fields[index][1] == 4, "锐化或清晰度字段类型无效。");
        exact(fields[8],1,4,"曲线启用的传统对比度");
        exact(fields[9],1,4,"曲线启用的传统亮度");
        for (int index : new int[] {10,11,14,23,24}) exact(fields[index],255,4,"未启用的传统调整");
        if (standard) exact(fields[12],128,0,"单色基底的无色彩滤镜效果");
        else require(fields[12][1] == 0 && ((fields[12][0] & 255) == 0x83 || (fields[12][0] & 255) == 0x84),
                "深色调单色仅支持已验证的红色或绿色滤镜。");
        exact(fields[13],128,0,"未调色的单色效果");
        for (int index = 15; index <= 20; index++) exact(fields[index],255,1,"未启用的传统色彩或 Quick sharp");
        exact(fields[21],255,10,"未启用的创意效果强度");
        for (int index = 25; index <= 29; index++) exact(fields[index],1,1,"曲线启用的 Flexible 色调哨兵");
        exact(fields[30],128,1,"中性的 Flexible 饱和度哨兵");
        for (int index = 0; index < 24; index++) require((fields[31][index] & 255) == 128,"单色配方包含非中性混色器。");
        require(fields[31][24] == 1 && fields[31][25] == 1 && fields[31][26] == 1 && fields[31][27] == 0,
                "混色器字段类型无效。");
        require(Arrays.equals(fields[32],NEUTRAL_GRADING),"单色配方包含未验证的 Flexible 调色参数。");

        byte[] curve = null;
        boolean note = false, ended = false;
        while (offset < data.length) {
            require(data.length - offset >= 4,"NP3 附加记录被截断。");
            long id = u32(data,offset); offset += 4;
            if (id == 0) {
                require(offset == data.length,"NP3 终止标记后包含数据。"); ended = true; break;
            }
            require(data.length - offset >= 4,"NP3 附加记录缺少长度。");
            long length = u32(data,offset); offset += 4;
            require(length <= data.length - offset,"NP3 附加记录长度无效。");
            if (id == 0x10100 && !note && curve == null && length >= 16 && length <= 248 && length % 2 == 0) {
                validateComment(data,offset,(int)length); note = true;
            } else if (id == 2 && curve == null && length == 578) {
                curve = Arrays.copyOfRange(data,offset,offset + 578);
            } else throw unsupported("包含未知、重复或长度无效的 NP3 附加记录。");
            offset += (int)length;
        }
        require(ended,"NP3 缺少终止标记。");
        require(curve != null,"单色当前仅支持已保存自定义曲线的配方。");
        return new Np3MonochromePreviewProfile(fields,curve,name);
    }

    public String registrationName() { return registrationName; }
    public String modelVersion() { return standard ? STANDARD_MODEL_VERSION : MODEL_VERSION; }
    public boolean hasCustomCurve() { return true; }
    public double sharpening() { return sharpening; }
    public double midRangeSharpening() { return midRangeSharpening; }
    public double clarity() { return clarity; }

    @Override public SpatialPreviewFilter.Settings spatialSettings() {
        return new SpatialPreviewFilter.Settings(sharpening / (sharpening < 0 ? 3.0 : 9.0),
                midRangeSharpening / 5.0,clarity / 5.0);
    }

    /** Stored curve alone; no inferred monochrome base response or editor Gamma. */
    public double mapTone(double normalized) {
        if (!Double.isFinite(normalized) || normalized < 0 || normalized > 1)
            throw new IllegalArgumentException("Tone input must be finite and within 0..1.");
        double position = normalized * 256;
        int lower = (int)position;
        if (lower == 256) return samples[256] / 32767.0;
        return (samples[lower] + (samples[lower + 1] - samples[lower]) * (position - lower)) / 32767.0;
    }

    /** Point transform only; alpha is retained and the full effect is achromatic. */
    public int applyArgb(int argb) {
        if (standard) {
            double red = ((argb >>> 16) & 255) / 255.0;
            double green = ((argb >>> 8) & 255) / 255.0;
            double blue = (argb & 255) / 255.0;
            double gray = Math.max(0, Math.min(1, .2126 * red + .7152 * green + .0722 * blue));
            double powered = Math.pow(gray, 1.92);
            double mapped = mapTone(powered / (powered + .48 * (1 - gray)));
            int channel = (int) Math.round(Math.max(0, Math.min(1, mapped)) * 255);
            return (argb & 0xff000000) | (channel << 16) | (channel << 8) | channel;
        }
        double red = LINEAR[(argb >>> 16) & 255], green = LINEAR[(argb >>> 8) & 255], blue = LINEAR[argb & 255];
        double gray = filterEffect == 0x83 ? .80 * red + .18 * green + .02 * blue
                : .15 * red + .80 * green + .05 * blue;
        // Evaluate the compact base response before interpolating the original
        // curve. Composing both into a coarse linear-light LUT smears legitimate
        // sharp/non-monotone curve knots, especially in dark colours.
        double encoded = gray <= .0031308 ? 12.92 * gray : 1.055 * Math.pow(gray, 1 / 2.4) - .055;
        double mapped = mapTone(Math.pow(Math.max(0, Math.min(1, encoded)), 1.35));
        int channel = (int) Math.round(Math.max(0, Math.min(1, mapped)) * 255);
        return (argb & 0xff000000) | (channel << 16) | (channel << 8) | channel;
    }

    private static int[] parseCurve(byte[] curve) throws IOException {
        require(curve[0] == 0x49 && curve[1] == 0x30,"自定义曲线标识无效。");
        require((curve[2] & 255) < (curve[3] & 255) && (curve[4] & 255) <= (curve[5] & 255),"曲线编辑器端点无效。");
        require((curve[6] & 255) <= 20 && (curve[7] & 255) <= 100,"曲线 Gamma 元数据超出已知范围。");
        int count = curve[8] & 255;
        require(count <= 20,"自定义曲线最多支持 20 个控制点。");
        int previous = -1;
        for (int index = 0; index < count; index++) {
            int x = curve[9 + index * 2] & 255;
            require(x > previous,"曲线控制点横坐标须严格递增。"); previous = x;
        }
        for (int index = 9 + count * 2; index < 64; index++) require(curve[index] == 0,"自定义曲线包含未知保留数据。");
        int[] samples = new int[257];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = u16(curve,64 + index * 2);
            require(samples[index] <= 32767,"曲线采样值超出 0–32767。");
        }
        return samples;
    }
    private static String name(byte[] bytes) throws IOException {
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            int value = bytes[end] & 255;
            require(value >= 32 && value <= 126,"NP3 名称须为可打印 ASCII。"); end++;
        }
        require(end > 0 && end <= 19,"NP3 名称须为 1–19 字符并以 NUL 终止。");
        for (int index = end; index < bytes.length; index++) require(bytes[index] == 0,"NP3 名称包含非零填充。");
        String name = new String(bytes,0,end,StandardCharsets.US_ASCII);
        require(!name.trim().isEmpty(),"NP3 名称不能为空。"); return name;
    }
    private static void validateComment(byte[] data,int offset,int length) throws IOException {
        int end = offset;
        while (end < offset + length && data[end] != 0) end++;
        require(end < offset + length,"NP3 备注缺少终止符。");
        for (int index = end; index < offset + length; index++) require(data[index] == 0,"NP3 备注填充无效。");
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data,offset,end - offset));
        } catch (CharacterCodingException error) { throw unsupported("NP3 备注不是有效 UTF-8。"); }
    }
    private static int signed(byte[] field,int min,int max,String label) throws IOException {
        int value = (field[0] & 255) - 128;
        require(value >= min && value <= max,label + "超出支持范围。"); return value;
    }
    private static void exact(byte[] field,int first,int second,String label) throws IOException {
        require((field[0] & 255) == first && (field[1] & 255) == second,"尚不支持此" + label + "参数。");
    }
    private static long u32(byte[] data,int offset) {
        return ((long)(data[offset] & 255) << 24) | ((long)(data[offset + 1] & 255) << 16)
                | ((long)(data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
    }
    private static int u16(byte[] data,int offset) { return ((data[offset] & 255) << 8) | (data[offset + 1] & 255); }
    private static void require(boolean condition,String message) throws IOException { if (!condition) throw unsupported(message); }
    private static IOException unsupported(String message) { return new IOException("此 NP3 暂不支持手机模拟。" + message); }
}

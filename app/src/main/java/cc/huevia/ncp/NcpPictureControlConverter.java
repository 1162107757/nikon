package cc.huevia.ncp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/** Explicit compatibility adaptation of a small, researched legacy NCP subset. */
public final class NcpPictureControlConverter {
    private static final int FILE_BYTES = 638;
    private static final int PROFILE_OFFSET = 12;
    private static final int CURVE_OFFSET = 56;
    private static final int CURVE_BYTES = 578;
    private static final int WIRE_HEADER_BYTES = 36;

    private NcpPictureControlConverter() {}

    /** The two supplied sharpening values use quarter units, not whole camera menu units. */
    public static Conversion convert(byte[] original, int middleQuarter, int clarityQuarter)
            throws IOException {
        requireQuarterRange(middleQuarter, "中等范围锐化");
        requireQuarterRange(clarityQuarter, "清晰度");
        if (original == null || original.length != FILE_BYTES) {
            throw unsupported("仅支持包含 36 字节预设记录和 578 字节曲线的 638 字节 NCP。");
        }
        byte[] source = original.clone();
        if (source[0] != 'N' || source[1] != 'C' || source[2] != 'P' || source[3] != 0) {
            throw unsupported("NCP 文件签名不符。");
        }
        if (uint32be(source, 4) != 1 || uint32be(source, 8) != 36
                || uint32be(source, 48) != 2 || uint32be(source, 52) != CURVE_BYTES
                || uint32be(source, 634) != 0) {
            throw unsupported("暂不支持此 NCP 记录顺序、长度或附加记录。");
        }
        if (source[12] != '0' || source[13] != '1' || source[14] != '0' || source[15] != '0') {
            throw unsupported("仅支持 NCP 0100 版本。");
        }
        String name = readName(source, PROFILE_OFFSET + 4);
        int legacyBase = uint16be(source, PROFILE_OFFSET + 24);
        int base = mapBase(legacyBase);

        // NCP metadata: 0=unmodified, 1=edited back to defaults, 2=modified.
        // dumpncp.pl documents this field independently of the actual stored parameters.
        // It is not the camera's PTP ModifiedFlag and must never be copied into the wire header.
        // All parameter/curve checks below remain mandatory for every recognized state.
        int modificationState = unsigned(source[38]);
        if (modificationState > 2) {
            throw unsupported("预设包含未支持的修改状态（" + modificationState + "），原文件未改动。");
        }
        if (unsigned(source[39]) != 0xff) {
            throw unsupported("预设的保留字段不符合已支持的格式，原文件未改动。");
        }
        int sharpening = unsigned(source[40]) - 0x80;
        int saturation = unsigned(source[43]) - 0x80;
        int hue = unsigned(source[44]) - 0x80;
        if (sharpening < 0 || sharpening > 9) {
            throw unsupported("锐化必须为 0–9；本版不支持 Auto 或其它特殊值。");
        }
        if (unsigned(source[41]) != 1 || unsigned(source[42]) != 1) {
            throw unsupported("仅支持对比度和亮度都使用自定义曲线的 NCP。");
        }
        if (saturation < -3 || saturation > 3 || hue < -3 || hue > 3) {
            throw unsupported("饱和度和色相必须为 -3–3；本版不支持 Auto 或其它特殊值。");
        }
        if (unsigned(source[45]) != 0xff || unsigned(source[46]) != 0xff
                || unsigned(source[47]) != 0xff) {
            throw unsupported("本版仅支持单色滤镜、调色及浓度均为 N/A 的彩色 NCP。");
        }
        byte[] curve = Arrays.copyOfRange(source, CURVE_OFFSET, CURVE_OFFSET + CURVE_BYTES);
        validateCurve(curve);

        byte[] data = new byte[WIRE_HEADER_BYTES + CURVE_BYTES];
        data[0] = (byte) base;
        data[1] = (byte) (base >>> 8);
        System.arraycopy(source, PROFILE_OFFSET + 4, data, 2, 20);
        data[22] = 0; // No creative-picture-control apply level.
        data[23] = 0; // Explicit individual sharpening controls.
        data[24] = 0; // Compatibility value, not a recovered legacy quick setting.
        data[25] = (byte) (sharpening * 4);
        data[26] = (byte) middleQuarter;
        data[27] = (byte) clarityQuarter;
        data[28] = 0; // Explicit curve-mode adaptation; never copy the legacy sentinel.
        data[29] = 0;
        data[30] = (byte) (saturation * 4);
        data[31] = (byte) (hue * 4);
        data[32] = 0;
        data[33] = 0;
        data[34] = 0;
        data[35] = 1;
        System.arraycopy(curve, 0, data, WIRE_HEADER_BYTES, CURVE_BYTES);
        return new Conversion(data, name, base, middleQuarter, clarityQuarter,
                sharpening, saturation, hue, sha256(source), sha256(curve), sha256(data));
    }

    private static String readName(byte[] source, int offset) throws IOException {
        int end = offset;
        int limit = offset + 20;
        while (end < limit && source[end] != 0) {
            int value = unsigned(source[end]);
            if (value < 0x20 || value > 0x7e) throw unsupported("预设名称必须使用可打印 ASCII 字符。");
            end++;
        }
        if (end == limit) throw unsupported("预设名称必须在 19 个字符内以 NUL 终止。");
        for (int i = end; i < limit; i++) {
            if (source[i] != 0) throw unsupported("暂不支持名称终止符后包含非零填充的 NCP。");
        }
        String name = new String(source, offset, end - offset, StandardCharsets.US_ASCII);
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

    private static void validateCurve(byte[] curve) throws IOException {
        if (unsigned(curve[0]) != 0x49 || unsigned(curve[1]) != 0x30) {
            throw unsupported("暂不支持此自定义曲线头标识。");
        }
        if (unsigned(curve[6]) > 20 || unsigned(curve[7]) > 100) {
            throw unsupported("曲线 Gamma 头字段超出已核实范围。");
        }
        int splinePoints = unsigned(curve[8]);
        if (splinePoints < 2 || splinePoints > 20) {
            throw unsupported("曲线控制点数量必须为 2–20。");
        }
        for (int i = 49; i < 64; i++) {
            if (curve[i] != 0) throw unsupported("暂不支持曲线头保留区包含非零内容的 NCP。");
        }
        for (int i = 64; i < CURVE_BYTES; i += 2) {
            if (uint16be(curve, i) > 32767) {
                throw unsupported("曲线采样值超出 0–32767，未修改或截断原曲线。");
            }
        }
    }

    private static void requireQuarterRange(int value, String field) throws IOException {
        if (value < -20 || value > 20) throw unsupported(field + "适配值必须为 -5–5，使用四分之一单位。");
    }

    private static int unsigned(byte value) { return value & 0xff; }
    private static int uint16be(byte[] data, int offset) {
        return (unsigned(data[offset]) << 8) | unsigned(data[offset + 1]);
    }
    private static long uint32be(byte[] data, int offset) {
        return ((long) unsigned(data[offset]) << 24) | ((long) unsigned(data[offset + 1]) << 16)
                | ((long) unsigned(data[offset + 2]) << 8) | unsigned(data[offset + 3]);
    }
    private static IOException unsupported(String message) {
        return new IOException("NCP 兼容试导入：" + message);
    }
    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return text.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("此运行环境无法计算 SHA-256。", error);
        }
    }
    private static String quarterText(int quarter) {
        int absolute = Math.abs(quarter);
        String fraction;
        switch (absolute % 4) {
            case 1: fraction = ".25"; break;
            case 2: fraction = ".5"; break;
            case 3: fraction = ".75"; break;
            default: fraction = "";
        }
        return (quarter < 0 ? "-" : "") + absolute / 4 + fraction;
    }

    public static final class Conversion {
        private final byte[] data;
        private final String name;
        private final int base;
        private final int middleQuarter;
        private final int clarityQuarter;
        private final int sharpening;
        private final int saturation;
        private final int hue;
        private final String sourceSha256;
        private final String curveSha256;
        private final String dataSha256;

        private Conversion(byte[] data, String name, int base, int middleQuarter, int clarityQuarter,
                           int sharpening, int saturation, int hue, String sourceSha256,
                           String curveSha256, String dataSha256) {
            this.data = data.clone();
            this.name = name;
            this.base = base;
            this.middleQuarter = middleQuarter;
            this.clarityQuarter = clarityQuarter;
            this.sharpening = sharpening;
            this.saturation = saturation;
            this.hue = hue;
            this.sourceSha256 = sourceSha256;
            this.curveSha256 = curveSha256;
            this.dataSha256 = dataSha256;
        }

        public byte[] dataBytes() { return data.clone(); }
        public String registrationName() { return name; }
        public int basePictureControl() { return base; }
        public int middleRangeSharpeningQuarter() { return middleQuarter; }
        public int clarityQuarter() { return clarityQuarter; }
        public String sourceSha256() { return sourceSha256; }
        public String curveSha256() { return curveSha256; }
        public String dataSha256() { return dataSha256; }

        public String toDisplayText() {
            return "NCP 兼容适配预览\n名称：" + name + "，base=" + base
                    + "\n保留：锐化 " + sharpening + "、饱和度 " + saturation + "、色相 " + hue
                    + "；578 字节自定义曲线原样保留。"
                    + "\n用户选择的适配值：中等范围锐化 " + quarterText(middleQuarter)
                    + "、清晰度 " + quarterText(clarityQuarter)
                    + "。\n其它适配值：逐项锐化（快速锐化关闭，quick=0），对比度=0，亮度=0。"
                    + "\n这些是明确的兼容适配值，并非推导出的尼康原生载入结果。"
                    + "\n转换结果尚不证明相机登记成功或拍摄效果等效。";
        }
    }
}

package cc.huevia.library;

import cc.huevia.ncp.NcpFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Storage-only checks for researched preset containers; not a camera/rendering capability claim. */
public final class PresetFile {
    public static final String NCP = "NCP", NP3 = "NP3";
    public final String fileFormat, version, registrationName;

    private PresetFile(String format, String version, String name) {
        this.fileFormat = format; this.version = version; this.registrationName = name;
    }

    public static String requireFormat(String value) throws IOException {
        if (!NCP.equals(value) && !NP3.equals(value)) throw new IOException("预设格式须为 NCP 或 NP3。");
        return value;
    }

    /** Missing fields belong to the original NCP-only catalog/metadata schema. */
    static String formatFromMap(Map<String, Object> map) throws IOException {
        return map.containsKey("fileFormat")
                ? requireFormat(LibraryJson.string(map, "fileFormat", true, 3)) : NCP;
    }

    public static String extension(String format) throws IOException {
        return "." + requireFormat(format).toLowerCase(Locale.ROOT);
    }

    public static PresetFile validate(byte[] data) throws IOException {
        if (data == null || data.length < 8 || data.length > NcpFile.MAX_BYTES)
            throw new IOException("预设文件为空、过短或超过 1 MB。");
        if (data[0] != 'N' || data[1] != 'C' || data[2] != 'P' || data[3] != 0)
            throw new IOException("文件不是已支持的 NCP 或 NP3 预设（签名不符）。");
        // The common magic cannot distinguish NP3. Its version is the first 0x100 record.
        if (uint32be(data, 4) == 0x100) return validateNp3(data);
        try { return new PresetFile(NCP, NcpFile.validate(data), ""); }
        catch (IllegalArgumentException error) { throw new IOException(error.getMessage(), error); }
    }

    private static PresetFile validateNp3(byte[] data) throws IOException {
        int offset = 4; String name = "";
        for (int index = 1; index <= 32; index++) {
            if (data.length - offset < 8) throw new IOException("NP3 基础记录缺失或被截断。");
            long id = uint32be(data, offset), length = uint32be(data, offset + 4); offset += 8;
            int expectedLength = index == 1 ? 4 : index == 2 || index == 32 ? 20 : index == 31 ? 28 : 2;
            if (id != index * 0x100L || length != expectedLength)
                throw new IOException("暂不支持此 NP3 基础记录的顺序或长度。");
            if (length > data.length - offset) throw new IOException("NP3 记录长度超出文件范围。");
            if (index == 1 && !"0310".equals(new String(data, offset, 4, StandardCharsets.US_ASCII)))
                throw new IOException("暂只支持保存 NP3 0310 版本。");
            if (index == 2) {
                int end = offset;
                while (end < offset + 20 && data[end] >= 0x20 && data[end] <= 0x7e) end++;
                if (end == offset + 20 || data[end] == 0)
                    name = new String(data, offset, end - offset, StandardCharsets.US_ASCII).trim();
            }
            offset += (int) length;
        }
        boolean note = false, curve = false;
        while (offset < data.length) {
            if (data.length - offset < 4) throw new IOException("NP3 记录被截断。");
            long id = uint32be(data, offset); offset += 4;
            if (id == 0) {
                if (offset != data.length) throw new IOException("NP3 终止标记后存在附加数据。");
                return new PresetFile(NP3, "0310", name);
            }
            if (data.length - offset < 4) throw new IOException("NP3 记录缺少长度。");
            long length = uint32be(data, offset); offset += 4;
            if (length > data.length - offset) throw new IOException("NP3 记录长度超出文件范围。");
            if (id == 0x10100 && !note && !curve && length >= 16 && length <= 248) {
                try {
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data, offset, (int) length));
                } catch (CharacterCodingException error) {
                    throw new IOException("NP3 备注记录不是有效的 UTF-8 文字。", error);
                }
                note = true;
            }
            else if (id == 2 && !curve && length == 578) curve = true;
            else throw new IOException("NP3 包含不支持、重复或长度无效的附加记录。");
            offset += (int) length;
        }
        throw new IOException("NP3 缺少终止标记。");
    }

    private static long uint32be(byte[] data, int offset) {
        return ((long) (data[offset] & 255) << 24) | ((long) (data[offset + 1] & 255) << 16)
                | ((long) (data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
    }
}

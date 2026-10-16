package com.nikonpreset.usbprobe.ncp;

import java.nio.charset.StandardCharsets;

/** Structural checks for the researched NCP 0100 envelope, not a Nikon compatibility guarantee. */
public final class NcpFile {
    public static final int MAX_BYTES = 1024 * 1024;
    private NcpFile() {}

    public static String validate(byte[] data) {
        if (data == null || data.length < 8 || data.length > MAX_BYTES) {
            throw new IllegalArgumentException("NCP 文件为空、过短或超过 1 MB。");
        }
        if (data[0] != 'N' || data[1] != 'C' || data[2] != 'P' || data[3] != 0) {
            throw new IllegalArgumentException("文件不是已支持的 NCP 格式（签名不符）。");
        }
        int offset = 4;
        String version = null;
        while (offset < data.length) {
            if (data.length - offset < 4) throw new IllegalArgumentException("NCP 记录被截断。");
            long id = uint32be(data, offset);
            offset += 4;
            if (id == 0) {
                if (offset != data.length) throw new IllegalArgumentException("暂不支持终止标记后有附加数据的 NCP。");
                if (version == null) throw new IllegalArgumentException("NCP 缺少预设记录。");
                return version;
            }
            if (data.length - offset < 4) throw new IllegalArgumentException("NCP 记录缺少长度。");
            long length = uint32be(data, offset);
            offset += 4;
            if (length > data.length - offset) throw new IllegalArgumentException("NCP 记录长度超出文件范围。");
            if (id == 1) {
                if (version != null || length < 36) throw new IllegalArgumentException("暂不支持此 NCP 预设记录结构。");
                version = new String(data, offset, 4, StandardCharsets.US_ASCII);
                if (!"0100".equals(version)) throw new IllegalArgumentException("暂不支持 NCP 版本 " + version + "。");
            }
            offset += (int) length;
        }
        throw new IllegalArgumentException("NCP 缺少终止标记。");
    }

    private static long uint32be(byte[] data, int offset) {
        return ((long) (data[offset] & 255) << 24) | ((long) (data[offset + 1] & 255) << 16)
                | ((long) (data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
    }
}

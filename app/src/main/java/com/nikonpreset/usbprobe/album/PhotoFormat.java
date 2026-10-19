package com.nikonpreset.usbprobe.album;

import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Source formats, not encoders. RAW is always a byte-preserved camera NEF. */
public enum PhotoFormat {
    JPEG, NEF, OTHER;

    public static final long MAX_RAW_BYTES = 512L * 1024 * 1024;
    public static boolean isNefName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).matches("[^/\\\\\\p{Cntrl}]+\\.nef");
    }
    public static PhotoFormat of(PtpObjectInfo info) {
        if (info == null || info.objectFormat() == 0x3001 || info.associationType() != 0) return OTHER;
        // Nikon firmware may report TIFF/EP or a vendor format. Do not guess a
        // universal vendor code: the received bytes must independently pass NEF validation.
        if (isNefName(info.filename())) return NEF;
        return info.objectFormat() == 0x3801 ? JPEG : OTHER;
    }

    /** Bounded classic-TIFF structure checks; never decodes or rewrites sensor/EXIF data. */
    public static void validateNef(File file, long expectedBytes) throws IOException {
        if (file == null || !file.isFile()) throw invalid();
        long size = file.length(), changed = file.lastModified();
        if (size < 32 || size > MAX_RAW_BYTES || expectedBytes > 0 && size != expectedBytes) throw invalid();
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            int first = input.readUnsignedByte(), second = input.readUnsignedByte();
            boolean little = first == 'I' && second == 'I';
            if (!little && !(first == 'M' && second == 'M') || u16(input, little) != 42) throw invalid();
            long root = u32(input, little);
            ArrayDeque<Long> pending = new ArrayDeque<>(); pending.add(root);
            Set<Long> visited = new HashSet<>(); boolean nikon = false, raw = false;
            int entries = 0;
            while (!pending.isEmpty()) {
                interrupted(); long offset = pending.removeFirst();
                if (offset == 0) continue;
                if (offset < 8 || !visited.add(offset) || visited.size() > 64) throw invalid();
                span(offset, 2, size); input.seek(offset); int count = u16(input, little);
                if (count < 1 || count > 4096 || (entries += count) > 16384) throw invalid();
                span(offset, 2L + count * 12L + 4, size);
                long[] strips = null, lengths = null, tiles = null, tileLengths = null;
                long width = 0, height = 0, photometric = -1;
                Set<Integer> structuralTags = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    long entry = offset + 2 + i * 12L; input.seek(entry);
                    int tag = u16(input, little), type = u16(input, little); long values = u32(input, little);
                    if ((tag == 0x010f || tag == 0x014a || tag == 0x8769 || tag == 0x0100 || tag == 0x0101
                            || tag == 0x0106 || tag == 0x0111 || tag == 0x0117 || tag == 0x0144 || tag == 0x0145)
                            && !structuralTags.add(tag)) throw invalid();
                    int unit = typeSize(type); long bytes = values * unit;
                    if (values > MAX_RAW_BYTES || bytes > MAX_RAW_BYTES) throw invalid();
                    long value = bytes <= 4 ? entry + 8 : u32(input, little);
                    if (bytes > 0) span(value, bytes, size);
                    if (tag == 0x010f) {
                        if (type != 2 || values < 5 || values > 256) throw invalid();
                        byte[] make = new byte[(int) values]; input.seek(value); input.readFully(make);
                        String text = new String(make, StandardCharsets.US_ASCII).replace("\u0000", "").trim();
                        nikon |= text.toUpperCase(Locale.ROOT).startsWith("NIKON");
                    } else if (tag == 0x014a || tag == 0x8769) {
                        long[] children = integers(input, type, values, value, little, 64);
                        for (long child : children) if (child != 0) pending.add(child);
                    } else if (tag == 0x0100 || tag == 0x0101 || tag == 0x0106) {
                        long[] number = integers(input, type, values, value, little, 1);
                        if (number.length != 1) throw invalid();
                        if (tag == 0x0100) width = number[0]; else if (tag == 0x0101) height = number[0]; else photometric = number[0];
                    } else if (tag == 0x0111) strips = integers(input, type, values, value, little, 16384);
                    else if (tag == 0x0117) lengths = integers(input, type, values, value, little, 16384);
                    else if (tag == 0x0144) tiles = integers(input, type, values, value, little, 16384);
                    else if (tag == 0x0145) tileLengths = integers(input, type, values, value, little, 16384);
                }
                // Preview planes belong to the original file too. A valid CFA plane
                // must never hide a truncated or out-of-file preview payload.
                if (strips != null || lengths != null) { if (strips == null || lengths == null) throw invalid(); segments(strips, lengths, size); }
                if (tiles != null || tileLengths != null) { if (tiles == null || tileLengths == null) throw invalid(); segments(tiles, tileLengths, size); }
                // Standard TIFF/EP CFA photometric 32803 identifies a sensor plane.
                // Ordinary Nikon TIFF exports/JPEG previews cannot satisfy this check.
                if (photometric == 32803 && width > 0 && height > 0 && width <= 65535 && height <= 65535) {
                    if (strips != null || tiles != null) raw = true;
                }
                input.seek(offset + 2 + count * 12L); long next = u32(input, little);
                if (next != 0) pending.add(next);
            }
            if (!nikon || !raw) throw new IOException("相机返回的文件未通过 NEF 原文件结构检查，未保存为 RAW。请保留相机原文件后重试。");
        }
        if (file.length() != size || file.lastModified() != changed) throw new IOException("RAW 原文件在校验时发生变化，请重试。");
    }

    private static void segments(long[] offsets, long[] lengths, long size) throws IOException {
        if (offsets.length == 0 || offsets.length != lengths.length) throw invalid();
        for (int i = 0; i < offsets.length; i++) { if (lengths[i] < 1) throw invalid(); span(offsets[i], lengths[i], size); }
    }
    private static long[] integers(RandomAccessFile input, int type, long count, long offset, boolean little, int maximum) throws IOException {
        if ((type != 3 && type != 4 && type != 13) || count < 1 || count > maximum) throw invalid();
        long[] result = new long[(int) count]; input.seek(offset);
        for (int i = 0; i < result.length; i++) result[i] = type == 3 ? u16(input, little) : u32(input, little);
        return result;
    }
    private static int typeSize(int type) throws IOException {
        switch (type) {
            case 1: case 2: case 6: case 7: return 1;
            case 3: case 8: return 2;
            case 4: case 9: case 11: case 13: return 4;
            case 5: case 10: case 12: return 8;
            default: throw invalid();
        }
    }
    private static void span(long offset, long bytes, long size) throws IOException { if (offset < 8 || bytes < 0 || offset > size || bytes > size - offset) throw invalid(); }
    private static int u16(RandomAccessFile input, boolean little) throws IOException {
        int a = input.readUnsignedByte(), b = input.readUnsignedByte(); return little ? a | b << 8 : a << 8 | b;
    }
    private static long u32(RandomAccessFile input, boolean little) throws IOException {
        long a = u16(input, little), b = u16(input, little); return little ? a | b << 16 : a << 16 | b;
    }
    private static void interrupted() throws IOException { if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("RAW 校验已取消。"); }
    private static IOException invalid() { return new IOException("RAW 文件不完整或 NEF 结构无效，未确认保存。"); }
}

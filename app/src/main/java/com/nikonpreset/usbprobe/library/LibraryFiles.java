package com.nikonpreset.usbprobe.library;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class LibraryFiles {
    private LibraryFiles() {}
    static byte[] read(File file, int limit) throws IOException {
        try (InputStream input = new FileInputStream(file)) { return read(input, limit); }
    }
    static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("操作已取消。");
            if (count == 0) throw new IOException("读取未取得进展。");
            if (count > limit - result.size()) throw new IOException("文件超过允许的大小。");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }
    static String utf8(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new IOException("文件不是有效 UTF-8。", e); }
    }
    static void atomicWrite(File target, byte[] bytes) throws IOException {
        File directory = target.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建本地预设目录。");
        File stage = Files.createTempFile(directory.toPath(), ".pending-", ".tmp").toFile();
        try {
            try (FileOutputStream output = new FileOutputStream(stage)) {
                output.write(bytes); output.getFD().sync();
            }
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(stage.toPath()); }
    }
    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static String requireHash(String value) throws IOException {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) throw new IOException("SHA-256 格式无效。");
        return value.toLowerCase(Locale.ROOT);
    }
}

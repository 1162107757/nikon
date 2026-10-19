package com.nikonpreset.usbprobe.album;

import android.util.AtomicFile;
import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Optional sidecar evidence for future RAW export; never changes the JPEG completion schema. */
final class RawJpegReferenceStore {
    private static final Object LOCK = new Object();
    private static final int MAGIC = 0x524a5232;
    private final File records;
    private final List<File> allowed = new ArrayList<>();
    static final class Reference {
        final String camera, sourceIdentity; final long handle; final PtpObjectInfo info;
        Reference(String camera, String sourceIdentity, long handle, PtpObjectInfo info) {
            this.camera = camera; this.sourceIdentity = sourceIdentity; this.handle = handle; this.info = info;
        }
    }
    RawJpegReferenceStore(File filesDirectory, File externalPicturesDirectory) throws IOException {
        File files = filesDirectory.getCanonicalFile(); records = new File(files, "raw-jpeg-references");
        if (!files.isDirectory() && !files.mkdirs() && !files.isDirectory()) throw new IOException("无法读取本机文件目录。");
        if (!records.getCanonicalFile().equals(records) || Files.isSymbolicLink(records.toPath())) throw new IOException("RAW 来源记录路径无效。");
        allowed.add(new File(new File(files, "pictures"), "camera-album").getCanonicalFile());
        if (externalPicturesDirectory != null) allowed.add(new File(externalPicturesDirectory, "camera-album").getCanonicalFile());
    }
    void bind(String camera, boolean trusted, PhotoItem photo, DownloadedPhoto saved) throws IOException {
        if (!trusted) return;
        validateCamera(camera);
        if (photo == null || photo.format() != PhotoFormat.JPEG) throw new IOException("仅可记录相机 JPG 原图来源。");
        File file = owned(saved); String sha = PhotoOriginalStore.contentId(file);
        Reference reference = new Reference(camera, PhotoLiveLedger.identity(camera, photo.handle(), photo.objectInfo()), photo.handle(), photo.objectInfo());
        synchronized (LOCK) {
            AtomicFile record = record(sha, true); Entry old = read(record, sha, file.length());
            if (old != null && (old.ambiguous || old.reference.sourceIdentity.equals(reference.sourceIdentity))) return;
            // Byte-identical JPEGs can originate from different devices or captures.
            // Keep that ambiguity instead of silently rebinding the same local file.
            write(record, new Entry(old != null, old == null ? reference : old.reference, sha, file.length()));
        }
    }
    Reference find(DownloadedPhoto saved) throws IOException {
        File file = owned(saved); String sha = PhotoOriginalStore.contentId(file);
        synchronized (LOCK) {
            Entry entry = read(record(sha, false), sha, file.length());
            if (entry == null) return null;
            if (entry.ambiguous) throw new IOException("这张 JPG 有多个拍摄来源，无法唯一核对 RAW。请在相机 RAW 文件列表明确选择原文件。");
            return entry.reference;
        }
    }
    private File owned(DownloadedPhoto photo) throws IOException {
        if (photo == null || photo.file() == null) throw new IOException("请先选择完整 JPG 原图。");
        File expected = photo.file().getAbsoluteFile(), file = expected.getCanonicalFile();
        if (!expected.equals(file) || Files.isSymbolicLink(expected.toPath()) || !allowed.contains(file.getParentFile())
                || file.getName().matches("effect-[0-9a-f]{64}__Effect\\.JPG")) throw new IOException("请先从效果图找到对应 JPG 原图，再读取 RAW。");
        return file;
    }
    private AtomicFile record(String sha, boolean create) throws IOException {
        if (!sha.matches("[0-9a-f]{64}")) throw new IOException("RAW 来源标识无效。");
        if (!records.getCanonicalFile().equals(records) || Files.isSymbolicLink(records.toPath())) throw new IOException("RAW 来源路径无效。");
        if (create && !records.isDirectory() && !records.mkdir() && !records.isDirectory()) throw new IOException("无法创建 RAW 来源记录。");
        File base = new File(records, sha + ".record");
        for (String suffix : new String[] {"", ".bak", ".new"}) {
            File file = new File(base + suffix);
            if (!file.getCanonicalFile().equals(file) || Files.isSymbolicLink(file.toPath()) || file.exists() && (!file.isFile() || file.length() > 70000)) throw new IOException("RAW 来源记录无效。");
        }
        if (create && !base.exists()) {
            String[] files = records.list(); if (files == null || files.length >= 20000) throw new IOException("RAW 来源记录已达本版上限。");
        }
        return new AtomicFile(base);
    }
    private static final class Entry {
        final boolean ambiguous; final Reference reference; final String jpegSha; final long bytes;
        Entry(boolean ambiguous, Reference reference, String jpegSha, long bytes) { this.ambiguous = ambiguous; this.reference = reference; this.jpegSha = jpegSha; this.bytes = bytes; }
    }
    private static Entry read(AtomicFile record, String expectedSha, long expectedBytes) throws IOException {
        if (!record.getBaseFile().exists() && !new File(record.getBaseFile() + ".bak").exists()) return null;
        try (DataInputStream input = new DataInputStream(record.openRead())) {
            if (input.readInt() != MAGIC) throw new IOException("RAW 来源记录格式无效。");
            String sha = input.readUTF(); long bytes = input.readLong();
            if (!sha.equals(expectedSha) || bytes != expectedBytes) throw new IOException("RAW 来源记录与 JPG 原图内容不符。");
            boolean ambiguous = input.readBoolean(); String camera = input.readUTF(), identity = input.readUTF();
            long handle = input.readLong(); int size = input.readInt();
            validateCamera(camera);
            if (size < 1 || size > 65536 || handle < 1 || handle >= 0xffffffffL || !identity.matches("[0-9a-f]{64}")) throw new IOException("RAW 来源记录内容无效。");
            byte[] metadata = new byte[size]; input.readFully(metadata); PtpObjectInfo info = PtpObjectInfo.parse(metadata);
            if (input.read() != -1 || PhotoFormat.of(info) != PhotoFormat.JPEG || !identity.equals(PhotoLiveLedger.identity(camera, handle, info))) throw new IOException("RAW 来源与相机元数据不符。");
            return new Entry(ambiguous, new Reference(camera, identity, handle, info), sha, bytes);
        }
    }
    private static void write(AtomicFile record, Entry entry) throws IOException {
        FileOutputStream file = null;
        try {
            file = record.startWrite(); DataOutputStream output = new DataOutputStream(file);
            Reference ref = entry.reference; byte[] info = ref.info.toBytes();
            output.writeInt(MAGIC); output.writeUTF(entry.jpegSha); output.writeLong(entry.bytes);
            output.writeBoolean(entry.ambiguous); output.writeUTF(ref.camera); output.writeUTF(ref.sourceIdentity);
            output.writeLong(ref.handle); output.writeInt(info.length); output.write(info); output.flush(); file.getFD().sync();
            record.finishWrite(file); file = null;
        } finally { if (file != null) record.failWrite(file); }
    }
    private static void validateCamera(String camera) throws IOException {
        if (camera == null || camera.length() > 2048 || camera.indexOf('\n') != 32
                || !camera.substring(0, 32).matches("[0-9a-fA-F]{32}") || camera.substring(0,32).matches("0+")) throw new IOException("相机身份不足以核对 RAW 来源。");
    }
}

package cc.huevia.album;

import cc.huevia.ncp.PresetPreviewProfiles;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.Arrays;

/** Worker-only, account-scoped drafts, indexed by verified original bytes rather than a filename. */
public final class PhotoEditStore {
    private static final int MAGIC = 0x50454431, LIMIT = 1024 * 1024;
    private static final Object LOCK = new Object();
    private final File root;
    public static final class Draft {
        public final String sourceSha, presetSha, name;
        public final int strength;
        public final boolean exported;
        private final byte[] bytes;
        public Draft(String sourceSha, byte[] preset, String name, int strength, boolean exported) throws IOException {
            if (sourceSha == null || !sourceSha.matches("[0-9a-f]{64}") || strength < 0 || strength > 100)
                throw new IOException("照片编辑身份或强度无效。");
            bytes = preset == null ? new byte[0] : preset.clone();
            if (bytes.length > LIMIT) throw new IOException("预设文件过大。");
            if (bytes.length > 0) PresetPreviewProfiles.fromPreset(bytes);
            this.sourceSha = sourceSha; presetSha = bytes.length == 0 ? "" : hash(bytes);
            this.name = bytes.length == 0 ? "原图" : clean(name); this.strength = bytes.length == 0 ? 0 : strength; this.exported = exported;
        }
        public byte[] presetBytes() { return bytes.clone(); }
        public boolean hasEffect() { return !presetSha.isEmpty() && strength > 0; }
        public String identity() { return sourceSha + ":" + presetSha + ":" + strength; }
    }
    public PhotoEditStore(File files, String owner) throws IOException {
        File base = files.getCanonicalFile();
        root = new File(base, "photo-edit-drafts/" + hash((owner == null ? "" : owner).getBytes(StandardCharsets.UTF_8))).getCanonicalFile();
        if (!root.toPath().startsWith(base.toPath()) || !root.isDirectory() && !root.mkdirs()) throw new IOException("无法保存照片编辑草稿。");
    }
    public Draft read(String sourceSha) throws IOException {
        synchronized (LOCK) {
            File file = file(sourceSha); if (!file.exists()) return null;
            if (file.length() < 40 || file.length() > LIMIT + 4096) throw new IOException("照片编辑草稿损坏。");
            byte[] all = Files.readAllBytes(file.toPath()); int size = all.length - 32;
            byte[] body = Arrays.copyOf(all, size);
            if (!MessageDigest.isEqual(digest(body), Arrays.copyOfRange(all, size, all.length))) throw new IOException("照片编辑草稿校验失败。");
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
                if (in.readInt() != MAGIC || !in.readUTF().equals(sourceSha)) throw new IOException("草稿与原图不符。");
                String name = in.readUTF(); int strength = in.readInt(); boolean exported = in.readBoolean(); int length = in.readInt();
                if (length < 0 || length > LIMIT || length != in.available()) throw new IOException("草稿预设数据不完整。");
                byte[] bytes = new byte[length]; in.readFully(bytes); return new Draft(sourceSha, bytes, name, strength, exported);
            }
        }
    }
    public void save(Draft draft) throws IOException {
        synchronized (LOCK) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC); out.writeUTF(draft.sourceSha); out.writeUTF(draft.name);
                out.writeInt(draft.strength); out.writeBoolean(draft.exported); out.writeInt(draft.bytes.length); out.write(draft.bytes);
            }
            byte[] body = bytes.toByteArray(); File target = file(draft.sourceSha), temp = File.createTempFile("draft-", ".part", root);
            try {
                try (FileOutputStream out = new FileOutputStream(temp)) { out.write(body); out.write(digest(body)); out.getFD().sync(); }
                try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp.toPath()); }
        }
    }
    /** Exported settings are history; they must not replace an explicitly chosen saved version. */
    public Draft readPending(String sourceSha) throws IOException {
        Draft draft = read(sourceSha); return draft != null && !draft.exported ? draft : null;
    }
    public void markExported(Draft frozen) throws IOException {
        synchronized (LOCK) {
            Draft live = read(frozen.sourceSha);
            if (live != null && live.identity().equals(frozen.identity())) save(new Draft(live.sourceSha, live.bytes, live.name, live.strength, true));
        }
    }
    private File file(String sha) throws IOException {
        if (sha == null || !sha.matches("[0-9a-f]{64}")) throw new IOException("原图身份无效。");
        File file = new File(root, sha + ".draft"); if (!file.getCanonicalFile().equals(file)) throw new IOException("草稿路径无效。"); return file;
    }
    public static String hashFile(File file) throws IOException {
        MessageDigest digest = algorithm(); byte[] buffer = new byte[65536];
        try (InputStream in = Files.newInputStream(file.toPath())) { int n; while ((n = in.read(buffer)) != -1) { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("编辑已取消。"); digest.update(buffer, 0, n); } }
        return hex(digest.digest());
    }
    public static String hash(byte[] bytes) { return hex(digest(bytes)); }
    private static byte[] digest(byte[] bytes) { return algorithm().digest(bytes); }
    private static MessageDigest algorithm() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(64); for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return s.toString(); }
    private static String clean(String value) throws IOException { if (value == null || value.isBlank() || value.length() > 256) throw new IOException("预设名称无效。"); return value.replaceAll("[\\p{Cntrl}]", " "); }
}

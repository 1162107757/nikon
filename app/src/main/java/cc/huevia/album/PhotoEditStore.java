package cc.huevia.album;

import cc.huevia.ncp.PresetPreviewProfiles;
import cc.huevia.ncp.PhotoAdjustments;
import cc.huevia.ncp.PresetPreviewProfile;
import cc.huevia.ncp.StrengthPreviewProfile;
import cc.huevia.ncp.AdjustedPreviewProfile;
import cc.huevia.ncp.PhotoGeometry;
import cc.huevia.ncp.GeometryPreviewProfile;
import cc.huevia.ncp.PhotoBorder;
import cc.huevia.ncp.BorderPreviewProfile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.Arrays;

/** Worker-only, account-scoped drafts, indexed by verified original bytes rather than a filename. */
public final class PhotoEditStore {
    private static final int LEGACY_MAGIC = 0x50454431, ADJUSTMENT_MAGIC = 0x50454432, GEOMETRY_MAGIC = 0x50454433, MAGIC = 0x50454434, LIMIT = 1024 * 1024;
    private static final Object LOCK = new Object();
    private final File root;
    public static final class Draft {
        public final String sourceSha, presetSha, name;
        public final int strength;
        public final boolean exported;
        public final PhotoAdjustments adjustments;
        public final PhotoGeometry geometry;
        public final PhotoBorder border;
        private final byte[] bytes;
        public Draft(String sourceSha, byte[] preset, String name, int strength, boolean exported) throws IOException {
            this(sourceSha,preset,name,strength,PhotoAdjustments.NONE,exported);
        }
        public Draft(String sourceSha, byte[] preset, String name, int strength, PhotoAdjustments adjustments, boolean exported) throws IOException {
            this(sourceSha,preset,name,strength,adjustments,PhotoGeometry.NONE,exported);
        }
        public Draft(String sourceSha, byte[] preset, String name, int strength, PhotoAdjustments adjustments, PhotoGeometry geometry, boolean exported) throws IOException {
            this(sourceSha,preset,name,strength,adjustments,geometry,PhotoBorder.NONE,exported);
        }
        public Draft(String sourceSha, byte[] preset, String name, int strength, PhotoAdjustments adjustments, PhotoGeometry geometry, PhotoBorder border, boolean exported) throws IOException {
            if (sourceSha == null || !sourceSha.matches("[0-9a-f]{64}") || strength < 0 || strength > 100)
                throw new IOException("照片编辑身份或强度无效。");
            bytes = preset == null ? new byte[0] : preset.clone();
            if (bytes.length > LIMIT) throw new IOException("预设文件过大。");
            if (bytes.length > 0) PresetPreviewProfiles.fromPreset(bytes);
            this.sourceSha = sourceSha; presetSha = bytes.length == 0 ? "" : hash(bytes);
            if(adjustments==null||geometry==null||border==null)throw new IOException("照片调整设置无效。");
            this.adjustments=adjustments;this.geometry=geometry;this.border=border;
            this.name = bytes.length == 0 ? "无滤镜" : clean(name); this.strength = bytes.length == 0 ? 0 : strength; this.exported = exported;
        }
        public byte[] presetBytes() { return bytes.clone(); }
        public boolean hasEffect() { return !presetSha.isEmpty() && strength > 0 || !adjustments.isNeutral() || !geometry.isNeutral() || !border.isNone(); }
        public String identity() { return sourceSha + ":" + presetSha + ":" + strength + (adjustments.isNeutral()?"":":"+adjustments.identity())+(geometry.isNeutral()?"":":"+geometry.identity())+(border.equals(PhotoBorder.NONE)?"":":"+border.identity()); }
        public String effectPresetSha(){return presetSha.isEmpty()?hash("huevia-manual-original-v1".getBytes(StandardCharsets.UTF_8)):presetSha;}
        public PresetPreviewProfile profile() throws IOException {
            PresetPreviewProfile preset=bytes.length==0?null:StrengthPreviewProfile.of(PresetPreviewProfiles.fromPreset(bytes),strength);
            return BorderPreviewProfile.of(GeometryPreviewProfile.of(AdjustedPreviewProfile.of(preset,adjustments),geometry),border);
        }
        public String label(){return name+(presetSha.isEmpty()?"":" · "+strength+"%")+(adjustments.isNeutral()?"":" · 调节 "+adjustments.count()+" 项")+(geometry.isNeutral()?"":" · 已构图")+(border.isNone()?"":" · "+PhotoBorder.TEMPLATES[border.template]);}
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
                int magic=in.readInt();
                if ((magic != MAGIC && magic != GEOMETRY_MAGIC && magic != ADJUSTMENT_MAGIC && magic != LEGACY_MAGIC) || !in.readUTF().equals(sourceSha)) throw new IOException("草稿与原图不符。");
                String name = in.readUTF(); int strength = in.readInt(); boolean exported = in.readBoolean(); int length = in.readInt();
                if (length < 0 || length > LIMIT || length > in.available()) throw new IOException("草稿预设数据不完整。");
                byte[] bytes = new byte[length]; in.readFully(bytes);
                PhotoAdjustments adjustments=PhotoAdjustments.NONE;
                if(magic==MAGIC||magic==GEOMETRY_MAGIC||magic==ADJUSTMENT_MAGIC){
                    if(!PhotoAdjustments.MODEL.equals(in.readUTF())||in.readInt()!=PhotoAdjustments.Parameter.values().length)throw new IOException("无法读取此版本的基础调整。");
                    int[] values=new int[PhotoAdjustments.Parameter.values().length];for(int i=0;i<values.length;i++)values[i]=in.readInt();
                    try{adjustments=new PhotoAdjustments(values);}catch(IllegalArgumentException invalid){throw new IOException("基础调整草稿损坏。",invalid);}
                }
                PhotoGeometry geometry=PhotoGeometry.NONE;
                if(magic==MAGIC||magic==GEOMETRY_MAGIC){
                    if(!PhotoGeometry.MODEL.equals(in.readUTF()))throw new IOException("无法读取此版本的构图。");
                    try{geometry=new PhotoGeometry(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt());}catch(IllegalArgumentException invalid){throw new IOException("构图草稿损坏。",invalid);}
                }
                PhotoBorder border=magic==MAGIC?PhotoBorder.read(in):PhotoBorder.NONE;
                if(in.available()!=0)throw new IOException("草稿包含未知数据。");
                return new Draft(sourceSha, bytes, name, strength, adjustments, geometry, border, exported);
            }
        }
    }
    public void save(Draft draft) throws IOException {
        synchronized (LOCK) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC); out.writeUTF(draft.sourceSha); out.writeUTF(draft.name);
                out.writeInt(draft.strength); out.writeBoolean(draft.exported); out.writeInt(draft.bytes.length); out.write(draft.bytes);
                out.writeUTF(PhotoAdjustments.MODEL);int[] values=draft.adjustments.values();out.writeInt(values.length);for(int value:values)out.writeInt(value);
                PhotoGeometry g=draft.geometry;out.writeUTF(PhotoGeometry.MODEL);out.writeInt(g.turns);out.writeInt(g.angle);out.writeInt(g.left);out.writeInt(g.top);out.writeInt(g.right);out.writeInt(g.bottom);
                draft.border.write(out);
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
            if (live != null && live.identity().equals(frozen.identity())) save(new Draft(live.sourceSha, live.bytes, live.name, live.strength, live.adjustments, live.geometry, live.border, true));
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

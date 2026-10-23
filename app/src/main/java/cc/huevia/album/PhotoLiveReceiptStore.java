package cc.huevia.album;

import cc.huevia.ptp.PtpObjectInfo;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Per-entry atomic metadata, shared by worker instances. JPEG files and completion ledger are untouched. */
final class PhotoLiveReceiptStore {
    private static final Object LOCK = new Object();
    private static final int BIND_MAGIC = 0x504c4231, RECORD_MAGIC = 0x504c5231, CONTENT_RECORD_MAGIC = 0x504c5232,
            TARGET_RECORD_MAGIC = 0x504c5233, MAX_RECORDS = 20000;
    private static final Set<String> STATUSES = new HashSet<>(Arrays.asList("queued", "preview", "receiving", "saved", "error", "stopped"));
    private final File root, photos;
    interface AtomicReplace { void replace(File source, File target) throws IOException; }
    private final AtomicReplace replace;

    static final class Binding {
        final String camera; final boolean trusted;
        Binding(String camera, boolean trusted) { this.camera = camera; this.trusted = trusted; }
    }

    PhotoLiveReceiptStore(File root, File photos) throws IOException {
        this(root, photos, (source, target) -> {
            try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        });
    }
    PhotoLiveReceiptStore(File root, File photos, AtomicReplace replace) throws IOException {
        if (replace == null) throw new IllegalArgumentException("replace");
        this.replace = replace;
        this.root = root.getCanonicalFile(); this.photos = photos.getCanonicalFile();
        if (!this.root.isDirectory() && !this.root.mkdirs()) throw new IOException("无法保存接收记录。");
    }

    void bind(String projectId, String camera, boolean trusted) throws IOException {
        synchronized (LOCK) {
            if (camera == null || camera.isEmpty() || camera.length() > 4096) throw new IOException("相机身份无效。");
            File folder = folder(projectId, true), target = new File(folder, "camera");
            if (target.exists()) {
                Binding old = binding(projectId);
                if (!old.camera.equals(camera) || old.trusted != trusted) throw new IOException("项目属于另一台相机，未修改接收记录。");
                return;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(BIND_MAGIC); out.writeUTF(projectId); out.writeUTF(camera); out.writeBoolean(trusted);
            }
            atomic(target, bytes.toByteArray());
        }
    }

    Binding binding(String projectId) throws IOException {
        synchronized (LOCK) {
            File source = new File(folder(projectId, false), "camera");
            if (!source.isFile()) throw new IOException("此项目没有可核对的相机身份，请新建项目继续拍摄。");
            try (DataInputStream in = read(source)) {
                if (in.readInt() != BIND_MAGIC || !projectId.equals(in.readUTF())) throw new IOException("项目相机记录无效。");
                String camera = in.readUTF(); boolean trusted = in.readBoolean();
                if (camera.isEmpty() || camera.length() > 4096 || in.read() != -1) throw new IOException("项目相机记录无效。");
                return new Binding(camera, trusted);
            }
        }
    }

    void write(String projectId, String camera, boolean trusted, List<PhotoLiveReceipt> receipts) throws IOException {
        synchronized (LOCK) {
            bind(projectId, camera, trusted);
            File directory = folder(projectId, false);
            for (PhotoLiveReceipt receipt : receipts) {
                if (receipt == null || !projectId.equals(receipt.projectId) || !STATUSES.contains(receipt.status)
                        || receipt.identity == null || !receipt.identity.matches("[0-9a-f]{64}")
                        || receipt.photo.handle() <= 0 || receipt.photo.handle() >= 0xffffffffL
                        || receipt.createdAt < 0 || receipt.updatedAt < receipt.createdAt
                        || !receipt.identity.equals(PhotoLiveLedger.identity(camera, receipt.photo.handle(), receipt.photo.objectInfo()))) {
                    throw new IOException("接收记录身份无效，未保存。");
                }
                File target = new File(directory, receipt.identity + ".receipt");
                if (target.exists()) {
                    PhotoLiveReceipt previous = readReceipt(target, projectId, "", new Binding(camera, trusted));
                    if (previous.createdAt != receipt.createdAt) throw new IOException("接收记录的首次发现时间不能更改。");
                    if (previous.saveTarget != receipt.saveTarget) throw new IOException("已接收照片的保存位置不能更改。");
                    if (previous.updatedAt > receipt.updatedAt) continue;
                }
                if (!target.exists()) {
                    String[] entries = directory.list((dir, name) -> name.endsWith(".receipt"));
                    if (entries == null || entries.length >= MAX_RECORDS) throw new IOException("项目接收记录已达到上限。");
                }
                String savedName = "", savedSha = "", savedDisplayName = ""; long savedBytes = 0;
                if (receipt.saved != null) {
                    File expected = receipt.saved.file().getAbsoluteFile(), saved = expected.getCanonicalFile();
                    if (!expected.equals(saved) || Files.isSymbolicLink(expected.toPath()) || !photos.equals(saved.getParentFile())) {
                        throw new IOException("接收记录的本机原图路径无效。");
                    }
                    savedSha = PhotoOriginalStore.contentId(saved); savedBytes = saved.length();
                    if (savedBytes != receipt.photo.byteSize() || !saved.getName().startsWith("live-" + receipt.identity + "__")
                            && !new PhotoOriginalStore(photos).containsCompleted(saved, savedSha)) throw new IOException("接收记录的原图完成身份不符。");
                    savedName = saved.getName();
                    savedDisplayName = receipt.saved.filename();
                    if (savedDisplayName == null || savedDisplayName.length() > 256) throw new IOException("接收记录的原图名称无效。");
                }
                byte[] info = receipt.photo.objectInfo().toBytes();
                if (info.length > 65536) throw new IOException("照片元数据过大。");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream out = new DataOutputStream(bytes)) {
                    out.writeInt(TARGET_RECORD_MAGIC); out.writeUTF(projectId); out.writeUTF(receipt.identity);
                    out.writeLong(receipt.photo.handle()); out.writeInt(info.length); out.write(info);
                    out.writeUTF(receipt.status); out.writeLong(receipt.updatedAt); out.writeLong(receipt.createdAt);
                    out.writeUTF(shortError(receipt.error)); out.writeUTF(savedName);
                    out.writeLong(savedBytes); out.writeUTF(savedSha); out.writeUTF(savedDisplayName);
                    out.writeUTF(receipt.saveTarget.name());
                }
                atomic(target, bytes.toByteArray());
            }
        }
    }

    List<PhotoLiveReceipt> list(String projectId, String projectName) throws IOException {
        synchronized (LOCK) {
            File directory = folder(projectId, false);
            if (!directory.exists()) return Collections.emptyList();
            Binding binding = binding(projectId);
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".receipt"));
            if (files == null || files.length > MAX_RECORDS) throw new IOException("接收记录数量无效。");
            List<PhotoLiveReceipt> result = new ArrayList<>();
            for (File source : files) {
                result.add(readReceipt(source, projectId, projectName, binding));
            }
            result.sort(Comparator.comparingLong((PhotoLiveReceipt entry) -> entry.updatedAt).reversed().thenComparing(entry -> entry.identity));
            return Collections.unmodifiableList(result);
        }
    }

    PhotoLiveReceipt get(String projectId, String identity, String projectName) throws IOException {
        synchronized (LOCK) {
            if (identity == null || !identity.matches("[0-9a-f]{64}")) throw new IOException("接收记录照片标识无效。");
            File file = new File(folder(projectId, false), identity + ".receipt");
            return file.exists() ? readReceipt(file, projectId, projectName, binding(projectId)) : null;
        }
    }

    private PhotoLiveReceipt readReceipt(File source, String projectId, String projectName, Binding binding) throws IOException {
                if (!source.getCanonicalFile().getParentFile().equals(folder(projectId, false)) || Files.isSymbolicLink(source.toPath())) throw new IOException("接收记录路径无效。");
                try (DataInputStream in = read(source)) {
                    int format = in.readInt();
                    if (format != RECORD_MAGIC && format != CONTENT_RECORD_MAGIC && format != TARGET_RECORD_MAGIC || !projectId.equals(in.readUTF())) throw new IOException("接收记录格式无效。");
                    String identity = in.readUTF(); long handle = in.readLong(); int length = in.readInt();
                    if (!identity.matches("[0-9a-f]{64}") || !source.getName().equals(identity + ".receipt")
                            || handle <= 0 || handle >= 0xffffffffL || length < 1 || length > 65536) throw new IOException("接收记录照片无效。");
                    byte[] bytes = new byte[length]; in.readFully(bytes); PtpObjectInfo info = PtpObjectInfo.parse(bytes);
                    if (!identity.equals(PhotoLiveLedger.identity(binding.camera, handle, info))) throw new IOException("接收记录校验失败。");
                    String status = in.readUTF(); long time = in.readLong(), created = in.readLong(); String error = in.readUTF(), savedName = in.readUTF();
                    boolean contentRecord = format == CONTENT_RECORD_MAGIC || format == TARGET_RECORD_MAGIC;
                    long savedBytes = contentRecord ? in.readLong() : 0;
                    String savedSha = contentRecord ? in.readUTF() : "";
                    String savedDisplayName = contentRecord ? in.readUTF() : info.filename();
                    PhotoSaveTarget saveTarget = PhotoSaveTarget.PHONE_ALBUM;
                    if (format == TARGET_RECORD_MAGIC) {
                        try { saveTarget = PhotoSaveTarget.valueOf(in.readUTF()); }
                        catch (IllegalArgumentException invalid) { throw new IOException("接收记录保存位置无效。", invalid); }
                    }
                    if (!STATUSES.contains(status) || created < 0 || time < created || error.length() > 2000 || in.read() != -1) throw new IOException("接收记录状态无效。");
                    if (contentRecord && (savedName.isEmpty() ? savedBytes != 0 || !savedSha.isEmpty() || !savedDisplayName.isEmpty()
                            : savedBytes != info.compressedSize() || savedBytes < 4 || savedBytes > 512L * 1024 * 1024 || !savedSha.matches("[0-9a-f]{64}") || savedDisplayName.length() > 256)) throw new IOException("接收记录原图身份无效。");
                    DownloadedPhoto saved = null;
                    if (!savedName.isEmpty()) {
                        File expected = new File(photos, savedName), target = expected.getCanonicalFile();
                        if (!expected.equals(target) || !photos.equals(target.getParentFile()) || format == RECORD_MAGIC && !savedName.startsWith("live-" + identity + "__")
                                || !savedName.equals(target.getName()) || Files.isSymbolicLink(target.toPath())) throw new IOException("接收记录原图路径无效。");
                        if (target.isFile()) {
                            boolean valid = true;
                            if (contentRecord) {
                                try { valid = target.length() == savedBytes && savedSha.equals(PhotoOriginalStore.contentId(target)); }
                                catch (IOException changed) { valid = false; }
                            }
                            if (!valid) error = "本机原图已变化，未复用此副本。";
                            else saved = new DownloadedPhoto(target, savedDisplayName, projectId, projectName);
                        }
                        else if (status.equals("saved") && error.isEmpty()) error = "本机原图已移除。";
                    }
                    return new PhotoLiveReceipt(projectId, identity, status, new PhotoItem("", handle, info), saved, error, created, time, saveTarget);
                }
    }

    private File folder(String id, boolean create) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IOException("项目标识无效。");
        File value = new File(root, id).getCanonicalFile();
        if (!root.equals(value.getParentFile()) || Files.isSymbolicLink(new File(root, id).toPath())) throw new IOException("项目接收路径无效。");
        if (create && !value.isDirectory() && !value.mkdirs()) throw new IOException("无法创建接收记录目录。");
        return value;
    }

    private static String shortError(String value) { return value == null ? "" : value.substring(0, Math.min(2000, value.length())); }
    private static DataInputStream read(File source) throws IOException {
        if (source.length() > 100000 || source.length() < 32) throw new IOException("接收记录大小无效。");
        byte[] all = Files.readAllBytes(source.toPath());
        byte[] payload = Arrays.copyOf(all, all.length - 32), expected = Arrays.copyOfRange(all, all.length - 32, all.length);
        if (!MessageDigest.isEqual(digest(payload), expected)) throw new IOException("接收记录文件损坏，未继续传输。");
        return new DataInputStream(new ByteArrayInputStream(payload));
    }
    private void atomic(File target, byte[] payload) throws IOException {
        File temporary = File.createTempFile("receipt-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(payload); output.write(digest(payload)); output.getFD().sync(); }
            replace.replace(temporary, target);
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}

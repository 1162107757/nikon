package cc.huevia.album;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Worker-owned project catalog. Only metadata is persisted; completed source originals are never moved or removed. */
public final class PhotoLiveProjectStore {
    private static final int MAGIC = 0x504c5031, MAX_PROJECTS = 256, MAX_PHOTOS = 20000, MAX_SNAPSHOT = 16 * 1024 * 1024;
    private static final long MAX_PHOTO_BYTES = 512L * 1024 * 1024;
    private final File root, photosDirectory;

    public PhotoLiveProjectStore(File root, File photosDirectory) throws IOException {
        if (root == null || photosDirectory == null) throw new IOException("项目保存目录无效。");
        this.root = root.getCanonicalFile(); this.photosDirectory = photosDirectory.getCanonicalFile();
        if (!this.root.isDirectory() && !this.root.mkdirs()) throw new IOException("无法创建照片项目记录。");
    }

    public synchronized PhotoLiveProject create(String name) throws IOException {
        name = checkedName(name);
        List<Project> projects = readAll();
        if (projects.size() >= MAX_PROJECTS) throw new IOException("照片项目已达到本版上限。");
        String id = UUID.randomUUID().toString();
        if (projectFile(id, "").exists()) throw new IOException("项目标识重复，请重试。");
        long createdAt = System.currentTimeMillis();
        for (Project existing : projects) {
            if (existing.createdAt == Long.MAX_VALUE) throw new IOException("照片项目时间无效。");
            createdAt = Math.max(createdAt, existing.createdAt + 1);
        }
        Project project = new Project(id, name, createdAt, new ArrayList<>());
        write(project); return snapshot(project);
    }

    public synchronized List<PhotoLiveProject> list() throws IOException {
        List<Project> projects = readAll();
        projects.sort(Comparator.comparingLong((Project p) -> p.createdAt).reversed().thenComparing(p -> p.id));
        List<PhotoLiveProject> result = new ArrayList<>();
        for (Project project : projects) result.add(snapshot(project));
        return java.util.Collections.unmodifiableList(result);
    }

    public synchronized PhotoLiveProject get(String id) throws IOException { return snapshot(read(id)); }

    /** Rename metadata only. The immutable identity, creation order and original references are retained. */
    public synchronized PhotoLiveProject rename(String id, String name) throws IOException {
        name = checkedName(name);
        Project previous = read(id);
        if (previous.name.equals(name)) return snapshot(previous);
        // Bind the original display-derived folder before changing the mutable name.
        // A failed binding must leave the project record untouched.
        new PhotoLiveProjectFolders(root).bind(previous.id, previous.name);
        Project renamed = new Project(previous.id, name, previous.createdAt, previous.entries);
        write(renamed);
        return snapshot(renamed);
    }

    public synchronized void record(String projectId, String identity, DownloadedPhoto photo) throws IOException {
        if (identity == null || !identity.matches("[0-9a-f]{64}")) throw new IOException("项目照片标识无效。");
        Project project = read(projectId);
        if (photo == null || photo.file() == null) throw new IOException("项目照片信息无效。");
        File source = ownedFile(photo.file().getName());
        if (!source.equals(photo.file().getCanonicalFile()) || !source.isFile()) throw new IOException("仅可归类应用内已完成的原图。");
        String filename = checkedFilename(photo.filename());
        long bytes = source.length(), modified = source.lastModified();
        if (bytes < 4 || bytes > MAX_PHOTO_BYTES) throw new IOException("项目原图大小无效。");
        PhotoPreviewCache.validateJpeg(source, bytes);
        String sha = hashFile(source);
        if (bytes != source.length() || modified != source.lastModified()) throw new IOException("项目原图发生变化，未修改项目记录。");
        for (Entry entry : project.entries) {
            if (entry.identity.equals(identity)) {
                if (!entry.file.equals(source.getName()) || !entry.filename.equals(filename) || entry.bytes != bytes || !entry.sha.equals(sha)) {
                    throw new IOException("项目照片记录与原图不符，未覆盖已有分类。");
                }
                return;
            }
            if (entry.file.equals(source.getName())) {
                // Camera identities/entries may refer to exactly the same immutable original after cross-entry deduplication.
                // Keep the first project association; each receive receipt independently retains its camera identity.
                if (entry.bytes == bytes && entry.sha.equals(sha)) return;
                throw new IOException("项目原图内容与已有分类不符，未覆盖已有分类。");
            }
        }
        if (project.entries.size() >= MAX_PHOTOS) throw new IOException("项目照片已达到本版上限。");
        project.entries.add(new Entry(identity, source.getName(), filename, bytes, sha));
        write(project);
    }

    /** Exclusive local-deletion worker only. Remove all matching references before unlinking a source. */
    synchronized void removeFile(File source, long expectedBytes, String expectedSha) throws IOException {
        File canonical = source.getCanonicalFile();
        if (!photosDirectory.equals(canonical.getParentFile())) return;
        if (!ownedFile(canonical.getName()).equals(canonical)) throw new IOException("项目照片路径无效。");
        List<Project> projects = readAll();
        // Validate every affected entry before changing any project.
        for (Project project : projects) for (Entry entry : project.entries) if (entry.file.equals(canonical.getName())
                && (entry.bytes != expectedBytes || !entry.sha.equals(expectedSha))) throw new IOException("项目照片与待删除文件不符，应用内原图未删除。");
        for (Project project : projects) {
            List<Entry> kept = new ArrayList<>();
            for (Entry entry : project.entries) if (!entry.file.equals(canonical.getName())) kept.add(entry);
            if (kept.size() != project.entries.size()) write(new Project(project.id, project.name, project.createdAt, kept));
        }
    }

    static String galleryFolder(String id, String name) throws IOException {
        checkedId(id); name = checkedName(name);
        StringBuilder slug = new StringBuilder();
        for (int offset = 0; offset < name.length() && slug.codePointCount(0, slug.length()) < 32;) {
            int cp = name.codePointAt(offset); offset += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || cp == '-' || cp == '_') slug.appendCodePoint(cp);
            else if (cp == ' ' && slug.length() > 0 && slug.charAt(slug.length() - 1) != ' ') slug.append(' ');
        }
        String value = slug.toString().trim();
        return (value.isEmpty() ? "项目" : value) + "-" + id.substring(0, 8);
    }

    private PhotoLiveProject snapshot(Project project) throws IOException {
        List<DownloadedPhoto> photos = new ArrayList<>(); long totalBytes = 0;
        for (int i = project.entries.size() - 1; i >= 0; i--) {
            Entry entry = project.entries.get(i);
            totalBytes += entry.bytes;
            photos.add(new DownloadedPhoto(ownedFile(entry.file), entry.filename, project.id, project.name));
        }
        return new PhotoLiveProject(project.id, project.name, project.createdAt, photos, totalBytes);
    }

    private List<Project> readAll() throws IOException {
        File[] files = root.listFiles(); if (files == null) throw new IOException("无法读取照片项目。");
        Set<String> ids = new HashSet<>();
        for (File file : files) {
            String name = file.getName(); int marker = name.indexOf(".project");
            if (marker < 0 || !Arrays.asList(".project", ".project.bak", ".project.part").contains(name.substring(marker))) continue;
            String id = name.substring(0, marker); checkedId(id); ids.add(id);
        }
        if (ids.size() > MAX_PROJECTS) throw new IOException("照片项目数量无效。");
        List<Project> projects = new ArrayList<>();
        for (String id : ids) { recover(id); if (projectFile(id, "").exists()) projects.add(read(id)); }
        return projects;
    }

    private Project read(String id) throws IOException {
        checkedId(id); recover(id); File file = projectFile(id, "");
        if (!file.isFile()) throw new IOException("照片项目不存在。");
        return decode(file, id);
    }

    private void recover(String id) throws IOException {
        File base = projectFile(id, ""), backup = projectFile(id, ".bak"), temporary = projectFile(id, ".part");
        if (base.exists()) {
            decode(base, id); // Never replace a damaged committed record with an empty/new snapshot.
            if (backup.exists()) { decode(backup, id); Files.delete(backup.toPath()); }
        } else if (backup.exists()) {
            decode(backup, id); Files.move(backup.toPath(), base.toPath());
        }
        if (temporary.exists()) Files.delete(temporary.toPath()); // Incomplete intent never replaces the committed catalog.
    }

    private Project decode(File file, String expectedId) throws IOException {
        if (!file.isFile() || file.length() < 40 || file.length() > MAX_SNAPSHOT) throw new IOException("照片项目记录不完整。");
        byte[] encoded = Files.readAllBytes(file.toPath());
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw new IOException("照片项目记录格式无效。");
            int size = input.readInt();
            if (size < 0 || size != encoded.length - 40) throw new IOException("照片项目记录长度无效。");
            byte[] payload = new byte[size], checksum = new byte[32]; input.readFully(payload); input.readFully(checksum);
            if (!MessageDigest.isEqual(checksum, digest().digest(payload))) throw new IOException("照片项目记录损坏，未修改已有分类。");
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload))) {
                String id = data.readUTF(), name = data.readUTF(); long createdAt = data.readLong();
                if (!expectedId.equals(id) || !checkedName(name).equals(name) || createdAt < 0) throw new IOException("照片项目信息无效。");
                int count = data.readInt(); if (count < 0 || count > MAX_PHOTOS) throw new IOException("项目照片数量无效。");
                List<Entry> entries = new ArrayList<>(); Set<String> identities = new HashSet<>(), names = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    String identity = data.readUTF(), source = data.readUTF(), filename = data.readUTF(); long bytes = data.readLong(); String sha = data.readUTF();
                    if (!identity.matches("[0-9a-f]{64}") || !sha.matches("[0-9a-f]{64}") || bytes < 4 || bytes > MAX_PHOTO_BYTES
                            || !identities.add(identity) || !names.add(source)) throw new IOException("项目照片记录无效。");
                    ownedFile(source); checkedFilename(filename); entries.add(new Entry(identity, source, filename, bytes, sha));
                }
                if (data.read() != -1) throw new IOException("照片项目记录含多余内容。");
                return new Project(id, name, createdAt, entries);
            }
        }
    }

    private void write(Project project) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeUTF(project.id); data.writeUTF(project.name); data.writeLong(project.createdAt); data.writeInt(project.entries.size());
            for (Entry entry : project.entries) {
                data.writeUTF(entry.identity); data.writeUTF(entry.file); data.writeUTF(entry.filename); data.writeLong(entry.bytes); data.writeUTF(entry.sha);
            }
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length + 40 > MAX_SNAPSHOT) throw new IOException("照片项目记录超过本版上限。");
        File base = projectFile(project.id, ""), backup = projectFile(project.id, ".bak"), temporary = projectFile(project.id, ".part");
        try {
            try (FileOutputStream file = new FileOutputStream(temporary); DataOutputStream data = new DataOutputStream(file)) {
                data.writeInt(MAGIC); data.writeInt(payload.length); data.write(payload); data.write(digest().digest(payload)); data.flush(); file.getFD().sync();
            }
            decode(temporary, project.id);
            try { Files.move(temporary.toPath(), base.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                if (base.exists()) Files.move(base.toPath(), backup.toPath());
                try { Files.move(temporary.toPath(), base.toPath()); }
                catch (IOException failure) { if (backup.exists() && !base.exists()) Files.move(backup.toPath(), base.toPath()); throw failure; }
                if (backup.exists()) Files.delete(backup.toPath());
            }
        } finally { if (temporary.exists()) Files.delete(temporary.toPath()); }
    }

    private File ownedFile(String name) throws IOException {
        checkedFilename(name); File file = new File(photosDirectory, name).getCanonicalFile();
        if (!photosDirectory.equals(file.getParentFile()) || !name.equals(file.getName())) throw new IOException("项目原图路径超出应用目录或指向其他文件。");
        return file;
    }
    private File projectFile(String id, String suffix) throws IOException {
        checkedId(id); String name = id + ".project" + suffix; File file = new File(root, name).getCanonicalFile();
        if (!root.equals(file.getParentFile()) || !name.equals(file.getName())) throw new IOException("照片项目记录路径无效。");
        return file;
    }
    private static String checkedId(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IOException("照片项目标识无效。");
        return id;
    }
    private static String checkedName(String name) throws IOException {
        if (name == null) throw new IOException("请输入项目名称。");
        for (int offset = 0; offset < name.length();) {
            int cp = name.codePointAt(offset); offset += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT || Character.getType(cp) == Character.SURROGATE) throw new IOException("项目名称不能包含控制字符。");
        }
        name = name.trim(); int count = name.codePointCount(0, name.length());
        if (count < 1 || count > 60 || name.contains("..") || name.equals(".") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) throw new IOException("项目名称需为 1–60 个字符，且不能包含路径。");
        return name;
    }
    private static String checkedFilename(String name) throws IOException {
        if (name == null || name.length() < 1 || name.length() > 255 || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")
                || !name.toLowerCase(Locale.ROOT).matches(".*\\.jpe?g")) throw new IOException("项目照片文件名无效。");
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) throw new IOException("项目照片文件名无效。");
        return name;
    }
    private static String hashFile(File file) throws IOException {
        MessageDigest sha = digest(); byte[] buffer = new byte[65536];
        try (FileInputStream input = new FileInputStream(file)) { int count; while ((count = input.read(buffer)) != -1) sha.update(buffer, 0, count); }
        StringBuilder result = new StringBuilder(64); for (byte value : sha.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255)); return result.toString();
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static final class Project {
        final String id, name; final long createdAt; final List<Entry> entries;
        Project(String id, String name, long createdAt, List<Entry> entries) { this.id = id; this.name = name; this.createdAt = createdAt; this.entries = entries; }
    }
    private static final class Entry {
        final String identity, file, filename, sha; final long bytes;
        Entry(String identity, String file, String filename, long bytes, String sha) { this.identity = identity; this.file = file; this.filename = filename; this.bytes = bytes; this.sha = sha; }
    }
}

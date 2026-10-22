package cc.huevia.album;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Immutable project-to-gallery directory bindings, separate from editable project metadata. */
public final class PhotoLiveProjectFolders {
    private static final Object LOCK = new Object();
    private static final int MAGIC = 0x504c4631, MAX_RECORD_BYTES = 1024;
    private final File directory;

    public PhotoLiveProjectFolders(File projectDirectory) throws IOException {
        if (projectDirectory == null) throw new IOException("项目相册目录无效。");
        directory = projectDirectory.getCanonicalFile();
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("无法创建项目相册目录记录。");
    }

    /** First committed name chooses the directory. Renaming a project never moves its originals. */
    public String bind(String projectId, String projectName) throws IOException {
        String proposed = PhotoLiveProjectStore.galleryFolder(projectId, projectName);
        synchronized (LOCK) {
            File lockFile = ownedFile(".folders.lock");
            requireRegularIfPresent(lockFile);
            try (RandomAccessFile file = new RandomAccessFile(lockFile, "rw"); FileLock ignored = file.getChannel().lock()) {
                File base = ownedFile(projectId + ".folder"), temporary = ownedFile(projectId + ".folder.part");
                requireRegularIfPresent(base); requireRegularIfPresent(temporary);
                if (exists(base)) {
                    String folder = read(base, projectId);
                    // The binding is immutable. An interrupted later intent cannot replace it.
                    if (exists(temporary)) Files.delete(temporary.toPath());
                    return folder;
                }
                if (exists(temporary)) {
                    String folder = read(temporary, projectId);
                    commit(temporary, base);
                    if (!folder.equals(read(base, projectId))) throw new IOException("项目相册目录未确认保存完成。");
                    return folder;
                }
                write(temporary, projectId, projectName.trim(), proposed);
                read(temporary, projectId);
                commit(temporary, base);
                String actual = read(base, projectId);
                if (!proposed.equals(actual)) throw new IOException("项目相册目录未确认保存完成。");
                return actual;
            }
        }
    }

    private File ownedFile(String name) throws IOException {
        if (!directory.isDirectory() || !directory.equals(directory.getCanonicalFile())) throw new IOException("项目相册目录已变化。");
        File expected = new File(directory, name), canonical = expected.getCanonicalFile();
        if (Files.isSymbolicLink(expected.toPath()) || !expected.equals(canonical) || !directory.equals(canonical.getParentFile())) {
            throw new IOException("项目相册目录记录指向其他文件。");
        }
        return canonical;
    }

    private static boolean exists(File file) { return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS); }
    private static void requireRegularIfPresent(File file) throws IOException {
        if (exists(file) && !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("项目相册目录记录无效。");
    }

    private static void commit(File temporary, File base) throws IOException {
        try { Files.move(temporary.toPath(), base.toPath(), StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary.toPath(), base.toPath()); }
    }

    private static void write(File file, String projectId, String originalName, String folder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeUTF(projectId); data.writeUTF(originalName); data.writeUTF(folder);
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length + 40 > MAX_RECORD_BYTES) throw new IOException("项目相册目录记录过长。");
        try (FileOutputStream output = new FileOutputStream(file); DataOutputStream data = new DataOutputStream(output)) {
            data.writeInt(MAGIC); data.writeInt(payload.length); data.write(payload); data.write(digest().digest(payload));
            data.flush(); output.getFD().sync();
        }
    }

    private static String read(File file, String projectId) throws IOException {
        if (!file.isFile() || file.length() < 40 || file.length() > MAX_RECORD_BYTES) throw new IOException("项目相册目录记录不完整。");
        byte[] encoded = Files.readAllBytes(file.toPath());
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw new IOException("项目相册目录记录格式无效。");
            int length = input.readInt();
            if (length < 0 || length != encoded.length - 40) throw new IOException("项目相册目录记录长度无效。");
            byte[] payload = new byte[length], checksum = new byte[32]; input.readFully(payload); input.readFully(checksum);
            if (!MessageDigest.isEqual(checksum, digest().digest(payload))) throw new IOException("项目相册目录记录校验失败。");
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload))) {
                String id = data.readUTF(), originalName = data.readUTF(), folder = data.readUTF();
                if (!projectId.equals(id) || data.read() != -1 || !folder.equals(PhotoLiveProjectStore.galleryFolder(id, originalName))) {
                    throw new IOException("项目相册目录记录归属不符。");
                }
                return folder;
            }
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}

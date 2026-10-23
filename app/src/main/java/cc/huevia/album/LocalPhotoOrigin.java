package cc.huevia.album;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.AtomicFile;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Read-only evidence that an imported, unchanged JPEG already exists in the phone library.
 * This is source provenance, never ownership or permission to delete the source. */
public final class LocalPhotoOrigin {
    public enum State { NOT_IMPORTED, EXISTING_SOURCE, MISSING_OR_CHANGED, UNAVAILABLE }
    public static final class Result {
        public final State state;
        public final String uri, message;
        private Result(State state, String uri, String message) { this.state = state; this.uri = uri; this.message = message; }
    }
    private static final long MAX_BYTES = 96L * 1024 * 1024;
    private static final String RETRY = "无法核对手机原照片，未新增副本。请重新授权或选择原照片，或勾选“另存新副本”。";
    private LocalPhotoOrigin() { }
    private static Result result(State state) { return new Result(state, "", ""); }

    public static Result check(Context context, DownloadedPhoto photo) {
        if (photo == null || photo.file() == null || !photo.file().getName().matches("phone-[0-9a-f]{64}-[0-9a-f]{64}__.+\\.[jJ][pP][eE]?[gG]")) return result(State.NOT_IMPORTED);
        try {
            File root = context.getFilesDir().getCanonicalFile();
            File picturesRoot = new File(root, "pictures"), pictures = new File(picturesRoot, "camera-album");
            File declaredPictures = new File(new File(context.getFilesDir().getAbsoluteFile(), "pictures"), "camera-album");
            File expected = photo.file().getAbsoluteFile(), source = expected.getCanonicalFile();
            // Android exposes the same app data through /data/user/0 and /data/data.
            // Permit that configured root alias, while keeping every path below it literal and owned.
            for (java.nio.file.Path part : expected.toPath()) if (part.toString().equals("..")) throw new IOException("App 内照片路径无效。");
            File parent = expected.toPath().normalize().getParent().toFile();
            if ((!parent.equals(declaredPictures.toPath().normalize().toFile()) && !parent.equals(pictures))
                    || !pictures.equals(pictures.getCanonicalFile()) || Files.isSymbolicLink(picturesRoot.toPath())
                    || Files.isSymbolicLink(pictures.toPath()) || !pictures.equals(source.getParentFile())
                    || !source.getName().equals(expected.getName()) || Files.isSymbolicLink(expected.toPath()) || !source.isFile()) throw new IOException("App 内照片路径无效。");
            String key = source.getName().substring(0, source.getName().indexOf("__"));
            File directory = new File(root, "local-photo-sources"), record = new File(directory, key + ".source");
            if (!directory.equals(directory.getCanonicalFile()) || Files.isSymbolicLink(directory.toPath())
                    || !record.equals(record.getCanonicalFile()) || Files.isSymbolicLink(record.toPath())) throw new IOException("照片来源记录路径无效。");
            File backup = new File(record.getPath() + ".bak");
            if (!backup.equals(backup.getCanonicalFile()) || Files.isSymbolicLink(backup.toPath())) throw new IOException("照片来源备份路径无效。");
            File readable = backup.isFile() ? backup : record;
            if (!readable.isFile() || readable.length() < 32 || readable.length() > 8192) throw new IOException("照片来源记录不完整。");
            String address, mime, originalSha, retained, workingSha; long declaredBytes, workingBytes;
            try (DataInputStream input = new DataInputStream(new AtomicFile(record).openRead())) {
                if (input.readInt() != 0x4c505332) throw new IOException("照片来源记录版本无效。");
                address = input.readUTF(); input.readUTF(); mime = input.readUTF(); originalSha = input.readUTF();
                retained = input.readUTF(); declaredBytes = input.readLong(); input.readLong(); workingSha = input.readUTF(); workingBytes = input.readLong();
                if (input.read() != -1) throw new IOException("照片来源记录含未知内容。");
            }
            // A PNG's JPEG working copy is a new version and needs its own publication.
            if ("image/png".equals(mime)) return result(State.NOT_IMPORTED);
            if (!"image/jpeg".equals(mime) || !retained.isEmpty() || !originalSha.matches("[0-9a-f]{64}")
                    || !originalSha.equals(workingSha) || workingBytes < 4 || workingBytes > MAX_BYTES
                    || declaredBytes < -1 || declaredBytes >= 0 && declaredBytes != workingBytes
                    || !key.equals("phone-" + hash(address.getBytes(StandardCharsets.UTF_8)) + "-" + originalSha)) throw new IOException("照片来源身份不符。");
            Uri uri = Uri.parse(address);
            if (!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority())
                    || uri.getPath() == null || !uri.getPath().matches("/(external|external_primary|internal)/images/media/[1-9][0-9]*")) {
                return new Result(State.UNAVAILABLE, "", "所选来源暂不能确认已在手机相册中。请从手机照片列表重新选择，或勾选“另存新副本”。");
            }
            long modified = source.lastModified();
            if (source.length() != workingBytes || !workingSha.equals(hashFile(source, workingBytes))) throw new IOException("App 内照片已变化，无法复用原来源。");
            String[] columns = Build.VERSION.SDK_INT >= 29
                    ? new String[]{MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.IS_PENDING}
                    : new String[]{MediaStore.Images.Media.MIME_TYPE};
            try (Cursor cursor = context.getContentResolver().query(uri, columns, null, null, null)) {
                if (cursor == null) throw new IOException("系统未返回原照片状态。");
                if (!cursor.moveToFirst()) return result(State.MISSING_OR_CHANGED);
                if (!"image/jpeg".equalsIgnoreCase(cursor.getString(0))) return result(State.MISSING_OR_CHANGED);
                if (Build.VERSION.SDK_INT >= 29 && cursor.getInt(1) != 0) throw new IOException("手机原照片仍在写入。");
            }
            String current;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("手机原照片暂不可读。");
                current = fingerprint(input, workingBytes);
            }
            if (current == null || !workingSha.equals(current)) return result(State.MISSING_OR_CHANGED);
            if (source.length() != workingBytes || source.lastModified() != modified || !workingSha.equals(hashFile(source, workingBytes))) throw new IOException("App 内照片在核对期间发生变化。");
            return new Result(State.EXISTING_SOURCE, address, "原照片仍在手机相册，未新增副本。");
        } catch (OutOfMemoryError memory) {
            return new Result(State.UNAVAILABLE, "", "核对原照片时内存不足，未新增副本。请稍后重试，或勾选“另存新副本”。");
        } catch (Exception failure) {
            return new Result(State.UNAVAILABLE, "", RETRY);
        }
    }
    private static String hashFile(File file, long bytes) throws IOException {
        try (InputStream input = new FileInputStream(file)) { return fingerprint(input, bytes); }
    }
    /** Null means a readable but different snapshot; errors are deliberately not treated as missing. */
    private static String fingerprint(InputStream input, long expectedBytes) throws IOException {
        MessageDigest digest = digest(); byte[] buffer = new byte[65536]; long bytes = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("原照片核对已取消。");
            bytes += count; if (bytes > expectedBytes) return null;
            digest.update(buffer, 0, count);
        }
        return bytes == expectedBytes ? hex(digest.digest()) : null;
    }
    private static String hash(byte[] bytes) { return hex(digest().digest(bytes)); }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(64); for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255)); return value.toString(); }
}

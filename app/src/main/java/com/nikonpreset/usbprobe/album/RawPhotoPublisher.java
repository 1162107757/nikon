package com.nikonpreset.usbprobe.album;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.AtomicFile;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Worker-thread original-byte NEF export to Pictures/Huevia/RAW, without a document picker. */
public final class RawPhotoPublisher {
    private static final Object LOCK = new Object();
    private static final int MAGIC = 0x52505031;
    private static final String PATH = "Pictures/Huevia/RAW/", MIME = "image/x-nikon-nef";
    private final Context context;

    public RawPhotoPublisher(Context context) {
        if (context == null) throw new IllegalArgumentException("缺少 RAW 相册保存上下文。");
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
    }

    /** Returns the existing verified URI on repeat export, including recovery after process death. */
    public String publish(RawPhotoDownload photo) throws IOException {
        try (PhotoLocalTasks.Lease ignoredTask = PhotoLocalTasks.use()) {
            if (Build.VERSION.SDK_INT < 29 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                throw new IOException("请允许保存照片到系统相册后重试，RAW 原图仍保留在应用内。");
            }
            File source = ownedSource(photo);
            long modified = source.lastModified();
            synchronized (LOCK) {
                File directory = journalDirectory(); File lockFile = child(directory, ".publisher.lock");
                try (RandomAccessFile lock = new RandomAccessFile(lockFile, "rw"); FileLock ignored = lock.getChannel().lock()) {
                    AtomicFile journal = new AtomicFile(child(directory, photo.sha256() + (Build.VERSION.SDK_INT < 29 ? ".legacy.record" : ".record")));
                    return Build.VERSION.SDK_INT < 29 ? publishLegacy(source, photo, modified, journal)
                            : publishModern(source, photo, modified, journal);
                }
            }
        } catch (IOException failure) { throw failure; }
        catch (RuntimeException failure) { throw new IOException("RAW 保存到系统相册未完成，原文件仍保留在应用内，可重试。", failure); }
    }

    private String publishModern(File source, RawPhotoDownload photo, long modified, AtomicFile journal) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Record record = read(journal, photo);
        if (record != null && !record.uri.isEmpty()) {
            Uri uri = checkedUri(record.uri, collection); Row row = readRow(resolver, uri);
            if (row != null) {
                requireOwner(row);
                if (!row.pending) {
                    verifyPublished(resolver, uri, photo); verifySource(source, photo, modified);
                    write(journal, new Record(photo, uri.toString(), true, "")); return uri.toString();
                }
                if (record.published) throw new IOException("系统相册 RAW 状态已变化，未改动已有照片。");
                return finishPending(resolver, row, source, photo, modified, journal);
            }
        }
        // Persist intent before insert. If insert succeeds but its response is lost, the exact
        // owner/path/content marker recovers that pending row instead of making a duplicate.
        write(journal, new Record(photo, "", false, ""));
        List<Row> candidates = recoverRows(resolver, collection, photo);
        if (candidates.size() > 1) throw new IOException("发现多份 RAW 相册保存记录，未继续新增照片。");
        if (candidates.size() == 1) {
            Row row = candidates.get(0);
            if (row.pending) return finishPending(resolver, row, source, photo, modified, journal);
            verifyPublished(resolver, row.uri, photo); verifySource(source, photo, modified);
            write(journal, new Record(photo, row.uri.toString(), true, "")); return row.uri.toString();
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, publicName(photo));
        values.put(MediaStore.Images.Media.MIME_TYPE, MIME);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, PATH);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri inserted = resolver.insert(collection, values);
        if (inserted == null) throw new IOException("无法创建系统相册 RAW 照片，原文件仍保留在应用内。");
        Uri uri = checkedUri(inserted.toString(), collection);
        write(journal, new Record(photo, uri.toString(), false, ""));
        return finishPending(resolver, readRow(resolver, uri), source, photo, modified, journal);
    }

    private String finishPending(ContentResolver resolver, Row row, File source, RawPhotoDownload photo, long modified, AtomicFile journal) throws IOException {
        requirePending(row, photo);
        write(journal, new Record(photo, row.uri.toString(), false, ""));
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(row.uri, "rwt");
        if (descriptor == null) throw new IOException("无法写入系统相册 RAW 照片。");
        try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
            copy(source, output, photo); output.flush(); output.getFD().sync();
        }
        verifySource(source, photo, modified);
        verifyPublished(resolver, row.uri, photo);
        requirePending(readRow(resolver, row.uri), photo);
        ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
        if (resolver.update(row.uri, values, null, null) != 1) throw new IOException("RAW 相册保存状态尚未确认，可重试恢复。");
        Row published = readRow(resolver, row.uri); requireOwner(published);
        if (published.pending) throw new IOException("RAW 原图仍在等待相册确认，可重试。");
        verifyPublished(resolver, row.uri, photo); verifySource(source, photo, modified);
        write(journal, new Record(photo, row.uri.toString(), true, ""));
        return row.uri.toString();
    }

    @SuppressWarnings("deprecation")
    private String publishLegacy(File source, RawPhotoDownload photo, long modified, AtomicFile journal) throws IOException {
        Record previous = read(journal, photo); ContentResolver resolver = context.getContentResolver();
        File directory = legacyDirectory(), target = child(directory, publicName(photo));
        if (previous != null && !previous.path.equals(target.getAbsolutePath())) throw new IOException("RAW 相册保存路径记录不符。");
        if (previous != null && !previous.uri.isEmpty()) {
            Uri uri = checkedUri(previous.uri, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            try (Cursor cursor = resolver.query(uri, new String[] {MediaStore.Images.Media.DATA}, null, null, null)) {
                if (cursor == null) throw new IOException("无法核对 RAW 相册保存记录。");
                if (cursor.moveToFirst()) {
                    verifyPublished(resolver, uri, photo); verifySource(source, photo, modified); return uri.toString();
                }
            }
        }
        File temporary = child(directory, "." + publicName(photo) + ".part");
        if (!target.exists()) {
            if (temporary.exists() && previous == null) throw new IOException("存在未确认的 RAW 相册临时文件，未覆盖已有内容。");
            write(journal, new Record(photo, "", false, target.getAbsolutePath()));
            try (FileOutputStream output = new FileOutputStream(temporary)) { copy(source, output, photo); output.flush(); output.getFD().sync(); }
            verifySource(source, photo, modified); verifyFile(temporary, photo);
            Files.move(temporary.toPath(), target.toPath());
        }
        verifyFile(target, photo); verifySource(source, photo, modified);
        write(journal, new Record(photo, "", false, target.getAbsolutePath()));
        AtomicReference<Uri> scanned = new AtomicReference<>(); CountDownLatch complete = new CountDownLatch(1);
        MediaScannerConnection.scanFile(context, new String[] {target.getAbsolutePath()}, new String[] {MIME},
                (path, uri) -> { scanned.set(uri); complete.countDown(); });
        try {
            if (!complete.await(30, TimeUnit.SECONDS)) throw new IOException("RAW 原图已保存，系统相册仍在更新，请稍后重试。");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("RAW 相册保存已停止，原文件仍保留在应用内。", interrupted);
        }
        if (scanned.get() == null) throw new IOException("RAW 原图已保存，系统相册尚未确认，请稍后重试。");
        Uri uri = checkedUri(scanned.get().toString(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        verifyPublished(resolver, uri, photo); verifySource(source, photo, modified);
        write(journal, new Record(photo, uri.toString(), true, target.getAbsolutePath()));
        return uri.toString();
    }

    private File ownedSource(RawPhotoDownload photo) throws IOException {
        if (photo == null || photo.file() == null) throw new IOException("RAW 原图信息无效。");
        File expected = photo.file().getAbsoluteFile(), file = expected.getCanonicalFile();
        File internal = child(context.getFilesDir().getCanonicalFile(), "camera-raw");
        File external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        boolean owned = internal.equals(file.getParentFile());
        if (external != null) owned |= child(external.getCanonicalFile(), "camera-raw").equals(file.getParentFile());
        if (!owned || !expected.equals(file) || !file.isFile() || Files.isSymbolicLink(expected.toPath())
                || !file.getName().equals("raw-" + photo.sha256() + ".NEF")) throw new IOException("仅可导出应用内已完成的相机 NEF 原文件。");
        if (!new RawPhotoStore(file.getParentFile()).contains(photo)) throw new IOException("RAW 原图缺少有效完成记录。");
        return file;
    }
    private static void verifySource(File source, RawPhotoDownload photo, long modified) throws IOException {
        if (source.length() != photo.byteSize() || source.lastModified() != modified || !photo.sha256().equals(RawPhotoStore.fingerprint(source))) {
            throw new IOException("RAW 原文件在保存过程中发生变化，未确认保存成功。");
        }
    }
    private static void copy(File source, OutputStream output, RawPhotoDownload photo) throws IOException {
        try (InputStream input = new FileInputStream(source)) {
            MessageDigest digest = RawPhotoStore.digest(); long total = 0; byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) {
                interrupted(); total += count;
                if (total > photo.byteSize()) throw new IOException("RAW 原文件大小已变化。");
                digest.update(buffer, 0, count); output.write(buffer, 0, count);
            }
            if (total != photo.byteSize() || !photo.sha256().equals(RawPhotoStore.hex(digest.digest()))) throw new IOException("RAW 原文件不完整，未发布到系统相册。");
        }
    }
    private static void verifyFile(File file, RawPhotoDownload photo) throws IOException {
        if (!file.isFile() || file.length() != photo.byteSize()) throw new IOException("已保存 RAW 原图大小不符，未覆盖已有内容。");
        try (InputStream input = new FileInputStream(file)) { verifyStream(input, photo); }
    }
    private static void verifyPublished(ContentResolver resolver, Uri uri, RawPhotoDownload photo) throws IOException {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("无法核对系统相册 RAW 原图。");
            verifyStream(input, photo);
        }
    }
    private static void verifyStream(InputStream input, RawPhotoDownload photo) throws IOException {
        MessageDigest digest = RawPhotoStore.digest(); long total = 0; byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) != -1) {
            interrupted(); total += count;
            if (total > photo.byteSize()) throw new IOException("系统相册 RAW 原图与完成记录不符。");
            digest.update(buffer, 0, count);
        }
        if (total != photo.byteSize() || !photo.sha256().equals(RawPhotoStore.hex(digest.digest()))) throw new IOException("系统相册 RAW 原图内容已变化，未改动已有照片。");
    }
    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("RAW 相册保存已停止，原文件仍保留在应用内。");
    }

    private List<Row> recoverRows(ContentResolver resolver, Uri collection, RawPhotoDownload photo) throws IOException {
        List<Row> result = new ArrayList<>(); int scanned = 0;
        try (Cursor cursor = resolver.query(MediaStore.setIncludePending(collection), projection(),
                MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?", new String[] {publicName(photo) + "%"}, MediaStore.Images.Media._ID + " ASC")) {
            if (cursor == null) throw new IOException("无法核对 RAW 相册保存记录。");
            while (cursor.moveToNext()) {
                if (++scanned > 32) throw new IOException("RAW 相册保存记录过多，未继续新增照片。");
                Row row = row(cursor, ContentUris.withAppendedId(collection, cursor.getLong(0)));
                if (context.getPackageName().equals(row.owner) && publicName(photo).equals(row.name)
                        && PATH.equals(row.path)) result.add(row);
            }
        }
        return result;
    }
    private Row readRow(ContentResolver resolver, Uri uri) throws IOException {
        try (Cursor cursor = resolver.query(MediaStore.setIncludePending(uri), projection(), null, null, null)) {
            if (cursor == null) throw new IOException("无法核对 RAW 相册照片状态。");
            return cursor.moveToFirst() ? row(cursor, uri) : null;
        }
    }
    private void requireOwner(Row row) throws IOException {
        if (row == null || !context.getPackageName().equals(row.owner)) throw new IOException("RAW 相册照片归属不符，未修改已有照片。");
    }
    private void requirePending(Row row, RawPhotoDownload photo) throws IOException {
        requireOwner(row);
        if (!row.pending || !PATH.equals(row.path) || !publicName(photo).equals(row.name)) throw new IOException("RAW 待保存相册照片归属或状态不符。");
    }
    private static String[] projection() {
        return new String[] {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.OWNER_PACKAGE_NAME, MediaStore.Images.Media.IS_PENDING};
    }
    private static Row row(Cursor cursor, Uri uri) { return new Row(uri, cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getInt(4) != 0); }
    private static String publicName(RawPhotoDownload photo) { return "Huevia_RAW_" + photo.sha256() + ".NEF"; }
    private static Uri checkedUri(String value, Uri collection) throws IOException {
        String prefix = collection.toString() + "/";
        if (value == null || !value.startsWith(prefix) || !value.substring(prefix.length()).matches("[1-9][0-9]{0,18}")) throw new IOException("RAW 相册 URI 无效。");
        return Uri.parse(value);
    }
    private File journalDirectory() throws IOException {
        File parent = context.getFilesDir().getCanonicalFile(), directory = child(parent, "raw-photo-publications");
        if (!directory.isDirectory() && !directory.mkdir() && !directory.isDirectory()) throw new IOException("无法创建 RAW 相册保存记录。");
        return directory;
    }
    private static File child(File parent, String name) throws IOException {
        File expected = new File(parent, name), canonical = expected.getCanonicalFile();
        if (!expected.equals(canonical) || !parent.equals(canonical.getParentFile()) || Files.isSymbolicLink(expected.toPath())) throw new IOException("RAW 相册路径归属不符。");
        return canonical;
    }
    @SuppressWarnings("deprecation")
    private static File legacyDirectory() throws IOException {
        File expected = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsoluteFile(), directory = expected.getCanonicalFile();
        if (!expected.equals(directory) || Files.isSymbolicLink(expected.toPath())) throw new IOException("系统相册目录无效。");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("无法创建系统相册目录。");
        for (String name : new String[] {"Huevia", "RAW"}) {
            directory = child(directory, name);
            if (!directory.isDirectory() && !directory.mkdir() && !directory.isDirectory()) throw new IOException("无法创建 RAW 系统相册目录。");
        }
        return directory;
    }
    private static Record read(AtomicFile journal, RawPhotoDownload photo) throws IOException {
        File base = journal.getBaseFile();
        if (!base.exists() && !new File(base + ".bak").exists()) return null;
        for (String suffix : new String[] {"", ".bak", ".new"}) {
            File file = new File(base + suffix);
            if (Files.isSymbolicLink(file.toPath()) || file.exists() && (!file.isFile() || file.length() > 2048)) throw new IOException("RAW 相册保存记录路径无效。");
        }
        try (DataInputStream input = new DataInputStream(journal.openRead())) {
            if (input.readInt() != MAGIC || input.readLong() != photo.byteSize() || !input.readUTF().equals(photo.sha256())) throw new IOException("RAW 相册保存记录与原文件不符。");
            String uri = input.readUTF(); boolean published = input.readBoolean(); String path = input.readUTF();
            if (uri.length() > 512 || path.length() > 1024 || input.read() != -1) throw new IOException("RAW 相册保存记录无效。");
            return new Record(photo, uri, published, path);
        }
    }
    private static void write(AtomicFile journal, Record record) throws IOException {
        // AtomicFile may touch its recovery/new files as well as the base; reject path substitutions.
        File base = journal.getBaseFile();
        for (String suffix : new String[] {"", ".bak", ".new"}) {
            File path = new File(base + suffix);
            if (Files.isSymbolicLink(path.toPath()) || path.exists() && !path.isFile()) throw new IOException("RAW 相册保存记录路径无效。");
        }
        FileOutputStream output = null;
        try {
            output = journal.startWrite(); DataOutputStream data = new DataOutputStream(output);
            data.writeInt(MAGIC); data.writeLong(record.photo.byteSize()); data.writeUTF(record.photo.sha256());
            data.writeUTF(record.uri); data.writeBoolean(record.published); data.writeUTF(record.path);
            data.flush(); output.getFD().sync(); journal.finishWrite(output); output = null;
            Record actual = read(journal, record.photo);
            if (actual == null || !actual.uri.equals(record.uri) || actual.published != record.published || !actual.path.equals(record.path)) throw new IOException("RAW 相册保存记录尚未确认落盘，可重试恢复。");
        } catch (IOException | RuntimeException failure) {
            if (output != null) journal.failWrite(output);
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("无法保存 RAW 相册完成记录，原文件仍保留在应用内。", failure);
        }
    }
    private static final class Record {
        final RawPhotoDownload photo; final String uri, path; final boolean published;
        Record(RawPhotoDownload photo, String uri, boolean published, String path) { this.photo = photo; this.uri = uri; this.published = published; this.path = path; }
    }
    private static final class Row {
        final Uri uri; final String name, path, owner; final boolean pending;
        Row(Uri uri, String name, String path, String owner, boolean pending) { this.uri = uri; this.name = name; this.path = path; this.owner = owner; this.pending = pending; }
    }
}

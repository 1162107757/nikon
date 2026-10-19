package com.nikonpreset.usbprobe.album;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.media.MediaScannerConnection;
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
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Synchronous worker-thread publication of completed app-owned originals; never deletes a source. */
public final class PhotoLiveGallery {
    private static final Object LOCK = new Object();
    private static final int MAGIC = 0x504c4731;
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final String RELATIVE_PATH = "Pictures/相机预设/";
    private final Context context;

    public PhotoLiveGallery(Context context) {
        if (context == null) throw new IllegalArgumentException("缺少相册保存上下文。");
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
    }

    public enum PublicationStatus { PUBLISHED, MISSING, PENDING, UNAVAILABLE }
    public static final class PublicationState {
        public final PublicationStatus status;
        public final String uri, message;
        PublicationState(PublicationStatus status, String uri, String message) { this.status = status; this.uri = uri; this.message = message; }
    }

    /** Read-only worker query. Missing/modified/foreign provider copies are never reported as saved. */
    public PublicationState publicationStatus(DownloadedPhoto photo) {
        try (PhotoLocalTasks.Lease ignoredTask = PhotoLocalTasks.use()) {
            File source = ownedSource(photo); long bytes = source.length(); String sha = PhotoOriginalStore.contentId(source);
            String id = sourceId(source, sha);
            synchronized (LOCK) {
                File directory = privateJournalDirectory();
                if (Build.VERSION.SDK_INT < 29) {
                    LegacyRecord record = readLegacyRecord(new AtomicFile(new File(directory, id + ".legacy.record")), bytes, sha);
                    if (record == null || record.uri.isEmpty()) {
                        EffectAlias alias = publishedEffectAlias(source, bytes, sha, directory, true);
                        if (alias != null) return new PublicationState(PublicationStatus.PUBLISHED, alias.uri, "");
                        return new PublicationState(record == null ? PublicationStatus.MISSING : PublicationStatus.PENDING, "", "");
                    }
                    Uri uri = checkedUri(record.uri, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                    try (Cursor cursor = context.getContentResolver().query(uri, new String[] {MediaStore.Images.Media.DATA}, null, null, null)) {
                        if (cursor == null) throw new IOException("无法核对系统相册状态。");
                        if (!cursor.moveToFirst()) {
                            EffectAlias alias = publishedEffectAlias(source, bytes, sha, directory, true);
                            return new PublicationState(alias == null ? PublicationStatus.MISSING : PublicationStatus.PUBLISHED, alias == null ? "" : alias.uri, "");
                        }
                    }
                    verifyContent(context.getContentResolver(), uri, bytes, sha);
                    return new PublicationState(PublicationStatus.PUBLISHED, uri.toString(), "");
                }
                Record record = readRecord(new AtomicFile(new File(directory, id + ".record")), bytes, sha);
                if (record == null || record.uri.isEmpty()) {
                    String marker = friendlyPrefix(photo, source, id);
                    List<Row> recovered = recoverRows(context.getContentResolver(), MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), marker);
                    recovered.addAll(recoverRows(context.getContentResolver(), MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), legacyPrefix(source, id)));
                    for (Row candidate : recovered) if (!candidate.pending) {
                        verifyContent(context.getContentResolver(), candidate.uri, bytes, sha);
                        return new PublicationState(PublicationStatus.PUBLISHED, candidate.uri.toString(), "");
                    }
                    if (!PhotoPresetSources.isEffect(source)) {
                        Row alias = existingPublishedAlias(context.getContentResolver(), MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), directory, bytes, sha, id);
                        if (alias != null) return new PublicationState(PublicationStatus.PUBLISHED, alias.uri.toString(), "");
                    } else {
                        EffectAlias alias = publishedEffectAlias(source, bytes, sha, directory, false);
                        if (alias != null) return new PublicationState(PublicationStatus.PUBLISHED, alias.uri, "");
                    }
                    return new PublicationState(recovered.isEmpty() ? PublicationStatus.MISSING : PublicationStatus.PENDING, "", "");
                }
                Uri uri = checkedUri(record.uri, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY));
                Row row = readRow(context.getContentResolver(), uri);
                if (row == null) {
                    EffectAlias alias = publishedEffectAlias(source, bytes, sha, directory, false);
                    return new PublicationState(alias == null ? PublicationStatus.MISSING : PublicationStatus.PUBLISHED, alias == null ? "" : alias.uri, "");
                }
                if (!context.getPackageName().equals(row.owner)) throw new IOException("系统相册副本归属已变化。");
                if (row.pending) return new PublicationState(PublicationStatus.PENDING, uri.toString(), "");
                verifyContent(context.getContentResolver(), uri, bytes, sha);
                return new PublicationState(PublicationStatus.PUBLISHED, uri.toString(), "");
            }
        } catch (Exception | OutOfMemoryError failure) { return new PublicationState(PublicationStatus.UNAVAILABLE, "", failure.getMessage() == null ? "系统相册状态暂不可用。" : failure.getMessage()); }
    }

    /** Call from a local saving worker; completed source bytes and EXIF are copied without recompression. */
    public String publish(DownloadedPhoto photo) throws IOException {
        try (PhotoLocalTasks.Lease ignoredTask = PhotoLocalTasks.use()) {
            if (Build.VERSION.SDK_INT < 29 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                throw new IOException("请允许保存照片到系统相册后重试，原图仍保留在应用内。");
            }
            File source = ownedSource(photo);
            long bytes = source.length(), modified = source.lastModified();
            if (bytes < 4 || bytes > MAX_BYTES) throw new IOException("直播原图大小无效。");
            PhotoPreviewCache.validateJpeg(source, bytes);
            String sha;
            try (InputStream input = new FileInputStream(source)) { sha = fingerprint(input, bytes); }
            if (source.length() != bytes || source.lastModified() != modified) throw new IOException("直播原图发生变化，未保存到系统相册。");
            String id = sourceId(source, sha);
            String prefix = friendlyPrefix(photo, source, id);
            String relativePath = photo.projectId().isEmpty() ? RELATIVE_PATH
                    : RELATIVE_PATH + new PhotoLiveProjectFolders(new File(context.getFilesDir(), "photo-live-projects"))
                            .bind(photo.projectId(), photo.projectName()) + "/";
            synchronized (LOCK) {
                File directory = privateJournalDirectory();
                File lockFile = new File(directory, ".publisher.lock").getCanonicalFile();
                if (!directory.equals(lockFile.getParentFile())) throw new IOException("相册保存记录路径无效。");
                try (RandomAccessFile file = new RandomAccessFile(lockFile, "rw"); FileLock ignored = file.getChannel().lock()) {
                    if (Build.VERSION.SDK_INT < 29) return publishLegacyLocked(source, bytes, sha, id, prefix, directory, relativePath);
                    return publishLocked(source, bytes, sha, id, prefix, directory, relativePath);
                }
            }
        } catch (IOException failure) { throw failure; }
        catch (RuntimeException failure) { throw new IOException("系统相册保存未完成，原图仍保留在应用内。", failure); }
    }

    /** Local deletion transaction owns the exclusive PhotoLocalTasks lease; never removes the app original. */
    public void deletePublished(DownloadedPhoto photo) throws IOException {
        try {
            if (Build.VERSION.SDK_INT < 29 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                throw new IOException("请允许访问系统相册后重试，应用内照片尚未删除。");
            }
            File source = ownedSource(photo); long bytes = source.length(), modified = source.lastModified();
            if (bytes < 4 || bytes > MAX_BYTES) throw new IOException("照片大小无效，未继续删除。");
            PhotoPreviewCache.validateJpeg(source, bytes);
            String sha; try (InputStream input = new FileInputStream(source)) { sha = fingerprint(input, bytes); }
            if (bytes != source.length() || modified != source.lastModified()) throw new IOException("应用内照片已变化，未删除相册副本。");
            String id = sourceId(source, sha);
            String prefix = friendlyPrefix(photo, source, id);
            synchronized (LOCK) {
                File directory = privateJournalDirectory(); File lockFile = new File(directory, ".publisher.lock").getCanonicalFile();
                if (!directory.equals(lockFile.getParentFile())) throw new IOException("相册保存记录路径无效。");
                try (RandomAccessFile file = new RandomAccessFile(lockFile, "rw"); FileLock ignored = file.getChannel().lock()) {
                    rejectChangedSourceHistory(source, bytes, sha, directory);
                    if (Build.VERSION.SDK_INT < 29) deleteLegacyLocked(source, bytes, sha, id, prefix, directory);
                    else {
                        deletePublishedLocked(source, bytes, sha, id, prefix, legacyPrefix(source, id), directory);
                        // A phone may have upgraded since this app published its older public-Pictures copy.
                        deleteLegacyLocked(source, bytes, sha, id, prefix, directory);
                    }
                }
            }
        } catch (IOException failure) { throw failure; }
        catch (RuntimeException failure) { throw new IOException("系统相册副本删除未完成，应用内照片尚未删除。", failure); }
    }

    private static void rejectChangedSourceHistory(File source, long bytes, String sha, File directory) throws IOException {
        File[] files = directory.listFiles(); if (files == null) throw new IOException("无法核对历史相册保存记录。");
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (File file : files) {
            String name = file.getName();
            if (!name.matches("[0-9a-f]{64}(?:\\.legacy)?\\.record(?:\\.bak)?")) continue;
            names.add(name.endsWith(".bak") ? name.substring(0, name.length() - 4) : name);
            if (names.size() > 20000) throw new IOException("历史相册记录过多，未继续删除应用内照片。");
        }
        for (String name : names) {
            File file = new File(directory, name).getCanonicalFile();
            if (!directory.equals(file.getParentFile()) || !name.equals(file.getName())) throw new IOException("历史相册记录路径无效。");
            AtomicFile journal = new AtomicFile(file);
            try (DataInputStream input = new DataInputStream(journal.openRead())) {
                int expected = name.contains(".legacy.") ? 0x504c4c31 : MAGIC;
                if (input.readInt() != expected) throw new IOException("历史相册保存记录损坏，无法安全核对删除目标。");
                long previousBytes = input.readLong(); String previousSha = input.readUTF();
                if (previousBytes < 4 || previousBytes > MAX_BYTES || !previousSha.matches("[0-9a-f]{64}")) throw new IOException("历史相册保存记录无效。");
                String previousId = hex(digest().digest((source.getPath() + "\0" + previousSha).getBytes(StandardCharsets.UTF_8)));
                if (name.startsWith(previousId + ".") && (previousBytes != bytes || !previousSha.equals(sha))) {
                    throw new IOException("应用内照片与历史相册副本不符，未删除照片，请先检查文件。");
                }
            }
        }
    }

    private void deletePublishedLocked(File source, long bytes, String sha, String id, String prefix, String oldPrefix, File directory) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        File recordFile = new File(directory, id + ".record").getCanonicalFile();
        if (!directory.equals(recordFile.getParentFile())) throw new IOException("相册保存记录路径无效。");
        Record record = readRecord(new AtomicFile(recordFile), bytes, sha);
        java.util.Map<String, Row> candidates = new java.util.LinkedHashMap<>();
        if (record != null && !record.uri.isEmpty()) {
            Row row = readRow(resolver, checkedUri(record.uri, collection));
            if (row != null) candidates.put(row.uri.toString(), row);
        }
        EffectAlias alias = publishedEffectAlias(source, bytes, sha, directory, false);
        if (alias != null) {
            Row row = readRow(resolver, checkedUri(alias.uri, collection));
            if (row != null) candidates.put(row.uri.toString(), row);
        }
        // An insert may have succeeded before its URI was returned or its final journal write completed.
        for (String marker : new String[] {prefix, oldPrefix}) try (Cursor cursor = resolver.query(MediaStore.setIncludePending(collection), projection(),
                MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?", new String[] {marker + "%"}, MediaStore.Images.Media._ID + " ASC")) {
            if (cursor == null) throw new IOException("无法核对系统相册删除目标。");
            while (cursor.moveToNext()) {
                Row row = row(cursor, ContentUris.withAppendedId(collection, cursor.getLong(0)));
                if (!matchesName(row.name, marker)) continue;
                candidates.put(row.uri.toString(), row);
                if (candidates.size() > 16) throw new IOException("相册副本记录异常，未继续删除。");
            }
        }
        // Check every candidate first. One modified/foreign row must not silently produce a partial-success result.
        for (Row row : candidates.values()) {
            if (!context.getPackageName().equals(row.owner)) throw new IOException("系统相册副本归属已变化，未删除应用内照片。");
            if (row.pending && (!ownedRelativePath(row.relativePath) || !matchesName(row.name, prefix) && !matchesName(row.name, oldPrefix))) throw new IOException("相册待保存副本状态不符，未继续删除。");
            verifyContent(resolver, row.uri, bytes, sha);
        }
        for (Row row : candidates.values()) {
            Row current = readRow(resolver, row.uri); if (current == null) continue;
            if (!context.getPackageName().equals(current.owner)) throw new IOException("系统相册副本归属已变化，未删除应用内照片。");
            verifyContent(resolver, current.uri, bytes, sha);
            resolver.delete(current.uri, null, null);
            if (readRow(resolver, current.uri) != null) throw new IOException("系统相册尚未确认删除，应用内照片已保留。");
        }
        // Retain the journal: a retry can confirm missing rows, and a later explicit save may create a new copy.
    }

    @SuppressWarnings("deprecation")
    private void deleteLegacyLocked(File source, long bytes, String sha, String id, String prefix, File directory) throws IOException {
        File recordFile = new File(directory, id + ".legacy.record").getCanonicalFile();
        if (!directory.equals(recordFile.getParentFile())) throw new IOException("相册保存记录路径无效。");
        LegacyRecord record = readLegacyRecord(new AtomicFile(recordFile), bytes, sha);
        if (record == null) {
            EffectAlias alias = publishedEffectAlias(source, bytes, sha, directory, true);
            if (alias == null) return; // Never infer ownership of a public legacy file from its display name alone.
            record = new LegacyRecord(bytes, sha, alias.path, alias.uri);
        }
        prefix = legacyRecordPrefix(source, bytes, sha, id, prefix, record.path);
        File target = checkedLegacyDeleteTarget(record.path, prefix);
        if (target.equals(source)) throw new IOException("系统相册记录指向应用原图，未继续删除。");
        ContentResolver resolver = context.getContentResolver(); Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        java.util.Map<String, File> candidates = new java.util.LinkedHashMap<>();
        if (!record.uri.isEmpty()) {
            Uri uri = checkedUri(record.uri, collection);
            try (Cursor cursor = resolver.query(uri, legacyDeleteProjection(false), null, null, null)) {
                if (cursor == null) throw new IOException("无法核对系统相册删除目标。");
                if (cursor.moveToFirst()) {
                    requireLegacyOwner(cursor, 1);
                    candidates.put(uri.toString(), legacyRowFile(cursor.getString(0), source));
                }
            }
        }
        // Recover a scanner result which was not journalled. DATA is the exact previously committed public path.
        try (Cursor cursor = resolver.query(collection, legacyDeleteProjection(true),
                MediaStore.Images.Media.DATA + "=?", new String[] {target.getPath()}, MediaStore.Images.Media._ID + " ASC")) {
            if (cursor == null) throw new IOException("无法核对系统相册删除目标。");
            while (cursor.moveToNext()) {
                requireLegacyOwner(cursor, 2);
                File rowFile = legacyRowFile(cursor.getString(1), source);
                if (!rowFile.equals(target)) throw new IOException("系统相册路径核对不符，未继续删除。");
                candidates.put(ContentUris.withAppendedId(collection, cursor.getLong(0)).toString(), rowFile);
                if (candidates.size() > 16) throw new IOException("相册副本记录异常，未继续删除。");
            }
        }
        if (target.exists()) verifyFile(target, bytes, sha);
        for (String value : candidates.keySet()) verifyContent(resolver, Uri.parse(value), bytes, sha);
        java.util.Set<File> removedPaths = new java.util.HashSet<>();
        for (String value : candidates.keySet()) {
            Uri uri = Uri.parse(value);
            try (Cursor cursor = resolver.query(uri, legacyDeleteProjection(false), null, null, null)) {
                if (cursor == null) throw new IOException("无法核对系统相册删除状态。");
                if (!cursor.moveToFirst()) continue;
                requireLegacyOwner(cursor, 1);
                File rowFile = legacyRowFile(cursor.getString(0), source);
                if (!rowFile.equals(candidates.get(value))) throw new IOException("系统相册照片路径已变化，未继续删除。");
            }
            File rowFile = candidates.get(value);
            if (rowFile.exists()) verifyContent(resolver, uri, bytes, sha);
            else if (!removedPaths.contains(rowFile)) throw new IOException("系统相册照片已变化，未继续删除。");
            resolver.delete(uri, null, null);
            try (Cursor cursor = resolver.query(uri, new String[] {MediaStore.Images.Media.DATA}, null, null, null)) {
                if (cursor == null || cursor.moveToFirst()) throw new IOException("系统相册尚未确认删除，应用内照片已保留。");
            }
            if (rowFile.exists()) { verifyFile(rowFile, bytes, sha); Files.delete(rowFile.toPath()); }
            removedPaths.add(rowFile);
        }
        if (target.exists()) { verifyFile(target, bytes, sha); Files.delete(target.toPath()); }
    }

    private static String[] legacyDeleteProjection(boolean id) {
        if (Build.VERSION.SDK_INT >= 29) return id
                ? new String[] {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA, MediaStore.Images.Media.OWNER_PACKAGE_NAME}
                : new String[] {MediaStore.Images.Media.DATA, MediaStore.Images.Media.OWNER_PACKAGE_NAME};
        return id ? new String[] {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA} : new String[] {MediaStore.Images.Media.DATA};
    }

    private void requireLegacyOwner(Cursor cursor, int ownerColumn) throws IOException {
        if (Build.VERSION.SDK_INT >= 29 && !context.getPackageName().equals(cursor.getString(ownerColumn))) {
            throw new IOException("系统相册副本归属已变化，应用内照片尚未删除。");
        }
    }

    private static File legacyRowFile(String path, File source) throws IOException {
        if (path == null || path.isEmpty()) throw new IOException("系统相册照片路径无效。");
        File expected = new File(path).getAbsoluteFile(), canonical = expected.getCanonicalFile();
        if (!expected.equals(canonical) || Files.isSymbolicLink(expected.toPath()) || canonical.equals(source)) throw new IOException("系统相册照片路径归属不符。");
        return canonical;
    }

    @SuppressWarnings("deprecation")
    private static File checkedLegacyDeleteTarget(String path, String prefix) throws IOException {
        File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getCanonicalFile();
        File expectedRoot = new File(pictures, "相机预设"), root = expectedRoot.getCanonicalFile();
        File expected = new File(path).getAbsoluteFile(), target = expected.getCanonicalFile(), parent = target.getParentFile();
        if (!expectedRoot.equals(root) || !expected.equals(target) || parent == null || !target.getName().equals(prefix + ".jpg")
                || !parent.equals(root) && (!root.equals(parent.getParentFile()) || !ownedRelativePath(RELATIVE_PATH + parent.getName() + "/"))) {
            throw new IOException("系统相册删除路径归属不符。");
        }
        return ownedLegacyFile(parent, target.getName());
    }

    private String publishLocked(File source, long bytes, String sha, String id, String prefix, File directory, String relativePath) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        File recordFile = new File(directory, id + ".record").getCanonicalFile();
        if (!directory.equals(recordFile.getParentFile())) throw new IOException("相册保存记录路径无效。");
        AtomicFile journal = new AtomicFile(recordFile);
        Record record = readRecord(journal, bytes, sha);
        String oldPrefix = legacyPrefix(source, id);
        Row row = null;
        if (record != null && !record.uri.isEmpty()) {
            Uri previous = checkedUri(record.uri, collection);
            row = readRow(resolver, previous);
            if (row != null && !context.getPackageName().equals(row.owner)) throw new IOException("系统相册记录归属不符，未修改已有照片。");
            if (row != null && row.pending && matchesName(row.name, oldPrefix)) prefix = oldPrefix;
        }
        if (row == null) {
            if (!PhotoPresetSources.isEffect(source)) {
                Row alias = existingPublishedAlias(resolver, collection, directory, bytes, sha, id);
                if (alias != null) { writeRecord(journal, new Record(bytes, sha, alias.uri.toString(), true)); return alias.uri.toString(); }
            } else {
                EffectAlias alias = publishedEffectAlias(source, bytes, sha, directory, false);
                if (alias != null) { writeRecord(journal, new Record(bytes, sha, alias.uri, true)); return alias.uri; }
            }
            // Commit intent before insert. The deterministic marker recovers an insert whose returned URI was never journalled.
            writeRecord(journal, new Record(bytes, sha, "", false));
            List<Row> candidates = recoverRows(resolver, collection, prefix);
            if (!oldPrefix.equals(prefix)) candidates.addAll(recoverRows(resolver, collection, oldPrefix));
            for (Row candidate : candidates) if (!candidate.pending) {
                verifyContent(resolver, candidate.uri, bytes, sha);
                writeRecord(journal, new Record(bytes, sha, candidate.uri.toString(), true));
                for (Row stale : candidates) if (stale.pending) deletePending(resolver, stale.uri, matchesName(stale.name, oldPrefix) ? oldPrefix : prefix, stale.relativePath);
                return candidate.uri.toString();
            }
            if (!candidates.isEmpty()) { row = candidates.get(0); if (matchesName(row.name, oldPrefix)) prefix = oldPrefix; }
        }
        if (row != null && !row.pending) {
            // A user may rename/move an already published original. Keep its URI rather than inserting a second copy.
            verifyContent(resolver, row.uri, bytes, sha);
            writeRecord(journal, new Record(bytes, sha, row.uri.toString(), true));
            return row.uri.toString();
        }
        // The same original can be saved from a project view or the general downloads view.
        // Recover its original pending destination even when the latter has no project display metadata.
        if (row != null) {
            if (!ownedRelativePath(row.relativePath)) throw new IOException("系统相册待保存目录归属不符，未修改已有照片。");
            relativePath = row.relativePath;
        }
        Uri pending = row == null ? null : row.uri;
        try {
            if (pending == null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, prefix + ".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                pending = resolver.insert(collection, values);
                if (pending == null) throw new IOException("无法创建系统相册照片，原图仍保留在应用内。");
                pending = checkedUri(pending.toString(), collection);
            }
            Row owned = readRow(resolver, pending);
            requireOwnedPending(owned, prefix, relativePath);
            writeRecord(journal, new Record(bytes, sha, pending.toString(), false));
            copySource(resolver, pending, source, bytes, sha);
            verifyContent(resolver, pending, bytes, sha);
            ContentValues ready = new ContentValues(); ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(pending, ready, null, null) != 1) throw new IOException("系统相册未确认保存完成。");
            Row published = readRow(resolver, pending);
            if (published == null || published.pending || !context.getPackageName().equals(published.owner)) {
                throw new IOException("系统相册保存状态尚未确认，原图仍保留在应用内。");
            }
            // A crash or disk failure here leaves the public row intact; the next call recovers it by URI/marker.
            writeRecord(journal, new Record(bytes, sha, pending.toString(), true));
            return pending.toString();
        } catch (IOException | RuntimeException failure) {
            if (pending != null) try { deletePending(resolver, pending, prefix, relativePath); }
            catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("系统相册保存未完成，原图仍保留在应用内。", failure);
        }
    }

    private File ownedSource(DownloadedPhoto photo) throws IOException {
        if (photo == null || photo.file() == null) throw new IOException("直播原图信息无效。");
        File source = photo.file().getCanonicalFile();
        File internal = new File(new File(context.getFilesDir(), "pictures"), "camera-album").getCanonicalFile();
        File external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        boolean owned = internal.equals(source.getParentFile());
        if (external != null) owned |= new File(external, "camera-album").getCanonicalFile().equals(source.getParentFile());
        if (!owned || !source.isFile() || !source.getName().toLowerCase(Locale.ROOT).matches(".*\\.jpe?g")) {
            throw new IOException("仅可保存应用内已完成的直播 JPEG 原图。");
        }
        return source;
    }

    private File privateJournalDirectory() throws IOException {
        File parent = context.getFilesDir().getCanonicalFile();
        File directory = new File(parent, "photo-live-gallery").getCanonicalFile();
        if (!parent.equals(directory.getParentFile()) || !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建相册保存记录。");
        }
        return directory;
    }

    private List<Row> recoverRows(ContentResolver resolver, Uri collection, String prefix) throws IOException {
        List<Row> rows = new ArrayList<>();
        String where = MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?";
        try (Cursor cursor = resolver.query(MediaStore.setIncludePending(collection), projection(), where,
                new String[] {prefix + "%"}, MediaStore.Images.Media._ID + " ASC")) {
            if (cursor == null) throw new IOException("无法核对系统相册保存记录。");
            while (cursor.moveToNext()) {
                Row row = row(cursor, ContentUris.withAppendedId(collection, cursor.getLong(0)));
                if (context.getPackageName().equals(row.owner) && matchesName(row.name, prefix)
                        && (!row.pending || ownedRelativePath(row.relativePath))) rows.add(row);
                if (rows.size() > 16) throw new IOException("发现重复的相册保存记录，未继续新增照片。");
            }
        }
        return rows;
    }

    /** Existing byte-identical aliases keep their original URI even after adopting the new private completion index. */
    private Row existingPublishedAlias(ContentResolver resolver, Uri collection, File directory, long bytes, String sha, String exceptId) throws IOException {
        java.util.Set<String> effectIds = new java.util.HashSet<>();
        File internal = new File(new File(context.getFilesDir(), "pictures"), "camera-album"), external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        for (File originals : new File[] {internal, external == null ? null : new File(external, "camera-album")}) {
            if (originals == null) continue;
            File[] effects = originals.listFiles(PhotoPresetSources::isEffect);
            if (effects == null) continue;
            if (effects.length > 20000) throw new IOException("效果图数量过多，未继续生成相册副本。");
            for (File effect : effects) effectIds.add(sourceId(effect, sha) + ".record");
        }
        File[] files = directory.listFiles(file -> file.getName().matches("[0-9a-f]{64}\\.record"));
        if (files == null) throw new IOException("无法核对历史相册记录。");
        if (files.length > 20000) throw new IOException("历史相册记录过多，未继续生成重复副本。");
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        for (File file : files) {
            if (file.getName().equals(exceptId + ".record") || effectIds.contains(file.getName())) continue;
            Record previous;
            try (DataInputStream input = new DataInputStream(new AtomicFile(file).openRead())) {
                if (input.readInt() != MAGIC) continue;
                long priorBytes = input.readLong(); String priorSha = input.readUTF(), uri = input.readUTF(); boolean published = input.readBoolean();
                if (input.read() != -1 || priorBytes != bytes || !sha.equals(priorSha) || uri.isEmpty() || !published) continue;
                previous = new Record(bytes, sha, uri, true);
            } catch (IOException malformedUnrelated) { continue; }
            Row row = readRow(resolver, checkedUri(previous.uri, collection));
            if (row == null || row.pending || !context.getPackageName().equals(row.owner)) continue;
            try { verifyContent(resolver, row.uri, bytes, sha); return row; }
            catch (IOException changedAlias) { /* An independently edited alias is retained and is not reused. */ }
        }
        return null;
    }

    private static final class EffectAlias {
        final String uri, path;
        EffectAlias(String uri, String path) { this.uri = uri; this.path = path; }
    }

    /** Only the same effect key with a verified output ledger can share publication across private directories. */
    private EffectAlias publishedEffectAlias(File source, long bytes, String sha, File directory, boolean legacy) throws IOException {
        if (!verifiedEffectOutput(source, bytes, sha)) return null;
        ContentResolver resolver = context.getContentResolver();
        Uri collection = legacy ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        for (File alias : effectAliasPaths(source)) {
            if (alias.equals(source)) continue;
            if (alias.exists()) {
                try { if (alias.length() != bytes || !sha.equals(PhotoOriginalStore.contentId(alias))) continue; }
                catch (IOException changedAlias) { continue; }
            }
            String aliasId = sourceId(alias, sha);
            if (legacy) {
                LegacyRecord record = readLegacyRecord(new AtomicFile(new File(directory, aliasId + ".legacy.record")), bytes, sha);
                if (record == null || record.uri.isEmpty()) continue;
                Uri uri = checkedUri(record.uri, collection);
                try (Cursor cursor = resolver.query(uri, legacyDeleteProjection(false), null, null, null)) {
                    if (cursor == null) throw new IOException("无法核对系统相册保存记录。");
                    if (!cursor.moveToFirst()) continue;
                    requireLegacyOwner(cursor, 1);
                }
                verifyContent(resolver, uri, bytes, sha);
                return new EffectAlias(uri.toString(), record.path);
            }
            Record record = readRecord(new AtomicFile(new File(directory, aliasId + ".record")), bytes, sha);
            if (record == null || record.uri.isEmpty()) continue;
            Uri uri = checkedUri(record.uri, collection); Row row = readRow(resolver, uri);
            if (row == null || row.pending || !context.getPackageName().equals(row.owner)) continue;
            verifyContent(resolver, uri, bytes, sha);
            return new EffectAlias(uri.toString(), "");
        }
        return null;
    }

    private boolean verifiedEffectOutput(File source, long bytes, String sha) throws IOException {
        if (!PhotoPresetSources.isEffect(source)) return false;
        String key = source.getName().substring(7, 71);
        File files = context.getFilesDir().getCanonicalFile(), records = new File(files, "photo-preset-results").getCanonicalFile();
        File expected = new File(records, key + ".record"), record = expected.getCanonicalFile();
        if (!files.equals(records.getParentFile()) || !expected.equals(record) || Files.isSymbolicLink(expected.toPath()) || !records.equals(record.getParentFile())) throw new IOException("效果图完成记录路径无效。");
        File backup = new File(record.getPath() + ".bak");
        if (!record.exists() && !backup.exists()) return false;
        if (record.exists() && record.length() > 256) throw new IOException("效果图完成记录无效。");
        try (DataInputStream input = new DataInputStream(new AtomicFile(record).openRead())) {
            if (input.readInt() != 0x50424531 || input.readLong() != bytes || !input.readUTF().equals(sha) || input.read() != -1) throw new IOException("效果图完成内容不符，未新增相册副本。");
        }
        return true;
    }

    private List<File> effectAliasPaths(File source) throws IOException {
        List<File> paths = new ArrayList<>();
        File external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        for (File directory : new File[] {new File(new File(context.getFilesDir(), "pictures"), "camera-album"), external == null ? null : new File(external, "camera-album")}) {
            if (directory == null) continue;
            File parent = directory.getCanonicalFile(), expected = new File(parent, source.getName()), candidate = expected.getCanonicalFile();
            if (!expected.equals(candidate) || Files.isSymbolicLink(expected.toPath()) || !parent.equals(candidate.getParentFile())) throw new IOException("效果图别名路径无效。");
            if (!paths.contains(candidate)) paths.add(candidate);
        }
        return paths;
    }

    private String legacyRecordPrefix(File source, long bytes, String sha, String id, String prefix, String path) throws IOException {
        String name = new File(path).getName(), old = legacyPrefix(source, id);
        if (name.equals(prefix + ".jpg")) return prefix;
        if (name.equals(old + ".jpg")) return old;
        if (verifiedEffectOutput(source, bytes, sha)) for (File alias : effectAliasPaths(source)) {
            String aliasId = sourceId(alias, sha), legacy = legacyPrefix(alias, aliasId);
            String friendly = prefix.substring(0, prefix.length() - 12) + aliasId.substring(0, 12);
            if (name.equals(legacy + ".jpg")) return legacy;
            if (name.equals(friendly + ".jpg")) return friendly;
        }
        return prefix; // The strict path checker below rejects an unrelated public filename.
    }

    private String friendlyPrefix(DownloadedPhoto photo, File source, String id) throws IOException {
        String filename = photo.filename(), presetName = "";
        if (PhotoPresetSources.isEffect(source)) {
            PhotoPresetSources.Metadata metadata = new PhotoPresetSources(context.getFilesDir(), context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)).metadata(photo);
            if (!metadata.filename.isEmpty()) filename = metadata.filename;
            else presetName = metadata.presetName.isEmpty() ? "预设效果" : metadata.presetName;
        }
        return "Huevia_" + displayStem(filename) + (presetName.isEmpty() ? "" : "_" + displayStem(presetName)) + "_" + id.substring(0, 12);
    }

    private static String sourceId(File source, String sha) throws IOException {
        return hex(digest().digest((source.getCanonicalPath() + "\0" + sha).getBytes(StandardCharsets.UTF_8)));
    }
    private static String legacyPrefix(File source, String id) {
        String privateName = source.getName();
        String name = privateName.substring(privateName.lastIndexOf("__") + (privateName.contains("__") ? 2 : 1));
        int dot = name.lastIndexOf('.'); if (dot > 0) name = name.substring(0, dot);
        StringBuilder stem = new StringBuilder();
        for (int i = 0; i < name.length() && stem.length() < 32; i++) {
            char value = name.charAt(i); if (Character.isLetterOrDigit(value) || value == '-') stem.append(value);
        }
        return (stem.length() == 0 ? "Photo" : stem.toString()) + "-LIVE-" + id;
    }

    private static boolean ownedRelativePath(String value) {
        if (RELATIVE_PATH.equals(value)) return true;
        if (value == null || !value.startsWith(RELATIVE_PATH) || !value.endsWith("/")) return false;
        String leaf = value.substring(RELATIVE_PATH.length(), value.length() - 1);
        return !leaf.isEmpty() && !leaf.contains("/") && !leaf.contains("\\") && !leaf.contains("..");
    }

    @SuppressWarnings("deprecation")
    private String publishLegacyLocked(File source, long bytes, String sha, String id, String prefix, File journalDirectory, String relativePath) throws IOException {
        File recordFile = new File(journalDirectory, id + ".legacy.record").getCanonicalFile();
        if (!journalDirectory.equals(recordFile.getParentFile())) throw new IOException("相册保存记录路径无效。");
        AtomicFile journal = new AtomicFile(recordFile); LegacyRecord record = readLegacyRecord(journal, bytes, sha);
        String oldPrefix = legacyPrefix(source, id);
        if (record != null) prefix = legacyRecordPrefix(source, bytes, sha, id, prefix, record.path);
        ContentResolver resolver = context.getContentResolver();
        if (record != null && !record.uri.isEmpty()) {
            Uri previous = checkedUri(record.uri, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            try (Cursor cursor = resolver.query(previous, new String[] {MediaStore.Images.Media.DATA}, null, null, null)) {
                if (cursor == null) throw new IOException("无法核对系统相册保存记录。");
                if (cursor.moveToFirst()) {
                    verifyContent(resolver, previous, bytes, sha);
                    return previous.toString();
                }
            }
        }
        EffectAlias alias = publishedEffectAlias(source, bytes, sha, journalDirectory, true);
        if (alias != null) {
            writeLegacyRecord(journal, new LegacyRecord(bytes, sha, alias.path, alias.uri));
            return alias.uri;
        }
        File target = record == null ? ownedLegacyFile(legacyDirectory(relativePath), prefix + ".jpg")
                : checkedLegacyTarget(record.path, prefix);
        File targetDirectory = target.getParentFile();
        File temporary = ownedLegacyFile(targetDirectory, "." + prefix + ".part");
        if (target.exists()) {
            verifyFile(target, bytes, sha); // Never overwrite an existing or user-modified public original.
        } else {
            if (temporary.exists() && record == null) throw new IOException("发现未确认的相册临时文件，未覆盖已有内容。");
            writeLegacyRecord(journal, new LegacyRecord(bytes, sha, target.getAbsolutePath(), ""));
            try {
                copySourceFile(source, temporary, bytes, sha);
                Files.move(temporary.toPath(), target.toPath());
            } catch (IOException | RuntimeException failure) {
                if (temporary.isFile()) try { Files.delete(temporary.toPath()); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        verifyFile(target, bytes, sha);
        writeLegacyRecord(journal, new LegacyRecord(bytes, sha, target.getAbsolutePath(), ""));
        AtomicReference<Uri> scanned = new AtomicReference<>(); CountDownLatch scan = new CountDownLatch(1);
        MediaScannerConnection.scanFile(context, new String[] {target.getAbsolutePath()}, new String[] {"image/jpeg"},
                (path, uri) -> { scanned.set(uri); scan.countDown(); });
        try {
            if (!scan.await(30, TimeUnit.SECONDS)) throw new IOException("照片已保存，系统相册仍在更新，请稍后重试。");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("相册保存已停止，原图仍保留在应用内。", interrupted); }
        Uri uri = scanned.get();
        if (uri == null) throw new IOException("照片已保存，系统相册尚未确认，请稍后重试。");
        uri = checkedUri(uri.toString(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        verifyContent(resolver, uri, bytes, sha);
        writeLegacyRecord(journal, new LegacyRecord(bytes, sha, target.getAbsolutePath(), uri.toString()));
        return uri.toString();
    }

    private static File checkedLegacyTarget(String path, String prefix) throws IOException {
        File expected = new File(path).getAbsoluteFile(), canonical = expected.getCanonicalFile();
        File base = legacyDirectory(RELATIVE_PATH), parent = canonical.getParentFile();
        if (!expected.equals(canonical) || !canonical.getName().equals(prefix + ".jpg") || parent == null) throw new IOException("系统相册记录归属不符。");
        if (!base.equals(parent)) {
            if (!base.equals(parent.getParentFile()) || !ownedRelativePath(RELATIVE_PATH + parent.getName() + "/")) throw new IOException("系统相册记录目录归属不符。");
            legacyDirectory(RELATIVE_PATH + parent.getName() + "/");
        }
        return ownedLegacyFile(parent, canonical.getName());
    }

    private static LegacyRecord readLegacyRecord(AtomicFile file, long bytes, String sha) throws IOException {
        File base = file.getBaseFile();
        if (!base.exists() && !new File(base.getPath() + ".bak").exists()) return null;
        try (DataInputStream input = new DataInputStream(file.openRead())) {
            if (input.readInt() != 0x504c4c31 || input.readLong() != bytes || !input.readUTF().equals(sha)) throw new IOException("相册保存记录不完整，未继续新增照片。");
            String path = input.readUTF(), uri = input.readUTF();
            if (input.read() != -1 || path.length() > 512 || uri.length() > 512) throw new IOException("相册保存记录格式无效。");
            return new LegacyRecord(bytes, sha, path, uri);
        }
    }

    private static void writeLegacyRecord(AtomicFile file, LegacyRecord record) throws IOException {
        FileOutputStream output = null;
        try {
            output = file.startWrite(); DataOutputStream data = new DataOutputStream(output);
            data.writeInt(0x504c4c31); data.writeLong(record.bytes); data.writeUTF(record.sha); data.writeUTF(record.path); data.writeUTF(record.uri);
            data.flush(); output.getFD().sync(); file.finishWrite(output); output = null;
            LegacyRecord actual = readLegacyRecord(file, record.bytes, record.sha);
            if (actual == null || !actual.path.equals(record.path) || !actual.uri.equals(record.uri)) throw new IOException("相册保存记录未确认落盘，可重试恢复。");
        } catch (IOException | RuntimeException failure) {
            if (output != null) file.failWrite(output);
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("无法保存相册完成记录，应用内原图已保留。", failure);
        }
    }

    @SuppressWarnings("deprecation")
    private static File legacyDirectory(String relativePath) throws IOException {
        if (!ownedRelativePath(relativePath)) throw new IOException("项目相册目录无效。");
        File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getCanonicalFile();
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("无法创建系统相册目录。");
        String suffix = relativePath.substring("Pictures/".length());
        for (String name : suffix.split("/")) {
            File next = ownedLegacyFile(directory, name);
            if (!next.isDirectory() && !next.mkdirs() && !next.isDirectory()) throw new IOException("无法创建系统相册目录。");
            directory = next;
        }
        return directory;
    }

    private static File ownedLegacyFile(File parent, String name) throws IOException {
        File expected = new File(parent, name), canonical = expected.getCanonicalFile();
        if (Files.isSymbolicLink(expected.toPath()) || !canonical.equals(expected) || !parent.equals(canonical.getParentFile())
                || Files.exists(expected.toPath(), LinkOption.NOFOLLOW_LINKS) && !canonical.isFile() && !canonical.isDirectory()) {
            throw new IOException("系统相册保存路径无效。");
        }
        return canonical;
    }

    private static void copySourceFile(File source, File temporary, long bytes, String sha) throws IOException {
        try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(temporary)) {
            MessageDigest digest = digest(); byte[] buffer = new byte[65536]; long total = 0; int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("相册保存已停止，原图仍保留在应用内。");
                total += count; if (total > bytes) throw new IOException("原图大小发生变化。");
                output.write(buffer, 0, count); digest.update(buffer, 0, count);
            }
            if (total != bytes || !sha.equals(hex(digest.digest()))) throw new IOException("原图发生变化，未发布不完整照片。");
            output.flush(); output.getFD().sync();
        }
    }

    private static void verifyFile(File file, long bytes, String sha) throws IOException {
        if (!file.isFile() || file.length() != bytes) throw new IOException("已保存照片大小不符，未覆盖已有内容。");
        try (InputStream input = new FileInputStream(file)) {
            if (!sha.equals(fingerprint(input, bytes))) throw new IOException("已保存照片内容不符，未覆盖已有内容。");
        }
    }

    private Row readRow(ContentResolver resolver, Uri uri) throws IOException {
        try (Cursor cursor = resolver.query(MediaStore.setIncludePending(uri), projection(), null, null, null)) {
            if (cursor == null) throw new IOException("无法核对系统相册照片状态。");
            return cursor.moveToFirst() ? row(cursor, uri) : null;
        }
    }

    private static String[] projection() {
        return new String[] {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.OWNER_PACKAGE_NAME, MediaStore.Images.Media.IS_PENDING};
    }
    private static Row row(Cursor cursor, Uri uri) {
        return new Row(uri, cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getInt(4) != 0);
    }
    private void requireOwnedPending(Row row, String prefix, String relativePath) throws IOException {
        if (row == null || !row.pending || !context.getPackageName().equals(row.owner)
                || !relativePath.equals(row.relativePath) || !matchesName(row.name, prefix)) {
            throw new IOException("系统相册待保存照片归属或状态不符，未覆盖已有照片。");
        }
    }
    private void deletePending(ContentResolver resolver, Uri uri, String prefix, String relativePath) throws IOException {
        Row existing = readRow(resolver, uri);
        if (existing == null || !existing.pending) return; // Never remove a published/user-modified original.
        requireOwnedPending(existing, prefix, relativePath);
        if (resolver.delete(uri, null, null) != 1 && readRow(resolver, uri) != null) throw new IOException("待保存相册记录未清理完成，可重试恢复。");
    }

    private static void copySource(ContentResolver resolver, Uri uri, File source, long bytes, String sha) throws IOException {
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rwt");
        if (descriptor == null) throw new IOException("无法写入系统相册。");
        try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
             InputStream input = new FileInputStream(source)) {
            MessageDigest digest = digest(); byte[] buffer = new byte[65536]; long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("系统相册保存已停止，应用内原图已保留。");
                total += count;
                if (total > bytes) throw new IOException("直播原图大小发生变化。");
                output.write(buffer, 0, count); digest.update(buffer, 0, count);
            }
            if (total != bytes || !sha.equals(hex(digest.digest()))) throw new IOException("直播原图发生变化，未发布不完整照片。");
            output.flush(); output.getFD().sync();
        }
    }

    private static void verifyContent(ContentResolver resolver, Uri uri, long bytes, String sha) throws IOException {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null || !sha.equals(fingerprint(input, bytes))) throw new IOException("系统相册照片与应用内原图不符，未修改已有照片。");
        }
    }

    private static String fingerprint(InputStream input, long bytes) throws IOException {
        MessageDigest digest = digest(); byte[] buffer = new byte[65536]; long total = 0; int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("相册保存已停止，应用内原图已保留。");
            total += count;
            if (total > bytes) throw new IOException("照片大小与完成记录不符。");
            digest.update(buffer, 0, count);
        }
        if (total != bytes) throw new IOException("照片内容不完整，未发布到系统相册。");
        return hex(digest.digest());
    }

    private static Record readRecord(AtomicFile file, long bytes, String sha) throws IOException {
        File base = file.getBaseFile();
        if (!base.exists() && !new File(base.getPath() + ".bak").exists()) return null;
        try (DataInputStream input = new DataInputStream(file.openRead())) {
            if (input.readInt() != MAGIC || input.readLong() != bytes || !input.readUTF().equals(sha)) throw new IOException("相册保存记录不完整，未继续新增照片。");
            String uri = input.readUTF(); boolean published = input.readBoolean();
            if (input.read() != -1 || uri.length() > 512) throw new IOException("相册保存记录格式无效。");
            return new Record(bytes, sha, uri, published);
        }
    }

    private static void writeRecord(AtomicFile file, Record record) throws IOException {
        FileOutputStream output = null;
        try {
            output = file.startWrite(); DataOutputStream data = new DataOutputStream(output);
            data.writeInt(MAGIC); data.writeLong(record.bytes); data.writeUTF(record.sha); data.writeUTF(record.uri); data.writeBoolean(record.published);
            data.flush(); output.getFD().sync(); file.finishWrite(output); output = null;
            Record actual = readRecord(file, record.bytes, record.sha);
            if (actual == null || !actual.uri.equals(record.uri) || actual.published != record.published) throw new IOException("相册保存记录未确认落盘，可重试恢复。");
        } catch (IOException | RuntimeException failure) {
            if (output != null) file.failWrite(output);
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("无法保存相册完成记录，应用内原图已保留。", failure);
        }
    }

    private static Uri checkedUri(String value, Uri collection) throws IOException {
        String prefix = collection.toString() + "/";
        if (!value.startsWith(prefix) || !value.substring(prefix.length()).matches("[1-9][0-9]{0,18}")) throw new IOException("相册保存 URI 无效。");
        return Uri.parse(value);
    }
    private static boolean matchesName(String name, String prefix) {
        if (name == null || !name.startsWith(prefix)) return false;
        return name.substring(prefix.length()).toLowerCase(Locale.ROOT).matches("(?: ?\\([0-9]+\\))?\\.jpg");
    }
    private static String displayStem(String privateName) {
        String name = privateName == null ? "Photo" : privateName.substring(privateName.lastIndexOf("__") + (privateName.contains("__") ? 2 : 1));
        int dot = name.lastIndexOf('.'); if (dot > 0) name = name.substring(0, dot);
        StringBuilder stem = new StringBuilder();
        for (int i = 0; i < name.length() && stem.length() < 32; i++) {
            char value = name.charAt(i); if (Character.isLetterOrDigit(value) || value == '-' || value == '_' || value == ' ') stem.append(value);
        }
        return stem.length() == 0 ? "Photo" : stem.toString();
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2], alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) { chars[i * 2] = alphabet[(bytes[i] >>> 4) & 15]; chars[i * 2 + 1] = alphabet[bytes[i] & 15]; }
        return new String(chars);
    }
    private static final class Record {
        final long bytes; final String sha, uri; final boolean published;
        Record(long bytes, String sha, String uri, boolean published) { this.bytes = bytes; this.sha = sha; this.uri = uri; this.published = published; }
    }
    private static final class LegacyRecord {
        final long bytes; final String sha, path, uri;
        LegacyRecord(long bytes, String sha, String path, String uri) { this.bytes = bytes; this.sha = sha; this.path = path; this.uri = uri; }
    }
    private static final class Row {
        final Uri uri; final String name, relativePath, owner; final boolean pending;
        Row(Uri uri, String name, String relativePath, String owner, boolean pending) {
            this.uri = uri; this.name = name; this.relativePath = relativePath; this.owner = owner; this.pending = pending;
        }
    }
}

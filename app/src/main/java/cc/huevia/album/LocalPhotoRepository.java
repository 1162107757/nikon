package cc.huevia.album;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;

/** Phone media is read only. Only complete, verified private copies reach the JPEG editor. */
public final class LocalPhotoRepository {
    public enum Access { FULL, PARTIAL, NONE }
    public static final long MAX_IMPORT_BYTES = 96L * 1024 * 1024;
    public static final long MAX_IMPORT_PIXELS = 50_000_000L;
    public static final int MAX_BATCH = 100;
    private final Context context;
    private final ContentResolver resolver;
    private final File pictures, sources;
    private final List<File> photoDirectories = new ArrayList<>();
    private final PhotoDateReader dates = new PhotoDateReader();
    private PhotoLibraryCatalog catalog;

    public LocalPhotoRepository(Context context) {
        this.context = context.getApplicationContext(); resolver = this.context.getContentResolver();
        pictures = new File(new File(context.getFilesDir(), "pictures"), "camera-album");
        sources = new File(context.getFilesDir(), "local-photo-sources");
        photoDirectories.add(pictures);
        File external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (external != null) photoDirectories.add(new File(external, "camera-album"));
    }
    /** Permission state is always read afresh, never persisted. */
    public Access access() {
        if (Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES)) return Access.FULL;
        if (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) return Access.PARTIAL;
        if (Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE)) return Access.FULL;
        return Access.NONE;
    }
    private boolean granted(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    public static final class PhonePhoto {
        public final Uri uri;
        public final String key, filename, mime;
        public final long bytes, date, modified;
        PhonePhoto(Uri uri, String filename, String mime, long bytes, long date, long modified) {
            this.uri = uri; this.filename = filename == null || filename.isBlank() ? "照片" : filename;
            this.mime = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
            this.bytes = bytes; this.date = Math.max(0, date); this.modified = Math.max(0, modified);
            key = uri.toString();
        }
        public String thumbnailKey() { return key + "|" + bytes + "|" + modified; }
        public boolean png() { return "image/png".equals(mime); }
    }
    public static final class Page {
        public final List<PhonePhoto> photos;
        public final int nextOffset;
        public final boolean hasMore;
        Page(List<PhonePhoto> photos, int offset, boolean more) {
            this.photos = Collections.unmodifiableList(photos); nextOffset = offset; hasMore = more;
        }
    }
    /** Bounded foreground page. Phone chronology is explicitly the media's added date. */
    public Page phonePage(int offset, int pageSize) throws IOException {
        if (access() == Access.NONE) throw new IOException("尚未授权读取手机照片。可授权浏览，或用系统选择器选择照片。");
        int limit = Math.min(120, Math.max(1, pageSize));
        String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_MODIFIED};
        Bundle args = new Bundle();
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Images.Media.MIME_TYPE + " IN (?,?)");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{"image/jpeg", "image/png"});
        args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, MediaStore.Images.Media.DATE_ADDED + " DESC, " + MediaStore.Images.Media._ID + " DESC");
        // Manual cursor movement also works with providers that ignore QUERY_ARG_OFFSET/LIMIT.
        List<PhonePhoto> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, args, null)) {
            if (cursor == null) throw new IOException("系统未返回手机照片，请刷新重试。");
            if (!cursor.moveToPosition(Math.max(0, offset))) return new Page(result, Math.max(0, offset), false);
            do {
                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
                result.add(new PhonePhoto(uri, cursor.getString(1), cursor.getString(2), cursor.getLong(3),
                        cursor.getLong(4) * 1000L, cursor.getLong(5)));
            } while (result.size() < limit && cursor.moveToNext());
            return new Page(result, Math.max(0, offset) + result.size(), cursor.moveToNext());
        } catch (SecurityException denied) { throw new IOException("手机照片访问权限已变化，请重新授权或选择照片。", denied); }
        catch (RuntimeException failure) { throw new IOException("无法读取手机照片，请刷新或使用系统选择器。", failure); }
    }
    public PhonePhoto describe(Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("请选择系统提供的照片。");
        String name = "照片", mime; long size = -1, modified = 0, date = 0;
        try {
            mime = resolver.getType(uri);
            try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (index >= 0) name = cursor.getString(index);
                    index = cursor.getColumnIndex(OpenableColumns.SIZE); if (index >= 0 && !cursor.isNull(index)) size = cursor.getLong(index);
                    index = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED); if (index >= 0) modified = cursor.getLong(index);
                    index = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED); if (index >= 0) date = cursor.getLong(index) * 1000L;
                }
            }
        } catch (SecurityException denied) { throw new IOException("这张照片的访问权限已失效，请重新选择。", denied); }
        catch (RuntimeException failure) { throw new IOException("无法读取照片信息，请重新选择。", failure); }
        if (!"image/jpeg".equalsIgnoreCase(mime) && !"image/png".equalsIgnoreCase(mime)) throw new IOException("目前仅支持 JPEG 和 PNG 照片。");
        return new PhonePhoto(uri, name, mime, size, date, modified);
    }
    public List<PhotoLibraryCatalog.PhotoGroup> appPhotos(BooleanSupplier cancel) throws IOException {
        List<DownloadedPhoto> completed = new ArrayList<>();
        for (File directory : photoDirectories) {
            check(cancel);
            if (!directory.exists()) continue;
            File[] files = directory.listFiles(file -> file.isFile() && jpeg(file.getName()));
            if (files == null) throw new IOException("无法读取 App 内照片，请刷新重试。");
            for (File file : files) {
                check(cancel); String name = file.getName(); int marker = name.indexOf("__");
                completed.add(new DownloadedPhoto(file, marker < 0 ? name : name.substring(marker + 2)));
            }
        }
        if (catalog == null) catalog = new PhotoLibraryCatalog(context.getFilesDir(), context.getExternalFilesDir(Environment.DIRECTORY_PICTURES));
        return catalog.list(dates.read(completed, cancel), cancel);
    }
    /** Exact JPEG bytes are copied; PNG source bytes are retained beside a white-flattened JPEG working copy. */
    public DownloadedPhoto importPhoto(PhonePhoto selected, BooleanSupplier cancel) throws IOException {
        try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.use()) {
            return importLocked(selected, cancel);
        } catch (OutOfMemoryError memory) { throw new IOException("这张照片超出当前可用内存，请换一张较小的照片后重试。", memory); }
        catch (SecurityException denied) { throw new IOException("照片访问权限已失效，请重新选择照片。", denied); }
    }
    private DownloadedPhoto importLocked(PhonePhoto selected, BooleanSupplier cancel) throws IOException {
        check(cancel); PhonePhoto before = describe(selected.uri);
        if (!sameMetadata(selected, before)) throw new IOException("手机上的照片已变化，请刷新后重新选择。");
        if (before.bytes > MAX_IMPORT_BYTES) throw new IOException("单张照片不能超过 96 MB。");
        mkdir(pictures); mkdir(sources);
        if (pictures.getUsableSpace() < Math.max(16L * 1024 * 1024, Math.max(0, before.bytes) * 3)) throw new IOException("手机存储空间不足，请释放空间后重试。");
        File temporary = File.createTempFile("import-", ".part", sources), normalized = null;
        try {
            String sha;
            try (InputStream input = open(before.uri); FileOutputStream output = new FileOutputStream(temporary)) {
                sha = transfer(input, output, cancel); output.getFD().sync();
            }
            if (before.bytes >= 0 && before.bytes != temporary.length()) throw new IOException("照片尚未完整读取，请重新选择后重试。");
            verifyImage(temporary, before.mime, cancel);
            PhonePhoto after = describe(before.uri);
            if (!sameMetadata(before, after)) throw new IOException("读取期间照片已变化，请刷新后重试。");
            try (InputStream again = open(before.uri)) {
                if (!sha.equals(transfer(again, null, cancel))) throw new IOException("读取期间照片内容已变化，请刷新后重试。");
            }
            String identity = hash(before.uri.toString().getBytes(StandardCharsets.UTF_8));
            String key = "phone-" + identity + "-" + sha;
            String filename = cleanName(before.filename, before.png());
            File target = new File(pictures, key + "__" + filename);
            if (target.exists()) {
                PhotoPreviewCache.validateJpeg(target, target.length());
                if (before.png()) verifyPngWorkingCopy(key, before, sha, target, cancel);
                else if (!sha.equals(hashFile(target, cancel))) throw new IOException("App 内工作副本已变化，请移除该副本后重试。");
                return new DownloadedPhoto(target, filename, "", "", before.date > 0 ? before.date : target.lastModified());
            }
            File retained = null;
            if (before.png()) {
                retained = new File(sources, key + ".png");
                if (retained.exists() && !sha.equals(hashFile(retained, cancel))) throw new IOException("PNG 源副本校验失败，未覆盖已有文件。");
                normalized = File.createTempFile("normalize-", ".part", pictures);
                flattenPng(temporary, normalized, cancel);
                PhotoPreviewCache.validateJpeg(normalized, normalized.length());
                if (!retained.exists()) atomicCopy(temporary, retained, cancel);
            }
            check(cancel);
            File working = before.png() ? normalized : temporary;
            writeSourceRecord(key, before, sha, retained == null ? "" : retained.getName(), hashFile(working, cancel), working.length());
            if (before.png()) {
                if (!normalized.renameTo(target)) throw new IOException("无法保存 App 内工作副本。"); normalized = null;
            } else atomicCopy(temporary, target, cancel);
            long date = before.date > 0 ? before.date : System.currentTimeMillis(); target.setLastModified(date);
            return new DownloadedPhoto(target, filename, "", "", date);
        } finally { temporary.delete(); if (normalized != null) normalized.delete(); }
    }
    private void writeSourceRecord(String key, PhonePhoto photo, String sha, String retained, String workingSha, long workingBytes) throws IOException {
        AtomicFile file = new AtomicFile(new File(sources, key + ".source")); FileOutputStream stream = null;
        try {
            stream = file.startWrite(); DataOutputStream out = new DataOutputStream(stream);
            out.writeInt(0x4c505332); out.writeUTF(photo.uri.toString()); out.writeUTF(photo.filename);
            out.writeUTF(photo.mime); out.writeUTF(sha); out.writeUTF(retained); out.writeLong(photo.bytes); out.writeLong(photo.date);
            out.writeUTF(workingSha); out.writeLong(workingBytes);
            out.flush(); stream.getFD().sync(); file.finishWrite(stream); stream = null;
        } finally { if (stream != null) file.failWrite(stream); }
    }
    private void verifyPngWorkingCopy(String key, PhonePhoto photo, String sha, File working, BooleanSupplier cancel) throws IOException {
        AtomicFile record = new AtomicFile(new File(sources, key + ".source"));
        try (DataInputStream input = new DataInputStream(record.openRead())) {
            if (input.readInt() != 0x4c505332 || !photo.uri.toString().equals(input.readUTF())) throw new IOException("PNG 工作副本的来源记录不符。");
            input.readUTF();
            if (!photo.mime.equals(input.readUTF()) || !sha.equals(input.readUTF()) || !(key + ".png").equals(input.readUTF())) throw new IOException("PNG 工作副本的来源校验失败。");
            input.readLong(); input.readLong(); String expected = input.readUTF(); long bytes = input.readLong();
            if (input.read() != -1 || bytes != working.length() || !expected.equals(hashFile(working, cancel))
                    || !sha.equals(hashFile(new File(sources, key + ".png"), cancel))) throw new IOException("PNG 或 JPEG 工作副本已变化，未复用该副本。");
        }
    }
    private static void flattenPng(File png, File jpeg, BooleanSupplier cancel) throws IOException {
        Bitmap source = null, working = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB); options.inScaled = false;
            source = BitmapFactory.decodeFile(png.getPath(), options);
            if (source == null) throw new IOException("PNG 无法完整解码。");
            check(cancel); working = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(working); canvas.drawColor(Color.WHITE); canvas.drawBitmap(source, 0, 0, null);
            check(cancel);
            try (FileOutputStream out = new FileOutputStream(jpeg)) {
                if (!working.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IOException("PNG 工作副本转换失败。"); out.getFD().sync();
            }
            // PNG eXIf can carry orientation too; only the new JPEG receives metadata.
            try {
                ExifInterface original = new ExifInterface(png.getPath()), output = new ExifInterface(jpeg.getPath());
                String orientation = original.getAttribute(ExifInterface.TAG_ORIENTATION);
                if (orientation != null) { output.setAttribute(ExifInterface.TAG_ORIENTATION, orientation); output.saveAttributes(); }
            } catch (IOException unsupportedMetadata) { /* The pixel conversion remains valid without optional PNG metadata. */ }
        } finally { if (source != null) source.recycle(); if (working != null) working.recycle(); }
    }
    public Bitmap thumbnail(PhonePhoto photo, int edge) throws IOException {
        try (InputStream input = open(photo.uri)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeStream(input, null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("无法读取缩略图。");
            Bitmap bitmap;
            try (InputStream pixels = open(photo.uri)) { bitmap = BitmapFactory.decodeStream(pixels, null, thumbOptions(bounds, edge)); }
            return orient(bitmap, photo.uri);
        } catch (OutOfMemoryError memory) { throw new IOException("缩略图内存不足。", memory); }
    }
    public Bitmap thumbnail(DownloadedPhoto photo, int edge) throws IOException {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(photo.file().getPath(), bounds);
            Bitmap bitmap = BitmapFactory.decodeFile(photo.file().getPath(), thumbOptions(bounds, edge));
            if (bitmap == null) throw new IOException("照片已移走或无法读取。");
            return orient(bitmap, Uri.fromFile(photo.file()));
        } catch (OutOfMemoryError memory) { throw new IOException("缩略图内存不足。", memory); }
    }
    private Bitmap orient(Bitmap bitmap, Uri uri) throws IOException {
        if (bitmap == null) throw new IOException("无法读取缩略图。");
        try (InputStream input = "file".equals(uri.getScheme()) ? new FileInputStream(uri.getPath()) : open(uri)) {
            ExifInterface exif = new ExifInterface(input); int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case 2: matrix.setScale(-1, 1); break; case 3: matrix.setRotate(180); break;
                case 4: matrix.setScale(1, -1); break; case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case 6: matrix.setRotate(90); break; case 7: matrix.setRotate(270); matrix.postScale(-1, 1); break;
                case 8: matrix.setRotate(270); break; default: return bitmap;
            }
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle(); return rotated;
        } catch (IOException | RuntimeException unsupported) { return bitmap; }
    }
    private static BitmapFactory.Options thumbOptions(BitmapFactory.Options bounds, int edge) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig = Bitmap.Config.RGB_565; options.inScaled = false;
        int size = 1; while (bounds.outWidth / size > edge * 2 || bounds.outHeight / size > edge * 2) size *= 2;
        options.inSampleSize = size; return options;
    }
    private InputStream open(Uri uri) throws IOException {
        InputStream input = resolver.openInputStream(uri); if (input == null) throw new IOException("照片不可用，请重新选择。"); return input;
    }
    private static boolean sameMetadata(PhonePhoto a, PhonePhoto b) {
        return a.uri.equals(b.uri) && a.mime.equals(b.mime) && (a.bytes < 0 || b.bytes < 0 || a.bytes == b.bytes)
                && (a.modified == 0 || b.modified == 0 || a.modified == b.modified);
    }
    private static void verifyImage(File file, String mime, BooleanSupplier cancel) throws IOException {
        if ("image/png".equals(mime)) verifyPng(file, cancel); else verifyJpeg(file, cancel);
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.getPath(), bounds);
        if (!mime.equals(bounds.outMimeType) || bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("照片格式与文件内容不符。");
        long pixels = (long) bounds.outWidth * bounds.outHeight;
        if (pixels > MAX_IMPORT_PIXELS) throw new IOException("照片超过 5000 万像素，无法安全编辑。");
        long memory = pixels * ("image/png".equals(mime) ? 8 : 4);
        if (memory > Runtime.getRuntime().maxMemory() * .65) throw new IOException("照片尺寸超过本机可编辑范围，请选择较小的照片。");
        Bitmap decoded;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setTargetSampleSize(thumbOptions(bounds, 512).inSampleSize);
                    decoder.setOnPartialImageListener(error -> false);
                });
            } catch (ImageDecoder.DecodeException incomplete) { throw new IOException("照片内容损坏或未完整下载，请换一张照片后重试。", incomplete); }
        } else decoded = BitmapFactory.decodeFile(file.getPath(), thumbOptions(bounds, 512));
        if (decoded == null) throw new IOException("照片无法完整解码。"); decoded.recycle(); check(cancel);
    }
    /** Check every marker/segment and entropy stream through the final EOI, not just the filename. */
    private static void verifyJpeg(File file, BooleanSupplier cancel) throws IOException {
        PhotoPreviewCache.validateJpeg(file, file.length());
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 65536))) {
            in.readUnsignedShort(); boolean frame = false, scan = false; int next = -1; byte[] segment = new byte[65536];
            for (;;) {
                check(cancel); int marker;
                if (next >= 0) { marker = next; next = -1; }
                else { if (in.read() != 0xff) throw new IOException("JPEG 数据段损坏。"); do { marker = in.read(); } while (marker == 0xff); }
                if (marker == 0xd9) { if (!frame || !scan || in.read() != -1) throw new IOException("JPEG 数据不完整。"); return; }
                if (marker <= 0 || marker == 0xd8 || (marker >= 0xd0 && marker <= 0xd7)) throw new IOException("JPEG 数据段无效。");
                int length = in.readUnsignedShort(); if (length < 2) throw new IOException("JPEG 数据被截断。");
                if (marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) frame = true;
                in.readFully(segment, 0, length - 2);
                if (marker != 0xda) continue;
                scan = true; int block = 0;
                for (;;) {
                    if ((block++ & 65535) == 0) check(cancel);
                    int value = in.read(); if (value == -1) throw new IOException("JPEG 缺少完整结束标记。");
                    if (value != 0xff) continue;
                    do { value = in.read(); } while (value == 0xff);
                    if (value == -1) throw new IOException("JPEG 数据被截断。");
                    if (value == 0 || value >= 0xd0 && value <= 0xd7) continue;
                    next = value; break;
                }
            }
        } catch (java.io.EOFException truncated) { throw new IOException("JPEG 数据被截断。", truncated); }
    }
    private static void verifyPng(File file, BooleanSupplier cancel) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (input.readLong() != 0x89504e470d0a1a0aL) throw new IOException("PNG 文件头无效。");
            boolean header = false, data = false; byte[] buffer = new byte[32768];
            for (;;) {
                check(cancel); long length = Integer.toUnsignedLong(input.readInt()); int type = input.readInt();
                if (length > MAX_IMPORT_BYTES) throw new IOException("PNG 数据段过大。");
                CRC32 crc = new CRC32(); for (int shift = 24; shift >= 0; shift -= 8) crc.update(type >>> shift & 255);
                if (!header && (type != 0x49484452 || length != 13)) throw new IOException("PNG 缺少图片信息。");
                if (type == 0x49484452) { if (header) throw new IOException("PNG 图片信息重复。"); header = true; }
                if (type == 0x49444154) data = true;
                long remaining = length; while (remaining > 0) { check(cancel); int count = input.read(buffer, 0, (int)Math.min(buffer.length, remaining)); if (count < 0) throw new IOException("PNG 数据被截断。"); crc.update(buffer, 0, count); remaining -= count; }
                if (Integer.toUnsignedLong(input.readInt()) != crc.getValue()) throw new IOException("PNG 内容校验失败。");
                if (type == 0x49454e44) { if (length != 0 || !data || input.read() != -1) throw new IOException("PNG 数据不完整。"); return; }
            }
        } catch (java.io.EOFException truncated) { throw new IOException("PNG 数据被截断。", truncated); }
    }
    private static String transfer(InputStream input, FileOutputStream output, BooleanSupplier cancel) throws IOException {
        MessageDigest digest = digest(); byte[] buffer = new byte[65536]; long size = 0; int count;
        while ((count = input.read(buffer)) != -1) { check(cancel); size += count; if (size > MAX_IMPORT_BYTES) throw new IOException("单张照片不能超过 96 MB。"); digest.update(buffer, 0, count); if (output != null) output.write(buffer, 0, count); }
        if (size < 4) throw new IOException("照片内容为空或不完整。"); return hex(digest.digest());
    }
    private static String hashFile(File file, BooleanSupplier cancel) throws IOException { try (InputStream in = new FileInputStream(file)) { return transfer(in, null, cancel); } }
    private static String hash(byte[] bytes) { return hex(digest().digest(bytes)); }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(64); for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255)); return out.toString(); }
    private static void atomicCopy(File from, File to, BooleanSupplier cancel) throws IOException {
        File part = File.createTempFile("copy-", ".part", to.getParentFile());
        try {
            try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(part)) { transfer(in, out, cancel); out.getFD().sync(); }
            check(cancel); if (to.exists() || !part.renameTo(to)) throw new IOException("无法提交照片副本，请重试。");
        } finally { part.delete(); }
    }
    private static String cleanName(String name, boolean png) {
        String value = name.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_");
        int dot = value.lastIndexOf('.'); if (dot > 0) value = value.substring(0, dot);
        if (value.length() > 48) value = value.substring(0, 48);
        if (value.isBlank()) value = "照片";
        return value + (png ? "_PNG白底.jpg" : ".jpg");
    }
    private static boolean jpeg(String name) { String lower = name.toLowerCase(Locale.ROOT); return lower.endsWith(".jpg") || lower.endsWith(".jpeg"); }
    private static void mkdir(File directory) throws IOException { if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建 App 内照片目录。"); }
    private static void check(BooleanSupplier cancel) throws InterruptedIOException { if (cancel.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("照片读取已取消。"); }
}

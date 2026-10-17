package com.nikonpreset.usbprobe.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.AtomicFile;

import com.nikonpreset.usbprobe.R;
import com.nikonpreset.usbprobe.ncp.NcpPreviewProfile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Worker-only image processing for the explicitly labelled, phone-side template approximation. */
public final class PresetPreviewRenderer {
    private static final int MAX_EDGE = 960;
    private static final int MAX_PIXELS = 1_000_000;
    private static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;
    private static final String BUILT_IN_VERSION = "neutral-synthetic-v1";
    private static final String CUSTOM_HASH = "custom-sha256";

    private final Context context;
    private final SharedPreferences preferences;
    private final Object templateLock = new Object();
    private volatile TemplateInfo info;
    // Only these unmodified pixels are retained. Rendered bitmaps belong to the caller's cache.
    private TemplatePixels cached;

    public PresetPreviewRenderer(Context context) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        preferences = this.context.getSharedPreferences("preset-preview-template", Context.MODE_PRIVATE);
        String hash = preferences.getString(CUSTOM_HASH, "");
        info = new TemplateInfo(hash != null && hash.matches("[0-9a-f]{64}") ? hash : "");
    }

    /** Main-thread safe: no image, disk, digest or pixel work. */
    public String versionKey() { return NcpPreviewProfile.MODEL_VERSION + ':' + info.version; }
    public String templateLabel() { return info.custom ? "自选模板" : "内置模板"; }
    public boolean hasCustomTemplate() { return info.custom; }

    /** Copies a selected image into private storage, correcting orientation and bounding its size. */
    public void setTemplate(Uri source) throws IOException {
        try { saveTemplate(source); }
        catch (OutOfMemoryError unavailable) {
            throw new IOException("图片处理内存不足，请选择较小的模板图片。", unavailable);
        }
    }

    private void saveTemplate(Uri source) throws IOException {
        if (source == null) throw new IOException("请选择模板图片。");
        byte[] original;
        try (InputStream input = context.getContentResolver().openInputStream(source)) {
            if (input == null) throw new IOException("无法读取模板图片。");
            original = readBounded(input);
        }
        Bitmap normalized = decodeBounded(original, true);
        byte[] jpeg;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!normalized.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                throw new IOException("模板图片无法保存。");
            }
            jpeg = output.toByteArray();
        } finally {
            // This bitmap is private to this worker and has never been attached to an ImageView.
            normalized.recycle();
        }
        String hash = sha256(jpeg);
        TemplateInfo next = new TemplateInfo(hash);
        // Cache the saved JPEG's decoded pixels so current and future launches use identical input.
        TemplatePixels decoded = pixels(decodeBounded(jpeg, false), next.version);
        synchronized (templateLock) {
            TemplateInfo previous = info;
            AtomicFile target = customFile(hash);
            FileOutputStream output = null;
            boolean attemptedPreferences = false;
            try {
                output = target.startWrite();
                output.write(jpeg);
                output.getFD().sync();
                target.finishWrite(output);
                output = null;
                // AtomicFile can log a failed final rename rather than throwing. The reference
                // must never be switched until the complete, correctly named file is readable.
                try (InputStream published = target.openRead()) {
                    if (!hash.equals(sha256(readBounded(published)))) {
                        throw new IOException("模板图片未完整保存，请重试。");
                    }
                }
                attemptedPreferences = true;
                if (!preferences.edit().putString(CUSTOM_HASH, hash).commit()) {
                    throw new IOException("模板设置未保存，请重试。");
                }
                cached = decoded;
                info = next;
            } catch (IOException | RuntimeException failure) {
                if (output != null) target.failWrite(output);
                boolean oldReferenceSaved = !attemptedPreferences;
                if (attemptedPreferences) {
                    // A failed commit may already have changed SharedPreferences' memory map.
                    // Restore that map too. If disk recovery remains uncertain, retain both
                    // complete content-addressed files so either committed reference is valid.
                    SharedPreferences.Editor rollback = preferences.edit();
                    if (previous.custom) rollback.putString(CUSTOM_HASH, previous.hash);
                    else rollback.remove(CUSTOM_HASH);
                    try { oldReferenceSaved = rollback.commit(); }
                    catch (RuntimeException ignored) { oldReferenceSaved = false; }
                }
                if (oldReferenceSaved && !hash.equals(previous.hash)) deleteUnreferenced(target);
                if (failure instanceof IOException) throw (IOException) failure;
                throw new IOException("模板图片无法保存。", failure);
            }
            // A crash before this cleanup only leaves an unreferenced file, never a missing
            // referenced template. Do not delete the current file when reselecting identical input.
            if (previous.custom && !previous.hash.equals(hash)) deleteUnreferenced(customFile(previous.hash));
        }
    }

    /** Worker only. A failed settings commit leaves the previous template intact. */
    public void resetTemplate() {
        synchronized (templateLock) {
            if (!preferences.edit().remove(CUSTOM_HASH).commit()) {
                // Keep the in-memory preference aligned with info when commit reports failure.
                if (info.custom) preferences.edit().putString(CUSTOM_HASH, info.hash).commit();
                throw new IllegalStateException("模板设置未保存，请重试。");
            }
            TemplateInfo previous = info;
            cached = null;
            info = new TemplateInfo("");
            if (previous.custom) deleteUnreferenced(customFile(previous.hash));
        }
    }

    /** Returns a new bitmap; never modifies or publishes the cached template pixel array. */
    public Bitmap render(byte[] ncp) throws IOException {
        try {
            NcpPreviewProfile profile = NcpPreviewProfile.fromNcp(ncp);
            TemplatePixels template = snapshot();
            int[] output = profile.render(template.argb, template.width, template.height);
            return Bitmap.createBitmap(output, template.width, template.height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError unavailable) {
            throw new IOException("暂时没有足够内存模拟预设，请稍后重试。", unavailable);
        }
    }

    /** Worker only. The caller owns this fresh copy, suitable for the original-template viewer. */
    public Bitmap originalTemplate() throws IOException {
        try {
            TemplatePixels template = snapshot();
            return Bitmap.createBitmap(template.argb, template.width, template.height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError unavailable) {
            throw new IOException("暂时没有足够内存查看模板，请稍后重试。", unavailable);
        }
    }

    private TemplatePixels snapshot() throws IOException {
        synchronized (templateLock) {
            TemplateInfo current = info;
            if (cached != null && cached.version.equals(current.version)) return cached;
            byte[] bytes;
            if (current.custom) {
                try (InputStream input = customFile(current.hash).openRead()) { bytes = readBounded(input); }
                if (!current.hash.equals(sha256(bytes))) {
                    throw new IOException("模板文件与设置不符，请重新选择或重置模板。");
                }
            } else {
                try (InputStream input = context.getResources().openRawResource(R.drawable.preset_template)) {
                    bytes = readBounded(input);
                }
            }
            cached = pixels(decodeBounded(bytes, !current.custom), current.version);
            return cached;
        }
    }

    private static TemplatePixels pixels(Bitmap bitmap, String version) {
        try {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            int[] argb = new int[width * height];
            bitmap.getPixels(argb, 0, width, 0, 0, width, height);
            return new TemplatePixels(version, width, height, argb);
        } finally { bitmap.recycle(); }
    }

    private AtomicFile customFile(String hash) {
        return new AtomicFile(new File(context.getFilesDir(), "preset-preview-" + hash + ".jpeg"));
    }

    private static void deleteUnreferenced(AtomicFile file) {
        // Cleanup failure cannot invalidate a successfully committed template reference.
        try { file.delete(); }
        catch (RuntimeException ignored) { }
    }

    private static Bitmap decodeBounded(byte[] bytes, boolean orient) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int width = bounds.outWidth, height = bounds.outHeight;
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535
                || (long) width * height > 200_000_000L) {
            throw new IOException("无法读取这张模板图片，请选择 JPEG、PNG 或 WebP 图片。");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        options.inSampleSize = 1;
        while (((width + (long) options.inSampleSize - 1) / options.inSampleSize)
                * ((height + (long) options.inSampleSize - 1) / options.inSampleSize) > MAX_PIXELS
                || Math.max(width, height) / (long) options.inSampleSize > 2L * MAX_EDGE) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("模板图片无法解码。");
        try {
            if (orient) {
                int orientation = ExifInterface.ORIENTATION_NORMAL;
                // Formats without EXIF have normal orientation. Decoding is still validated above.
                try (InputStream input = new ByteArrayInputStream(bytes)) {
                    orientation = new ExifInterface(input).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                } catch (IOException noExif) { }
                Matrix matrix = orientation(orientation);
                if (!matrix.isIdentity()) {
                    Bitmap transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    if (transformed != bitmap) { bitmap.recycle(); bitmap = transformed; }
                }
            }
            float scale = Math.min(1f, MAX_EDGE / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
            if (scale < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
                if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled; }
            }
            if (bitmap.hasAlpha()) {
                Bitmap opaque = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(opaque);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(bitmap, 0, 0, null);
                bitmap.recycle(); bitmap = opaque;
            }
            return bitmap;
        } catch (RuntimeException | OutOfMemoryError failure) {
            bitmap.recycle();
            throw failure;
        }
    }

    private static Matrix orientation(int value) {
        Matrix matrix = new Matrix();
        switch (value) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
            default: break;
        }
        return matrix;
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count, total = 0;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("模板读取已取消。");
            total += count;
            if (total > MAX_SOURCE_BYTES) throw new IOException("模板图片超过 20 MB，请选择较小的图片。");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder encoded = new StringBuilder(64);
            for (byte value : hash) encoded.append(Character.forDigit((value >>> 4) & 15, 16))
                    .append(Character.forDigit(value & 15, 16));
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IOException("无法校验模板图片。", impossible); }
    }

    private static final class TemplateInfo {
        final boolean custom;
        final String hash, version;
        TemplateInfo(String hash) {
            this.hash = hash;
            custom = !hash.isEmpty();
            version = custom ? "custom-" + hash : BUILT_IN_VERSION;
        }
    }

    private static final class TemplatePixels {
        final String version;
        final int width, height;
        final int[] argb;
        TemplatePixels(String version, int width, int height, int[] argb) {
            this.version = version; this.width = width; this.height = height; this.argb = argb;
        }
    }
}

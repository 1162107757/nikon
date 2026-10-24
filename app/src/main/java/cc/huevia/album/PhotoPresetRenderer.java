package cc.huevia.album;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.media.ExifInterface;
import android.content.res.AssetManager;
import cc.huevia.ncp.PhotoBorder;
import cc.huevia.ncp.BorderPreviewProfile;
import cc.huevia.ncp.NcpPreviewProfile;
import cc.huevia.ncp.Np3PreviewProfile;
import cc.huevia.ncp.Np3MonochromePreviewProfile;
import cc.huevia.ncp.PresetPreviewProfile;
import cc.huevia.ncp.SpatialPreviewFilter;
import cc.huevia.ncp.PhotoGeometry;
import cc.huevia.ncp.GeometryPreviewProfile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Full-resolution local JPEG renderer. One mutable sRGB bitmap and bounded row/halo storage. */
public final class PhotoPresetRenderer {
    private final AssetManager assets;
    public PhotoPresetRenderer(){this(null);}
    public PhotoPresetRenderer(AssetManager assets){this.assets=assets;}
    public static final String MODEL_VERSION = NcpPreviewProfile.MODEL_VERSION + ":full-jpeg95-srgb-v1";
    public static final String NP3_MODEL_VERSION = Np3PreviewProfile.MODEL_VERSION + ":full-jpeg95-srgb-v1";
    public static final String NP3_MONOCHROME_MODEL_VERSION = Np3MonochromePreviewProfile.MODEL_VERSION + ":full-jpeg95-srgb-v1";
    public static final String NP3_STANDARD_MONOCHROME_MODEL_VERSION = Np3MonochromePreviewProfile.STANDARD_MODEL_VERSION + ":full-jpeg95-srgb-v1";
    public static final String LEGACY_NP3_MODEL_VERSION = "np3-0310-encoded-hsl-zones-v1:full-jpeg95-srgb-v1";
    public static final String LEGACY_NP3_SPATIAL_MODEL_VERSION = "np3-0310-encoded-hsl-zones-spatial-v2:full-jpeg95-srgb-v1";
    /** Preserve legacy NCP identities while keeping each phone colour model's outputs separate. */
    public static String modelVersion(PresetPreviewProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile");
        return profile.modelVersion() + ":full-jpeg95-srgb-v1";
    }
    /** The legacy full/Q95 identity remains byte-compatible. Other actual specifications use a compact, durable identity. */
    public static String modelVersion(PresetPreviewProfile profile, PhotoExportSpec.Resolved spec) {
        return spec.isFullJpeg95() ? modelVersion(profile) : exportModel(modelVersion(profile), spec.identity());
    }
    static String exportModel(String model, String actualSpec) {
        return "export-jpeg-v1:" + PhotoEditStore.hash((model + "\n" + actualSpec).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public static final class Dimensions {
        public final int contentWidth, contentHeight, width, height;
        Dimensions(int cw, int ch, int w, int h) { contentWidth = cw; contentHeight = ch; width = w; height = h; }
    }
    /** Dimensions are in the final displayed orientation, including the proportional full-size border. Worker only. */
    public static Dimensions dimensions(File source, PresetPreviewProfile profile) throws IOException {
        BitmapFactory.Options bounds = bounds(source);
        int orientation = orientation(new ExifInterface(source.getPath()));
        PhotoGeometry.Plan plan = GeometryPreviewProfile.geometry(profile).plan(bounds.outWidth, bounds.outHeight, orientation);
        int[] framed = BorderPreviewProfile.border(profile).dimensions(plan.width, plan.height);
        if (framed[0] > 65535 || framed[1] > 65535) throw new IOException("成品尺寸超过 JPEG 支持范围。");
        return new Dimensions(plan.width, plan.height, framed[0], framed[1]);
    }
    private static final String[] CAPTURE_TAGS = {"Orientation", "DateTime", "DateTimeOriginal", "DateTimeDigitized",
            "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized", "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
            "Make", "Model", "LensMake", "LensModel", "ExposureTime", "FNumber", "ISOSpeedRatings", "PhotographicSensitivity",
            "ExposureProgram", "ExposureBiasValue", "MeteringMode", "Flash", "FocalLength", "FocalLengthIn35mmFilm", "WhiteBalance"};
    public interface Progress { void update(double progress); }
    /** Completed work only: codecs have no byte-progress API, so they advance at their boundaries. */
    private static void progress(Progress progress, double value) { if (progress != null) progress.update(value); }
    interface Pixels extends SpatialPreviewFilter.Pixels {}
    public static final class Cancelled extends IOException { Cancelled() { super("批量处理已取消。"); } }

    /** Writes only to a caller-owned temporary file. Never downscales or edits the source. */
    public void render(File source, File target, PresetPreviewProfile profile, BooleanSupplier cancel, Progress progress) throws IOException {
        render(source, target, profile, PhotoExportSpec.full(), cancel, progress);
    }
    /** Colour and spatial effects run at full source resolution; only the finished canvas may be reduced. */
    public void render(File source, File target, PresetPreviewProfile profile, PhotoExportSpec spec, BooleanSupplier cancel, Progress progress) throws IOException {
        Bitmap bitmap = null;
        try {
            if (spec == null || profile == null || cancel == null) throw new IllegalArgumentException("profile/spec/cancel");
            if (source.getCanonicalFile().equals(target.getCanonicalFile())) throw new IOException("不能覆盖原图。");
            check(cancel);
            progress(progress, 0);
            BitmapFactory.Options bounds = bounds(source);
            ExifInterface original = new ExifInterface(source.getPath());
            Dimensions natural = dimensions(source, profile);
            PhotoExportSpec.Resolved resolved;
            try { resolved = spec.resolve(natural.width, natural.height); }
            catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
            space(target, (long) natural.width * natural.height * 4 + 1024 * 1024);
            progress(progress, .02);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1; options.inScaled = false; options.inMutable = true;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888; options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            bitmap = BitmapFactory.decodeFile(source.getPath(), options);
            if (bitmap == null || bitmap.getWidth() != bounds.outWidth || bitmap.getHeight() != bounds.outHeight
                    || !bitmap.isMutable() || bitmap.getConfig() != Bitmap.Config.ARGB_8888
                    || !ColorSpace.get(ColorSpace.Named.SRGB).equals(bitmap.getColorSpace())) throw new IOException("无法按完整尺寸解码为 sRGB，未缩小原图。");
            progress(progress, .10);
            Bitmap working = bitmap;
            applyRows(new Pixels() {
                public int width() { return working.getWidth(); } public int height() { return working.getHeight(); }
                public void read(int[] pixels, int row, int rows) { working.getPixels(pixels, 0, width(), 0, row, width(), rows); }
                public void write(int[] pixels, int row, int rows) { working.setPixels(pixels, 0, width(), 0, row, width(), rows); }
            }, profile, cancel, value -> progress(progress, .10 + .70 * value));
            check(cancel);
            PhotoGeometry geometry=GeometryPreviewProfile.geometry(profile);
            int outputWidth=bounds.outWidth,outputHeight=bounds.outHeight;
            PhotoBorder border=BorderPreviewProfile.border(profile);
            boolean normalized = !geometry.isNeutral() || !border.isNone() || !resolved.isFullJpeg95();
            if(normalized){
                Bitmap composed=PhotoComposition.apply(bitmap,geometry,orientation(original));if(composed!=bitmap)bitmap.recycle();bitmap=composed;
                outputWidth=bitmap.getWidth();outputHeight=bitmap.getHeight();check(cancel);
            }
            if(!border.isNone()){
                Bitmap framed=new PhotoBorderRenderer(assets).apply(bitmap,border,PhotoCaptureInfo.read(source));bitmap.recycle();bitmap=framed;
                outputWidth=bitmap.getWidth();outputHeight=bitmap.getHeight();check(cancel);
            }
            if (!resolved.isFullJpeg95() && (bitmap.getWidth() != resolved.width || bitmap.getHeight() != resolved.height)) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, resolved.width, resolved.height, true);
                if (scaled != bitmap) bitmap.recycle(); bitmap = scaled;
                outputWidth = bitmap.getWidth(); outputHeight = bitmap.getHeight(); check(cancel);
            }
            try (FileOutputStream output = new FileOutputStream(target)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, resolved.quality, output)) throw new IOException("效果图 JPEG 编码失败。");
                output.flush(); output.getFD().sync();
            }
            check(cancel);
            progress(progress, .93);
            // The new encoder stream has no original thumbnail. Copy only capture metadata,
            // never original offsets/thumbnail bytes, GPS or an incompatible ICC payload.
            ExifInterface result = new ExifInterface(target.getPath());
            for (String tag : CAPTURE_TAGS) { String value = original.getAttribute(tag); if (value != null) result.setAttribute(tag, value); }
            if(normalized)result.setAttribute(ExifInterface.TAG_ORIENTATION,"1");
            result.setAttribute("ImageWidth", String.valueOf(outputWidth)); result.setAttribute("ImageLength", String.valueOf(outputHeight));
            result.setAttribute("PixelXDimension", String.valueOf(outputWidth)); result.setAttribute("PixelYDimension", String.valueOf(outputHeight));
            result.setAttribute("ColorSpace", "1"); result.saveAttributes();
            PhotoExifRationals.preserve(source,target);
            try (FileOutputStream sync = new FileOutputStream(target, true)) { sync.getFD().sync(); }
            progress(progress, .97);
            check(cancel); PhotoPreviewCache.validateJpeg(target, target.length());
            BitmapFactory.Options verified = new BitmapFactory.Options(); verified.inJustDecodeBounds = true; BitmapFactory.decodeFile(target.getPath(), verified);
            if (verified.outWidth != outputWidth || verified.outHeight != outputHeight) throw new IOException("效果图尺寸核对失败，未发布。");
            check(cancel); progress(progress, 1);
        } catch (OutOfMemoryError memory) { throw new IOException("此原图完整尺寸处理内存不足；未缩小图片，原图已保留。", memory); }
        finally { if (bitmap != null) bitmap.recycle(); }
    }

    public static final class DetailResult {
        public final int width, height;
        DetailResult(int width, int height) { this.width = width; this.height = height; }
    }
    /** Lossless, full-resolution, pre-JPEG content. No frame, no export resize, no thumbnail fallback. */
    public DetailResult renderDetail(File source, File target, PresetPreviewProfile profile, boolean before,
                                     BooleanSupplier cancel, Progress progress) throws IOException {
        if (profile == null || cancel == null) throw new IllegalArgumentException("profile/cancel");
        if (source.getCanonicalFile().equals(target.getCanonicalFile())) throw new IOException("不能覆盖原图。");
        Bitmap bitmap = null;
        try {
            check(cancel); progress(progress, 0);
            PhotoPreviewCache.validateJpeg(source, source.length());
            BitmapFactory.Options bounds = bounds(source);
            PhotoGeometry geometry = GeometryPreviewProfile.geometry(profile);
            int exif = orientation(new ExifInterface(source.getPath()));
            PhotoGeometry.Plan plan = geometry.plan(bounds.outWidth, bounds.outHeight, exif);
            space(target, (long) plan.width * plan.height * 5 + 1024 * 1024);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1; options.inScaled = false; options.inMutable = true;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888; options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            bitmap = BitmapFactory.decodeFile(source.getPath(), options);
            if (bitmap == null || bitmap.getWidth() != bounds.outWidth || bitmap.getHeight() != bounds.outHeight || !bitmap.isMutable()
                    || bitmap.getConfig() != Bitmap.Config.ARGB_8888 || !ColorSpace.get(ColorSpace.Named.SRGB).equals(bitmap.getColorSpace()))
                throw new IOException("无法读取真实完整像素，未使用缩略图。");
            // Resource density must never change the 1 image pixel : 1 framebuffer pixel contract.
            bitmap.setDensity(Bitmap.DENSITY_NONE); progress(progress, .10);
            if (!before) {
                Bitmap working = bitmap;
                applyRows(new Pixels() {
                    public int width() { return working.getWidth(); } public int height() { return working.getHeight(); }
                    public void read(int[] pixels, int row, int rows) { working.getPixels(pixels, 0, width(), 0, row, width(), rows); }
                    public void write(int[] pixels, int row, int rows) { working.setPixels(pixels, 0, width(), 0, row, width(), rows); }
                }, profile, cancel, value -> progress(progress, .10 + .70 * value));
            }
            check(cancel); progress(progress, .80);
            Bitmap composed = PhotoComposition.apply(bitmap, geometry, exif);
            if (composed != bitmap) bitmap.recycle(); bitmap = composed; bitmap.setDensity(Bitmap.DENSITY_NONE);
            check(cancel);
            try (FileOutputStream output = new FileOutputStream(target)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("真实细节暂存失败。");
                output.flush(); output.getFD().sync();
            }
            check(cancel); progress(progress, .95);
            BitmapFactory.Options verified = new BitmapFactory.Options(); verified.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(target.getPath(), verified);
            if (verified.outWidth != plan.width || verified.outHeight != plan.height || !"image/png".equals(verified.outMimeType))
                throw new IOException("真实细节尺寸核对失败。");
            check(cancel); progress(progress, 1); return new DetailResult(plan.width, plan.height);
        } catch (OutOfMemoryError memory) { throw new IOException("细节暂时无法载入，返回后可重试。", memory); }
        finally { if (bitmap != null) bitmap.recycle(); }
    }
    private static BitmapFactory.Options bounds(File source) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getPath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 65535 || bounds.outHeight > 65535 || !"image/jpeg".equals(bounds.outMimeType)) throw new IOException("仅支持完整 JPEG 原图。");
        return bounds;
    }
    private static int orientation(ExifInterface original) { int value = original.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1); return value >= 1 && value <= 8 ? value : 1; }
    private static void space(File target, long needed) throws IOException {
        File directory = target.getCanonicalFile().getParentFile();
        if (directory == null || !directory.isDirectory()) throw new IOException("暂存目录不可用。");
        long free = directory.getUsableSpace(); if (free > 0 && free < needed) throw new IOException("存储空间不足，请释放空间后重试。");
    }

    /** Independent of Android codecs so full-resolution strip/cancellation policy can be host-tested. */
    static void applyRows(Pixels image, PresetPreviewProfile profile, BooleanSupplier cancel, Progress progress) throws IOException {
        int width = image.width(), height = image.height();
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) throw new IOException("JPEG 原图尺寸无效。");
        try {
            profile.renderPixels(image, cancel, progress == null ? null : progress::update);
        } catch (IOException failure) {
            check(cancel);
            throw failure;
        }
    }
    static void check(BooleanSupplier cancel) throws Cancelled { if (cancel.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Cancelled(); }
}

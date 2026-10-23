package cc.huevia.album;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.media.ExifInterface;
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
        Bitmap bitmap = null;
        try {
            check(cancel);
            progress(progress, 0);
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || !"image/jpeg".equals(bounds.outMimeType)) throw new IOException("仅支持完整 JPEG 原图。");
            ExifInterface original = new ExifInterface(source.getPath());
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
            if(!geometry.isNeutral()){
                int orientation=original.getAttributeInt(ExifInterface.TAG_ORIENTATION,1);if(orientation<1||orientation>8)orientation=1;
                Bitmap composed=PhotoComposition.apply(bitmap,geometry,orientation);bitmap.recycle();bitmap=composed;
                outputWidth=bitmap.getWidth();outputHeight=bitmap.getHeight();check(cancel);
            }
            try (FileOutputStream output = new FileOutputStream(target)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) throw new IOException("完整尺寸效果图编码失败。");
                output.flush(); output.getFD().sync();
            }
            check(cancel);
            progress(progress, .93);
            // The new encoder stream has no original thumbnail. Copy only capture metadata,
            // never original offsets/thumbnail bytes, GPS or an incompatible ICC payload.
            ExifInterface result = new ExifInterface(target.getPath());
            for (String tag : CAPTURE_TAGS) { String value = original.getAttribute(tag); if (value != null) result.setAttribute(tag, value); }
            if(!geometry.isNeutral())result.setAttribute(ExifInterface.TAG_ORIENTATION,"1");
            result.setAttribute("ImageWidth", String.valueOf(outputWidth)); result.setAttribute("ImageLength", String.valueOf(outputHeight));
            result.setAttribute("PixelXDimension", String.valueOf(outputWidth)); result.setAttribute("PixelYDimension", String.valueOf(outputHeight));
            result.setAttribute("ColorSpace", "1"); result.saveAttributes();
            try (FileOutputStream sync = new FileOutputStream(target, true)) { sync.getFD().sync(); }
            progress(progress, .97);
            check(cancel); PhotoPreviewCache.validateJpeg(target, target.length());
            BitmapFactory.Options verified = new BitmapFactory.Options(); verified.inJustDecodeBounds = true; BitmapFactory.decodeFile(target.getPath(), verified);
            if (verified.outWidth != outputWidth || verified.outHeight != outputHeight) throw new IOException("效果图尺寸核对失败，未发布。");
            check(cancel); progress(progress, 1);
        } catch (OutOfMemoryError memory) { throw new IOException("此原图完整尺寸处理内存不足；未缩小图片，原图已保留。", memory); }
        finally { if (bitmap != null) bitmap.recycle(); }
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

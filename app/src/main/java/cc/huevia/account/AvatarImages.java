package cc.huevia.account;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Worker-only image preparation. Re-encoding removes EXIF and location metadata before upload. */
public final class AvatarImages {
    private static final int MAX_SOURCE = 20 * 1024 * 1024;
    public static final class Prepared implements AutoCloseable {
        public final Bitmap preview;
        public final byte[] jpeg;
        Prepared(Bitmap preview, byte[] jpeg) { this.preview = preview; this.jpeg = jpeg; }
        @Override public void close() { Arrays.fill(jpeg, (byte) 0); }
    }
    private AvatarImages() { }

    public static Prepared prepare(Context context, Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())) throw invalid();
        byte[] bytes;
        try (InputStream input = context.getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw invalid();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0 || count > MAX_SOURCE - output.size() || Thread.currentThread().isInterrupted()) throw invalid();
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        Bitmap decoded = null, oriented = null, square = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth < 64 || bounds.outHeight < 64 || bounds.outWidth > 32768 || bounds.outHeight > 32768
                    || (long) bounds.outWidth * bounds.outHeight > 128_000_000) throw invalid();
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2;
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (decoded == null) throw invalid();
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream input = new ByteArrayInputStream(bytes)) { orientation = new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, orientation); }
            catch (IOException ignored) { /* Images without EXIF are already upright. */ }
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
            }
            oriented = matrix.isIdentity() ? decoded : Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
            int side = Math.min(oriented.getWidth(), oriented.getHeight()), size = Math.min(512, Math.max(64, side));
            square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(square); canvas.drawColor(Color.WHITE);
            android.graphics.Rect source = new android.graphics.Rect((oriented.getWidth()-side)/2, (oriented.getHeight()-side)/2,
                    (oriented.getWidth()+side)/2, (oriented.getHeight()+side)/2);
            canvas.drawBitmap(oriented, source, new android.graphics.Rect(0, 0, size, size), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
            byte[] result = null;
            for (int quality = 92; quality >= 52; quality -= 10) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!square.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw invalid();
                result = output.toByteArray(); if (result.length <= AccountRepository.MAX_AVATAR_BYTES) break;
            }
            if (result == null || result.length > AccountRepository.MAX_AVATAR_BYTES) throw invalid();
            return new Prepared(square, result);
        } catch (RuntimeException | OutOfMemoryError error) { throw invalid(); }
        finally {
            Arrays.fill(bytes, (byte) 0);
            if (oriented != null && oriented != decoded) oriented.recycle();
            if (decoded != null) decoded.recycle();
        }
    }
    private static IOException invalid() { return new AccountException("INVALID_AVATAR", 400, "无法读取这张图片，请选择 20 MB 以内、至少 64 像素的照片。"); }
}

package com.nikonpreset.usbprobe.library;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.IOException;

/** Reject HTML/non-image downloads and bound decoding cost before committing a preview cache. */
final class PreviewImages {
    private PreviewImages() {}
    static void validate(byte[] bytes) throws IOException {
        boolean png = bytes.length >= 32 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G' && bytes[4] == 13 && bytes[5] == 10
                && bytes[6] == 26 && bytes[7] == 10 && bytes[bytes.length - 8] == 'I'
                && bytes[bytes.length - 7] == 'E' && bytes[bytes.length - 6] == 'N' && bytes[bytes.length - 5] == 'D';
        boolean jpeg = bytes.length >= 4 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
                && bytes[bytes.length - 2] == (byte) 0xff && bytes[bytes.length - 1] == (byte) 0xd9;
        boolean gif = bytes.length >= 14 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a'
                && bytes[bytes.length - 1] == 0x3b;
        boolean webp = bytes.length >= 20 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P'
                && (((long) bytes[4] & 255) | (((long) bytes[5] & 255) << 8)
                | (((long) bytes[6] & 255) << 16) | (((long) bytes[7] & 255) << 24)) == bytes.length - 8L;
        if (!(png || jpeg || gif || webp)) throw new IOException("样片不是完整的 PNG、JPEG、GIF 或 WebP；未缓存。");
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 16384 || bounds.outHeight > 16384
                    || (long) bounds.outWidth * bounds.outHeight > 64_000_000L)
                throw new IOException("样片尺寸无效或过大，未缓存。");
            BitmapFactory.Options sample = new BitmapFactory.Options(); sample.inSampleSize = 1;
            while (bounds.outWidth / sample.inSampleSize > 1024 || bounds.outHeight / sample.inSampleSize > 1024)
                sample.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, sample);
            if (bitmap == null) throw new IOException("样片无法解码，未缓存。");
            bitmap.recycle();
        } catch (RuntimeException | OutOfMemoryError e) { throw new IOException("样片无法安全解码，未缓存。", e); }
    }
}

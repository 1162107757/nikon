package android.graphics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
/** Explicit host acquisition facade: records the actual render input path and maps four payload bytes to one pixel. */
public final class BitmapFactory {
    public static Bitmap decodeStream(java.io.InputStream input){throw new UnsupportedOperationException("Border assets require native Android decoding.");}
    public static final List<String> decodedPaths = new CopyOnWriteArrayList<>();
    public static volatile boolean failDecode;
    public static final class Options {
        public boolean inJustDecodeBounds, inScaled, inMutable;
        public int inSampleSize, outWidth, outHeight;
        public String outMimeType;
        public Bitmap.Config inPreferredConfig;
        public ColorSpace inPreferredColorSpace;
    }
    public static Bitmap decodeFile(String filename, Options options) {
        try {
            byte[] bytes = Files.readAllBytes(Path.of(filename));
            if (bytes.length < 8 || (bytes[0] & 255) != 255 || (bytes[1] & 255) != 216
                    || (bytes[bytes.length - 2] & 255) != 255 || (bytes[bytes.length - 1] & 255) != 217) return null;
            options.outWidth = options.outHeight = 1; options.outMimeType = "image/jpeg";
            if (options.inJustDecodeBounds) return null;
            decodedPaths.add(Path.of(filename).toAbsolutePath().normalize().toString());
            if (failDecode) return null;
            return new Bitmap(((bytes[2] & 255) << 24) | ((bytes[3] & 255) << 16) | ((bytes[4] & 255) << 8) | (bytes[5] & 255));
        } catch (java.io.IOException failure) { return null; }
    }
    public static Bitmap decodeByteArray(byte[] bytes, int offset, int length, Options options) {
        throw new AssertionError("Library preview decoding is outside this synthetic batch-render facade");
    }
}

package android.graphics;
import java.io.IOException;
import java.io.OutputStream;
/** Synthetic one-pixel payload facade for exercising the real renderer control path, not a JPEG codec. */
public final class Bitmap {
    public static volatile boolean failCompress;
    public static volatile Runnable beforeCompress;
    public enum Config { ARGB_8888 }
    public enum CompressFormat { JPEG }
    private int pixel;
    Bitmap(int pixel) { this.pixel = pixel; }
    public int getWidth() { return 1; }
    public int getHeight() { return 1; }
    public boolean isMutable() { return true; }
    public Config getConfig() { return Config.ARGB_8888; }
    public ColorSpace getColorSpace() { return ColorSpace.get(ColorSpace.Named.SRGB); }
    public void getPixels(int[] target, int offset, int stride, int x, int y, int width, int height) { target[offset] = pixel; }
    public void setPixels(int[] source, int offset, int stride, int x, int y, int width, int height) { pixel = source[offset]; }
    public boolean compress(CompressFormat format, int quality, OutputStream output) {
        Runnable callback = beforeCompress; if (callback != null) callback.run();
        if (failCompress) return false;
        try { output.write(new byte[]{(byte)255, (byte)216, (byte)(pixel >>> 24), (byte)(pixel >>> 16), (byte)(pixel >>> 8), (byte)pixel, (byte)255, (byte)217}); return true; }
        catch (IOException failure) { return false; }
    }
    public void recycle() { }
}

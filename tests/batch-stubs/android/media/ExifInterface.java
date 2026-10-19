package android.media;
/** Host-only no-op metadata boundary. The suite makes no Android EXIF preservation claim. */
public final class ExifInterface {
    public ExifInterface(String path) { }
    public String getAttribute(String tag) { return null; }
    public void setAttribute(String tag, String value) { }
    public void saveAttributes() { }
}

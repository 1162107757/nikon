package android.media;
/** Host-only no-op metadata boundary. The suite makes no Android EXIF preservation claim. */
public final class ExifInterface {
    public static final String TAG_ORIENTATION="Orientation";
    public ExifInterface(String path) throws java.io.IOException { }
    public String getAttribute(String tag) { return null; }
    public double getAttributeDouble(String tag,double fallback){return fallback;}
    public int getAttributeInt(String tag,int fallback){return fallback;}
    public void setAttribute(String tag, String value) { }
    public void saveAttributes() { }
}

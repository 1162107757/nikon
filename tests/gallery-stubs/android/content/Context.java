package android.content;
import java.io.File;
public final class Context {
    private final File root;
    public final ContentResolver resolver;
    public boolean permissionGranted = true, scanReturnsNull;
    public int scanRequests;
    public Context(File root) { this.root = root; new File(root, "files").mkdirs(); resolver = new ContentResolver(new File(root, "media"), getPackageName()); }
    public Context getApplicationContext() { return this; }
    public File getFilesDir() { return new File(root, "files"); }
    public File getExternalFilesDir(String type) { return new File(root, "external/" + type); }
    public ContentResolver getContentResolver() { return resolver; }
    public String getPackageName() { return "com.nikonpreset.usbprobe"; }
    public int checkSelfPermission(String permission) { return permissionGranted ? 0 : -1; }
}

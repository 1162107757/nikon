package android.content;

import java.io.File;

/** Host-only filesystem context; the production controller owns its real file operations. */
public final class Context {
    private final File root;
    public Context(File root) { this.root = root; }
    public Context getApplicationContext() { return this; }
    public android.content.res.AssetManager getAssets() { return null; }
    public File getCacheDir() { return new File(root, "cache"); }
    public File getFilesDir() { return new File(root, "files"); }
    public File getExternalFilesDir(String type) { return new File(root, "external/" + type); }
}

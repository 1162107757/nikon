package android.content;
public class Context {
    public static final String CONNECTIVITY_SERVICE = "connectivity";
    public static final int MODE_PRIVATE = 0;
    private final Object service;
    public Context(Object service) { this.service = service; }
    public Context getApplicationContext() { return this; }
    public Object getSystemService(String name) { return service; }
    public SharedPreferences getSharedPreferences(String name, int mode) { throw new UnsupportedOperationException(); }
    public Object startForegroundService(Intent intent) { return null; }
    public java.io.File getFilesDir() { return new java.io.File("tests/.build/wifi-runtime/files"); }
}

package android.util;
public final class Log {
    public static int warnings;
    public static int w(String tag, String message, Throwable error) { warnings++; return 0; }
    public static int w(String tag, String message) { warnings++; return 0; }
    public static int i(String tag, String message) { return 0; }
}

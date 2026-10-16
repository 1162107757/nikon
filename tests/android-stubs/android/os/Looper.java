package android.os;

/** Host execution models a worker thread without an Android message loop. */
public final class Looper {
    private static final Looper MAIN = new Looper();
    private Looper() {}
    public static Looper myLooper() { return null; }
    public static Looper getMainLooper() { return MAIN; }
}

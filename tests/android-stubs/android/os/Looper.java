package android.os;

/** Host execution models a worker thread without an Android message loop. */
public final class Looper {
    private static final Looper MAIN = new Looper();
    private static final ThreadLocal<Boolean> TEST_MAIN = new ThreadLocal<>();
    private Looper() {}
    public static Looper myLooper() { return Boolean.TRUE.equals(TEST_MAIN.get()) ? MAIN : null; }
    public static Looper getMainLooper() { return MAIN; }
    public static void setMainThreadForTest(boolean main) { TEST_MAIN.set(main); }
}

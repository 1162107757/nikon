package android.os;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public final class Looper {
    private static final Looper MAIN = new Looper();
    static final ExecutorService QUEUE = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "test-android-main"); thread.setDaemon(true); return thread; });
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return Thread.currentThread().getName().equals("test-android-main") ? MAIN : null; }
}

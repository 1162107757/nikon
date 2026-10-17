package android.os;

/** Callbacks execute immediately in host tests; tests await completion using latches. */
public final class Handler {
    public Handler(Looper looper) { }
    public boolean post(Runnable runnable) { runnable.run(); return true; }
}

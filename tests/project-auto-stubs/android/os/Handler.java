package android.os;

/** Host-only callbacks; local retry timers are daemon tasks, not Android scheduling evidence. */
public final class Handler {
    private static final java.util.concurrent.ScheduledExecutorService CLOCK = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "project-auto-host-timer"); thread.setDaemon(true); return thread;
    });
    public Handler(Looper ignored) { }
    public boolean post(Runnable action) { action.run(); return true; }
    public boolean postDelayed(Runnable action, long delay) { CLOCK.schedule(action, delay, java.util.concurrent.TimeUnit.MILLISECONDS); return true; }
}

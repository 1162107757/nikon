package android.os;
import java.util.ArrayDeque;
import java.util.ArrayList;
public final class Handler {
    private static final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private static final ArrayList<Timed> delayed = new ArrayList<>();
    private static long now;
    private record Timed(Runnable runnable, long due) { }
    public Handler(Looper ignored) { }
    public boolean post(Runnable value) { queue.add(value); return true; }
    public boolean postDelayed(Runnable value, long delay) { delayed.add(new Timed(value, now + delay)); return true; }
    public void removeCallbacks(Runnable value) { queue.removeIf(item -> item == value); delayed.removeIf(item -> item.runnable == value); }
    public static void drain() { int limit = 10000; while (!queue.isEmpty()) { if (--limit == 0) throw new AssertionError("Unbounded test handler queue"); queue.remove().run(); } }
    public static void advance(long millis) {
        now += millis;
        var due = new ArrayList<Runnable>(); delayed.removeIf(item -> { if (item.due <= now) { due.add(item.runnable); return true; } return false; });
        queue.addAll(due); drain();
    }
    public static void reset() { queue.clear(); delayed.clear(); now = 0; }
}

package cc.huevia.ui;

/** A complete, single-finger horizontal gesture. Pinching, zooming and cancellation disarm it. */
public final class PhotoSwipeGesture {
    private boolean armed;
    private float startX, startY, maxVertical;
    private long started;

    /** A missing/failed current preview must not trap the user when a neighbour is available. */
    public static boolean canStart(boolean currentPhoto, boolean previous, boolean next, float zoom) {
        return Float.isFinite(zoom) && zoom >= 1f && zoom <= 1.001f && (currentPhoto || previous || next);
    }

    public void begin(float x, float y, long time, boolean fitted) {
        armed = fitted && Float.isFinite(x) && Float.isFinite(y);
        startX = x; startY = y; started = time; maxVertical = 0;
    }

    public void move(float x, float y, boolean fitted, int pointers) {
        if (!fitted || pointers != 1 || !Float.isFinite(x) || !Float.isFinite(y)) cancel();
        if (armed) maxVertical = Math.max(maxVertical, Math.abs(y - startY));
    }

    /** +1 is next (leftwards), -1 previous; zero is a tap, pan or cancelled gesture. */
    public int finish(float x, float y, long time, boolean fitted, float threshold) {
        move(x, y, fitted, 1);
        boolean eligible = armed && time >= started && time - started <= 800
                && Float.isFinite(threshold) && threshold > 0;
        armed = false;
        float dx = x - startX;
        if (!eligible || Math.abs(dx) < threshold || Math.abs(dx) < maxVertical * 1.75f) return 0;
        return dx < 0 ? 1 : -1;
    }

    public void cancel() { armed = false; }
    public boolean isArmed() { return armed; }
    public boolean isHorizontal(float x) {
        return armed && Float.isFinite(x) && Math.abs(x - startX) >= maxVertical * 1.75f;
    }

    /** Explicit release at an unavailable edge, separate from committing a real neighbouring page. */
    public int boundaryDirection(float x, long time, float threshold, float pixelsPerSecond) {
        if (!isHorizontal(x) || time < started || !Float.isFinite(threshold) || threshold <= 0
                || !Float.isFinite(pixelsPerSecond)) return 0;
        float dx = x - startX;
        boolean fast = Math.abs(pixelsPerSecond) >= threshold * 5;
        if (fast && Math.signum(dx) != Math.signum(pixelsPerSecond)) return 0;
        if (Math.abs(dx) < threshold && !(fast && Math.abs(dx) >= threshold * .25f)) return 0;
        return dx < 0 ? 1 : -1;
    }
}

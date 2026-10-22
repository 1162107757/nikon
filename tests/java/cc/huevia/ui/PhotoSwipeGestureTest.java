package cc.huevia.ui;

/** Complete gesture sequences guard against paging while zooming, pinching or cancelling. */
public final class PhotoSwipeGestureTest {
    private static int checks;
    public static void main(String[] args) {
        PhotoSwipeGesture gesture = new PhotoSwipeGesture();
        gesture.begin(300, 400, 1000, true);
        gesture.move(230, 403, true, 1);
        check(gesture.isArmed() && gesture.isHorizontal(230), "continuous paging shares the fitted single-finger horizontal guard");
        equal(1, gesture.finish(170, 405, 1250, true, 60), "left swipe advances once");
        equal(0, gesture.finish(100, 405, 1300, true, 60), "duplicate up cannot advance again");
        gesture.begin(100, 400, 2000, true);
        gesture.move(170, 398, true, 1);
        equal(-1, gesture.finish(230, 397, 2250, true, 60), "right swipe goes backwards");
        for (float[] end : new float[][] {{305, 403}, {350, 400}, {310, 590}, {430, 490}}) {
            gesture.begin(300, 400, 3000, true);
            equal(0, gesture.finish(end[0], end[1], 3300, true, 60), "tap, jitter, vertical and diagonal gestures do not page");
        }
        gesture.begin(300, 400, 4000, true);
        gesture.move(200, 500, true, 1);
        check(!gesture.isHorizontal(170), "a vertical detour cannot commit a continuous drag on release");
        equal(0, gesture.finish(170, 400, 4300, true, 60), "a vertical detour is not hidden by returning to the starting row");
        gesture.begin(300, 400, 5000, true);
        gesture.move(160, 400, true, 1);
        equal(0, gesture.finish(280, 400, 5300, true, 60), "a swipe reversed before release does not page");

        for (boolean beginFitted : new boolean[] {false, true}) {
            gesture.begin(300, 400, 6000, beginFitted);
            gesture.move(230, 400, false, 1);
            gesture.move(180, 400, true, 1);
            equal(0, gesture.finish(120, 400, 6300, true, 60), "a zoomed pan never becomes paging after returning to fit");
        }
        for (int pointers : new int[] {0, 2, 3}) {
            gesture.begin(300, 400, 7000, true);
            gesture.move(230, 400, true, pointers);
            gesture.move(180, 400, true, 1);
            equal(0, gesture.finish(100, 400, 7300, true, 60), "multi-touch or lost pointer disarms the entire gesture");
        }
        gesture.begin(300, 400, 8000, true);
        gesture.cancel(); // ACTION_CANCEL, scale begin, or second tap of a double tap.
        equal(0, gesture.finish(100, 400, 8300, true, 60), "cancelled gestures do not page");
        gesture.begin(300, 400, 9000, true);
        equal(0, gesture.finish(100, 400, 9900, true, 60), "long held drags are not quick page swipes");
        gesture.begin(300, 400, 9000, true);
        equal(0, gesture.finish(100, 400, 8900, true, 60), "invalid event time cannot page");
        for (float threshold : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            gesture.begin(300, 400, 10000, true);
            equal(0, gesture.finish(100, 400, 10300, true, threshold), "invalid threshold is rejected");
        }
        for (float coordinate : new float[] {Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
            gesture.begin(coordinate, 400, 11000, true);
            equal(0, gesture.finish(100, 400, 11300, true, 60), "invalid starting coordinate is rejected");
            gesture.begin(300, 400, 11000, true);
            gesture.move(coordinate, 400, true, 1);
            equal(0, gesture.finish(100, 400, 11300, true, 60), "invalid intermediate coordinate permanently disarms gesture");
        }
        // A fresh down re-arms after every disarming path, and threshold equality is intentional.
        gesture.begin(300, 400, 12000, true);
        equal(1, gesture.finish(240, 400, 12300, true, 60), "fresh valid gesture works after cancellation and malformed events");
        gesture.begin(300, 400, 13000, PhotoSwipeGesture.canStart(false, false, true, 1));
        gesture.move(180, 400, true, 1);
        equal(1, gesture.finish(140, 400, 13300, true, 60), "failed/missing preview with a next photo can still swipe away");
        gesture.begin(300, 400, 14000, PhotoSwipeGesture.canStart(false, true, false, 1));
        gesture.move(420, 400, true, 1);
        equal(-1, gesture.finish(460, 400, 14300, true, 60), "missing last preview can swipe to the previous photo");
        gesture.begin(300, 400, 15000, PhotoSwipeGesture.canStart(false, false, false, 1));
        equal(0, gesture.finish(100, 400, 15300, true, 60), "missing preview without neighbours cannot invent a page");
        gesture.begin(300, 400, 16000, PhotoSwipeGesture.canStart(false, true, true, 2));
        equal(0, gesture.finish(100, 400, 16300, true, 60), "allowing missing previews does not enable paging while zoomed");
        gesture.begin(300, 400, 17000, true); gesture.move(180, 400, true, 1);
        equal(1, gesture.boundaryDirection(150, 17300, 60, 0), "intentional left release can request a paused next page without a fake neighbour");
        gesture.cancel();
        equal(0, gesture.boundaryDirection(150, 17300, 60, -1000), "cancelled/consumed edge release cannot retry again");
        gesture.begin(300, 400, 18000, true); gesture.move(430, 400, true, 1);
        equal(-1, gesture.boundaryDirection(450, 18300, 60, 0), "right edge intent reports the previous direction separately");
        gesture.begin(300, 400, 19000, true); gesture.move(280, 400, true, 1);
        equal(0, gesture.boundaryDirection(280, 19300, 60, 0), "small slow edge pull does not retry");
        equal(1, gesture.boundaryDirection(280, 19300, 60, -900), "short decisive edge flick is an explicit retry intent");
        equal(0, gesture.boundaryDirection(200, 19300, 60, 900), "fast reverse cancels an earlier edge retry intent");
        gesture.begin(300, 400, 20000, true); gesture.move(180, 560, true, 1);
        equal(0, gesture.boundaryDirection(180, 20300, 60, -900), "vertical or diagonal movement never retries at an edge");
        gesture.begin(300, 400, 21000, true); gesture.move(180, 400, true, 2);
        equal(0, gesture.boundaryDirection(150, 21300, 60, -900), "multi-touch remains disarmed for the edge callback");
        gesture.begin(300, 400, 22000, false);
        equal(0, gesture.boundaryDirection(150, 22300, 60, -900), "zoomed gestures cannot request a page retry");
        System.out.println("PASS: " + checks + " photo swipe gesture checks");
    }
    private static void equal(int expected, int actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": " + actual);
        checks++;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}

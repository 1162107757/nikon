package cc.huevia.ui;

/** Physical drag/settle contracts, including interruption, cancellation and variable frame cadence. */
public final class PhotoPagerMotionTest {
    private static int checks;
    public static void main(String[] args) {
        directManipulationAndEdges();
        distanceVelocityAndCancellation();
        interruptedTransitionsCannotCommitOldDestinations();
        criticalSpringPreservesVelocityAndFrameCadence();
        immediateAndInvalidInputs();
        System.out.println("PASS: " + checks + " photo pager motion checks");
    }

    private static void directManipulationAndEdges() {
        PhotoPagerMotion motion = new PhotoPagerMotion();
        motion.drag(-123, 1000, true, true);
        close(-123, motion.offset(), .001f, "available page follows the finger one to one");
        motion.drag(321, 1000, true, true);
        close(321, motion.offset(), .001f, "reverse drag follows the same live position");
        motion.drag(-300, 1000, true, false);
        float first = Math.abs(motion.offset());
        check(first > 0 && first < 300, "last page resists without a hard zero stop");
        motion.drag(-600, 1000, true, false);
        check(Math.abs(motion.offset()) > first && Math.abs(motion.offset()) - first < first,
                "edge resistance grows progressively rather than linearly");
        motion.drag(10000, 1000, false, true);
        check(motion.offset() > 0 && motion.offset() < 320, "first-page overscroll stays bounded");
        motion.drag(-10000, 1000, true, true);
        close(-1000, motion.offset(), .001f, "a drag cannot skip several logical photos");
    }

    private static void distanceVelocityAndCancellation() {
        PhotoPagerMotion motion = new PhotoPagerMotion();
        motion.drag(-350, 1000, true, true);
        motion.release(0, 1000, true, true, false);
        check(motion.pendingDirection() == 1 && motion.isSettling(), "long drag selects next without committing on release");
        check(settle(motion, 1f / 60) == 1, "next commits exactly once after spring reaches the page centre");
        close(-1000, motion.offset(), .001f, "incoming page is centred before next commit");
        check(motion.advance(.5f) == 0 && motion.finishImmediately() == 0, "completed transition cannot commit twice");

        motion.reset(); motion.drag(350, 1000, true, true); motion.release(0, 1000, true, true, false);
        check(settle(motion, 1f / 60) == -1, "right drag settles to previous");
        motion.reset(); motion.drag(-70, 1000, true, true); motion.release(-1500, 1000, true, true, false);
        check(settle(motion, 1f / 120) == 1, "short decisive flick uses release momentum");
        motion.reset(); motion.drag(-70, 1000, true, true); motion.release(-30, 1000, true, true, false);
        check(settle(motion, 1f / 60) == 0, "same short distance without speed returns home");
        close(0, motion.offset(), .001f, "incomplete drag returns to original photo");
        motion.drag(-400, 1000, true, true); motion.release(3000, 1000, true, true, false);
        check(settle(motion, 1f / 60) == 0, "a fast reverse at release cancels an earlier left drag");
        for (boolean cancelled : new boolean[] {true, false}) {
            motion.reset(); motion.drag(-500, 1000, true, false);
            motion.release(-8000, 1000, true, false, cancelled);
            check(settle(motion, 1f / 60) == 0, "edge or cancellation cannot invent an unavailable next photo");
            close(0, motion.offset(), .001f, "unavailable/cancelled gesture springs home");
        }
        motion.reset(); motion.release(6000, 1000, true, true, true); motion.advance(.02f);
        close(0, motion.offset(), .001f, "a zoomed pan or vertical swipe must not impart velocity to a cancelled pager");
    }

    private static void interruptedTransitionsCannotCommitOldDestinations() {
        PhotoPagerMotion motion = new PhotoPagerMotion();
        motion.drag(-300, 1000, true, true); motion.release(-1200, 1000, true, true, false);
        motion.advance(.08f);
        float live = motion.offset();
        check(live < -300 && live > -1000, "spring carries the existing release velocity into motion");
        motion.grab();
        close(live, motion.offset(), .001f, "new touch grabs the presentation position without snapping");
        check(!motion.isSettling() && motion.advance(1) == 0, "interruption removes the previous completion");
        motion.drag(live + 450, 1000, true, true);
        close(live + 450, motion.offset(), .001f, "reverse finger delta is added to the live grab offset");
        motion.release(3000, 1000, true, true, false);
        int direction = settle(motion, 1f / 60);
        check(direction != 1, "reversing a grabbed transition never commits its stale next target");
        motion.reset(); motion.drag(-400, 1000, true, true); motion.release(-2000, 1000, true, true, false);
        motion.advance(.05f); motion.spring(0, 1000, 0);
        check(settle(motion, 1f / 60) == 0, "disabling neighbours/download start cancels a pending page commit");
    }

    private static void criticalSpringPreservesVelocityAndFrameCadence() {
        PhotoPagerMotion first = new PhotoPagerMotion(), second = new PhotoPagerMotion();
        first.drag(-200, 1000, true, true); first.spring(1, 1000, -1100);
        second.drag(-200, 1000, true, true); second.spring(1, 1000, -1100);
        first.advance(.00001f);
        close(-1100, (first.offset() + 200) / .00001f, 5, "spring starts at the supplied finger velocity");
        first.reset(); first.drag(-200, 1000, true, true); first.spring(1, 1000, -1100);
        for (int frame = 0; frame < 10; frame++) first.advance(.016f);
        second.advance(.16f);
        close(first.offset(), second.offset(), .02f, "critical spring remains consistent across uneven frame delivery");
        check(settle(first, .016f) == 1 && settle(second, .033f) == 1, "60 Hz and delayed frames each emit one final commit");
        PhotoPagerMotion returnHome = new PhotoPagerMotion();
        returnHome.drag(180, 1000, false, true); returnHome.release(0, 1000, false, true, true);
        float previous = returnHome.offset();
        boolean monotonic = true;
        for (int frame = 0; frame < 90 && returnHome.isSettling(); frame++) {
            returnHome.advance(1f / 60);
            monotonic &= returnHome.offset() >= 0 && returnHome.offset() <= previous;
            previous = returnHome.offset();
        }
        check(monotonic && !returnHome.isSettling(), "zero-velocity edge spring returns without overshoot or oscillation");
    }

    private static void immediateAndInvalidInputs() {
        PhotoPagerMotion motion = new PhotoPagerMotion();
        motion.drag(-400, 1000, true, true); motion.release(-800, 1000, true, true, false);
        check(motion.finishImmediately() == 1 && !motion.isSettling(), "reduced motion completes the chosen page immediately");
        close(-1000, motion.offset(), .001f, "reduced-motion destination is exact");
        motion.reset(); check(motion.offset() == 0 && motion.advance(.1f) == 0, "lifecycle reset clears presentation and callbacks");
        for (float bad : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            motion.drag(-300, bad, true, true); motion.release(-5000, bad, true, true, false);
            check(!motion.isSettling() && motion.offset() == 0, "invalid viewport cannot animate or commit");
        }
        motion.drag(-300, 1000, true, true); motion.release(Float.NaN, 1000, true, true, false);
        check(settle(motion, .016f) == 1, "non-finite velocity is ignored while valid distance remains meaningful");
    }

    private static int settle(PhotoPagerMotion motion, float step) {
        int completion = 0;
        for (int frame = 0; frame < 240 && motion.isSettling(); frame++) {
            int result = motion.advance(step);
            if (result != 0) {
                if (completion != 0) throw new AssertionError("duplicate completion");
                completion = result;
            }
        }
        if (motion.isSettling()) throw new AssertionError("spring did not converge");
        return completion;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void close(float expected, float actual, float tolerance, String message) {
        check(Math.abs(expected - actual) <= tolerance, message + ": " + actual);
    }
}

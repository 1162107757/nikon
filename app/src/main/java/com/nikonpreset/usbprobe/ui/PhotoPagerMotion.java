package com.nikonpreset.usbprobe.ui;

/** Pixel-space direct manipulation and a critically damped page spring; contains no Android objects. */
public final class PhotoPagerMotion {
    private float offset, velocity, target;
    private boolean settling;
    private int direction;

    public float offset() { return offset; }
    public boolean isSettling() { return settling; }
    public int pendingDirection() { return direction; }

    /** A new finger takes the exact presentation position, cancelling the old completion. */
    public void grab() { settling = false; direction = 0; velocity = 0; }

    public void drag(float desired, float width, boolean previous, boolean next) {
        grab();
        if (!Float.isFinite(desired) || !validWidth(width)) { reset(); return; }
        offset = Math.max(-width, Math.min(width, desired));
        if (offset > 0 && !previous || offset < 0 && !next) {
            float dimension = width * .32f;
            offset = (offset * dimension * .55f) / (dimension + .55f * Math.abs(offset));
        }
    }

    public void release(float pixelsPerSecond, float width, boolean previous, boolean next, boolean cancelled) {
        if (!validWidth(width)) { reset(); return; }
        velocity = Float.isFinite(pixelsPerSecond) ? Math.max(-width * 8, Math.min(width * 8, pixelsPerSecond)) : 0;
        if (cancelled) velocity = 0;
        float projection = offset + velocity * .15f;
        boolean flick = Math.abs(velocity) > width * .85f && Math.abs(offset) > width * .04f
                && Math.signum(velocity) == Math.signum(offset);
        int destination = 0;
        if (!cancelled) {
            if (next && (projection < -width * .28f || flick && velocity < 0)) destination = 1;
            else if (previous && (projection > width * .28f || flick && velocity > 0)) destination = -1;
        }
        spring(destination, width, velocity);
    }

    public void spring(int destination, float width, float initialVelocity) {
        if (!validWidth(width) || destination < -1 || destination > 1) { reset(); return; }
        direction = destination;
        target = -destination * width;
        velocity = Float.isFinite(initialVelocity) ? initialVelocity : 0;
        settling = true;
    }

    /** Returns one completion only after the incoming page has reached the centre. */
    public int advance(float seconds) {
        if (!settling || !Float.isFinite(seconds) || seconds <= 0) return 0;
        // Exact critical-damping solution: unlike Euler integration it survives delayed/variable frames.
        double omega = 24, displacement = offset - target;
        double coefficient = velocity + omega * displacement;
        double decay = Math.exp(-omega * seconds);
        float nextOffset = (float) (target + (displacement + coefficient * seconds) * decay);
        velocity = (float) ((velocity - omega * coefficient * seconds) * decay);
        offset = nextOffset;
        boolean reachedPage = direction > 0 && offset <= target || direction < 0 && offset >= target;
        if (reachedPage || Math.abs(offset - target) < .5f && Math.abs(velocity) < 5) return finishImmediately();
        return 0;
    }

    public int finishImmediately() {
        if (!settling) return 0;
        offset = target; velocity = 0; settling = false;
        int completed = direction; direction = 0;
        return completed;
    }

    public void reset() { offset = velocity = target = 0; direction = 0; settling = false; }
    private static boolean validWidth(float width) { return Float.isFinite(width) && width > 0; }
}

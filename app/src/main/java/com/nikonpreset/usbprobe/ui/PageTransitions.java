package com.nikonpreset.usbprobe.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Small, interruptible entrance transitions for navigation content, excluding the tab bar. */
public final class PageTransitions {
    private static final WeakHashMap<View, Entrance> RUNNING = new WeakHashMap<>();
    private static final PathInterpolator EASE_OUT = new PathInterpolator(.22f, 1f, .36f, 1f);

    private PageTransitions() { }

    /**
     * Enters from the right for positive direction, the left for negative direction, or fades
     * for zero. crossFade uses the smaller, shorter transition intended for photo viewing.
     * Call on the UI thread only, and only when the destination changes.
     */
    public static void enter(View content, int direction, boolean crossFade) {
        if (content == null) return;
        Entrance previous = RUNNING.get(content);
        boolean continuing = previous != null;
        if (previous != null) previous.stop(false);
        if (!ValueAnimator.areAnimatorsEnabled()) {
            restore(content);
            return;
        }

        if (!continuing) {
            float distance = (crossFade ? 8f : 20f)
                    * content.getResources().getDisplayMetrics().density;
            content.setTranslationX(Integer.signum(direction) * distance);
            content.setAlpha(crossFade ? .65f : .82f);
        }
        Entrance entrance = new Entrance(content, crossFade ? 180L : 220L);
        RUNNING.put(content, entrance);
        content.addOnAttachStateChangeListener(entrance);
        entrance.animator.start();
    }

    /** Cancels this helper's pending work and leaves the content fully visible and at rest. */
    public static void cancel(View content) {
        if (content == null) return;
        Entrance entrance = RUNNING.get(content);
        if (entrance != null) entrance.stop(true);
        else restore(content);
    }

    private static void restore(View content) {
        content.setAlpha(1f);
        content.setTranslationX(0f);
    }

    private static final class Entrance extends AnimatorListenerAdapter
            implements ValueAnimator.AnimatorUpdateListener, View.OnAttachStateChangeListener {
        final WeakReference<View> content;
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        final float initialAlpha;
        final float initialTranslation;
        boolean finished;

        Entrance(View view, long duration) {
            content = new WeakReference<>(view);
            initialAlpha = view.getAlpha();
            initialTranslation = view.getTranslationX();
            animator.setDuration(duration);
            animator.setInterpolator(EASE_OUT);
            animator.addUpdateListener(this);
            animator.addListener(this);
        }

        @Override public void onAnimationUpdate(ValueAnimator animation) {
            View view = content.get();
            if (view == null) { stop(false); return; }
            if (!ValueAnimator.areAnimatorsEnabled()) { stop(true); return; }
            float progress = (float) animation.getAnimatedValue();
            view.setAlpha(initialAlpha + (1f - initialAlpha) * progress);
            view.setTranslationX(initialTranslation * (1f - progress));
        }

        @Override public void onAnimationEnd(Animator animation) { stop(true); }
        @Override public void onAnimationCancel(Animator animation) { stop(true); }
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { stop(true); }

        void stop(boolean reset) {
            if (finished) return;
            finished = true;
            animator.removeUpdateListener(this);
            animator.removeListener(this);
            animator.cancel();
            View view = content.get();
            if (view == null) return;
            view.removeOnAttachStateChangeListener(this);
            if (RUNNING.get(view) == this) RUNNING.remove(view);
            if (reset) restore(view);
        }
    }
}

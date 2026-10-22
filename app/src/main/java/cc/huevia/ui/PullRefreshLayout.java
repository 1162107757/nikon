package cc.huevia.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** A content-local pull gesture. The indicator overlays the list without moving its rows. */
public final class PullRefreshLayout extends FrameLayout {
    private static final int NO_POINTER = -1;
    private final BooleanSupplier canRefresh;
    private final Runnable onRefresh;
    private final FrameLayout indicator;
    private final ProgressBar spinner;
    private final Gesture gesture;
    private final float maxFeedback;
    private View content;
    private int activePointer = NO_POINTER;
    private boolean ownsGesture;
    private boolean refreshing;

    public PullRefreshLayout(Context context, BooleanSupplier canRefresh, Runnable onRefresh) {
        super(context);
        this.canRefresh = Objects.requireNonNull(canRefresh, "canRefresh");
        this.onRefresh = Objects.requireNonNull(onRefresh, "onRefresh");
        gesture = new Gesture(ViewConfiguration.get(context).getScaledTouchSlop(), dp(64));
        maxFeedback = dp(80);
        indicator = new FrameLayout(context);
        indicator.setClickable(false);
        indicator.setFocusable(false);
        indicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        indicator.setContentDescription("下拉刷新");
        indicator.setElevation(dp(4));
        spinner = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
        spinner.setClickable(false);
        spinner.setFocusable(false);
        indicator.addView(spinner, new LayoutParams(dp(24), dp(24), Gravity.CENTER));
        LayoutParams position = new LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        position.topMargin = dp(8);
        addView(indicator, position);
        setColors(0xff0065dc, 0xfff7f8fa);
        hideIndicator();
    }

    /** Content must be detached before wrapping it. Its existing padding and scroll state remain intact. */
    public void setContent(View content) {
        Objects.requireNonNull(content, "content");
        if (this.content == content) return;
        if (content == indicator || content.getParent() != null) {
            throw new IllegalArgumentException("Pull refresh content must not already have a parent.");
        }
        clearGesture();
        if (this.content != null) removeView(this.content);
        this.content = content;
        addView(content, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (refreshing && hasWindowFocus()) showRefreshing(false);
        else hideIndicator();
    }

    /** Updates the real request state; calling this never invokes the refresh callback. */
    public void setRefreshing(boolean refreshing) {
        this.refreshing = refreshing;
        clearGesture();
        if (refreshing && isAttachedToWindow() && hasWindowFocus()) showRefreshing(true);
        else hideIndicator();
    }

    public boolean isRefreshing() { return refreshing; }

    public void setColors(int accent, int surface) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(surface);
        background.setStroke(Math.max(1, dp(.5f)), Color.argb(32,
                Color.red(accent), Color.green(accent), Color.blue(accent)));
        indicator.setBackground(background);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(accent));
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            clearGesture();
            activePointer = event.getPointerId(0);
            gesture.begin(event.getX(), event.getY(), mayRefresh());
            return false;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP
                || event.getPointerCount() != 1) {
            abortPull();
            return ownsGesture;
        }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            if (ownsGesture) return true;
            clearGesture();
            return false;
        }
        if (ownsGesture) return true;
        int index = event.findPointerIndex(activePointer);
        if (action == MotionEvent.ACTION_MOVE && index >= 0
                && gesture.move(event.getX(index), event.getY(index), mayRefresh())) {
            ownsGesture = true;
            disallowAncestorIntercept(true);
            showPull();
            // ViewGroup dispatch sends the previous child target its normal ACTION_CANCEL.
            return true;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // An empty, non-clickable content view may leave the gesture with this container.
            activePointer = event.getPointerId(0);
            gesture.begin(event.getX(), event.getY(), mayRefresh());
            return gesture.eligible;
        }
        boolean handled = ownsGesture || gesture.eligible;
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP
                || event.getPointerCount() != 1) {
            abortPull();
            return handled;
        }
        int index = event.findPointerIndex(activePointer);
        if (action == MotionEvent.ACTION_CANCEL || index < 0) {
            clearGesture();
            if (!refreshing) hideIndicator();
            return handled;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (gesture.move(event.getX(index), event.getY(index), mayRefresh())) {
                ownsGesture = true;
                disallowAncestorIntercept(true);
                showPull();
            } else if (!refreshing) hideIndicator();
            return handled || ownsGesture;
        }
        if (action == MotionEvent.ACTION_UP) {
            boolean trigger = gesture.finish(event.getX(index), event.getY(index), mayRefresh());
            clearGesture();
            if (trigger) {
                setRefreshing(true);
                try { onRefresh.run(); }
                catch (RuntimeException failure) { setRefreshing(false); throw failure; }
            } else if (!refreshing) hideIndicator();
            return handled;
        }
        return handled || super.onTouchEvent(event);
    }

    private boolean mayRefresh() {
        return !refreshing && isEnabled() && content != null && content.isShown()
                && !content.canScrollVertically(-1) && canRefresh.getAsBoolean();
    }

    private void showPull() {
        indicator.animate().cancel();
        indicator.setVisibility(VISIBLE);
        indicator.setTranslationY(Math.min(maxFeedback, gesture.pull * .5f));
        indicator.setAlpha(Math.min(1f, .25f + .75f * gesture.pull / gesture.threshold));
    }

    private void showRefreshing(boolean animate) {
        indicator.animate().cancel();
        boolean visible = indicator.getVisibility() == VISIBLE;
        indicator.setVisibility(VISIBLE);
        if (animate && visible) indicator.animate().translationY(0).alpha(1).setDuration(120).start();
        else { indicator.setTranslationY(0); indicator.setAlpha(1); }
    }

    private void hideIndicator() {
        indicator.animate().cancel();
        indicator.setVisibility(INVISIBLE);
        indicator.setTranslationY(0);
        indicator.setAlpha(0);
    }

    private void abortPull() {
        gesture.cancel();
        activePointer = NO_POINTER;
        if (!refreshing) hideIndicator();
        // Keep ownership until UP/CANCEL if a child has already received CANCEL.
    }

    private void clearGesture() {
        gesture.cancel();
        activePointer = NO_POINTER;
        ownsGesture = false;
        disallowAncestorIntercept(false);
    }

    private void disallowAncestorIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        clearGesture();
        if (focused && refreshing) showRefreshing(false);
        else hideIndicator();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (refreshing && hasWindowFocus()) showRefreshing(false);
    }

    @Override protected void onDetachedFromWindow() {
        clearGesture();
        hideIndicator();
        super.onDetachedFromWindow();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** Pure state machine, shared by interception and touch handling and directly host-testable. */
    static final class Gesture {
        final float slop, threshold;
        boolean eligible, dragging;
        float startX, startY, pull;

        Gesture(float slop, float threshold) { this.slop = slop; this.threshold = threshold; }
        void begin(float x, float y, boolean allowed) {
            cancel();startX=x;startY=y;eligible=allowed&&Float.isFinite(x)&&Float.isFinite(y);
        }
        boolean move(float x, float y, boolean allowed) {
            if (!eligible) return false;
            if (!allowed || !Float.isFinite(x) || !Float.isFinite(y)) { cancel();return false; }
            float dx=Math.abs(x-startX),dy=y-startY;
            if (dy < -slop || dx > slop && dx >= Math.abs(dy)) { cancel();return false; }
            pull=Math.max(0,dy);
            if (!dragging && dy>slop && dy>dx*1.2f) dragging=true;
            return dragging;
        }
        boolean finish(float x, float y, boolean allowed) {
            boolean trigger=dragging&&move(x,y,allowed)&&pull>=threshold;
            cancel();return trigger;
        }
        void cancel() { eligible=false;dragging=false;pull=0; }
    }
}

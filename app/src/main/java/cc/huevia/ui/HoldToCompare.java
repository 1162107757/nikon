package cc.huevia.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.function.Consumer;

/** A momentary photo comparison, with a click toggle for keyboard and accessibility input. */
public final class HoldToCompare {
    private static final int NO_POINTER = -1;
    private final View target;
    private final Consumer<Bitmap> display;
    private final TextView badge;
    private final CompareButton button;
    private Bitmap effect;
    private Bitmap original;
    private int activePointer = NO_POINTER;
    private boolean showingOriginal;
    private boolean keyDown;
    private boolean unavailable;
    private String buttonLabel = "原图";

    public HoldToCompare(FrameLayout frame, ImageView image, TextView badge) {
        this(frame, image, badge, image == null ? null : image::setImageBitmap);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    }

    public HoldToCompare(FrameLayout frame, PhotoImageView image) {
        this(frame, image, null, image == null ? null : image::setPhotoPreservingTransform);
    }

    private HoldToCompare(FrameLayout frame, View target, TextView badge, Consumer<Bitmap> display) {
        requireMainThread();
        if (frame == null || target == null || display == null) throw new IllegalArgumentException("Missing preview view");
        this.target = target;
        this.display = display;
        this.badge = badge;
        button = new CompareButton(frame.getContext());
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setFocusable(true);
        button.setClickable(true);
        button.setLongClickable(false);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(48), Gravity.BOTTOM | Gravity.END);
        position.bottomMargin = dp(12);
        position.setMarginEnd(dp(12));
        frame.addView(button, position);
        updateButton();
    }

    /** The owner may position or hide this control; bitmaps remain owned by the caller. */
    public View getButtonView() { return button; }
    public void setButtonLabel(String label) { requireMainThread(); buttonLabel = label; updateButton(); }

    /** Updates the result without replacing an original that is currently being held. */
    public void setEffect(Bitmap bitmap) {
        requireMainThread();
        effect = usable(bitmap) ? bitmap : null;
        updateReadiness();
    }

    public void setOriginal(Bitmap bitmap) {
        requireMainThread();
        original = usable(bitmap) ? bitmap : null;
        updateReadiness();
    }

    /** Keeps the last effect available for restoration after an original fails to load. */
    public void setUnavailable() {
        requireMainThread();
        release();
        original = null;
        unavailable = true;
        updateButton();
    }

    /** Restores the effect when the owner pauses or abandons this preview. */
    public void release() {
        requireMainThread();
        activePointer = NO_POINTER;
        keyDown = false;
        disallowParentIntercept(false);
        showOriginal(false, false);
    }

    private void updateReadiness() {
        if (!ready()) release();
        else { unavailable = false; renderCurrent(); }
        updateButton();
    }

    private boolean ready() {
        return usable(effect) && usable(original);
    }

    private static boolean usable(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled();
    }

    private void showOriginal(boolean show, boolean announce) {
        boolean changed = showingOriginal != (show && ready());
        showingOriginal = show && ready();
        renderCurrent();
        updateButton();
        if (announce && changed) button.announceForAccessibility(
                showingOriginal ? "已显示原图，点击可返回预设效果" : "已恢复预设效果");
    }

    private void renderCurrent() {
        Bitmap displayed = showingOriginal ? original : effect;
        if (usable(displayed)) display.accept(displayed);
        target.setContentDescription(showingOriginal
                ? "原图，未应用预设" : "预设效果，手机模拟");
        if (badge != null) badge.setText(showingOriginal ? "原图" : "预设效果");
    }

    private void updateButton() {
        // View construction can call visibility/focus callbacks before this field is assigned.
        if (button == null) return;
        boolean enabled = ready();
        button.setEnabled(enabled);
        button.setText(enabled ? buttonLabel : unavailable ? "不可用" : "准备中");
        button.setTextColor(enabled ? Color.WHITE : Color.rgb(214, 218, 225));
        button.setPressed(activePointer != NO_POINTER && showingOriginal);
        button.setSelected(showingOriginal);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(showingOriginal ? Color.argb(238, 26, 29, 35) : Color.argb(215, 26, 29, 35));
        surface.setCornerRadius(dp(24));
        surface.setStroke(dp(button.hasFocus() ? 2 : 1),
                Color.argb(button.hasFocus() ? 255 : 55, 255, 255, 255));
        button.setBackground(surface);
        button.setContentDescription(!enabled ? unavailable ? "预览原图对比暂不可用，原图或效果加载失败" : "预览原图对比，正在准备"
                : showingOriginal ? "当前显示原图。点击返回预设效果；触摸松开可恢复效果"
                : "按住看原图，松开恢复预设效果。使用辅助功能或键盘时，点击可切换原图，再次点击恢复");
        if (Build.VERSION.SDK_INT >= 30) button.setStateDescription(!enabled ? unavailable ? "加载失败" : "正在准备"
                : showingOriginal ? "原图" : "预设效果");
    }

    private void disallowParentIntercept(boolean disallow) {
        if (button == null) return;
        ViewParent parent = button.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }

    private boolean handleTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Consume even while loading, so the surrounding card does not open on release.
                disallowParentIntercept(true);
                keyDown = false;
                activePointer = ready() ? event.getPointerId(0) : NO_POINTER;
                showOriginal(activePointer != NO_POINTER, false);
                break;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(activePointer);
                if (event.getPointerCount() != 1 || index < 0
                        || event.getX(index) < 0 || event.getY(index) < 0
                        || event.getX(index) >= button.getWidth() || event.getY(index) >= button.getHeight()) {
                    activePointer = NO_POINTER;
                    showOriginal(false, false);
                    // Keep consuming this gesture, including moves outside the button.
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                activePointer = NO_POINTER;
                showOriginal(false, false);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (activePointer == event.getPointerId(event.getActionIndex())) {
                    activePointer = NO_POINTER;
                    showOriginal(false, false);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release();
                break;
            default:
                break;
        }
        return true;
    }

    private int dp(int value) {
        return Math.round(value * target.getResources().getDisplayMetrics().density);
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Preview comparison must run on the main thread");
    }

    private final class CompareButton extends Button {
        CompareButton(Context context) { super(context); }

        @Override public boolean onTouchEvent(MotionEvent event) {
            // Do not call Button's touch handler: it would click-toggle again after ACTION_UP.
            return handleTouch(event);
        }

        @Override public boolean performClick() {
            if (!ready() || !isEnabled()) return false;
            activePointer = NO_POINTER;
            disallowParentIntercept(false);
            showOriginal(!showingOriginal, true);
            super.performClick();
            return true;
        }

        @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (isActivationKey(keyCode)) {
                if (event.getRepeatCount() == 0) keyDown = ready() && isEnabled();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (isActivationKey(keyCode)) {
                boolean activate = keyDown && !event.isCanceled();
                keyDown = false;
                if (activate) performClick();
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }

        private boolean isActivationKey(int keyCode) {
            return keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_SPACE;
        }

        @Override protected void onFocusChanged(boolean focused, int direction, Rect previous) {
            super.onFocusChanged(focused, direction, previous);
            if (!focused) release();
            updateButton();
        }

        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused);
            if (!focused) release();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility != View.VISIBLE) release();
        }

        @Override protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            if (visibility != View.VISIBLE) release();
        }

        @Override protected void onDetachedFromWindow() {
            release();
            super.onDetachedFromWindow();
        }
    }
}

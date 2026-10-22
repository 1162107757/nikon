package cc.huevia.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/** A non-modal, replaceable top notice. Destructive confirmations belong in their own dialogs. */
public final class TopNotice implements AutoCloseable {
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::dismiss;
    private final View.OnLayoutChangeListener layout = (view, l, t, r, b, ol, ot, or, ob) -> reposition();
    private PopupWindow popup;
    private View banner, decor;
    private Runnable action;
    private String identity = "";
    private long revision;
    private volatile boolean closed;

    public TopNotice(Activity activity) {
        if (activity == null) throw new IllegalArgumentException("activity");
        this.activity = activity;
    }

    public void show(String title, String message) { show(title, message, null, null); }

    /** A subsequent notice replaces the previous one; the action runs only after explicit selection. */
    public void show(String title, String message, String actionLabel, Runnable selectedAction) {
        if (closed) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> show(title, message, actionLabel, selectedAction)); return;
        }
        if (!hostAvailable()) return;
        String heading = clean(title), detail = clean(message), label = clean(actionLabel);
        if (heading.isEmpty() && detail.isEmpty()) { dismiss(); return; }
        boolean actionable = selectedAction != null && !label.isEmpty();
        UiPalette palette = new UiPalette(activity);
        String nextIdentity = heading + '\u0000' + detail + '\u0000' + (actionable ? label : "") + ':' + palette.dark;
        if (popup != null && popup.isShowing() && identity.equals(nextIdentity)) {
            action = actionable ? selectedAction : null;
            main.removeCallbacks(hide); main.postDelayed(hide, duration(actionable));
            reposition(); return;
        }
        dismiss();
        final long shown = ++revision;
        action = actionable ? selectedAction : null;
        identity = nextIdentity;
        decor = activity.getWindow().getDecorView();
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.HORIZONTAL); content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(14), dp(8), dp(4), dp(8)); content.setMinimumHeight(dp(64));
        LinearLayout words = new LinearLayout(activity); words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(0, dp(2), dp(6), dp(2));
        String description = heading.isEmpty() ? detail : detail.isEmpty() ? heading : heading + "。" + detail;
        words.setContentDescription(description); words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        TextView titleView = label(heading, 14, palette.text, true);
        titleView.setMaxLines(1); titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setVisibility(heading.isEmpty() ? View.GONE : View.VISIBLE);
        TextView messageView = label(detail, 13, palette.secondary, false);
        // The complete explanation is still available to accessibility services.
        messageView.setMaxLines(2); messageView.setEllipsize(TextUtils.TruncateAt.END);
        messageView.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        if (!heading.isEmpty() && !detail.isEmpty()) messageView.setPadding(0, dp(4), 0, 0);
        words.addView(titleView, new LinearLayout.LayoutParams(-1, -2));
        words.addView(messageView, new LinearLayout.LayoutParams(-1, -2));
        content.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (actionable) {
            TextView option = label(label, 14, palette.primary, true);
            option.setGravity(Gravity.CENTER); option.setMinHeight(dp(44)); option.setMinWidth(dp(44));
            option.setMaxWidth(dp(128)); option.setSingleLine(true); option.setEllipsize(TextUtils.TruncateAt.END);
            option.setPadding(dp(10), 0, dp(10), 0); option.setFocusable(true);
            option.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); option.setContentDescription(label);
            option.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), null, rounded(0xffffffff, 14)));
            option.setOnClickListener(view -> {
                if (shown != revision || closed || !hostAvailable()) return;
                Runnable selected = action; dismiss();
                if (selected != null && hostAvailable()) selected.run();
            });
            content.addView(option, new LinearLayout.LayoutParams(-2, dp(44)));
        }
        ImageButton close = new ImageButton(activity);
        close.setImageDrawable(new CloseMark(palette.secondary, activity.getResources().getDisplayMetrics().density));
        close.setPadding(dp(13), dp(13), dp(13), dp(13)); close.setContentDescription("关闭提示"); close.setFocusable(true);
        close.setBackgroundTintList(null);
        close.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), null, rounded(0xffffffff, 22)));
        close.setOnClickListener(view -> { if (shown == revision) dismiss(); });
        content.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        GradientDrawable background = rounded(palette.card, 20); background.setStroke(dp(1), palette.dark ? palette.outline : palette.rim);
        PopupWindow window = new PopupWindow(content, dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, false);
        window.setBackgroundDrawable(background); window.setElevation(dp(12));
        window.setTouchable(true); window.setOutsideTouchable(false); window.setFocusable(false);
        window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        window.setClippingEnabled(true); window.setAnimationStyle(0);
        if (Build.VERSION.SDK_INT >= 29) window.setTouchModal(false);
        window.setOnDismissListener(() -> {
            if (popup != window) return;
            main.removeCallbacksAndMessages(null); detach(); popup = null; banner = null; action = null; identity = ""; revision++;
        });
        popup = window; banner = content;
        int[] bounds = position(); window.setWidth(bounds[0]);
        try { window.showAtLocation(decor, Gravity.TOP | Gravity.LEFT, bounds[1], bounds[2]); }
        catch (WindowManager.BadTokenException | IllegalStateException unavailable) { dismiss(); return; }
        decor.addOnLayoutChangeListener(layout);
        if (ValueAnimator.areAnimatorsEnabled()) { content.setAlpha(0f); content.animate().alpha(1f).setDuration(160).start(); }
        main.postDelayed(() -> {
            if (shown == revision && popup == window && window.isShowing() && hostAvailable()) content.announceForAccessibility(description);
        }, 180);
        main.postDelayed(hide, duration(actionable));
    }

    public void dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::dismiss); return; }
        main.removeCallbacksAndMessages(null); revision++;
        PopupWindow previous = popup; popup = null;
        if (banner != null) banner.animate().cancel();
        detach(); banner = null; action = null; identity = "";
        if (previous != null) previous.dismiss();
    }

    /** Call from the host's onPause so a notice never follows the user to another screen or app. */
    public void onHostPaused() { dismiss(); }

    @Override public void close() { closed = true; dismiss(); }

    private boolean hostAvailable() {
        return !closed && !activity.isFinishing() && !activity.isDestroyed() && activity.getWindow() != null
                && activity.getWindow().getDecorView().isAttachedToWindow();
    }

    private void detach() { if (decor != null) { decor.removeOnLayoutChangeListener(layout); decor = null; } }

    private void reposition() {
        if (popup == null || !popup.isShowing() || decor == null) return;
        if (!hostAvailable()) { dismiss(); return; }
        int[] bounds = position(); popup.update(bounds[1], bounds[2], bounds[0], ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Width and offsets share the decor's coordinates, including split-screen and asymmetric cutouts. */
    private int[] position() {
        Rect visible = new Rect(); decor.getWindowVisibleDisplayFrame(visible);
        int[] location = new int[2]; decor.getLocationOnScreen(location);
        int width = decor.getWidth() > 0 ? decor.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        int left = Math.max(0, visible.left - location[0]), top = Math.max(0, visible.top - location[1]);
        int right = visible.isEmpty() ? 0 : Math.max(0, location[0] + width - visible.right);
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets != null) {
            left = Math.max(left, insets.getSystemWindowInsetLeft()); right = Math.max(right, insets.getSystemWindowInsetRight());
            top = Math.max(top, insets.getSystemWindowInsetTop());
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) { left = Math.max(left, cutout.getSafeInsetLeft()); right = Math.max(right, cutout.getSafeInsetRight()); top = Math.max(top, cutout.getSafeInsetTop()); }
            }
        }
        int available = Math.max(1, width - left - right - dp(24));
        int noticeWidth = Math.min(dp(560), available);
        return new int[] {noticeWidth, left + dp(12) + (available - noticeWidth) / 2, top + dp(8)};
    }

    private long duration(boolean actionable) {
        int duration = actionable ? 8000 : 4500;
        if (Build.VERSION.SDK_INT >= 29) {
            AccessibilityManager accessibility = (AccessibilityManager) activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (accessibility != null) duration = Math.max(duration, accessibility.getRecommendedTimeoutMillis(duration,
                    AccessibilityManager.FLAG_CONTENT_TEXT | AccessibilityManager.FLAG_CONTENT_CONTROLS));
        }
        return duration;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setIncludeFontPadding(false); view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return view;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static final class CloseMark extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;
        CloseMark(int color, float density) { paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(1.6f * density); size = Math.round(18 * density); }
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds(); float inset = Math.min(bounds.width(), bounds.height()) * .16f;
            canvas.drawLine(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset, paint);
            canvas.drawLine(bounds.right - inset, bounds.top + inset, bounds.left + inset, bounds.bottom - inset, paint);
        }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}

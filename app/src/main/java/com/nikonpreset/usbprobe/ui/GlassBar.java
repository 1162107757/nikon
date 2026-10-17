package com.nikonpreset.usbprobe.ui;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * A compact floating navigation material. Add navigation content after construction.
 * API 31+ blurs only a small sampled background image; child labels remain sharp.
 * Older devices and reduced-performance modes use an opaque material instead.
 */
public final class GlassBar extends FrameLayout {
    private static final long FRAME_INTERVAL_MS = 100; // At most ten captures per second.
    private static final int DOWNSAMPLE = 6;
    private final UiPalette colors;
    private final ImageView snapshotView;
    private final View tintView;
    private final float radius;
    private final boolean lowRam;
    private final PowerManager powerManager;
    private View backdrop;
    private ViewTreeObserver observedTree;
    private Bitmap snapshot;
    private Canvas snapshotCanvas;
    private boolean capturing, glassEnabled = true, materialAllowed, captureFailed;
    private long lastCaptureAt, lastPolicyAt;
    private final int[] sourceLocation = new int[2];
    private final int[] barLocation = new int[2];
    private final ViewTreeObserver.OnPreDrawListener sampler = () -> {
        sampleBackdrop();
        return true;
    };

    public GlassBar(Context context) {
        super(context);
        colors = new UiPalette(context);
        radius = dp(34);
        ActivityManager activity = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        lowRam = activity != null && activity.isLowRamDevice();
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        setBackgroundColor(colors.card);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setElevation(dp(8));
        if (Build.VERSION.SDK_INT >= 28) {
            setOutlineAmbientShadowColor(colors.dark ? 0x40000000 : 0x260D2038);
            setOutlineSpotShadowColor(colors.dark ? 0x60000000 : 0x300D2038);
        }
        snapshotView = new ImageView(context);
        snapshotView.setScaleType(ImageView.ScaleType.FIT_XY);
        snapshotView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        snapshotView.setVisibility(GONE);
        addView(snapshotView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 31) Api31.blur(snapshotView, dp(18));
        tintView = new View(context);
        tintView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tintView.setBackgroundColor(colors.card);
        addView(tintView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setForeground(new Rim());
    }

    /**
     * Supply the content layer behind this bar. The source must not contain this
     * GlassBar: using a separate sibling prevents recursive capture and feedback.
     * Pass null to use the solid fallback and release the sample immediately.
     */
    public void setBackdrop(View source) {
        removeSampler();
        backdrop = source;
        lastCaptureAt = 0;
        captureFailed = false;
        useFallback();
        if (isAttachedToWindow()) attachSampler();
        invalidate();
    }

    /** Allows a host accessibility preference to opt into the opaque material. */
    public void setGlassEnabled(boolean enabled) {
        if (glassEnabled == enabled) return;
        glassEnabled = enabled;
        lastPolicyAt = 0;
        if (!enabled) useFallback();
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastCaptureAt = lastPolicyAt = 0;
        captureFailed = false;
        attachSampler();
    }

    @Override protected void onDetachedFromWindow() {
        removeSampler();
        releaseSnapshot();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        invalidateOutline();
        lastCaptureAt = 0;
    }

    private void attachSampler() {
        if (backdrop == null || backdrop == this) return;
        observedTree = backdrop.getViewTreeObserver();
        if (observedTree.isAlive()) observedTree.addOnPreDrawListener(sampler);
    }

    private void removeSampler() {
        if (observedTree != null && observedTree.isAlive()) observedTree.removeOnPreDrawListener(sampler);
        observedTree = null;
    }

    private void sampleBackdrop() {
        if (capturing || !isShown() || backdrop == null || !backdrop.isShown()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastPolicyAt >= 1000 || lastPolicyAt == 0) {
            lastPolicyAt = now;
            materialAllowed = Build.VERSION.SDK_INT >= 31 && glassEnabled && !lowRam
                    && isHardwareAccelerated() && ValueAnimator.areAnimatorsEnabled()
                    && (powerManager == null || !powerManager.isPowerSaveMode());
            if (!materialAllowed) useFallback();
        }
        if (!materialAllowed || captureFailed || now - lastCaptureAt < FRAME_INTERVAL_MS) return;
        // A mistakenly supplied ancestor would include our sample and all nav buttons.
        for (ViewParent parent = getParent(); parent != null; parent = parent.getParent()) {
            if (parent == backdrop) { useFallback(); return; }
        }
        int width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0 || backdrop.getWidth() <= 0 || backdrop.getHeight() <= 0) return;
        lastCaptureAt = now;
        capturing = true;
        try {
            int sampleWidth = Math.max(1, (width + DOWNSAMPLE - 1) / DOWNSAMPLE);
            int sampleHeight = Math.max(1, (height + DOWNSAMPLE - 1) / DOWNSAMPLE);
            if (snapshot == null || snapshot.getWidth() != sampleWidth || snapshot.getHeight() != sampleHeight) {
                releaseSnapshot();
                snapshot = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888);
                snapshotCanvas = new Canvas(snapshot);
                snapshotView.setImageBitmap(snapshot);
            }
            backdrop.getLocationInWindow(sourceLocation);
            getLocationInWindow(barLocation);
            snapshot.eraseColor(colors.surface);
            int save = snapshotCanvas.save();
            try {
                snapshotCanvas.scale(sampleWidth / (float) width, sampleHeight / (float) height);
                snapshotCanvas.translate(sourceLocation[0] - barLocation[0], sourceLocation[1] - barLocation[1]);
                backdrop.draw(snapshotCanvas);
            } finally {
                snapshotCanvas.restoreToCount(save);
            }
            snapshotView.setVisibility(VISIBLE);
            tintView.setBackgroundColor(colors.glass);
            snapshotView.invalidate();
            // No timer or next-frame invalidation: subsequent source frames drive sampling.
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            captureFailed = true;
            useFallback();
        } finally {
            capturing = false;
        }
    }

    private void useFallback() {
        snapshotView.setVisibility(GONE);
        tintView.setBackgroundColor(colors.card);
        releaseSnapshot();
    }

    private void releaseSnapshot() {
        snapshotView.setImageDrawable(null);
        snapshotCanvas = null;
        // The render thread may still hold the preceding frame. Dropping our small
        // buffer is safe; forcing Bitmap.recycle() here can race that frame.
        snapshot = null;
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    /** Isolate newer framework types from API 26–30 class verification. */
    private static final class Api31 {
        static void blur(View view, float radius) {
            view.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
    }

    private final class Rim extends Drawable {
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float hairline = Math.max(1, dp(.5f));
            bounds.inset(hairline / 2, hairline / 2);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(hairline);
            ink.setColor(colors.dark ? 0x35FFFFFF : 0x180D2038);
            canvas.drawRoundRect(bounds, radius, radius, ink);
            // The short top arc catches light without a glossy gradient over content.
            ink.setColor(colors.rim);
            canvas.drawLine(bounds.left + radius, bounds.top, bounds.right - radius, bounds.top, ink);
        }
        @Override public void setAlpha(int alpha) { ink.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { ink.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}

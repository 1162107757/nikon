package cc.huevia.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Looper;
import android.view.View;

import cc.huevia.ncp.PresetPreviewProfile;
import cc.huevia.ncp.Np3PreviewProfile;
import cc.huevia.ncp.Np3MonochromePreviewProfile;

/** Read-only plot of NCP samples or the NP3 model's neutral-gray tone response. */
public final class ToneCurveView extends View {
    private static final int LOADING = 0, READY = 1, UNAVAILABLE = 2;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final UiPalette colors;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curve = new Path();
    private final Path reference = new Path();
    private final RectF card = new RectF();
    private final RectF plot = new RectF();
    private final float[] samples = new float[257];
    private final float density, scaledDensity;
    private final DashPathEffect dashed;
    private int state = LOADING;
    private boolean np3;
    private boolean monochrome;

    public ToneCurveView(Context context) {
        super(context);
        colors = new UiPalette(context);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        dashed = new DashPathEffect(new float[] {dp(4), dp(4)}, 0);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        setContentDescription("色调曲线，正在读取曲线。");
    }

    public ToneCurveView(Context context, String format) {
        this(context);
        np3 = "NP3".equals(format);
        if (np3) setContentDescription("手机色调映射，正在读取。");
    }

    public void setProfile(PresetPreviewProfile profile) {
        requireMainThread();
        if (profile == null) {
            setUnavailable();
            return;
        }
        monochrome = profile instanceof Np3MonochromePreviewProfile;
        np3 = monochrome || profile instanceof Np3PreviewProfile;
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) profile.mapTone(i / 256.0);
        }
        state = READY;
        rebuildPaths();
        setContentDescription((monochrome ? "单色预设曲线，257 个实际采样点。" : np3 ? "手机色调映射，代表灰阶亮度响应，257 个近似采样点。" : "色调曲线，257 个实际采样点。") + "输入 0，输出 "
                + normalizedLabel(samples[0]) + "；输入 128，输出 "
                + normalizedLabel(samples[128]) + "；输入 255，输出 "
                + normalizedLabel(samples[256]) + "。实线为预设曲线，虚线为线性参考。");
        invalidate();
    }

    public void setLoading() {
        requireMainThread();
        state = LOADING;
        curve.rewind();
        setContentDescription("色调曲线，正在读取曲线。");
        invalidate();
    }

    public void setUnavailable() {
        requireMainThread();
        state = UNAVAILABLE;
        curve.rewind();
        setContentDescription("色调曲线暂不可用，轻点重试。");
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        float fontScale = getResources().getConfiguration().fontScale;
        int height = Math.round(dp(188 + Math.max(0, fontScale - 1) * 86))
                + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(Math.max(getSuggestedMinimumWidth(), Math.round(dp(280)))
                        + getPaddingLeft() + getPaddingRight(), widthSpec),
                resolveSize(Math.max(getSuggestedMinimumHeight(), height), heightSpec));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        card.set(getPaddingLeft(), getPaddingTop(), w - getPaddingRight(), h - getPaddingBottom());
        float textGrowth = Math.max(0, scaledDensity / density - 1);
        plot.set(card.left + dp(45 + textGrowth * 12),
                card.top + dp(47 + textGrowth * 20),
                card.right - dp(17), card.bottom - dp(49 + textGrowth * 22));
        rebuildPaths();
    }

    private void rebuildPaths() {
        curve.rewind();
        reference.rewind();
        if (plot.width() <= 0 || plot.height() <= 0) return;
        reference.moveTo(plot.left, plot.bottom);
        reference.lineTo(plot.right, plot.top);
        if (state != READY) return;
        for (int i = 0; i < samples.length; i++) {
            float x = plot.left + plot.width() * i / 256f;
            float y = plot.bottom - plot.height() * samples[i];
            if (i == 0) curve.moveTo(x, y);
            else curve.lineTo(x, y);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colors.card);
        canvas.drawRoundRect(card, dp(16), dp(16), paint);
        text(canvas, monochrome ? "单色预设曲线" : np3 ? "手机色调映射" : "色调曲线", card.left + dp(16), card.top + dp(14), 13,
                colors.text, Paint.Align.LEFT, true);
        text(canvas, np3 ? "NP3" : "NCP", card.right - dp(16), card.top + dp(16), 11,
                colors.secondary, Paint.Align.RIGHT, false);
        if (state != READY) {
            String label = state == LOADING ? "正在读取曲线…" : "曲线暂不可用 · 轻点重试";
            text(canvas, label, card.centerX(), card.centerY() - dp(2), 12,
                    colors.secondary, Paint.Align.CENTER, false);
            return;
        }
        if (plot.width() <= 0 || plot.height() <= 0) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.7f));
        paint.setColor(withAlpha(colors.outline, colors.dark ? 150 : 160));
        for (int i = 0; i <= 4; i++) {
            float x = plot.left + plot.width() * i / 4;
            float y = plot.top + plot.height() * i / 4;
            canvas.drawLine(x, plot.top, x, plot.bottom, paint);
            canvas.drawLine(plot.left, y, plot.right, y, paint);
        }
        paint.setColor(withAlpha(colors.secondary, 200));
        paint.setStrokeWidth(dp(1));
        paint.setPathEffect(dashed);
        canvas.drawPath(reference, paint);
        paint.setPathEffect(null);
        paint.setColor(colors.primary);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        int clip = canvas.save();
        canvas.clipRect(plot.left - dp(1), plot.top - dp(1), plot.right + dp(1), plot.bottom + dp(1));
        canvas.drawPath(curve, paint);
        canvas.restoreToCount(clip);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        float axisSize = 9;
        textCenteredVertically(canvas, "255", plot.left - dp(8), plot.top,
                axisSize, Paint.Align.RIGHT);
        textCenteredVertically(canvas, "0", plot.left - dp(8), plot.bottom,
                axisSize, Paint.Align.RIGHT);
        text(canvas, "输出", card.left + dp(16), plot.top + plot.height() / 2 - sp(5),
                9, colors.secondary, Paint.Align.LEFT, false);
        text(canvas, "0", plot.left, plot.bottom + dp(7), axisSize,
                colors.secondary, Paint.Align.CENTER, false);
        text(canvas, "255", plot.right, plot.bottom + dp(7), axisSize,
                colors.secondary, Paint.Align.CENTER, false);
        text(canvas, "输入", plot.centerX(), plot.bottom + dp(7), 9,
                colors.secondary, Paint.Align.CENTER, false);
        drawLegend(canvas);
    }

    private void drawLegend(Canvas canvas) {
        float top = card.bottom - dp(24);
        float center = card.centerX();
        float left = center - dp(91) - Math.max(0, scaledDensity - density) * 18;
        float right = center + dp(13);
        float lineY = top + sp(5.5f);
        paint.setColor(colors.primary);
        paint.setStrokeWidth(dp(2));
        canvas.drawLine(left, lineY, left + dp(14), lineY, paint);
        text(canvas, "预设曲线", left + dp(20), top, 10,
                colors.secondary, Paint.Align.LEFT, false);
        paint.setColor(withAlpha(colors.secondary, 200));
        paint.setStrokeWidth(dp(1));
        paint.setPathEffect(dashed);
        canvas.drawLine(right, lineY, right + dp(14), lineY, paint);
        paint.setPathEffect(null);
        text(canvas, "线性参考", right + dp(20), top, 10,
                colors.secondary, Paint.Align.LEFT, false);
    }

    /** Coordinates here use text top, which makes large-font spacing explicit. */
    private void text(Canvas canvas, String value, float x, float top, float size,
            int color, Paint.Align align, boolean medium) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(align);
        paint.setTypeface(medium ? MEDIUM : Typeface.DEFAULT);
        paint.setTextSize(sp(size));
        paint.setColor(color);
        canvas.drawText(value, x, top - paint.ascent(), paint);
    }

    private void textCenteredVertically(Canvas canvas, String value, float x, float centerY,
            float size, Paint.Align align) {
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(sp(size));
        paint.setTextAlign(align);
        paint.setColor(colors.secondary);
        canvas.drawText(value, x, centerY - (paint.ascent() + paint.descent()) / 2, paint);
    }

    private float dp(float value) { return value * density; }
    private float sp(float value) { return value * scaledDensity; }
    private static int withAlpha(int color, int alpha) { return (color & 0x00ffffff) | (alpha << 24); }
    private static int normalizedLabel(float value) { return Math.round(value * 255); }
    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("ToneCurveView must be updated on the main thread.");
        }
    }
}

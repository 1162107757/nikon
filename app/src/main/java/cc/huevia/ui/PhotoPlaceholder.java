package cc.huevia.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** A quiet thumbnail state, drawn in view bounds so CENTER_CROP cannot enlarge the pictogram. */
public final class PhotoPlaceholder extends Drawable {
    private final boolean failed;
    private final float density;
    private final int background, secondary, quiet, accent;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final AppIcon photograph;
    private int alpha = 255;

    public PhotoPlaceholder(Context context, UiPalette palette, boolean failed) {
        this.failed = failed;
        density = context.getResources().getDisplayMetrics().density;
        background = blend(palette.container, palette.card, .22f);
        secondary = palette.secondary;
        quiet = blend(palette.secondary, background, .50f);
        accent = palette.photoAccent;
        photograph = new AppIcon(AppIcon.Kind.PRESETS, blend(palette.secondary, background, .25f), density);
        paint.setTextSize(10 * context.getResources().getDisplayMetrics().scaledDensity);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        paint.setColor(background); paint.setAlpha(alpha);
        canvas.drawRect(bounds, paint);
        float edge = Math.min(bounds.width(), bounds.height());
        float iconSize = Math.max(0, Math.min(edge - dp(16), Math.min(dp(28), Math.max(dp(18), edge * .24f))));
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float textHeight = metrics.descent - metrics.ascent;
        boolean caption = failed && bounds.width() >= paint.measureText("暂无预览") + dp(16)
                && bounds.height() >= iconSize + textHeight + dp(26);
        boolean dots = !failed && edge >= dp(40);
        float extra = caption ? dp(9) + textHeight : dots ? dp(10) + dp(2.5f) : 0;
        float top = bounds.exactCenterY() - (iconSize + extra) / 2f;
        photograph.setBounds(Math.round(bounds.exactCenterX() - iconSize / 2f), Math.round(top),
                Math.round(bounds.exactCenterX() + iconSize / 2f), Math.round(top + iconSize));
        photograph.draw(canvas);
        if (caption) {
            paint.setColor(secondary); paint.setAlpha(alpha);
            canvas.drawText("暂无预览", bounds.exactCenterX(), top + iconSize + dp(9) - metrics.ascent, paint);
        } else if (dots) {
            float y = top + iconSize + dp(10) + dp(1.25f);
            for (int index = -1; index <= 1; index++) {
                paint.setColor(index == 0 ? accent : quiet); paint.setAlpha(alpha);
                canvas.drawCircle(bounds.exactCenterX() + index * dp(6), y, dp(1.25f), paint);
            }
        }
    }

    private float dp(float value) { return value * density; }
    private static int blend(int from, int to, float amount) {
        return Color.rgb(Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }
    @Override public void setAlpha(int value) { alpha = value; photograph.setAlpha(value); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); photograph.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

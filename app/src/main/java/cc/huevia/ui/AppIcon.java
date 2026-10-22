package cc.huevia.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** One family of compact vector pictograms, no font/emoji-dependent navigation. */
public final class AppIcon extends Drawable {
    public enum Kind { PRESETS, ALBUM, CAMERA, FILE, DOWNLOAD, BACK, HELP, CHECK, WIFI, ADJUST, REFRESH,
        SETTINGS, PLUS, USB, CHEVRON, CHEVRON_DOWN, INFO, LINK, DELETE, STAR, STAR_FILLED, RECEIPTS, PERSON, EXPAND, GRID, CLOSE, SEARCH }
    private final Kind kind;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    public AppIcon(Kind kind, int color, float density) {
        this.kind = kind; size = Math.round(24 * density);
        ink.setColor(color); ink.setStyle(Paint.Style.STROKE); ink.setStrokeWidth(1.8f);
        ink.setStrokeCap(Paint.Cap.ROUND); ink.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override public void draw(Canvas canvas) {
        int save = canvas.save(); canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width()/24f, getBounds().height()/24f);
        switch (kind) {
            case CLOSE: line(canvas,6,6,18,18);line(canvas,18,6,6,18);break;
            case GRID: canvas.drawRoundRect(new RectF(3,3,10,10),1,1,ink);canvas.drawRoundRect(new RectF(14,3,21,10),1,1,ink);canvas.drawRoundRect(new RectF(3,14,10,21),1,1,ink);canvas.drawRoundRect(new RectF(14,14,21,21),1,1,ink);break;
            case SEARCH: canvas.drawCircle(10,10,6,ink);line(canvas,15,15,21,21);break;
            case EXPAND:
                line(canvas,8,3,3,3,3,8); line(canvas,16,3,21,3,21,8);
                line(canvas,3,16,3,21,8,21); line(canvas,16,21,21,21,21,16); break;
            case PERSON:
                canvas.drawCircle(12,7.5f,4,ink);
                canvas.drawArc(new RectF(4,13,20,27),180,180,false,ink); line(canvas,4,20,20,20); break;
            case PRESETS:
                canvas.drawRoundRect(new RectF(3,3,21,21),2,2,ink);
                canvas.drawCircle(8,8,1.6f,ink); line(canvas,4,18,10,12,14,16,17,12,21,17); break;
            case ALBUM:
                canvas.drawRoundRect(new RectF(6,6,22,22),2,2,ink);
                line(canvas,3,17,2,17,2,2,17,2,17,3); line(canvas,7,18,12,13,16,17,19,14,22,18); break;
            case CAMERA:
                line(canvas,3,7,7,7,9,4,15,4,17,7,21,7,21,21,3,21,3,7);
                canvas.drawCircle(12,13,4,ink); break;
            case FILE: line(canvas,5,2,14,2,20,8,20,22,5,22,5,2); line(canvas,14,2,14,8,20,8); break;
            case DOWNLOAD: line(canvas,12,3,12,16); line(canvas,7,11,12,16,17,11); line(canvas,4,16,4,21,20,21,20,16); break;
            case DELETE:
                line(canvas,4,6,20,6); line(canvas,9,6,9,3,15,3,15,6);
                line(canvas,6,6,7,21,17,21,18,6); line(canvas,10,10,10,17); line(canvas,14,10,14,17); break;
            case STAR: star(canvas, false); break;
            case STAR_FILLED: star(canvas, true); break;
            case RECEIPTS:
                canvas.drawRoundRect(new RectF(3,3,21,21),3,3,ink);
                canvas.drawPoint(7,8,ink); canvas.drawPoint(7,12,ink); canvas.drawPoint(7,16,ink);
                line(canvas,11,8,17,8); line(canvas,11,12,17,12); line(canvas,11,16,15,16); break;
            case BACK: line(canvas,14,4,6,12,14,20); line(canvas,6,12,22,12); break;
            case HELP:
                canvas.drawCircle(12,12,10,ink); canvas.drawArc(new RectF(8,6,16,14),190,210,false,ink);
                line(canvas,12,13,12,15); canvas.drawPoint(12,18,ink); break;
            case CHECK: line(canvas,4,12,9,17,21,5); break;
            case WIFI:
                canvas.drawArc(new RectF(1,4,23,22),210,120,false,ink);
                canvas.drawArc(new RectF(5,9,19,21),215,110,false,ink); canvas.drawCircle(12,19,1,ink); break;
            case ADJUST:
                line(canvas,3,6,21,6); line(canvas,3,12,21,12); line(canvas,3,18,21,18);
                canvas.drawCircle(8,6,2,ink); canvas.drawCircle(16,12,2,ink); canvas.drawCircle(9,18,2,ink); break;
            case REFRESH:
                canvas.drawArc(new RectF(3.5f,3.5f,20.5f,20.5f),20,280,false,ink);
                line(canvas,16.25f,4.64f,20.5f,9);line(canvas,20.5f,3.5f,20.5f,9,15,9);break;
            case SETTINGS:
                gear(canvas); canvas.drawCircle(12,12,3.2f,ink); break;
            case PLUS: line(canvas,12,5,12,19); line(canvas,5,12,19,12); break;
            case USB:
                line(canvas,12,21,12,3); line(canvas,9,6,12,3,15,6);
                line(canvas,12,16,7,12,7,8); line(canvas,12,12,17,9,17,6);
                canvas.drawCircle(7,6.5f,1.5f,ink); canvas.drawRect(15.5f,3,18.5f,6,ink);
                canvas.drawCircle(12,21,1.2f,ink); break;
            case CHEVRON: line(canvas,9,5,16,12,9,19); break;
            case CHEVRON_DOWN: line(canvas,4,8,12,16,20,8); break;
            case INFO:
                canvas.drawCircle(12,12,9,ink); canvas.drawPoint(12,7,ink); line(canvas,12,11,12,17); break;
            case LINK:
                canvas.save(); canvas.rotate(-45,12,12);
                canvas.drawArc(new RectF(8,2,16,14),180,270,false,ink);
                canvas.drawArc(new RectF(8,10,16,22),0,270,false,ink);
                line(canvas,12,8,12,16); canvas.restore(); break;
        }
        canvas.restoreToCount(save);
    }
    private void line(Canvas canvas,float... xy) {
        Path path=new Path(); path.moveTo(xy[0],xy[1]); for(int i=2;i<xy.length;i+=2) path.lineTo(xy[i],xy[i+1]); canvas.drawPath(path,ink);
    }
    private void star(Canvas canvas, boolean filled) {
        Path path = new Path();
        for (int vertex = 0; vertex < 10; vertex++) {
            double angle = Math.toRadians(-90 + vertex * 36);
            float radius = vertex % 2 == 0 ? 10 : 4.5f;
            float x = 12 + (float) Math.cos(angle) * radius;
            float y = 12 + (float) Math.sin(angle) * radius;
            if (vertex == 0) path.moveTo(x,y); else path.lineTo(x,y);
        }
        path.close();
        Paint.Style previous = ink.getStyle();
        if (filled) ink.setStyle(Paint.Style.FILL);
        canvas.drawPath(path,ink);
        ink.setStyle(previous);
    }
    private void gear(Canvas canvas) {
        Path path = new Path();
        // Four vertices per tooth retain a quiet, mechanical outline at 24 dp.
        for (int tooth = 0; tooth < 8; tooth++) {
            for (int vertex = 0; vertex < 4; vertex++) {
                double angle = Math.toRadians(-101.25 + tooth * 45 + vertex * 11.25);
                float radius = vertex < 2 ? 10 : 8;
                float x = 12 + (float) Math.cos(angle) * radius;
                float y = 12 + (float) Math.sin(angle) * radius;
                if (tooth == 0 && vertex == 0) path.moveTo(x,y); else path.lineTo(x,y);
            }
        }
        path.close(); canvas.drawPath(path,ink);
    }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { ink.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { ink.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

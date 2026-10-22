package cc.huevia.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** A small, pre-blurred current-photo sample beneath legible native editor chrome. */
final class EditorGlassDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private final float radius, hairline;
    private final Bitmap backdrop;

    EditorGlassDrawable(Bitmap backdrop, float density, float radius) {
        this.backdrop = backdrop;
        this.radius = radius * density;
        this.hairline = Math.max(1f, density * .5f);
    }

    @Override public void draw(Canvas canvas) {
        bounds.set(getBounds());
        if (bounds.isEmpty()) return;
        int save = canvas.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(bounds, radius, radius, android.graphics.Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawColor(0xff22252a);
        if (backdrop != null && !backdrop.isRecycled()) {
            float scale = Math.max(bounds.width() / backdrop.getWidth(), bounds.height() / backdrop.getHeight());
            float width = backdrop.getWidth() * scale, height = backdrop.getHeight() * scale;
            RectF image = new RectF(bounds.centerX()-width/2, bounds.centerY()-height/2,
                    bounds.centerX()+width/2, bounds.centerY()+height/2);
            paint.setAlpha(255);
            canvas.drawBitmap(backdrop, null, image, paint);
            paint.setColor(0xe622252a);
            canvas.drawRect(bounds, paint);
        }
        paint.setShader(new android.graphics.LinearGradient(0,bounds.top,0,bounds.bottom,
                0x10ffffff,0x00ffffff,android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRect(bounds,paint);paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(hairline);
        paint.setColor(0x24ffffff);
        bounds.inset(hairline/2,hairline/2);
        canvas.drawRoundRect(bounds,radius,radius,paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restoreToCount(save);
    }

    /** Work is bounded to at most 64×64 pixels; call on the editor's I/O worker. */
    static Bitmap sample(Bitmap source) {
        float ratio = Math.min(1f,64f/Math.max(source.getWidth(),source.getHeight()));
        int width=Math.max(1,Math.round(source.getWidth()*ratio));
        int height=Math.max(1,Math.round(source.getHeight()*ratio));
        Bitmap small=Bitmap.createScaledBitmap(source,width,height,true);
        int[] pixels=new int[width*height],out=new int[pixels.length];
        small.getPixels(pixels,0,width,0,0,width,height);
        for(int pass=0;pass<3;pass++) {
            for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                int red=0,green=0,blue=0,count=0;
                for(int dy=-3;dy<=3;dy++)for(int dx=-3;dx<=3;dx++) {
                    int sx=Math.max(0,Math.min(width-1,x+dx)),sy=Math.max(0,Math.min(height-1,y+dy));
                    int pixel=pixels[sy*width+sx];red+=(pixel>>16)&255;green+=(pixel>>8)&255;blue+=pixel&255;count++;
                }
                out[y*width+x]=0xff000000|(red/count<<16)|(green/count<<8)|blue/count;
            }
            int[] old=pixels;pixels=out;out=old;
        }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public void getOutline(android.graphics.Outline outline) { outline.setRoundRect(getBounds(),radius); }
}

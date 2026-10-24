package cc.huevia.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;

/** Real, bounded photo-backdrop material. The owner must not be inside the sampled View. */
public final class PhotoGlassDrawable extends Drawable implements AutoCloseable {
    private final View source,owner;private final float radius,hairline;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF rect=new RectF();private final Path clip=new Path();
    private Bitmap backdrop;private boolean dirty=true,sampling,closed;private int alpha=255;
    private int lastX,lastY,lastWidth,lastHeight;
    public PhotoGlassDrawable(View photoSource,View owner,float density,float radiusDp){
        if(photoSource==null||owner==null||photoSource==owner)throw new IllegalArgumentException("Glass needs a separate photo source");
        for(ViewParent parent=owner.getParent();parent!=null;parent=parent.getParent())if(parent==photoSource)throw new IllegalArgumentException("Glass owner cannot be inside its source");
        source=photoSource;this.owner=owner;radius=radiusDp*density;hairline=Math.max(1,density*.5f);
    }
    public void refresh(){if(closed)return;dirty=true;invalidateSelf();owner.invalidate();}
    public boolean hasBackdrop(){return backdrop!=null&&!backdrop.isRecycled();}
    public int sampledPixelCount(){return hasBackdrop()?backdrop.getWidth()*backdrop.getHeight():0;}
    private void sample(){
        if(closed||sampling||owner.getWidth()<1||owner.getHeight()<1||source.getWidth()<1||source.getHeight()<1)return;
        int[] origin=new int[2],position=new int[2];source.getLocationOnScreen(origin);owner.getLocationOnScreen(position);
        int x=position[0]-origin[0],y=position[1]-origin[1],width=owner.getWidth(),height=owner.getHeight();
        if(!dirty&&x==lastX&&y==lastY&&width==lastWidth&&height==lastHeight)return;
        sampling=true;Bitmap sampled=null;
        try{
            float scale=Math.min(1,192f/Math.max(width,height));int w=Math.max(1,Math.round(width*scale)),h=Math.max(1,Math.round(height*scale));
            sampled=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);sampled.setDensity(Bitmap.DENSITY_NONE);
            Canvas snapshot=new Canvas(sampled);snapshot.scale(w/(float)width,h/(float)height);snapshot.translate(-x,-y);source.draw(snapshot);
            int[] pixels=new int[w*h],scratch=new int[pixels.length];sampled.getPixels(pixels,0,w,0,0,w,h);
            for(int pass=0;pass<2;pass++){blur(pixels,scratch,w,h,7,true);blur(scratch,pixels,w,h,7,false);}
            sampled.setPixels(pixels,0,w,0,0,w,h);Bitmap old=backdrop;backdrop=sampled;sampled=null;
            if(old!=null&&!old.isRecycled())old.recycle();dirty=false;lastX=x;lastY=y;lastWidth=width;lastHeight=height;
        }catch(RuntimeException|OutOfMemoryError unavailable){/* A readable translucent fallback cannot stop editing. */}
        finally{sampling=false;if(sampled!=null)sampled.recycle();}
    }
    private static void blur(int[] input,int[] output,int width,int height,int radius,boolean horizontal){
        int lines=horizontal?height:width,length=horizontal?width:height,divisor=radius*2+1;
        for(int line=0;line<lines;line++){
            int a=0,r=0,g=0,b=0;for(int k=-radius;k<=radius;k++){int p=input[index(line,Math.max(0,Math.min(length-1,k)),width,horizontal)];a+=p>>>24;r+=(p>>>16)&255;g+=(p>>>8)&255;b+=p&255;}
            for(int k=0;k<length;k++){
                output[index(line,k,width,horizontal)]=(a/divisor<<24)|(r/divisor<<16)|(g/divisor<<8)|b/divisor;
                int leaving=input[index(line,Math.max(0,k-radius),width,horizontal)],entering=input[index(line,Math.min(length-1,k+radius+1),width,horizontal)];
                a+=(entering>>>24)-(leaving>>>24);r+=((entering>>>16)&255)-((leaving>>>16)&255);g+=((entering>>>8)&255)-((leaving>>>8)&255);b+=(entering&255)-(leaving&255);
            }
        }
    }
    private static int index(int line,int point,int width,boolean horizontal){return horizontal?line*width+point:point*width+line;}
    @Override public void draw(Canvas canvas){
        rect.set(getBounds());if(rect.isEmpty()||closed||sampling)return;sample();
        int save=canvas.save();clip.reset();clip.addRoundRect(rect,radius,radius,Path.Direction.CW);canvas.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setAlpha(alpha);
        if(hasBackdrop())canvas.drawBitmap(backdrop,null,rect,paint);
        paint.setColor(0xa6201e1c);paint.setAlpha(Math.round(.65f*alpha));canvas.drawRect(rect,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(hairline);paint.setColor(0x45ffffff);paint.setAlpha(Math.round(.27f*alpha));
        rect.inset(hairline/2,hairline/2);canvas.drawRoundRect(rect,radius,radius,paint);paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int alpha){this.alpha=Math.max(0,Math.min(255,alpha));invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    @Override public void getOutline(Outline outline){outline.setRoundRect(getBounds(),radius);}
    @Override public void close(){closed=true;if(backdrop!=null){backdrop.recycle();backdrop=null;}}
}

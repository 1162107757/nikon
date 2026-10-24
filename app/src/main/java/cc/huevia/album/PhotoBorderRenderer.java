package cc.huevia.album;

import android.content.res.AssetManager;
import android.graphics.*;
import cc.huevia.ncp.PhotoBorder;
import java.io.*;
import java.util.*;

/** Shared proportional layout for real preview pixels and full-resolution JPEG exports. */
public final class PhotoBorderRenderer {
    private final AssetManager assets;
    private final Map<String,Bitmap> logos=new HashMap<>();
    private Typeface sport;
    public PhotoBorderRenderer(AssetManager assets){this.assets=assets;}
    private Bitmap logo(String name)throws IOException{Bitmap b=logos.get(name);if(b==null){if(assets==null)throw new IOException("边框素材尚未就绪。");try(InputStream in=assets.open("photo-borders/"+name+".png")){b=BitmapFactory.decodeStream(in);}if(b==null)throw new IOException("无法读取边框 Logo。");logos.put(name,b);}return b;}
    public Bitmap apply(Bitmap photo,PhotoBorder b,PhotoCaptureInfo info)throws IOException{
        if(b.isNone())return photo;
        int w=photo.getWidth(),h=photo.getHeight();int[] size=b.dimensions(w,h);
        if(size[0]>65535||size[1]>65535||(long)size[0]*size[1]>100_000_000)throw new IOException("添加边框后的尺寸过大，请先裁剪照片。");
        Bitmap output=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);boolean complete=false;
        try{Canvas c=new Canvas(output);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);float unit=w,margin=b.margin/100f;
            if(b.template==3){c.drawColor(Color.WHITE);c.drawBitmap(photo,0,0,p);footer(c,b,info,w,h,size[1]);}
            else{
                background(c,photo,b,size[0],size[1],p);
                float x=b.template==1?(size[0]-w)/2f:size[0]-w-unit*.08f*margin,y=unit*(b.template==1?.06f:.07f)*margin;
                RectF rect=new RectF(x,y,x+w,y+h);float radius=unit*.025f*b.corner/100f;
                if(b.shadow>0){p.setColor(0x99000000);p.setShadowLayer(unit*.025f*b.shadow/100f,0,unit*.014f,0xb0000000);c.drawRoundRect(rect,radius,radius,p);p.clearShadowLayer();}
                int save=c.save();Path clip=new Path();clip.addRoundRect(rect,radius,radius,Path.Direction.CW);c.clipPath(clip);p.setColor(Color.WHITE);c.drawBitmap(photo,x,y,p);c.restoreToCount(save);
                if(b.template==1)center(c,b,info,w,x,y+h,size[0]);else sidebar(c,b,info,w,h,x,y);
            }
            complete=true;return output;
        }finally{if(!complete)output.recycle();}
    }
    private void background(Canvas c,Bitmap photo,PhotoBorder b,int w,int h,Paint p){
        int edge=144;int sw=photo.getWidth()>=photo.getHeight()?edge:Math.max(2,Math.round(edge*photo.getWidth()/(float)photo.getHeight())),sh=photo.getHeight()>=photo.getWidth()?edge:Math.max(2,Math.round(edge*photo.getHeight()/(float)photo.getWidth()));
        Bitmap sample=Bitmap.createScaledBitmap(photo,sw,sh,true);Bitmap blurred=sample;
        if(b.blur>0){int[] pix=new int[sw*sh];sample.getPixels(pix,0,sw,0,0,sw,sh);int radius=Math.max(1,Math.round(10*b.blur/100f));for(int pass=0;pass<3;pass++)box(pix,sw,sh,radius);blurred=Bitmap.createBitmap(pix,sw,sh,Bitmap.Config.ARGB_8888);}
        float scale=Math.max(w/(float)sw,h/(float)sh),bw=sw*scale,bh=sh*scale;c.drawBitmap(blurred,null,new RectF((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2),p);
        if(blurred!=sample)blurred.recycle();if(sample!=photo)sample.recycle();
    }
    private static void box(int[] pixels,int w,int h,int r){int[] temp=new int[pixels.length];for(int axis=0;axis<2;axis++){int a=axis==0?w:h,z=axis==0?h:w;for(int line=0;line<z;line++){long red=0,green=0,blue=0;for(int k=-r;k<=r;k++){int pos=Math.max(0,Math.min(a-1,k)),v=pixels[axis==0?line*w+pos:pos*w+line];red+=(v>>16)&255;green+=(v>>8)&255;blue+=v&255;}for(int j=0;j<a;j++){int count=2*r+1;temp[axis==0?line*w+j:j*w+line]=0xff000000|((int)(red/count)<<16)|((int)(green/count)<<8)|(int)(blue/count);int old=Math.max(0,j-r),next=Math.min(a-1,j+r+1),v=pixels[axis==0?line*w+old:old*w+line],n=pixels[axis==0?line*w+next:next*w+line];red+=((n>>16)&255)-((v>>16)&255);green+=((n>>8)&255)-((v>>8)&255);blue+=(n&255)-(v&255);}}System.arraycopy(temp,0,pixels,0,pixels.length);}}
    private Paint type(float size,int color,boolean bold){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setTextSize(size);p.setColor(color);p.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));return p;}
    private void text(Canvas c,String value,float x,float baseline,float size,float max,int color,boolean bold){if(value==null||value.isEmpty()||max<=0)return;Paint p=type(size,color,bold);float width=p.measureText(value);if(width>max)p.setTextSize(size*max/width);c.drawText(value,x,baseline,p);}
    private float brand(Canvas c,PhotoBorder b,PhotoCaptureInfo info,float x,float y,float height,boolean white)throws IOException{
        return brand(c,b,info,x,y,height,white,Float.POSITIVE_INFINITY);
    }
    private float brand(Canvas c,PhotoBorder b,PhotoCaptureInfo info,float x,float y,float height,boolean white,float maxWidth)throws IOException{
        String name=info.brand(b);if(name.isEmpty())return 0;
        if(name.equals("NIKON")){String file=b.logo==1?"nikon-yellow-square":b.logo==2?"nikon-wordmark-white":b.logo==3?"nikon-wordmark-black":white?"nikon-wordmark-white":"nikon-yellow-square";Bitmap image=logo(file);float width=height*image.getWidth()/image.getHeight();if(width>maxWidth){float actualHeight=height*maxWidth/width;y+=(height-actualHeight)/2;height=actualHeight;width=maxWidth;}c.drawBitmap(image,null,new RectF(x,y,x+width,y+height),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));return width;}
        Paint p=type(height,white?Color.WHITE:Color.BLACK,true);String label=name.equals("CANON")?"Canon":name;float width=p.measureText(label);if(width>maxWidth)p.setTextSize(height*maxWidth/width);c.drawText(label,x,y+height/2-(p.ascent()+p.descent())/2,p);return p.measureText(label);
    }
    private void center(Canvas c,PhotoBorder b,PhotoCaptureInfo info,float w,float x,float bottom,float total)throws IOException{
        float m=b.margin/100f;float h=w*.027f*m,baseline=bottom+w*.048f*b.margin/100f;String model=b.shows(0)?info.model(b):"";Paint p=type(w*.021f*m,Color.WHITE,false);
        float logoWidth=info.brand(b).isEmpty()?0:info.brand(b).equals("NIKON")?(b.logo==1?h:h*(1600f/443)):Math.min(w*.25f,type(h,Color.WHITE,true).measureText(info.brand(b).equals("CANON")?"Canon":info.brand(b)));float textWidth=Math.min(w*.58f,p.measureText(model)),gap=model.isEmpty()||logoWidth==0?0:w*.02f;float start=(total-logoWidth-gap-textWidth)/2;
        brand(c,b,info,start,baseline-h,h,true,w*.25f);text(c,model,start+logoWidth+gap,baseline,w*.021f*m,w*.58f,Color.WHITE,false);
        String line=info.parameters(b);Paint small=type(w*.018f*m,Color.WHITE,false);float width=small.measureText(line);float max=w*.82f;if(width>max){small.setTextSize(small.getTextSize()*max/width);width=max;}c.drawText(line,(total-width)/2,baseline+w*.028f*m,small);
    }
    private void sidebar(Canvas c,PhotoBorder b,PhotoCaptureInfo info,float w,float h,float x,float y)throws IOException{
        float m=b.margin/100f;brand(c,b,info,w*.085f*m,y+h*.16f,w*.049f*m,true,x-w*.125f*m);
        if(sport==null&&assets!=null)try{sport=Typeface.createFromAsset(assets,"photo-borders/parameters.ttf");}catch(RuntimeException unavailable){throw new IOException("边框参数字体不可用。",unavailable);}
        String[] names={"F","ISO","S"};int[] fields={1,3,2};for(int row=0;row<3;row++){int field=fields[row];String value=info.field(b,field);if(!b.shows(field+1)||value.isEmpty())continue;if(field==1)value=value.replaceFirst("(?i)^F","");if(field==3)value=value.replaceFirst("(?i)^ISO","");if(field==2)value=value.replaceFirst("s$","");float top=y+h*(.46f+row*.175f),left=w*.09f*m,bw=w*.09f*m,bh=w*.047f*m;Paint p=type(w*.024f*m,Color.WHITE,true);if(sport!=null)p.setTypeface(sport);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w*.0033f*m);c.drawRoundRect(new RectF(left,top,left+bw,top+bh),w*.009f*m,w*.009f*m,p);p.setStyle(Paint.Style.FILL);p.setTextSize(w*.022f*m);c.drawText(names[row],left+(bw-p.measureText(names[row]))/2,top+bh*.69f,p);p.setTextSize(w*.027f*m);float max=x-left-bw-w*.06f*m,measure=p.measureText(value);if(measure>max)p.setTextSize(p.getTextSize()*max/measure);c.drawText(value,left+bw+w*.045f*m,top+bh*.7f,p);}
    }
    private void footer(Canvas c,PhotoBorder b,PhotoCaptureInfo info,float w,float h,float outH)throws IOException{
        float fh=outH-h,first=h+fh*.43f,second=h+fh*.76f;
        text(c,info.company(b),w*.04f,first,w*.024f,w*.51f,Color.BLACK,true);
        if(b.shows(0))text(c,info.model(b),w*.04f,second,w*.018f,w*.51f,0xff777777,false);
        float logoHeight=fh*.40f;if(info.brand(b).equals("NIKON")&&(b.logo==2||b.logo==3))logoHeight=w*.042f/3.612f;brand(c,b,info,w*.606f,h+fh*.50f-logoHeight/2,logoHeight,false,w*.045f);Paint line=type(1,0xffd5d5d5,false);line.setStrokeWidth(Math.max(1,w*.0006f));if(!info.brand(b).isEmpty())c.drawLine(w*.664f,h+fh*.24f,w*.664f,h+fh*.78f,line);
        text(c,info.parameters(b),w*.68f,first,w*.023f,w*.28f,Color.BLACK,true);
        if(b.shows(5))text(c,info.field(b,4),w*.68f,second,w*.020f,w*.28f,0xff777777,false);
    }
}

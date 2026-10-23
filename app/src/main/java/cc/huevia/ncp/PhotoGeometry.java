package cc.huevia.ncp;

import java.util.Objects;

/** Immutable, source-independent crop in the oriented/rotated frame. No source pixels are changed. */
public final class PhotoGeometry {
    public static final int UNIT=1_000_000;
    public static final String MODEL="photo-composition-affine-v1";
    public static final PhotoGeometry NONE=new PhotoGeometry(0,0,0,0,UNIT,UNIT);
    public final int turns,angle,left,top,right,bottom;
    public PhotoGeometry(int turns,int angle,int left,int top,int right,int bottom){
        if(turns<0||turns>3||angle< -450||angle>450||left<0||top<0||right>UNIT||bottom>UNIT||right<=left||bottom<=top)
            throw new IllegalArgumentException("构图参数无效。");
        this.turns=turns;this.angle=angle;this.left=left;this.top=top;this.right=right;this.bottom=bottom;
    }
    public boolean isNeutral(){return equals(NONE);}
    public PhotoGeometry fullFrame(){return new PhotoGeometry(turns,angle,0,0,UNIT,UNIT);}
    public PhotoGeometry rotate(){return new PhotoGeometry((turns+1)%4,angle,0,0,UNIT,UNIT);}
    public PhotoGeometry straighten(int value){return new PhotoGeometry(turns,value,left,top,right,bottom);}
    public PhotoGeometry crop(double l,double t,double r,double b){return new PhotoGeometry(turns,angle,quantize(l),quantize(t),quantize(r),quantize(b));}
    private static int quantize(double v){if(!Double.isFinite(v))throw new IllegalArgumentException("裁剪位置无效。");return (int)Math.round(v*UNIT);}
    public String identity(){return MODEL+":"+turns+":"+angle+":"+left+":"+top+":"+right+":"+bottom;}
    public String label(){return isNeutral()?"未构图":"已构图"+(turns==0?"":" · 旋转 "+turns*90+"°")+(angle==0?"":" · 拉直 "+String.format(java.util.Locale.ROOT,"%.1f°",angle/10.0));}
    @Override public boolean equals(Object other){if(!(other instanceof PhotoGeometry))return false;PhotoGeometry g=(PhotoGeometry)other;return turns==g.turns&&angle==g.angle&&left==g.left&&top==g.top&&right==g.right&&bottom==g.bottom;}
    @Override public int hashCode(){return Objects.hash(turns,angle,left,top,right,bottom);}
    /** Edge-coordinate affine map; caller filters pixels once, then applies this composition. */
    public Plan plan(int width,int height,int exif){
        if(width<1||height<1||width>65535||height>65535||exif<1||exif>8)throw new IllegalArgumentException("照片尺寸或方向无效。");
        int ow=exif>=5?height:width,oh=exif>=5?width:height;
        int bw=turns%2==0?ow:oh,bh=turns%2==0?oh:ow;
        int l=(int)Math.round(left/(double)UNIT*bw),t=(int)Math.round(top/(double)UNIT*bh);
        l=Math.min(l,bw-1);t=Math.min(t,bh-1);
        int r=Math.max(l+1,Math.min(bw,(int)Math.round(right/(double)UNIT*bw)));
        int b=Math.max(t+1,Math.min(bh,(int)Math.round(bottom/(double)UNIT*bh)));
        double[] p=point(0,0,width,height,exif,bw,bh),x=point(width,0,width,height,exif,bw,bh),y=point(0,height,width,height,exif,bw,bh);
        return new Plan(r-l,b-t,bw,bh,(x[0]-p[0])/width,(y[0]-p[0])/height,p[0]-l,(x[1]-p[1])/width,(y[1]-p[1])/height,p[1]-t);
    }
    private double[] point(double x,double y,int w,int h,int exif,int bw,int bh){
        double ox=x,oy=y;int ow=exif>=5?h:w,oh=exif>=5?w:h;
        switch(exif){case 2:ox=w-x;break;case 3:ox=w-x;oy=h-y;break;case 4:oy=h-y;break;case 5:ox=y;oy=x;break;case 6:ox=h-y;oy=x;break;case 7:ox=h-y;oy=w-x;break;case 8:ox=y;oy=w-x;break;}
        double tx=ox,ty=oy;
        switch(turns){case 1:tx=oh-oy;ty=ox;break;case 2:tx=ow-ox;ty=oh-oy;break;case 3:tx=oy;ty=ow-ox;break;}
        double radians=Math.toRadians(angle/10.0),c=Math.cos(radians),s=Math.sin(radians);
        // Rotate around centre and zoom just enough to cover all four frame corners.
        double zoom=c+Math.abs(s)*Math.max(bw/(double)bh,bh/(double)bw),dx=tx-bw/2.0,dy=ty-bh/2.0;
        return new double[]{bw/2.0+zoom*(c*dx-s*dy),bh/2.0+zoom*(s*dx+c*dy)};
    }
    public static final class Plan {
        public final int width,height,frameWidth,frameHeight;
        public final double a,b,c,d,e,f;
        Plan(int w,int h,int fw,int fh,double a,double b,double c,double d,double e,double f){width=w;height=h;frameWidth=fw;frameHeight=fh;this.a=a;this.b=b;this.c=c;this.d=d;this.e=e;this.f=f;}
        public float[] matrix(){return new float[]{(float)a,(float)b,(float)c,(float)d,(float)e,(float)f,0,0,1};}
        public double[] inverse(double x,double y){double det=a*e-b*d;return new double[]{(e*(x-c)-b*(y-f))/det,(-d*(x-c)+a*(y-f))/det};}
    }
}

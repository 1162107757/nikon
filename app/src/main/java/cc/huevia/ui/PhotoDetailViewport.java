package cc.huevia.ui;

/** Pixel coordinates only: scale 1 is one content pixel per physical canvas pixel. */
public final class PhotoDetailViewport {
    public int imageWidth, imageHeight, canvasWidth, canvasHeight;
    public double scale=1, centerX, centerY;
    public boolean fitting=true;
    public double fit(){return imageWidth<1||imageHeight<1||canvasWidth<1||canvasHeight<1?1:Math.min(1,Math.min(canvasWidth/(double)imageWidth,canvasHeight/(double)imageHeight));}
    public void image(int width,int height){
        double nx=imageWidth>0?centerX/imageWidth:.5,ny=imageHeight>0?centerY/imageHeight:.5;
        imageWidth=width;imageHeight=height;centerX=nx*width;centerY=ny*height;
        if(fitting)scale=fit();constrain();
    }
    public void canvas(int width,int height){canvasWidth=width;canvasHeight=height;if(fitting)scale=fit();constrain();}
    public void fitImage(){fitting=true;scale=fit();centerX=imageWidth/2.0;centerY=imageHeight/2.0;constrain();}
    public void zoom(double value,double focusX,double focusY){
        if(imageWidth<1||!Double.isFinite(value))return;
        double old=scale,next=Math.max(fit(),Math.min(2,value));
        centerX+=(focusX-canvasWidth/2.0)*(1/old-1/next);
        centerY+=(focusY-canvasHeight/2.0)*(1/old-1/next);
        scale=next;fitting=false;constrain();
    }
    public void move(double dx,double dy){centerX-=dx/scale;centerY-=dy/scale;constrain();}
    public void center(double x,double y){centerX=x;centerY=y;constrain();}
    public void constrain(){
        if(imageWidth<1||imageHeight<1)return;
        scale=Math.max(fit(),Math.min(2,scale));
        double halfX=canvasWidth/(2*scale),halfY=canvasHeight/(2*scale);
        centerX=imageWidth<=halfX*2?imageWidth/2.0:Math.max(halfX,Math.min(imageWidth-halfX,centerX));
        centerY=imageHeight<=halfY*2?imageHeight/2.0:Math.max(halfY,Math.min(imageHeight-halfY,centerY));
    }
    public double left(){return canvasWidth/2.0-centerX*scale;}
    public double top(){return canvasHeight/2.0-centerY*scale;}
    public int sample(){int sample=1;while(sample<32768&&sample*2<=1/scale)sample*=2;return sample;}
}

package cc.huevia.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import cc.huevia.album.PhotoDetailSource;

/** Physical-pixel canvas. No ImageView/resource density matrix participates in 100% rendering. */
public final class PhotoDetailView extends View implements AutoCloseable {
    public interface Listener {
        void state(boolean loading,String message,double scale,boolean fitting,int mode,boolean failed);
        void invalidated();
    }
    private static final int PAN_LEFT=0x01020001,PAN_RIGHT=0x01020002,PAN_UP=0x01020003,PAN_DOWN=0x01020004;
    private final PhotoDetailSource source;private final Listener listener;
    private final PhotoDetailViewport viewport=new PhotoDetailViewport();
    private final Paint imagePaint=new Paint(),miniPaint=new Paint(Paint.ANTI_ALIAS_FLAG),linePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mini=new RectF();
    private final RectF floatingControls=new RectF();
    private final GestureDetector gestures;private final ScaleGestureDetector scales;
    private PhotoDetailSource.Frame frame;private int mode=PhotoDetailSource.CURRENT;
    private boolean closed,paused,loading,failed,miniDrag;private long revision;
    private final Runnable submit=this::submitFrame;
    private final Runnable validity=new Runnable(){public void run(){if(closed||paused)return;if(!source.valid()){listener.invalidated();return;}postDelayed(this,750);}};
    public PhotoDetailView(Context context,PhotoDetailSource source,Listener listener){
        super(context);this.source=source;this.listener=listener;setBackgroundColor(0xff0b0b0b);setFocusable(true);setClickable(true);
        setContentDescription("照片细节。双击切换适应与100%，双指缩放，放大后拖动查看。");
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onDoubleTap(MotionEvent e){
                if(viewport.imageWidth<1)return true;
                if(viewport.fitting)viewport.zoom(1,e.getX(),e.getY());else viewport.fitImage();changed();return true;
            }
            @Override public boolean onScroll(MotionEvent first,MotionEvent now,float dx,float dy){if(!scales.isInProgress()){viewport.move(-dx,-dy);changed();}return true;}
            @Override public boolean onSingleTapConfirmed(MotionEvent e){performClick();return true;}
        });
        scales=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){viewport.zoom(viewport.scale*detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY());changed();return true;}
        });
        linePaint.setColor(0xfff3b493);linePaint.setStrokeWidth(dp(2));linePaint.setStyle(Paint.Style.STROKE);
        miniPaint.setFilterBitmap(true);post(validity);
    }
    public int selectedMode(){return mode;}
    public void mode(int next){if(next==PhotoDetailSource.BEFORE&&!source.canCompare()||mode==next)return;mode=next;failed=false;changed();}
    public void fit(){viewport.fitImage();changed();}
    public void zoom(double scale){viewport.zoom(scale,getWidth()/2.0,getHeight()/2.0);changed();}
    public void retry(){failed=false;changed();}
    public void pause(){if(closed||paused)return;paused=true;revision++;removeCallbacks(submit);removeCallbacks(validity);source.cancelFrames();frame=null;source.releaseMemory();invalidate();}
    public void resume(){if(closed||!paused)return;paused=false;post(validity);changed();}
    public double scale(){return viewport.scale;}
    public boolean isFitting(){return viewport.fitting;}
    public int contentWidth(){return viewport.imageWidth;}
    public int contentHeight(){return viewport.imageHeight;}
    public RectF visiblePhotoBounds(){if(viewport.imageWidth<1)return new RectF();return new RectF((float)Math.max(0,viewport.left()),(float)Math.max(0,viewport.top()),(float)Math.min(getWidth(),viewport.left()+viewport.imageWidth*viewport.scale),(float)Math.min(getHeight(),viewport.top()+viewport.imageHeight*viewport.scale));}
    public void setFloatingControls(RectF bounds){if(!floatingControls.equals(bounds)){floatingControls.set(bounds);invalidate();}}
    private void changed(){
        if(closed||paused)return;revision++;loading=true;failed=false;
        notifyState("正在载入"+(mode==PhotoDetailSource.BEFORE?"调色前":"当前效果")+" · 真实细节");
        invalidate();removeCallbacks(submit);postDelayed(submit,45);
    }
    private void submitFrame(){
        if(closed||paused||getWidth()<1||getHeight()<1)return;
        if(!source.valid()){listener.invalidated();return;}
        final long token=revision;
        source.frame(mode,getWidth(),getHeight(),viewport.fitting?0:viewport.scale,viewport.imageWidth<1?.5:viewport.centerX/viewport.imageWidth,viewport.imageHeight<1?.5:viewport.centerY/viewport.imageHeight,new PhotoDetailSource.Callback(){
            public void loaded(PhotoDetailSource.Frame result){
                if(closed||paused||token!=revision)return;
                frame=result;viewport.image(result.width,result.height);loading=false;failed=false;
                notifyState(viewport.fitting?"点按100%查看真实像素":Math.abs(viewport.scale-1)<.001?"像素1:1 · 拖动查看细节":viewport.scale>1?"放大查看，不增加细节":"拖动查看细节");invalidate();
            }
            public void failed(String message){if(closed||paused||token!=revision)return;loading=false;failed=true;notifyState(message);invalidate();}
            public void invalidated(){if(!closed)listener.invalidated();}
        });
    }
    private void notifyState(String message){
        if((loading||failed)&&frame!=null&&frame.mode!=mode)message+=" · 仍显示"+(frame.mode==PhotoDetailSource.BEFORE?"调色前":"当前效果");
        listener.state(loading,message,viewport.scale,viewport.fitting,mode,failed);
    }
    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){viewport.canvas(width,height);changed();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);mini.setEmpty();PhotoDetailSource.Frame shown=frame;if(shown==null)return;
        double scale=viewport.scale,left=viewport.left(),top=viewport.top();
        if(Math.abs(scale-1)<.00001||Math.abs(scale-2)<.00001){left=Math.round(left);top=Math.round(top);}
        canvas.save();canvas.translate((float)left,(float)top);canvas.scale((float)scale,(float)scale);
        imagePaint.setFilterBitmap(true);canvas.drawBitmap(shown.overview,null,new RectF(0,0,shown.width,shown.height),imagePaint);
        imagePaint.setFilterBitmap(scale<.99999);
        for(PhotoDetailSource.Tile tile:shown.tiles)canvas.drawBitmap(tile.bitmap,null,new RectF(tile.bounds),imagePaint);
        canvas.restore();
        if(scale>viewport.fit()+.0001){
            RectF photo=visiblePhotoBounds();float maxWidth=Math.min(dp(110),getWidth()*.28f),maxHeight=Math.min(dp(86),photo.height()*.24f);
            float miniScale=Math.min(maxWidth/shown.width,maxHeight/shown.height),w=shown.width*miniScale,h=shown.height*miniScale;
            mini.set(photo.right-dp(12)-w,photo.bottom-dp(12)-h,photo.right-dp(12),photo.bottom-dp(12));
            if(RectF.intersects(mini,floatingControls))mini.offset(0,floatingControls.top-dp(10)-mini.bottom);
            miniPaint.setColor(0xff292724);canvas.drawRect(mini.left-dp(3),mini.top-dp(3),mini.right+dp(3),mini.bottom+dp(3),miniPaint);
            canvas.drawBitmap(shown.overview,null,mini,miniPaint);
            float l=(float)Math.max(0,viewport.centerX-getWidth()/(2*scale)),t=(float)Math.max(0,viewport.centerY-getHeight()/(2*scale));
            float r=(float)Math.min(shown.width,viewport.centerX+getWidth()/(2*scale)),b=(float)Math.min(shown.height,viewport.centerY+getHeight()/(2*scale));
            canvas.drawRect(mini.left+l*miniScale,mini.top+t*miniScale,mini.left+r*miniScale,mini.top+b*miniScale,linePaint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(closed||paused)return false;
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){RectF hit=new RectF(mini);hit.inset(-dp(6),-dp(6));miniDrag=!mini.isEmpty()&&hit.contains(event.getX(),event.getY());}
        if(miniDrag){
            if(event.getPointerCount()==1&&!mini.isEmpty()){viewport.center((event.getX()-mini.left)/mini.width()*viewport.imageWidth,(event.getY()-mini.top)/mini.height()*viewport.imageHeight);changed();}
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){miniDrag=false;performClick();}return true;
        }
        scales.onTouchEvent(event);gestures.onTouchEvent(event);return true;
    }
    @Override public boolean performClick(){return super.performClick();}
    @Override public boolean onKeyDown(int keyCode,KeyEvent event){
        switch(keyCode){case KeyEvent.KEYCODE_DPAD_LEFT:pan(-1,0);return true;case KeyEvent.KEYCODE_DPAD_RIGHT:pan(1,0);return true;case KeyEvent.KEYCODE_DPAD_UP:pan(0,-1);return true;case KeyEvent.KEYCODE_DPAD_DOWN:pan(0,1);return true;case KeyEvent.KEYCODE_PLUS:case KeyEvent.KEYCODE_EQUALS:zoom(viewport.scale*1.25);return true;case KeyEvent.KEYCODE_MINUS:zoom(viewport.scale/1.25);return true;case KeyEvent.KEYCODE_ENTER:if(viewport.fitting)zoom(1);else fit();return true;default:return super.onKeyDown(keyCode,event);}
    }
    private void pan(int dx,int dy){viewport.move(-dx*getWidth()*.25,-dy*getHeight()*.25);changed();}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
        super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.ImageView");
        info.setContentDescription("照片真实细节，"+Math.round(viewport.scale*100)+"%，"+(mode==PhotoDetailSource.BEFORE?"调色前":"当前效果")+(loading?"，正在载入":failed?"，载入失败":""));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(PAN_LEFT,"查看左侧"));info.addAction(new AccessibilityNodeInfo.AccessibilityAction(PAN_RIGHT,"查看右侧"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(PAN_UP,"查看上方"));info.addAction(new AccessibilityNodeInfo.AccessibilityAction(PAN_DOWN,"查看下方"));
    }
    @Override public boolean performAccessibilityAction(int action,android.os.Bundle args){switch(action){case PAN_LEFT:pan(-1,0);return true;case PAN_RIGHT:pan(1,0);return true;case PAN_UP:pan(0,-1);return true;case PAN_DOWN:pan(0,1);return true;default:return super.performAccessibilityAction(action,args);}}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    @Override public void close(){if(closed)return;closed=true;revision++;removeCallbacks(submit);removeCallbacks(validity);source.cancelFrames();frame=null;mini.setEmpty();invalidate();}
}

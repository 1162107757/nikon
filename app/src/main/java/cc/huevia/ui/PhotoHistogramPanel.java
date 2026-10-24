package cc.huevia.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import cc.huevia.ncp.PhotoHistogram;
import java.util.concurrent.CancellationException;

/** Editor-only diagnostics. The host owns snapshot identity, request ordering and image lifetime.
 * Neither statistics nor warning preferences are edit parameters or export inputs. */
public final class PhotoHistogramPanel extends LinearLayout {
    public interface Listener { void onWarningsChanged(boolean shadows, boolean highlights); }
    private static final int TEXT=0xfff4f5f7, ACCENT=0xfff3b493;
    private final Listener listener;
    private final ToggleButton luminance, rgb, shadow, highlight;
    private final Chart chart;
    private final ImageButton info;
    private PhotoHistogram data;
    private boolean rgbMode, shadowOn, highlightOn, updating;
    private String error="",status="";
    private View photoSource;
    private PhotoGlassDrawable glass;
    private AlertDialog explanation;

    public PhotoHistogramPanel(Context context, Listener listener) {
        super(context); this.listener=listener;
        setOrientation(VERTICAL);
        setPadding(dp(8),0,dp(8),dp(4));
        setBackground(fallbackMaterial());
        LinearLayout header=row();
        luminance=button("亮度",false,()->{rgbMode=false;refresh();});
        rgb=button("RGB",false,()->{rgbMode=true;refresh();});
        header.addView(luminance,new LayoutParams(dp(48),dp(48)));
        header.addView(rgb,new LayoutParams(dp(48),dp(48)));
        shadow=button("",true,()->{if(hasData()){shadowOn=!shadowOn;refresh();notifyWarnings();}});
        highlight=button("",true,()->{if(hasData()){highlightOn=!highlightOn;refresh();notifyWarnings();}});
        LayoutParams shadowSize=new LayoutParams(chipWidth(shadow,72,"暗部 100.0%"),dp(48));shadowSize.setMarginStart(dp(6));header.addView(shadow,shadowSize);
        LayoutParams highlightSize=new LayoutParams(chipWidth(highlight,76,"高光 100.0%"),dp(48));highlightSize.setMarginStart(dp(6));header.addView(highlight,highlightSize);
        info=new ImageButton(context);info.setImageDrawable(new AppIcon(AppIcon.Kind.INFO,TEXT,getResources().getDisplayMetrics().density));
        info.setPadding(dp(16),dp(16),dp(16),dp(16));
        info.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28f3b493),null,new InsetDrawable(shape(Color.WHITE,14),dp(10))));
        info.setContentDescription("直方图与警示说明");info.setOnClickListener(v->showExplanation());
        HorizontalScrollView headerScroll=new HorizontalScrollView(context);headerScroll.setHorizontalScrollBarEnabled(false);
        headerScroll.setContentDescription("直方图工具，窄屏可左右滑动");headerScroll.addView(header);
        addView(headerScroll,new LayoutParams(-1,dp(48)));
        FrameLayout plot=new FrameLayout(context);
        chart=new Chart(context);plot.addView(chart,new FrameLayout.LayoutParams(-1,-1));
        plot.addView(info,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.END|Gravity.BOTTOM));
        addView(plot,new LayoutParams(-1,dp(52)));
        refresh();
    }

    /** The photo is sampled locally beneath this floating overlay; never sample an ancestor. */
    public void setPhotoSource(View source) {
        if(photoSource==source&&glass!=null){glass.refresh();return;}
        closeGlass();photoSource=source;createGlass();
    }
    /** Call after a preview or viewport update; the bounded backdrop is refreshed on the next draw. */
    public void refreshGlass() { if(glass!=null)glass.refresh(); }
    private void createGlass() {
        if(photoSource==null){setBackground(fallbackMaterial());return;}
        glass=new PhotoGlassDrawable(photoSource,this,getResources().getDisplayMetrics().density,16);setBackground(glass);
    }
    private void closeGlass() { if(glass!=null){glass.close();glass=null;} }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(glass==null)createGlass();}
    @Override protected void onDetachedFromWindow(){if(explanation!=null){explanation.dismiss();explanation=null;}closeGlass();super.onDetachedFromWindow();}

    public void setData(PhotoHistogram value) {
        data=value!=null&&value.hasData()?value:null;updating=false;error="";refresh();
    }
    /** Keep the last valid plot while explicitly labelling it as the previous preview. */
    public void setUpdating() { updating=true;error="";refresh(); }
    public void setError(String message) { updating=false;error=message==null||message.trim().isEmpty()?"预览统计暂不可用，请调整后重试。":message;refresh(); }
    /** Call on photograph/account rebind or a new editor session; no callback or draft mutation. */
    public void reset() { data=null;rgbMode=false;shadowOn=false;highlightOn=false;updating=false;error="";if(explanation!=null){explanation.dismiss();explanation=null;}refresh(); }
    /** Silent host synchronisation. */
    public void setWarnings(boolean shadows,boolean highlights) { shadowOn=shadows;highlightOn=highlights;refresh(); }
    public boolean isShadowWarning() { return shadowOn; }
    public boolean isHighlightWarning() { return highlightOn; }
    private void showExplanation() {
        if(explanation!=null&&explanation.isShowing())return;
        String message=(error.isEmpty()?"":error+"\n\n")+"基于当前 sRGB 预览，统计整幅构图内容，不含外加边框。\n\n高光：任一 RGB 通道为 255。暗部：三通道均为 0。RGB 三通道共用纵轴。\n\n暖色右斜纹表示高光，蓝色左斜纹表示暗部，仅辅助判断，不代表原片细节一定不可恢复；并非 RAW 测量。警示不会写入导出照片。";
        explanation=new AlertDialog.Builder(getContext()).setTitle("直方图与警示").setMessage(message).setPositiveButton("知道了",null).create();
        explanation.setOnDismissListener(dialog->explanation=null);explanation.show();
    }
    private boolean hasData() { return data!=null&&data.hasData(); }
    private void notifyWarnings() { if(listener!=null)listener.onWarningsChanged(shadowOn,highlightOn); }

    private void refresh() {
        boolean valid=hasData(),old=updating||!error.isEmpty();
        select(luminance,!rgbMode);select(rgb,rgbMode);
        status=updating?(valid?"sRGB · 更新中 · 上次预览":"sRGB · 更新中"):
                !error.isEmpty()?(valid?"统计失败 · 上次预览":"统计暂不可用 · 点 ⓘ 查看"):(valid?"sRGB · 预览":"sRGB · 暂无预览");
        shadow.setEnabled(valid);highlight.setEnabled(valid);
        shadow.setAlpha(valid?1:.55f);highlight.setAlpha(valid?1:.55f);
        String sp=valid?data.shadowPercent():"—",hp=valid?data.highlightPercent():"—";
        shadow.setText("暗部 "+sp);
        highlight.setText("高光 "+hp);
        select(shadow,shadowOn);select(highlight,highlightOn);
        shadow.setContentDescription("暗部警示，"+(shadowOn?"已开启":"已关闭")+"，"+(old&&valid?"上次预览 ":"")+sp+"，蓝色左斜纹");
        highlight.setContentDescription("高光警示，"+(highlightOn?"已开启":"已关闭")+"，"+(old&&valid?"上次预览 ":"")+hp+"，暖色右斜纹，任一通道触顶");
        String summary=valid?(rgbMode?"RGB 三通道，共享纵轴":"显示亮度，0 至 255")+"，有效预览像素 "+data.pixelCount()+"，暗部 "+sp+"，高光 "+hp:"暂无有效预览统计";
        chart.setContentDescription(status+"，"+summary+(old&&valid?"，上一份有效预览":"")+(error.isEmpty()?"":"，"+error));
        info.setContentDescription("直方图与警示说明"+(error.isEmpty()?"":"，"+error));chart.invalidate();
    }

    /** Use only unframed, unmasked sRGB working content, on a host worker thread. */
    public static PhotoHistogram measure(Bitmap content) {
        requireBitmap(content);
        PhotoHistogram.Builder builder=new PhotoHistogram.Builder();
        int width=content.getWidth();int[] row=new int[width];
        for(int y=0;y<content.getHeight();y++) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            content.getPixels(row,0,width,0,y,width,1);builder.addArgb(row,0,width);
        }
        return builder.build();
    }

    /** Returns a distinct ARGB_8888 copy even when disabled. Apply an external frame only afterwards.
     * The source, histogram data and export pixels must never be replaced by this diagnostic copy. */
    public static Bitmap warningCopy(Bitmap content,boolean shadows,boolean highlights) {
        requireBitmap(content);
        Bitmap copy=content.copy(Bitmap.Config.ARGB_8888,true);
        if(copy==null)throw new IllegalStateException("预览警示暂无法载入。");
        if(!shadows&&!highlights)return copy;
        try {
            int width=content.getWidth();int[] row=new int[width];
            int stripe=Math.max(3,Math.min(10,Math.min(width,content.getHeight())/70));
            for(int y=0;y<content.getHeight();y++) {
                if(Thread.currentThread().isInterrupted())throw new CancellationException();
                content.getPixels(row,0,width,0,y,width,1);
                for(int x=0;x<width;x++) {
                    int argb=row[x];boolean high=highlights&&PhotoHistogram.isHighlight(argb);
                    boolean low=shadows&&PhotoHistogram.isShadow(argb);
                    if(!high&&!low)continue;
                    // Opposing stripe directions remain distinguishable without colour vision.
                    boolean line=Math.floorMod(high?x-y:x+y,stripe*2)<stripe;
                    int tint=high?0xfff3a676:0xff80b9f2,alpha=line?144:46;
                    row[x]=blend(argb,tint,alpha);
                }
                copy.setPixels(row,0,width,0,y,width,1);
            }
            return copy;
        }catch(RuntimeException|Error failure){copy.recycle();throw failure;}
    }
    private static void requireBitmap(Bitmap bitmap) {
        if(bitmap==null||bitmap.isRecycled()||bitmap.getWidth()<1||bitmap.getHeight()<1)
            throw new IllegalArgumentException("预览内容不可用。");
    }
    private static int blend(int source,int tint,int alpha) {
        int inverse=255-alpha;
        int r=(((source>>>16)&255)*inverse+((tint>>>16)&255)*alpha+127)/255;
        int g=(((source>>>8)&255)*inverse+((tint>>>8)&255)*alpha+127)/255;
        int b=((source&255)*inverse+(tint&255)*alpha+127)/255;
        return (source&0xff000000)|(r<<16)|(g<<8)|b;
    }

    private final class Chart extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final Path path=new Path();
        Chart(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // The info target occupies its own right gutter, outside every measured curve bin.
            float left=dp(5),right=getWidth()-dp(48),top=dp(14),bottom=getHeight()-dp(5);
            if(right<=left||bottom<=top)return;
            paint.setStyle(Paint.Style.FILL);paint.setColor(TEXT);paint.setTextSize(9*getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText(status,left,dp(9),paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1,dp(.5f)));paint.setColor(0x42ffffff);
            canvas.drawLine(left,bottom,right,bottom,paint);
            paint.setColor(0x1effffff);
            canvas.drawLine((left+right)/2,top,(left+right)/2,bottom,paint);
            if(!hasData()) {
                paint.setStyle(Paint.Style.FILL);paint.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);paint.setColor(TEXT);paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(updating?"正在统计预览":"暂无统计数据",(left+right)/2,(top+bottom)/2-(paint.ascent()+paint.descent())/2,paint);
                paint.setTextAlign(Paint.Align.LEFT);return;
            }
            if(rgbMode) {
                long maximum=data.sharedRgbMaximum();
                drawChannel(canvas,PhotoHistogram.Channel.RED,0xffeea399,maximum,left,right,top,bottom,false);
                drawChannel(canvas,PhotoHistogram.Channel.GREEN,0xffa6cca9,maximum,left,right,top,bottom,false);
                drawChannel(canvas,PhotoHistogram.Channel.BLUE,0xff93b6e4,maximum,left,right,top,bottom,false);
            } else drawChannel(canvas,PhotoHistogram.Channel.LUMINANCE,0xffc9c3ba,data.max(PhotoHistogram.Channel.LUMINANCE),left,right,top,bottom,true);
            if(data.shadowCount()>0)endpoint(canvas,left,top,0xff93b6e4);
            if(data.highlightCount()>0)endpoint(canvas,right,top,ACCENT);
        }
        private void drawChannel(Canvas canvas,PhotoHistogram.Channel channel,int color,long max,float left,float right,float top,float bottom,boolean fill) {
            if(max<=0)return;
            path.reset();
            for(int bin=0;bin<256;bin++) {
                float x=left+(right-left)*bin/255f;
                // Fixed linear mapping, preserving all endpoint peaks and channel relative height.
                float y=bottom-(float)((bottom-top)*data.count(channel,bin)/(double)max);
                if(bin==0)path.moveTo(x,y);else path.lineTo(x,y);
            }
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(color);canvas.drawPath(path,paint);
            if(fill){path.lineTo(right,bottom);path.lineTo(left,bottom);path.close();paint.setStyle(Paint.Style.FILL);paint.setColor((color&0xffffff)|0x45000000);canvas.drawPath(path,paint);}
        }
        private void endpoint(Canvas canvas,float x,float top,int color){path.reset();path.moveTo(x,top+dp(6));path.lineTo(x-dp(3),top);path.lineTo(x+dp(3),top);path.close();paint.setColor(color);paint.setStyle(Paint.Style.FILL);canvas.drawPath(path,paint);}
    }
    private final class ToggleButton extends Button {
        private final boolean chip;
        ToggleButton(Context context,boolean chip){super(context);this.chip=chip;}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setCheckable(true);info.setChecked(isSelected());}
    }
    private LinearLayout row(){LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setBaselineAligned(false);return row;}
    private ToggleButton button(String name,boolean chip,Runnable action){ToggleButton button=new ToggleButton(getContext(),chip);button.setText(name);button.setAllCaps(false);button.setTextSize(chip?11:12);button.setIncludeFontPadding(false);button.setSingleLine(true);button.setGravity(Gravity.CENTER);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setPadding(0,0,0,0);button.setStateListAnimator(null);button.setBackgroundTintList(null);button.setOnClickListener(v->action.run());return button;}
    private int chipWidth(ToggleButton button,int minimum,String longest){return Math.max(dp(minimum),(int)Math.ceil(button.getPaint().measureText(longest))+dp(12));}
    private void select(ToggleButton button,boolean selected){
        button.setSelected(selected);button.setTextColor(TEXT);button.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);
        GradientDrawable face=shape(button.chip?(selected?0x74533c31:0x0cffffff):Color.TRANSPARENT,9);
        if(button.chip)face.setStroke(Math.max(1,dp(1)),selected?0xb3f3b493:0x26ffffff);
        Drawable content;
        if(button.chip){
            content=new InsetDrawable(face,dp(1),dp(10),dp(1),dp(10));
        }else{
            // A real background layer is independent of TextView's text canvas and clipping.
            LayerDrawable mode=new LayerDrawable(new Drawable[]{face,shape(selected?ACCENT:Color.TRANSPARENT,1)});
            mode.setLayerSize(1,dp(14),dp(2));
            mode.setLayerGravity(1,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
            mode.setLayerInset(1,0,0,0,dp(12));
            content=mode;
        }
        InsetDrawable mask=new InsetDrawable(shape(Color.WHITE,9),dp(1),dp(10),dp(1),dp(10));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28f3b493),content,mask));button.setBackgroundTintList(null);
        // Drawable insets position the 28dp face, not the text. RippleDrawable reports those
        // insets as padding, so reset it after every background replacement to keep glyphs whole.
        button.setPadding(0,0,0,0);button.setGravity(Gravity.CENTER);button.setClipToOutline(false);button.invalidate();
    }
    private GradientDrawable fallbackMaterial(){GradientDrawable material=shape(0xc7191b1e,16);material.setStroke(Math.max(1,dp(.5f)),0x45ffffff);return material;}
    private GradientDrawable shape(int color,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}

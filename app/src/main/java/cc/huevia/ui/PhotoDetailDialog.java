package cc.huevia.ui;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.AlertDialog;
import android.view.ContextThemeWrapper;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.RectF;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import cc.huevia.album.PhotoDetailSource;
import java.util.Locale;
import java.util.ArrayList;

/** Frozen, full-screen detail surface; closing it never mutates drafts, history or exports. */
public final class PhotoDetailDialog extends Dialog implements AutoCloseable {
    private static final int BG=0xff151413,TEXT=0xfff6f1eb,MUTED=0xffbab1a7,ACCENT=0xfff3b493,FIELD=0xff292724;
    private final Activity activity;private final PhotoDetailSource source;private final boolean canCompare;
    private PhotoDetailView detail;private TextView status,metric;private Button current,before,fit,one,two,retry;
    private FrameLayout stage;private LinearLayout palette,feedback,comparison;private final ArrayList<PhotoGlassDrawable> glasses=new ArrayList<>();
    private boolean hasFailure,hasLoading;private AlertDialog information;
    private boolean released;
    private final Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
        public void onActivityCreated(Activity a,Bundle state){}public void onActivityStarted(Activity a){if(a==activity&&detail!=null&&!released)detail.resume();}
        public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}
        public void onActivityStopped(Activity a){if(a==activity&&detail!=null&&!released)detail.pause();}
        public void onActivitySaveInstanceState(Activity a,Bundle state){}
        public void onActivityDestroyed(Activity a){if(a==activity)close();}
    };
    public static PhotoDetailDialog show(Activity activity,PhotoDetailSource.Request request){
        PhotoDetailDialog dialog=new PhotoDetailDialog(activity,request);dialog.show();return dialog;
    }
    public PhotoDetailDialog(Activity activity,PhotoDetailSource.Request request){
        super(activity,android.R.style.Theme_Material_NoActionBar);this.activity=activity;source=new PhotoDetailSource(request);canCompare=request.canCompare;
        setOnDismissListener(dialog->release());setCancelable(true);
    }
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);configureWindow();
        FrameLayout page=new FrameLayout(activity);page.setBackgroundColor(BG);page.setTag("detail-page");
        // The page owns insets exactly once. The photo canvas remains physical-pixel sized.
        page.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(safe.left,safe.top,safe.right,safe.bottom);}
            else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        stage=new FrameLayout(activity);stage.setTag("detail-photo-stage");page.addView(stage,new FrameLayout.LayoutParams(-1,-1));
        detail=new PhotoDetailView(activity,source,new PhotoDetailView.Listener(){
            public void state(boolean loading,String message,double scale,boolean fitting,int mode,boolean failed){
                if(released||status==null)return;hasFailure=failed;hasLoading=loading;status.setText(message);status.setTextColor(failed?0xffffb4a7:TEXT);retry.setVisibility(failed?View.VISIBLE:View.GONE);feedback.setVisibility(loading||failed?View.VISIBLE:View.GONE);
                metric.setText(detail.contentWidth()>0?String.format(Locale.ROOT,"%d × %d px · %d%%",detail.contentWidth(),detail.contentHeight(),Math.round(scale*100)):"正在准备真实像素");
                select(current,mode==PhotoDetailSource.CURRENT);select(before,mode==PhotoDetailSource.BEFORE);
                select(fit,fitting);select(one,!fitting&&Math.abs(scale-1)<.005);select(two,!fitting&&Math.abs(scale-2)<.005);
                boolean available=detail.contentWidth()>0;fit.setEnabled(available);one.setEnabled(available);two.setEnabled(available);
                positionControls();refreshGlass();
            }
            public void invalidated(){if(!released){Toast.makeText(activity,"照片或编辑状态已变化，请重新打开细节。",Toast.LENGTH_SHORT).show();dismiss();}}
        });
        stage.addView(detail,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=row();header.setTag("detail-header");
        Button back=button("返回");back.setContentDescription("返回编辑照片");back.setOnClickListener(v->dismiss());glass(back,22);header.addView(back,new LinearLayout.LayoutParams(dp(56),dp(48)));
        TextView title=text(canCompare?"细节预览":"已保存版本",15,TEXT);title.setTypeface(null,Typeface.BOLD);title.setShadowLayer(dp(3),0,dp(1),0xdd000000);header.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        Button info=button("");info.setContentDescription("细节查看说明");AppIcon icon=new AppIcon(AppIcon.Kind.INFO,TEXT,activity.getResources().getDisplayMetrics().density);icon.setBounds(0,0,dp(18),dp(18));info.setCompoundDrawables(icon,null,null,null);info.setPadding(dp(15),0,dp(15),0);info.setOnClickListener(v->showInformation());glass(info,22);header.addView(info,new LinearLayout.LayoutParams(dp(48),dp(48)));
        Button done=button("完成");done.setTextColor(ACCENT);done.setOnClickListener(v->dismiss());glass(done,22);header.addView(done,new LinearLayout.LayoutParams(dp(56),dp(48)));
        FrameLayout.LayoutParams headerParams=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);headerParams.setMargins(dp(10),dp(4),dp(10),0);stage.addView(header,headerParams);
        LinearLayout modes=row();comparison=modes;modes.setTag("detail-compare-palette");modes.setPadding(dp(5),0,dp(5),0);glass(modes,24);
        current=button(canCompare?"当前效果":"已保存版本");before=button("调色前");modes.addView(current,new LinearLayout.LayoutParams(0,dp(48),1));modes.addView(before,new LinearLayout.LayoutParams(0,dp(48),1));
        FrameLayout.LayoutParams modesParams=new FrameLayout.LayoutParams(dp(216),dp(48),Gravity.TOP|Gravity.CENTER_HORIZONTAL);modesParams.topMargin=dp(60);stage.addView(modes,modesParams);
        palette=column();palette.setTag("detail-zoom-palette");palette.setPadding(dp(6),dp(2),dp(6),dp(5));glass(palette,18);
        LinearLayout zoom=row();fit=button("适应");one=button("100%");two=button("200%");zoom.addView(fit,new LinearLayout.LayoutParams(0,dp(48),1));zoom.addView(one,new LinearLayout.LayoutParams(0,dp(48),1));zoom.addView(two,new LinearLayout.LayoutParams(0,dp(48),1));palette.addView(zoom);
        FrameLayout.LayoutParams paletteParams=new FrameLayout.LayoutParams(dp(232),-2,Gravity.LEFT|Gravity.BOTTOM);paletteParams.setMargins(dp(12),0,0,dp(12));stage.addView(palette,paletteParams);
        metric=text("正在准备真实像素",11,TEXT);metric.setTag("detail-pixel-metric");metric.setSingleLine(true);metric.setEllipsize(android.text.TextUtils.TruncateAt.END);metric.setPadding(dp(10),dp(6),dp(10),dp(6));glass(metric,12);
        FrameLayout.LayoutParams metricParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);metricParams.setMargins(dp(12),dp(112),dp(12),0);stage.addView(metric,metricParams);
        metric.addOnLayoutChangeListener((v,l,t,r,b,oldL,oldT,oldR,oldB)->{if(r-l!=oldR-oldL||b-t!=oldB-oldT){positionControls();refreshGlass();}});
        feedback=column();feedback.setPadding(dp(12),dp(6),dp(12),dp(6));glass(feedback,14);
        status=text("正在载入当前效果 · 真实细节",12,TEXT);status.setMaxLines(3);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);feedback.addView(status);
        retry=button("重试载入");retry.setTextColor(ACCENT);retry.setVisibility(View.GONE);retry.setOnClickListener(v->detail.retry());feedback.addView(retry);
        FrameLayout.LayoutParams feedbackParams=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT);feedbackParams.setMargins(dp(12),0,dp(12),dp(98));stage.addView(feedback,feedbackParams);
        stage.addOnLayoutChangeListener((v,l,t,r,b,oldL,oldT,oldR,oldB)->{positionControls();refreshGlass();});setContentView(page);page.requestApplyInsets();
        current.setOnClickListener(v->detail.mode(PhotoDetailSource.CURRENT));before.setOnClickListener(v->detail.mode(PhotoDetailSource.BEFORE));
        if(!canCompare){before.setEnabled(false);before.setAlpha(.55f);before.setContentDescription("调色前不可用：此版本暂无法恢复调色前内容");}
        fit.setOnClickListener(v->detail.fit());one.setOnClickListener(v->detail.zoom(1));two.setOnClickListener(v->detail.zoom(2));
        fit.setEnabled(false);one.setEnabled(false);two.setEnabled(false);select(current,true);select(fit,true);
        activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
    }
    @Override protected void onStart(){super.onStart();configureWindow();Window window=getWindow();if(window!=null){window.setLayout(-1,-1);window.getDecorView().requestApplyInsets();}}
    private void configureWindow(){
        Window window=getWindow();if(window==null)return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS|WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.setBackgroundDrawable(new ColorDrawable(BG));window.getDecorView().setBackgroundColor(BG);
        window.setStatusBarColor(BG);window.setNavigationBarColor(BG);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if(Build.VERSION.SDK_INT>=29){window.setStatusBarContrastEnforced(false);window.setNavigationBarContrastEnforced(false);}
        if(Build.VERSION.SDK_INT>=30){
            window.setDecorFitsSystemWindows(false);WindowInsetsController controls=window.getInsetsController();
            if(controls!=null)controls.setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }else window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    private void select(Button button,boolean selected){if(button==null)return;button.setSelected(selected);button.setTextColor(selected?0xff352016:TEXT);button.setBackground(new InsetDrawable(ripple(selected?ACCENT:Color.TRANSPARENT),0,dp(8),0,dp(8)));}
    private Button button(String label){Button b=new Button(activity);b.setAllCaps(false);b.setText(label);b.setTextSize(12);b.setTextColor(TEXT);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(8),0,dp(8),0);b.setStateListAnimator(null);b.setBackground(ripple(Color.TRANSPARENT));return b;}
    private TextView text(String value,float size,int color){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER);return t;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private GradientDrawable shape(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private RippleDrawable ripple(int color){return new RippleDrawable(ColorStateList.valueOf(0x25ffffff),shape(color,dp(16)),shape(Color.WHITE,dp(16)));}
    private void glass(View owner,float radius){PhotoGlassDrawable glass=new PhotoGlassDrawable(detail,owner,activity.getResources().getDisplayMetrics().density,radius);owner.setBackground(glass);glasses.add(glass);}
    private void refreshGlass(){for(PhotoGlassDrawable glass:glasses)glass.refresh();}
    private void positionControls(){
        if(detail==null||palette==null||stage.getWidth()<1)return;RectF photo=detail.visiblePhotoBounds();if(photo.isEmpty())photo.set(0,0,stage.getWidth(),stage.getHeight());
        FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)palette.getLayoutParams();int width=Math.min(dp(232),Math.max(dp(176),stage.getWidth()-dp(100))),bottom=Math.max(dp(12),Math.round(stage.getHeight()-photo.bottom)+dp(12)),left=Math.min(stage.getWidth()-width-dp(12),Math.max(dp(12),Math.round(photo.left)+dp(12)));
        if(params.width!=width||params.bottomMargin!=bottom||params.leftMargin!=left){params.width=width;params.bottomMargin=bottom;params.leftMargin=left;palette.setLayoutParams(params);}
        int metricWidth=Math.min(stage.getWidth()-dp(24),Math.round(metric.getPaint().measureText(metric.getText().toString()))+metric.getPaddingLeft()+metric.getPaddingRight());
        int right=Math.max(dp(12),Math.round(stage.getWidth()-photo.right)+dp(12));
        int comparisonRight=(stage.getWidth()+dp(216))/2;
        boolean besideComparison=stage.getWidth()-right-metricWidth>=comparisonRight+dp(8);
        int top=Math.max(Math.round(photo.top)+dp(12),dp(besideComparison?60:112));
        FrameLayout.LayoutParams metricParams=(FrameLayout.LayoutParams)metric.getLayoutParams();
        if(metricParams.topMargin!=top||metricParams.rightMargin!=right){metricParams.topMargin=top;metricParams.rightMargin=right;metric.setMaxWidth(stage.getWidth()-dp(24));metric.setLayoutParams(metricParams);}
        FrameLayout.LayoutParams message=(FrameLayout.LayoutParams)feedback.getLayoutParams();int messageBottom=bottom+palette.getMeasuredHeight()+dp(10);if(message.bottomMargin!=messageBottom){message.bottomMargin=messageBottom;message.width=Math.min(dp(306),stage.getWidth()-dp(24));feedback.setLayoutParams(message);}
        detail.setFloatingControls(new RectF(left,stage.getHeight()-bottom-palette.getMeasuredHeight(),left+width,stage.getHeight()-bottom));
    }
    private void showInformation(){
        if(information!=null)information.dismiss();String explanation=canCompare?"当前效果与调色前保持同一构图和查看位置，不含外加边框。\n\n100%：1个图像像素对应1个屏幕物理像素。200%只放大查看，不增加细节。双击照片可切换适应与100%，双指缩放，拖动照片或定位图移动。\n\n细节预览不代表导出压缩效果。":"此版本暂无法恢复调色前内容。查看完整已保存JPEG的文件像素，包括其中已烘焙的边框。\n\n100%：1个图像像素对应1个屏幕物理像素。200%只放大查看，不增加细节。";
        information=new AlertDialog.Builder(new ContextThemeWrapper(activity,cc.huevia.R.style.AppThemeDark)).setTitle("细节查看说明").setMessage(explanation).setPositiveButton("知道了",null).create();information.show();
    }
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private void release(){if(released)return;released=true;activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);if(information!=null)information.dismiss();if(detail!=null)detail.close();for(PhotoGlassDrawable glass:glasses)glass.close();glasses.clear();source.close();}
    @Override public void dismiss(){super.dismiss();release();}
    @Override public void close(){dismiss();}
}

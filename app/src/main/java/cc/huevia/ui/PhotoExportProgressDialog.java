package cc.huevia.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import cc.huevia.album.PhotoPresetBatch;
import cc.huevia.album.PhotoPresetBatchState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Frozen-task progress. Rows come from the batch ledger, never from assumed source order of successes. */
public final class PhotoExportProgressDialog extends Dialog {
    private static final int BG=0xff141413,FIELD=0xff292724,TEXT=0xfff4f5f7,MUTED=0xffbab1a7,ACCENT=0xfff3b493,OUTLINE=0xff4c4640,SUCCESS=0xff9ac8ae,ERROR=0xffffb4a7;
    private final Activity activity;
    private final PhotoPresetBatch batch;
    private final List<PhotoExportDialog.Item> previews;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Row> rows=new ArrayList<>();
    private final List<Bitmap> thumbnailCopies=new ArrayList<>();
    private final Runnable observer=()->main.post(this::refresh);
    private TextView title,summary,stage,note;
    private LinearLayout list;
    private ProgressBar progress;
    private Button stop;
    private boolean observing;

    public static PhotoExportProgressDialog show(Activity activity,PhotoPresetBatch batch) { return show(activity,batch,Collections.emptyList()); }
    /** Optional settings previews are copied at thumbnail size; their source bitmaps are never retained or recycled. */
    public static PhotoExportProgressDialog show(Activity activity,PhotoPresetBatch batch,List<PhotoExportDialog.Item> previews) {
        if(activity.isFinishing()||activity.isDestroyed())return null;
        PhotoExportProgressDialog dialog=new PhotoExportProgressDialog(activity,batch,previews);dialog.show();return dialog;
    }
    private PhotoExportProgressDialog(Activity activity,PhotoPresetBatch batch,List<PhotoExportDialog.Item> previews) {
        super(new ContextThemeWrapper(activity,cc.huevia.R.style.AppThemeDark));
        if(batch==null)throw new IllegalArgumentException("batch");
        this.activity=activity;this.batch=batch;this.previews=previews==null?Collections.emptyList():new ArrayList<>(previews);
        requestWindowFeature(Window.FEATURE_NO_TITLE);setCanceledOnTouchOutside(false);setContentView(content());
        configureWindow();
    }
    @Override protected void onStart() {
        super.onStart();configureWindow();Window window=getWindow();
        if(window!=null){window.setLayout(-1,-1);window.getDecorView().requestApplyInsets();window.getDecorView().post(()->{if(isShowing())configureWindow();});}
    }
    private void configureWindow() {
        Window window=getWindow();if(window==null)return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS|WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.setBackgroundDrawable(new ColorDrawable(BG));window.getDecorView().setBackgroundColor(BG);
        window.setStatusBarColor(BG);window.setNavigationBarColor(BG);
        if(Build.VERSION.SDK_INT>=29){window.setStatusBarContrastEnforced(false);window.setNavigationBarContrastEnforced(false);}
        if(Build.VERSION.SDK_INT>=30){window.setDecorFitsSystemWindows(false);WindowInsetsController controls=window.getInsetsController();if(controls!=null)controls.setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}
        else {int flags=window.getDecorView().getSystemUiVisibility();window.getDecorView().setSystemUiVisibility((flags|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)&~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));}
    }
    @Override public void show() {
        super.show();Window window=getWindow();if(window!=null)window.setLayout(-1,-1);
        if(!observing){observing=true;batch.addObserver(observer);}refresh();
    }
    @Override public void dismiss() {
        if(observing){observing=false;batch.removeObserver(observer);}main.removeCallbacksAndMessages(null);super.dismiss();
        list.removeAllViews();rows.clear();
        for(Bitmap bitmap:thumbnailCopies)if(!bitmap.isRecycled())bitmap.recycle();thumbnailCopies.clear();
    }
    private View content() {
        LinearLayout root=column();root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(safe.left,safe.top,safe.right,safe.bottom);}else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout header=row();header.setPadding(dp(12),0,dp(12),0);
        Button back=button("",this::dismiss);back.setContentDescription("收起进度，已接受任务继续处理");back.setCompoundDrawablesRelativeWithIntrinsicBounds(new AppIcon(AppIcon.Kind.BACK,TEXT,density()),null,null,null);back.setPadding(dp(12),0,dp(12),0);header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(56)));
        TextView heading=text("导出进度",18,TEXT);heading.setTypeface(null,Typeface.BOLD);heading.setGravity(Gravity.CENTER);header.addView(heading,new LinearLayout.LayoutParams(0,dp(56),1));header.addView(new View(activity),new LinearLayout.LayoutParams(dp(48),1));root.addView(header);
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        LinearLayout body=column();body.setPadding(dp(24),dp(24),dp(24),dp(24));
        title=text("正在准备导出",24,TEXT);title.setTypeface(null,Typeface.BOLD);body.addView(title);
        note=text("按确认时的设置逐张处理",13,MUTED);add(body,note,10);
        progress=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(false);progress.setProgressTintList(ColorStateList.valueOf(ACCENT));progress.setProgressBackgroundTintList(ColorStateList.valueOf(OUTLINE));progress.setTag("export-task-progress");LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(-1,dp(6));bar.topMargin=dp(26);body.addView(progress,bar);
        summary=text("",13,MUTED);summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);add(body,summary,10);
        stage=text("",14,ACCENT);stage.setTag("export-task-stage");add(body,stage,16);
        list=column();list.setTag("export-task-items");add(body,list,18);
        TextView retained=text("已完成的照片会保留。返回编辑或收起此页，不会改变已接受任务的设置。",12,MUTED);retained.setLineSpacing(dp(3),1);add(body,retained,20);
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=column();footer.setPadding(dp(24),dp(10),dp(24),dp(18));
        stop=button("停止后续",()->{PhotoPresetBatchState state=batch.state();if(state.active){batch.cancel();refresh();}else dismiss();});stop.setTextColor(ACCENT);stop.setTag("export-stop-after-current");stop.setBackground(shape(BG,OUTLINE,28));footer.addView(stop,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView safe=text("已完成的照片会保留",12,MUTED);safe.setGravity(Gravity.CENTER);add(footer,safe,12);root.addView(footer);return root;
    }
    private void refresh() {
        if(!observing||!isShowing())return;
        if(activity.isFinishing()||activity.isDestroyed()){dismiss();return;}
        PhotoPresetBatchState state=batch.state();
        title.setText(state.active?(state.cancelRequested?"正在停止后续处理":"正在导出 "+state.total+" 张照片"):state.cancelled>0?"导出已停止":state.failed>0?"部分照片待重试":"导出完成");
        progress.setMax(Math.max(1,state.total));progress.setProgress(state.completed);
        progress.setContentDescription("已完成 "+state.completed+" / "+state.total+" 张，失败和停止的照片不计入完成进度");
        summary.setText("已完成 "+state.completed+" / "+state.total+" 张"+(state.failed>0?" · 失败 "+state.failed:"")+(state.cancelled>0?" · 未完成 "+state.cancelled:""));
        stage.setText(state.active?(state.cancelRequested?"正在取消，等待当前步骤结束":state.currentStage+(state.currentFilename.isEmpty()?"":" · "+state.currentFilename)):state.currentStage);
        note.setText("按确认时的设置逐张处理 · 已接受任务保留各自的尺寸、质量和保存位置");
        stop.setEnabled(!state.active||!state.cancelRequested);stop.setAlpha(stop.isEnabled()?1:.65f);stop.setText(!state.active?"关闭":state.cancelRequested?"正在取消…":"停止后续");
        if(rows.size()!=state.items.size()){list.removeAllViews();rows.clear();for(PhotoPresetBatchState.Item item:state.items)rows.add(new Row(item.index));if(!previews.isEmpty())previews.clear();}
        for(int i=0;i<state.items.size();i++)rows.get(i).bind(state.items.get(i),state);
    }
    private final class Row {
        final TextView filename,detail,status;
        final LinearLayout root;
        Row(int index) {
            root=column();LinearLayout mainRow=row();mainRow.setGravity(Gravity.CENTER_VERTICAL);mainRow.setPadding(0,dp(16),0,dp(16));
            Bitmap thumb=index<previews.size()?copyThumbnail(previews.get(index)):null;
            if(thumb!=null){ImageView image=new ImageView(activity);image.setImageBitmap(thumb);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(shape(FIELD,0,6));image.setClipToOutline(true);mainRow.addView(image,new LinearLayout.LayoutParams(dp(48),dp(48)));}
            else {TextView number=text(String.format(java.util.Locale.ROOT,"%02d",index+1),13,MUTED);number.setGravity(Gravity.CENTER);mainRow.addView(number,new LinearLayout.LayoutParams(dp(32),dp(44)));}
            LinearLayout labels=column();labels.setPadding(dp(12),0,dp(12),0);filename=text("",14,TEXT);filename.setTypeface(null,Typeface.BOLD);filename.setMaxLines(2);filename.setEllipsize(TextUtils.TruncateAt.END);labels.addView(filename);detail=text("",12,MUTED);detail.setLineSpacing(dp(2),1);add(labels,detail,5);mainRow.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            status=text("",12,MUTED);status.setGravity(Gravity.END);status.setMinWidth(dp(56));mainRow.addView(status,new LinearLayout.LayoutParams(-2,-2));root.addView(mainRow);View line=new View(activity);line.setBackgroundColor(FIELD);root.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));list.addView(root);
        }
        void bind(PhotoPresetBatchState.Item item,PhotoPresetBatchState state) {
            filename.setText(item.filename);String label;
            switch(item.status){case "completed":label="已完成";break;case "reused":label="已复用";break;case "processing":label="处理中";break;case "failed":label="未完成";break;case "cancelled":label="已停止";break;default:label="等待";}
            status.setText(label);status.setTextColor(item.status.equals("completed")||item.status.equals("reused")?SUCCESS:item.status.equals("failed")?ERROR:item.status.equals("processing")?ACCENT:MUTED);
            String description=!item.error.isEmpty()?item.error:item.status.equals("processing")?(state.cancelRequested?"等待当前步骤安全结束":state.currentStage):item.status.equals("waiting")?"等待处理":item.width>0&&item.height>0?item.width+" × "+item.height+" px · JPEG "+item.quality:label;
            detail.setText(description);root.setContentDescription(item.filename+"，"+label+"，"+description);
        }
    }
    private Bitmap copyThumbnail(PhotoExportDialog.Item item) {
        if(item==null||item.thumbnail==null||item.thumbnail.isRecycled())return null;
        try {Bitmap source=item.thumbnail;float scale=dp(48)/(float)Math.max(source.getWidth(),source.getHeight());int width=Math.max(1,Math.round(source.getWidth()*scale)),height=Math.max(1,Math.round(source.getHeight()*scale));Bitmap scaled=Bitmap.createScaledBitmap(source,width,height,true);if(scaled==source)scaled=source.copy(Bitmap.Config.ARGB_8888,false);if(scaled!=null){thumbnailCopies.add(scaled);return scaled;}}
        catch(RuntimeException|OutOfMemoryError unavailable){/* A missing summary image cannot stop a task. */}return null;
    }
    private LinearLayout column(){LinearLayout layout=new LinearLayout(activity);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private LinearLayout row(){LinearLayout layout=new LinearLayout(activity);layout.setOrientation(LinearLayout.HORIZONTAL);return layout;}
    private TextView text(String value,float size,int color){TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setIncludeFontPadding(false);return view;}
    private Button button(String value,Runnable action){Button button=new Button(activity);button.setText(value);button.setTextSize(15);button.setAllCaps(false);button.setTextColor(ACCENT);button.setMinHeight(dp(48));button.setMinimumHeight(dp(48));button.setBackgroundColor(android.graphics.Color.TRANSPARENT);button.setOnClickListener(v->action.run());return button;}
    private void add(LinearLayout parent,View child,int top){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(top);parent.addView(child,params);}
    private GradientDrawable shape(int fill,int stroke,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(fill);drawable.setCornerRadius(dp(radius));if(stroke!=0)drawable.setStroke(dp(1),stroke);return drawable;}
    private int dp(float value){return Math.round(value*density());}
    private float density(){return activity.getResources().getDisplayMetrics().density;}
}

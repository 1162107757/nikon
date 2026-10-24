package cc.huevia.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import cc.huevia.album.PhotoExportSpec;
import cc.huevia.album.PhotoSaveTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Full-screen export settings. Receives dimensions already resolved on a worker; never opens files.
 * Accepted settings feed the existing receipt-checked export/save flow without duplicating it. */
public final class PhotoExportDialog extends Dialog {
    public interface Listener { void chosen(PhotoExportSpec spec,PhotoSaveTarget target,boolean newCopy); }
    public static final class Item {
        public final String filename, description;
        /** Natural dimensions of the final full composition, including its external frame. */
        public final int fullWidth, fullHeight;
        /** Optional caller-owned preview, used only as a visual summary and never as export data. */
        public final Bitmap thumbnail;
        public Item(String filename,int fullWidth,int fullHeight) { this(filename,fullWidth,fullHeight,null); }
        public Item(String filename,int fullWidth,int fullHeight,Bitmap thumbnail) { this(filename,fullWidth,fullHeight,thumbnail,"当前效果 · 原图保持不变"); }
        public Item(String filename,int fullWidth,int fullHeight,Bitmap thumbnail,String description) {
            this.filename=filename==null||filename.trim().isEmpty()?"照片":filename;
            this.fullWidth=fullWidth;this.fullHeight=fullHeight;this.thumbnail=thumbnail;
            this.description=description==null?"":description;
        }
        public boolean dimensionsAvailable() { return fullWidth>0&&fullHeight>0&&fullWidth<=65535&&fullHeight<=65535; }
    }
    private static final int BG=0xff141413,FIELD=0xff292724,TEXT=0xfff4f5f7,MUTED=0xffbab1a7,ACCENT=0xfff3b493,ERROR=0xffffb4a7;
    private final Activity activity;
    private final List<Item> items;
    private final Listener listener;
    private final Runnable original,raw;
    private LinearLayout custom,dimensionsList;
    private TextView dimensionsTitle,dimensions,hint,qualityFixed,validation;
    private EditText edgeInput,qualityInput;
    private SeekBar edgeSlider,qualitySlider;
    private Button next;
    private Button destinationButton,copyButton;
    private TextView destinationDetail,availabilityText;
    private LinearLayout copyChoices;
    private CheckBox extraCopy;
    private PhotoSaveTarget destination;
    private AlertDialog destinationDialog;
    private final ChoiceButton[] modes=new ChoiceButton[3];
    private PhotoExportSpec.Mode mode=PhotoExportSpec.Mode.FULL;
    private boolean synchronising,customEntered,submitted;
    private final Handler main=new Handler(Looper.getMainLooper());
    private PhotoExportAvailability.Provider availabilityProvider;
    private PhotoExportAvailability.ViewSaved viewSaved;
    private PhotoExportAvailability.Result availability;
    private String availabilityKey="";
    private int availabilityGeneration;
    private boolean availabilityChecking,availabilityFromTimeout;
    private Runnable availabilityTimeout;
    private final int customMaximum;

    public static PhotoExportDialog show(Activity activity,List<Item> items,Listener listener,Runnable original,Runnable raw) {
        if(activity.isFinishing()||activity.isDestroyed())return null;
        PhotoExportDialog dialog=new PhotoExportDialog(activity,items,listener,original,raw);dialog.show();return dialog;
    }
    private PhotoExportDialog(Activity activity,List<Item> input,Listener listener,Runnable original,Runnable raw) {
        super(new ContextThemeWrapper(activity,cc.huevia.R.style.AppThemeDark));
        this.activity=activity;this.listener=listener;this.original=original;this.raw=raw;
        destination=PhotoSaveChoice.preferred(activity,"edited",PhotoSaveTarget.PHONE_ALBUM);
        List<Item> copy=new ArrayList<>();if(input!=null)for(Item item:input)if(item!=null)copy.add(item);
        this.items=Collections.unmodifiableList(copy);
        int max=0;for(Item item:items)if(item.dimensionsAvailable())max=Math.max(max,Math.max(item.fullWidth,item.fullHeight));
        customMaximum=Math.min(PhotoExportSpec.MAX_CUSTOM_EDGE,max);
        requestWindowFeature(Window.FEATURE_NO_TITLE);setContentView(buildContent());
        Window window=getWindow();
        if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);window.setStatusBarColor(BG);window.setNavigationBarColor(BG);if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(false);else window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);}
    }
    @Override public void show(){super.show();Window window=getWindow();if(window!=null)window.setLayout(-1,-1);refreshAvailability();}
    @Override public void dismiss(){invalidateAvailability();if(destinationDialog!=null){AlertDialog old=destinationDialog;destinationDialog=null;old.dismiss();}super.dismiss();}

    /** Attach after show(). The provider must freeze its source/edit identities before worker lookup. */
    public void setAvailabilityProvider(PhotoExportAvailability.Provider provider,PhotoExportAvailability.ViewSaved viewSaved){
        invalidateAvailability();availabilityProvider=provider;this.viewSaved=viewSaved;refresh();
    }

    private View buildContent() {
        LinearLayout root=column();root.setBackgroundColor(BG);
        // Handle dialog insets itself, so fixed actions stay clear of gesture navigation and cutouts.
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(safe.left,safe.top,safe.right,safe.bottom);}
            else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header=row();header.setPadding(dp(12),0,dp(12),0);
        Button back=button("",this::dismiss);back.setContentDescription("返回编辑，取消导出设置");back.setCompoundDrawablesRelativeWithIntrinsicBounds(new AppIcon(AppIcon.Kind.BACK,TEXT,density()),null,null,null);back.setPadding(dp(12),0,dp(12),0);
        header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(56)));
        TextView title=text("导出照片",18,TEXT);title.setTypeface(null,Typeface.BOLD);title.setGravity(Gravity.CENTER);header.addView(title,new LinearLayout.LayoutParams(0,dp(56),1));
        header.addView(button("取消",this::dismiss),new LinearLayout.LayoutParams(dp(56),dp(56)));root.addView(header);

        ScrollView scroll=new ScrollView(getContext());scroll.setFillViewport(true);scroll.setClipToPadding(false);
        LinearLayout content=column();content.setTag("export-scroll-content");content.setPadding(dp(20),dp(12),dp(20),dp(24));scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        summary(content);
        LinearLayout tabs=row();tabs.setPadding(dp(4),dp(4),dp(4),dp(4));tabs.setBackground(shape(FIELD,12));
        String[] names={"完整尺寸","分享","自定义"};for(int i=0;i<3;i++){final PhotoExportSpec.Mode choice=PhotoExportSpec.Mode.values()[i];ChoiceButton control=new ChoiceButton(getContext());styleButton(control,names[i],()->choose(choice));modes[i]=control;tabs.addView(control,new LinearLayout.LayoutParams(0,-2,1));}
        margin(content,tabs,20,0);
        custom=column();custom.setPadding(0,dp(20),0,0);
        custom.addView(text("最终成品长边",13,MUTED));
        edgeInput=input("长边像素，640–"+customMaximum+" px","export-custom-edge");edgeInput.setContentDescription("最终含框成品长边，像素");edgeInput.setHint("640–"+customMaximum+" px");
        margin(custom,edgeInput,8,0);
        edgeSlider=slider(Math.max(0,customMaximum-PhotoExportSpec.MIN_CUSTOM_EDGE));edgeSlider.setContentDescription("最终成品长边滑块，像素");custom.addView(edgeSlider,new LinearLayout.LayoutParams(-1,dp(48)));
        custom.addView(text("640–"+Math.max(640,customMaximum)+" px · 保持比例，不放大",12,MUTED));
        margin(content,custom,0,0);

        dimensionsTitle=text("最终导出尺寸 · 包含边框",12,MUTED);margin(content,dimensionsTitle,24,0);
        dimensions=text("",28,TEXT);dimensions.setTypeface(null,Typeface.BOLD);dimensions.setTag("export-dimensions");margin(content,dimensions,8,0);
        hint=text("",12,MUTED);margin(content,hint,8,0);
        dimensionsList=column();margin(content,dimensionsList,8,0);
        divider(content,20);
        valueRow(content,"格式","JPEG · sRGB");
        qualityFixed=valueRow(content,"JPEG 质量","95");
        LinearLayout qualityCustom=column();qualityCustom.setTag("export-quality-custom");
        qualityInput=input("JPEG 质量，70–100","export-custom-quality");qualityInput.setContentDescription("JPEG 质量，70 至 100");qualityInput.setHint("70–100");
        LinearLayout qr=row();qr.addView(text("JPEG 质量",14,MUTED),new LinearLayout.LayoutParams(0,dp(48),1));qr.addView(qualityInput,new LinearLayout.LayoutParams(dp(96),-2));qualityCustom.addView(qr);
        qualitySlider=slider(30);qualitySlider.setContentDescription("JPEG 质量滑块，70 至 100");qualityCustom.addView(qualitySlider,new LinearLayout.LayoutParams(-1,dp(48)));
        content.addView(qualityCustom);
        divider(content,12);
        destinationButton=button("",this::chooseDestination);destinationButton.setTag("export-destination");destinationButton.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);destinationButton.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,new AppIcon(AppIcon.Kind.CHEVRON,MUTED,density()),null);content.addView(destinationButton,new LinearLayout.LayoutParams(-1,-2));
        destinationDetail=text("",12,MUTED);margin(content,destinationDetail,4,0);
        copyButton=button("",()->copyChoices.setVisibility(copyChoices.getVisibility()==View.GONE?View.VISIBLE:View.GONE));copyButton.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);copyButton.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,new AppIcon(AppIcon.Kind.CHEVRON,MUTED,density()),null);content.addView(copyButton,new LinearLayout.LayoutParams(-1,-2));
        copyChoices=column();extraCopy=new CheckBox(getContext());extraCopy.setTag("save-extra-copy");extraCopy.setText("另存新副本");extraCopy.setTextColor(TEXT);extraCopy.setTextSize(14);extraCopy.setMinHeight(dp(48));extraCopy.setButtonTintList(ColorStateList.valueOf(ACCENT));copyChoices.addView(extraCopy,new LinearLayout.LayoutParams(-1,-2));
        margin(copyChoices,text("同一成品和规格已保存且仍有效时默认复用。选中“另存新副本”会新增一张照片，并按现有规则使用本次保存时间。",12,MUTED),4,0);copyChoices.setVisibility(View.GONE);content.addView(copyChoices,new LinearLayout.LayoutParams(-1,-2));extraCopy.setOnCheckedChangeListener((button,checked)->refreshDestination());refreshDestination();
        divider(content,20);
        margin(content,text("编码成品：保留拍摄信息 · 不含定位",12,MUTED),0,0);
        margin(content,text("原始 JPEG／NEF 另行保存，不附加手机编辑效果，不改写原文件及其元数据。",12,MUTED),8,0);
        margin(content,text("预估体积",12,MUTED),24,0);
        margin(content,text("导出后显示实际大小",20,TEXT),8,0);
        margin(content,text("尺寸、JPEG 质量和画面内容都会影响体积。原图和编辑记录保留。",12,MUTED),8,0);
        validation=text("",13,ERROR);validation.setTag("export-validation");validation.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);margin(content,validation,16,0);
        availabilityText=text("",12,MUTED);availabilityText.setTag("export-availability");availabilityText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);availabilityText.setVisibility(View.GONE);margin(content,availabilityText,12,0);
        LinearLayout footer=column();footer.setTag("export-fixed-footer");footer.setPadding(dp(20),dp(8),dp(20),dp(8));footer.setBackgroundColor(BG);
        next=button(exportActionLabel(),()->submit());next.setTag("export-confirm");next.setTextColor(0xff352016);next.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33262016),shape(ACCENT,28),null));footer.addView(next,new LinearLayout.LayoutParams(-1,dp(56)));
        LinearLayout originalActions=column();originalActions.setTag("export-original-actions");
        if(original!=null)originalActions.addView(button("保存 JPEG 原始文件",()->original()),new LinearLayout.LayoutParams(-1,dp(48)));
        if(raw!=null)originalActions.addView(button("保存 NEF 原始文件",()->raw()),new LinearLayout.LayoutParams(-1,dp(48)));
        root.addView(footer,new LinearLayout.LayoutParams(-1,-2));
        if(originalActions.getChildCount()>0){
            // The main export stays reachable in a short landscape window. Separate original-file
            // actions remain available at the end of the scrolling settings instead of shrinking it.
            Runnable placeOriginalActions=()->{
                LinearLayout target=getContext().getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?content:footer;
                if(originalActions.getParent()==target)return;
                if(originalActions.getParent() instanceof LinearLayout)((LinearLayout)originalActions.getParent()).removeView(originalActions);
                target.addView(originalActions,new LinearLayout.LayoutParams(-1,-2));
            };
            placeOriginalActions.run();
            root.addOnLayoutChangeListener((v,l,t,r,b,oldL,oldT,oldR,oldB)->placeOriginalActions.run());
        }

        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){if(!synchronising)refresh();}public void afterTextChanged(Editable e){}};
        edgeInput.addTextChangedListener(watcher);qualityInput.addTextChangedListener(watcher);
        edgeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int value,boolean user){if(user)edgeInput.setText(String.valueOf(value+640));}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        qualitySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int value,boolean user){if(user)qualityInput.setText(String.valueOf(value+70));}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        qualityCustom.setVisibility(View.GONE);custom.setVisibility(View.GONE);refresh();return root;
    }

    private void summary(LinearLayout content) {
        LinearLayout row=row();Bitmap thumb=items.isEmpty()?null:items.get(0).thumbnail;
        if(thumb!=null&&!thumb.isRecycled()){ImageView image=new ImageView(getContext());image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(thumb);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);image.setBackground(shape(FIELD,6));image.setClipToOutline(true);row.addView(image,new LinearLayout.LayoutParams(dp(64),dp(48)));}
        LinearLayout copy=column();copy.setPadding(thumb!=null?dp(12):0,0,0,0);
        TextView filename=text(items.size()==1?items.get(0).filename:"已选择 "+items.size()+" 张照片",14,TEXT);filename.setTypeface(null,Typeface.BOLD);copy.addView(filename);
        margin(copy,text(items.size()==1?items.get(0).description:"逐张按构图与边框计算尺寸 · 原图保持不变",12,MUTED),6,0);
        row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));content.addView(row);
    }
    private void choose(PhotoExportSpec.Mode selected) {
        if(selected==PhotoExportSpec.Mode.CUSTOM&&!customAvailable())return;
        mode=selected;
        if(mode==PhotoExportSpec.Mode.CUSTOM&&!customEntered){customEntered=true;synchronising=true;edgeInput.setText(String.valueOf(Math.min(4096,customMaximum)));qualityInput.setText("90");synchronising=false;}
        refresh();
    }
    private boolean customAvailable(){return customMaximum>=PhotoExportSpec.MIN_CUSTOM_EDGE&&!items.isEmpty()&&dimensionsAvailable();}
    private boolean dimensionsAvailable(){if(items.isEmpty())return false;for(Item item:items)if(!item.dimensionsAvailable())return false;return true;}
    private PhotoExportSpec selected() {
        if(mode==PhotoExportSpec.Mode.FULL)return PhotoExportSpec.full();
        if(mode==PhotoExportSpec.Mode.SHARE)return PhotoExportSpec.share();
        int edge=parse(edgeInput.getText().toString(),"长边"),quality=parse(qualityInput.getText().toString(),"JPEG 质量");
        if(edge<640||edge>customMaximum)throw new IllegalArgumentException("最终成品长边须为 640–"+customMaximum+" px，不能放大照片。");
        if(quality<70||quality>100)throw new IllegalArgumentException("JPEG 质量须为 70–100。");
        return PhotoExportSpec.custom(edge,quality);
    }
    private static int parse(String value,String label) {
        String trimmed=value.trim();if(trimmed.isEmpty())throw new IllegalArgumentException("请输入"+label+"。");
        if(!trimmed.matches("[0-9]+"))throw new IllegalArgumentException(label+"须为整数，不含小数、符号或其他字符。");
        try{return Integer.parseInt(trimmed);}catch(NumberFormatException invalid){throw new IllegalArgumentException(label+"数值过大，请检查输入。");}
    }
    private void refresh() {
        for(int i=0;i<modes.length;i++){ChoiceButton button=modes[i];boolean active=i==mode.ordinal();button.setSelected(active);button.setTextColor(active?0xff352016:MUTED);button.setTypeface(null,active?Typeface.BOLD:Typeface.NORMAL);button.setBackground(shape(active?ACCENT:Color.TRANSPARENT,8));button.setContentDescription(button.getText()+(active?"，已选择":""));}
        modes[2].setEnabled(customAvailable());modes[2].setAlpha(customAvailable()?1:.45f);modes[2].setContentDescription("自定义"+(customAvailable()?(mode==PhotoExportSpec.Mode.CUSTOM?"，已选择":""):"，不可用，完整成品长边不足 640 px 或尺寸尚不可用"));
        boolean isCustom=mode==PhotoExportSpec.Mode.CUSTOM;custom.setVisibility(isCustom?View.VISIBLE:View.GONE);
        View qc=qualityInput.getParent() instanceof View?(View)qualityInput.getParent():null;
        if(qc!=null&&qc.getParent() instanceof View)((View)qc.getParent()).setVisibility(isCustom?View.VISIBLE:View.GONE);
        ((View)qualityFixed.getParent()).setVisibility(isCustom?View.GONE:View.VISIBLE);
        qualityFixed.setText(mode==PhotoExportSpec.Mode.FULL?"95":"90");
        PhotoExportSpec spec=null;String problem="";
        if(!dimensionsAvailable())problem=items.isEmpty()?"没有可导出的照片。":"完整成品尺寸暂不可用，请返回后重试。";
        else try{spec=selected();}catch(IllegalArgumentException invalid){problem=invalid.getMessage();}
        validation.setText(problem);validation.setVisibility(problem.isEmpty()?View.GONE:View.VISIBLE);next.setEnabled(problem.isEmpty()&&!submitted);next.setAlpha(next.isEnabled()?1:.45f);
        dimensionsList.removeAllViews();
        if(spec==null){dimensions.setText("—");hint.setText(problem.isEmpty()?"请检查导出设置":problem);invalidateAvailability();showInvalidAction();return;}
        if(isCustom){synchronising=true;edgeSlider.setProgress(spec.longEdge-640);qualitySlider.setProgress(spec.quality-70);synchronising=false;}
        if(items.size()==1){Item item=items.get(0);int[] size=resolvedSize(item,spec);dimensions.setText(size[0]+" × "+size[1]+" px");dimensionsTitle.setText("最终导出尺寸 · 包含边框");}
        else {dimensions.setText(items.size()+" 张 · 逐张计算");dimensionsTitle.setText("最终导出尺寸 · 每张均包含各自边框");for(Item item:items){int[] size=resolvedSize(item,spec);TextView entry=text(item.filename+"\n"+size[0]+" × "+size[1]+" px · JPEG "+spec.quality,12,MUTED);entry.setPadding(0,dp(6),0,dp(6));dimensionsList.addView(entry,new LinearLayout.LayoutParams(-1,-2));}}
        hint.setText(spec.mode==PhotoExportSpec.Mode.FULL?"保留当前构图与边框的完整尺寸":("保持比例 · 不放大"+(items.size()>1?" · 每张长边 ≤ "+spec.longEdge+" px":"")));
        if(!customAvailable()&&dimensionsAvailable())hint.append("\n此图长边不足 640 px，可选完整尺寸或分享。");
        refreshAvailability();
    }
    /** Mirror the final-canvas downscale rule for summaries; no decoding or pixel allocation. */
    private static int[] resolvedSize(Item item,PhotoExportSpec spec) {
        int edge=Math.max(item.fullWidth,item.fullHeight),target=spec.mode==PhotoExportSpec.Mode.FULL?edge:Math.min(edge,spec.longEdge);
        int width=item.fullWidth>=item.fullHeight?target:Math.max(1,(int)Math.round(item.fullWidth*(double)target/edge));
        int height=item.fullHeight>=item.fullWidth?target:Math.max(1,(int)Math.round(item.fullHeight*(double)target/edge));return new int[]{width,height};
    }
    private void chooseDestination(){if(!isShowing()||submitted)return;String[] names={"仅保存在 App 内","保存到手机相册"};destinationDialog=new AlertDialog.Builder(getContext()).setTitle("保存到").setSingleChoiceItems(names,destination==PhotoSaveTarget.APP_ONLY?0:1,(dialog,index)->{if(isShowing()&&!submitted){destination=index==0?PhotoSaveTarget.APP_ONLY:PhotoSaveTarget.PHONE_ALBUM;refreshDestination();}dialog.dismiss();}).setNegativeButton("取消",null).create();AlertDialog shown=destinationDialog;shown.setOnDismissListener(ignored->{if(destinationDialog==shown)destinationDialog=null;});shown.show();}
    private void refreshDestination(){boolean phone=destination==PhotoSaveTarget.PHONE_ALBUM;if(!phone&&extraCopy.isChecked())extraCopy.setChecked(false);extraCopy.setEnabled(phone);destinationButton.setText("保存到  ·  "+(phone?"手机相册":"App 内"));destinationButton.setContentDescription("选择保存位置，当前"+(phone?"手机相册":"仅保存在 App 内"));destinationDetail.setText(phone?"同时保留在 App 内；保存前由现有流程核对成品与相册回执。":"手机相册不新增照片。App 内成品不会随预览缓存清理；卸载或清除应用数据前请另存需要保留的照片。");copyButton.setText("保存方式  ·  "+(phone&&extraCopy.isChecked()?"另存新副本":"复用已保存照片"));copyButton.setContentDescription("展开或收起保存方式，"+(phone&&extraCopy.isChecked()?"另存新副本":"默认复用已保存照片"));refreshAvailability();}
    private void invalidateAvailability(){availabilityGeneration++;availabilityKey="";availability=null;availabilityChecking=false;availabilityFromTimeout=false;cancelAvailabilityTimeout();if(availabilityText!=null)availabilityText.setVisibility(View.GONE);}
    private void cancelAvailabilityTimeout(){if(availabilityTimeout!=null){main.removeCallbacks(availabilityTimeout);availabilityTimeout=null;}}
    private String exportActionLabel(){return items.size()>1?"导出 "+items.size()+" 张照片":"导出照片";}
    private void showInvalidAction(){if(next!=null){next.setText(exportActionLabel());next.setEnabled(false);next.setAlpha(.45f);}}
    private String availabilityKey(PhotoExportSpec spec){StringBuilder key=new StringBuilder(destination.name());for(Item item:items)key.append('|').append(spec.resolve(item.fullWidth,item.fullHeight).identity());return key.toString();}
    private void refreshAvailability(){
        if(next==null||availabilityText==null||submitted)return;
        final PhotoExportSpec spec;
        try{if(!dimensionsAvailable())throw new IllegalArgumentException();spec=selected();}catch(IllegalArgumentException invalid){invalidateAvailability();showInvalidAction();return;}
        if(availabilityProvider==null){availabilityText.setVisibility(View.GONE);next.setText(exportActionLabel());return;}
        if(!isShowing())return;
        String key=availabilityKey(spec);
        if(!key.equals(availabilityKey)){
            cancelAvailabilityTimeout();availabilityKey=key;availability=null;availabilityChecking=true;availabilityFromTimeout=false;
            int generation=++availabilityGeneration;PhotoSaveTarget target=destination;
            // A stalled provider must not trap the settings page. Unknown retains normal receipt deduplication.
            availabilityTimeout=()->applyAvailability(generation,key,PhotoExportAvailability.unknown(items.size()),true);main.postDelayed(availabilityTimeout,12000);
            try{availabilityProvider.query(spec,target,result->main.post(()->applyAvailability(generation,key,result,false)));}
            catch(RuntimeException unavailable){applyAvailability(generation,key,PhotoExportAvailability.unknown(items.size()),false);}
        }
        updateAvailabilityAction();
    }
    private void applyAvailability(int generation,String key,PhotoExportAvailability.Result result,boolean timeout){
        if(submitted||!isShowing()||generation!=availabilityGeneration||!key.equals(availabilityKey))return;
        // A slow actual reply may upgrade the timeout fallback. Neither timeout nor duplicate replies
        // may overwrite a completed lookup for this generation.
        if(!availabilityChecking&&(timeout||!availabilityFromTimeout))return;
        cancelAvailabilityTimeout();availabilityFromTimeout=timeout;
        availability=result==null||result.entries.size()!=items.size()?PhotoExportAvailability.unknown(items.size()):result;
        availabilityChecking=false;updateAvailabilityAction();
    }
    private void updateAvailabilityAction(){
        if(next==null||availabilityText==null)return;
        availabilityText.setVisibility(View.VISIBLE);
        boolean copy=destination==PhotoSaveTarget.PHONE_ALBUM&&extraCopy.isChecked();
        if(availabilityChecking){availabilityText.setText("正在核对已保存照片…");next.setText("正在核对…");next.setEnabled(false);next.setAlpha(.45f);return;}
        if(availability==null){availabilityText.setVisibility(View.GONE);return;}
        availabilityText.setText(availability.summary(destination)+(copy?"\n新副本使用本次时间。":""));
        next.setText(availability.primaryLabel(destination,copy));
        // A verified existing result needs a viewing route, never an accidental export fallback.
        boolean canAct=copy||!availability.allSaved(destination)||viewSaved!=null;
        next.setEnabled(canAct&&!submitted);next.setAlpha(next.isEnabled()?1:.45f);
    }
    private void submit(){if(submitted||availabilityChecking)return;PhotoExportSpec spec;try{if(!dimensionsAvailable())return;spec=selected();}catch(IllegalArgumentException invalid){refresh();return;}PhotoSaveTarget target=destination;boolean newCopy=target==PhotoSaveTarget.PHONE_ALBUM&&extraCopy.isChecked();if(!newCopy&&availability!=null&&availability.allSaved(target)){if(viewSaved==null)return;submitted=true;PhotoExportAvailability.Result saved=availability;PhotoSaveChoice.remember(activity,"edited",target);dismiss();viewSaved.open(saved);return;}submitted=true;PhotoSaveChoice.remember(activity,"edited",target);dismiss();if(listener!=null)listener.chosen(spec,target,newCopy);}
    private void original(){if(submitted)return;submitted=true;dismiss();if(original!=null)original.run();}
    private void raw(){if(submitted)return;submitted=true;dismiss();if(raw!=null)raw.run();}
    private final class ChoiceButton extends Button {
        ChoiceButton(Context context){super(context);}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setCheckable(true);info.setChecked(isSelected());}
    }
    private EditText input(String description,String tag){EditText input=new EditText(getContext());input.setTag(tag);input.setTextSize(16);input.setTextColor(TEXT);input.setHintTextColor(MUTED);input.setPadding(dp(12),dp(10),dp(12),dp(10));input.setSingleLine(true);input.setMinHeight(dp(48));input.setBackground(shape(FIELD,8));input.setContentDescription(description);input.setSelectAllOnFocus(true);
        // Keep pasted invalid text visible for validation; the default numeric KeyListener silently
        // removes characters (turning e.g. "2048px" into a different, apparently valid value).
        input.setKeyListener(TextKeyListener.getInstance());input.setRawInputType(InputType.TYPE_CLASS_NUMBER);return input;}
    private SeekBar slider(int max){SeekBar bar=new SeekBar(getContext());bar.setMax(max);bar.setProgressTintList(ColorStateList.valueOf(ACCENT));bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff635247));bar.setThumbTintList(ColorStateList.valueOf(ACCENT));return bar;}
    private LinearLayout column(){LinearLayout view=new LinearLayout(getContext());view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout row(){LinearLayout view=new LinearLayout(getContext());view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private TextView text(String copy,int size,int color){TextView text=new TextView(getContext());text.setText(copy);text.setTextColor(color);text.setTextSize(size);text.setIncludeFontPadding(false);return text;}
    private TextView valueRow(LinearLayout content,String label,String value){LinearLayout row=row();TextView caption=text(label,14,MUTED);row.addView(caption,new LinearLayout.LayoutParams(0,dp(56),1));caption.setGravity(Gravity.CENTER_VERTICAL);TextView actual=text(value,14,TEXT);actual.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);row.addView(actual,new LinearLayout.LayoutParams(-2,dp(56)));content.addView(row);return actual;}
    private void divider(LinearLayout content,int top){View line=new View(getContext());line.setBackgroundColor(0xff38332e);margin(content,line,top,0);line.getLayoutParams().height=dp(1);}
    private void margin(LinearLayout content,View view,int top,int bottom){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(top);params.bottomMargin=dp(bottom);content.addView(view,params);}
    private Button button(String name,Runnable action){Button button=new Button(getContext());styleButton(button,name,action);return button;}
    private void styleButton(Button button,String name,Runnable action){button.setText(name);button.setAllCaps(false);button.setTextSize(14);button.setTextColor(ACCENT);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(dp(48));button.setMinimumHeight(dp(48));button.setPadding(dp(8),dp(6),dp(8),dp(6));button.setStateListAnimator(null);button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28f3b493),shape(Color.TRANSPARENT,12),null));button.setOnClickListener(v->action.run());}
    private GradientDrawable shape(int color,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;}
    private float density(){return getContext().getResources().getDisplayMetrics().density;}
    private int dp(float value){return Math.round(value*density());}
}

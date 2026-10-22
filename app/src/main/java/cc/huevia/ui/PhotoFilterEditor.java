package cc.huevia.ui;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.ExifInterface;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import cc.huevia.album.*;
import cc.huevia.library.*;
import cc.huevia.ncp.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** A draft-only editing surface. Only an explicit export enters the application batch worker. */
public final class PhotoFilterEditor implements AutoCloseable {
    public interface Host {
        void changed();
        void export(java.util.List<PhotoPresetBatch.Edit> edits);
        void raw();
        boolean canExportRaw();
        void download();
        void addFilters();
        void error(String message);
    }
    private static final int BG=0xff22252a, FIELD=0xff30353d, TEXT=0xfff4f5f7, MUTED=0xffadb6c3, BLUE=0xff8dbbff;
    private final Activity activity; private final PhotoPresetBatch batch; private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->thread(r,"photo-edit-io"));
    private final ExecutorService catalogIo=Executors.newSingleThreadExecutor(r->thread(r,"photo-edit-catalog"));
    private final ThreadPoolExecutor previews=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->thread(r,"photo-edit-preview"),new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ThreadPoolExecutor thumbnails=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->thread(r,"photo-edit-thumbnails"),new ThreadPoolExecutor.AbortPolicy());
    private final java.util.List<Entry> entries=new ArrayList<>(); private final java.util.List<Runnable> visibleRequests=new ArrayList<>();
    private final java.util.List<Entry> onlineEntries=new ArrayList<>();
    private final ArrayDeque<Entry> renderedEntries=new ArrayDeque<>();
    private volatile int generation, previewRequest; private volatile boolean closed;
    private DownloadedPhoto bound, originalPhoto; private String owner="", sourceSha="", selected="", selectedName="原图", category="", query="";
    private byte[] selectedBytes=new byte[0]; private PresetPreviewProfile selectedProfile;
    private int strength=100; private boolean ready, hasDraft, exported, updating, parkedDraft;
    private int pickerFirst, pickerTop;
    private Bitmap original, current, thumb, glass; private PresetLibrary library;
    private LinearLayout dock, strip, categories; private TextView title, value, note; private SeekBar slider;
    private HorizontalScrollView filterScroll; private Dialog popup; private GridView grid; private BaseAdapter gridAdapter;
    private Runnable delayedSave; private boolean savePending; private Entry pendingChoice;
    private boolean pendingChoiceFromPicker;
    private Runnable pendingRetry;private int pendingRetryCount;private long pendingChoiceSerial;
    private final Map<String, FilterCard> stripCards = new LinkedHashMap<>();
    private final Map<String, Button> categoryButtons = new LinkedHashMap<>();
    private View addFiltersAction;
    private boolean onlineSource=true,catalogLoading;private String catalogMessage="正在加载在线滤镜…",catalogDiagnostic="";
    private EditorPresetCatalog onlineCatalog;private Button onlineButton,mineButton;private TextView sourceStatus;
    private final java.util.List<OnlinePreset> catalogMetadata=new ArrayList<>();private long catalogLoadedAt;private String cachedCatalogMessage="",cachedCatalogDiagnostic="";
    private LinearLayout pickerCategories;private TextView pickerStatus,pickerTitle;
    private static final Map<String,Boolean> KNOWN=Collections.synchronizedMap(new LinkedHashMap<String,Boolean>(64,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Boolean> entry){return size()>512;}});
    private static final class Entry {
        final PresetRecord preset;final OnlinePreset online;final String id,name,category;
        Bitmap image;byte[] bytes;PresetPreviewProfile profile;String error="";boolean loading,retryable;
        Entry(PresetRecord p){preset=p;online=null;id=p.id;name=p.displayName;category=p.category;}
        Entry(OnlinePreset p){preset=null;online=p;id=p.sha256;name=p.name;category=p.category;}
    }
    public PhotoFilterEditor(Activity activity, PhotoPresetBatch batch, Host host){this.activity=activity;this.batch=batch;this.host=host;}
    private static Thread thread(Runnable r,String name){Thread t=new Thread(r,name);t.setDaemon(true);return t;}
    public static String accountOwner(Context context){
        cc.huevia.account.AccountRepository repository=cc.huevia.account.AndroidAccounts.get(context);
        cc.huevia.account.AccountRepository.Session session=repository.cachedSession(); return session==null||repository.isLogoutPending()?"":session.user.id;
    }
    public Bitmap preview(){return ready?current:null;} public Bitmap original(){return ready?original:null;}
    public boolean isReady(){return ready;} public String label(){return hasDraft?selectedName+(selected.isEmpty()?"":" · "+strength+"%")+(exported?"":" · 未导出"):parkedDraft?"已保存版本 · 未导出草稿已保留":"当前已保存版本";}
    public boolean dirty(){return ready&&hasDraft&&!exported;}
    public boolean matches(PhotoPresetBatch.Edit edit){return ready&&(edit.draft==null?bound.file().equals(edit.source.localPhoto()==null?null:edit.source.localPhoto().file()):hasDraft&&sourceSha.equals(edit.draft.sourceSha)&&selected.equals(edit.draft.presetSha)&&strength==edit.draft.strength);}
    public void bind(DownloadedPhoto photo){bind(photo,false);}
    public void bindSavedVersion(DownloadedPhoto photo){unbind();bind(photo,true);}
    private void bind(DownloadedPhoto photo,boolean savedOnly){
        String nextOwner=accountOwner(activity);
        if(bound!=null&&photo!=null&&bound.file().equals(photo.file())&&owner.equals(nextOwner))return;
        unbind(); bound=photo; owner=nextOwner;
        if(photo==null){refresh();return;} final int token=generation; final String scope=owner;
        io.execute(()->{
            try{
                if(!valid(token,scope))return;
                DownloadedPhoto root=batch.processingOriginalFor(photo); String sha=PhotoEditStore.hashFile(root.file());
                PhotoEditStore.Draft stored=new PhotoEditStore(activity.getFilesDir(),scope).read(sha);
                PhotoEditStore.Draft draft=!savedOnly&&stored!=null&&!stored.exported?stored:null;
                Bitmap base=decode(root.file(),1280), shown=draft==null&&!root.file().equals(photo.file())?decode(photo.file(),1280):base;
                PresetPreviewProfile profile=draft==null||draft.presetSha.isEmpty()?null:PresetPreviewProfiles.fromPreset(draft.presetBytes());
                if(draft!=null&&profile!=null)shown=render(base,StrengthPreviewProfile.of(profile,draft.strength));
                PresetLibrary local=new PresetLibrary(activity.getFilesDir()); PersonalPresetSync personal=new PersonalPresetSync(activity.getFilesDir(),local);
                java.util.List<PresetRecord> visible=new ArrayList<>();for(PresetRecord p:local.list())if(personal.visible(scope,p.id))visible.add(p);
                Bitmap result=shown, material=EditorGlassDrawable.sample(base); main.post(()->{
                    if(!valid(token,scope))return;
                    originalPhoto=root;sourceSha=sha;original=base;current=result;glass=material;thumb=Bitmap.createScaledBitmap(base,256,Math.max(1,base.getHeight()*256/base.getWidth()),true);
                    if(dock!=null)dock.setBackground(material());library=local;ready=true;hasDraft=draft!=null;exported=false;parkedDraft=savedOnly&&stored!=null&&!stored.exported;
                    selected=draft==null?"":draft.presetSha;selectedName=draft==null?"原图":draft.name;selectedBytes=draft==null?new byte[0]:draft.presetBytes();strength=draft==null?100:draft.strength;selectedProfile=profile;
                    entries.clear();for(PresetRecord p:visible)entries.add(new Entry(p));KNOWN.put(scope+":"+sha,stored!=null&&!stored.exported);
                    if(onlineCatalog!=null&&SystemClock.elapsedRealtime()-catalogLoadedAt<300000){for(OnlinePreset p:catalogMetadata)onlineEntries.add(new Entry(p));catalogMessage=cachedCatalogMessage;catalogDiagnostic=cachedCatalogDiagnostic;refresh();host.changed();}else{refresh();host.changed();loadOnlineCatalog();}
                });
            }catch(Exception|OutOfMemoryError failure){main.post(()->{if(valid(token,scope)){if(note!=null)note.setText("无法准备编辑 · 点按重试");if(note!=null)note.setOnClickListener(v->{DownloadedPhoto p=bound;bound=null;bind(p);});host.error(message(failure));}});}
        });
    }
    public void unbind(){
        flushSave();generation++;previewRequest++;cancelPendingRetry();previews.getQueue().clear();thumbnails.getQueue().clear();dismissPopup();
        bound=null;ready=false;original=null;current=null;thumb=null;glass=null;originalPhoto=null;sourceSha="";entries.clear();onlineEntries.clear();catalogLoading=false;catalogMessage="正在加载在线滤镜…";catalogDiagnostic="";renderedEntries.clear();pendingChoice=null;parkedDraft=false;pickerFirst=pickerTop=0;selected="";selectedProfile=null;selectedBytes=new byte[0];hasDraft=false;visibleRequests.clear();stripCards.clear();categoryButtons.clear();if(strip!=null)strip.removeAllViews();if(categories!=null)categories.removeAllViews();if(filterScroll!=null)filterScroll.scrollTo(0,0);
    }
    private boolean valid(int token,String scope){return !closed&&token==generation&&scope.equals(accountOwner(activity));}
    public View createDock(){
        stripCards.clear();categoryButtons.clear();visibleRequests.clear();addFiltersAction=null;
        dock=column();dock.setBackground(material());dock.setPadding(dp(14),dp(4),dp(14),dp(10));dock.setClipToOutline(true);dock.setElevation(dp(6));
        TextView handle=text("",12,MUTED);handle.setBackground(shape(0xff657080,2));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(30),dp(3));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.topMargin=dp(4);hp.bottomMargin=dp(6);dock.addView(handle,hp);
        LinearLayout heading=row(); title=text("滤镜",16,TEXT);title.setTypeface(null,Typeface.BOLD);heading.addView(title,new LinearLayout.LayoutParams(0,dp(42),1));
        LinearLayout sources=sourceToggle();onlineButton=button("在线",false,()->setOnlineSource(true));mineButton=button("我的",false,()->setOnlineSource(false));sources.addView(onlineButton,new LinearLayout.LayoutParams(dp(54),dp(48)));sources.addView(mineButton,new LinearLayout.LayoutParams(dp(54),dp(48)));LinearLayout.LayoutParams sourceParams=new LinearLayout.LayoutParams(-2,dp(48));sourceParams.rightMargin=dp(12);heading.addView(sources,sourceParams);
        Button all=button("全部",false,this::showAll);all.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(AppIcon.Kind.GRID),null,null,null);all.setCompoundDrawablePadding(dp(6));heading.addView(all,new LinearLayout.LayoutParams(-2,dp(48)));dock.addView(heading);
        final float[] start={0};heading.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)start[0]=e.getY();if(e.getActionMasked()==MotionEvent.ACTION_UP&&start[0]-e.getY()>dp(24)){showAll();return true;}return false;});
        HorizontalScrollView cats=new HorizontalScrollView(activity);cats.setHorizontalScrollBarEnabled(false);cats.setOverScrollMode(View.OVER_SCROLL_NEVER);categories=row();cats.addView(categories);LinearLayout.LayoutParams categoryParams=new LinearLayout.LayoutParams(-1,dp(48));categoryParams.topMargin=dp(6);dock.addView(cats,categoryParams);
        sourceStatus=text("",11,MUTED);sourceStatus.setSingleLine(true);sourceStatus.setEllipsize(TextUtils.TruncateAt.END);sourceStatus.setOnClickListener(v->{if(onlineSource&&!catalogLoading)loadOnlineCatalog();});dock.addView(sourceStatus,new LinearLayout.LayoutParams(-1,dp(28)));
        filterScroll=new HorizontalScrollView(activity);filterScroll.setHorizontalScrollBarEnabled(false);filterScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);strip=row();filterScroll.addView(strip);dock.addView(filterScroll,new LinearLayout.LayoutParams(-1,dp(112)));
        filterScroll.getViewTreeObserver().addOnScrollChangedListener(this::requestVisible);
        LinearLayout intensity=row();TextView label=text("强度",13,TEXT);intensity.addView(label,new LinearLayout.LayoutParams(dp(44),dp(48)));
        slider=new SeekBar(activity);slider.setMax(100);slider.setProgressTintList(ColorStateList.valueOf(BLUE));slider.setThumbTintList(ColorStateList.valueOf(BLUE));slider.setContentDescription("滤镜强度，0 到 100 百分比");intensity.addView(slider,new LinearLayout.LayoutParams(0,dp(48),1));
        value=text("100%",13,TEXT);value.setGravity(Gravity.CENTER);intensity.addView(value,new LinearLayout.LayoutParams(dp(48),dp(48)));dock.addView(intensity);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){if(user&&!updating&&ready&&!selected.isEmpty()){strength=p;exported=false;hasDraft=true;value.setText(p+"%");schedulePreview();scheduleSave();}}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){flushSave();}});
        note=text("手机近似效果 · 原图保留",11,MUTED);note.setSingleLine(true);note.setEllipsize(TextUtils.TruncateAt.END);dock.addView(note,new LinearLayout.LayoutParams(-1,dp(24)));refresh();return dock;
    }
    private void refresh(){
        if(dock==null)return;updating=true;slider.setProgress(selected.isEmpty()?0:strength);slider.setEnabled(ready&&!selected.isEmpty());value.setText((selected.isEmpty()?0:strength)+"%");updating=false;
        note.setText(!ready?(bound==null?"下载完整原图后即可使用滤镜 · 点按下载":"正在准备当前照片的滤镜…"):label()+(parkedDraft?" · 点按恢复编辑":" · 手机近似效果"));note.setTooltipText(note.getText());note.setOnClickListener(parkedDraft?v->{DownloadedPhoto photo=bound;unbind();bind(photo);}:!ready&&bound==null?v->host.download():null);
        title.setText("滤镜");styleSource(onlineButton,onlineSource);styleSource(mineButton,!onlineSource);
        updateCatalogStatus(sourceStatus);
        buildCategories();buildStrip();
    }
    private java.util.List<Entry> activeEntries(){return onlineSource?onlineEntries:entries;}
    private void updateCatalogStatus(TextView status){
        String label=onlineSource?(catalogMessage.isEmpty()?"在线滤镜 · "+onlineEntries.size()+" 款":catalogMessage):"我的滤镜 · "+entries.size()+" 款";
        status.setText(label);status.setTooltipText(onlineSource&&!catalogDiagnostic.isEmpty()?label+"\n"+catalogDiagnostic:label);
    }
    private void setOnlineSource(boolean online){if(onlineSource==online)return;cancelPendingRetry();onlineSource=online;category="";query="";pendingChoice=null;pickerFirst=pickerTop=0;refresh();if(filterScroll!=null){final HorizontalScrollView target=filterScroll;final int token=generation;target.post(()->{if(!closed&&generation==token&&filterScroll==target&&onlineSource==online)target.scrollTo(0,0);});}}
    private void loadOnlineCatalog(){
        if(!ready||closed||catalogLoading)return;catalogLoading=true;catalogMessage="正在加载在线滤镜…";catalogDiagnostic="";refresh();refreshPickerMetadata();
        final int token=generation;final String scope=owner;final PresetLibrary local=library;
        catalogIo.execute(()->{if(!valid(token,scope))return;try{EditorPresetCatalog catalog=new EditorPresetCatalog(activity.getFilesDir(),local);OnlinePresetRepository.CatalogResult result=catalog.load();main.post(()->{
            if(!valid(token,scope))return;onlineCatalog=catalog;catalogLoading=false;Map<String,Entry> previous=new LinkedHashMap<>();for(Entry e:onlineEntries)previous.put(e.id,e);LinkedHashMap<String,Entry> distinct=new LinkedHashMap<>();for(OnlinePreset preset:result.presets){Entry entry=previous.get(preset.sha256);if(entry==null||!entry.category.equals(preset.category)||!entry.name.equals(preset.name))entry=new Entry(preset);distinct.putIfAbsent(entry.id,entry);}onlineEntries.clear();onlineEntries.addAll(distinct.values());
            if(pendingChoice!=null&&pendingChoice.online!=null&&!onlineEntries.contains(pendingChoice))pendingChoice=null;
            catalogMessage="ok".equals(result.status)?(onlineEntries.isEmpty()?"在线暂无滤镜 · 轻点刷新":""):"cached".equals(result.status)?"离线缓存 · "+onlineEntries.size()+" 款 · 轻点刷新":"在线滤镜加载失败 · 轻点重试";
            catalogDiagnostic="ok".equals(result.status)?"":result.message;catalogMetadata.clear();catalogMetadata.addAll(result.presets);catalogLoadedAt=SystemClock.elapsedRealtime();cachedCatalogMessage=catalogMessage;cachedCatalogDiagnostic=catalogDiagnostic;refresh();refreshPickerMetadata();if(gridAdapter!=null)gridAdapter.notifyDataSetChanged();
        });}catch(Exception failure){main.post(()->{if(valid(token,scope)){catalogLoading=false;catalogMessage="在线滤镜加载失败 · 轻点重试";catalogDiagnostic=message(failure);refresh();refreshPickerMetadata();}});}});
    }
    private void buildCategories(){
        if(categories==null)return;LinkedHashSet<String> names=categoryNames();
        if(!names.contains(category))category="";
        if(!new ArrayList<>(categoryButtons.keySet()).equals(new ArrayList<>(names))){categories.removeAllViews();categoryButtons.clear();for(String name:names){Button b=button(name.isEmpty()?"全部":name,false,()->{if(category.equals(name))return;cancelPendingRetry();pendingChoice=null;category=name;buildCategories();buildStrip();if(gridAdapter!=null)gridAdapter.notifyDataSetChanged();});b.setTag(name);categoryButtons.put(name,b);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(48));p.rightMargin=dp(6);categories.addView(b,p);}}
        for(Map.Entry<String,Button> item:categoryButtons.entrySet())styleCategory(item.getValue(),item.getKey().equals(category));
    }
    private LinkedHashSet<String> categoryNames(){LinkedHashSet<String> present=new LinkedHashSet<>(),names=new LinkedHashSet<>();names.add("");for(Entry e:activeEntries())present.add(PresetCategories.label(e.category));for(String c:PresetCategories.SUGGESTED)if(present.remove(c))names.add(c);boolean uncategorized=present.remove(PresetCategories.UNCATEGORIZED);names.addAll(present);if(uncategorized)names.add(PresetCategories.UNCATEGORIZED);return names;}
    private boolean included(Entry e){return(category.isEmpty()||category.equals(PresetCategories.label(e.category)))&&(query.isEmpty()||(e.name+" "+PresetCategories.label(e.category)).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)));}
    private void buildStrip(){
        if(strip==null)return;visibleRequests.clear();
        java.util.List<Entry> shown=new ArrayList<>();Entry chosen=null;
        for(Entry entry:activeEntries()){if(entry.id.equals(selected))chosen=entry;if(included(entry)&&shown.size()<24)shown.add(entry);}
        if(chosen!=null&&included(chosen)&&!shown.contains(chosen)){if(shown.size()==24)shown.remove(23);shown.add(chosen);}
        java.util.List<Entry> desired=new ArrayList<>();desired.add(null);desired.addAll(shown);
        LinkedHashSet<String> keys=new LinkedHashSet<>();for(Entry entry:desired)keys.add(entry==null?"":entry.id);
        boolean structureChanged=!new ArrayList<>(stripCards.keySet()).equals(new ArrayList<>(keys));
        if(!structureChanged)for(Entry entry:desired){FilterCard existing=stripCards.get(entry==null?"":entry.id);if(existing==null||existing.entry!=entry){structureChanged=true;break;}}
        int scrollX=filterScroll.getScrollX();
        if(structureChanged){Map<String,FilterCard> old=new LinkedHashMap<>(stripCards);stripCards.clear();for(int i=0;i<desired.size();i++){Entry entry=desired.get(i);String id=entry==null?"":entry.id;FilterCard view=old.remove(id);if(view!=null&&view.entry!=entry){strip.removeView(view);view=null;}if(view==null)view=new FilterCard(entry,false);stripCards.put(id,view);if(view.getParent()!=strip)strip.addView(view,i,new LinearLayout.LayoutParams(dp(84),dp(110)));else if(strip.indexOfChild(view)!=i){strip.removeView(view);strip.addView(view,i,new LinearLayout.LayoutParams(dp(84),dp(110)));}}for(FilterCard stale:old.values())strip.removeView(stale);}
        for(FilterCard view:stripCards.values()){view.update();visibleRequests.add(view::requestIfVisible);}
        if(ready&&!onlineSource&&entries.isEmpty()){if(addFiltersAction==null)addFiltersAction=button("去添加滤镜",false,host::addFilters);if(addFiltersAction.getParent()!=strip)strip.addView(addFiltersAction,new LinearLayout.LayoutParams(-2,dp(96)));}else if(addFiltersAction!=null&&addFiltersAction.getParent()==strip)strip.removeView(addFiltersAction);
        final HorizontalScrollView target=filterScroll;final int token=generation;final boolean restore=structureChanged,online=onlineSource;final String scope=owner;
        target.post(()->{if(!valid(token,scope)||filterScroll!=target||onlineSource!=online)return;if(restore)target.scrollTo(scrollX,0);requestVisible();});
    }
    private View card(Entry entry,boolean large){
        return new FilterCard(entry,large);
    }
    /** A card's identity and bounds survive selection and asynchronous thumbnail completion. */
    private final class FilterCard extends LinearLayout {
        final Entry entry;final boolean large;final ImageView image,check;final TextView name;final FrameLayout photo;final ProgressBar busy;
        final GradientDrawable border;Bitmap shownBitmap;String shownLabel="";
        FilterCard(Entry entry,boolean large){
            super(activity);this.entry=entry;this.large=large;setOrientation(VERTICAL);setPadding(dp(3),dp(3),dp(3),dp(3));setTag(entry==null?"":entry.id);
            photo=new FrameLayout(activity);border=shape(FIELD,12);photo.setBackground(border);photo.setClipToOutline(true);photo.setPadding(dp(3),dp(3),dp(3),dp(3));
            image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(shape(FIELD,9));image.setClipToOutline(true);photo.addView(image,new FrameLayout.LayoutParams(-1,-1));
            check=new ImageView(activity);check.setImageDrawable(icon(AppIcon.Kind.CHECK));check.setBackground(shape(0xff356eab,12));check.setPadding(dp(3),dp(3),dp(3),dp(3));FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.BOTTOM|Gravity.END);cp.setMargins(0,0,dp(4),dp(4));photo.addView(check,cp);
            busy=new ProgressBar(activity);busy.setIndeterminateTintList(ColorStateList.valueOf(BLUE));busy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);photo.addView(busy,new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
            addView(photo,new LinearLayout.LayoutParams(-1,large?dp(86):dp(78)));name=text("",12,TEXT);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);name.setGravity(Gravity.CENTER);addView(name,new LinearLayout.LayoutParams(-1,dp(26)));
            setFocusable(true);setClickable(true);setOnClickListener(v->{if(!ready)return;cancelPendingRetry();pendingChoice=null;if(entry!=null&&!entry.error.isEmpty()){if(entry.retryable){entry.error="";entry.retryable=false;}else{refreshPendingState();host.error(entry.error);return;}}if(entry!=null&&entry.profile==null){pendingChoice=entry;pendingChoiceFromPicker=large;refreshPendingState();request(entry);return;}refreshPendingState();choose(entry,large);dismissPopup();});
            update();post(this::requestIfVisible);
        }
        void requestIfVisible(){if(entry!=null&&entry.image==null&&entry.error.isEmpty()&&isShown()&&getGlobalVisibleRect(new Rect()))request(entry);}
        void update(){
            String id=entry==null?"":entry.id;boolean chosen=id.equals(selected)&&(hasDraft||ready&&bound!=null&&originalPhoto!=null&&bound.file().equals(originalPhoto.file()));
            Bitmap bitmap=entry==null?thumb:entry.image;if(bitmap!=shownBitmap){shownBitmap=bitmap;image.setImageBitmap(bitmap);}
            boolean unsupported=entry!=null&&!entry.error.isEmpty();String label=unsupported?(entry.retryable?"轻点重试":"暂不支持"):entry==null?"原图":entry.name;
            if(!shownLabel.equals(label)){shownLabel=label;name.setText(label);}image.setAlpha(unsupported?.3f:1f);
            border.setColor(chosen?0xff263d5a:FIELD);border.setStroke(dp(2),chosen?BLUE:Color.TRANSPARENT);check.setVisibility(chosen?View.VISIBLE:View.GONE);name.setTextColor(chosen?BLUE:TEXT);name.setTypeface(null,chosen?Typeface.BOLD:Typeface.NORMAL);setSelected(chosen);
            busy.setVisibility(entry!=null&&pendingChoice==entry&&entry.profile==null?View.VISIBLE:View.GONE);
            setContentDescription((entry==null?"原图":entry.name)+(unsupported?"，暂不支持："+entry.error:chosen?"，已选择":pendingChoice==entry&&entry!=null?"，正在准备预览":""));
        }
    }
    private void refreshPendingState(){for(FilterCard card:stripCards.values())card.update();refreshGridThumbnails();}
    private void requestVisible(){
        // A tap takes priority over speculative thumbnails. A full queue must not strand
        // an expanded-grid choice that has no counterpart in the collapsed strip.
        if(pendingChoice!=null)request(pendingChoice);
        for(Runnable r:new ArrayList<>(visibleRequests))r.run();
        if(grid!=null)for(int i=0;i<grid.getChildCount();i++){View child=grid.getChildAt(i);if(child instanceof FilterCard)((FilterCard)child).requestIfVisible();}
    }
    private void refreshGridThumbnails(){
        if(grid==null)return;
        for(int i=0;i<grid.getChildCount();i++){
            View child=grid.getChildAt(i);if(child instanceof FilterCard)((FilterCard)child).update();
        }
    }
    private void request(Entry entry){
        if(!ready||entry.loading||entry.image!=null||!entry.error.isEmpty())return;entry.loading=true;
        final int token=generation;final String scope=owner;final Bitmap base=thumb;final PresetLibrary local=library;final EditorPresetCatalog catalog=onlineCatalog;
        try{thumbnails.execute(()->{if(!valid(token,scope))return;Bitmap image=null;String error="";byte[] bytes=null;PresetPreviewProfile profile=null;
            boolean retryable=false;try{if(entry.online!=null){try{if(catalog==null)throw new IOException("在线滤镜目录尚未就绪，请稍后重试。");bytes=catalog.read(entry.online);}catch(IOException failure){retryable=true;throw failure;}}else bytes=local.readPreset(entry.id);profile=PresetPreviewProfiles.fromPreset(bytes);image=render(base,profile);}catch(Exception|OutOfMemoryError e){error=message(e);}
            final Bitmap result=image;final String reason=error;final byte[] data=bytes;final PresetPreviewProfile parsed=profile;final boolean canRetry=retryable;
            main.post(()->{entry.loading=false;if(!valid(token,scope)||!(entries.contains(entry)||onlineEntries.contains(entry)))return;entry.image=result;entry.error=reason;entry.retryable=canRetry;entry.bytes=data;entry.profile=parsed;renderedEntries.remove(entry);renderedEntries.addLast(entry);while(renderedEntries.size()>48){Entry old=renderedEntries.removeFirst();old.image=null;old.bytes=null;old.profile=null;}if(pendingChoice==entry){boolean fromPicker=pendingChoiceFromPicker;pendingChoice=null;if(parsed!=null&&reason.isEmpty()){choose(entry,fromPicker);dismissPopup();}else host.error(reason);}FilterCard card=stripCards.get(entry.id);if(card!=null&&card.entry==entry)card.update();refreshGridThumbnails();requestVisible();});
        });}catch(RejectedExecutionException full){entry.loading=false;if(pendingChoice==entry)schedulePendingRetry(entry);}
    }
    private void cancelPendingRetry(){if(pendingRetry!=null)main.removeCallbacks(pendingRetry);pendingRetry=null;pendingRetryCount=0;pendingChoiceSerial++;}
    /** Four admission retries, then worker completions retry the active tap first. No idle polling. */
    private void schedulePendingRetry(Entry entry){
        if(closed||pendingRetry!=null||pendingRetryCount>=4||thumbnails.isShutdown())return;
        final int token=generation,attempt=++pendingRetryCount;final String scope=owner;final boolean online=onlineSource;final long choice=pendingChoiceSerial;
        pendingRetry=()->{pendingRetry=null;if(!valid(token,scope)||onlineSource!=online||choice!=pendingChoiceSerial||pendingChoice!=entry||!activeEntries().contains(entry))return;request(entry);};
        main.postDelayed(pendingRetry,80L<<(attempt-1));
    }
    private void choose(Entry entry){
        choose(entry,false);
    }
    private void choose(Entry entry,boolean fromPicker){
        cancelPendingRetry();pendingChoice=null;
        selected=entry==null?"":entry.id;selectedName=entry==null?"原图":entry.name;selectedBytes=entry==null?new byte[0]:entry.bytes.clone();selectedProfile=entry==null?null:entry.profile;
        strength=entry==null?0:100;hasDraft=true;exported=false;parkedDraft=false;refresh();refreshGridThumbnails();scheduleSave();schedulePreview();
        if(fromPicker&&filterScroll!=null){final String id=selected,scope=owner;final int token=generation;final boolean online=onlineSource;final HorizontalScrollView target=filterScroll;target.post(()->{FilterCard card=stripCards.get(id);if(!valid(token,scope)||filterScroll!=target||onlineSource!=online||card==null||!selected.equals(id))return;int left=target.getScrollX(),right=left+target.getWidth();if(card.getLeft()<left)target.scrollTo(Math.max(0,card.getLeft()-dp(8)),0);else if(card.getRight()>right)target.scrollTo(Math.max(0,card.getRight()-target.getWidth()+dp(8)),0);});}
    }
    private void schedulePreview(){
        final int token=generation,request=++previewRequest,amount=strength;final String scope=owner;final Bitmap base=original;final PresetPreviewProfile profile=selectedProfile;
        previews.execute(()->{try{Bitmap result=profile==null||amount==0?base:render(base,StrengthPreviewProfile.of(profile,amount));main.post(()->{if(valid(token,scope)&&request==previewRequest){current=result;if(note!=null)note.setText(label()+" · 手机近似效果");host.changed();}});}catch(Exception|OutOfMemoryError e){main.post(()->{if(valid(token,scope)&&request==previewRequest)host.error(message(e));});}});
    }
    private void scheduleSave(){savePending=true;if(delayedSave!=null)main.removeCallbacks(delayedSave);delayedSave=this::flushSave;main.postDelayed(delayedSave,200);}
    private void flushSave(){
        if(delayedSave!=null){main.removeCallbacks(delayedSave);delayedSave=null;}if(!savePending||!ready||!hasDraft||closed)return;savePending=false;
        final String sha=sourceSha,name=selectedName,scope=owner;final byte[] bytes=selectedBytes.clone();final int amount=strength;final boolean saved=exported;
        io.execute(()->{try{PhotoEditStore.Draft draft=new PhotoEditStore.Draft(sha,bytes,name,amount,saved);new PhotoEditStore(activity.getFilesDir(),scope).save(draft);KNOWN.put(scope+":"+sha,!draft.exported);}catch(IOException e){main.post(()->{if(!closed)host.error(message(e));});}});
    }
    public void flushDraft(){flushSave();}
    public void refreshExportStatus(){
        if(!ready)return;flushSave();final int token=generation;final String scope=owner,sha=sourceSha,id=selected;final int amount=strength;
        io.execute(()->{try{PhotoEditStore.Draft d=new PhotoEditStore(activity.getFilesDir(),scope).read(sha);main.post(()->{if(!valid(token,scope)||!id.equals(selected)||amount!=strength)return;exported=d!=null&&d.exported&&d.presetSha.equals(id)&&d.strength==amount;KNOWN.put(scope+":"+sha,d!=null&&!d.exported);refresh();host.changed();});}catch(IOException e){main.post(()->{if(valid(token,scope))host.error(message(e));});}});
    }
    private void resetPickerPosition(){pickerFirst=pickerTop=0;if(grid!=null)grid.setSelection(0);}
    /** Serial barrier also covers a draft just flushed while leaving the detail page. */
    public void afterPendingSaves(Runnable done){flushSave();io.execute(()->main.post(()->{if(!closed)done.run();}));}
    public void freeze(Consumer<PhotoPresetBatch.Edit> done){
        if(!ready){host.download();return;}flushSave();final DownloadedPhoto photo=originalPhoto,displayed=bound;final int token=generation;final String scope=owner,sha=sourceSha;
        final PhotoEditStore.Draft frozen;
        try{frozen=hasDraft?new PhotoEditStore.Draft(sha,selectedBytes,selectedName,strength,exported):null;}catch(IOException error){host.error(message(error));return;}
        io.execute(()->{try{if(frozen!=null)new PhotoEditStore(activity.getFilesDir(),scope).save(frozen);PhotoPresetBatch.Edit edit=frozen==null?PhotoPresetBatch.Edit.saved(PhotoPresetBatch.Source.local(displayed)):PhotoPresetBatch.Edit.draft(PhotoPresetBatch.Source.local(photo),frozen,scope);main.post(()->{if(valid(token,scope))done.accept(edit);});}catch(Exception e){main.post(()->{if(valid(token,scope))host.error(message(e));});}});
    }
    public void showExport(){
        if(!ready){host.download();return;}dismissPopup();LinearLayout content=sheet("导出照片");
        LinearLayout summary=row();ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(current);summary.addView(image,new LinearLayout.LayoutParams(dp(86),dp(64)));TextView name=text(label(),14,TEXT);name.setPadding(dp(16),0,0,0);name.setMaxLines(3);summary.addView(name,new LinearLayout.LayoutParams(0,dp(72),1));content.addView(summary);
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(originalPhoto.file().getPath(),bounds);
        info(content,"格式","JPG");info(content,"尺寸",bounds.outWidth+" × "+bounds.outHeight);TextView hint=text("保存为新照片，原图保留",12,MUTED);hint.setGravity(Gravity.CENTER);content.addView(hint,new LinearLayout.LayoutParams(-1,dp(44)));
        Button save=button("保存到相册",true,()->freeze(edit->{dismissPopup();host.export(Collections.singletonList(edit));}));content.addView(save,new LinearLayout.LayoutParams(-1,dp(50)));
        if(host.canExportRaw()){Button raw=button("导出相机原始 NEF",false,()->{dismissPopup();host.raw();});content.addView(raw,new LinearLayout.LayoutParams(-1,dp(48)));TextView rawHint=text("原始 NEF 不包含手机滤镜",11,MUTED);rawHint.setGravity(Gravity.CENTER);content.addView(rawHint);}showSheet(content,-2);
    }
    public void showAll(){
        if(!ready)return;
        dismissPopup();query="";
        boolean compact=activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout content=sheet((onlineSource?"在线滤镜":"我的滤镜")+" · "+activeEntries().size());pickerTitle=(TextView)((LinearLayout)content.getChildAt(1)).getChildAt(0);
        LinearLayout header=(LinearLayout)content.getChildAt(1);
        LinearLayout sources=sourceToggle();
        for(boolean online:new boolean[]{true,false}){
            Button choice=button(online?"在线":"我的",false,()->{if(onlineSource==online)return;dismissPopup();setOnlineSource(online);showAll();});choice.setSingleLine(true);
            styleSource(choice,onlineSource==online);
            sources.addView(choice,compact?new LinearLayout.LayoutParams(dp(54),dp(48)):new LinearLayout.LayoutParams(0,dp(48),1));
        }
        if(compact){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(48));p.rightMargin=dp(12);header.addView(sources,header.getChildCount()-1,p);}else{LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.bottomMargin=dp(8);content.addView(sources,p);}
        EditText search=new EditText(activity);search.setTextColor(TEXT);search.setHintTextColor(MUTED);search.setTextSize(14);search.setSingleLine(true);search.setHint("搜索滤镜名称或分类");search.setBackground(shape(FIELD,14));search.setPadding(dp(14),0,dp(14),0);
        HorizontalScrollView cats=new HorizontalScrollView(activity);pickerCategories=row();cats.setHorizontalScrollBarEnabled(false);cats.setOverScrollMode(View.OVER_SCROLL_NEVER);cats.addView(pickerCategories);
        if(compact){LinearLayout filters=row();int searchWidth=Math.min(260,Math.max(176,activity.getResources().getConfiguration().screenWidthDp*32/100));filters.addView(search,new LinearLayout.LayoutParams(dp(searchWidth),dp(48)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.leftMargin=dp(12);filters.addView(cats,p);content.addView(filters,new LinearLayout.LayoutParams(-1,dp(48)));}else{content.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));content.addView(cats,new LinearLayout.LayoutParams(-1,dp(48)));}
        pickerStatus=text("",12,MUTED);pickerStatus.setOnClickListener(v->{if(!catalogLoading)loadOnlineCatalog();});if(compact){pickerStatus.setSingleLine(true);pickerStatus.setEllipsize(TextUtils.TruncateAt.END);content.addView(pickerStatus,new LinearLayout.LayoutParams(-1,dp(24)));}else{pickerStatus.setMaxLines(3);pickerStatus.setPadding(0,dp(6),0,dp(6));content.addView(pickerStatus);}refreshPickerMetadata();
        FrameLayout results=new FrameLayout(activity);content.addView(results,new LinearLayout.LayoutParams(-1,0,1));
        grid=new GridView(activity);grid.setNumColumns(3);grid.setHorizontalSpacing(dp(8));grid.setVerticalSpacing(dp(10));grid.setClipToPadding(false);grid.setPadding(0,dp(8),0,dp(12));grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        gridAdapter=new BaseAdapter(){
            private final java.util.List<Entry> values=new ArrayList<>();{filter();}
            private void filter(){values.clear();if(query.isEmpty())values.add(null);for(Entry e:activeEntries())if(included(e))values.add(e);}
            public void notifyDataSetChanged(){filter();super.notifyDataSetChanged();}
            public int getCount(){return values.size();}public Object getItem(int i){return values.get(i);}
            public boolean hasStableIds(){return true;}public long getItemId(int i){Entry entry=values.get(i);return entry==null?0:Long.parseUnsignedLong(entry.id.substring(0,16),16);}
            public View getView(int i,View old,ViewGroup parent){Entry entry=(Entry)getItem(i);if(old instanceof FilterCard&&((FilterCard)old).entry==entry){((FilterCard)old).update();old.post(((FilterCard)old)::requestIfVisible);return old;}return card(entry,true);}
        };
        grid.setAdapter(gridAdapter);results.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout empty=column();empty.setGravity(Gravity.CENTER);TextView noResults=text("没有找到匹配的滤镜",14,MUTED);empty.addView(noResults);empty.addView(button("清空搜索",false,()->search.setText("")));results.addView(empty,new FrameLayout.LayoutParams(-1,-1));grid.setEmptyView(empty);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int d){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString().trim();gridAdapter.notifyDataSetChanged();resetPickerPosition();}public void afterTextChanged(Editable e){}});
        int screenHeight=activity.getResources().getDisplayMetrics().heightPixels;
        int pickerHeight=compact?Math.min(screenHeight,Math.max((int)(screenHeight*.90),dp(322))):(int)(screenHeight*.72);
        showSheet(content,pickerHeight);
        final GridView restoring=grid;final int first=pickerFirst,top=pickerTop;restoring.post(()->{if(grid==restoring){restoring.setSelection(first);restoring.post(()->{if(grid==restoring)restoring.smoothScrollToPositionFromTop(first,top,0);});}});
    }
    private void refreshPickerMetadata(){
        if(pickerCategories==null)return;
        LinkedHashSet<String> all=categoryNames();java.util.List<String> present=new ArrayList<>();for(int i=0;i<pickerCategories.getChildCount();i++)present.add((String)pickerCategories.getChildAt(i).getTag());
        if(!present.equals(new ArrayList<>(all))){pickerCategories.removeAllViews();for(String c:all){Button button=button(c.isEmpty()?"全部":c,false,()->{if(category.equals(c))return;cancelPendingRetry();pendingChoice=null;category=c;buildCategories();buildStrip();refreshPickerMetadata();if(gridAdapter!=null)gridAdapter.notifyDataSetChanged();resetPickerPosition();});button.setTag(c);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(48));p.rightMargin=dp(6);pickerCategories.addView(button,p);}}
        for(int i=0;i<pickerCategories.getChildCount();i++){Button button=(Button)pickerCategories.getChildAt(i);styleCategory(button,category.equals(button.getTag()));}
        if(pickerStatus!=null)updateCatalogStatus(pickerStatus);
        if(pickerTitle!=null)pickerTitle.setText((onlineSource?"在线滤镜":"我的滤镜")+" · "+activeEntries().size());
    }
    private LinearLayout sheet(String label){LinearLayout content=column();content.setPadding(dp(20),dp(8),dp(20),dp(24));content.setBackground(material());content.setClipToOutline(true);View handle=new View(activity);handle.setBackground(shape(0xff657080,2));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(32),dp(3));p.gravity=Gravity.CENTER_HORIZONTAL;p.bottomMargin=dp(8);content.addView(handle,p);LinearLayout row=row();final float[] y={0};row.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)y[0]=e.getY();if(e.getActionMasked()==MotionEvent.ACTION_UP&&e.getY()-y[0]>dp(24)){dismissPopup();return true;}return true;});TextView title=text(label,18,TEXT);title.setTypeface(null,Typeface.BOLD);row.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));Button close=button("",false,this::dismissPopup);close.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(AppIcon.Kind.CLOSE),null,null,null);close.setContentDescription("关闭面板");row.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));content.addView(row);return content;}
    private void showSheet(LinearLayout content,int height){Dialog dialog=new Dialog(activity);popup=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);if(height==-2){ScrollView scroll=new ScrollView(activity);scroll.addView(content);dialog.setContentView(scroll);content.measure(View.MeasureSpec.makeMeasureSpec(activity.getResources().getDisplayMetrics().widthPixels,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));height=Math.min(content.getMeasuredHeight(),(int)(activity.getResources().getDisplayMetrics().heightPixels*.90));}else dialog.setContentView(content);Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setGravity(Gravity.BOTTOM);window.setLayout(-1,height);window.setDimAmount(.35f);if(android.os.Build.VERSION.SDK_INT>=31)window.setBackgroundBlurRadius(dp(24));}dialog.setOnDismissListener(v->{if(popup==dialog)popup=null;if(grid!=null&&query.isEmpty()){pickerFirst=grid.getFirstVisiblePosition();View first=grid.getChildAt(0);pickerTop=first==null?0:first.getTop();}grid=null;gridAdapter=null;pickerCategories=null;pickerStatus=null;pickerTitle=null;query="";});dialog.show();if(window!=null)window.setLayout(-1,height);}
    public boolean dismissPopup(){if(popup==null)return false;Dialog old=popup;popup=null;old.dismiss();return true;}
    private void info(LinearLayout parent,String label,String value){LinearLayout row=row();row.addView(text(label,14,TEXT),new LinearLayout.LayoutParams(0,dp(46),1));row.addView(text(value,14,MUTED));parent.addView(row);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);return v;}private LinearLayout row(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String s,int size,int color){TextView v=new TextView(activity);v.setText(s);v.setTextColor(color);v.setTextSize(size);v.setGravity(Gravity.CENTER_VERTICAL);v.setIncludeFontPadding(false);return v;}
    private Button button(String s,boolean primary,Runnable action){Button b=new Button(activity);b.setText(s);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(primary?0xff07244a:BLUE);b.setStateListAnimator(null);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setPadding(dp(12),0,dp(12),0);b.setBackground(shape(primary?BLUE:Color.TRANSPARENT,24));b.setOnClickListener(v->action.run());return b;}
    private LinearLayout sourceToggle(){LinearLayout group=row();group.setBackground(new InsetDrawable(shape(0x4d10151c,11),0,dp(8),0,dp(8)));group.setPadding(dp(3),0,dp(3),0);return group;}
    private void styleSource(Button control,boolean active){
        control.setTextSize(13);control.setSingleLine(true);control.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));control.setTextColor(active?TEXT:MUTED);control.setSelected(active);
        StateListDrawable states=new StateListDrawable();GradientDrawable focus=shape(active?0x30ffffff:0x14ffffff,9);focus.setStroke(dp(1),BLUE);states.addState(new int[]{android.R.attr.state_focused},new InsetDrawable(focus,dp(2),dp(10),dp(2),dp(10)));states.addState(new int[]{android.R.attr.state_pressed},new InsetDrawable(shape(0x3dffffff,9),dp(2),dp(10),dp(2),dp(10)));states.addState(new int[]{},new InsetDrawable(shape(active?0x30ffffff:Color.TRANSPARENT,9),dp(2),dp(10),dp(2),dp(10)));control.setBackground(states);control.setPadding(dp(8),0,dp(8),0);
    }
    private void styleCategory(Button control,boolean active){
        control.setTextSize(13);control.setSingleLine(true);control.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));control.setTextColor(active?BLUE:MUTED);control.setSelected(active);control.setMinWidth(dp(48));control.setMinimumWidth(dp(48));
        LayerDrawable indicator=new LayerDrawable(new Drawable[]{shape(Color.TRANSPARENT,0),shape(active?BLUE:Color.TRANSPARENT,1)});indicator.setLayerSize(1,dp(18),dp(2));indicator.setLayerGravity(1,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);indicator.setLayerInset(1,0,0,0,dp(5));
        GradientDrawable focus=shape(0x0cffffff,8);focus.setStroke(dp(1),BLUE);StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},new InsetDrawable(focus,0,dp(5),0,dp(5)));states.addState(new int[]{},indicator);control.setBackground(new RippleDrawable(ColorStateList.valueOf(0x228dbbff),states,new InsetDrawable(shape(Color.WHITE,8),0,dp(5),0,dp(5))));control.setPadding(dp(10),0,dp(10),0);
    }
    private EditorGlassDrawable material(){return new EditorGlassDrawable(glass,activity.getResources().getDisplayMetrics().density,26);}
    private AppIcon icon(AppIcon.Kind kind){return new AppIcon(kind,TEXT,activity.getResources().getDisplayMetrics().density);}private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}private int dp(float n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private static String message(Throwable e){return e.getMessage()==null?"暂时无法处理，请重试。":e.getMessage();}
    private static Bitmap render(Bitmap base,PresetPreviewProfile profile){int w=base.getWidth(),h=base.getHeight();int[] pixels=new int[w*h];base.getPixels(pixels,0,w,0,0,w,h);int[] result=profile.render(pixels,w,h);return Bitmap.createBitmap(result,w,h,Bitmap.Config.ARGB_8888);}
    public static Bitmap decode(File file,int edge)throws IOException{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);if(bounds.outWidth<1||bounds.outHeight<1)throw new IOException("原图无法读取。");BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB);options.inScaled=false;options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>edge*2)options.inSampleSize*=2;Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),options);if(bitmap==null)throw new IOException("原图无法解码。");
        int orientation=new ExifInterface(file.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);Matrix matrix=new Matrix();switch(orientation){case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setScale(1,-1);break;case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;case 7:matrix.setRotate(270);matrix.postScale(-1,1);break;case 8:matrix.setRotate(270);break;}if(!matrix.isIdentity())bitmap=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);float scale=Math.min(1,edge/(float)Math.max(bitmap.getWidth(),bitmap.getHeight()));return scale<1?Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true):bitmap;
    }
    /** Runs on the album thumbnail worker. A missing draft never changes a saved version's pixels. */
    public static Bitmap draftThumbnail(Context context,DownloadedPhoto original,String sha,int edge)throws IOException{
        String owner=accountOwner(context);PhotoEditStore.Draft d=new PhotoEditStore(context.getFilesDir(),owner).read(sha);if(d==null){KNOWN.remove(owner+":"+sha);return null;}KNOWN.put(owner+":"+sha,!d.exported);if(d.exported)return null;Bitmap base=decode(original.file(),edge);return d.hasEffect()?render(base,StrengthPreviewProfile.of(PresetPreviewProfiles.fromPreset(d.presetBytes()),d.strength)):base;
    }
    public static String draftBadge(Context context,String sha){return Boolean.TRUE.equals(KNOWN.get(accountOwner(context)+":"+sha))?"未导出":"";}
    @Override public void close(){flushSave();closed=true;generation++;cancelPendingRetry();dismissPopup();io.shutdown();catalogIo.shutdownNow();previews.shutdownNow();thumbnails.shutdownNow();main.removeCallbacksAndMessages(null);}
}

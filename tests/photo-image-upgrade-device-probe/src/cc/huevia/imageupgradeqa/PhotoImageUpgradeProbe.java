package cc.huevia.imageupgradeqa;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.media.ExifInterface;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.json.*;

/** Reflection-only instrumentation of production Views/codecs. No replica screens or real devices. */
public final class PhotoImageUpgradeProbe extends Instrumentation {
    private Bundle args;private ClassLoader loader;private Activity activity;private Object panel,editor;
    private File output;private String stage="start",owner="";private int checks;private Dialog detailDialog,exportDialog;private boolean detailEntryChecked;
    private final JSONObject report=new JSONObject();private final JSONArray assertions=new JSONArray(),cleanup=new JSONArray(),osGuides=new JSONArray();private int priorAccessibilityFlags;
    private final List<File> fixtures=new ArrayList<>(),drafts=new ArrayList<>();private final List<String> sourceHashes=new ArrayList<>();
    private final Map<String,Object> appearance=new HashMap<>();
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args==null?new Bundle():args;start();}
    @Override public void onStart(){Bundle result=new Bundle();boolean passed=false;try{
        check(List.of("ranchu","goldfish").contains(Build.HARDWARE),"emulator-hardware-only");
        android.accessibilityservice.AccessibilityServiceInfo service=getUiAutomation().getServiceInfo();priorAccessibilityFlags=service.flags;service.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;getUiAutomation().setServiceInfo(service);
        Context context=getTargetContext();loader=context.getClassLoader();String installed=sha(new File(context.getApplicationInfo().sourceDir));
        check(installed.equals(args.getString("apkSha256")),"exact-candidate-APK-bytes");
        check(context.getPackageManager().getPackageInfo("cc.huevia",0).getLongVersionCode()==Long.parseLong(args.getString("versionCode")),"candidate-version");
        String mode=args.getString("mode","phone");boolean progressOnly=args.getString("progressOnly","false").equals("true");output=new File(context.getExternalFilesDir(null),"photo-image-upgrade-91/"+mode+(progressOnly?"-progress":""));output.mkdirs();
        report.put("mode",mode).put("apkSha256",installed).put("versionCode",args.getString("versionCode")).put("scope","Production native editor/detail/histogram/export controls on isolated emulator; own JPEG/draft fixtures only; no camera, physical-phone or new MediaStore publication.");
        SharedPreferences pref=context.getSharedPreferences("app-appearance",0);appearance.putAll(pref.getAll());pref.edit().putString("mode","system").commit();
        activity=startActivitySync(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName("cc.huevia","cc.huevia.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        until(()->(Boolean)get(activity,"ready")&&get(activity,"album")!=null,"main-activity-ready");
        int orientation=mode.equals("landscape")?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        ui(()->activity.setRequestedOrientation(orientation));until(()->activity.getResources().getConfiguration().orientation==(mode.equals("landscape")?2:1),"requested-native-orientation");
        report.put("fontScale",activity.getResources().getConfiguration().fontScale).put("density",activity.getResources().getDisplayMetrics().density).put("orientation",activity.getResources().getConfiguration().orientation).put("nightMask",activity.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK);
        check(Math.abs(activity.getResources().getConfiguration().fontScale-(mode.equals("font1.3")?1.3:1))<.01,"actual-font-scale");
        check((activity.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==(mode.equals("dark")?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO),"actual-system-night-mode");
        ui(()->{panel=get(activity,"album");set(activity,"albumTab",true);set(activity,"settingsTab",false);set(panel,"downloadedTab",true);});
        if(progressOnly){stage="isolated-export-progress";Object progressPhoto=fixture(false);progress((File)invoke(progressPhoto,"file"));}
        else{
        stage="real-pixel-detail";Object grid=fixture(true);open(grid);owner=(String)get(editor,"owner");
        showDetails();View canvas=(View)get(detailDialog,"detail");final View gridCanvas=canvas;Object viewport=get(canvas,"viewport");
        check((Boolean)invoke(canvas,"isFitting"),"detail-starts-fit");
        check((Integer)invoke(canvas,"contentWidth")==2401&&(Integer)invoke(canvas,"contentHeight")==1601,"detail-full-source-not-1280-preview");
        ui(()->invoke(gridCanvas,"zoom",new Class<?>[]{double.class},1d));awaitFrame(canvas,"100-percent-ready");
        check(Math.abs((Double)invoke(canvas,"scale")-1)<1e-9,"100-percent-is-one-physical-pixel");
        check((Integer)get(get(canvas,"frame"),"sample")==1,"100-percent-regions-unsampled");
        capture("01-detail-grid-100");pixelProof(canvas,(File)invoke(grid,"file"));
        ui(()->invoke(gridCanvas,"zoom",new Class<?>[]{double.class},2d));awaitFrame(canvas,"200-percent-ready");
        check(Math.abs((Double)invoke(canvas,"scale")-2)<1e-9,"200-percent-scale");
        check(!((RectF)get(canvas,"mini")).isEmpty(),"zoom-has-live-mini-map");
        RectF mini=new RectF((RectF)get(canvas,"mini"));double beforeX=(Double)get(viewport,"centerX");
        ui(()->{long t=SystemClock.uptimeMillis();gridCanvas.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,mini.left+mini.width()*.85f,mini.centerY(),0));gridCanvas.dispatchTouchEvent(MotionEvent.obtain(t,t+30,MotionEvent.ACTION_UP,mini.left+mini.width()*.85f,mini.centerY(),0));});
        awaitFrame(canvas,"mini-map-pan-ready");check((Double)get(viewport,"centerX")>beforeX,"mini-map-repositions-real-viewport");
        ui(()->invoke(gridCanvas,"fit"));awaitFrame(canvas,"fit-again-ready");check((Boolean)invoke(canvas,"isFitting"),"fit-control-restores-fit");
        ui(()->detailDialog.dismiss());
        stage="same-composition";Object photo=fixture(false);File source=(File)invoke(photo,"file");String sourceHash=sha(source);Object draft=makeDraft(source,true);saveDraft(draft);open(photo);
        check((Boolean)get(editor,"hasDraft"),"editor-loads-real-persisted-draft");
        Bitmap editorBitmap=(Bitmap)invoke(editor,"preview");int previewW=editorBitmap.getWidth(),previewH=editorBitmap.getHeight();
        showDetails();canvas=(View)get(detailDialog,"detail");final View detailCanvas=canvas;
        Object geometry=get(draft,"geometry");Object plan=invoke(geometry,"plan",new Class<?>[]{int.class,int.class,int.class},bounds(source).outWidth,bounds(source).outHeight,1);
        int contentW=(Integer)get(plan,"width"),contentH=(Integer)get(plan,"height");
        check((Integer)invoke(canvas,"contentWidth")==contentW&&(Integer)invoke(canvas,"contentHeight")==contentH,"detail-current-matches-unframed-composition");
        check(contentW!=previewW||contentH!=previewH,"detail-is-not-editor-framed-preview");
        ui(()->invoke(detailCanvas,"zoom",new Class<?>[]{double.class},1d));awaitFrame(canvas,"effect-at-100");
        viewport=get(canvas,"viewport");double anchorX=(Double)get(viewport,"centerX"),anchorY=(Double)get(viewport,"centerY");
        Object currentFrame=get(canvas,"frame");ui(()->((View)get(detailDialog,"before")).performClick());awaitMode(canvas,1,"before-registered");
        check((Integer)invoke(canvas,"contentWidth")==contentW&&(Integer)invoke(canvas,"contentHeight")==contentH,"before-same-composition-dimensions");
        check(Math.abs((Double)get(viewport,"centerX")-anchorX)<1e-7&&Math.abs((Double)get(viewport,"centerY")-anchorY)<1e-7&&Math.abs((Double)invoke(canvas,"scale")-1)<1e-9,"before-preserves-anchor-and-scale");
        check(frameDiffers(currentFrame,get(canvas,"frame")),"before-removes-real-manual-colour-effect");capture("02-detail-before");
        ui(()->((View)get(detailDialog,"current")).performClick());awaitMode(canvas,0,"current-restored");capture("03-detail-current");
        stage="same-photo-rebind";Dialog old=detailDialog;ui(()->{invoke(editor,"unbind");invoke(editor,"bind",new Class<?>[]{type("album.DownloadedPhoto")},photo);});
        check(!old.isShowing(),"explicit-same-file-rebind-invalidates-detail-dialog");until(()->(Boolean)invoke(editor,"isReady"),"rebound-editor-ready");
        stage="histogram-editor-overlay";histogram(source,sourceHash);
        stage="export-dialog-validation";exports();
        stage="saved-version";String orphanKey=hex(MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));File baked=new File(source.getParentFile(),"effect-"+orphanKey+"__Effect.JPG");fixtures.add(baked);
        Object renderer=construct("album.PhotoPresetRenderer",new Class<?>[]{android.content.res.AssetManager.class},context.getAssets());
        invoke(renderer,"render",new Class<?>[]{File.class,File.class,type("ncp.PresetPreviewProfile"),BooleanSupplier.class,type("album.PhotoPresetRenderer$Progress")},source,baked,invoke(draft,"profile"),(BooleanSupplier)()->false,null);
        sourceHashes.add(sha(baked));Object saved=construct("album.DownloadedPhoto",new Class<?>[]{File.class,String.class},baked,"SAVED.JPG");
        open(saved);ui(()->invoke(editor,"bindSavedVersion",new Class<?>[]{type("album.DownloadedPhoto")},saved));until(()->(Boolean)invoke(editor,"isReady"),"saved-only-editor-ready");
        check(((File)invoke(get(editor,"bound"),"file")).equals(baked),"saved-file-bound-in-real-workspace");showDetails();
        canvas=(View)get(detailDialog,"detail");check(!((Button)get(detailDialog,"before")).isEnabled(),"saved-file-before-disabled");
        check((Integer)invoke(canvas,"contentWidth")==bounds(baked).outWidth&&(Integer)invoke(canvas,"contentHeight")==bounds(baked).outHeight,"saved-file-whole-JPEG-includes-baked-frame");capture("07-detail-saved-file");ui(()->detailDialog.dismiss());
        stage="scope-callback-guard";scopeGuard(source,draft);check(sha(source).equals(sourceHash),"original-JPEG-SHA-unchanged");
        }
        report.put("status","PASS");passed=true;result.putString("status","PASS");result.putString("stream","Photo image upgrade native PASS; checks="+checks+"\n");
    }catch(Throwable e){while(e instanceof InvocationTargetException)e=((InvocationTargetException)e).getCause();try{report.put("status","FAIL").put("stage",stage).put("failure",e.toString());if(activity!=null)capture("failure");}catch(Exception ignored){}result.putString("status","FAIL");result.putString("stream","Photo image upgrade native FAIL "+stage+": "+e+"\n");}
    finally{try{if(activity!=null)ui(()->{if(detailDialog!=null)detailDialog.dismiss();if(exportDialog!=null)exportDialog.dismiss();if(editor!=null)invoke(editor,"unbind");});
        if(editor!=null){CountDownLatch barrier=new CountDownLatch(1);ui(()->invoke(editor,"afterPendingSaves",new Class<?>[]{Runnable.class},(Runnable)barrier::countDown));barrier.await(10,TimeUnit.SECONDS);}
        for(File draft:drafts){draft.delete();cleanup.put(new JSONObject().put("draft",draft.getPath()).put("absent",!draft.exists()));}
        boolean cleaned=true;for(int i=0;i<fixtures.size();i++){File f=fixtures.get(i);String observed=f.isFile()?sha(f):"absent";String expected=i<sourceHashes.size()?sourceHashes.get(i):observed;f.delete();boolean unchanged=observed.equals(expected),absent=!f.exists();cleaned&=unchanged&&absent;cleanup.put(new JSONObject().put("fixture",f.getPath()).put("shaBeforeDelete",observed).put("unchanged",unchanged).put("absent",absent));}
        SharedPreferences.Editor restore=getTargetContext().getSharedPreferences("app-appearance",0).edit().clear();for(Map.Entry<String,Object> e:appearance.entrySet())putPreference(restore,e.getKey(),e.getValue());restore.commit();
        boolean appearanceRestored=getTargetContext().getSharedPreferences("app-appearance",0).getAll().equals(appearance);if(!cleaned||!appearanceRestored){passed=false;report.put("status","FAIL").put("cleanupError","Fixture cleanup or original/preference integrity failed");result.putString("status","FAIL");}
        android.accessibilityservice.AccessibilityServiceInfo service=getUiAutomation().getServiceInfo();service.flags=priorAccessibilityFlags;getUiAutomation().setServiceInfo(service);
        report.put("checks",checks).put("assertions",assertions).put("cleanup",cleanup).put("appearanceRestored",appearanceRestored).put("osGuideDismissals",osGuides);
        if(output!=null)Files.writeString(new File(output,"report.json").toPath(),report.toString(2));if(activity!=null)ui(()->activity.finish());
    }catch(Exception e){passed=false;result.putString("status","FAIL");result.putString("stream","Photo image upgrade cleanup FAIL "+e+"\n");}}
        finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private Object fixture(boolean grid)throws Exception{
        File dir=new File(getTargetContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),"camera-album");dir.mkdirs();File file=new File(dir,"image-upgrade-qa-"+UUID.randomUUID()+"__"+(grid?"GRID.JPG":"PHOTO.JPG"));fixtures.add(file);
        Bitmap bitmap;if(grid){bitmap=Bitmap.createBitmap(2401,1601,Bitmap.Config.ARGB_8888);int[] row=new int[2401];for(int y=0;y<1601;y++){for(int x=0;x<row.length;x++){int l=((x+y)&1)==0?22:234;row[x]=x<600?0xff000000|(l<<16)|(l<<8)|l:0xff000000|((x*13+y*7)&255)<<16|((x*3+y*11)&255)<<8|((x*17+y*5)&255);}bitmap.setPixels(row,0,row.length,0,y,row.length,1);}}
        else{int id=getTargetContext().getResources().getIdentifier("preset_template","drawable","cc.huevia");BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;Bitmap base=BitmapFactory.decodeResource(getTargetContext().getResources(),id,options);if(base==null)throw new AssertionError("shipping photo fixture resource missing");bitmap=Bitmap.createScaledBitmap(base,2400,1600,true);if(bitmap!=base)base.recycle();}
        bitmap.setDensity(Bitmap.DENSITY_NONE);try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.JPEG,100,out);}bitmap.recycle();ExifInterface exif=new ExifInterface(file.getPath());exif.setAttribute("Orientation","1");exif.setAttribute("ImageDescription","Isolated image-upgrade fixture "+UUID.randomUUID());exif.saveAttributes();sourceHashes.add(sha(file));return construct("album.DownloadedPhoto",new Class<?>[]{File.class,String.class},file,grid?"DETAIL_GRID.JPG":"DSC_DETAIL.JPG");
    }
    private Object makeDraft(File source,boolean effect)throws Exception{
        int[] values=new int[13];if(effect){values[1]=3;values[11]=55;values[12]=70;}Object adjustments=construct("ncp.PhotoAdjustments",new Class<?>[]{int[].class},values);
        Object geometry=construct("ncp.PhotoGeometry",new Class<?>[]{int.class,int.class,int.class,int.class,int.class,int.class},0,13,100000,100000,900000,900000);
        Object border=invoke(type("ncp.PhotoBorder").getField("NONE").get(null),"withTemplate",new Class<?>[]{int.class},2);
        return construct("album.PhotoEditStore$Draft",new Class<?>[]{String.class,byte[].class,String.class,int.class,type("ncp.PhotoAdjustments"),type("ncp.PhotoGeometry"),type("ncp.PhotoBorder"),boolean.class},sha(source),new byte[0],"无滤镜",0,adjustments,geometry,border,false);
    }
    private void saveDraft(Object draft)throws Exception{Object store=construct("album.PhotoEditStore",new Class<?>[]{File.class,String.class},getTargetContext().getFilesDir(),owner);invoke(store,"save",new Class<?>[]{type("album.PhotoEditStore$Draft")},draft);drafts.add(new File((File)get(store,"root"),get(draft,"sourceSha")+".draft"));}
    private void open(Object photo)throws Exception{ui(()->invoke(panel,"openLocalWorkspace",new Class<?>[]{List.class,int.class},List.of(photo),0));until(()->get(panel,"filterEditor")!=null&&(Boolean)invoke(get(panel,"filterEditor"),"isReady"),"native-editor-ready");editor=get(panel,"filterEditor");waitForIdleSync();}
    private void showDetails()throws Exception{
        View entry=activity.getWindow().getDecorView().findViewWithTag("photo-detail-entry");
        if(!detailEntryChecked){check(entry instanceof Button,"real-photo-detail-entry-present");verifyDetailEntry((Button)entry);detailEntryChecked=true;}
        if(entry!=null){boolean[] clicked={false};ui(()->clicked[0]=entry.performClick());check(clicked[0],"actual-photo-detail-entry-performClick-handled");}
        else ui(()->invoke(editor,"showDetails"));
        until(()->findDialog(editor,"PhotoDetailDialog")!=null,"detail-dialog-opens");detailDialog=findDialog(editor,"PhotoDetailDialog");View canvas=(View)get(detailDialog,"detail");awaitFrame(canvas,"detail-first-real-frame");detailSafeInsets();
    }
    private void verifyDetailEntry(Button entry)throws Exception{
        float density=activity.getResources().getDisplayMetrics().density;Rect visible=global(entry);
        check(entry.isEnabled()&&entry.isClickable()&&visible.width()==entry.getWidth()&&visible.height()==entry.getHeight()&&Math.abs(entry.getHeight()-48*density)<=1,"detail-entry-visible-and-48dp-touch-target");
        android.graphics.drawable.Drawable icon=entry.getCompoundDrawables()[0];check(icon!=null&&Math.abs(icon.getBounds().width()-18*density)<=1&&Math.abs(icon.getBounds().height()-18*density)<=1,"detail-entry-has-18dp-visible-content-icon");
        float content=entry.getPaint().measureText(entry.getText().toString())+icon.getBounds().width()+entry.getCompoundDrawablePadding()+entry.getPaddingLeft()+entry.getPaddingRight();
        check(entry.getText().toString().equals("查看细节")&&entry.getWidth()<=Math.ceil(content+6*density)&&entry.getWidth()<=140*density,"detail-entry-wraps-content-without-large-empty-leading-space");
        android.graphics.drawable.Drawable background=entry.getBackground();check(background instanceof android.graphics.drawable.InsetDrawable,"detail-entry-uses-inset-glass-not-white-pill");
        Object glass=((android.graphics.drawable.InsetDrawable)background).getDrawable();check(glass!=null&&glass.getClass().getSimpleName().equals("PhotoGlassDrawable"),"detail-entry-shares-real-photo-glass");
        until(()->(Boolean)invoke(glass,"hasBackdrop"),"detail-entry-real-photo-glass-ready");
        check(get(glass,"source")==get(panel,"viewerImage")&&(Integer)invoke(glass,"sampledPixelCount")>0&&(Integer)invoke(glass,"sampledPixelCount")<=36864,"detail-entry-glass-samples-photo-with-bounded-memory");
        Bitmap rendered=getUiAutomation().takeScreenshot();check(rendered!=null,"detail-entry-native-framebuffer-available");int whiteIconPixels=0;
        for(int y=Math.max(0,visible.top+(entry.getHeight()-icon.getBounds().height())/2-2);y<Math.min(rendered.getHeight(),visible.top+(entry.getHeight()+icon.getBounds().height())/2+2);y++)for(int x=Math.max(0,visible.left+entry.getPaddingLeft());x<Math.min(rendered.getWidth(),visible.left+entry.getPaddingLeft()+icon.getBounds().width()+2);x++){int c=rendered.getPixel(x,y);if(Color.alpha(c)>220&&Color.red(c)>220&&Color.green(c)>220&&Color.blue(c)>220)whiteIconPixels++;}
        rendered.recycle();
        Files.writeString(new File(output,"detail-entry.json").toPath(),viewGeometry(entry).put("text",entry.getText().toString()).put("contentWidth",content).put("whiteIconPixels",whiteIconPixels).put("iconSample","Actual UiAutomation framebuffer in the entry icon region").put("paddingLeft",entry.getPaddingLeft()).put("iconWidth",icon.getBounds().width()).put("iconHeight",icon.getBounds().height()).put("glassVisualHeight",((android.graphics.drawable.Drawable)glass).getBounds().height()).put("glassSamplePixels",invoke(glass,"sampledPixelCount")).toString(2));
        check(whiteIconPixels>8,"detail-entry-light-icon-is-painted-over-dark-glass");
        check(Math.abs(((android.graphics.drawable.Drawable)glass).getBounds().height()-32*density)<=2,"detail-entry-glass-visual-is-compact-32dp-with-48dp-hit-area");
        View photo=(View)get(panel,"viewerImage");RectF photoBounds=(RectF)invoke(photo,"visiblePhotoBounds");int[] photoPosition=new int[2];ui(()->photo.getLocationOnScreen(photoPosition));
        check(Math.abs(visible.left-(photoPosition[0]+photoBounds.left+12*density))<=2&&Math.abs(visible.bottom-(photoPosition[1]+photoBounds.bottom-10*density))<=2,"detail-entry-anchors-to-actual-photo-lower-left");
    }
    private void detailSafeInsets()throws Exception{
        View decor=detailDialog.getWindow().getDecorView(),page=decor.findViewWithTag("detail-page"),header=decor.findViewWithTag("detail-header");
        check(page!=null&&header!=null,"detail-page-and-header-real-views");WindowInsets insets=decor.getRootWindowInsets();android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        Rect bounds=global(header);int[] location=new int[2];ui(()->decor.getLocationOnScreen(location));
        check(bounds.top>=location[1]+safe.top&&bounds.left>=location[0]+safe.left&&bounds.right<=location[0]+decor.getWidth()-safe.right,"detail-header-does-not-intersect-system-bars-or-cutout");
        check(page.getPaddingTop()==safe.top&&page.getPaddingLeft()==safe.left&&page.getPaddingRight()==safe.right&&page.getPaddingBottom()==safe.bottom,"detail-safe-insets-applied-exactly-once");
        check((insets.getSystemWindowInsetTop()==0||bounds.top-location[1]<=safe.top+12*activity.getResources().getDisplayMetrics().density),"detail-no-double-top-padding");
        check((detailDialog.getWindow().getDecorView().getWindowInsetsController().getSystemBarsAppearance()&(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS))==0,"detail-white-system-bar-icons");
        View photo=(View)get(detailDialog,"detail"),stage=decor.findViewWithTag("detail-photo-stage"),palette=decor.findViewWithTag("detail-zoom-palette");
        check(stage!=null&&photo.getWidth()==stage.getWidth()&&photo.getHeight()==stage.getHeight(),"detail-photo-owns-full-safe-stage-without-bottom-panel");
        check(palette!=null&&palette.getWidth()<=Math.round(233*activity.getResources().getDisplayMetrics().density)&&palette.getHeight()<=Math.round(60*activity.getResources().getDisplayMetrics().density),"detail-floating-zoom-palette-holds-only-three-compact-buttons");
        View metric=decor.findViewWithTag("detail-pixel-metric"),compare=decor.findViewWithTag("detail-compare-palette");
        Rect metricBounds=global(metric),compareBounds=global(compare);RectF visiblePhoto=(RectF)invoke(photo,"visiblePhotoBounds");int[] photoLocation=new int[2];ui(()->photo.getLocationOnScreen(photoLocation));
        check(metric.getParent()==stage&&metric.getParent()!=palette,"detail-pixel-metric-is-a-separate-photo-overlay");
        check(metricBounds.top>=photoLocation[1]+visiblePhoto.top&&Math.abs(metricBounds.right-(photoLocation[0]+visiblePhoto.right-12*activity.getResources().getDisplayMetrics().density))<=2,"detail-pixel-metric-anchors-to-photo-top-right");
        check(!Rect.intersects(metricBounds,bounds)&&!Rect.intersects(metricBounds,compareBounds),"detail-pixel-metric-avoids-header-and-comparison");
        Object glass=palette.getBackground();until(()->(Boolean)invoke(glass,"hasBackdrop"),"detail-glass-real-photo-backdrop-ready");
        check(get(glass,"source")==photo&&(Integer)invoke(glass,"sampledPixelCount")>0&&(Integer)invoke(glass,"sampledPixelCount")<=36864,"detail-glass-samples-real-photo-with-bounded-memory");
    }
    private void awaitFrame(Object view,String label)throws Exception{until(()->get(view,"frame")!=null&&!(Boolean)get(view,"loading")&&!(Boolean)get(view,"failed"),label);waitForIdleSync();}
    private void awaitMode(Object view,int mode,String label)throws Exception{until(()->get(view,"frame")!=null&&(Integer)get(get(view,"frame"),"mode")==mode&&!(Boolean)get(view,"loading"),label);waitForIdleSync();}
    private boolean frameDiffers(Object one,Object two)throws Exception{Bitmap a=(Bitmap)get(one,"overview"),b=(Bitmap)get(two,"overview");for(int y=0;y<Math.min(a.getHeight(),b.getHeight());y+=17)for(int x=0;x<Math.min(a.getWidth(),b.getWidth());x+=17)if(a.getPixel(x,y)!=b.getPixel(x,y))return true;return false;}
    private void pixelProof(View canvas,File jpeg)throws Exception{
        BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB);Bitmap oracle=BitmapFactory.decodeFile(jpeg.getPath(),options);
        Object viewport=get(canvas,"viewport"),frame=get(canvas,"frame");List<?> tiles=(List<?>)get(frame,"tiles");JSONArray samples=new JSONArray();int[] pos=new int[2];ui(()->canvas.getLocationOnScreen(pos));Bitmap screen=getUiAutomation().takeScreenshot();
        long left=Math.round((Double)invoke(viewport,"left")),top=Math.round((Double)invoke(viewport,"top"));int errors=0,compared=0,maxDelta=0;
        for(int ly=Math.max(20,canvas.getHeight()/3);ly<Math.min(canvas.getHeight()-40,canvas.getHeight()/3+128);ly+=3)for(int lx=Math.max(20,canvas.getWidth()/3);lx<Math.min(canvas.getWidth()-40,canvas.getWidth()/3+128);lx+=3){
            int sx=(int)(lx-left),sy=(int)(ly-top);if(sx<0||sy<0||sx>=oracle.getWidth()||sy>=oracle.getHeight())continue;
            int expected=oracle.getPixel(sx,sy),actual=screen.getPixel(pos[0]+lx,pos[1]+ly);int d=delta(expected,actual);maxDelta=Math.max(maxDelta,d);compared++;if(d>2)errors++;
            if(samples.length()<24)samples.put(new JSONObject().put("sourceX",sx).put("sourceY",sy).put("screenX",pos[0]+lx).put("screenY",pos[1]+ly).put("expectedRGB",expected&0xffffff).put("actualRGB",actual&0xffffff).put("maxChannelDelta",d));
        }
        int tileErrors=0,tileSamples=0;for(Object t:tiles){Rect r=(Rect)get(t,"bounds");Bitmap b=(Bitmap)get(t,"bitmap");for(int y=0;y<b.getHeight();y+=37)for(int x=0;x<b.getWidth();x+=37){tileSamples++;if(b.getPixel(x,y)!=oracle.getPixel(r.left+x,r.top+y))tileErrors++;}}
        JSONObject proof=new JSONObject().put("oracle","Decoded actual full-resolution fixture JPEG; no-effect production detail, no resource density scaling").put("sourceWidth",oracle.getWidth()).put("sourceHeight",oracle.getHeight()).put("canvasX",pos[0]).put("canvasY",pos[1]).put("canvasWidth",canvas.getWidth()).put("canvasHeight",canvas.getHeight()).put("contentLeft",left).put("contentTop",top).put("scale",1).put("regionSample",get(frame,"sample")).put("framebufferCompared",compared).put("framebufferErrorsOver2",errors).put("framebufferMaxDelta",maxDelta).put("tileCompared",tileSamples).put("tileErrors",tileErrors).put("samples",samples);
        Files.writeString(new File(output,"pixel-mapping.json").toPath(),proof.toString(2));oracle.recycle();screen.recycle();check(tileSamples>100&&tileErrors==0,"region-pixels-identical-to-full-JPEG-source");check(compared>500&&errors==0,"physical-framebuffer-1-to-1-high-frequency-pixels");
    }
    private void histogram(File source,String sha)throws Exception{
        View photo=(View)get(panel,"viewerImage"),dock=(View)get(editor,"dock");Rect initial=global(photo),dockBefore=global(dock);String currentPixels=bitmapSha((Bitmap)get(editor,"current"));String draftBefore=identity();
        File noWarnings=new File(output,"warning-off.JPG"),withWarnings=new File(output,"warning-on.JPG");renderFrozen(source,noWarnings);
        capture("04-editor-histogram-closed");ui(()->invoke(editor,"toggleHistogram"));Object histogram=findFieldObject(editor,"PhotoHistogramPanel");until(()->histogram!=null&&get(histogram,"data")!=null,"histogram-real-preview-statistics");
        Object histogramGlass=get(histogram,"glass");until(()->histogramGlass!=null&&(Boolean)invoke(histogramGlass,"hasBackdrop"),"histogram-glass-real-photo-backdrop-ready");
        check((Integer)invoke(histogramGlass,"sampledPixelCount")>0&&(Integer)invoke(histogramGlass,"sampledPixelCount")<=36864,"histogram-glass-bounded-real-backdrop");
        check(global(photo).equals(initial)&&global(dock).equals(dockBefore),"histogram-overlay-does-not-move-photo-or-tools");
        histogramHeaderUnclipped(histogram,"default");
        if(args.getString("mode","").equals("landscape")){
            View chart=(View)get(histogram,"chart");Rect visible=global(chart);check(chart.getHeight()>0&&visible.height()==chart.getHeight(),"landscape-default-histogram-curve-fully-visible");
            ScrollView histogramScroll=(ScrollView)activity.getWindow().getDecorView().findViewWithTag("editor-histogram-scroll");check(histogramScroll!=null&&histogramScroll.isVerticalScrollBarEnabled(),"landscape-histogram-has-overflow-scroll-affordance");
        }
        ui(()->((View)get(histogram,"rgb")).performClick());check((Boolean)get(histogram,"rgbMode"),"RGB-display-selectable");
        histogramModeLine(histogram,"rgb","luminance","rgb-selected");
        ui(()->((View)get(histogram,"luminance")).performClick());check(!(Boolean)get(histogram,"rgbMode"),"luminance-display-selectable-after-RGB");
        histogramModeLine(histogram,"luminance","rgb","luminance-selected");
        ui(()->((View)get(histogram,"rgb")).performClick());check((Boolean)get(histogram,"rgbMode"),"RGB-display-restored-after-luminance");
        histogramModeLine(histogram,"rgb","luminance","rgb-restored");
        ui(()->{((View)get(histogram,"shadow")).performClick();((View)get(histogram,"highlight")).performClick();});
        check((Boolean)invoke(histogram,"isShadowWarning")&&(Boolean)invoke(histogram,"isHighlightWarning"),"both-preview-warnings-selectable");
        until(()->get(editor,"diagnosticPreview")!=null&&!(Boolean)get(histogram,"updating"),"diagnostic-warning-bitmap-ready");
        histogramHeaderUnclipped(histogram,"warnings-selected");
        check(bitmapSha((Bitmap)get(editor,"current")).equals(currentPixels),"warning-render-keeps-current-effect-pixels-unchanged");check(identity().equals(draftBefore),"diagnostic-preferences-not-draft-identity");check(sha(source).equals(sha),"warnings-do-not-rewrite-source");renderFrozen(source,withWarnings);check(sha(noWarnings).equals(sha(withWarnings)),"preview-warning-choices-do-not-change-encoded-export-bytes");capture("05-editor-histogram-rgb-warnings");
        collapseDuringFinalEdit();check(global(photo).equals(initial)&&global(dock).equals(dockBefore),"closing-histogram-keeps-photo-and-tools");
    }
    private void histogramModeLine(Object histogram,String activeName,String inactiveName,String phase)throws Exception{
        waitForIdleSync();Thread.sleep(180);Bitmap screen=getUiAutomation().takeScreenshot();check(screen!=null,"histogram-mode-line-native-framebuffer-"+phase);
        TextView active=(TextView)get(histogram,activeName),inactive=(TextView)get(histogram,inactiveName);JSONObject activePixels=modeLinePixels(active,screen),inactivePixels=modeLinePixels(inactive,screen);
        JSONObject proof=new JSONObject().put("phase",phase).put("activeName",activeName).put("inactiveName",inactiveName).put("sample","Raw UiAutomation framebuffer; accent243,180,147 tolerance16; physical screen coordinates").put("active",activePixels).put("inactive",inactivePixels);
        Files.writeString(new File(output,"histogram-mode-line-"+phase+".json").toPath(),proof.toString(2));
        try(FileOutputStream out=new FileOutputStream(new File(output,"histogram-mode-line-"+phase+".png"))){screen.compress(Bitmap.CompressFormat.PNG,100,out);}finally{screen.recycle();}
        check(activePixels.getInt("accentPixels")>=Math.max(8,activePixels.getInt("sampleArea")/3),"selected-"+activeName+"-line-is-painted-in-framebuffer-"+phase);
        check(inactivePixels.getInt("accentPixels")<=Math.max(2,inactivePixels.getInt("sampleArea")/20),"unselected-"+inactiveName+"-has-no-line-in-framebuffer-"+phase);
        check(activePixels.getInt("localLineTop")>activePixels.getInt("glyphBottom")&&activePixels.getInt("screenLineBottom")<=activePixels.getInt("globalVisibleBottom"),"mode-line-does-not-touch-text-or-clip-"+phase);
    }
    private JSONObject modeLinePixels(TextView button,Bitmap screen)throws Exception{
        float density=activity.getResources().getDisplayMetrics().density;int[] position=new int[2];ui(()->button.getLocationOnScreen(position));
        int localLeft=(int)Math.ceil(button.getWidth()/2f-Math.round(7*density)),localRight=(int)Math.floor(button.getWidth()/2f+Math.round(7*density));
        int localTop=(int)Math.ceil(button.getHeight()/2f+Math.round(10*density)),localBottom=(int)Math.floor(button.getHeight()/2f+Math.round(12*density));
        Rect pixels=new Rect(position[0]+localLeft,position[1]+localTop,position[0]+localRight,position[1]+localBottom),visible=global(button);int accent=0;
        for(int y=Math.max(0,pixels.top);y<Math.min(screen.getHeight(),pixels.bottom);y++)for(int x=Math.max(0,pixels.left);x<Math.min(screen.getWidth(),pixels.right);x++){int c=screen.getPixel(x,y);if(Color.alpha(c)>230&&Math.abs(Color.red(c)-243)<=16&&Math.abs(Color.green(c)-180)<=16&&Math.abs(Color.blue(c)-147)<=16)accent++;}
        Rect glyph=new Rect();String text=button.getText().toString();button.getPaint().getTextBounds(text,0,text.length(),glyph);
        return viewGeometry(button).put("text",text).put("accentPixels",accent).put("sampleArea",pixels.width()*pixels.height()).put("screenLineLeft",pixels.left).put("screenLineTop",pixels.top).put("screenLineRight",pixels.right).put("screenLineBottom",pixels.bottom).put("localLineTop",localTop).put("localLineBottom",localBottom).put("glyphBottom",button.getBaseline()+glyph.bottom);
    }
    private void histogramHeaderUnclipped(Object histogram,String state)throws Exception{
        for(String name:new String[]{"shadow","highlight"}){
            View label=(View)get(histogram,name);Rect visible=global(label);
            check(label.getWidth()>0&&visible.width()==label.getWidth(),"histogram-"+name+"-header-full-width-"+state);
        }
        JSONArray geometry=new JSONArray();List<String> failures=new ArrayList<>();
        for(String name:new String[]{"luminance","rgb","shadow","highlight"}){
            TextView label=(TextView)get(histogram,name);Rect glyph=new Rect();String text=label.getText().toString();
            label.getPaint().getTextBounds(text,0,text.length(),glyph);
            int inkTop=label.getBaseline()+glyph.top,inkBottom=label.getBaseline()+glyph.bottom;
            int clipTop=label.getExtendedPaddingTop()+label.getScrollY(),clipBottom=label.getHeight()-label.getExtendedPaddingBottom()+label.getScrollY();
            Paint.FontMetricsInt fm=label.getPaint().getFontMetricsInt();android.text.Layout layout=label.getLayout();Rect visible=global(label);int[] screen=new int[2];ui(()->label.getLocationOnScreen(screen));
            JSONObject entry=viewGeometry(label).put("name",name).put("text",text).put("baseline",label.getBaseline()).put("gravity",label.getGravity())
                    .put("fontMetrics",new JSONObject().put("top",fm.top).put("ascent",fm.ascent).put("descent",fm.descent).put("bottom",fm.bottom).put("leading",fm.leading))
                    .put("paddingTop",label.getPaddingTop()).put("paddingBottom",label.getPaddingBottom()).put("compoundPaddingTop",label.getCompoundPaddingTop()).put("compoundPaddingBottom",label.getCompoundPaddingBottom())
                    .put("extendedPaddingTop",label.getExtendedPaddingTop()).put("extendedPaddingBottom",label.getExtendedPaddingBottom()).put("inkTop",inkTop).put("inkBottom",inkBottom).put("clipTop",clipTop).put("clipBottom",clipBottom)
                    .put("layoutLineTop",layout==null?JSONObject.NULL:layout.getLineTop(0)).put("layoutLineBottom",layout==null?JSONObject.NULL:layout.getLineBottom(0)).put("layoutLineBaseline",layout==null?JSONObject.NULL:layout.getLineBaseline(0));
            if(label.getParent() instanceof View){View header=(View)label.getParent();entry.put("parentHeader",viewGeometry(header));if(header.getParent() instanceof View)entry.put("parentScroll",viewGeometry((View)header.getParent()));}
            geometry.put(entry);
            if(!(inkTop>=clipTop-1&&inkBottom<=clipBottom+1))failures.add("histogram-"+name+"-glyphs-within-text-clip-"+state);
            if(!(screen[1]+inkTop>=visible.top-1&&screen[1]+inkBottom<=visible.bottom+1))failures.add("histogram-"+name+"-glyphs-within-ancestor-visible-clip-"+state);
        }
        Files.writeString(new File(output,"histogram-header-"+state+".json").toPath(),geometry.toString(2));
        check(failures.isEmpty(),"histogram-header-full-glyphs-"+state+(failures.isEmpty()?"":":"+failures.get(0)));
    }
    private JSONObject viewGeometry(View view)throws Exception{Rect visible=global(view);int[] screen=new int[2];ui(()->view.getLocationOnScreen(screen));return new JSONObject().put("class",view.getClass().getName()).put("width",view.getWidth()).put("height",view.getHeight()).put("measuredHeight",view.getMeasuredHeight()).put("top",view.getTop()).put("screenTop",screen[1]).put("screenLeft",screen[0]).put("globalVisibleLeft",visible.left).put("globalVisibleTop",visible.top).put("globalVisibleRight",visible.right).put("globalVisibleBottom",visible.bottom).put("globalVisibleWidth",visible.width()).put("globalVisibleHeight",visible.height());}
    private void collapseDuringFinalEdit()throws Exception{
        ThreadPoolExecutor previews=(ThreadPoolExecutor)get(editor,"previews");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        previews.execute(()->{entered.countDown();try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        check(entered.await(10,TimeUnit.SECONDS),"preview-worker-gated-off-main-thread");Bitmap expected=null;
        try{
            Class<?> parameter=type("ncp.PhotoAdjustments$Parameter");Object exposure=Enum.valueOf((Class)parameter,"EXPOSURE");
            check((Integer)invoke(get(editor,"adjustments"),"get",new Class<?>[]{parameter},exposure)==3,"in-flight-edit-starts-from-nonzero-exposure");
            ui(()->invoke(editor,"resetParameter",new Class<?>[]{parameter},exposure));
            check((Integer)invoke(get(editor,"adjustments"),"get",new Class<?>[]{parameter},exposure)==0,"final-editor-parameter-is-zero-exposure");
            ui(()->invoke(editor,"toggleHistogram"));check(!(Boolean)invoke(editor,"isHistogramVisible"),"histogram-closed-while-final-preview-pending");
            expected=(Bitmap)invokeStatic("ui.PhotoFilterEditor","renderFramed",new Class<?>[]{Bitmap.class,type("ncp.PresetPreviewProfile"),type("album.PhotoCaptureInfo"),Context.class},get(editor,"original"),invoke(editor,"currentProfile"),get(editor,"captureInfo"),activity);
            String expectedPixels=bitmapSha(expected);release.countDown();
            until(()->previews.getActiveCount()==0&&previews.getQueue().isEmpty(),"final-preview-executor-drained");waitForIdleSync();
            check(bitmapSha((Bitmap)get(editor,"current")).equals(expectedPixels),"closing-histogram-cannot-drop-final-edit-render");
            check(get(editor,"diagnosticPreview")==null,"closed-histogram-clears-diagnostic-copy");
        }finally{release.countDown();if(expected!=null)expected.recycle();}
    }
    private String identity()throws Exception{Object state=invoke(editor,"snapshot");return invoke(get(state,"manual"),"identity")+":"+invoke(get(state,"geometry"),"identity")+":"+invoke(get(state,"border"),"identity")+":"+get(editor,"selected")+":"+get(editor,"strength");}
    private void renderFrozen(File source,File target)throws Exception{CountDownLatch latch=new CountDownLatch(1);AtomicReference<Object> edit=new AtomicReference<>();ui(()->invoke(editor,"freeze",new Class<?>[]{Consumer.class},(Consumer<Object>)v->{edit.set(v);latch.countDown();}));check(latch.await(20,TimeUnit.SECONDS),"freeze-export-snapshot");Object renderer=construct("album.PhotoPresetRenderer",new Class<?>[]{android.content.res.AssetManager.class},getTargetContext().getAssets());invoke(renderer,"render",new Class<?>[]{File.class,File.class,type("ncp.PresetPreviewProfile"),BooleanSupplier.class,type("album.PhotoPresetRenderer$Progress")},source,target,invoke(get(edit.get(),"draft"),"profile"),(BooleanSupplier)()->false,null);}
    private void exports()throws Exception{
        ui(()->invoke(editor,"showExport"));until(()->findDialog(editor,"PhotoExportDialog")!=null,"unified-export-dialog-opens");exportDialog=findDialog(editor,"PhotoExportDialog");
        awaitAvailability(exportDialog,"default-export-availability-complete");
        View exportRoot=exportDialog.getWindow().getDecorView(),footer=exportRoot.findViewWithTag("export-fixed-footer"),originals=exportRoot.findViewWithTag("export-original-actions"),scrollContent=exportRoot.findViewWithTag("export-scroll-content");
        if(args.getString("mode","").equals("landscape"))check(footer!=null&&footer.getHeight()<=Math.round(73*activity.getResources().getDisplayMetrics().density)&&originals!=null&&originals.getParent()==scrollContent,"landscape-export-keeps-only-primary-in-fixed-footer");
        else check(originals==null||originals.getParent()==footer,"portrait-original-file-actions-stay-in-fixed-footer");
        Object full=invoke(exportDialog,"selected");check(get(full,"mode").toString().equals("FULL")&&(Integer)get(full,"quality")==95,"default-full-JPEG95");
        View[] modes=(View[])get(exportDialog,"modes");ui(()->modes[1].performClick());Object share=invoke(exportDialog,"selected");check(get(share,"mode").toString().equals("SHARE")&&(Integer)get(share,"quality")==90,"share-JPEG90");
        ui(()->modes[2].performClick());EditText edge=(EditText)get(exportDialog,"edgeInput"),quality=(EditText)get(exportDialog,"qualityInput");
        check(edge.getText().toString().equals(Integer.toString(Math.min(4096,(Integer)get(exportDialog,"customMaximum"))))&&quality.getText().toString().equals("90"),"first-custom-defaults");
        Button confirm=(Button)get(exportDialog,"next");for(String invalid:new String[]{"","639","8193","999999999999999999999","abc","12.5"}){ui(()->edge.setText(invalid));check(!confirm.isEnabled(),"invalid-edge-blocks-export:"+invalid);check(confirm.getText().toString().equals("导出照片")&&!(Boolean)get(exportDialog,"availabilityChecking"),"invalid-edge-clears-checking-label:"+invalid);}
        scrollIntoView((View)get(exportDialog,"validation"));capture("08-export-invalid-edge");
        ui(()->edge.setText("1280"));for(String invalid:new String[]{"","69","101","abc"}){ui(()->quality.setText(invalid));check(!confirm.isEnabled(),"invalid-quality-blocks-export:"+invalid);check(confirm.getText().toString().equals("导出照片")&&!(Boolean)get(exportDialog,"availabilityChecking"),"invalid-quality-clears-checking-label:"+invalid);}
        ui(()->quality.setText("90"));awaitAvailability(exportDialog,"custom-export-availability-complete");check(confirm.isEnabled(),"valid-custom-can-export");ui(()->firstScroll(exportDialog.getWindow().getDecorView()).fullScroll(View.FOCUS_UP));capture("06-export-custom");scrollIntoView((View)get(exportDialog,"destinationButton"));capture("09-export-save-location");ui(()->exportDialog.dismiss());
        ui(()->invoke(editor,"showExport"));until(()->findDialog(editor,"PhotoExportDialog")!=null,"export-reopens");exportDialog=findDialog(editor,"PhotoExportDialog");check(get(invoke(exportDialog,"selected"),"mode").toString().equals("FULL"),"reopened-export-defaults-full");ui(()->exportDialog.dismiss());
    }
    private void awaitAvailability(Dialog dialog,String label)throws Exception{until(()->!(Boolean)get(dialog,"availabilityChecking"),label);}
    private void scrollIntoView(View child)throws Exception{ui(()->child.requestRectangleOnScreen(new Rect(0,0,child.getWidth(),child.getHeight()),true));waitForIdleSync();}
    private ScrollView firstScroll(View view){if(view instanceof ScrollView)return (ScrollView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ScrollView found=firstScroll(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
    private void scopeGuard(File source,Object draft)throws Exception{
        AtomicBoolean valid=new AtomicBoolean(true);Object request=construct("album.PhotoDetailSource$Request",new Class<?>[]{Context.class,File.class,String.class,String.class,type("ncp.PresetPreviewProfile"),boolean.class,BooleanSupplier.class},activity,source,sha(source),"owned-scope-"+UUID.randomUUID(),invoke(draft,"profile"),true,(BooleanSupplier)valid::get);
        ui(()->detailDialog=(Dialog)invokeStatic("ui.PhotoDetailDialog","show",new Class<?>[]{Activity.class,type("album.PhotoDetailSource$Request")},activity,request));Object view=get(detailDialog,"detail");awaitFrame(view,"guard-page-loaded");
        valid.set(false);until(()->!detailDialog.isShowing(),"owner-scope-invalidation-closes-page");Object src=get(detailDialog,"source");check((Boolean)get(src,"closed"),"invalid-page-releases-source");
        File dir=(File)get(src,"directory");until(()->!dir.exists(),"closed-source-removes-private-PNGs");
    }
    private void progress(File fixture)throws Exception{
        final File isolated=new File(getTargetContext().getCacheDir(),"image-upgrade-progress-"+UUID.randomUUID());check(isolated.mkdir(),"progress-owned-isolated-context-created");
        Context scoped=new ContextWrapper(getTargetContext()){
            private File directory(String name){File f=new File(isolated,name);if(!f.isDirectory()&&!f.mkdirs())throw new IllegalStateException("QA directory unavailable");return f;}
            @Override public Context getApplicationContext(){return this;}@Override public File getFilesDir(){return directory("files");}@Override public File getCacheDir(){return directory("cache");}@Override public File getExternalFilesDir(String type){return directory("external/"+(type==null?"root":type));}
        };
        Object album=null,batch=null;Dialog progress=null;CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicReference<Object> finalState=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();
        try{
            File directory=new File(scoped.getExternalFilesDir(Environment.DIRECTORY_PICTURES),"camera-album");check(directory.mkdir(),"progress-owned-album-created");List<Object> edits=new ArrayList<>();List<File> inputs=new ArrayList<>();
            Object spec=invokeStatic("album.PhotoExportSpec","share",new Class<?>[0]),target=type("album.PhotoSaveTarget").getField("APP_ONLY").get(null);
            for(int i=1;i<=3;i++){File input=new File(directory,"progress-"+i+".JPG");Files.copy(fixture.toPath(),input.toPath());inputs.add(input);Object photo=construct("album.DownloadedPhoto",new Class<?>[]{File.class,String.class},input,"DSC_"+(9186+i)+".JPG");Object source=invokeStatic("album.PhotoPresetBatch$Source","local",new Class<?>[]{type("album.DownloadedPhoto")},photo);Object edit=invokeStatic("album.PhotoPresetBatch$Edit","saved",new Class<?>[]{type("album.PhotoPresetBatch$Source"),type("album.PhotoExportSpec"),String.class},source,spec,sha(input));edit=invoke(edit,"withAccount",new Class<?>[]{String.class},"");edit=invoke(edit,"withDestination",new Class<?>[]{type("album.PhotoSaveTarget"),boolean.class},target,false);edits.add(edit);}
            album=construct("album.AlbumController",new Class<?>[]{Context.class},scoped);batch=construct("album.PhotoPresetBatch",new Class<?>[]{Context.class,type("album.AlbumController")},scoped,album);final Object task=batch;AtomicBoolean gated=new AtomicBoolean();
            Supplier<String> account=()->{try{Object state=invoke(task,"state");if(Looper.myLooper()!=Looper.getMainLooper()&&(Integer)get(state,"completed")==1&&(Integer)get(state,"currentNumber")==2&&gated.compareAndSet(false,true)){entered.countDown();release.await(45,TimeUnit.SECONDS);}return "";}catch(Exception failure){throw new IllegalStateException(failure);}};
            Class<?> callback=type("album.AlbumController$Callback");Object accepted=Proxy.newProxyInstance(loader,new Class<?>[]{callback},(p,m,a)->{if(m.getName().equals("onError")){error.set(String.valueOf(a[0]));finished.countDown();}return null;});Object terminal=Proxy.newProxyInstance(loader,new Class<?>[]{callback},(p,m,a)->{if(m.getName().equals("onSuccess"))finalState.set(a[0]);if(m.getName().equals("onError"))error.set(String.valueOf(a[0]));finished.countDown();return null;});
            ui(()->invoke(task,"startEdits",new Class<?>[]{List.class,type("album.PhotoSaveTarget"),boolean.class,callback,callback,Supplier.class},edits,target,false,accepted,terminal,account));check(entered.await(40,TimeUnit.SECONDS),"actual-second-job-account-check-gated-on-worker");
            Object state=invoke(task,"state");List<?> items=(List<?>)get(state,"items");check(get(items.get(0),"status").equals("completed")&&get(items.get(1),"status").equals("processing")&&get(items.get(2),"status").equals("waiting"),"progress-rows-come-from-real-completed-processing-waiting-ledger");
            List<?> results=(List<?>)get(state,"results");File retained=(File)invoke(results.get(0),"file");String retainedSha=sha(retained);
            AtomicReference<Dialog> shown=new AtomicReference<>();ui(()->shown.set((Dialog)invokeStatic("ui.PhotoExportProgressDialog","show",new Class<?>[]{Activity.class,type("album.PhotoPresetBatch")},activity,task)));progress=shown.get();capture("10-export-progress");
            ProgressBar bar=(ProgressBar)get(progress,"progress");check(bar.getProgress()==1&&bar.getMax()==3,"processing-progress-counts-only-completed-one-of-three");systemBars(progress,"progress");
            Dialog current=progress;ui(()->((View)get(current,"stop")).performClick());check((Boolean)get(invoke(task,"state"),"cancelRequested"),"progress-stop-requests-safe-cancellation");release.countDown();check(finished.await(40,TimeUnit.SECONDS)&&error.get()==null&&finalState.get()!=null,"stopped-isolated-batch-completes-safely");
            check(retained.isFile()&&sha(retained).equals(retainedSha),"progress-stop-retains-completed-file");for(File input:inputs)check(sha(input).equals(sha(fixture)),"progress-stop-original-input-preserved:"+input.getName());waitForIdleSync();check((Integer)get(finalState.get(),"completed")==1&&(Integer)get(finalState.get(),"cancelled")==2&&(Integer)get(finalState.get(),"total")==3,"real-cancellation-leaves-one-completed-and-two-stopped");check(bar.getProgress()==1&&bar.getMax()==3,"cancelled-progress-not-misreported-as-one-hundred-percent");capture("11-export-progress-stopped");ui(current::dismiss);
        }finally{release.countDown();if(progress!=null){Dialog current=progress;ui(current::dismiss);}if(batch!=null){invoke(batch,"cancel");((ExecutorService)get(batch,"worker")).shutdown();((ExecutorService)get(batch,"worker")).awaitTermination(40,TimeUnit.SECONDS);}if(album!=null)invoke(album,"close");deleteOwnedTree(isolated,isolated);cleanup.put(new JSONObject().put("progressScratch",isolated.getPath()).put("absent",!isolated.exists()));check(!isolated.exists(),"progress-entire-private-scratch-cleaned");}
    }
    private static void deleteOwnedTree(File root,File current)throws Exception{if(!current.getCanonicalPath().equals(root.getCanonicalPath())&&!current.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("QA cleanup escaped own root");File[] children=current.listFiles();if(children!=null)for(File child:children)deleteOwnedTree(root,child);if(current.exists()&&!current.delete())throw new IOException("QA scratch cleanup failed: "+current);}
    private void systemBars(Dialog dialog,String label)throws Exception{
        View decor=dialog.getWindow().getDecorView();check((decor.getWindowInsetsController().getSystemBarsAppearance()&(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS))==0,label+"-white-system-icons");
        Bitmap screenshot=getUiAutomation().takeScreenshot();android.graphics.Insets safe=decor.getRootWindowInsets().getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());int[] pos=new int[2];ui(()->decor.getLocationOnScreen(pos));
        int mismatches=0;for(int x=screenshot.getWidth()/3;x<screenshot.getWidth()*2/3;x+=11){int y=Math.max(0,Math.min(screenshot.getHeight()-1,pos[1]+Math.max(1,safe.top/2)));if(delta(screenshot.getPixel(x,y),0xff141413)>2)mismatches++;}screenshot.recycle();check(mismatches==0,label+"-status-bar-has-opaque-graphite-backdrop");
    }
    private static int delta(int a,int b){return Math.max(Math.abs(((a>>16)&255)-((b>>16)&255)),Math.max(Math.abs(((a>>8)&255)-((b>>8)&255)),Math.abs((a&255)-(b&255))));}
    private static String bitmapSha(Bitmap b)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");int[] row=new int[b.getWidth()];byte[] bytes=new byte[row.length*4];for(int y=0;y<b.getHeight();y++){b.getPixels(row,0,row.length,0,y,row.length,1);for(int x=0;x<row.length;x++){bytes[x*4]=(byte)(row[x]>>>24);bytes[x*4+1]=(byte)(row[x]>>>16);bytes[x*4+2]=(byte)(row[x]>>>8);bytes[x*4+3]=(byte)row[x];}digest.update(bytes);}return hex(digest.digest());}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static void putPreference(SharedPreferences.Editor e,String k,Object v){if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Set)e.putStringSet(k,(Set<String>)v);}
    private Rect global(View v)throws Exception{Rect r=new Rect();ui(()->v.getGlobalVisibleRect(r));return r;}
    private BitmapFactory.Options bounds(File file){BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),b);return b;}
    private Dialog findDialog(Object object,String simple)throws Exception{Object value=findFieldObject(object,simple);return value instanceof Dialog&&((Dialog)value).isShowing()?(Dialog)value:null;}
    private Object findFieldObject(Object object,String simple)throws Exception{for(Field field:object.getClass().getDeclaredFields()){field.setAccessible(true);Object value=field.get(object);if(value!=null&&value.getClass().getSimpleName().equals(simple))return value;}return null;}
    private void dismissOsGuide(String capture)throws Exception{
        for(int attempt=0;attempt<3;attempt++){
            List<android.view.accessibility.AccessibilityNodeInfo> roots=new ArrayList<>();android.view.accessibility.AccessibilityNodeInfo active=getUiAutomation().getRootInActiveWindow();if(active!=null)roots.add(active);
            for(android.view.accessibility.AccessibilityWindowInfo window:getUiAutomation().getWindows()){android.view.accessibility.AccessibilityNodeInfo root=window.getRoot();if(root!=null)roots.add(root);}
            android.view.accessibility.AccessibilityNodeInfo gotIt=null;for(android.view.accessibility.AccessibilityNodeInfo root:roots){gotIt=findOsGotIt(root);if(gotIt!=null)break;}
            if(gotIt==null)return;Rect bounds=new Rect();gotIt.getBoundsInScreen(bounds);boolean clicked=gotIt.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            if(!clicked&&!bounds.isEmpty()){long down=SystemClock.uptimeMillis();sendPointerSync(MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0));sendPointerSync(MotionEvent.obtain(down,down+60,MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0));}
            osGuides.put(new JSONObject().put("capture",capture).put("text",String.valueOf(gotIt.getText())).put("accessibilityClick",clicked).put("bounds",bounds.flattenToString()));waitForIdleSync();Thread.sleep(500);
        }
    }
    private android.view.accessibility.AccessibilityNodeInfo findOsGotIt(android.view.accessibility.AccessibilityNodeInfo node){CharSequence text=node.getText();if(text!=null&&text.toString().trim().equalsIgnoreCase("Got it"))return node;for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo child=node.getChild(i);if(child!=null){android.view.accessibility.AccessibilityNodeInfo found=findOsGotIt(child);if(found!=null)return found;}}return null;}
    private void capture(String name)throws Exception{waitForIdleSync();dismissOsGuide(name);waitForIdleSync();Thread.sleep(180);Bitmap b=getUiAutomation().takeScreenshot();if(b==null)throw new AssertionError("Native screenshot unavailable");File file=new File(output,name+".png");try(FileOutputStream out=new FileOutputStream(file)){b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}String hero=name.equals("04-editor-histogram-closed")?"hero-editor-closed":name.equals("05-editor-histogram-rgb-warnings")?"hero-editor-expanded":name.equals("03-detail-current")?"hero-detail":name.equals("06-export-custom")?"hero-export":null;if(hero!=null)Files.copy(file.toPath(),new File(output,hero+".png").toPath(),StandardCopyOption.REPLACE_EXISTING);}
    private interface Task{void run()throws Exception;}private interface Check{boolean test()throws Exception;}
    private void ui(Task task)throws Exception{AtomicReference<Throwable> error=new AtomicReference<>();runOnMainSync(()->{try{task.run();}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new RuntimeException(error.get());}
    private void until(Check test,String name)throws Exception{for(int i=0;i<400;i++){boolean[] ready={false};ui(()->ready[0]=test.test());if(ready[0]){check(true,name);return;}Thread.sleep(100);}check(false,name+" timeout");}
    private void check(boolean value,String name)throws Exception{checks++;assertions.put(new JSONObject().put("name",name).put("passed",value));if(!value)throw new AssertionError(name);}
    private Class<?> type(String name)throws Exception{return loader.loadClass("cc.huevia."+name);}
    private Object construct(String name,Class<?>[] types,Object... args)throws Exception{Constructor<?> c=type(name).getDeclaredConstructor(types);c.setAccessible(true);return c.newInstance(args);}
    private Object invokeStatic(String name,String method,Class<?>[] types,Object... args)throws Exception{Method m=type(name).getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(null,args);}
    private static Object invoke(Object o,String name)throws Exception{return invoke(o,name,new Class<?>[0]);}
    private static Object invoke(Object o,String name,Class<?>[] types,Object... args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static String sha(File file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
}

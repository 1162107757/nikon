package cc.huevia.editorqa;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import android.view.*;
import android.widget.*;
import android.content.pm.ActivityInfo;
import java.util.ArrayList;

/** Dedicated emulator instrumentation. Uses actual app rendering and current-photo filter Views. */
public final class EditorNativeProbe extends Instrumentation {
    private static final String TARGET="cc.huevia";
    private static final Class<?>[] NONE=new Class<?>[0];
    private final JSONObject report=new JSONObject();private final JSONArray assertions=new JSONArray();
    private ClassLoader loader;private Activity activity;private File output;private int checks;private String stage="startup";
    private Object panel,library,batch,editor;private Bundle args;private Object a,b,c,supported,ncp,unsupported;
    private byte[] npBytes,ncpBytes,onlineManifest;private String owner;private final List<Object> frozen=new ArrayList<>(),corpus=new ArrayList<>();
    @Override public void onCreate(Bundle b){super.onCreate(b);args=b==null?new Bundle():b;start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        check(List.of("ranchu","goldfish").contains(Build.HARDWARE),"dedicated-emulator-only");
        Context context=getTargetContext();loader=context.getClassLoader();
        int expectedVersion=Integer.parseInt(args.getString("version","74"));
        var version=context.getPackageManager().getPackageInfo(TARGET,0);check(version.getLongVersionCode()==expectedVersion,"version-"+expectedVersion);
        String requestedScope=args.getString("scope","full");check(requestedScope.equals("full")||requestedScope.equals("viewer-boundary")||requestedScope.equals("selection-polish")||requestedScope.equals("album-refresh"),"supported-probe-scope");
        String evidenceFolder=requestedScope.equals("album-refresh")?"photo-album-refresh-":requestedScope.equals("viewer-boundary")?"photo-viewer-boundary-":requestedScope.equals("selection-polish")?"photo-selection-polish-":"photo-editor-categories-";
        output=new File(context.getExternalFilesDir(null),evidenceFolder+expectedVersion+"/"+args.getString("mode","phone"));output.mkdirs();Files.deleteIfExists(new File(output,"failure.png").toPath());
        report.put("version",version.versionName).put("versionCode",version.getLongVersionCode()).put("apkSha256",sha(new File(context.getApplicationInfo().sourceDir))).put("scope","Isolated offline emulator, real JPEG/EXIF/MediaStore and existing app-template source. Online UI uses an explicitly seeded validated catalog fixture from existing local recipes; this is not a live production catalog or camera check.");
        activity=startActivitySync(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(TARGET,TARGET+".MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final boolean landscape=args.getString("mode","").equals("landscape");
        ui(()->activity.setRequestedOrientation(landscape?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        until(()->(Boolean)get(activity,"ready")&&get(activity,"album")!=null,"activity-ready");
        until(()->activity.getResources().getConfiguration().orientation==(landscape?android.content.res.Configuration.ORIENTATION_LANDSCAPE:android.content.res.Configuration.ORIENTATION_PORTRAIT)
                &&(landscape?activity.getWindow().getDecorView().getWidth()>activity.getWindow().getDecorView().getHeight():activity.getWindow().getDecorView().getHeight()>activity.getWindow().getDecorView().getWidth()),"actual-configuration-and-window-match-requested-orientation");
        ui(()->recordEnvironment(landscape));
        ui(()->{panel=get(activity,"album");library=get(activity,"library");batch=get(panel,"photoPresets");set(activity,"albumTab",true);set(activity,"settingsTab",false);set(panel,"downloadedTab",true);});
        check(!(Boolean)invoke(get(panel,"controller"),"hasLiveSession"),"offline-editing-no-camera");
        owner=(String)invokeStatic("ui.PhotoFilterEditor","accountOwner",new Class<?>[]{Context.class},context);
        seed();if(requestedScope.equals("album-refresh")){albumRefreshScope();}else if(requestedScope.equals("viewer-boundary")){viewerBoundary();}else if(requestedScope.equals("selection-polish")){onlineCategoriesAndStableCards();selectionPolish();}else{onlineCategoriesAndStableCards();editAndRestore();sheets();exportAndCompare();largeLibraryAndVersions();}report.put("status","PASS");result.putString("status","PASS");result.putString("stream","Photo editor native PASS; checks="+checks+"\n");
    }catch(Throwable e){Throwable cause=unwrap(e);try{report.put("status","FAIL").put("stage",stage).put("failure",cause.toString());if(activity!=null)capture("failure");}catch(Exception ignored){}result.putString("status","FAIL");result.putString("stream","Photo editor native FAIL; "+stage+"; "+cause+"\n");}
    finally{try{report.put("checks",checks).put("assertions",assertions);if(output!=null)Files.writeString(new File(output,"report.json").toPath(),report.toString(2));if(activity!=null)ui(()->activity.finish());}catch(Exception ignored){}}
    finish("PASS".equals(result.getString("status"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);}
    private void seed()throws Exception{
        npBytes=asset("supported.np3");ncpBytes=cc.huevia.ncp.NcpCompatibilityFixtures.ncp();
        supported=get(invoke(library,"importFile",new Class<?>[]{String.class,byte[].class},"冷杉绿调.NP3",npBytes),"record");
        ncp=get(invoke(library,"importFile",new Class<?>[]{String.class,byte[].class},"暖阳胶片.NCP",ncpBytes),"record");
        unsupported=get(invoke(library,"importFile",new Class<?>[]{String.class,byte[].class},"蓝调颗粒.NP3",asset("unsupported.np3")),"record");
        invoke(library,"setCategory",new Class<?>[]{String.class,String.class},get(supported,"id"),"风景");invoke(library,"setCategory",new Class<?>[]{String.class,String.class},get(ncp,"id"),"胶片");
        File dir=new File(getTargetContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),"camera-album");dir.mkdirs();
        for(int i=0;i<56;i++){byte[] fixture=ncpBytes.clone();Arrays.fill(fixture,16,36,(byte)0);byte[] name=("LIBRARY_"+String.format(java.util.Locale.ROOT,"%02d",i)).getBytes(java.nio.charset.StandardCharsets.US_ASCII);System.arraycopy(name,0,fixture,16,name.length);corpus.add(get(invoke(library,"importFile",new Class<?>[]{String.class,byte[].class},"library-"+i+".NCP",fixture),"record"));}
        prepareOnlineFixture();seedOnlineCatalog();
        a=source(dir,"A",1);b=source(dir,"B",1);c=source(dir,"C",6);ui(()->invoke(panel,"refreshDownloads"));until(()->invoke(panel,"photoGroup",new Class<?>[]{type("album.DownloadedPhoto")},a)!=null,"catalog-photo-group-ready");
    }
    private void prepareOnlineFixture()throws Exception{
        JSONArray rows=new JSONArray();List<Object> fixtures=new ArrayList<>(List.of(supported,ncp,unsupported));fixtures.addAll(corpus.subList(0,20));
        String[] categories={"人像","日常","黑白","人像","风景","胶片","黑白","日常","夜景","柔光",""};
        for(int index=0;index<fixtures.size();index++){
            Object record=fixtures.get(index);String format=get(record,"fileFormat").toString(),id=get(record,"id").toString();
            byte[] bytes=(byte[])invoke(library,"readPreset",new Class<?>[]{String.class},id);
            check(sha(bytes).equals(get(record,"sha256")),"seed-recipe-real-sha-"+index);
            rows.put(new JSONObject().put("id","editor-qa-"+index).put("name","QA "+get(record,"displayName"))
                    .put("description","Offline device-probe fixture from existing local source; not production catalog evidence.")
                    .put("fileUrl","https://editor-fixture.invalid/"+id+"."+format.toLowerCase(java.util.Locale.ROOT))
                    .put("sha256",sha(bytes)).put("byteLength",bytes.length).put("fileFormat",format)
                    .put("category",categories[index%categories.length]).put("source","device-qa"));
        }
        onlineManifest=new JSONObject().put("version",1).put("presets",rows).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(new File(output,"qa-catalog-manifest.json").toPath(),onlineManifest);
        report.put("fixtureCatalogSha256",sha(onlineManifest)).put("fixtureCatalogEntries",rows.length())
                .put("fixtureRecipes","Two existing NP3 research sources and hand-authored NCP fixtures already used by the local editor probe; no product download state is fabricated.");
    }
    private File onlineCache(){return new File(getTargetContext().getFilesDir(),"online-presets/catalog.json");}
    private void seedOnlineCatalog()throws Exception{
        seedOnlineCatalog(onlineManifest);
    }
    private void seedOnlineCatalog(byte[] manifest)throws Exception{
        Field managed=type("library.OnlinePresetRepository").getField("MANAGED_CATALOG_URL");String endpoint=(String)managed.get(null);
        JSONObject cache=new JSONObject().put("schemaVersion",1).put("endpoint",endpoint).put("fetchedAt",System.currentTimeMillis())
                .put("manifest",new JSONObject(new String(manifest,java.nio.charset.StandardCharsets.UTF_8)));
        File path=onlineCache();path.getParentFile().mkdirs();Files.writeString(path.toPath(),cache.toString());
    }
    /** Native UI eligibility only. No camera, HTTP request or PTP byte transfer is performed by this fixture. */
    private void albumRefreshScope()throws Exception{
        report.put("scope","Album refresh and export-button UI on isolated offline emulator. Real MotionEvents traverse the actual PullRefreshLayout and downloaded-list refresh worker. Typed synthetic PhotoCameraClient metadata, a real fixture JPEG thumbnail and controlled async page gates exercise loading/failure/retry/end without network. No camera transport, production catalog or physical phone certification.");
        String original=sha((File)invoke(a,"file"));
        open(a);until(()->((View)get(panel,"viewerSave")).getWidth()>0,"compact-export-native-layout-ready");
        ui(()->{
            Button export=(Button)get(panel,"viewerSave");
            check(export.getWidth()==pixelsDp(64)&&export.getHeight()>=pixelsDp(48),"export-button-64-by-at-least-48dp-touch");
            check(export.getBackground() instanceof android.graphics.drawable.InsetDrawable,"export-button-background-is-inset");
            android.graphics.Rect visual=((android.graphics.drawable.InsetDrawable)export.getBackground()).getDrawable().getBounds();
            check(visual.width()==pixelsDp(64)&&visual.height()<=pixelsDp(36)&&visual.height()>=pixelsDp(36)-1,"export-button-64-by-36dp-visual");
            check(export.getLineCount()==1&&export.getLayout()!=null&&export.getLayout().getEllipsisCount(0)==0,"export-caption-single-line-not-ellipsized");
            check(export.getPaint().measureText(export.getText().toString())<=export.getWidth()-export.getPaddingLeft()-export.getPaddingRight(),"export-caption-fits-native-padding");
            android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
            float observedTextPx=export.getTextSize(),expectedTextPx=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,13,metrics);
            report.put("exportTextObservedPx",observedTextPx).put("exportTextExpected13spPx",expectedTextPx).put("exportTextScaledDensity",metrics.scaledDensity);
            check(Math.abs(observedTextPx-expectedTextPx)<.5f,"export-caption-13sp");
            report.put("exportTouchPx",new JSONArray().put(export.getWidth()).put(export.getHeight())).put("exportVisualPx",rectJson(visual));
        });
        capture("compact-export");ui(()->invoke(panel,"goBack"));
        until(()->get(panel,"albumRefresh")!=null&&get(panel,"downloadDateList")!=null&&!(Boolean)get(panel,"loadingDownloads"),"downloaded-pull-wrapper-ready");
        ui(()->((ListView)get(panel,"downloadDateList")).setSelection(0));waitForIdleSync();
        final View wrapper=(View)get(panel,"albumRefresh");final Object list=get(panel,"downloadDateList");
        check((Boolean)invoke(panel,"canPullRefreshAlbum"),"downloaded-list-real-pull-eligibility");
        check(!hasText(activity.getWindow().getDecorView(),"刷新"),"downloaded-heading-has-no-refresh-button");
        int before=((Number)get(panel,"dateRequest")).intValue();
        pullGesture(wrapper,24,false);
        check(((Number)get(panel,"dateRequest")).intValue()==before&&!(Boolean)invoke(wrapper,"isRefreshing"),"below-64dp-pull-does-not-refresh");
        pullGesture(wrapper,120,true);
        check(((Number)get(panel,"dateRequest")).intValue()==before&&!(Boolean)invoke(wrapper,"isRefreshing"),"cancelled-over-threshold-pull-does-not-refresh");
        java.util.concurrent.ExecutorService worker=(java.util.concurrent.ExecutorService)get(get(panel,"controller"),"worker");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        worker.execute(()->{entered.countDown();try{release.await(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        check(entered.await(5,TimeUnit.SECONDS),"local-refresh-real-worker-gated");
        try{
            pullGesture(wrapper,120,false);
            check(((Number)get(panel,"dateRequest")).intValue()==before+1,"over-threshold-release-starts-exactly-one-real-refresh");
            check((Boolean)get(panel,"loadingDownloads")&&(Boolean)invoke(wrapper,"isRefreshing"),"pull-indicator-tracks-pending-real-list-downloads");
            check(get(panel,"downloadDateList")==list,"pending-pull-keeps-current-downloaded-list");
            pullGesture(wrapper,120,false);
            check(((Number)get(panel,"dateRequest")).intValue()==before+1,"second-pull-while-loading-does-not-duplicate-request");
            capture("downloaded-refreshing");
        }finally{release.countDown();}
        until(()->!(Boolean)get(panel,"loadingDownloads")&&get(panel,"albumRefresh")!=null&&!(Boolean)invoke(get(panel,"albumRefresh"),"isRefreshing"),"real-downloads-refresh-completes-and-indicator-stops");
        check(((Number)get(panel,"dateRequest")).intValue()==before+1,"completed-local-refresh-remains-single-request");
        check(invoke(panel,"photoGroup",new Class<?>[]{type("album.DownloadedPhoto")},a)!=null,"real-refreshed-catalog-retains-fixture-photo");
        capture("downloaded-refreshed");
        checkNativeListGestures();
        cameraPageFooterScope();
        check(original.equals(sha((File)invoke(a,"file"))),"album-refresh-preserves-original-jpeg-bytes");
    }
    private void pullGesture(View wrapper,int distanceDp,boolean cancel)throws Exception{
        long down=android.os.SystemClock.uptimeMillis();float x=wrapper.getWidth()*.5f,y=pixelsDp(20);
        sendTouch(wrapper,down,MotionEvent.ACTION_DOWN,x,y);
        for(int step=1;step<=6;step++){Thread.sleep(16);sendTouch(wrapper,down,MotionEvent.ACTION_MOVE,x,y+pixelsDp(distanceDp)*step/6f);}
        sendTouch(wrapper,down,cancel?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP,x,y+pixelsDp(distanceDp));waitForIdleSync();
    }
    private void sendTouch(View target,long down,int action,float x,float y)throws Exception{
        ui(()->{MotionEvent event=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0);try{target.dispatchTouchEvent(event);}finally{event.recycle();}});
    }
    private View firstPhotoTile(View view){
        if(view.isShown()&&view.getClass().getSimpleName().equals("ThumbTile")){android.graphics.Rect rect=new android.graphics.Rect();if(view.getGlobalVisibleRect(rect)&&rect.height()>=pixelsDp(32)&&rect.width()>=pixelsDp(48))return view;}
        if(view instanceof ViewGroup group)for(int index=0;index<group.getChildCount();index++){View found=firstPhotoTile(group.getChildAt(index));if(found!=null)return found;}return null;
    }
    private void checkNativeListGestures()throws Exception{
        until(()->firstPhotoTile((View)get(panel,"downloadDateList"))!=null,"downloaded-photo-tile-visible-for-native-tap");
        final View[] wrapper={null},tile={null};final float[] point=new float[2];
        ui(()->{wrapper[0]=(View)get(panel,"albumRefresh");tile[0]=firstPhotoTile((View)get(panel,"downloadDateList"));visibleTileTouchPoint(wrapper[0],tile[0],point,"tap");});
        int before=((Number)get(panel,"dateRequest")).intValue();long down=android.os.SystemClock.uptimeMillis();sendTouch(wrapper[0],down,MotionEvent.ACTION_DOWN,point[0],point[1]);Thread.sleep(40);sendTouch(wrapper[0],down,MotionEvent.ACTION_UP,point[0],point[1]);
        until(()->get(panel,"screen").toString().equals("DETAIL"),"pull-wrapper-does-not-steal-real-photo-tap");
        check(((Number)get(panel,"dateRequest")).intValue()==before,"native-tap-does-not-refresh-downloads");ui(()->invoke(panel,"goBack"));
        until(()->get(panel,"downloadDateList")!=null&&firstPhotoTile((View)get(panel,"downloadDateList"))!=null,"downloaded-photo-tile-ready-for-native-long-press");
        ui(()->{wrapper[0]=(View)get(panel,"albumRefresh");tile[0]=firstPhotoTile((View)get(panel,"downloadDateList"));visibleTileTouchPoint(wrapper[0],tile[0],point,"long-press");});
        down=android.os.SystemClock.uptimeMillis();sendTouch(wrapper[0],down,MotionEvent.ACTION_DOWN,point[0],point[1]);Thread.sleep(ViewConfiguration.getLongPressTimeout()+180);sendTouch(wrapper[0],down,MotionEvent.ACTION_UP,point[0],point[1]);
        until(()->(Boolean)get(panel,"selectionMode"),"pull-wrapper-does-not-steal-native-long-press-selection");
        check(((Number)get(panel,"dateRequest")).intValue()==before,"native-long-press-does-not-refresh-downloads");
        ui(()->{set(panel,"selectionMode",false);invoke(panel,"clearSelection");invoke(panel,"render");});
    }
    private void visibleTileTouchPoint(View wrapper,View tile,float[] point,String phase)throws Exception{
        android.graphics.Rect visible=new android.graphics.Rect(),wrapperRect=new android.graphics.Rect();
        check(tile!=null&&tile.getGlobalVisibleRect(visible)&&wrapper.getGlobalVisibleRect(wrapperRect)&&visible.intersect(wrapperRect),"native-photo-has-visible-touch-rect-"+phase);
        check(visible.width()>=pixelsDp(48)&&visible.height()>=pixelsDp(32),"native-photo-visible-touch-area-at-least-48-by-32dp-"+phase);
        int[] outer=new int[2];wrapper.getLocationOnScreen(outer);point[0]=visible.exactCenterX()-outer[0];point[1]=visible.exactCenterY()-outer[1];
        report.put("nativePhotoVisibleRectPx-"+phase,rectJson(visible));
    }
    private void cameraPageFooterScope()throws Exception{
        Object controller=get(panel,"controller");String[] controllerFields={"active","cachedConnectionInfo","sessionConnectionInfo","deviceInfo","session","handles"};Object[] prior=new Object[controllerFields.length];for(int i=0;i<prior.length;i++)prior[i]=get(controller,controllerFields[i]);Object priorConnection=get(panel,"connection");
        final List<Object> fixture=new ArrayList<>();final Map<Long,byte[]> metadata=new java.util.LinkedHashMap<>();final long[] handles=new long[48];
        for(int index=0;index<48;index++){handles[index]=820001+index;Object photo=viewerPhoto(handles[index],"QA_PAGE_"+String.format(java.util.Locale.ROOT,"%02d",index)+".JPG");fixture.add(photo);metadata.put(handles[index],(byte[])invoke(invoke(photo,"objectInfo"),"toBytes"));}
        Bitmap thumbnail=(Bitmap)invokeStatic("ui.PhotoFilterEditor","decode",new Class<?>[]{File.class,int.class},invoke(a,"file"),160);java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();thumbnail.compress(Bitmap.CompressFormat.JPEG,85,encoded);thumbnail.recycle();final byte[] jpeg=encoded.toByteArray();
        java.util.concurrent.atomic.AtomicInteger rounds=new java.util.concurrent.atomic.AtomicInteger(),reads=new java.util.concurrent.atomic.AtomicInteger(),originals=new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch[] entered={new CountDownLatch(1),new CountDownLatch(1)},release={new CountDownLatch(1),new CountDownLatch(1)};
        Object client=Proxy.newProxyInstance(loader,new Class<?>[]{type("album.PhotoCameraClient")},(proxy,method,values)->{
            String name=method.getName();if(name.equals("isOpen"))return true;if(name.equals("isProtocolUnconfirmed"))return false;if(name.equals("cameraIdentity"))return "00000000000000000000000000000000";if(name.equals("connectedHost"))return "album-fixture.invalid";if(name.equals("connectedPort"))return 1;if(name.equals("closeReason"))return "Offline album paging fixture";
            if(name.equals("hashCode"))return System.identityHashCode(proxy);if(name.equals("equals"))return proxy==values[0];if(name.equals("toString"))return "Gated synthetic album metadata transport";
            if(name.equals("beginPhotoPage")){int round=rounds.incrementAndGet();if(round>2)throw new java.io.IOException("Unexpected synthetic page round");entered[round-1].countDown();if(!release[round-1].await(25,TimeUnit.SECONDS))throw new java.io.IOException("Synthetic page gate timed out");if(round==1)throw new java.io.IOException("QA synthetic page failure: no network");return null;}
            if(name.equals("getData")){int operation=((Number)values[0]).intValue();if(operation==0x1008){reads.incrementAndGet();byte[] bytes=metadata.get(((long[])values[2])[0]);if(bytes==null)throw new java.io.IOException("Unexpected synthetic handle");return bytes.clone();}if(operation==0x100a)return jpeg.clone();throw new java.io.IOException("Unexpected synthetic operation");}
            if(name.equals("readTo")||name.equals("connect")||name.equals("readAlbumDeviceInfo")){originals.incrementAndGet();throw new java.io.IOException("Fixture cannot connect or download originals");}return null;
        });
        Object connection=construct("album.ConnectionInfo",new Class<?>[]{String.class,String.class,int.class,int.class},"QA synthetic camera","album-fixture.invalid",1,48);
        Object device=invokeStatic("ptp.PtpDeviceInfo","parse",new Class<?>[]{byte[].class},viewerDeviceInfo());Object paging=get(panel,"paging");
        try{
            waitForIdleSync();
            ui(()->{
                check(!(Boolean)get(panel,"downloading")&&!(Boolean)get(panel,"exporting")&&!(Boolean)get(panel,"loadingDownloads"),"camera-fixture-starts-with-no-photo-or-local-refresh-work");
                check(!(Boolean)invoke(panel,"presetBusy")&&!(Boolean)invoke(get(panel,"rawPanel"),"isBusy")&&!(Boolean)invoke(controller,"isRawBusy"),"camera-fixture-starts-with-no-edit-or-raw-task");
                check(!(Boolean)invoke(controller,"isConnecting"),"camera-fixture-controller-is-not-connecting");
                set(panel,"selectionMode",false);invoke(panel,"clearSelection");
                set(controller,"active",client);set(controller,"cachedConnectionInfo",connection);set(controller,"sessionConnectionInfo",connection);set(controller,"deviceInfo",device);set(controller,"session","viewer-boundary-qa");set(controller,"handles",handles);set(panel,"connection",connection);set(panel,"connecting",false);set(panel,"downloadedTab",false);set(panel,"liveTab",false);set(panel,"cameraError","");set(panel,"resetGridPosition",true);
                List<Object> photos=(List<Object>)get(panel,"photos");photos.clear();photos.addAll(fixture.subList(0,24));invoke(paging,"reset",new Class<?>[]{boolean.class},true);Object request=invoke(paging,"begin");invoke(paging,"succeed",new Class<?>[]{type("ui.AlbumPaging$Request"),int.class,boolean.class},request,24,true);invoke(panel,"render");
                check(!(Boolean)get(panel,"selectionMode")&&((Set<?>)get(panel,"selected")).isEmpty()&&((Set<?>)get(panel,"selectedLocal")).isEmpty(),"camera-pagination-fixture-explicitly-exits-multiselect");
                check((Boolean)invoke(panel,"isConnected")&&(Boolean)invoke(controller,"isConnected"),"camera-pagination-fixture-natural-connection-getters-ready");
                check(!(Boolean)invoke(paging,"isLoading")&&!(Boolean)invoke(paging,"isPaused")&&(Boolean)invoke(paging,"hasMore")&&((Number)invoke(paging,"cursor")).intValue()==24,"camera-pagination-fixture-starts-at-unpaused-next-cursor");
            });
            until(()->get(panel,"grid")!=null&&((GridView)get(panel,"grid")).getChildCount()>0&&!(Boolean)get(panel,"restoringGridPosition"),"camera-grid-native-layout-ready");
            final GridView grid=(GridView)get(panel,"grid");final View footer=(View)get(panel,"cameraPageFooter");
            ui(()->{assertFooter(grid,footer,"initial",false);check(((TextView)get(panel,"pageStatus")).getText().toString().equals("上滑加载更多"),"camera-footer-initial-more-label");cameraDiagnostic("before-first-scroll",paging,rounds,entered);});
            touchScrollCameraTo(grid,23,paging,rounds,entered,"first");
            until(()->grid.getLastVisiblePosition()>=23,"camera-scroll-reaches-real-final-seeded-item");
            ui(()->cameraDiagnostic("after-first-scroll",paging,rounds,entered));
            check(entered[0].await(8,TimeUnit.SECONDS),"actual-controller-page-request-enters-first-async-gate");
            until(()->((TextView)get(panel,"pageStatus")).getText().toString().equals("加载中…"),"camera-footer-loading-visible");
            until(()->footer.isShown()&&footer.getHeight()>0&&footer.getBottom()==((View)footer.getParent()).getHeight()-pixelsDp(compactAlbumViewport()?8:96),"loading-footer-native-layout-visible-at-list-bottom");
            ui(()->assertFooter(grid,footer,"loading"));
            check(get(panel,"grid")==grid&&get(panel,"cameraPageFooter")==footer,"page-loading-preserves-grid-and-footer-reference");
            check(((ProgressBar)get(panel,"cameraPageSpinner")).getVisibility()==View.VISIBLE,"camera-footer-spinner-visible-only-during-page-load");capture("camera-loading");
            release[0].countDown();until(()->(Boolean)invoke(paging,"isPaused"),"synthetic-page-failure-pauses-actual-paging");
            check(get(panel,"grid")==grid&&get(panel,"cameraPageFooter")==footer,"page-failure-keeps-original-grid-and-footer");
            check(((TextView)get(panel,"pageStatus")).getText().toString().equals("加载失败 · 轻点重试")&&footer.isClickable(),"camera-footer-failure-retry-label-action");
            ui(()->assertFooter(grid,footer,"failed"));
            check(((ProgressBar)get(panel,"cameraPageSpinner")).getVisibility()==View.GONE,"camera-footer-failure-stops-spinner");capture("camera-failed");
            ui(footer::performClick);check(entered[1].await(8,TimeUnit.SECONDS),"footer-click-retries-actual-controller-page");
            check(get(panel,"grid")==grid&&((ProgressBar)get(panel,"cameraPageSpinner")).getVisibility()==View.VISIBLE,"retry-loading-keeps-grid-and-shows-spinner");
            ui(()->assertFooter(grid,footer,"retry-loading"));
            release[1].countDown();until(()->((List<?>)get(panel,"photos")).size()==48&&!(Boolean)invoke(paging,"isLoading")&&!(Boolean)invoke(paging,"hasMore"),"retry-success-appends-real-metadata-page-to-end");
            check(get(panel,"grid")==grid&&get(panel,"cameraPageFooter")==footer,"successful-append-keeps-original-grid-and-footer");
            check(grid.getAdapter().getCount()==48&&reads.get()==24&&rounds.get()==2,"one-retry-reads-exactly-next-24-metadata-records");
            check(((TextView)get(panel,"pageStatus")).getText().toString().equals("已加载全部照片"),"camera-footer-exhausted-label-readable");
            check(((ProgressBar)get(panel,"cameraPageSpinner")).getVisibility()==View.GONE&&!footer.isClickable(),"camera-footer-end-stops-spinner-and-retry-action");
            ui(()->cameraDiagnostic("before-final-scroll",paging,rounds,entered));
            touchScrollCameraTo(grid,47,paging,rounds,entered,"final");
            until(()->footer.isShown()&&grid.getLastVisiblePosition()>=47,"exhausted-footer-visible-only-at-final-grid-end");
            ui(()->cameraDiagnostic("after-final-scroll",paging,rounds,entered));
            ui(()->assertFooter(grid,footer,"end"));capture("camera-end");check(originals.get()==0,"paging-fixture-performs-no-network-connection-or-original-download");
            report.put("syntheticMetadataReads",reads.get()).put("syntheticPageAttempts",rounds.get()).put("syntheticOriginalRequests",originals.get());
        }catch(Exception|Error failure){
            String failedStage=stage;
            try{ui(()->cameraDiagnostic("failure-before-cleanup",paging,rounds,entered));capture("camera-failure-state");}catch(Exception|Error ignored){}finally{stage=failedStage;}
            throw failure;
        }finally{
            release[0].countDown();release[1].countDown();ui(()->invoke(panel,"cancelCameraBrowse"));CountDownLatch drained=new CountDownLatch(1);((java.util.concurrent.ExecutorService)get(controller,"worker")).execute(drained::countDown);drained.await(10,TimeUnit.SECONDS);
            ui(()->{for(int index=0;index<prior.length;index++)set(controller,controllerFields[index],prior[index]);set(panel,"connection",priorConnection);set(panel,"downloadedTab",true);set(panel,"cameraError","");invoke(panel,"render");});
        }
    }
    private void touchScrollCameraTo(GridView grid,int target,Object paging,java.util.concurrent.atomic.AtomicInteger rounds,CountDownLatch[] entered,String phase)throws Exception{
        for(int attempt=1;attempt<=18;attempt++){
            final boolean[] reached={false};final View[] wrapper={null};final float[] geometry=new float[3];
            ui(()->{
                check(get(panel,"grid")==grid,"native-camera-swipe-retains-grid-"+phase+"-"+target+"-"+((JSONArray)report.opt("cameraDiagnostics")).length());
                reached[0]=grid.getFirstVisiblePosition()<=target&&grid.getLastVisiblePosition()>=target;
                wrapper[0]=(View)get(panel,"albumRefresh");geometry[0]=wrapper[0].getWidth()*.5f;geometry[1]=wrapper[0].getHeight()-pixelsDp(8);geometry[2]=pixelsDp(8);
            });
            if(reached[0])return;
            check(geometry[1]-geometry[2]>ViewConfiguration.get(activity).getScaledTouchSlop(),"native-camera-swipe-has-real-movement-room-"+phase+"-"+attempt);
            long down=android.os.SystemClock.uptimeMillis();sendTouch(wrapper[0],down,MotionEvent.ACTION_DOWN,geometry[0],geometry[1]);
            for(int step=1;step<=8;step++){Thread.sleep(20);sendTouch(wrapper[0],down,MotionEvent.ACTION_MOVE,geometry[0],geometry[1]+(geometry[2]-geometry[1])*step/8f);}
            sendTouch(wrapper[0],down,MotionEvent.ACTION_UP,geometry[0],geometry[2]);waitForIdleSync();Thread.sleep(180);
            final int observedAttempt=attempt;ui(()->cameraDiagnostic(phase+"-native-swipe-"+observedAttempt,paging,rounds,entered));
        }
        ui(()->check(grid.getFirstVisiblePosition()<=target&&grid.getLastVisiblePosition()>=target,"native-camera-swipes-reach-required-item-"+phase+"-"+target));
    }
    private void cameraDiagnostic(String phase,Object paging,java.util.concurrent.atomic.AtomicInteger rounds,CountDownLatch[] entered)throws Exception{
        GridView current=(GridView)get(panel,"grid");
        JSONObject snapshot=new JSONObject().put("phase",phase).put("first",current==null?-1:current.getFirstVisiblePosition()).put("last",current==null?-1:current.getLastVisiblePosition())
                .put("count",current==null||current.getAdapter()==null?-1:current.getAdapter().getCount()).put("gridHeight",current==null?-1:current.getHeight())
                .put("paddingTop",current==null?-1:current.getPaddingTop()).put("paddingBottom",current==null?-1:current.getPaddingBottom())
                .put("inTouchMode",current!=null&&current.isInTouchMode()).put("childCount",current==null?-1:current.getChildCount())
                .put("restoring",get(panel,"restoringGridPosition")).put("selectionMode",get(panel,"selectionMode")).put("downloading",get(panel,"downloading")).put("exporting",get(panel,"exporting"))
                .put("pagingCursor",invoke(paging,"cursor")).put("pagingLoading",invoke(paging,"isLoading")).put("pagingPaused",invoke(paging,"isPaused")).put("pagingMore",invoke(paging,"hasMore"))
                .put("pageAttempts",rounds.get()).put("firstGateRemaining",entered[0].getCount()).put("retryGateRemaining",entered[1].getCount()).put("cameraError",get(panel,"cameraError"));
        JSONArray history=report.optJSONArray("cameraDiagnostics");if(history==null){history=new JSONArray();report.put("cameraDiagnostics",history);}history.put(snapshot);
        android.util.Log.i("EditorNativeQA","camera-diagnostic "+snapshot);
    }
    private void assertFooter(GridView grid,View footer,String phase)throws Exception{
        assertFooter(grid,footer,phase,true);
    }
    private void assertFooter(GridView grid,View footer,String phase,boolean visibleExpected)throws Exception{
        check(footer!=null&&footer.getParent() instanceof FrameLayout,"camera-footer-is-list-stage-overlay-"+phase);
        FrameLayout stage=(FrameLayout)footer.getParent();check(((View)get(panel,"albumRefresh")).getParent()==stage,"camera-footer-shares-list-stage-not-heading-"+phase);
        check(grid.getParent()==get(panel,"albumRefresh"),"camera-footer-stage-contains-current-grid-"+phase);
        FrameLayout.LayoutParams position=(FrameLayout.LayoutParams)footer.getLayoutParams();
        boolean compact=compactAlbumViewport();int bottom=compact?8:96;
        check((position.gravity&Gravity.VERTICAL_GRAVITY_MASK)==Gravity.BOTTOM&&position.bottomMargin==pixelsDp(bottom),"camera-footer-responsive-bottom-anchor-"+phase);
        check((position.gravity&Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)==(compact?Gravity.END:Gravity.CENTER_HORIZONTAL),"camera-footer-responsive-horizontal-anchor-"+phase);
        if(compact)check(position.width==pixelsDp(136)&&position.rightMargin==pixelsDp(12),"compact-camera-footer-136dp-width-and-12dp-end-margin-"+phase);
        if(!visibleExpected){check(footer.getVisibility()==View.GONE&&!footer.isShown(),"camera-footer-hidden-during-middle-browsing-"+phase);return;}
        check(footer.isShown(),"camera-footer-is-visible-list-stage-overlay-"+phase);
        check(footer.getBottom()==stage.getHeight()-position.bottomMargin&&footer.getTop()>=0,"camera-footer-native-bottom-geometry-"+phase);
        android.graphics.Rect visible=new android.graphics.Rect();check(footer.getGlobalVisibleRect(visible)&&visible.height()>=footer.getHeight()-1,"camera-footer-entire-touch-row-visible-"+phase);
        check(footer.getHeight()>=pixelsDp(48)&&grid.getPaddingBottom()>=pixelsDp(compact?100:156),"camera-footer-touch-and-final-row-clearance-"+phase);
        if(compact)check(footer.getRight()==stage.getWidth()-position.rightMargin,"compact-camera-footer-native-end-position-"+phase);
        report.put("footerRectPx-"+phase,rectJson(visible));
    }
    private boolean compactAlbumViewport(){return activity.getResources().getConfiguration().screenHeightDp<500;}
    private void viewerBoundary()throws Exception{
        report.put("scope","Viewer boundary UI only on isolated offline emulator. Real existing template JPEGs and typed synthetic PhotoItem descriptors exercise eligibility. A short-lived synthetic connected flag and a rejecting PhotoCameraClient proxy activate the real preview-error/retry UI; the proxy cannot connect or return bytes. No real camera, remote download, transfer correctness, live production catalog, or phone is certified.");
        report.put("fixtureOriginalSha256",sha((File)invoke(a,"file"))).put("fixtureOriginalBytes",((File)invoke(a,"file")).length());
        Object first=viewerPhoto(780001,"QA_REMOTE_A.JPG"),second=viewerPhoto(780002,"QA_REMOTE_B.JPG");
        Bitmap preview=(Bitmap)invokeStatic("ui.PhotoFilterEditor","decode",new Class<?>[]{File.class,int.class},invoke(b,"file"),640);
        ui(()->{List<Object> photos=(List<Object>)get(panel,"photos");photos.clear();photos.add(first);photos.add(second);((Map<?,?>)get(panel,"savedThisSession")).clear();set(panel,"downloadedTab",false);});
        check(get(panel,"filterEditor")==null,"cold-viewer-has-not-created-editor");
        openRemoteFixture(first,preview);assertPureViewer("cold-remote");
        check(get(panel,"filterEditor")==null,"remote-preview-does-not-create-editor-or-catalog-worker");
        capture("remote-preview");
        View image=(View)get(panel,"viewerImage");
        ui(image::performClick);check(!(Boolean)get(panel,"viewerControlsVisible"),"pure-viewer-photo-tap-hides-controls");
        check(((View)get(panel,"viewerTop")).getVisibility()==View.GONE&&((View)get(panel,"viewerBottom")).getVisibility()==View.GONE,"pure-viewer-chrome-hidden");
        ui(image::performClick);check((Boolean)get(panel,"viewerControlsVisible"),"pure-viewer-photo-tap-restores-controls");
        until(()->!(Boolean)get(panel,"previewLoading"),"offline-preview-request-settled-before-retry-fixture");
        verifyPreviewReasonAndRetry(first);
        open(a);until(()->(Boolean)get(panel,"viewerEditing"),"completed-local-opens-editor");
        editor=get(panel,"filterEditor");
        check(hasText(activity.getWindow().getDecorView(),"滤镜")&&hasText(activity.getWindow().getDecorView(),"强度"),"local-editor-controls-visible");
        until(()->{Object comparison=get(panel,"viewerComparison");return comparison!=null&&"原图".contentEquals(((TextView)invoke(comparison,"getButtonView")).getText());},"comparison-visible-label-is-original");
        capture("local-editor");
        choose(supported,70);Object priorEditor=editor;Bitmap priorPreview=(Bitmap)invoke(priorEditor,"preview");String oldPixels=pixels(priorPreview);
        openRemoteFixture(second,preview);assertPureViewer("after-local-edit");
        check(!(Boolean)invoke(priorEditor,"isReady")&&get(priorEditor,"bound")==null,"return-remote-unbinds-old-editor");
        check(get(priorEditor,"pendingChoice")==null&&get(priorEditor,"pendingRetry")==null,"return-remote-cancels-pending-editor-choice");
        check(get(panel,"viewerPhoto")!=priorPreview,"return-remote-does-not-display-old-editor-bitmap");
        check(!oldPixels.equals(pixels((Bitmap)get(panel,"viewerPhoto"))),"return-remote-pixels-are-current-preview");
        capture("return-remote");
        ui(()->{((Map<Long,Object>)get(panel,"savedThisSession")).put(780001L,a);});
        openRemoteFixture(first,preview);
        until(()->(Boolean)get(panel,"viewerEditing")&&get(panel,"filterEditor")!=null&&(Boolean)invoke(get(panel,"filterEditor"),"isReady"),"camera-source-with-complete-saved-local-opens-editor");
        editor=get(panel,"filterEditor");
        check(get(panel,"remoteDetail")==first&&get(panel,"localDetail")==a,"remote-descriptor-and-complete-local-coexist");
        check((Boolean)invoke(panel,"hasEditableDetail"),"editor-eligibility-does-not-block-camera-source");
        capture("remote-saved-editor");
        openRemoteFixture(second,preview);assertPureViewer("before-live-eligibility-upgrade");
        ui(()->{set(panel,"localDetail",b);invoke(panel,"updateViewerStatus");});
        until(()->(Boolean)get(panel,"viewerEditing")&&(Boolean)invoke(get(panel,"filterEditor"),"isReady"),"update-status-promotes-complete-local-to-editor");
        check(get(panel,"remoteDetail")==second,"eligibility-upgrade-retains-remote-identity");
        ui(()->{set(panel,"localDetail",null);invoke(panel,"updateViewerStatus");});
        until(()->!(Boolean)get(panel,"viewerEditing"),"update-status-demotes-missing-local-to-pure-viewer");
        assertPureViewer("live-eligibility-downgrade");
        File directory=((File)invoke(a,"file")).getParentFile();File empty=new File(directory,"viewer-empty-"+UUID.randomUUID()+".JPG"),partial=new File(directory,"viewer-partial-"+UUID.randomUUID()+".part");
        Files.write(empty.toPath(),new byte[0]);Files.write(partial.toPath(),new byte[]{(byte)255,(byte)216,1,2,3});
        try{
            Object emptyPhoto=construct("album.DownloadedPhoto",new Class<?>[]{File.class,String.class},empty,"QA_EMPTY.JPG");
            Object partialPhoto=construct("album.DownloadedPhoto",new Class<?>[]{File.class,String.class},partial,"QA_PARTIAL.JPG");
            ui(()->{set(panel,"localDetail",emptyPhoto);invoke(panel,"updateViewerStatus");});assertPureViewer("empty-local");
            check(!(Boolean)invoke(panel,"hasEditableDetail"),"empty-jpg-does-not-qualify-for-editor");
            ui(()->{set(panel,"localDetail",partialPhoto);invoke(panel,"updateViewerStatus");});assertPureViewer("partial-local");
            check(!(Boolean)invoke(panel,"hasEditableDetail"),"part-file-does-not-qualify-for-editor");
        }finally{Files.deleteIfExists(empty.toPath());Files.deleteIfExists(partial.toPath());ui(()->{set(panel,"localDetail",null);invoke(panel,"updateViewerStatus");});}
        check(sha((File)invoke(a,"file")).equals(report.getString("fixtureOriginalSha256")),"viewer-boundary-preserves-original-jpeg-bytes");
        ui(()->invoke(panel,"goBack"));check(!"DETAIL".equals(get(panel,"screen").toString()),"pure-viewer-back-returns-to-album");
    }
    private Object viewerPhoto(long handle,String name)throws Exception{
        Object builder=construct("ptp.PtpObjectInfo$Builder",NONE);
        invoke(builder,"storageId",new Class<?>[]{long.class},1L);invoke(builder,"objectFormat",new Class<?>[]{int.class},0x3801);invoke(builder,"filename",new Class<?>[]{String.class},name);invoke(builder,"compressedSize",new Class<?>[]{long.class},((File)invoke(a,"file")).length());
        Object info=invoke(builder,"build");return construct("album.PhotoItem",new Class<?>[]{String.class,long.class,type("ptp.PtpObjectInfo")},"viewer-boundary-qa",handle,info);
    }
    private void openRemoteFixture(Object photo,Bitmap preview)throws Exception{
        ui(()->{String key=(String)invoke(panel,"remoteKey",new Class<?>[]{type("album.PhotoItem")},photo);((LruCache<String,Bitmap>)get(panel,"thumbs")).put(key,preview);invoke(panel,"openRemote",new Class<?>[]{type("album.PhotoItem")},photo);});
        until(()->get(panel,"remoteDetail")==photo&&get(panel,"viewerImage")!=null,"remote-fixture-detail-open");
    }
    private void assertPureViewer(String phase)throws Exception{
        check(!(Boolean)get(panel,"viewerEditing"),"pure-viewer-mode-"+phase);
        View decor=activity.getWindow().getDecorView();
        for(String label:List.of("滤镜","强度","在线","我的"))check(!hasText(decor,label),"pure-viewer-no-"+label+"-"+phase);
        check(get(panel,"viewerTools")==null,"pure-viewer-no-editor-tools-"+phase);
        check(get(panel,"viewerSave") instanceof Button&&((View)get(panel,"viewerSave")).isShown(),"pure-viewer-export-visible-"+phase);
        check(get(panel,"viewerStar") instanceof ImageButton&&((View)get(panel,"viewerStar")).isShown(),"pure-viewer-star-visible-"+phase);
    }
    private void verifyPreviewReasonAndRetry(Object photo)throws Exception{
        Object controller=get(panel,"controller");Object priorClient=get(controller,"active"),priorConnection=get(controller,"cachedConnectionInfo"),priorDevice=get(controller,"deviceInfo"),priorSession=get(controller,"session"),priorPanelConnection=get(panel,"connection");
        java.util.concurrent.atomic.AtomicInteger rejectedReads=new java.util.concurrent.atomic.AtomicInteger();
        Class<?> clientType=type("album.PhotoCameraClient");
        Object client=Proxy.newProxyInstance(loader,new Class<?>[]{clientType},(proxy,method,values)->{
            String name=method.getName();if(name.equals("isOpen"))return true;if(name.equals("isProtocolUnconfirmed"))return false;if(name.equals("cameraIdentity"))return "00000000000000000000000000000000";if(name.equals("connectedHost"))return "viewer-fixture.invalid";if(name.equals("connectedPort"))return 1;if(name.equals("closeReason"))return "Synthetic viewer UI fixture";
            if(name.equals("hashCode"))return System.identityHashCode(proxy);if(name.equals("equals"))return proxy==values[0];if(name.equals("toString"))return "Synthetic rejecting UI transport";
            if(name.equals("getData")||name.equals("getLargeThumbnail")||name.equals("readTo")||name.equals("connect")||name.equals("readAlbumDeviceInfo")){rejectedReads.incrementAndGet();throw new java.io.IOException("QA offline transport: no camera or network bytes");}return null;
        });
        Object connection=construct("album.ConnectionInfo",new Class<?>[]{String.class,String.class,int.class,int.class},"Synthetic viewer UI","viewer-fixture.invalid",1,2);
        Object device=invokeStatic("ptp.PtpDeviceInfo","parse",new Class<?>[]{byte[].class},viewerDeviceInfo());
        try{
            ui(()->{set(controller,"active",client);set(controller,"cachedConnectionInfo",connection);set(controller,"deviceInfo",device);set(controller,"session","viewer-boundary-qa");set(panel,"connection",connection);((LruCache<?,?>)get(panel,"thumbs")).evictAll();set(panel,"detailBitmap",null);set(panel,"previewError","QA preview failed: synthetic offline fixture");set(panel,"previewLoading",false);invoke(panel,"updateDetailPhoto");((View)get(panel,"viewerImage")).performClick();});
            until(()->activeWindowHas("预览未能加载"),"pure-viewer-preview-reason-dialog-visible");
            check(activeWindowHas("QA preview failed: synthetic offline fixture"),"pure-viewer-preview-reason-is-displayed");
            check(activeWindowHas("重试预览"),"pure-viewer-preview-retry-action-visible");
            int request=((Number)get(panel,"previewRequest")).intValue();clickActiveText("重试预览");
            until(()->((Number)get(panel,"previewRequest")).intValue()>request,"pure-viewer-retry-reissues-preview-request");
            until(()->!(Boolean)get(panel,"previewLoading")&&!get(panel,"previewError").toString().isEmpty(),"pure-viewer-retry-failure-keeps-truthful-error");
            check(rejectedReads.get()>0,"synthetic-retry-reaches-rejecting-fixture-transport");
            check(get(panel,"filterEditor")==null,"preview-retry-does-not-create-editor-or-catalog");
        }finally{ui(()->{set(controller,"active",priorClient);set(controller,"cachedConnectionInfo",priorConnection);set(controller,"deviceInfo",priorDevice);set(controller,"session",priorSession);set(panel,"connection",priorPanelConnection);});report.put("syntheticRejectedTransportCalls",rejectedReads.get());}
    }
    private static byte[] viewerDeviceInfo(){
        java.nio.ByteBuffer bytes=java.nio.ByteBuffer.allocate(512).order(java.nio.ByteOrder.LITTLE_ENDIAN);bytes.putShort((short)100).putInt(0).putShort((short)0);viewerPtpString(bytes,"");bytes.putShort((short)0).putInt(1).putShort((short)0x100a);for(int i=0;i<4;i++)bytes.putInt(0);viewerPtpString(bytes,"QA fixture");viewerPtpString(bytes,"Viewer UI only");viewerPtpString(bytes,"1");viewerPtpString(bytes,"");return Arrays.copyOf(bytes.array(),bytes.position());
    }
    private static void viewerPtpString(java.nio.ByteBuffer bytes,String value){if(value.isEmpty()){bytes.put((byte)0);return;}bytes.put((byte)(value.length()+1));for(int i=0;i<value.length();i++)bytes.putChar(value.charAt(i));bytes.putChar((char)0);}
    private boolean activeWindowHas(String text){android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(text.contentEquals(node.getText()))return true;return false;}
    private void clickActiveText(String text)throws Exception{android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();check(root!=null,"preview-dialog-accessibility-root");for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(text.contentEquals(node.getText())&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))return;check(false,"preview-dialog-action-"+text);}
    private void selectionPolish()throws Exception{
        report.put("scope","Selection-surface polish on isolated offline emulator. Runs real existing queue/source/category selection regression, then measures actual native touch bounds, spacing and unchanged category children. Catalog and recipes are explicit validated local fixtures; no live production catalog, camera or physical frame-rate claim.");
        open(a);ui(()->invoke(editor,"setOnlineSource",new Class<?>[]{boolean.class},true));
        until(()->!(Boolean)get(editor,"catalogLoading")&&((List<?>)get(editor,"onlineEntries")).size()==23,"selection-polish-online-catalog-ready");
        until(()->((View)get(editor,"categories")).getHeight()>0,"selection-polish-native-controls-laid-out");
        final LinearLayout categoryRow=(LinearLayout)get(editor,"categories");
        final List<View> categoryChildren=children(categoryRow);final List<int[]> categoryBounds=new ArrayList<>();
        ui(()->{for(View view:categoryChildren)categoryBounds.add(new int[]{view.getLeft(),view.getTop(),view.getRight(),view.getBottom()});});
        Map<?,?> buttons=(Map<?,?>)get(editor,"categoryButtons");check(buttons.containsKey("黑白"),"black-white-category-present-in-explicit-fixture");
        Button portrait=(Button)buttons.get("人像"),blackWhite=(Button)buttons.get("黑白");
        ui(()->portrait.performClick());waitForIdleSync();
        assertCategoryGeometry(categoryRow,categoryChildren,categoryBounds,"portrait");
        capture("selection-portrait");
        ui(()->{ViewParent parent=categoryRow.getParent();if(parent instanceof HorizontalScrollView)((HorizontalScrollView)parent).scrollTo(Math.max(0,blackWhite.getLeft()-Math.round(12*activity.getResources().getDisplayMetrics().density)),0);});
        waitForIdleSync();ui(()->blackWhite.performClick());waitForIdleSync();
        check("黑白".equals(get(editor,"category")),"selection-polish-black-white-active");
        assertCategoryGeometry(categoryRow,categoryChildren,categoryBounds,"black-white");
        ui(()->{
            Button online=(Button)get(editor,"onlineButton"),mine=(Button)get(editor,"mineButton");
            check(online.getLayout()!=null&&online.getLayout().getLineCount()==1&&mine.getLayout()!=null&&mine.getLayout().getLineCount()==1,"source-segment-labels-stay-single-line");
            check(online.getHeight()>=pixelsDp(48)-1&&online.getWidth()>=pixelsDp(48)-1&&mine.getHeight()>=pixelsDp(48)-1&&mine.getWidth()>=pixelsDp(48)-1,"source-segments-retain-48dp-touch-targets");
            android.graphics.Rect sourceTouch=new android.graphics.Rect(),categoriesVisible=new android.graphics.Rect();check(online.getGlobalVisibleRect(sourceTouch)&&categoryRow.getGlobalVisibleRect(categoriesVisible),"source-and-category-controls-visible");
            check(categoriesVisible.top>=sourceTouch.bottom+pixelsDp(6)-1,"source-touch-row-and-category-row-separated-by-six-dp");
            report.put("sourceTouchBounds",rectJson(sourceTouch)).put("categoryVisibleBounds",rectJson(categoriesVisible)).put("selectionFontScale",activity.getResources().getConfiguration().fontScale);
            View track=(View)online.getParent();android.graphics.Outline outline=new android.graphics.Outline();android.graphics.Rect material=new android.graphics.Rect();
            if(track.getBackground()!=null){track.getBackground().getOutline(outline);if(outline.getRect(material)){int[] location=new int[2];track.getLocationOnScreen(location);material.offset(location[0],location[1]);report.put("sourceMaterialOutlineBounds",rectJson(material));check(material.height()<=pixelsDp(36)+1,"source-track-material-visual-height-is-compact");check(categoriesVisible.top-material.bottom>=pixelsDp(12)-1,"source-material-and-category-controls-do-not-crowd");}}
        });
        capture("selection-black-white");
        ui(()->invoke(editor,"showAll"));until(()->get(editor,"grid")!=null&&((GridView)get(editor,"grid")).getChildCount()>0,"selection-polish-expanded-grid-ready");
        GridView expanded=(GridView)get(editor,"grid");
        check(expanded.getHeight()>=expanded.getChildAt(0).getHeight()+expanded.getPaddingTop()+expanded.getPaddingBottom(),"selection-polish-expanded-grid-fits-complete-photo-and-label-row");
        LinearLayout pickerCategories=(LinearLayout)get(editor,"pickerCategories");List<View> pickerChildren=children(pickerCategories);List<int[]> pickerBounds=new ArrayList<>();
        for(View view:pickerChildren)pickerBounds.add(new int[]{view.getLeft(),view.getTop(),view.getRight(),view.getBottom()});
        final View firstCategory=pickerChildren.get(0);ui(firstCategory::performClick);waitForIdleSync();
        // Popup category controls must also keep their geometry while selected styling changes.
        List<View> afterPicker=children((LinearLayout)get(editor,"pickerCategories"));
        check(afterPicker.size()==pickerChildren.size(),"expanded-category-count-stable-on-selection");
        for(int index=0;index<pickerChildren.size();index++){View after=afterPicker.get(index);int[] before=pickerBounds.get(index);check(after==pickerChildren.get(index),"expanded-category-child-stable-"+index);check(after.getLeft()==before[0]&&after.getTop()==before[1]&&after.getRight()==before[2]&&after.getBottom()==before[3],"expanded-category-position-stable-"+index);}
        capture("selection-expanded");ui(()->invoke(editor,"dismissPopup"));
    }
    private void assertCategoryGeometry(LinearLayout row,List<View> expected,List<int[]> bounds,String phase)throws Exception{
        check(row.getChildCount()==expected.size(),"category-count-stable-"+phase);
        for(int index=0;index<expected.size();index++){View actual=row.getChildAt(index);int[] before=bounds.get(index);check(actual==expected.get(index),"category-child-reference-stable-"+phase+"-"+index);check(actual.getLeft()==before[0]&&actual.getTop()==before[1]&&actual.getRight()==before[2]&&actual.getBottom()==before[3],"category-position-stable-"+phase+"-"+index);check(actual.getHeight()>=pixelsDp(48)-1&&actual.getWidth()>=pixelsDp(48)-1,"category-48dp-touch-target-"+phase+"-"+index);if(index>0)check(actual.getLeft()-row.getChildAt(index-1).getRight()>=pixelsDp(6)-1,"category-six-dp-gap-"+phase+"-"+index);}
    }
    private int pixelsDp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private static JSONArray rectJson(android.graphics.Rect rect){return new JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom);}
    private void onlineCategoriesAndStableCards()throws Exception{
        long localCount=((List<?>)invoke(library,"list")).size();open(a);
        check((Boolean)get(editor,"onlineSource"),"editor-default-source-is-online");
        until(()->!(Boolean)get(editor,"catalogLoading")&&((List<?>)get(editor,"onlineEntries")).size()==23,"offline-validated-online-catalog-visible");
        check(get(editor,"catalogMessage").toString().contains("缓存"),"online-offline-cache-warning-visible");
        waitForIdleSync();ui(()->assertStatusReadable((TextView)get(editor,"sourceStatus"),"缓存","source-cached-catalog"));
        Map<?,?> categoryViews=(Map<?,?>)get(editor,"categoryButtons");
        check(categoryViews.keySet().containsAll(List.of("","人像","风景","胶片","黑白","日常","夜景","柔光","未分类")),"online-categories-include-publisher-and-uncategorized-labels");
        ui(()->((Button)((Map<?,?>)get(editor,"categoryButtons")).get("人像")).performClick());
        check("人像".equals(get(editor,"category")),"online-portrait-chip-changes-active-category");
        for(Object card:((Map<?,?>)get(editor,"stripCards")).values()){
            Object e=get(card,"entry");check(e==null||"人像".equals(get(e,"category")),"online-portrait-strip-contains-matching-recipes");
        }
        capture("online-portrait");ui(()->((Button)((Map<?,?>)get(editor,"categoryButtons")).get("")).performClick());
        capture("online-default-categories");

        ThreadPoolExecutor thumbnailWorker=(ThreadPoolExecutor)get(editor,"thumbnails");
        until(()->thumbnailWorker.getActiveCount()==0&&thumbnailWorker.getQueue().isEmpty(),"initial-visible-thumbnail-work-completes");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        thumbnailWorker.execute(()->{entered.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        check(entered.await(3,TimeUnit.SECONDS),"thumbnail-worker-gated-for-real-pending-click");
        final Object[] clicked={null};final View[] clickedView={null};final List<View> before=new ArrayList<>();final List<CardGeometry> beforeGeometry=new ArrayList<>();final int[] scroll={0};
        try{
            ui(()->{
                for(Object e:(List<?>)get(editor,"onlineEntries"))if(get(e,"profile")==null&&get(e,"error").toString().isEmpty()){clicked[0]=e;break;}
                check(clicked[0]!=null,"offscreen-online-recipe-has-not-been-rendered");
                clickedView[0]=(View)((Map<?,?>)get(editor,"stripCards")).get(get(clicked[0],"id"));
                for(Object e:(List<?>)get(editor,"onlineEntries"))if(e!=clicked[0])invoke(editor,"request",new Class<?>[]{e.getClass()},e);
                while(thumbnailWorker.getQueue().remainingCapacity()>0)thumbnailWorker.execute(()->{});
                check(thumbnailWorker.getQueue().remainingCapacity()==0,"thumbnail-queue-deliberately-full-with-real-preview-work");
                HorizontalScrollView viewport=(HorizontalScrollView)get(editor,"filterScroll");viewport.scrollTo(Math.max(1,clickedView[0].getLeft()-Math.round(84*activity.getResources().getDisplayMetrics().density)),0);
            });
            waitForIdleSync();Thread.sleep(150);
            ui(()->{
                LinearLayout strip=(LinearLayout)get(editor,"strip");HorizontalScrollView viewport=(HorizontalScrollView)get(editor,"filterScroll");
                scroll[0]=viewport.getScrollX();for(int i=0;i<strip.getChildCount();i++){View card=strip.getChildAt(i);before.add(card);beforeGeometry.add(new CardGeometry(card));}
                report.put("stripGeometryBeforeClick",geometryJson(beforeGeometry));
                check(scroll[0]>0&&clickedView[0].getLeft()>=scroll[0]&&clickedView[0].getRight()<=scroll[0]+viewport.getWidth(),"pending-thumbnail-target-visible-away-from-first-card");clickedView[0].performClick();
                check(get(editor,"pendingChoice")==clicked[0],"tap-waits-for-the-real-recipe-without-rebuilding-strip");
                checkSameStrip(before,beforeGeometry,scroll[0],"pending-tap");
            });
            capture("online-pending-choice");Thread.sleep(1500);
            ui(()->{checkSameStrip(before,beforeGeometry,scroll[0],"pending-after-next-layout");int retries=((Number)get(editor,"pendingRetryCount")).intValue();check(retries>0&&retries<=4&&get(editor,"pendingRetry")==null,"full-queue-pending-retry-is-bounded");});
        }finally{release.countDown();}
        until(()->get(clicked[0],"id").equals(get(editor,"selected")),"pending-tap-applies-after-async-thumbnail-completion");
        until(()->thumbnailWorker.getActiveCount()==0&&thumbnailWorker.getQueue().isEmpty(),"post-click-visible-thumbnail-callbacks-complete");
        waitForIdleSync();Thread.sleep(200);ui(()->checkSameStrip(before,beforeGeometry,scroll[0],"after-all-thumbnail-callbacks"));
        capture("online-selected-stable");

        List<View> previous=children((LinearLayout)get(editor,"strip"));Object priorEditor=editor;
        open(b);until(()->get(editor,"sourceSha").equals(sha((File)invoke(b,"file"))),"switch-photo-b-binds-correct-source");
        for(View card:children((LinearLayout)get(editor,"strip")))check(!previous.contains(card),"switch-photo-does-not-reuse-previous-photo-card");
        check(editor!=priorEditor||((List<?>)get(editor,"onlineEntries")).stream().noneMatch(e->{try{return e==clicked[0];}catch(Exception failure){throw new RuntimeException(failure);}}),"switch-photo-has-fresh-entry-identities");
        mine();check(((List<?>)get(editor,"entries")).size()==59,"my-source-retains-existing-59-local-recipes");
        check(!((Map<?,?>)get(editor,"categoryButtons")).containsKey("柔光"),"my-categories-come-from-local-library");
        ui(()->((Button)((Map<?,?>)get(editor,"categoryButtons")).get("风景")).performClick());
        check("风景".equals(get(editor,"category")),"my-landscape-category-selects-independently");capture("my-landscape");
        ui(()->((Button)get(editor,"onlineButton")).performClick());
        check("".equals(get(editor,"category")),"source-switch-resets-category-filter");
        until(()->!(Boolean)get(editor,"catalogLoading"),"online-catalog-idle-before-empty-cache-case");
        Files.delete(onlineCache().toPath());ui(()->{
            TextView status=(TextView)get(editor,"sourceStatus");status.performClick();
            check((Boolean)get(editor,"catalogLoading")&&status.isShown()&&status.getText().toString().contains("正在加载"),"catalog-loading-status-visible-before-async-completion");
            report.put("loadingStatusText",status.getText().toString());
        });
        until(()->!(Boolean)get(editor,"catalogLoading")&&((List<?>)get(editor,"onlineEntries")).isEmpty(),"offline-no-catalog-cache-displays-failure");
        waitForIdleSync();ui(()->assertStatusReadable((TextView)get(editor,"sourceStatus"),"重试","source-no-cache-error"));
        check(((Map<?,?>)get(editor,"stripCards")).size()==1,"offline-no-cache-keeps-only-original-card");capture("online-no-cache");
        ui(()->invoke(editor,"showAll"));until(()->get(editor,"pickerStatus")!=null&&((TextView)get(editor,"pickerStatus")).getHeight()>0,"no-cache-picker-status-laid-out");
        waitForIdleSync();ui(()->assertStatusReadable((TextView)get(editor,"pickerStatus"),"重试","picker-no-cache-error"));capture("online-no-cache-picker");
        seedOnlineCatalog();ui(()->((TextView)get(editor,"pickerStatus")).performClick());
        until(()->!(Boolean)get(editor,"catalogLoading")&&((List<?>)get(editor,"onlineEntries")).size()==23,"visible-retry-recovers-validated-cached-categories");
        check(get(editor,"catalogMessage").toString().contains("缓存"),"retry-keeps-clear-offline-cache-warning");
        waitForIdleSync();ui(()->assertStatusReadable((TextView)get(editor,"pickerStatus"),"缓存","picker-retry-cache"));capture("online-retry-cache-picker");
        ui(()->invoke(editor,"dismissPopup"));waitForIdleSync();ui(()->assertStatusReadable((TextView)get(editor,"sourceStatus"),"缓存","source-retry-cache"));
        capture("online-retry-cache");
        byte[] emptyManifest=new JSONObject().put("version",1).put("presets",new JSONArray()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        seedOnlineCatalog(emptyManifest);ui(()->((TextView)get(editor,"sourceStatus")).performClick());
        until(()->!(Boolean)get(editor,"catalogLoading")&&((List<?>)get(editor,"onlineEntries")).isEmpty(),"validated-empty-cached-catalog-loads-zero-recipes");
        waitForIdleSync();ui(()->{
            TextView status=(TextView)get(editor,"sourceStatus");assertStatusReadable(status,"0 款","source-empty-cached-catalog");
            check(((Map<?,?>)get(editor,"stripCards")).size()==1,"validated-empty-catalog-preserves-original-choice");
        });
        capture("online-empty-cache");
        ui(()->invoke(editor,"showAll"));until(()->get(editor,"pickerStatus")!=null&&((TextView)get(editor,"pickerStatus")).getHeight()>0,"empty-catalog-picker-laid-out");
        waitForIdleSync();ui(()->{
            assertStatusReadable((TextView)get(editor,"pickerStatus"),"0 款","picker-empty-cached-catalog");
            check(((BaseAdapter)get(editor,"gridAdapter")).getCount()==1,"validated-empty-catalog-picker-offers-only-original");
        });capture("online-empty-cache-picker");
        seedOnlineCatalog();ui(()->((TextView)get(editor,"pickerStatus")).performClick());
        until(()->!(Boolean)get(editor,"catalogLoading")&&((List<?>)get(editor,"onlineEntries")).size()==23,"empty-catalog-test-restores-23-recipe-fixture");
        ui(()->invoke(editor,"dismissPopup"));
        check(((List<?>)invoke(library,"list")).size()==localCount,"online-browsing-and-applying-do-not-import-local-presets");
        Object landscape=invoke(library,"findBySha256",new Class<?>[]{String.class},get(supported,"sha256"));check("风景".equals(get(landscape,"category")),"online-category-does-not-overwrite-same-sha-local-category");
        ui(()->invoke(panel,"goBack"));
    }
    private static List<View> children(ViewGroup group){List<View> result=new ArrayList<>();for(int index=0;index<group.getChildCount();index++)result.add(group.getChildAt(index));return result;}
    private void recordEnvironment(boolean landscape)throws Exception{
        android.content.res.Configuration configuration=activity.getResources().getConfiguration();
        android.util.DisplayMetrics display=new android.util.DisplayMetrics();activity.getWindowManager().getDefaultDisplay().getRealMetrics(display);
        float expectedFont=args.getString("mode","").equals("large-font")?1.3f:1.0f;
        check(Math.abs(configuration.fontScale-expectedFont)<0.0001f,"actual-configuration-font-scale-matches-mode");
        check(landscape?display.widthPixels>display.heightPixels:display.heightPixels>display.widthPixels,"actual-display-dimensions-match-orientation");
        report.put("mode",args.getString("mode","phone")).put("fontScale",configuration.fontScale).put("orientation",configuration.orientation)
                .put("displayWidthPixels",display.widthPixels).put("displayHeightPixels",display.heightPixels).put("density",display.density)
                .put("screenWidthDp",configuration.screenWidthDp).put("screenHeightDp",configuration.screenHeightDp)
                .put("windowWidthPixels",activity.getWindow().getDecorView().getWidth()).put("windowHeightPixels",activity.getWindow().getDecorView().getHeight());
    }
    private void assertStatusReadable(TextView view,String phrase,String phase)throws Exception{
        android.graphics.Rect visible=new android.graphics.Rect();android.text.Layout layout=view.getLayout();
        check(view.getVisibility()==View.VISIBLE&&view.isShown()&&view.getGlobalVisibleRect(visible)&&visible.width()>=view.getWidth()-1&&visible.height()>=view.getHeight()-1,"catalog-status-entire-view-visible-"+phase);
        check(view.getText().toString().contains(phrase),"catalog-status-required-action-readable-"+phase);
        check(layout!=null&&layout.getLineCount()>0,"catalog-status-has-real-text-layout-"+phase);
        JSONArray ellipses=new JSONArray();for(int line=0;line<layout.getLineCount();line++){int count=layout.getEllipsisCount(line);ellipses.put(count);check(count==0,"catalog-status-zero-ellipsis-"+phase+"-"+line);}
        int last=layout.getLineCount()-1;
        check(layout.getLineEnd(last)>=view.getText().length(),"catalog-status-entire-text-laid-out-"+phase);
        check(layout.getLineBottom(last)-layout.getLineTop(0)<=view.getHeight()-view.getCompoundPaddingTop()-view.getCompoundPaddingBottom(),"catalog-status-entire-text-height-visible-"+phase);
        JSONObject states=report.optJSONObject("catalogStatusLayouts");if(states==null){states=new JSONObject();report.put("catalogStatusLayouts",states);}
        states.put(phase,new JSONObject().put("text",view.getText().toString()).put("visibleRect",rectJson(visible)).put("lineCount",layout.getLineCount()).put("ellipsisCounts",ellipses));
    }
    private static final class CardGeometry{
        final int left,top,width,height;final boolean visible;final String tag;final android.graphics.Rect screen,clipped;
        CardGeometry(View view){left=view.getLeft();top=view.getTop();width=view.getWidth();height=view.getHeight();tag=String.valueOf(view.getTag());int[] position=new int[2];view.getLocationOnScreen(position);screen=new android.graphics.Rect(position[0],position[1],position[0]+width,position[1]+height);clipped=new android.graphics.Rect();visible=view.getGlobalVisibleRect(clipped);if(!visible)clipped.setEmpty();}
        JSONObject json()throws Exception{return new JSONObject().put("tag",tag).put("left",left).put("top",top).put("width",width).put("height",height).put("globalRect",rectJson(screen)).put("visible",visible).put("visibleRect",rectJson(clipped));}
    }
    private static JSONArray geometryJson(List<CardGeometry> values)throws Exception{JSONArray result=new JSONArray();for(CardGeometry value:values)result.put(value.json());return result;}
    private void checkSameStrip(List<View> expected,List<CardGeometry> geometry,int scroll,String phase)throws Exception{
        List<View> actual=children((LinearLayout)get(editor,"strip"));check(actual.size()==expected.size(),"strip-child-count-stable-"+phase);
        List<CardGeometry> observed=new ArrayList<>();
        for(int index=0;index<expected.size();index++){
            View view=actual.get(index);CardGeometry before=geometry.get(index),after=new CardGeometry(view);observed.add(after);
            check(view==expected.get(index),"strip-child-reference-stable-"+phase+"-"+index);
            check(after.left==before.left&&after.top==before.top&&after.width==before.width&&after.height==before.height,"strip-card-local-geometry-stable-"+phase+"-"+index);
            check(after.screen.equals(before.screen),"strip-card-global-rect-stable-"+phase+"-"+index);
            check(after.visible==before.visible&&after.clipped.equals(before.clipped),"strip-card-visible-rect-stable-"+phase+"-"+index);
        }
        check(((HorizontalScrollView)get(editor,"filterScroll")).getScrollX()==scroll,"strip-scroll-position-stable-"+phase);
        JSONObject phases=report.optJSONObject("stripGeometryAfterClick");if(phases==null){phases=new JSONObject();report.put("stripGeometryAfterClick",phases);}phases.put(phase,geometryJson(observed));
    }
    private Object source(File dir,String name,int orientation)throws Exception{
        File f=new File(dir,"editor-"+UUID.randomUUID().toString().replace("-","")+"__"+name+".JPG");
        Bitmap image=BitmapFactory.decodeStream(getContext().getAssets().open("photo.png"));
        try(FileOutputStream out=new FileOutputStream(f)){image.compress(Bitmap.CompressFormat.JPEG,96,out);}finally{image.recycle();}
        ExifInterface ex=new ExifInterface(f.getPath());ex.setAttribute("Make","Huevia editor QA");ex.setAttribute("Model",name+UUID.randomUUID());ex.setAttribute("Orientation",""+orientation);ex.setAttribute("ColorSpace","1");ex.saveAttributes();
        return construct("album.DownloadedPhoto",new Class<?>[]{File.class,String.class},f,"DSC_000"+name+".JPG");
    }
    private void open(Object photo)throws Exception{ui(()->invoke(panel,"openLocal",new Class<?>[]{type("album.DownloadedPhoto")},photo));until(()->get(panel,"filterEditor")!=null&&(Boolean)invoke(get(panel,"filterEditor"),"isReady"),"current-photo-editor-ready");editor=get(panel,"filterEditor");}
    private Object entry(Object preset)throws Exception{for(Object e:(List<?>)get(editor,"entries"))if(get(get(e,"preset"),"id").equals(get(preset,"id")))return e;throw new IllegalStateException("missing entry");}
    private void mine()throws Exception{ui(()->((Button)get(editor,"mineButton")).performClick());check(!(Boolean)get(editor,"onlineSource"),"explicit-my-source-for-local-recipes");}
    private void choose(Object preset,int amount)throws Exception{
        mine();
        Object e=entry(preset);ui(()->invoke(editor,"request",new Class<?>[]{e.getClass()},e));until(()->get(e,"profile")!=null,"real-current-photo-filter-thumbnail-ready");
        ui(()->invoke(editor,"choose",new Class<?>[]{e.getClass()},e));SeekBar slider=(SeekBar)get(editor,"slider");
        ui(()->{slider.requestFocus();slider.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));});
        // Keyboard produces a fromUser event; controlled unit steps reach the target exactly.
        ui(()->{int old=slider.getProgress();slider.setKeyProgressIncrement(1);for(int n=old;n>amount;n--)slider.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT));});
        until(()->((Number)get(editor,"strength")).intValue()==amount,"user-strength-"+amount);
        barrier();until(()->pixels((Bitmap)invoke(editor,"preview")).equals(expectedPreview(preset,amount)),"preview-shared-model-strength-"+amount);
    }
    private String expectedPreview(Object preset,int amount)throws Exception{Bitmap base=(Bitmap)invoke(editor,"original");Object profile=invokeStatic("ncp.PresetPreviewProfiles","fromPreset",new Class<?>[]{byte[].class},preset==supported?npBytes:ncpBytes);profile=invokeStatic("ncp.StrengthPreviewProfile","of",new Class<?>[]{type("ncp.PresetPreviewProfile"),int.class},profile,amount);int[] p=new int[base.getWidth()*base.getHeight()];base.getPixels(p,0,base.getWidth(),0,0,base.getWidth(),base.getHeight());int[] q=(int[])profile.getClass().getMethod("render",int[].class,int.class,int.class).invoke(profile,p,base.getWidth(),base.getHeight());Bitmap rendered=Bitmap.createBitmap(q,base.getWidth(),base.getHeight(),Bitmap.Config.ARGB_8888);try{return pixels(rendered);}finally{rendered.recycle();}}
    private void barrier()throws Exception{CountDownLatch done=new CountDownLatch(1);ui(()->invoke(editor,"afterPendingSaves",new Class<?>[]{Runnable.class},(Runnable)done::countDown));check(done.await(10,TimeUnit.SECONDS),"draft-save-barrier");}
    private long effects()throws Exception{File dir=((File)invoke(a,"file")).getParentFile();return Arrays.stream(dir.listFiles()).filter(f->f.getName().startsWith("effect-")).count();}
    private void editAndRestore()throws Exception{
        long before=effects();open(a);choose(supported,70);ui(()->invoke(panel,"goBack"));open(b);choose(ncp,40);ui(()->invoke(panel,"goBack"));open(c);
        ui(()->invoke(editor,"choose",new Class<?>[]{type("ui.PhotoFilterEditor$Entry")},(Object)null));barrier();
        check(effects()==before,"preview-and-strength-do-not-export-private-effects");
        ui(()->{invoke(editor,"bind",new Class<?>[]{type("album.DownloadedPhoto")},a);invoke(editor,"bind",new Class<?>[]{type("album.DownloadedPhoto")},b);invoke(editor,"bind",new Class<?>[]{type("album.DownloadedPhoto")},a);});
        open(a);until(()->get(editor,"sourceSha").equals(sha((File)invoke(a,"file")))&&((Number)get(editor,"strength")).intValue()==70,"rapid-switch-restores-exact-photo-A-draft");
        check(pixels((Bitmap)invoke(editor,"preview")).equals(expectedPreview(supported,70)),"rapid-switch-cannot-overwrite-current-photo");capture("editor");
        Object group=invoke(panel,"photoGroup",new Class<?>[]{type("album.DownloadedPhoto")},a);check(((List<?>)get(group,"versions")).size()==1,"draft-photo-has-only-original-before-export");TextView badge=new TextView(activity);ui(()->invoke(panel,"updateVersionBadge",new Class<?>[]{TextView.class,type("album.DownloadedPhoto")},badge,a));check(badge.getVisibility()==View.VISIBLE&&badge.getText().toString().equals("未导出"),"single-original-draft-badge-visible");
        ui(()->invoke(panel,"goBack"));until(()->hasText(activity.getWindow().getDecorView(),"未导出"),"album-draft-badge-actually-visible");capture("draft-badge");open(b);check(((Number)get(editor,"strength")).intValue()==40,"photo-B-retains-independent-strength");ui(()->invoke(panel,"goBack"));open(a);
        Object store=construct("album.PhotoEditStore",new Class<?>[]{File.class,String.class},getTargetContext().getFilesDir(),owner);
        for(Object photo:List.of(a,b,c)){String sha=sha((File)invoke(photo,"file"));Object draft=invoke(store,"read",new Class<?>[]{String.class},sha);check(draft!=null,"per-photo-draft-survives-detail-exit");Object src=invokeStatic("album.PhotoPresetBatch$Source","local",new Class<?>[]{type("album.DownloadedPhoto")},photo);frozen.add(invokeStatic("album.PhotoPresetBatch$Edit","draft",new Class<?>[]{type("album.PhotoPresetBatch$Source"),type("album.PhotoEditStore$Draft"),String.class},src,draft,owner));}
    }
    private void sheets()throws Exception{
        mine();
        ui(()->invoke(editor,"showAll"));until(()->get(editor,"grid")!=null,"expanded-three-column-current-photo-grid");Thread.sleep(300);GridView visibleGrid=(GridView)get(editor,"grid");check(visibleGrid.getChildCount()>0&&visibleGrid.getHeight()>=visibleGrid.getChildAt(0).getHeight()+visibleGrid.getPaddingTop()+visibleGrid.getPaddingBottom(),"picker-fits-complete-photo-and-label-row");capture("all-filters");
        DialogSearch: {android.app.Dialog d=(android.app.Dialog)get(editor,"popup");EditText search=find(d.getWindow().getDecorView(),EditText.class);ui(()->search.setText("NO_MATCH_7721"));waitForIdleSync();check(((BaseAdapter)get(editor,"gridAdapter")).getCount()==0,"search-empty-state-with-clear-action");capture("search-empty");}
        ui(()->invoke(editor,"dismissPopup"));ui(()->invoke(editor,"showExport"));capture("export");ui(()->invoke(editor,"dismissPopup"));
    }
    private void exportAndCompare()throws Exception{
        List<String> hashes=new ArrayList<>();for(Object p:List.of(a,b,c))hashes.add(sha((File)invoke(p,"file")));
        Object state=runEdits(frozen);check(((Number)get(state,"completed")).intValue()==3&&((Number)get(state,"failed")).intValue()==0,"three-photos-export-own-filter-and-strength");List<?> results=(List<?>)get(state,"results");check(results.size()==3,"three-export-results");
        for(int i=0;i<3;i++){File source=(File)invoke(List.of(a,b,c).get(i),"file"),out=(File)invoke(results.get(i),"file");check(sha(source).equals(hashes.get(i)),"source-bytes-preserved-"+i);if(i==2){check(source.equals(out),"original-choice-exports-original");continue;}Object d=get(frozen.get(i),"draft");Object profile=invokeStatic("ncp.PresetPreviewProfiles","fromPreset",new Class<?>[]{byte[].class},(Object)(i==0?npBytes:ncpBytes));profile=invokeStatic("ncp.StrengthPreviewProfile","of",new Class<?>[]{type("ncp.PresetPreviewProfile"),int.class},profile,i==0?70:40);File single=new File(output,"individual-"+i+".JPG");invoke(construct("album.PhotoPresetRenderer",NONE),"render",new Class<?>[]{File.class,File.class,type("ncp.PresetPreviewProfile"),BooleanSupplier.class,type("album.PhotoPresetRenderer$Progress")},source,single,profile,(BooleanSupplier)()->false,null);check(sha(out).equals(sha(single)),"batch-equals-individual-real-JPEG-"+i);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(out.getPath(),bounds);check(bounds.outWidth==1448&&bounds.outHeight==1086,"original-pixel-dimensions-"+i);check(((File)invoke(invoke(batch,"sourceFor",new Class<?>[]{type("album.DownloadedPhoto")},results.get(i)),"file")).equals(source),"effect-original-source-link-"+i);Object gallery=construct("album.PhotoLiveGallery",new Class<?>[]{Context.class},getTargetContext());String uri=(String)invoke(gallery,"publish",new Class<?>[]{type("album.DownloadedPhoto")},results.get(i));try(InputStream in=getTargetContext().getContentResolver().openInputStream(Uri.parse(uri))){check(sha(in.readAllBytes()).equals(sha(out)),"real-MediaStore-published-effect-bytes-"+i);}}
        Object repeated=runEdits(frozen);check(((Number)get(repeated,"reused")).intValue()==2,"same-filter-and-strength-reexport-reuses-existing-effects");
    }

    private void largeLibraryAndVersions()throws Exception{
        Object first=((List<?>)get(invoke(batch,"state"),"results")).get(0);
        open(a);check(!(Boolean)get(editor,"hasDraft"),"exported-history-does-not-override-original-selection");check(((SeekBar)get(editor,"slider")).getProgress()==0&&!((SeekBar)get(editor,"slider")).isEnabled(),"saved-version-disabled-strength-shows-zero-consistently");check(pixels((Bitmap)invoke(editor,"preview")).equals(pixels((Bitmap)invoke(editor,"original"))),"selected-original-displays-original-after-export");
        open(first);Bitmap old=(Bitmap)invokeStatic("ui.PhotoFilterEditor","decode",new Class<?>[]{File.class,int.class},invoke(first,"file"),1280);check(pixels(old).equals(pixels((Bitmap)invoke(editor,"preview"))),"selected-saved-effect-displays-its-own-pixels");old.recycle();
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Object> choice=new AtomicReference<>();ui(()->invoke(editor,"freeze",new Class<?>[]{java.util.function.Consumer.class},(java.util.function.Consumer<Object>)e->{choice.set(e);done.countDown();}));check(done.await(10,TimeUnit.SECONDS)&&get(choice.get(),"draft")==null,"single-export-preserves-selected-saved-version");
        Object originalDraft=get(frozen.get(0),"draft");Object otherDraft=construct("album.PhotoEditStore$Draft",new Class<?>[]{String.class,byte[].class,String.class,int.class,boolean.class},get(originalDraft,"sourceSha"),npBytes,"Other 35",35,false);Object src=invokeStatic("album.PhotoPresetBatch$Source","local",new Class<?>[]{type("album.DownloadedPhoto")},a);Object other=invokeStatic("album.PhotoPresetBatch$Edit","draft",new Class<?>[]{type("album.PhotoPresetBatch$Source"),type("album.PhotoEditStore$Draft"),String.class},src,otherDraft,owner);Object newEffect=((List<?>)get(runEdits(List.of(other)),"results")).get(0);
        open(newEffect);Bitmap newer=(Bitmap)invokeStatic("ui.PhotoFilterEditor","decode",new Class<?>[]{File.class,int.class},invoke(newEffect,"file"),1280);check(pixels(newer).equals(pixels((Bitmap)invoke(editor,"preview"))),"newer-saved-version-displays-its-own-pixels");newer.recycle();open(first);capture("saved-version");
        ui(()->invoke(panel,"refreshDownloads"));until(()->{Object g=invoke(panel,"photoGroup",new Class<?>[]{type("album.DownloadedPhoto")},first);return g!=null&&((List<?>)get(g,"versions")).size()>=3;},"saved-versions-catalog-ready");
        Object group=invoke(panel,"photoGroup",new Class<?>[]{type("album.DownloadedPhoto")},first),originalVersion=((List<?>)get(group,"versions")).get(0);
        ui(()->invoke(panel,"choosePhotoVersion",new Class<?>[]{type("album.PhotoLibraryCatalog$PhotoGroup"),type("album.PhotoLibraryCatalog$Version")},group,originalVersion));until(()->invoke(get(panel,"localDetail"),"file").equals(invoke(a,"file"))&&(Boolean)invoke(get(panel,"filterEditor"),"isReady"),"actual-version-menu-selects-original");editor=get(panel,"filterEditor");check(!(Boolean)get(editor,"hasDraft")&&pixels((Bitmap)invoke(editor,"preview")).equals(pixels((Bitmap)invoke(editor,"original"))),"actual-version-menu-does-not-restore-exported-draft");
        mine();List<?> entries=(List<?>)get(editor,"entries");check(entries.size()>48,"large-library-more-than-48-presets");Object late=entries.get(entries.size()-1);if(!get(late,"error").toString().isEmpty())late=entries.get(entries.size()-2);final Object chosen=late;String id=(String)get(get(chosen,"preset"),"id");
        ui(()->invoke(editor,"showAll"));GridView grid=(GridView)get(editor,"grid");BaseAdapter adapter=(BaseAdapter)get(editor,"gridAdapter");int pos=0;for(int i=0;i<adapter.getCount();i++)if(adapter.getItem(i)==chosen)pos=i;final int position=pos;check(position>24,"target-filter-beyond-first-24");until(()->grid.getHeight()>0,"picker-layout-ready-for-user-scroll");waitForIdleSync();ui(()->grid.setSelection(position));Thread.sleep(300);report.put("lateTargetPosition",position).put("lateObservedFirst",grid.getFirstVisiblePosition()).put("lateObservedChildren",grid.getChildCount());until(()->{for(int i=0;i<grid.getChildCount();i++)if(id.equals(grid.getChildAt(i).getTag()))return true;return false;},"late-filter-visible-in-grid");int previous=grid.getFirstVisiblePosition();
        ui(()->{for(int i=0;i<grid.getChildCount();i++)if(id.equals(grid.getChildAt(i).getTag())){grid.getChildAt(i).performClick();break;}});until(()->id.equals(get(editor,"selected"))&&get(editor,"popup")==null,"one-tap-late-filter-selects-and-collapses");LinearLayout strip=(LinearLayout)get(editor,"strip");boolean found=false;for(int i=0;i<strip.getChildCount();i++)if(id.equals(strip.getChildAt(i).getTag()))found=true;check(found&&strip.getChildCount()<=25,"late-selected-filter-retained-in-bounded-collapsed-strip");until(()->{LinearLayout bar=(LinearLayout)get(editor,"strip");for(int i=0;i<bar.getChildCount();i++){View item=bar.getChildAt(i);android.graphics.Rect rect=new android.graphics.Rect();if(id.equals(item.getTag())&&item.getGlobalVisibleRect(rect)&&rect.width()>=item.getWidth()-2)return true;}return false;},"late-selected-thumbnail-actually-visible-after-collapse");
        ui(()->invoke(editor,"showAll"));until(()->((GridView)get(editor,"grid")).getFirstVisiblePosition()>=Math.max(0,previous-3),"picker-reopens-at-retained-scroll-position");capture("library-late");ui(()->invoke(editor,"dismissPopup"));
        for(Object e:entries){if(get(e,"preset")==unsupported||!get(e,"error").toString().isEmpty())continue;ui(()->invoke(editor,"request",new Class<?>[]{e.getClass()},e));until(()->get(e,"profile")!=null||!get(e,"error").toString().isEmpty(),"cache-entry-render-completes");}
        int retained=0;Object evicted=null;for(Object e:entries){if(get(e,"bytes")!=null||get(e,"profile")!=null||get(e,"image")!=null)retained++;else if(get(e,"error").toString().isEmpty())evicted=e;}check(retained<=48&&evicted!=null,"bitmaps-preset-bytes-and-profiles-are-all-bounded");Object reload=evicted;ui(()->invoke(editor,"request",new Class<?>[]{reload.getClass()},reload));until(()->get(reload,"profile")!=null,"evicted-recipe-can-reload");
        // Pause boundary uses the same path as MainActivity.onPause, before the delayed choice save.
        ui(()->{invoke(editor,"choose",new Class<?>[]{reload.getClass()},reload);invoke(panel,"releasePhotoComparison");});barrier();Object store=construct("album.PhotoEditStore",new Class<?>[]{File.class,String.class},getTargetContext().getFilesDir(),owner);Object pending=invoke(store,"readPending",new Class<?>[]{String.class},get(editor,"sourceSha"));check(pending!=null&&get(pending,"presetSha").equals(get(editor,"selected")),"pause-boundary-flushes-latest-filter-choice");
    }
    private Object runEdits(List<Object> edits)throws Exception{CountDownLatch accepted=new CountDownLatch(1),done=new CountDownLatch(1);AtomicReference<Object> result=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();Object cb=callback("album.AlbumController$Callback",(n,v)->{if(n.equals("onError"))error.set(v[0].toString());accepted.countDown();});Object finish=callback("album.AlbumController$Callback",(n,v)->{if(n.equals("onSuccess"))result.set(v[0]);else error.set(v[0].toString());done.countDown();});ui(()->invoke(batch,"startEdits",new Class<?>[]{List.class,type("album.AlbumController$Callback"),type("album.AlbumController$Callback")},edits,cb,finish));check(accepted.await(10,TimeUnit.SECONDS)&&error.get()==null,"native-export-accepted");check(done.await(60,TimeUnit.SECONDS)&&error.get()==null,"native-export-completed");return result.get();}
    private static boolean hasText(View v,String value){if(v instanceof TextView t&&v.isShown()&&value.equals(t.getText().toString()))return true;if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)if(hasText(g.getChildAt(i),value))return true;return false;}
    private static <T extends View>T find(View v,Class<T> cls){if(cls.isInstance(v))return cls.cast(v);if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){T x=find(g.getChildAt(i),cls);if(x!=null)return x;}return null;}
    private byte[] asset(String name) throws Exception { try (InputStream input = getContext().getAssets().open(name)) { return input.readAllBytes(); } }
    private void saveBitmap(Bitmap image, String name) throws Exception { try (FileOutputStream stream = new FileOutputStream(new File(output,name))) { check(image.compress(Bitmap.CompressFormat.JPEG,95,stream), "native-template-evidence-encoded"); } }
    private String pixels(Bitmap bitmap) throws Exception { int[] values = new int[bitmap.getWidth()*bitmap.getHeight()]; bitmap.getPixels(values,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight()); MessageDigest digest = MessageDigest.getInstance("SHA-256"); for (int value : values) { digest.update((byte)(value>>>24)); digest.update((byte)(value>>>16)); digest.update((byte)(value>>>8)); digest.update((byte)value); } return hex(digest.digest()); }
    private void capture(String name) throws Exception {
        waitForIdleSync();Thread.sleep(200);Bitmap image=getUiAutomation().takeScreenshot();check(image!=null,"native-screenshot-"+name);
        try{
            boolean landscape=args.getString("mode","").equals("landscape");
            check(landscape?image.getWidth()>image.getHeight():image.getHeight()>image.getWidth(),"native-screenshot-orientation-"+name);
            JSONObject screenshots=report.optJSONObject("screenshots");if(screenshots==null){screenshots=new JSONObject();report.put("screenshots",screenshots);}
            screenshots.put(name,new JSONObject().put("width",image.getWidth()).put("height",image.getHeight()).put("orientation",activity.getResources().getConfiguration().orientation).put("fontScale",activity.getResources().getConfiguration().fontScale));
            try(FileOutputStream stream=new FileOutputStream(new File(output,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,stream);}
        }finally{image.recycle();}
    }
    private String sha(File file) throws Exception { try (InputStream input = Files.newInputStream(file.toPath())) { return sha(input.readAllBytes()); } }
    private String sha(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hex(byte[] bytes) { StringBuilder result = new StringBuilder(); for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT,"%02x",value&255)); return result.toString(); }
    private void until(Check condition, String name) throws Exception { for (int i=0;i<300;i++) { boolean[] ready={false}; ui(() -> ready[0]=condition.test()); if(ready[0]) { check(true,name); return; } Thread.sleep(100); } check(false,name+"-timeout"); }
    private void check(boolean value, String name) { stage=name; if(!value) throw new IllegalStateException(name); assertions.put(name); checks++; android.util.Log.i("EditorNativeQA",name); }
    private Class<?> type(String suffix) throws Exception { return Class.forName(TARGET+'.'+suffix,true,loader); }
    private Object construct(String suffix, Class<?>[] types, Object... values) throws Exception { Constructor<?> constructor=type(suffix).getDeclaredConstructor(types); constructor.setAccessible(true); return constructor.newInstance(values); }
    private Object invokeStatic(String suffix,String name,Class<?>[] types,Object... values) throws Exception { Method method=type(suffix).getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(null,values); }
    private static Object invoke(Object value,String name) throws Exception { return invoke(value,name,NONE); }
    private static Object invoke(Object value,String name,Class<?>[] types,Object... values) throws Exception { Method method=value.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(value,values); }
    private static Object get(Object value,String name) throws Exception { Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value); }
    private static void set(Object value,String name,Object next) throws Exception { Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);field.set(value,next); }
    private Object callback(String suffix, Callback callback) throws Exception { Class<?> type=type(suffix);return Proxy.newProxyInstance(loader,new Class<?>[]{type},(proxy,method,values)->{ if(method.getDeclaringClass()==Object.class) { if(method.getName().equals("hashCode"))return System.identityHashCode(proxy); if(method.getName().equals("equals"))return proxy==values[0]; return "NP3 QA callback"; } callback.call(method.getName(),values==null?new Object[0]:values);return null; }); }
    private static Throwable unwrap(Throwable failure) { Throwable value=failure; while(value.getCause()!=null && (value instanceof InvocationTargetException || value instanceof RuntimeException || value instanceof Exception)) value=value.getCause();return value; }
    private void ui(Task task) throws Exception { Throwable[] failure={null};runOnMainSync(()->{try{task.run();}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null){if(failure[0] instanceof Exception)throw(Exception)failure[0];throw new RuntimeException(failure[0]);} }
    private interface Task { void run() throws Exception; }
    private interface Check { boolean test() throws Exception; }
    private interface Callback { void call(String method,Object[] values) throws Exception; }
}

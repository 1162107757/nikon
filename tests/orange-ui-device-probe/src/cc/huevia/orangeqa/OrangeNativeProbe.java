package cc.huevia.orangeqa;

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
public final class OrangeNativeProbe extends Instrumentation {
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
        String requestedScope=args.getString("scope","full");check(requestedScope.equals("local-album")||requestedScope.equals("filter-navigation")||requestedScope.equals("full")||requestedScope.equals("viewer-boundary")||requestedScope.equals("selection-polish")||requestedScope.equals("album-refresh")||requestedScope.equals("basic-adjustments")||requestedScope.equals("composition-recipes"),"supported-probe-scope");
        String evidenceFolder="orange-ui-";
        output=new File(context.getExternalFilesDir(null),evidenceFolder+expectedVersion+"/"+requestedScope+"/"+args.getString("mode","phone"));output.mkdirs();Files.deleteIfExists(new File(output,"failure.png").toPath());
        report.put("version",version.versionName).put("versionCode",version.getLongVersionCode()).put("apkSha256",sha(new File(context.getApplicationInfo().sourceDir))).put("scope","Isolated offline emulator, real JPEG/EXIF/MediaStore and existing app-template source. Online UI uses an explicitly seeded validated catalog fixture from existing local recipes; this is not a live production catalog or camera check.");
        if(args.containsKey("originDiagnosticUri")){
            String address=args.getString("originDiagnosticUri"),id=address.substring(address.lastIndexOf('/')+1);List<String> prefixes=new ArrayList<>();for(String volume:List.of("external","external_primary","internal"))prefixes.add("phone-"+sha(("content://media/"+volume+"/images/media/"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8))+"-");File pictures=new File(context.getFilesDir(),"pictures/camera-album");JSONArray diagnostics=new JSONArray();File[] imported=pictures.listFiles(file->prefixes.stream().anyMatch(prefix->file.getName().startsWith(prefix))&&file.getName().contains("__"));if(imported!=null)for(File file:imported){Object photo=construct("album.DownloadedPhoto",new Class<?>[]{File.class,String.class},file,file.getName().substring(file.getName().indexOf("__")+2));diagnostics.put(originDiagnostic(photo,address));}report.put("scope","Read-only origin forensic query on existing private files/provider rows; no seed, import, editor, publication or deletion.").put("originDiagnostics",diagnostics);check(diagnostics.length()>0,"existing-imported-fixture-found-for-origin-diagnostic");report.put("status","PASS");result.putString("status","PASS");result.putString("stream","Read-only origin diagnostics PASS; files="+diagnostics.length()+"\n");
        }else{
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
        seed();if(requestedScope.equals("local-album")){localAlbumScope();orangeScope();}else if(requestedScope.equals("filter-navigation")){filterNavigation();}else if(requestedScope.equals("composition-recipes")){compositionRecipes();}else if(requestedScope.equals("basic-adjustments")){orangeScope();basicAdjustments();}else if(requestedScope.equals("album-refresh")){albumRefreshScope();}else if(requestedScope.equals("viewer-boundary")){viewerBoundary();}else if(requestedScope.equals("selection-polish")){onlineCategoriesAndStableCards();selectionPolish();}else{onlineCategoriesAndStableCards();editAndRestore();sheets();exportAndCompare();largeLibraryAndVersions();}report.put("status","PASS");result.putString("status","PASS");result.putString("stream","Photo editor native PASS; checks="+checks+"\n");
        }
    }catch(Throwable e){Throwable cause=unwrap(e);try{report.put("status","FAIL").put("stage",stage).put("failure",cause.toString());if(activity!=null)capture("failure");}catch(Exception ignored){}result.putString("status","FAIL");result.putString("stream","Photo editor native FAIL; "+stage+"; "+cause+"\n");}
    finally{try{report.put("checks",checks).put("assertions",assertions);if(output!=null)Files.writeString(new File(output,"report.json").toPath(),report.toString(2));if(activity!=null)ui(()->activity.finish());}catch(Exception ignored){}}
    finish("PASS".equals(result.getString("status"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);}
    private JSONObject originDiagnostic(Object photo,String expectedUri)throws Exception{
        File file=(File)invoke(photo,"file");Context supplied=getTargetContext(),application=supplied.getApplicationContext();JSONObject value=new JSONObject().put("expectedSourceUri",expectedUri).put("providedContextPackage",supplied.getPackageName()).put("providedContextFilesDir",supplied.getFilesDir().getCanonicalPath()).put("applicationContextPackage",application==null?JSONObject.NULL:application.getPackageName()).put("applicationContextFilesDir",application==null?JSONObject.NULL:application.getFilesDir().getCanonicalPath()).put("privateAbsolutePath",file.getAbsolutePath()).put("privatePath",file.getCanonicalPath()).put("privateFilename",file.getName()).put("privateBytes",file.length()).put("privateModified",file.lastModified()).put("privateSha256",sha(file));
        Object helper=invokeStatic("album.LocalPhotoOrigin","check",new Class<?>[]{Context.class,type("album.DownloadedPhoto")},getTargetContext(),photo);value.put("originState",get(helper,"state").toString()).put("originUri",get(helper,"uri")).put("originMessage",get(helper,"message"));
        Object gallery=construct("album.PhotoLiveGallery",new Class<?>[]{Context.class},getTargetContext());Object status=invoke(gallery,"publicationStatus",new Class<?>[]{type("album.DownloadedPhoto")},photo),exact=invoke(gallery,"exactPublicationStatus",new Class<?>[]{type("album.DownloadedPhoto")},photo);value.put("publicationStatus",get(status,"status").toString()).put("publicationUri",get(status,"uri")).put("publicationMessage",get(status,"message")).put("exactStatus",get(exact,"status").toString()).put("exactUri",get(exact,"uri")).put("exactMessage",get(exact,"message"));
        String key=file.getName().substring(0,file.getName().indexOf("__"));File proof=new File(getTargetContext().getFilesDir(),"local-photo-sources/"+key+".source");JSONObject record=new JSONObject().put("path",proof.getCanonicalPath()).put("exists",proof.isFile());if(proof.isFile()){record.put("bytes",proof.length()).put("sha256",sha(proof));try(java.io.DataInputStream input=new java.io.DataInputStream(new java.io.FileInputStream(proof))){record.put("magic",Integer.toHexString(input.readInt())).put("uri",input.readUTF()).put("filename",input.readUTF()).put("mime",input.readUTF()).put("sourceSha256",input.readUTF()).put("retained",input.readUTF()).put("sourceBytes",input.readLong()).put("date",input.readLong()).put("workingSha256",input.readUTF()).put("workingBytes",input.readLong());}catch(Exception failure){record.put("readFailure",failure.toString());}}value.put("sourceRecord",record);
        JSONArray journals=new JSONArray();for(String directory:List.of("photo-effect-gallery","photo-live-gallery")){File[] files=new File(getTargetContext().getFilesDir(),directory).listFiles();if(files!=null)for(File journal:files)if(journal.isFile())journals.put(new JSONObject().put("directory",directory).put("name",journal.getName()).put("bytes",journal.length()).put("sha256",sha(journal)));}return value.put("publicationJournalFiles",journals);
    }
    /** Real native provider/editor boundaries using explicitly generated offline JPEG/PNG fixtures. */
    private void localAlbumScope()throws Exception{
        report.put("scope","Four-root navigation and local album on isolated emulator. Real runtime permission grant/revoke before launch, native MediaStore JPEG/PNG query, unchanged-source reuse, exact private import, incumbent editor, 13 adjustments, crop, recipe reuse, actual batch filter draft action and durable destination export. A hand-authored structural NEF fixture exercises the real offline RAW panel, exact-byte publisher and frozen retry target. Synthetic images/recipes and structural RAW fixture only; no physical camera, captured sensor data, live production catalog, FPS or TalkBack certification.");
        check(getTargetContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS")==android.content.pm.PackageManager.PERMISSION_GRANTED,"dedicated-emulator-notification-permission-is-pregranted");report.put("notificationPermissionSetup","POST_NOTIFICATIONS pregranted only to isolate the dedicated emulator run; notification permission UX is outside this scope. Media permission grant/revoke checks remain active.");
        for(String label:List.of("预设","同步","相册","设置","相册")){until(()->navigationCell(activity.getWindow().getDecorView(),label)!=null,"actual-navigation-cell-visible-"+label);ui(()->navigationCell(activity.getWindow().getDecorView(),label).performClick());until(()->navigationCell(activity.getWindow().getDecorView(),label)!=null&&navigationCell(activity.getWindow().getDecorView(),label).isSelected(),"actual-navigation-tap-selects-root-"+label);}
        until(()->get(activity,"localAlbum")!=null&&((View)get(get(activity,"localAlbum"),"root")).isShown(),"local-album-is-an-actual-navigation-root");
        Object local=get(activity,"localAlbum"),repository=get(local,"repository");
        Object receiveController=get(panel,"controller");check(invoke(receiveController,"photoLiveSaveTarget").toString().equals("APP_ONLY"),"actual-runtime-live-receive-default-is-app-only");check(get(receiveController,"originalDownloadObserver")==null,"actual-runtime-installs-no-unconditional-original-gallery-publisher");
        check(hasText(activity.getWindow().getDecorView(),"同步")&&hasText(activity.getWindow().getDecorView(),"相册"),"four-root-navigation-has-separate-sync-and-local-album");
        boolean rail=activity.getResources().getConfiguration().screenWidthDp>=600;int previous=-1;for(String label:List.of("预设","同步","相册","设置")){View cell=navigationCell(activity.getWindow().getDecorView(),label);int[] position=new int[2];cell.getLocationOnScreen(position);int coordinate=position[rail?1:0];check(coordinate>previous&&cell.getWidth()>=pixelsDp(48)&&cell.getHeight()>=pixelsDp(48),"actual-four-root-order-and-touch-target-"+label);previous=coordinate;}
        check(invoke(repository,"access").toString().equals("NONE"),"actual-media-permission-is-revoked-before-launch");
        until(()->!(Boolean)get(local,"loadingApp")&&!((List<?>)get(local,"appPhotos")).isEmpty(),"app-local-photo-list-works-without-media-permission");until(()->((LruCache<?,?>)get(local,"thumbnails")).size()>0,"app-local-native-thumbnails-are-real-decoded-images");assertLocalPhotoRowVisible(local,"app");assertLocalActionVisible(local,(View)get(local,"pickerFab"),"picker-app");assertLocalActionVisible(local,(View)get(local,"moreButton"),"overflow-app");check(((View)get(local,"toolbar")).getVisibility()==View.GONE,"local-album-actions-use-overflow-and-floating-picker");capture("local-app");
        long beforeDenied=mediaRows();getTargetContext().getSharedPreferences("photo-save-destinations",Context.MODE_PRIVATE).edit().remove("sync").commit();ui(()->invoke(panel,"saveToGallery",new Class<?>[]{type("album.DownloadedPhoto")},a));until(()->activeWindowHas("仅保存在 App 内")&&activeWindowHas("保存到手机相册"),"actual-original-save-opens-explicit-destination-choice");check(activeChecked("仅保存在 App 内"),"fresh-original-save-choice-defaults-to-app-only");clickActiveText("保存");until(()->!(Boolean)get(panel,"exporting")&&!(Boolean)invoke(invokeStatic("album.PhotoAlbumSaver","get",new Class<?>[]{Context.class},getTargetContext()),"isBusy"),"actual-original-app-only-save-finishes-with-permission-revoked");check(mediaRows()==beforeDenied,"actual-original-app-only-save-does-not-insert-MediaStore-row");
        Object saved=invokeStatic("album.PhotoPresetBatch$Edit","saved",new Class<?>[]{type("album.PhotoPresetBatch$Source")},invokeStatic("album.PhotoPresetBatch$Source","local",new Class<?>[]{type("album.DownloadedPhoto")},a));
        Object deniedResult=runEdits(List.of(saved),"APP_ONLY",false);check(((Number)get(deniedResult,"completed")).intValue()==1&&mediaRows()==beforeDenied,"denied-media-permission-does-not-block-app-only-result");
        ui(()->((Button)get(local,"phoneTab")).performClick());until(()->!(Boolean)get(local,"loadingPhone"),"denied-phone-tab-settles");
        check(((List<?>)get(local,"phonePhotos")).isEmpty()&&hasText((View)get(local,"root"),"点击获取授权")&&"选择照片".contentEquals(((View)get(local,"pickerFab")).getContentDescription()),"denied-phone-page-offers-central-authorization-and-floating-system-picker");assertLocalTextVisible(local,(TextView)get(local,"emptyTitle"),"denied-title");assertLocalTextVisible(local,(TextView)get(local,"emptyText"),"denied-guidance");assertLocalActionVisible(local,(View)get(local,"permissionButton"),"central-authorization");assertLocalActionVisible(local,(View)get(local,"pickerFab"),"picker-denied");capture("local-denied");localCancelledPlatformActions(local,repository);
        String run=UUID.randomUUID().toString().substring(0,8);String jpegName="QA_LOCAL_"+run+".jpg",pngName="QA_ALPHA_"+run+".png";
        byte[] jpeg=Files.readAllBytes(((File)invoke(a,"file")).toPath());Bitmap alpha=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(alpha);canvas.drawColor(Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR);Paint paint=new Paint();paint.setColor(0xff000000|(run.hashCode()&0xffffff));canvas.drawRect(80,40,280,200,paint);paint.setColor(Color.WHITE);paint.setTextSize(20);canvas.drawText(run,95,120,paint);java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();alpha.compress(Bitmap.CompressFormat.PNG,100,encoded);alpha.recycle();byte[] png=encoded.toByteArray();
        Uri jpegUri=insertMedia(jpegName,"image/jpeg",jpeg),pngUri=insertMedia(pngName,"image/png",png);String jpegHash=sha(jpeg),pngHash=sha(png);
        report.put("mediaFixtures",new JSONObject().put("jpegUri",jpegUri.toString()).put("jpegSha256",jpegHash).put("pngUri",pngUri.toString()).put("pngSha256",pngHash));
        shell("pm grant "+TARGET+" android.permission.READ_MEDIA_IMAGES");until(()->invoke(repository,"access").toString().equals("FULL"),"actual-read-media-images-grant-is-observed");
        ui(()->invoke(local,"refresh"));until(()->!(Boolean)get(local,"loadingPhone")&&phoneItem(local,jpegName)!=null&&phoneItem(local,pngName)!=null,"actual-phone-query-lists-seeded-jpeg-and-png");
        Object page=invoke(repository,"phonePage",new Class<?>[]{int.class,int.class},0,120);check(((List<?>)get(page,"photos")).stream().anyMatch(row->{try{return jpegName.equals(get(row,"filename"));}catch(Exception e){throw new RuntimeException(e);}}),"repository-page-is-a-real-MediaStore-query");until(()->((LruCache<String,Bitmap>)get(local,"thumbnails")).get((String)invoke(get(phoneItem(local,jpegName),"phone"),"thumbnailKey"))!=null,"authorized-phone-native-JPEG-thumbnail-is-decoded");assertLocalPhotoRowVisible(local,"phone-authorized");capture("phone-authorized");
        Object jpegPhoto=openPhone(local,jpegName,false);File jpegFile=(File)invoke(jpegPhoto,"file");long jpegModified=jpegFile.lastModified();check(sha(jpegFile).equals(jpegHash),"jpeg-import-preserves-exact-source-bytes");
        Object originGallery=construct("album.PhotoLiveGallery",new Class<?>[]{Context.class},getTargetContext());long beforeNeutral=mediaRows();report.put("neutralImportedOrigin",originDiagnostic(jpegPhoto,jpegUri.toString()));Object originStatus=invoke(originGallery,"publicationStatus",new Class<?>[]{type("album.DownloadedPhoto")},jpegPhoto);check(get(originStatus,"status").toString().equals("EXISTING_SOURCE"),"neutral-imported-JPEG-reports-existing-source-without-owned-publication");String existingSource=(String)invoke(originGallery,"publish",new Class<?>[]{type("album.DownloadedPhoto")},jpegPhoto);check(existingSource.equals(get(originStatus,"uri"))&&uriHash(Uri.parse(existingSource)).equals(jpegHash)&&mediaRows()==beforeNeutral,"neutral-imported-JPEG-phone-save-reuses-actual-source-without-duplicate");report.put("unchangedImportedSourceUri",existingSource);
        choose(ncp,65);adjust("BRIGHTNESS",11);Object crop=openCrop();cropButton(crop,"1:1");cropButton(crop,"应用");barrier();Object jpegEdit=freeze();check(!(Boolean)invoke(get(get(jpegEdit,"draft"),"geometry"),"isNeutral"),"imported-jpeg-uses-incumbent-crop-editor");capture("editor-jpeg");
        Object recipeStore=construct("album.PhotoRecipeStore",new Class<?>[]{File.class,String.class},getTargetContext().getFilesDir(),owner);Object recipe=invoke(recipeStore,"create",new Class<?>[]{String.class,type("album.PhotoEditStore$Draft")},"本地回归 "+run,get(jpegEdit,"draft"));
        returnToLocalAlbum();Object pngPhoto=openPhone(local,pngName,true);File pngFile=(File)invoke(pngPhoto,"file");long pngModified=pngFile.lastModified();
        Bitmap normalized=BitmapFactory.decodeFile(pngFile.getPath());check(normalized!=null&&Color.red(normalized.getPixel(5,5))>245&&Color.green(normalized.getPixel(5,5))>245&&Color.blue(normalized.getPixel(5,5))>245,"png-transparency-is-explicitly-flattened-on-white");normalized.recycle();
        ui(()->invoke(editor,"applyRecipe",new Class<?>[]{type("album.PhotoRecipeStore$Recipe")},recipe));until(()->((Number)get(editor,"strength")).intValue()==65&&manualValue("BRIGHTNESS")==11,"jpeg-recipe-reuses-incumbent-editor-on-png-copy");
        check((Boolean)invoke(get(editor,"geometry"),"isNeutral"),"recipe-does-not-copy-another-photo-crop");
        Object[] parameters=type("ncp.PhotoAdjustments$Parameter").getEnumConstants();check(parameters.length==13,"all-thirteen-basic-adjustments-remain-available");for(Object parameter:parameters)adjust(parameter.toString(),1);barrier();Object pngEdit=freeze();
        check(((Number)invoke(get(get(pngEdit,"draft"),"adjustments"),"count")).intValue()==13,"png-copy-keeps-thirteen-independent-adjustment-values");capture("editor-png-adjustments");
        List<Object> batchChoices=localBatchFilter(local,List.of(jpegPhoto,pngPhoto),List.of(jpegEdit,pngEdit));jpegEdit=batchChoices.get(0);pngEdit=batchChoices.get(1);
        getTargetContext().getSharedPreferences("photo-save-destinations",Context.MODE_PRIVATE).edit().remove("edited").commit();
        long beforeLocal=mediaRows();ui(()->invoke(panel,"exportLocalWorkspace",new Class<?>[]{List.class},List.of(jpegPhoto,pngPhoto)));until(()->activeWindowHas("保存到手机相册"),"actual-local-export-opens-save-destination-choice");
        check(activeChecked("保存到手机相册")&&activeWindowHas("仅保存在 App 内"),"fresh-edited-export-choice-defaults-to-phone-and-includes-app-only");capture("destination-sheet");clickActiveText("仅保存在 App 内");until(()->activeChecked("仅保存在 App 内"),"actual-native-radio-selects-app-only");clickActiveText("保存");
        until(()->{Object state=invoke(batch,"state");return ((Number)get(state,"total")).intValue()==2&&!(Boolean)get(state,"active");},"actual-local-export-UI-completes-two-independent-edits");Object localResult=invoke(batch,"state");
        check(((Number)get(localResult,"completed")).intValue()==2&&((Number)get(localResult,"failed")).intValue()==0&&mediaRows()==beforeLocal,"two-photo-app-only-batch-creates-no-system-album-row");List<?> results=(List<?>)get(localResult,"results");check(results.size()==2,"both-local-edit-results-are-durable");
        until(()->activeWindowHas("已保存到 App 内"),"actual-local-export-confirms-app-only-destination");clickActiveText("关闭");
        for(int i=0;i<2;i++){Object edit=List.of(jpegEdit,pngEdit).get(i);File source=(File)invoke(List.of(jpegPhoto,pngPhoto).get(i),"file"),result=(File)invoke(results.get(i),"file");File single=new File(output,"local-individual-"+i+".JPG");invoke(construct("album.PhotoPresetRenderer",NONE),"render",new Class<?>[]{File.class,File.class,type("ncp.PresetPreviewProfile"),BooleanSupplier.class,type("album.PhotoPresetRenderer$Progress")},source,single,invoke(get(edit,"draft"),"profile"),(BooleanSupplier)()->false,null);check(sha(result).equals(sha(single)),"local-batch-equals-incumbent-single-editor-render-"+i);}
        long publicationStarted=System.currentTimeMillis();Object publicResult=runEdits(List.of(jpegEdit,pngEdit));check(((Number)get(publicResult,"completed")).intValue()==2&&mediaRows()==beforeLocal+2,"edited-export-default-publishes-only-two-edited-results");
        Object gallery=construct("album.PhotoLiveGallery",new Class<?>[]{Context.class},getTargetContext());Object resultPhoto=results.get(0);File effectFile=(File)invoke(resultPhoto,"file");String effectHash=sha(effectFile);String first=(String)invoke(gallery,"publish",new Class<?>[]{type("album.DownloadedPhoto")},resultPhoto);long firstDate=galleryTime(first);check(firstDate>=publicationStarted-1000,"first-phone-export-has-current-export-chronology");
        String repeated=(String)invoke(gallery,"publish",new Class<?>[]{type("album.DownloadedPhoto")},resultPhoto);check(first.equals(repeated)&&mediaRows()==beforeLocal+2,"same-saved-version-default-publication-is-idempotent");
        Thread.sleep(1100);String copied=(String)invoke(gallery,"publishNewCopy",new Class<?>[]{type("album.DownloadedPhoto")},resultPhoto);check(!copied.equals(first)&&mediaRows()==beforeLocal+3&&galleryTime(copied)>firstDate,"explicit-new-copy-adds-one-freshly-dated-publication");
        check(effectHash.equals(sha(effectFile)),"explicit-new-copy-preserves-verified-local-render-cache-bytes");Bitmap privatePixels=BitmapFactory.decodeFile(effectFile.getPath());try(InputStream input=getTargetContext().getContentResolver().openInputStream(Uri.parse(copied))){Bitmap published=BitmapFactory.decodeStream(input);try{check(published!=null&&pixels(privatePixels).equals(pixels(published)),"new-copy-updates-EXIF-without-recompressing-pixels");}finally{if(published!=null)published.recycle();privatePixels.recycle();}}
        check(jpegHash.equals(sha(jpegFile))&&jpegFile.lastModified()==jpegModified&&pngFile.lastModified()==pngModified,"all-destinations-preserve-private-originals-and-import-times");check(jpegHash.equals(uriHash(jpegUri))&&pngHash.equals(uriHash(pngUri)),"all-edit-and-export-actions-preserve-phone-source-bytes");
        File retainedDir=new File(getTargetContext().getFilesDir(),"local-photo-sources");boolean retained=false;for(File file:retainedDir.listFiles())if(file.getName().endsWith(".png")&&pngHash.equals(sha(file)))retained=true;check(retained,"exact-transparent-PNG-source-remains-retained-in-App-storage");
        report.put("publicationRowsBeforeLocal",beforeLocal).put("publicationRowsAfterAppOnly",beforeLocal).put("publicationRowsAfterDefault",beforeLocal+2).put("publicationRowsAfterNewCopy",mediaRows()).put("firstExportUri",first).put("newCopyUri",copied).put("localResultSha256",effectHash);
        returnToLocalAlbum();ui(()->{invoke(local,"switchTab",new Class<?>[]{boolean.class},false);invoke(local,"refresh");});until(()->!(Boolean)get(local,"loadingApp")&&((List<?>)get(local,"appPhotos")).size()>=3,"local-results-refresh-in-dedicated-app-album");assertLocalPhotoRowVisible(local,"app-only-results");capture("app-only-results");
        invoke(recipeStore,"delete",new Class<?>[]{String.class},get(recipe,"id"));
        localRawDestinations(run);
    }
    private List<Object> localBatchFilter(Object local,List<Object> photos,List<Object> previous)throws Exception{
        long rowsBefore=mediaRows(),effectsBefore=allEffectFiles();returnToLocalAlbum();ui(()->{invoke(local,"switchTab",new Class<?>[]{boolean.class},false);invoke(local,"refresh");});until(()->!(Boolean)get(local,"loadingApp"),"local-batch-selection-has-refreshed-app-photos");
        selectLocalTile(local,photos.get(0),true);selectLocalTile(local,photos.get(1),false);check(((Set<?>)get(local,"selected")).size()==2,"native-local-gallery-selects-two-independent-originals");ui(()->((Button)get(local,"batchButton")).performClick());until(()->activeWindowHas("批量套用滤镜"),"native-local-gallery-batch-action-menu-opens");clickActiveText("批量套用滤镜");until(()->get(panel,"presetSelectionPage")!=null&&get(get(panel,"presetSelectionPage"),"dialog")!=null,"detached-local-gallery-opens-real-preset-picker");
        Object picker=get(panel,"presetSelectionPage");until(()->!((List<?>)get(picker,"entries")).isEmpty(),"batch-preset-picker-loads-actual-local-library");List<?> entries=(List<?>)get(picker,"entries");Object chosen=null;int position=-1;for(int index=0;index<entries.size();index++)if(get(get(entries.get(index),"preset"),"id").equals(get(ncp,"id"))){chosen=entries.get(index);position=index;break;}check(chosen!=null,"batch-preset-picker-contains-validated-NCP-fixture");final Object selected=chosen;final int row=position;ui(()->((ListView)get(picker,"list")).setSelection(row));until(()->get(selected,"state").toString().equals("READY"),"actual-batch-preset-card-native-preview-is-ready");String name=(String)get(ncp,"displayName");View card=photoTile(((android.app.Dialog)get(picker,"dialog")).getWindow().getDecorView(),name);check(card!=null,"actual-batch-preset-card-is-visible-and-clickable");ui(card::performClick);until(()->activeWindowHas("套用「"+name+"」"),"actual-batch-preset-card-opens-confirmation");clickActiveText("开始处理");until(()->!(Boolean)get(panel,"preparingEditedExport")&&get(readDraft(photos.get(0)),"presetSha").equals(get(ncp,"sha256"))&&((Number)get(readDraft(photos.get(0)),"strength")).intValue()==100,"actual-batch-filter-updates-pending-drafts");
        List<Object> edits=new ArrayList<>();for(int index=0;index<photos.size();index++){Object draft=readDraft(photos.get(index)),old=get(previous.get(index),"draft");check(get(draft,"adjustments").equals(get(old,"adjustments"))&&get(draft,"geometry").equals(get(old,"geometry")),"batch-filter-preserves-each-photo-manual-adjustments-and-crop-"+index);Object source=invokeStatic("album.PhotoPresetBatch$Source","local",new Class<?>[]{type("album.DownloadedPhoto")},photos.get(index));edits.add(invokeStatic("album.PhotoPresetBatch$Edit","draft",new Class<?>[]{type("album.PhotoPresetBatch$Source"),type("album.PhotoEditStore$Draft"),String.class},source,draft,owner));}
        check(mediaRows()==rowsBefore&&allEffectFiles()==effectsBefore,"batch-filter-action-updates-drafts-without-rendering-or-MediaStore-insertion");return edits;
    }
    private void selectLocalTile(Object local,Object photo,boolean longPress)throws Exception{
        File file=(File)invoke(photo,"file"),canonical=file.getCanonicalFile();int target=-1;String key=null;Object matched=null;List<?> rows=(List<?>)get(local,"rows");for(int index=0;index<rows.size();index++)for(Object item:(List<?>)get(rows.get(index),"items")){Object group=get(item,"group");if(group!=null&&invoke(group,"findByFile",new Class<?>[]{File.class},canonical)!=null){target=index;key=(String)invoke(item,"thumbKey");matched=item;break;}}JSONObject selection=new JSONObject().put("expectedAbsolutePath",file.getAbsolutePath()).put("expectedCanonicalPath",canonical.getPath()).put("listRows",rows.size()).put("appGroups",((List<?>)get(local,"appPhotos")).size()).put("matchedRow",target);if(matched!=null){selection.put("groupId",get(get(matched,"group"),"id")).put("displayCanonicalPath",((File)invoke(get(matched,"photo"),"file")).getCanonicalPath());}JSONArray selections=report.optJSONArray("localBatchSelections");if(selections==null){selections=new JSONArray();report.put("localBatchSelections",selections);}selections.put(selection);check(target>=0&&key!=null,"local-original-has-real-gallery-row-for-batch-selection");final int position=target;final String thumbKey=key;ui(()->((ListView)get(local,"list")).setSelection(position));until(()->localTileByKey((View)get(local,"root"),thumbKey)!=null,"local-gallery-batch-target-is-visible");ui(()->{View tile=localTileByKey((View)get(local,"root"),thumbKey);if(longPress)tile.performLongClick();else tile.performClick();});
    }
    private View localTileByKey(View root,String key){if(root instanceof ImageView&&key.equals(root.getTag())&&root.isShown()&&root.getParent() instanceof View frame&&frame.getParent() instanceof View tile&&tile.isClickable())return tile;if(root instanceof ViewGroup group)for(int index=0;index<group.getChildCount();index++){View found=localTileByKey(group.getChildAt(index),key);if(found!=null)return found;}return null;}
    private android.graphics.Rect screenBounds(View view){int[] location=new int[2];view.getLocationOnScreen(location);return new android.graphics.Rect(location[0],location[1],location[0]+view.getWidth(),location[1]+view.getHeight());}
    private android.graphics.Rect localUncoveredBounds(Object local)throws Exception{
        View cell=navigationCell(activity.getWindow().getDecorView(),"相册"),material=(View)((View)cell.getParent()).getParent();android.graphics.Rect bounds=new android.graphics.Rect();((View)get(local,"root")).getGlobalVisibleRect(bounds);if(material.getWidth()<material.getHeight())bounds.left=Math.max(bounds.left,screenBounds(material).right);else bounds.bottom=Math.min(bounds.bottom,screenBounds(material).top);return bounds;
    }
    private boolean fullyVisibleWithin(View view,android.graphics.Rect allowed){android.graphics.Rect full=screenBounds(view),visible=new android.graphics.Rect();return view.isShown()&&full.width()>0&&full.height()>0&&view.getGlobalVisibleRect(visible)&&visible.width()>=full.width()-1&&visible.height()>=full.height()-1&&allowed.contains(full);}
    private void assertLocalActionVisible(Object local,View action,String phase)throws Exception{waitForIdleSync();ui(()->{android.graphics.Rect allowed=localUncoveredBounds(local);check(action.isClickable()&&action.isEnabled()&&fullyVisibleWithin(action,allowed)&&action.getWidth()>=pixelsDp(48)&&action.getHeight()>=pixelsDp(48),"local-action-entire-touch-target-visible-above-navigation-"+phase);JSONObject layouts=report.optJSONObject("localActionLayouts");if(layouts==null){layouts=new JSONObject();report.put("localActionLayouts",layouts);}layouts.put(phase,new JSONObject().put("description",action.getContentDescription()).put("bounds",rectJson(screenBounds(action))).put("uncoveredBounds",rectJson(allowed)));});}
    /** Intercepts the real View click's framework activity-start dispatch and returns cancellation.
     * The external picker/permission-controller UI is deliberately not opened or certified. */
    private Intent cancelNativeLaunch(View action,String expectedAction,String phase)throws Exception{
        AtomicReference<Intent> observed=new AtomicReference<>();ActivityMonitor monitor=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){if(!expectedAction.equals(intent.getAction()))return null;observed.set(new Intent(intent));return new ActivityResult(Activity.RESULT_CANCELED,null);}};addMonitor(monitor);
        try{ui(()->check(action.performClick(),"actual-native-action-click-dispatched-"+phase));until(()->observed.get()!=null,"actual-native-action-reaches-platform-start-dispatch-"+phase);waitForIdleSync();check(monitor.getHits()==1,"single-native-platform-launch-intercepted-"+phase);return observed.get();}finally{removeMonitor(monitor);}
    }
    private void localCancelledPlatformActions(Object local,Object repository)throws Exception{
        int imports=((Number)get(local,"importRequest")).intValue(),requests=((Number)get(local,"phoneRequest")).intValue();Intent permission=cancelNativeLaunch((View)get(local,"permissionButton"),"android.content.pm.action.REQUEST_PERMISSIONS","authorization");String[] permissions=permission.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES");check(permissions!=null&&Arrays.asList(permissions).contains("android.permission.READ_MEDIA_IMAGES"),"central-authorization-requests-real-phone-image-permission");until(()->((Number)get(local,"phoneRequest")).intValue()>requests&&invoke(repository,"access").toString().equals("NONE"),"cancelled-native-authorization-restores-denied-library");
        Intent picker=cancelNativeLaunch((View)get(local,"pickerFab"),Intent.ACTION_OPEN_DOCUMENT,"document-picker");String[] mimes=picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);check(picker.hasCategory(Intent.CATEGORY_OPENABLE)&&"image/*".equals(picker.getType())&&mimes!=null&&Arrays.asList(mimes).containsAll(List.of("image/jpeg","image/png"))&&picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false),"floating-picker-dispatches-openable-multiple-JPEG-PNG-document-intent");check((picker.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0&&(picker.getFlags()&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0,"floating-picker-requests-read-and-persistable-access");until(()->!(Boolean)get(local,"importing")&&((View)get(local,"root")).isShown(),"cancelled-native-picker-restores-local-library");check(((Number)get(local,"importRequest")).intValue()==imports&&((List<?>)get(local,"phonePhotos")).isEmpty()&&invoke(repository,"access").toString().equals("NONE"),"cancelled-picker-imports-nothing-and-keeps-denied-library-usable");report.put("localPlatformActions",new JSONObject().put("boundary","Actual native View clicks reached framework start-activity dispatch; ActivityMonitor returned RESULT_CANCELED. External document-picker and permission-controller rendering/text are not certified.").put("permissionAction",permission.getAction()).put("permissionNames",new JSONArray(Arrays.asList(permissions))).put("pickerAction",picker.getAction()).put("pickerMime",picker.getType()).put("pickerMimeTypes",new JSONArray(Arrays.asList(mimes))).put("cancelledWithoutImport",true));
    }
    private void assertLocalPhotoRowVisible(Object local,String phase)throws Exception{
        ui(()->((ListView)get(local,"list")).setSelection(0));waitForIdleSync();until(()->((ListView)get(local,"list")).getChildCount()>0,"local-native-list-layout-ready-"+phase);ui(()->{
            ListView list=(ListView)get(local,"list");android.graphics.Rect allowed=localUncoveredBounds(local),listBounds=new android.graphics.Rect();list.getGlobalVisibleRect(listBounds);if(!allowed.intersect(listBounds))allowed.setEmpty();JSONArray candidates=new JSONArray();boolean complete=false;
            for(int index=0;index<list.getChildCount();index++){View row=list.getChildAt(index);if(!(row instanceof LinearLayout strip))continue;JSONArray tiles=new JSONArray();boolean rowVisible=true;int photos=0;for(int column=0;column<strip.getChildCount();column++){View child=strip.getChildAt(column);if(!(child instanceof LinearLayout tile)||!tile.isClickable()||tile.getChildCount()<2||!(tile.getChildAt(1) instanceof TextView filename))continue;ImageView image=find(tile,ImageView.class);photos++;boolean visible=image!=null&&fullyVisibleWithin(image,allowed)&&fullyVisibleWithin(filename,allowed)&&image.getWidth()>=pixelsDp(48)&&image.getHeight()>=pixelsDp(48)&&filename.getLayout()!=null&&!filename.getText().toString().isEmpty();rowVisible&=visible;tiles.put(new JSONObject().put("filename",filename.getText()).put("image",image==null?JSONObject.NULL:rectJson(screenBounds(image))).put("filenameBounds",rectJson(screenBounds(filename))).put("fullyVisibleAboveNavigation",visible));}if(photos>0){candidates.put(new JSONObject().put("listPosition",list.getFirstVisiblePosition()+index).put("tiles",tiles));if(rowVisible)complete=true;}}
            JSONObject layouts=report.optJSONObject("localViewportLayouts");if(layouts==null){layouts=new JSONObject();report.put("localViewportLayouts",layouts);}layouts.put(phase,new JSONObject().put("uncoveredListBounds",rectJson(allowed)).put("rows",candidates));check(complete,"complete-native-photo-row-and-filenames-visible-above-floating-navigation-"+phase);
        });
    }
    private void assertLocalTextVisible(Object local,TextView text,String phase)throws Exception{waitForIdleSync();ui(()->{android.graphics.Rect allowed=localUncoveredBounds(local);android.text.Layout layout=text.getLayout();check(fullyVisibleWithin(text,allowed),"local-guidance-entire-view-visible-above-navigation-"+phase);boolean complete=layout!=null&&layout.getLineCount()>0;if(complete){int last=layout.getLineCount()-1;complete=layout.getLineEnd(last)>=text.length()&&layout.getLineBottom(last)<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom();for(int line=0;line<layout.getLineCount();line++)complete&=layout.getEllipsisCount(line)==0;}check(complete,"local-guidance-entire-text-laid-out-"+phase);JSONObject layouts=report.optJSONObject("localGuidanceLayouts");if(layouts==null){layouts=new JSONObject();report.put("localGuidanceLayouts",layouts);}layouts.put(phase,new JSONObject().put("text",text.getText()).put("bounds",rectJson(screenBounds(text))).put("uncoveredBounds",rectJson(allowed)));});}
    private long allEffectFiles()throws Exception{long count=0;for(File directory:List.of(new File(getTargetContext().getFilesDir(),"pictures/camera-album"),new File(getTargetContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),"camera-album"))){File[] files=directory.listFiles();if(files!=null)for(File file:files)if(file.isFile()&&file.getName().startsWith("effect-"))count++;}return count;}
    private void localRawDestinations(String run)throws Exception{
        Object controller=get(get(activity,"album"),"controller"),store=invoke(controller,"rawStore");File temporary=(File)invoke(store,"createTemporary");byte[] nef=nativeNefFixture(run);Files.write(temporary.toPath(),nef);String identity=sha(("offline-raw:"+run).getBytes(java.nio.charset.StandardCharsets.UTF_8));Object raw=invoke(store,"commit",new Class<?>[]{String.class,File.class,String.class},identity,temporary,"QA_OFFLINE_"+run+".NEF");File source=(File)invoke(raw,"file");String hash=sha(source);long modified=source.lastModified(),before=mediaRows();
        ui(()->navigationCell(activity.getWindow().getDecorView(),"同步").performClick());Object rawPanel=get(get(activity,"album"),"rawPanel");getTargetContext().getSharedPreferences("photo-save-destinations",Context.MODE_PRIVATE).edit().remove("sync-raw").commit();ui(()->invoke(rawPanel,"open",new Class<?>[]{boolean.class},true));until(()->!(Boolean)get(rawPanel,"loading")&&rawIndex(rawPanel,identity)>=0,"actual-offline-RAW-list-has-verified-structural-NEF-fixture");
        rawSaveChoice(rawPanel,identity,null);until(()->get(rawPanel,"operation")!=null&&!(Boolean)get(get(rawPanel,"operation"),"busy"),"actual-RAW-app-only-operation-finishes");Object retained=get(rawPanel,"operation");check((Boolean)get(retained,"complete")&&get(retained,"saveTarget").toString().equals("APP_ONLY")&&mediaRows()==before,"RAW-default-app-only-validates-durable-source-without-MediaStore-insertion");until(()->activeWindowHas("完成"),"RAW-app-only-progress-shows-completion");clickActiveText("完成");
        rawSaveChoice(rawPanel,identity,"保存到手机相册");until(()->get(rawPanel,"operation")!=retained&&!(Boolean)get(get(rawPanel,"operation"),"busy"),"actual-explicit-RAW-phone-operation-finishes");Object published=get(rawPanel,"operation");check((Boolean)get(published,"complete")&&get(published,"saveTarget").toString().equals("PHONE_ALBUM")&&mediaRows()==before+1,"explicit-RAW-phone-choice-adds-one-system-row");String uri=publishRawSync(controller,raw);check(hash.equals(uriHash(Uri.parse(uri)))&&mediaRows()==before+1,"actual-RAW-publisher-is-byte-exact-and-idempotent");clickActiveText("完成");
        byte[] damaged=nef.clone();damaged[270]^=37;Object failed;try{Files.write(source.toPath(),damaged);rawSaveChoice(rawPanel,identity,"保存到手机相册");until(()->get(rawPanel,"operation")!=published&&!(Boolean)get(get(rawPanel,"operation"),"busy"),"changed-RAW-source-fails-before-publication");failed=get(rawPanel,"operation");check(!(Boolean)get(failed,"complete")&&!get(failed,"error").toString().isEmpty()&&mediaRows()==before+1,"changed-private-RAW-is-rejected-without-rewriting-public-copy");}finally{Files.write(source.toPath(),nef);boolean restoredTime=source.setLastModified(modified);String restoredHash=sha(source);report.put("rawFaultRestoration",new JSONObject().put("scope","Only the current run's generated structural NEF fixture").put("sourceIdentity",identity).put("expectedSha256",hash).put("actualSha256",restoredHash).put("expectedModified",modified).put("actualModified",source.lastModified()));check(restoredTime&&source.lastModified()==modified&&hash.equals(restoredHash),"fixture-restores-original-RAW-bytes-and-mtime-after-controlled-fault");}
        getTargetContext().getSharedPreferences("photo-save-destinations",Context.MODE_PRIVATE).edit().putString("sync-raw","APP_ONLY").commit();until(()->activeWindowHas("重试"),"RAW-failed-operation-offers-explicit-retry");clickActiveText("重试");until(()->get(rawPanel,"operation")!=failed&&!(Boolean)get(get(rawPanel,"operation"),"busy"),"RAW-explicit-retry-finishes-original-operation-target");Object retried=get(rawPanel,"operation");check((Boolean)get(retried,"complete")&&get(retried,"saveTarget").toString().equals("PHONE_ALBUM")&&mediaRows()==before+1,"RAW-retry-keeps-frozen-phone-target-despite-current-app-only-preference");check(hash.equals(sha(source))&&source.lastModified()==modified&&hash.equals(uriHash(Uri.parse(uri))),"RAW-retry-preserves-local-and-public-original-bytes-and-time");clickActiveText("完成");
        report.put("rawFixture",new JSONObject().put("kind","Hand-authored 512-byte TIFF/NEF structure fixture; not captured sensor data or a camera certification").put("sha256",hash).put("sourceIdentity",identity).put("phoneUri",uri).put("rowsBefore",before).put("rowsAfterAppOnly",before).put("rowsAfterPhoneAndRetry",mediaRows()));ui(()->invoke(activity,"goBack"));ui(()->navigationCell(activity.getWindow().getDecorView(),"相册").performClick());
    }
    private int rawIndex(Object rawPanel,String identity)throws Exception{List<?> files=(List<?>)get(rawPanel,"savedFiles");for(int index=0;index<files.size();index++)if(identity.equals(invoke(files.get(index),"sourceIdentity")))return index;return -1;}
    private void rawSaveChoice(Object rawPanel,String identity,String choice)throws Exception{
        until(()->!(Boolean)get(rawPanel,"loading")&&rawIndex(rawPanel,identity)>=0,"verified-RAW-source-remains-listed-for-explicit-save");int index=rawIndex(rawPanel,identity);ui(()->((ListView)get(rawPanel,"list")).setSelection(index));until(()->{ListView list=(ListView)get(rawPanel,"list");return index>=list.getFirstVisiblePosition()&&index<=list.getLastVisiblePosition()&&list.getChildAt(index-list.getFirstVisiblePosition())!=null;},"actual-RAW-file-row-is-visible");ui(()->{ListView list=(ListView)get(rawPanel,"list");list.performItemClick(list.getChildAt(index-list.getFirstVisiblePosition()),index,list.getAdapter().getItemId(index));});until(()->activeWindowHas("保存原文件"),"actual-RAW-row-opens-original-file-action");clickActiveText("保存原文件");until(()->activeWindowHas("RAW 保存位置"),"actual-RAW-action-opens-destination-chooser");if(choice==null)check(activeChecked("仅保存在 App 内"),"fresh-RAW-save-choice-defaults-to-app-only");else{clickActiveText(choice);until(()->activeChecked(choice),"actual-RAW-radio-selects-explicit-phone-target");}clickActiveText("保存");waitForIdleSync();
    }
    private String publishRawSync(Object controller,Object raw)throws Exception{CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>(),error=new AtomicReference<>();Object callback=callback("album.AlbumController$Callback",(method,values)->{if(method.equals("onSuccess"))result.set(values[0].toString());else error.set(values[0].toString());done.countDown();});invoke(controller,"publishRaw",new Class<?>[]{type("album.RawPhotoDownload"),type("album.AlbumController$Callback")},raw,callback);check(done.await(20,TimeUnit.SECONDS)&&error.get()==null&&result.get()!=null,"actual-RAW-local-publisher-callback-completes");return result.get();}
    private byte[] nativeNefFixture(String run){java.nio.ByteBuffer out=java.nio.ByteBuffer.allocate(512).order(java.nio.ByteOrder.LITTLE_ENDIAN);out.put((byte)'I').put((byte)'I').putShort((short)42).putInt(8).putShort((short)6);nefEntry(out,0x010f,2,6,100);nefEntry(out,0x0100,4,1,2);nefEntry(out,0x0101,4,1,2);out.putShort((short)0x0106).putShort((short)3).putInt(1).putShort((short)32803).putShort((short)0);nefEntry(out,0x0111,4,1,256);nefEntry(out,0x0117,4,1,32);out.putInt(0);out.position(100);out.put(new byte[]{'N','I','K','O','N',0});for(int index=256;index<288;index++)out.put(index,(byte)(index^run.hashCode()));return out.array();}
    private static void nefEntry(java.nio.ByteBuffer out,int tag,int type,int count,int value){out.putShort((short)tag).putShort((short)type).putInt(count).putInt(value);}
    private Object phoneItem(Object local,String filename)throws Exception{for(Object item:(List<?>)get(local,"phonePhotos")){Object photo=get(item,"phone");if(photo!=null&&filename.equals(get(photo,"filename")))return item;}return null;}
    private Object openPhone(Object local,String filename,boolean png)throws Exception{
        ui(()->invoke(local,"switchTab",new Class<?>[]{boolean.class},true));until(()->phoneItem(local,filename)!=null,"native-phone-item-ready-"+filename);
        until(()->photoTile((View)get(local,"root"),filename)!=null,"phone-source-has-real-visible-thumbnail-tile-"+filename);ui(()->photoTile((View)get(local,"root"),filename).performClick());
        if(png){until(()->activeWindowHas("以白底编辑 PNG"),"PNG-white-background-disclosure-visible");clickActiveText("继续编辑");}
        String stem=filename.substring(0,filename.lastIndexOf('.'));
        until(()->get(activity,"localPhotoEditor")!=null&&get(get(activity,"localPhotoEditor"),"localDetail")!=null&&((String)invoke(get(get(activity,"localPhotoEditor"),"localDetail"),"filename")).startsWith(stem)&&get(get(activity,"localPhotoEditor"),"filterEditor")!=null&&(Boolean)invoke(get(get(activity,"localPhotoEditor"),"filterEditor"),"isReady"),"actual-local-root-opens-shared-editor-"+filename);
        panel=get(activity,"localPhotoEditor");editor=get(panel,"filterEditor");check(get(editor,"bound")==get(panel,"localDetail"),"local-workspace-binds-the-selected-durable-photo");return get(panel,"localDetail");
    }
    private View photoTile(View root,String filename){CharSequence description=root.getContentDescription();if(root.isShown()&&root.isClickable()&&description!=null&&description.toString().startsWith(filename))return root;if(root instanceof ViewGroup group)for(int index=0;index<group.getChildCount();index++){View found=photoTile(group.getChildAt(index),filename);if(found!=null)return found;}return null;}
    private View navigationCell(View root,String label){CharSequence description=root.getContentDescription();if(root.isShown()&&root.isClickable()&&description!=null&&(description.toString().equals(label)||description.toString().startsWith(label+"，")))return root;if(root instanceof ViewGroup group)for(int index=0;index<group.getChildCount();index++){View found=navigationCell(group.getChildAt(index),label);if(found!=null)return found;}return null;}
    private boolean activeChecked(String text){android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(text.contentEquals(node.getText())&&node.isChecked())return true;return false;}
    private void returnToLocalAlbum()throws Exception{for(int step=0;step<4&&(Boolean)invoke(panel,"isImmersive");step++)ui(()->invoke(activity,"goBack"));until(()->!(Boolean)invoke(panel,"isImmersive")&&((View)get(get(activity,"localAlbum"),"root")).isShown(),"back-restores-local-album-root");}
    private Uri insertMedia(String name,String mime,byte[] bytes)throws Exception{android.content.ContentValues values=new android.content.ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,mime);values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/HueviaLocalQA/");values.put(MediaStore.Images.Media.DATE_TAKEN,978404645000L);values.put(MediaStore.Images.Media.IS_PENDING,1);Uri uri=getTargetContext().getContentResolver().insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values);check(uri!=null,"native-provider-fixture-row-created-"+mime);try(java.io.OutputStream stream=getTargetContext().getContentResolver().openOutputStream(uri)){stream.write(bytes);}values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);check(getTargetContext().getContentResolver().update(uri,values,null,null)==1,"native-provider-fixture-committed-"+mime);return uri;}
    private long mediaRows()throws Exception{try(Cursor cursor=getTargetContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{MediaStore.Images.Media._ID},null,null,null)){check(cursor!=null,"actual-MediaStore-row-count-readable");return cursor.getCount();}}
    private String uriHash(Uri uri)throws Exception{try(InputStream stream=getTargetContext().getContentResolver().openInputStream(uri)){return sha(stream.readAllBytes());}}
    private void shell(String command)throws Exception{try(android.os.ParcelFileDescriptor descriptor=getUiAutomation().executeShellCommand(command);InputStream stream=new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)){String response=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);check(response.isBlank(),"native-shell-command-completes-"+command.split(" ")[1]);}}

    /** UI-only typed camera-slot fixtures never enter a physical transport. */
    private void orangeScope()throws Exception{
        panel=get(activity,"album");batch=get(panel,"photoPresets");
        ui(()->{while((Boolean)invoke(panel,"canGoBack"))invoke(panel,"goBack");invoke(activity,"switchRoot",new Class<?>[]{int.class},0);set(activity,"localTab",true);set(activity,"message","");invoke(activity,"render");});
        until(()->get(activity,"list")!=null&&((GridView)get(activity,"list")).getChildCount()>0,"orange-real-preset-grid-mounted");
        until(()->((LruCache<?,?>)get(activity,"imageCache")).size()>0,"orange-real-preset-template-rendered");
        if(args.getString("mode","").equals("landscape")){ui(()->{GridView grid=(GridView)get(activity,"list");View card=grid.getChildAt(0);check(card!=null&&grid.getHeight()>=card.getHeight(),"orange-landscape-first-complete-preset-card-has-space");});}
        check(hasText(activity.getWindow().getDecorView(),"发现预设")||hasText(activity.getWindow().getDecorView(),"我的色彩"),"orange-preset-section-has-real-source");
        boolean rail=activity.getResources().getConfiguration().screenWidthDp>=600;
        View first=navigationCell(activity.getWindow().getDecorView(),"预设"),last=navigationCell(activity.getWindow().getDecorView(),"设置");
        check(rail?screenBounds(first).top<screenBounds(last).top:screenBounds(first).left<screenBounds(last).left,"orange-navigation-adapts-bottom-bar-or-rail");
        capture("presets");
        ui(()->invoke(activity,"openPreset",new Class<?>[]{Object.class,boolean.class},ncp,false));
        until(()->hasText(activity.getWindow().getDecorView(),"导入相机"),"orange-real-NCP-detail-opens");capture("preset-detail");
        ui(()->invoke(activity,"goBack"));
        // Construct actual typed native confirmation with nine deterministic observed slots.
        Object connection=construct("MainActivity$CameraConnection",new Class<?>[]{String.class,int.class,type("usb.WirelessPictureControl")},"192.0.2.1",15740,null);
        List<Object> slots=new ArrayList<>();
        for(int i=0;i<9;i++){Object slot=construct("usb.UsbPictureControlProbe$ItemRecord",new Class<?>[]{int.class,byte[].class},201+i,cc.huevia.ncp.NcpCompatibilityFixtures.expectedData(4,0));set(slot,"empty",i>1);slots.add(slot);}
        Object plan=construct("MainActivity$PendingConfirmation",new Class<?>[]{type("MainActivity$CameraConnection"),String.class,type("library.PresetRecord"),byte[].class,List.class},connection,"Nikon Z6II · QA",ncp,ncpBytes,slots);
        set(plan,"conversion",invokeStatic("ncp.NcpPictureControlConverter","convert",new Class<?>[]{byte[].class,int.class,int.class},ncpBytes,4,0));
        ui(()->{set(activity,"confirmation",plan);invoke(activity,"render");});
        until(()->slotButton(2)!=null&&slotButton(2).getWidth()>0,"orange-nine-slot-native-grid-is-laid-out");
        check(slotButton(0)!=null&&slotButton(8)!=null,"orange-all-nine-slots-present");
        ui(()->slotButton(0).performClick());check(((Number)get(plan,"targetIndex")).intValue()==0&&slotButton(0).isSelected(),"orange-occupied-slot-selection-updates-real-plan");
        check(hasText(activity.getWindow().getDecorView(),"覆盖 C1…"),"orange-occupied-slot-exposes-overwrite-confirmation-action");
        // Click the real confirmation action, then cancel before any write.
        ui(()->buttonText(activity.getWindow().getDecorView(),"覆盖 C1…").performClick());
        until(()->activeWindowHas("确认覆盖 C1"),"orange-existing-second-overwrite-confirmation-retained");
        clickActiveText("取消");ui(()->slotButton(2).performClick());
        check(((Number)get(plan,"targetIndex")).intValue()==2&&slotButton(2).isSelected(),"orange-empty-slot-selection-updates-real-plan");
        until(()->{if((Boolean)get(get(activity,"colors"),"dark"))return true;return (activity.getWindow().getInsetsController().getSystemBarsAppearance()&WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)!=0;},"orange-cancel-restores-light-surface-system-icon-appearance");
        capture("import-slots");
        ui(()->{set(activity,"confirmation",null);invoke(activity,"render");invoke(activity,"openPreset",new Class<?>[]{Object.class,boolean.class},supported,false);});
        until(()->hasText(activity.getWindow().getDecorView(),"前往相册"),"orange-NP3-detail-shows-mobile-editor-destination");
        check(!hasText(activity.getWindow().getDecorView(),"导入相机"),"orange-NP3-does-not-expose-camera-write");
        ui(()->buttonText(activity.getWindow().getDecorView(),"前往相册").performClick());check((Boolean)get(activity,"localAlbumTab"),"orange-NP3-opens-dedicated-local-album-root");
        capture("album");
        ui(()->invoke(activity,"switchRoot",new Class<?>[]{int.class},1));ui(()->invoke(panel,"chooseTab",new Class<?>[]{boolean.class},false));capture("sync");ui(()->invoke(panel,"chooseTab",new Class<?>[]{boolean.class},true));capture("downloaded");
        ui(()->invoke(panel,"choosePhotoLive"));until(()->((View)get(panel,"liveAction")).getHeight()>0,"orange-live-native-actions-laid-out");ui(()->{for(String field:List.of("liveAction","projectPresetAction","liveProjectChoice","liveProjectInfo")){View hit=(View)get(panel,field);check(hit.getWidth()>=pixelsDp(48)&&hit.getHeight()>=pixelsDp(48),"orange-live-48dp-measured-hit-"+field);}Button records=buttonText(activity.getWindow().getDecorView(),"接收记录");check(records!=null&&records.getWidth()>=pixelsDp(48)&&records.getHeight()>=pixelsDp(48),"orange-receipt-48dp-measured-hit");});capture("live");
        ui(()->invoke(activity,"switchRoot",new Class<?>[]{int.class},2));capture("settings");
        ui(()->invoke(activity,"openCameraSettings"));capture("wifi");ui(()->invoke(activity,"goBack"));
        ui(()->invoke(get(activity,"account"),"open"));capture("account");ui(()->invoke(activity,"goBack"));
        ui(()->invoke(get(activity,"updates"),"open"));capture("updates");ui(()->invoke(activity,"goBack"));
        ui(()->invoke(activity,"switchRoot",new Class<?>[]{int.class},1));open(c);choose(ncp,65);capture("editor");
        check(navigationCell(activity.getWindow().getDecorView(),"设置")==null,"orange-editor-hides-root-navigation");
        android.graphics.Rect before=screenBounds((View)get(panel,"viewerImage"));ui(()->((Button)get(editor,"adjustmentModeButton")).performClick());
        check(before.equals(screenBounds((View)get(panel,"viewerImage"))),"orange-tool-mode-preserves-exact-photo-canvas");capture("editor-adjustments");
        Object comparison=get(panel,"viewerComparison");View compareHit=(View)invoke(comparison,"getButtonView");ui(()->{long now=android.os.SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,compareHit.getWidth()/2f,compareHit.getHeight()/2f,0);compareHit.dispatchTouchEvent(down);down.recycle();});check(pixels((Bitmap)get(get(panel,"viewerImage"),"photo")).equals(pixels((Bitmap)invoke(editor,"original"))),"orange-scrim-compare-hold-still-shows-original");ui(()->{long now=android.os.SystemClock.uptimeMillis();MotionEvent up=MotionEvent.obtain(now-100,now,MotionEvent.ACTION_UP,compareHit.getWidth()/2f,compareHit.getHeight()/2f,0);compareHit.dispatchTouchEvent(up);up.recycle();});check(pixels((Bitmap)get(get(panel,"viewerImage"),"photo")).equals(pixels((Bitmap)invoke(editor,"preview"))),"orange-scrim-compare-release-restores-effect");
        Object crop=openCrop();cropButton(crop,"9:16");capture("crop");cropButton(crop,"取消");
        report.put("orangeNativeScope","Real native Views with real template-rendered presets and app JPEGs. Camera slot confirmation is a typed synthetic UI fixture; no physical transport write. Account/update remain offline; no account changes or install operation.");
    }
    private Button slotButton(int index)throws Exception{return buttonDescription(activity.getWindow().getDecorView(),"C"+(index+1)+" · ");}
    private Button buttonDescription(View view,String prefix){if(view instanceof Button b&&b.getContentDescription()!=null&&b.getContentDescription().toString().startsWith(prefix))return b;if(view instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){Button b=buttonDescription(g.getChildAt(i),prefix);if(b!=null)return b;}return null;}
    private Button buttonText(View view,String label){if(view instanceof Button b&&label.contentEquals(b.getText()))return b;if(view instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){Button b=buttonText(g.getChildAt(i),label);if(b!=null)return b;}return null;}

    private void seed()throws Exception{
        npBytes=asset("supported.np3");ncpBytes=cc.huevia.ncp.NcpCompatibilityFixtures.ncp();
        supported=get(invoke(library,"importFile",new Class<?>[]{String.class,byte[].class},"冷杉绿调.NP3",npBytes),"record");
        ncp=get(invoke(library,"importFile",new Class<?>[]{String.class,byte[].class},"暖阳胶片.NCP",ncpBytes),"record");
        unsupported=get(invoke(library,"importFile",new Class<?>[]{String.class,byte[].class},"蓝调颗粒.NP3",asset("unsupported.np3")),"record");
        invoke(library,"setCategory",new Class<?>[]{String.class,String.class},get(supported,"id"),"风景");invoke(library,"setCategory",new Class<?>[]{String.class,String.class},get(ncp,"id"),"胶片");
        File dir=new File(getTargetContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),"camera-album");dir.mkdirs();
        for(int i=0;i<56;i++){byte[] fixture=ncpBytes.clone();Arrays.fill(fixture,16,36,(byte)0);byte[] name=("LIBRARY_"+String.format(java.util.Locale.ROOT,"%02d",i)).getBytes(java.nio.charset.StandardCharsets.US_ASCII);System.arraycopy(name,0,fixture,16,name.length);corpus.add(get(invoke(library,"importFile",new Class<?>[]{String.class,byte[].class},"library-"+i+".NCP",fixture),"record"));}
        prepareOnlineFixture();seedOnlineCatalog();
        a=source(dir,"A",1);b=source(dir,"B",args.getString("scope","").equals("composition-recipes")?6:1);c=source(dir,"C",6);ui(()->invoke(panel,"refreshDownloads"));until(()->invoke(panel,"photoGroup",new Class<?>[]{type("album.DownloadedPhoto")},a)!=null,"catalog-photo-group-ready");
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
    private Button namedButton(View root,String text){if(root instanceof Button&&((Button)root).getText().toString().equals(text))return (Button)root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){Button b=namedButton(group.getChildAt(i),text);if(b!=null)return b;}}return null;}
    private Object openCrop()throws Exception{ui(()->invoke(editor,"showComposition"));until(()->get(editor,"popup")!=null&&get(editor,"popup").getClass().getName().endsWith("PhotoCropDialog"),"native-crop-workspace-open");Object dialog=get(editor,"popup");until(()->((View)get(dialog,"crop")).getWidth()>0,"crop-canvas-laid-out");return dialog;}
    private void cropButton(Object dialog,String name)throws Exception{ui(()->{Button button=namedButton(((android.app.Dialog)dialog).getWindow().getDecorView(),name);check(button!=null&&button.isShown(),"crop-action-"+name);button.performClick();});waitForIdleSync();}
    private void tapNative(View view)throws Exception{
        int[] point=new int[2];ui(()->{check(view.isShown()&&view.isEnabled(),"native-tap-target-visible");view.getLocationOnScreen(point);point[0]+=view.getWidth()/2;point[1]+=view.getHeight()/2;report.put("lastNativeTap",new JSONObject().put("x",point[0]).put("y",point[1]).put("width",view.getWidth()).put("height",view.getHeight()));});
        long now=android.os.SystemClock.uptimeMillis();for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){MotionEvent event=MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),action,point[0],point[1],0);sendPointerSync(event);event.recycle();if(action==MotionEvent.ACTION_DOWN)Thread.sleep(80);}waitForIdleSync();
    }
    private void tapCategory(String name,boolean popup)throws Exception{
        LinearLayout row=(LinearLayout)get(editor,popup?"pickerCategories":"categories");View target=null;
        for(int i=0;i<row.getChildCount();i++)if(name.equals(row.getChildAt(i).getTag()))target=row.getChildAt(i);
        check(target!=null,"category-exists-"+name);final View button=target;
        ui(()->((HorizontalScrollView)row.getParent()).scrollTo(Math.max(0,button.getLeft()-pixelsDp(8)),0));waitForIdleSync();tapNative(button);
        check(name.equals(get(editor,"category")),"native-category-tap-selects-"+name);
    }
    private void verifyPickerCategory(String name)throws Exception{
        List<String> expected=new ArrayList<>();for(Object entry:(List<?>)invoke(editor,"activeEntries"))if(name.isEmpty()||name.equals(invokeStatic("library.PresetCategories","label",new Class<?>[]{String.class},get(entry,"category"))))expected.add(get(entry,"id").toString());
        BaseAdapter adapter=(BaseAdapter)get(editor,"gridAdapter");check(adapter.getCount()==expected.size()+1,"picker-filtered-count-"+name);
        for(int i=1;i<adapter.getCount();i++)check(expected.get(i-1).equals(get(adapter.getItem(i),"id")),"picker-contains-only-matching-category-"+name+"-"+i);
        check(adapter.getItem(0)==null,"picker-preserves-no-filter-action-"+name);
    }
    private List<Object> readyLiveCards(Object page)throws Exception{
        List<Object> cards=new ArrayList<>();ListView list=(ListView)get(page,"list");if(list==null)return cards;
        for(int i=0;i<list.getChildCount();i++){Object row=list.getChildAt(i);List<Object> candidates=new ArrayList<>();if(row.getClass().getSimpleName().equals("CardRow")){candidates.add(get(row,"first"));candidates.add(get(row,"second"));}else if(row.getClass().getSimpleName().equals("Card"))candidates.add(row);
            for(Object card:candidates)if(get(card,"entry")!=null&&((ImageView)get(card,"image")).getDrawable() instanceof android.graphics.drawable.BitmapDrawable)cards.add(card);
        }return cards;
    }
    private Object attachedLiveCard(Object page,Object entry)throws Exception{
        ListView list=(ListView)get(page,"list");for(int i=0;i<list.getChildCount();i++){Object row=list.getChildAt(i);if(row.getClass().getSimpleName().equals("CardRow")){for(String name:new String[]{"first","second"}){Object card=get(row,name);if(get(card,"entry")==entry)return card;}}else if(get(row,"entry")==entry)return row;}return null;
    }
    private void checkLiveFailure(Object page,Object entry,String state,boolean retry)throws Exception{
        ui(()->{((BaseAdapter)get(page,"adapter")).notifyDataSetChanged();});waitForIdleSync();
        ui(()->{Object card=attachedLiveCard(page,entry);check(card!=null,"live-"+state+"-card-remains-attached");check(get(entry,"state").toString().equals(state),"live-"+state+"-survives-adapter-rebind");check(((ImageView)get(card,"image")).getDrawable()==null&&get(card,"boundPreview")==null,"live-"+state+"-clears-stale-image");check(!((View)get(card,"content")).isEnabled(),"live-"+state+"-content-disabled");check(((Button)get(card,"retry")).getVisibility()==(retry?View.VISIBLE:View.GONE),"live-"+state+"-retry-visibility");});
    }
    /** Controlled offline failures exercise actual choose/read/profile paths, not production content. */
    private void liveConfirmationFailures(Object page)throws Exception{
        stage="live-confirmation-failure-regression";until(()->!readyLiveCards(page).isEmpty(),"live-success-image-ready-before-confirmation-failure");
        final Object[] entry={null},saved={null};final Map<String,Object> records=(Map<String,Object>)get(get(page,"library"),"records");
        ui(()->{entry[0]=get(readyLiveCards(page).get(0),"entry");String id=get(get(entry[0],"preset"),"id").toString();synchronized(get(page,"library")){saved[0]=records.remove(id);}check(saved[0]!=null,"live-read-failure-fixture-removes-only-in-memory-record");check(((View)get(attachedLiveCard(page,entry[0]),"content")).performClick(),"live-ready-confirmation-action-invoked");});
        try{until(()->!(Boolean)get(page,"selecting")&&get(entry[0],"state").toString().equals("ERROR"),"actual-confirmation-read-failure-stays-error");checkLiveFailure(page,entry[0],"ERROR",true);}
        finally{synchronized(get(page,"library")){records.put(get(saved[0],"id").toString(),saved[0]);}}
        ui(()->check(((Button)get(attachedLiveCard(page,entry[0]),"retry")).performClick(),"live-error-bound-retry-action-invoked"));
        until(()->get(entry[0],"state").toString().equals("READY")&&((ImageView)get(attachedLiveCard(page,entry[0]),"image")).getDrawable()!=null,"live-error-retry-rerenders-restored-preset");
        ui(()->check(((View)get(attachedLiveCard(page,entry[0]),"content")).isEnabled(),"live-success-selectable-after-error-retry"));
        // Seed a stale READY thumbnail on a known unsupported fixture to reach real confirmation
        // profile rejection. This injection is explicitly UI-state coverage, not rendering support.
        final Object[] invalid={null};ui(()->{List<?> entries=(List<?>)get(page,"entries");int index=-1;for(int i=0;i<entries.size();i++)if(get(get(entries.get(i),"preset"),"id").equals(get(unsupported,"id"))){invalid[0]=entries.get(i);index=i;break;}check(index>=0,"live-profile-failure-fixture-present");Bitmap thumbnail=((android.graphics.drawable.BitmapDrawable)((ImageView)get(attachedLiveCard(page,entry[0]),"image")).getDrawable()).getBitmap();set(invalid[0],"state",Enum.valueOf((Class)get(invalid[0],"state").getClass(),"READY"));((LruCache<String,Bitmap>)get(page,"cache")).put(invoke(page,"key",new Class<?>[]{invalid[0].getClass()},invalid[0]).toString(),thumbnail);((BaseAdapter)get(page,"adapter")).notifyDataSetChanged();((ListView)get(page,"list")).setSelection(1+index/((Number)get(page,"columns")).intValue());});
        until(()->attachedLiveCard(page,invalid[0])!=null&&((View)get(attachedLiveCard(page,invalid[0]),"content")).isEnabled(),"stale-ready-profile-fixture-bound");
        ui(()->check(((View)get(attachedLiveCard(page,invalid[0]),"content")).performClick(),"live-profile-confirmation-action-invoked"));until(()->!(Boolean)get(page,"selecting")&&get(invalid[0],"state").toString().equals("UNSUPPORTED"),"actual-confirmation-profile-failure-stays-unsupported");checkLiveFailure(page,invalid[0],"UNSUPPORTED",false);
        report.put("confirmationFailureFixture","ERROR: temporary in-memory record removal drives actual choose/read failure, restored before real retry/render. UNSUPPORTED: deliberately seeded stale READY thumbnail on known unsupported local fixture drives actual choose/profile rejection. No app preset files or production content changed; unsupported seeding is UI-state coverage, not support certification.");
    }
    private void filterNavigation()throws Exception{
        report.put("scope","Filter category touch navigation and live preset preview stability on isolated native emulator; real Views/MotionEvents and existing local template/offline recipe fixtures. No physical camera, live catalog, frame-rate or TalkBack certification.");
        stage="filter-category-native-taps";open(a);until(()->!(Boolean)get(editor,"catalogLoading")&&((List<?>)get(editor,"onlineEntries")).size()==23,"offline-online-category-fixture-ready");
        ui(()->invoke(editor,"showAll"));until(()->get(editor,"grid")!=null&&((GridView)get(editor,"grid")).getChildCount()>0,"filter-grid-laid-out");
        tapCategory("人像",true);verifyPickerCategory("人像");capture("picker-portrait");tapCategory("黑白",true);verifyPickerCategory("黑白");capture("picker-black-white");
        tapCategory("",true);verifyPickerCategory("");ui(()->invoke(editor,"dismissPopup"));
        if(getTargetContext().getPackageManager().getPackageInfo(TARGET,0).getLongVersionCode()>=82){
            HorizontalScrollView cats=(HorizontalScrollView)get(editor,"categoryScroll");ui(()->cats.scrollTo(0,0));waitForIdleSync();
            ui(()->{check(get(editor,"gridButton")==((LinearLayout)cats.getParent()).getChildAt(0),"grid-button-remains-leftmost");int visible=0;for(View child:children((ViewGroup)get(editor,"categories"))){android.graphics.Rect rect=new android.graphics.Rect();if(child.getGlobalVisibleRect(rect)&&rect.width()>=child.getWidth()-1)visible++;}check(visible>=2,"multiple-full-category-labels-visible");check(get(editor,"popup")==null,"inline-categories-do-not-open-popup");});capture("inline-categories");
            tapCategory("人像",false);check(get(editor,"popup")==null,"inline-category-tap-keeps-editor-open");
            ui(()->cats.scrollTo(0,0));waitForIdleSync();int[] xy=new int[2];ui(()->cats.getLocationOnScreen(xy));long now=android.os.SystemClock.uptimeMillis();float start=xy[0]+cats.getWidth()*.85f,end=xy[0]+cats.getWidth()*.1f,y=xy[1]+cats.getHeight()/2f;
            for(int i=0;i<8;i++){MotionEvent event=MotionEvent.obtain(now,now+i*24,i==0?MotionEvent.ACTION_DOWN:i==7?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,start+(end-start)*i/7,y,0);sendPointerSync(event);event.recycle();}waitForIdleSync();check(cats.getScrollX()>0,"native-horizontal-swipe-reveals-later-categories");capture("inline-scrolled");
            tapNative((View)get(editor,"gridButton"));until(()->get(editor,"grid")!=null,"grid-button-still-opens-full-filter-picker");verifyPickerCategory("人像");ui(()->invoke(editor,"dismissPopup"));
        }
        ui(()->invoke(editor,"setOnlineSource",new Class<?>[]{boolean.class},false));ui(()->invoke(editor,"showAll"));until(()->get(editor,"pickerCategories")!=null,"local-picker-ready");tapCategory("风景",true);verifyPickerCategory("风景");capture("picker-local");ui(()->invoke(editor,"dismissPopup"));
        stage="live-preset-preview-stability";final Object page=construct("ui.PresetSelectionPage",new Class<?>[]{Activity.class,boolean.class},activity,true);java.util.concurrent.atomic.AtomicInteger picked=new java.util.concurrent.atomic.AtomicInteger();
        Object callback=callback("ui.PresetSelectionPage$Callback",(name,values)->{if(name.equals("onSelected"))picked.incrementAndGet();});
        ui(()->invoke(page,"show",new Class<?>[]{String.class,String.class,boolean.class,BooleanSupplier.class,type("ui.PresetSelectionPage$Callback")},"默认预设","",true,(BooleanSupplier)()->true,callback));until(()->get(page,"list")!=null,"live-preset-real-picker-ready");boolean dark=(activity.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;report.put("darkTheme",dark);check(dark==args.getString("mode","").equals("dark"),"actual-theme-matches-requested-mode");capture("live-loading");until(()->!readyLiveCards(page).isEmpty(),"live-preset-has-ready-visible-image");
        ThreadPoolExecutor worker=(ThreadPoolExecutor)get(page,"worker");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        worker.execute(()->{entered.countDown();try{release.await(20,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        try{check(entered.await(15,TimeUnit.SECONDS),"live-preview-worker-held-for-cache-pressure");List<Object> visible=new ArrayList<>();ui(()->visible.addAll(readyLiveCards(page)));check(!visible.isEmpty(),"ready-cards-present-before-cache-pressure");List<android.graphics.drawable.Drawable> images=new ArrayList<>();List<Integer> tops=new ArrayList<>();ui(()->{for(Object card:visible){images.add(((ImageView)get(card,"image")).getDrawable());tops.add(((View)card).getTop());}((LruCache<?,?>)get(page,"cache")).evictAll();((BaseAdapter)get(page,"adapter")).notifyDataSetChanged();});waitForIdleSync();
            ui(()->{for(int i=0;i<visible.size();i++){Object card=visible.get(i);check(((ImageView)get(card,"image")).getDrawable()==images.get(i),"visible-ready-image-survives-cache-eviction-and-rebind-"+i);check(((View)card).getTop()==tops.get(i),"ready-preview-keeps-card-position-"+i);}});capture("live-stable");
        }finally{release.countDown();}
        liveConfirmationFailures(page);
        ui(()->{ListView list=(ListView)get(page,"list");list.setSelection(0);});until(()->{ListView list=(ListView)get(page,"list");return !list.isLayoutRequested()&&list.getFirstVisiblePosition()==0&&list.getChildCount()>0&&!list.getChildAt(0).isLayoutRequested();},"live-none-row-layout-settled");Object none=((ListView)get(page,"list")).getChildAt(0);check(get(none,"entry")==null,"live-none-row-is-actual-none-entry");ui(()->check(((View)get(none,"content")).performClick(),"live-none-bound-click-action-operates"));until(()->picked.get()==1,"live-none-selection-still-returns-once");
        ui(()->invoke(page,"close"));check((Boolean)invoke(editor,"isReady"),"picker-return-keeps-photo-editor-ready");
    }
    private void photoLiveWithoutProject()throws Exception{
        stage="photo-live-without-project";
        until(()->get(panel,"projectPresets")!=null&&get(get(panel,"projectPresets"),"store")!=null,"photo-live-preset-store-initialized-before-entry");
        check(get(panel,"selectedLiveProjectId").toString().isEmpty(),"photo-live-regression-starts-with-no-selected-project");
        for(int turn=0;turn<3;turn++){
            final int pass=turn;
            ui(()->invoke(panel,"choosePhotoLive"));
            until(()->(Boolean)get(panel,"liveTab")&&get(panel,"livePresetDetail")!=null&&((View)get(panel,"livePresetDetail")).isShown(),"photo-live-empty-entry-visible-"+pass);
            ui(()->{
                invoke(panel,"updatePhotoLiveView");
                invoke(panel,"updateLivePresetProgress",new Class<?>[]{type("album.PhotoPresetBatchState")},(Object)null);
                check("未启用自动套用".contentEquals(((TextView)get(panel,"livePresetDetail")).getText()),"photo-live-no-project-has-disabled-default-"+pass);
                check(!activity.isFinishing(),"photo-live-entry-does-not-finish-activity-"+pass);
            });
            if(turn==0)capture("photo-live-no-project");
            ui(()->invoke(panel,"chooseTab",new Class<?>[]{boolean.class},true));
            until(()->!(Boolean)get(panel,"liveTab")&&(Boolean)get(panel,"downloadedTab"),"photo-live-can-return-to-downloads-"+pass);
        }
        check(get(get(panel,"projectPresets"),"store")!=null,"photo-live-regression-keeps-real-project-store");
    }
    private void compositionRecipes()throws Exception{
        photoLiveWithoutProject();
        report.put("scope","Composition, recipes and export chronology on isolated read-only Android emulator. Real native gesture/Canvas, JPEG/EXIF, private drafts/recipe storage and MediaStore; local synthetic template and recipe fixtures. Not physical hardware, camera, live catalog, FPS, TalkBack or colour calibration.");
        List<String> originalHashes=new ArrayList<>();List<Long> originalTimes=new ArrayList<>();for(Object photo:List.of(a,b)){File f=(File)invoke(photo,"file");originalHashes.add(sha(f));originalTimes.add(f.lastModified());}
        String runTag=UUID.randomUUID().toString().substring(0,4),recipeName="暖光试用 "+runTag,renamedName="暖光日常 "+runTag;
        Object initialStore=construct("album.PhotoRecipeStore",new Class<?>[]{File.class,String.class},getTargetContext().getFilesDir(),owner);int initialRecipeCount=((List<?>)invoke(initialStore,"list")).size();
        open(a);until(()->!(Boolean)get(editor,"catalogLoading"),"composition-offline-catalog-settled");choose(supported,70);adjust("EXPOSURE",4);
        check(((View)get(editor,"dock")).getHeight()<=pixelsDp(226),"four-actions-keep-compact-dock-height");capture("editor");
        String before=(String)invoke(readDraft(a),"identity"),beforePixels=pixels((Bitmap)invoke(editor,"preview"));Object cancel=openCrop();cropButton(cancel,"1:1");cropButton(cancel,"旋转 90°");cropButton(cancel,"取消");barrier();check(before.equals(invoke(readDraft(a),"identity"))&&beforePixels.equals(pixels((Bitmap)invoke(editor,"preview"))),"cancel-crop-preserves-draft-and-pixels");
        Object dialog=openCrop();capture("crop-original");cropButton(dialog,"1:1");
        View crop=(View)get(dialog,"crop");android.graphics.RectF box=new android.graphics.RectF((android.graphics.RectF)get(crop,"box"));int[] location=new int[2];ui(()->crop.getLocationOnScreen(location));long now=android.os.SystemClock.uptimeMillis();
        float x=location[0]+box.right,y=location[1]+box.bottom;for(int i=0;i<5;i++){MotionEvent event=MotionEvent.obtain(now,now+i*30,i==0?MotionEvent.ACTION_DOWN:i==4?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,x-pixelsDp(28)*i/4f,y-pixelsDp(28)*i/4f,0);sendPointerSync(event);event.recycle();}waitForIdleSync();
        Object afterDrag=invoke(dialog,"geometry");check(((Number)get(afterDrag,"right")).intValue()<1_000_000,"actual-corner-drag-resizes-crop");
        cropButton(dialog,"旋转 90°");ui(()->{SeekBar s=(SeekBar)get(dialog,"slider");for(int i=0;i<20;i++)s.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));});check(((Number)get(invoke(dialog,"geometry"),"angle")).intValue()==20,"straighten-native-keyboard-steps-tenths");capture("crop-square");cropButton(dialog,"应用");
        until(()->!(Boolean)invoke(get(editor,"geometry"),"isNeutral"),"crop-commits-only-on-apply");barrier();Object croppedA=readDraft(a);String croppedIdentity=(String)invoke(croppedA,"identity");
        until(()->pixels((Bitmap)invoke(editor,"preview")).equals(expectedComposedPreview(croppedA)),"crop-preview-matches-colour-plus-geometry");
        ui(()->invoke(editor,"restoreHistory",new Class<?>[]{boolean.class},false));check((Boolean)invoke(get(editor,"geometry"),"isNeutral")&&manualValue("EXPOSURE")==4,"one-undo-removes-crop-keeps-colour");ui(()->invoke(editor,"restoreHistory",new Class<?>[]{boolean.class},true));barrier();check(croppedIdentity.equals(invoke(readDraft(a),"identity")),"redo-restores-entire-composition");
        ui(()->invoke(editor,"saveRecipeDialog"));android.app.AlertDialog save=(android.app.AlertDialog)get(editor,"popup");EditText input=find(save.getWindow().getDecorView(),EditText.class);ui(()->{input.setText(recipeName);save.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();});until(()->get(editor,"popup")==null&&((List<?>)get(editor,"recipes")).size()==initialRecipeCount+1,"recipe-saved-through-native-dialog");ui(()->{Button action=(Button)get(editor,"saveRecipeButton");check(action.getLayout().getLineCount()==1&&action.getHeight()>=pixelsDp(48),"recipe-save-action-one-line-at-actual-font-scale");});capture("recipes");
        Object recipe=((List<?>)get(editor,"recipes")).get(0);Object recipeStore=construct("album.PhotoRecipeStore",new Class<?>[]{File.class,String.class},getTargetContext().getFilesDir(),owner);check(((Number)get(recipe,"strength")).intValue()==70,"recipe-retains-filter-strength");check(((String)invoke(recipe,"summary")).contains("调节 1 项"),"recipe-retains-manual-settings");Object isolated=construct("album.PhotoRecipeStore",new Class<?>[]{File.class,String.class},getTargetContext().getFilesDir(),owner+"-other");check(((List<?>)invoke(isolated,"list")).isEmpty(),"native-recipe-account-isolation");
        ui(()->invoke(editor,"saveRecipeDialog"));android.app.AlertDialog duplicate=(android.app.AlertDialog)get(editor,"popup");EditText duplicateInput=find(duplicate.getWindow().getDecorView(),EditText.class);ui(()->{duplicateInput.setText(recipeName);duplicate.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();});until(()->duplicateInput.getError()!=null,"duplicate-recipe-name-stays-in-dialog-with-error");check(hasText(duplicate.getWindow().getDecorView(),"已有同名配方，请换一个名称。"),"duplicate-name-error-is-visible-inline");capture("recipe-name-error");ui(()->invoke(editor,"dismissPopup"));
        ui(()->invoke(editor,"recipeNameDialog",new Class<?>[]{type("album.PhotoRecipeStore$Recipe")},recipe));android.app.AlertDialog rename=(android.app.AlertDialog)get(editor,"popup");EditText renameInput=find(rename.getWindow().getDecorView(),EditText.class);ui(()->{renameInput.setText(renamedName);rename.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();});until(()->get(editor,"popup")==null&&get(((List<?>)get(editor,"recipes")).get(0),"name").equals(renamedName),"recipe-rename-persists-in-native-list");
        Object editA=freeze();ui(()->invoke(editor,"showExport"));capture("composition-export");ui(()->invoke(editor,"dismissPopup"));ui(()->invoke(panel,"goBack"));open(b);Object bCrop=openCrop();cropButton(bCrop,"4:3");cropButton(bCrop,"应用");barrier();Object geometryB=get(editor,"geometry");
        final Object chosenRecipe=recipe;rapidRecipeChoices(chosenRecipe,recipeStore,geometryB);until(()->manualValue("EXPOSURE")==4&&((Number)get(editor,"strength")).intValue()==70,"recipe-applies-to-second-photo");check(geometryB.equals(get(editor,"geometry")),"applying-recipe-preserves-target-crop");ui(()->invoke(editor,"restoreHistory",new Class<?>[]{boolean.class},false));check(manualValue("EXPOSURE")==0&&geometryB.equals(get(editor,"geometry")),"recipe-application-is-one-undo-and-keeps-crop");ui(()->invoke(editor,"restoreHistory",new Class<?>[]{boolean.class},true));Object editB=freeze();
        List<Object> selectedSources=List.of(get(editA,"source"),get(editB,"source"));ui(()->invoke(panel,"applySelectedRecipe",new Class<?>[]{List.class,type("album.PhotoRecipeStore$Recipe"),String.class},selectedSources,chosenRecipe,owner));until(()->!(Boolean)get(panel,"preparingEditedExport"),"batch-recipe-draft-update-completes");check(get(readDraft(a),"geometry").equals(get(get(editA,"draft"),"geometry"))&&get(readDraft(b),"geometry").equals(geometryB),"batch-recipe-preserves-each-photo-crop");
        if(activeWindowHas("已套用配方"))clickActiveText("知道了");
        ui(()->{invoke(panel,"goBack");set(panel,"downloadedTab",true);set(panel,"liveTab",false);Set<String> selected=(Set<String>)get(panel,"selectedLocal");selected.clear();selected.add(((File)invoke(a,"file")).getAbsolutePath());selected.add(((File)invoke(b,"file")).getAbsolutePath());set(panel,"selectionMode",true);invoke(panel,"render");});until(()->((List<?>)invoke(panel,"selectedPresetSources")).size()==2,"batch-picker-has-two-actual-local-sources");ui(()->invoke(panel,"choosePhotoPreset"));until(()->activeWindowHas("为所选照片套用效果"),"batch-effects-source-choice-visible");capture("batch-effects");clickActiveText("我的配方");until(()->activeWindowHas("我的配方 · 2 张照片"),"batch-recipes-picker-visible");capture("batch-recipes");getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);waitForIdleSync();
        long started=System.currentTimeMillis();Object state=runEdits(List.of(editA,editB));report.put("composedExportErrors",new JSONArray((List<?>)get(state,"errors"))).put("composedExportCompleted",get(state,"completed")).put("composedExportFailed",get(state,"failed"));check(((Number)get(state,"completed")).intValue()==2&&((Number)get(state,"failed")).intValue()==0,"composed-batch-export-succeeds");List<?> outputs=(List<?>)get(state,"results");check(outputs.size()==2,"two-composed-JPEG-results");
        for(int i=0;i<2;i++){Object source=List.of(a,b).get(i),edit=List.of(editA,editB).get(i),draft=get(edit,"draft"),outputPhoto=outputs.get(i);File original=(File)invoke(source,"file"),target=(File)invoke(outputPhoto,"file");check(sha(original).equals(originalHashes.get(i))&&original.lastModified()==originalTimes.get(i),"export-preserves-original-bytes-and-time-"+i);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(original.getPath(),bounds);ExifInterface exif=new ExifInterface(original.getPath());Object plan=invoke(get(draft,"geometry"),"plan",new Class<?>[]{int.class,int.class,int.class},bounds.outWidth,bounds.outHeight,exif.getAttributeInt("Orientation",1));BitmapFactory.Options resultBounds=new BitmapFactory.Options();resultBounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(target.getPath(),resultBounds);check(resultBounds.outWidth==((Number)get(plan,"width")).intValue()&&resultBounds.outHeight==((Number)get(plan,"height")).intValue(),"cropped-original-pixel-dimensions-"+i);check(new ExifInterface(target.getPath()).getAttributeInt("Orientation",1)==1,"composed-EXIF-normalized-no-double-rotation-"+i);
            File individual=new File(output,"composed-individual-"+i+".JPG");invoke(construct("album.PhotoPresetRenderer",NONE),"render",new Class<?>[]{File.class,File.class,type("ncp.PresetPreviewProfile"),BooleanSupplier.class,type("album.PhotoPresetRenderer$Progress")},original,individual,invoke(draft,"profile"),(BooleanSupplier)()->false,null);check(sha(target).equals(sha(individual)),"composed-batch-equals-single-JPEG-"+i);
            Object gallery=construct("album.PhotoLiveGallery",new Class<?>[]{Context.class},getTargetContext());String uri=(String)invoke(gallery,"publish",new Class<?>[]{type("album.DownloadedPhoto")},outputPhoto);long firstDate=galleryTime(uri);check(firstDate>=started-1000&&Math.abs(System.currentTimeMillis()-firstDate)<5000,"gallery-time-is-current-export-time-"+i);String outputHash=sha(target);Thread.sleep(1100);String reused=(String)invoke(gallery,"publishNewCopy",new Class<?>[]{type("album.DownloadedPhoto")},outputPhoto);check(!uri.equals(reused)&&galleryTime(reused)>firstDate,"explicit-new-copy-publishes-freshly-dated-gallery-copy-"+i);check(outputHash.equals(sha(target))&&target.lastModified()>=started,"time-refresh-preserves-render-cache-bytes-and-updates-modified-"+i);Bitmap privatePixels=BitmapFactory.decodeFile(target.getPath());try(InputStream stream=getTargetContext().getContentResolver().openInputStream(Uri.parse(reused))){Bitmap publishedPixels=BitmapFactory.decodeStream(stream);try{check(publishedPixels!=null&&pixels(publishedPixels).equals(pixels(privatePixels)),"public-copy-changes-dates-without-JPEG-recompression-"+i);}finally{if(publishedPixels!=null)publishedPixels.recycle();privatePixels.recycle();}}
            Object publication=invoke(gallery,"publicationStatus",new Class<?>[]{type("album.DownloadedPhoto")},outputPhoto);check(get(publication,"status").toString().equals("PUBLISHED"),"dated-copy-publication-status-verifies-receipt-"+i);}
        Object repeat=runEdits(List.of(editA,editB));check(((Number)get(repeat,"reused")).intValue()==2,"same-colour-and-geometry-reuse-exact-effects");
        ui(()->invoke(panel,"goBack"));open(outputs.get(1));check((Boolean)get(editor,"hasDraft")&&(Boolean)get(editor,"exported")&&get(editor,"geometry").equals(geometryB),"latest-saved-version-restores-exact-exported-settings");
        ui(()->{set(editor,"recipeMode",true);invoke(editor,"updateModes");});capture("recipe-applied");
        Object gallery=construct("album.PhotoLiveGallery",new Class<?>[]{Context.class},getTargetContext());Object intact=outputs.get(0),changedPublic=outputs.get(1);Object priorStatus=invoke(gallery,"publicationStatus",new Class<?>[]{type("album.DownloadedPhoto")},changedPublic);String changedUri=(String)get(priorStatus,"uri");try(java.io.OutputStream stream=getTargetContext().getContentResolver().openOutputStream(Uri.parse(changedUri),"wa")){stream.write(7);}Object modified=invoke(gallery,"publicationStatus",new Class<?>[]{type("album.DownloadedPhoto")},changedPublic);check(get(modified,"status").toString().equals("UNAVAILABLE"),"modified-public-copy-is-not-reported-as-verified");try{deleteWithLease(gallery,changedPublic);check(false,"modified-public-copy-delete-must-refuse");}catch(Exception expected){check(unwrap(expected) instanceof java.io.IOException,"modified-public-copy-blocks-deletion-before-any-copy-is-removed");}try(Cursor cursor=getTargetContext().getContentResolver().query(Uri.parse(changedUri),new String[]{MediaStore.Images.Media._ID},null,null,null)){check(cursor!=null&&cursor.moveToFirst(),"changed-user-copy-preserved-after-delete-refusal");}
        deleteWithLease(gallery,intact);Object removed=invoke(gallery,"publicationStatus",new Class<?>[]{type("album.DownloadedPhoto")},intact);check(get(removed,"status").toString().equals("MISSING")&&((File)invoke(intact,"file")).isFile(),"delete-all-export-copies-keeps-private-render-cache");
        invoke(recipeStore,"delete",new Class<?>[]{String.class},get(chosenRecipe,"id"));check(((List<?>)invoke(recipeStore,"list")).size()==initialRecipeCount,"delete-recipe-leaves-drafts-separate");check(get(readDraft(b),"geometry").equals(geometryB),"recipe-delete-does-not-change-photo-crop");
    }
    private void rapidRecipeChoices(Object latest,Object recipeStore,Object geometry)throws Exception{
        Object desired=invoke(latest,"apply",new Class<?>[]{String.class,type("ncp.PhotoGeometry")},get(editor,"sourceSha"),geometry);
        Object exposure=type("ncp.PhotoAdjustments$Parameter").getField("EXPOSURE").get(null);
        Object firstManual=invoke(get(desired,"adjustments"),"with",new Class<?>[]{type("ncp.PhotoAdjustments$Parameter"),int.class},exposure,1);
        Object firstDraft=construct("album.PhotoEditStore$Draft",new Class<?>[]{String.class,byte[].class,String.class,int.class,type("ncp.PhotoAdjustments"),boolean.class},get(editor,"sourceSha"),npBytes,"Queued first",30,firstManual,false);
        Object first=invoke(recipeStore,"create",new Class<?>[]{String.class,type("album.PhotoEditStore$Draft")},"队列回归 "+UUID.randomUUID().toString().substring(0,8),firstDraft);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),drained=new CountDownLatch(1);
        java.util.concurrent.ExecutorService worker=(java.util.concurrent.ExecutorService)get(editor,"io");
        int before=((java.util.Collection<?>)get(editor,"undo")).size();
        worker.execute(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        try{
            check(entered.await(3,TimeUnit.SECONDS),"recipe-IO-held-before-consecutive-choices");
            ui(()->{invoke(editor,"applyRecipe",new Class<?>[]{type("album.PhotoRecipeStore$Recipe")},first);invoke(editor,"applyRecipe",new Class<?>[]{type("album.PhotoRecipeStore$Recipe")},latest);});
            worker.execute(drained::countDown);release.countDown();check(drained.await(10,TimeUnit.SECONDS),"consecutive-recipe-work-drained");waitForIdleSync();
            check(manualValue("EXPOSURE")==4&&((Number)get(editor,"strength")).intValue()==70,"latest-recipe-wins-held-queue-A-then-B");
            check(geometry.equals(get(editor,"geometry")),"rapid-recipe-choices-preserve-target-composition");
            check(((java.util.Collection<?>)get(editor,"undo")).size()==before+1,"only-latest-recipe-creates-one-undo-step");
        }finally{release.countDown();invoke(recipeStore,"delete",new Class<?>[]{String.class},get(first,"id"));}
    }
    private void deleteWithLease(Object gallery,Object photo)throws Exception{
        AutoCloseable held=null;for(int i=0;i<60&&held==null;i++){try{held=(AutoCloseable)invokeStatic("album.PhotoLocalTasks","delete",NONE);}catch(Exception busy){if(!(unwrap(busy) instanceof java.io.IOException))throw busy;Thread.sleep(50);}}
        check(held!=null,"exclusive-delete-lease-acquired");try(AutoCloseable lease=held){invoke(gallery,"deletePublished",new Class<?>[]{type("album.DownloadedPhoto")},photo);}
    }
    private long galleryTime(String uri)throws Exception{try(Cursor cursor=getTargetContext().getContentResolver().query(Uri.parse(uri),new String[]{MediaStore.Images.Media.DATE_TAKEN,MediaStore.Images.Media.DATE_MODIFIED},null,null,null)){check(cursor!=null&&cursor.moveToFirst(),"export-time-row-readable");long taken=cursor.getLong(0);check(Math.abs(cursor.getLong(1)-taken/1000)<=2,"gallery-modified-seconds-match-export-milliseconds");return taken;}}
    private String expectedComposedPreview(Object draft)throws Exception{Bitmap base=(Bitmap)invoke(editor,"original");Object profile=invoke(draft,"profile");int[] input=new int[base.getWidth()*base.getHeight()];base.getPixels(input,0,base.getWidth(),0,0,base.getWidth(),base.getHeight());int[] colours=(int[])profile.getClass().getMethod("render",int[].class,int.class,int.class).invoke(profile,input,base.getWidth(),base.getHeight());Bitmap colour=Bitmap.createBitmap(colours,base.getWidth(),base.getHeight(),Bitmap.Config.ARGB_8888);Bitmap composed=(Bitmap)invokeStatic("album.PhotoComposition","apply",new Class<?>[]{Bitmap.class,type("ncp.PhotoGeometry"),int.class},colour,get(draft,"geometry"),1);try{return pixels(composed);}finally{if(composed!=colour)composed.recycle();colour.recycle();}}
    private void basicAdjustments()throws Exception{
        report.put("scope","Basic manual adjustment and compact native editor on isolated read-only Android emulator; real JPEG/EXIF, draft I/O, rendering and MediaStore. Local preset fixtures and synthetic template photographs only. Not physical hardware, camera, live catalog, colour calibration, performance or TalkBack certification.");
        List<String> originals=new ArrayList<>();for(Object photo:List.of(a,b,c))originals.add(sha((File)invoke(photo,"file")));
        long existing=effects();open(a);until(()->!(Boolean)get(editor,"catalogLoading"),"offline-catalog-status-settled");check(((TextView)get(editor,"categoryScroll")).getContentDescription().toString().contains("缓存"),"offline-cache-is-disclosed-in-compact-source-control");choose(supported,70);
        ui(()->{View selected=(View)((Map<?,?>)get(editor,"stripCards")).get(get(editor,"selected"));HorizontalScrollView viewport=(HorizontalScrollView)get(editor,"filterScroll");viewport.scrollTo(Math.max(0,selected.getLeft()-pixelsDp(8)),0);});waitForIdleSync();
        until(()->((View)get(editor,"dock")).getHeight()>0,"compact-dock-laid-out");
        final View image=(View)get(panel,"viewerImage");final android.graphics.Rect before=new android.graphics.Rect();
        ui(()->{
            image.getGlobalVisibleRect(before);View dock=(View)get(editor,"dock");
            check(dock.getHeight()<=pixelsDp(226),"flat-dock-at-most-226dp");
            check(dock.getElevation()==0,"flat-dock-has-no-floating-shadow");
            check(((Map<?,?>)get(editor,"parameterControls")).size()==13,"all-13-manual-controls-exist");
            check(((View)get(editor,"sourceStatus")).getParent()==null&&((View)get(editor,"categories")).getParent()==null,"no-permanent-status-or-category-rows");
            for(String field:List.of("onlineButton","mineButton","filterModeButton","adjustmentModeButton")){View control=(View)get(editor,field);check(control.getWidth()>=pixelsDp(48)&&control.getHeight()>=pixelsDp(48),field+"-48dp-touch-target");}
            report.put("canvasBounds",rectJson(before)).put("dockHeightPx",dock.getHeight());
        });
        capture("filters");
        ui(()->((View)get(editor,"adjustmentModeButton")).performClick());waitForIdleSync();
        ui(()->{android.graphics.Rect after=new android.graphics.Rect();image.getGlobalVisibleRect(after);check(before.equals(after),"mode-switch-keeps-exact-photo-canvas-bounds");});
        adjust("BRIGHTNESS",12);String bright=pixels((Bitmap)invoke(editor,"preview"));
        ui(()->((Button)get(editor,"undoButton")).performClick());check(manualValue("BRIGHTNESS")==11,"keyboard-adjustment-is-undoable");ui(()->((Button)get(editor,"redoButton")).performClick());check(manualValue("BRIGHTNESS")==12,"redo-restores-value");
        int historyBefore=((java.util.Deque<?>)get(editor,"undo")).size();dragManualSlider();check(manualValue("BRIGHTNESS")!=12,"real-touch-drag-updates-manual-value");check(((java.util.Deque<?>)get(editor,"undo")).size()==Math.min(32,historyBefore+1),"continuous-touch-drag-retains-bounded-history");ui(()->((Button)get(editor,"undoButton")).performClick());check(manualValue("BRIGHTNESS")==12,"one-undo-restores-the-complete-continuous-touch-drag");
        until(()->pixels((Bitmap)invoke(editor,"preview")).equals(bright),"redo-restores-preview");capture("adjustments");
        ui(()->invoke(editor,"showExactValue"));android.app.AlertDialog exact=(android.app.AlertDialog)get(editor,"popup");EditText input=find(exact.getWindow().getDecorView(),EditText.class);
        ui(()->{input.setText("101");exact.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();check(input.getError()!=null&&exact.isShowing()&&manualValue("BRIGHTNESS")==12,"invalid-exact-value-keeps-last-valid-setting");});capture("exact-value");
        ui(()->{input.setText("12");exact.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();});until(()->get(editor,"popup")==null&&manualValue("BRIGHTNESS")==12,"valid-exact-value-closes-and-keeps-setting");
        adjust("EXPOSURE",7);adjust("CONTRAST",10);adjust("HIGHLIGHTS",-20);adjust("SHADOWS",15);adjust("TEMPERATURE",12);
        String preset=(String)get(editor,"selected");
        ui(()->invoke(editor,"reset",new Class<?>[]{boolean.class},false));check(get(editor,"selected").equals(preset)&&((Number)invoke(get(editor,"adjustments"),"count")).intValue()==0,"reset-manual-keeps-preset");
        ui(()->((Button)get(editor,"undoButton")).performClick());check(manualValue("TEMPERATURE")==12&&manualValue("EXPOSURE")==7,"undo-restores-whole-reset");
        ui(()->invoke(editor,"reset",new Class<?>[]{boolean.class},true));check(get(editor,"selected").equals("")&&manualValue("EXPOSURE")==0,"full-reset-clears-filter-and-manual");ui(()->((Button)get(editor,"undoButton")).performClick());check(get(editor,"selected").equals(preset)&&manualValue("EXPOSURE")==7,"undo-restores-filter-and-manual-together");
        ui(()->invoke(editor,"choose",new Class<?>[]{type("ui.PhotoFilterEditor$Entry")},(Object)null));check(manualValue("EXPOSURE")==7&&get(editor,"selected").equals(""),"no-filter-preserves-manual-values");ui(()->((Button)get(editor,"undoButton")).performClick());
        barrier();Object draftA=readDraft(a);String expected=expectedManualPreview(draftA);until(()->pixels((Bitmap)invoke(editor,"preview")).equals(expected),"complete-manual-preview-matches-export-profile");
        Object compare=get(panel,"viewerComparison");View compareButton=(View)invoke(compare,"getButtonView");
        ui(()->{long now=android.os.SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,compareButton.getWidth()/2f,compareButton.getHeight()/2f,0);compareButton.dispatchTouchEvent(down);down.recycle();});
        check(pixels((Bitmap)get(get(panel,"viewerImage"),"photo")).equals(pixels((Bitmap)invoke(editor,"original"))),"hold-compare-shows-complete-original");
        ui(()->{long now=android.os.SystemClock.uptimeMillis();MotionEvent up=MotionEvent.obtain(now-100,now,MotionEvent.ACTION_UP,compareButton.getWidth()/2f,compareButton.getHeight()/2f,0);compareButton.dispatchTouchEvent(up);up.recycle();});
        check(pixels((Bitmap)get(get(panel,"viewerImage"),"photo")).equals(expected),"release-compare-restores-combined-result");
        capture("colour");
        ui(()->{PopupMenu menu=new PopupMenu(activity,(View)get(editor,"adjustmentModeButton"));invoke(editor,"addMenuItems",new Class<?>[]{Menu.class},menu.getMenu());check(menu.getMenu().getItem(0).getTitle().toString().contains("参数说明"),"parameter-guide-is-discoverable-in-more-menu");invoke(editor,"showParameterHelp");});
        android.app.AlertDialog guide=(android.app.AlertDialog)get(editor,"popup");check(((TextView)guide.findViewById(android.R.id.message)).getText().toString().contains("JPEG 的相对冷暖"),"temperature-guide-explains-relative-JPEG-units");capture("parameter-help");ui(()->invoke(editor,"dismissPopup"));
        ui(()->invoke(editor,"showExport"));capture("export");ui(()->invoke(editor,"dismissPopup"));
        check(effects()==existing,"all-preview-operations-create-no-permanent-effects");
        Object editA=freeze();adjust("EXPOSURE",8);barrier();
        ui(()->invoke(panel,"goBack"));open(b);ui(()->((View)get(editor,"adjustmentModeButton")).performClick());adjust("EXPOSURE",-3);adjust("SATURATION",20);Object editB=freeze();check(get(get(editB,"draft"),"presetSha").equals(""),"manual-only-export-needs-no-preset");
        ui(()->invoke(panel,"goBack"));open(a);check(manualValue("EXPOSURE")==8&&manualValue("TEMPERATURE")==12,"reenter-photo-restores-own-manual-draft");
        ui(()->invoke(panel,"goBack"));open(c);ui(()->invoke(editor,"choose",new Class<?>[]{type("ui.PhotoFilterEditor$Entry")},(Object)null));Object editC=freeze();
        Object state=runEdits(List.of(editA,editB,editC));check(((Number)get(state,"completed")).intValue()==3&&((Number)get(state,"failed")).intValue()==0,"batch-exports-independent-preset-manual-and-original");List<?> outputs=(List<?>)get(state,"results");check(outputs.size()==3,"batch-contains-three-results");
        for(int index=0;index<3;index++){
            Object photo=List.of(a,b,c).get(index);File source=(File)invoke(photo,"file"),target=(File)invoke(outputs.get(index),"file");check(sha(source).equals(originals.get(index)),"original-bytes-unchanged-"+index);
            if(index==2){check(source.equals(target),"neutral-export-reuses-original");continue;}
            Object draft=get(List.of(editA,editB).get(index),"draft"),profile=invoke(draft,"profile");File individual=new File(output,"manual-individual-"+index+".JPG");invoke(construct("album.PhotoPresetRenderer",NONE),"render",new Class<?>[]{File.class,File.class,type("ncp.PresetPreviewProfile"),BooleanSupplier.class,type("album.PhotoPresetRenderer$Progress")},source,individual,profile,(BooleanSupplier)()->false,null);
            check(sha(target).equals(sha(individual)),"batch-equals-individual-full-JPEG-"+index);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(target.getPath(),bounds);check(bounds.outWidth==1448&&bounds.outHeight==1086,"full-resolution-manual-export-"+index);
            check(invoke(invoke(batch,"sourceFor",new Class<?>[]{type("album.DownloadedPhoto")},outputs.get(index)),"file").equals(source),"manual-output-keeps-original-source-link-"+index);
        }
        check(!(Boolean)get(readDraft(a),"exported")&&manualDraftValue(readDraft(a),"EXPOSURE")==8,"late-frozen-export-keeps-newer-draft-pending");
        Object repeated=runEdits(List.of(editA,editB,editC));check(((Number)get(repeated,"reused")).intValue()==2,"same-complete-manual-settings-reuse-two-effects");
        Object manual=get(get(editA,"draft"),"adjustments"),temperature=parameter("TEMPERATURE");Object different=invoke(manual,"with",new Class<?>[]{type("ncp.PhotoAdjustments$Parameter"),int.class},temperature,13);Object old=get(editA,"draft");Object changed=construct("album.PhotoEditStore$Draft",new Class<?>[]{String.class,byte[].class,String.class,int.class,type("ncp.PhotoAdjustments"),boolean.class},get(old,"sourceSha"),invoke(old,"presetBytes"),get(old,"name"),get(old,"strength"),different,false);Object other=invokeStatic("album.PhotoPresetBatch$Edit","draft",new Class<?>[]{type("album.PhotoPresetBatch$Source"),type("album.PhotoEditStore$Draft"),String.class},get(editA,"source"),changed,owner);Object differentOutput=((List<?>)get(runEdits(List.of(other)),"results")).get(0);check(!invoke(differentOutput,"file").equals(invoke(outputs.get(0),"file")),"one-temperature-step-has-a-different-output-identity");
        savedVersionNoOps(outputs.get(0),true);savedVersionNoOps(outputs.get(1),false);
    }
    private void savedVersionNoOps(Object photo,boolean parked)throws Exception{
        open(photo);ui(()->invoke(editor,"bindSavedVersion",new Class<?>[]{type("album.DownloadedPhoto")},photo));until(()->(Boolean)invoke(editor,"isReady"),"saved-version-editor-ready-"+parked);
        check(!(Boolean)get(editor,"hasDraft")&&(Boolean)get(editor,"parkedDraft")==parked,"saved-version-parked-state-"+parked);
        Object before=readDraft((String)get(editor,"sourceSha"));String identity=(String)invoke(before,"identity");boolean exported=(Boolean)get(before,"exported");String preview=pixels((Bitmap)invoke(editor,"preview"));
        ui(()->invoke(editor,"reset",new Class<?>[]{boolean.class},false));barrier();check(!(Boolean)get(editor,"hasDraft")&&(Boolean)get(editor,"parkedDraft")==parked&&preview.equals(pixels((Bitmap)invoke(editor,"preview"))),"neutral-reset-keeps-saved-display-"+parked);
        ui(()->invoke(editor,"showExactValue"));android.app.AlertDialog exact=(android.app.AlertDialog)get(editor,"popup");EditText input=find(exact.getWindow().getDecorView(),EditText.class);ui(()->{input.setText("0");exact.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();});until(()->get(editor,"popup")==null,"unchanged-exact-dialog-closes-"+parked);barrier();
        Object after=readDraft((String)get(editor,"sourceSha"));check(!(Boolean)get(editor,"hasDraft")&&(Boolean)get(editor,"parkedDraft")==parked&&preview.equals(pixels((Bitmap)invoke(editor,"preview"))),"unchanged-exact-keeps-saved-display-"+parked);
        check(identity.equals(invoke(after,"identity"))&&exported==(Boolean)get(after,"exported"),"no-op-actions-preserve-persisted-draft-"+parked);
    }
    private void dragManualSlider()throws Exception{
        SeekBar slider=(SeekBar)get(editor,"adjustmentSlider");int[] position=new int[2];ui(()->slider.getLocationOnScreen(position));float width=slider.getWidth()-slider.getPaddingLeft()-slider.getPaddingRight(),start=position[0]+slider.getPaddingLeft()+width*slider.getProgress()/slider.getMax(),end=Math.min(position[0]+slider.getWidth()-slider.getPaddingRight()-2,start+pixelsDp(28)),y=position[1]+slider.getHeight()/2f;long now=android.os.SystemClock.uptimeMillis();
        for(int i=0;i<8;i++){int action=i==0?MotionEvent.ACTION_DOWN:i==7?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;MotionEvent event=MotionEvent.obtain(now,now+i*24,action,start+(end-start)*i/7,y,0);sendPointerSync(event);event.recycle();}waitForIdleSync();
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private Object parameter(String name)throws Exception{return Enum.valueOf((Class)type("ncp.PhotoAdjustments$Parameter"),name);}
    private int manualValue(String name)throws Exception{return ((Number)invoke(get(editor,"adjustments"),"get",new Class<?>[]{type("ncp.PhotoAdjustments$Parameter")},parameter(name))).intValue();}
    private int manualDraftValue(Object draft,String name)throws Exception{return ((Number)invoke(get(draft,"adjustments"),"get",new Class<?>[]{type("ncp.PhotoAdjustments$Parameter")},parameter(name))).intValue();}
    private void adjust(String name,int target)throws Exception{
        Object parameter=parameter(name);ui(()->{View control=(View)((Map<?,?>)get(editor,"parameterControls")).get(parameter);((HorizontalScrollView)control.getParent().getParent()).scrollTo(Math.max(0,control.getLeft()-pixelsDp(8)),0);control.performClick();SeekBar slider=(SeekBar)get(editor,"adjustmentSlider");slider.setKeyProgressIncrement(1);int current=manualValue(name);for(int n=0;n<Math.abs(target-current);n++){int key=target>current?KeyEvent.KEYCODE_DPAD_RIGHT:KeyEvent.KEYCODE_DPAD_LEFT;slider.onKeyDown(key,new KeyEvent(KeyEvent.ACTION_DOWN,key));}});
        check(manualValue(name)==target,"native-keyboard-value-"+name+"-"+target);barrier();Object draft=readDraft((String)get(editor,"sourceSha"));String expected=expectedManualPreview(draft);until(()->pixels((Bitmap)invoke(editor,"preview")).equals(expected),"manual-preview-settled-"+name);
    }
    private Object readDraft(Object photo)throws Exception{return readDraft(sha((File)invoke(photo,"file")));}
    private Object readDraft(String sha)throws Exception{return invoke(construct("album.PhotoEditStore",new Class<?>[]{File.class,String.class},getTargetContext().getFilesDir(),owner),"read",new Class<?>[]{String.class},sha);}
    private Object freeze()throws Exception{AtomicReference<Object> result=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);ui(()->invoke(editor,"freeze",new Class<?>[]{java.util.function.Consumer.class},(java.util.function.Consumer<Object>)value->{result.set(value);done.countDown();}));check(done.await(10,TimeUnit.SECONDS),"frozen-edit-captured");return result.get();}
    private String expectedManualPreview(Object draft)throws Exception{Bitmap base=(Bitmap)invoke(editor,"original");Object profile=invoke(draft,"profile");int[] input=new int[base.getWidth()*base.getHeight()];base.getPixels(input,0,base.getWidth(),0,0,base.getWidth(),base.getHeight());int[] output=(int[])profile.getClass().getMethod("render",int[].class,int.class,int.class).invoke(profile,input,base.getWidth(),base.getHeight());Bitmap expected=Bitmap.createBitmap(output,base.getWidth(),base.getHeight(),Bitmap.Config.ARGB_8888);try{return pixels(expected);}finally{expected.recycle();}}
    private Object source(File dir,String name,int orientation)throws Exception{
        File f=new File(dir,"editor-"+UUID.randomUUID().toString().replace("-","")+"__"+name+".JPG");
        Bitmap image=BitmapFactory.decodeStream(getContext().getAssets().open("photo.png"));
        try(FileOutputStream out=new FileOutputStream(f)){image.compress(Bitmap.CompressFormat.JPEG,96,out);}finally{image.recycle();}
        ExifInterface ex=new ExifInterface(f.getPath());ex.setAttribute("Make","Huevia editor QA");ex.setAttribute("Model",name+UUID.randomUUID());ex.setAttribute("Orientation",""+orientation);ex.setAttribute("DateTime","2001:01:02 03:04:05");ex.setAttribute("DateTimeOriginal","2001:01:02 03:04:05");ex.setAttribute("ColorSpace","1");ex.saveAttributes();f.setLastModified(978404645000L);
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
    private Object runEdits(List<Object> edits)throws Exception{return runEdits(edits,null,false);}
    private Object runEdits(List<Object> edits,String destination,boolean forceNewCopy)throws Exception{CountDownLatch accepted=new CountDownLatch(1),done=new CountDownLatch(1);AtomicReference<Object> result=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();Object cb=callback("album.AlbumController$Callback",(n,v)->{if(n.equals("onError"))error.set(v[0].toString());accepted.countDown();});Object finish=callback("album.AlbumController$Callback",(n,v)->{if(n.equals("onSuccess"))result.set(v[0]);else error.set(v[0].toString());done.countDown();});ui(()->{if(destination==null)invoke(batch,"startEdits",new Class<?>[]{List.class,type("album.AlbumController$Callback"),type("album.AlbumController$Callback")},edits,cb,finish);else invoke(batch,"startEdits",new Class<?>[]{List.class,type("album.PhotoSaveTarget"),boolean.class,type("album.AlbumController$Callback"),type("album.AlbumController$Callback")},edits,type("album.PhotoSaveTarget").getField(destination).get(null),forceNewCopy,cb,finish);});check(accepted.await(10,TimeUnit.SECONDS)&&error.get()==null,"native-export-accepted");check(done.await(60,TimeUnit.SECONDS)&&error.get()==null,"native-export-completed");return result.get();}
    private static boolean hasText(View v,String value){if(v instanceof TextView t&&v.isShown()&&value.equals(t.getText().toString()))return true;if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++)if(hasText(g.getChildAt(i),value))return true;return false;}
    private static <T extends View>T find(View v,Class<T> cls){if(cls.isInstance(v))return cls.cast(v);if(v instanceof ViewGroup g)for(int i=0;i<g.getChildCount();i++){T x=find(g.getChildAt(i),cls);if(x!=null)return x;}return null;}
    private byte[] asset(String name) throws Exception { try (InputStream input = getContext().getAssets().open(name)) { return input.readAllBytes(); } }
    private void saveBitmap(Bitmap image, String name) throws Exception { try (FileOutputStream stream = new FileOutputStream(new File(output,name))) { check(image.compress(Bitmap.CompressFormat.JPEG,95,stream), "native-template-evidence-encoded"); } }
    private String pixels(Bitmap bitmap) throws Exception { int[] values = new int[bitmap.getWidth()*bitmap.getHeight()]; bitmap.getPixels(values,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight()); MessageDigest digest = MessageDigest.getInstance("SHA-256"); for (int value : values) { digest.update((byte)(value>>>24)); digest.update((byte)(value>>>16)); digest.update((byte)(value>>>8)); digest.update((byte)value); } return hex(digest.digest()); }
    private void capture(String name) throws Exception {
        ui(()->invoke(get(activity,"topNotice"),"dismiss"));waitForIdleSync();Thread.sleep(400);Bitmap image=getUiAutomation().takeScreenshot();check(image!=null,"native-screenshot-"+name);
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

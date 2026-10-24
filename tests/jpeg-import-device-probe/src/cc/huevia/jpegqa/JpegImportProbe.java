package cc.huevia.jpegqa;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.json.*;

/** Isolated emulator only: real MediaStore import, Android decoder, editor and border JPEG. */
public final class JpegImportProbe extends Instrumentation {
    private Bundle args; private ClassLoader loader; private Activity activity;
    private Context target; private File output; private int checks;
    private final JSONObject report=new JSONObject(); private final JSONArray assertions=new JSONArray();
    private final List<Uri> ownedRows=new ArrayList<>(); private final List<File> imported=new ArrayList<>();
    @Override public void onCreate(Bundle b){super.onCreate(b);args=b==null?new Bundle():b;start();}
    @Override public void onStart(){Bundle result=new Bundle(); boolean baseline=args.getString("mode","fixed").equals("baseline");
        try{
            check(List.of("ranchu","goldfish").contains(Build.HARDWARE),"isolated-emulator-only");
            target=getTargetContext(); loader=target.getClassLoader();
            int version=baseline?89:90;
            check(target.getPackageManager().getPackageInfo(target.getPackageName(),0).getLongVersionCode()==version,"expected-version-"+version);
            output=new File(target.getExternalFilesDir(null),"jpeg-import-90/"+args.getString("mode","fixed")); output.mkdirs();
            report.put("apkSha256",sha(Files.readAllBytes(new File(target.getApplicationInfo().sourceDir).toPath()))).put("mode",args.getString("mode","fixed"));
            byte[] screenshot;try(InputStream in=getContext().getAssets().open("phone-screenshot.jpg")){screenshot=in.readAllBytes();}
            check(screenshot.length==815072,"unmodified-user-screenshot-size");
            check(sha(screenshot).equals("830faf7e29108884ac960bb3df6a1a8ffc5d2338bca14f7ea1cfe4b50bcdc767"),"unmodified-user-screenshot-sha");
            Object repository=type("album.LocalPhotoRepository").getConstructor(Context.class).newInstance(target);
            Uri screen=seed("phone-screenshot",screenshot);
            checkImport(repository,screen,screenshot,!baseline,"user-screenshot-vendor-tail");
            byte[] ended=join(screenshot,new byte[]{(byte)255,(byte)217});
            checkImport(repository,seed("second-eoi",ended),ended,!baseline,"tail-ending-in-second-eoi");
            byte[] plain=Arrays.copyOf(screenshot,815048);
            // Diagnostic parity: Android also accepts deliberately shortened entropy if an EOI
            // is injected. Version 89 already behaved this way; no pixel-integrity certification.
            byte[] injectedEoi=join(Arrays.copyOf(plain,plain.length/2),new byte[]{(byte)255,(byte)217});
            checkImport(repository,seed("injected-eoi-parity",injectedEoi),injectedEoi,true,"preexisting-injected-eoi-decoder-parity");
            if(!baseline){
                checkImport(repository,seed("plain",plain),plain,true,"plain-jpeg");
                byte[] multi=join(screenshot,plain);
                checkImport(repository,seed("auxiliary-jpeg",multi),multi,true,"complete-primary-with-second-image");
                checkImport(repository,seed("truncated",Arrays.copyOf(plain,plain.length-2)),null,false,"missing-primary-eoi");
                checkImport(repository,seed("broken-primary",join(Arrays.copyOf(plain,plain.length/2),plain)),null,false,"second-image-cannot-replace-broken-primary");
                checkImport(repository,seed("empty-primary",new byte[]{(byte)255,(byte)216,(byte)255,(byte)217}),null,false,"missing-frame-and-scan");
                activity=startActivitySync(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName("cc.huevia","cc.huevia.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                until(()->(Boolean)get(activity,"ready")&&get(activity,"localAlbum")!=null,"activity-ready");
                ui(()->{invoke(activity,"switchRoot",new Class<?>[]{int.class},3);invoke(get(activity,"localAlbum"),"prepareUris",new Class<?>[]{List.class},List.of(screen));});
                until(()->{Object panel=get(activity,"localPhotoEditor");Object editor=get(panel,"filterEditor");return editor!=null&&(Boolean)invoke(editor,"isReady",new Class<?>[]{});},"real-phone-import-opens-editor");
                Object editor=get(get(activity,"localPhotoEditor"),"filterEditor");Object controls=get(editor,"borderControls");
                ui(()->{((View)get(editor,"dock")).findViewWithTag("border-mode").performClick();((View[])get(controls,"tiles"))[1].performClick();});
                until(()->{Bitmap preview=(Bitmap)invoke(editor,"preview",new Class<?>[]{});Bitmap original=(Bitmap)invoke(editor,"original",new Class<?>[]{});return preview.getWidth()!=original.getWidth()||preview.getHeight()!=original.getHeight();},"border-preview-after-phone-import");
                waitForIdleSync();capture("editor-after-import.png");
                CountDownLatch frozen=new CountDownLatch(1);AtomicReference<Object> edit=new AtomicReference<>();
                ui(()->invoke(editor,"freeze",new Class<?>[]{Consumer.class},(Consumer<Object>)v->{edit.set(v);frozen.countDown();}));
                check(frozen.await(20,TimeUnit.SECONDS),"freeze-normal-editor-settings");
                Object draft=get(edit.get(),"draft");Object profile=invoke(draft,"profile",new Class<?>[]{});
                Object renderer=type("album.PhotoPresetRenderer").getConstructor(android.content.res.AssetManager.class).newInstance(target.getAssets());
                File originalFile=(File)invoke(importedPhoto(repository,screen),"file",new Class<?>[]{});File rendered=new File(output,"border-output.JPG");
                invoke(renderer,"render",new Class<?>[]{File.class,File.class,type("ncp.PresetPreviewProfile"),BooleanSupplier.class,type("album.PhotoPresetRenderer$Progress")},originalFile,rendered,profile,(BooleanSupplier)()->false,null);
                Bitmap image=BitmapFactory.decodeFile(rendered.getPath());check(image!=null,"actual-border-jpeg-decodes");if(image!=null)image.recycle();
                check(sha(Files.readAllBytes(originalFile.toPath())).equals(sha(screenshot)),"border-export-keeps-imported-original-bytes");
                check(sha(read(screen)).equals(sha(screenshot)),"phone-source-unchanged-after-editor-and-export");
            }
            report.put("status","PASS");
        }catch(Throwable failure){reportPut("status","FAIL");reportPut("failure",unwrap(failure).toString());if(output!=null)try{capture("failure.png");}catch(Exception ignored){}result.putString("failure",unwrap(failure).toString());}
        finally{
            int removed=0;for(Uri uri:ownedRows)try{removed+=target.getContentResolver().delete(uri,null,null);}catch(Exception ignored){}
            reportPut("ownedRows",ownedRows.size());reportPut("removedRows",removed);
            if(removed!=ownedRows.size()){reportPut("status","FAIL");reportPut("cleanupFailure","Not all task-owned MediaStore rows removed");}
            reportPut("checks",checks);reportPut("assertions",assertions);
            if(output!=null)try{Files.writeString(new File(output,"report.json").toPath(),report.toString(2));}catch(Exception ignored){}
        }
        result.putString("report",report.toString());finish("PASS".equals(report.optString("status"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private Object importedPhoto(Object repository,Uri uri)throws Exception{Object selected=invoke(repository,"describe",new Class<?>[]{Uri.class},uri);return invoke(repository,"importPhoto",new Class<?>[]{type("album.LocalPhotoRepository$PhonePhoto"),BooleanSupplier.class},selected,(BooleanSupplier)()->false);}
    private void checkImport(Object repository,Uri uri,byte[] expected,boolean accept,String name)throws Exception{
        Object photo;
        try{photo=importedPhoto(repository,uri);}catch(Throwable failure){if(accept)throw failure;check(unwrap(failure) instanceof IOException,name+"-rejected");reportPut(name+"-message",unwrap(failure).getMessage());return;}
        check(accept,name+"-accepted");File file=(File)invoke(photo,"file",new Class<?>[]{});imported.add(file);
        check(Arrays.equals(expected,Files.readAllBytes(file.toPath())),name+"-private-bytes-preserved");
        check(Arrays.equals(expected,read(uri)),name+"-phone-bytes-preserved");
        Object again=importedPhoto(repository,uri);check(file.equals(invoke(again,"file",new Class<?>[]{})),name+"-reuse-same-verified-copy");
    }
    private Uri seed(String label,byte[] bytes)throws Exception{ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,"jpeg-qa-"+label+"-"+UUID.randomUUID()+".jpg");v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Huevia-JPEG-QA");v.put(MediaStore.Images.Media.IS_PENDING,1);Uri uri=target.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new IOException("seed failed");ownedRows.add(uri);try(OutputStream out=target.getContentResolver().openOutputStream(uri)){out.write(bytes);}v.clear();v.put(MediaStore.Images.Media.IS_PENDING,0);target.getContentResolver().update(uri,v,null,null);return uri;}
    private byte[] read(Uri uri)throws Exception{try(InputStream in=target.getContentResolver().openInputStream(uri)){return in.readAllBytes();}}
    private void capture(String name)throws Exception{Bitmap b=getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(output,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}}
    private void check(boolean ok,String name)throws Exception{assertions.put(new JSONObject().put("name",name).put("pass",ok));if(!ok)throw new AssertionError(name);checks++;}
    private void reportPut(String key,Object value){try{report.put(key,value);}catch(Exception ignored){}}
    private interface Task{void run()throws Exception;}private interface Ready{boolean run()throws Exception;}
    private void ui(Task task)throws Exception{AtomicReference<Throwable> failure=new AtomicReference<>();runOnMainSync(()->{try{task.run();}catch(Throwable e){failure.set(e);}});if(failure.get()!=null)throw new RuntimeException(failure.get());}
    private void until(Ready condition,String label)throws Exception{for(int i=0;i<150;i++){boolean[] ok={false};ui(()->ok[0]=condition.run());if(ok[0]){check(true,label);return;}Thread.sleep(100);}throw new AssertionError(label+" timeout");}
    private Class<?> type(String name)throws Exception{return loader.loadClass("cc.huevia."+name);}
    private Object invokeStatic(String name,String method,Class<?>[] parameters,Object... values)throws Exception{return call(type(name),null,method,parameters,values);}
    private static Object invoke(Object o,String method,Class<?>[] parameters,Object... values)throws Exception{return call(o.getClass(),o,method,parameters,values);}
    private static Object call(Class<?> c,Object o,String method,Class<?>[] parameters,Object... values)throws Exception{Method m=c.getDeclaredMethod(method,parameters);m.setAccessible(true);return m.invoke(o,values);}
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static Throwable unwrap(Throwable e){while((e instanceof InvocationTargetException||e instanceof RuntimeException)&&e.getCause()!=null)e=e.getCause();return e;}
    private static byte[] join(byte[] a,byte[] b){byte[] out=Arrays.copyOf(a,a.length+b.length);System.arraycopy(b,0,out,a.length,b.length);return out;}
    private static String sha(byte[] bytes)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
}

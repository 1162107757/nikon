package cc.huevia.np3qa;

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
import android.os.SystemClock;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Emulator-only: real Android template, JPEG, batch, MediaStore and NP3 selection boundaries. */
public final class Np3NativeProbe extends Instrumentation {
    private static final String TARGET = "cc.huevia";
    private static final Class<?>[] NONE = new Class<?>[0];
    private final JSONObject report = new JSONObject();
    private final JSONArray assertions = new JSONArray();
    private Bundle args;
    private ClassLoader loader;
    private Activity activity;
    private File output;
    private String stage = "arguments";
    private int checks;
    private Object library, supported, unsupported, renderer, controller, batch;
    private byte[] supportedBytes, unsupportedBytes;
    private String unsupportedReason;
    private boolean monochromeBatchEnabled;
    private Object monochromeBatchOriginal;
    private File monochromeBatchSource, priorMonochromeBatchEffect;
    private String priorMonochromeBatchPixels;

    @Override public void onCreate(Bundle value) { super.onCreate(value); args = value == null ? new Bundle() : value; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE), "dedicated-emulator-hardware");
            Context context = getTargetContext(); loader = context.getClassLoader();
            check(TARGET.equals(context.getPackageName()), "target-package");
            var version = context.getPackageManager().getPackageInfo(TARGET, 0);
            check(version.getLongVersionCode() == Integer.parseInt(args.getString("version", "79")), "expected-product-apk-version");
            check(version.versionName.equals(args.getString("versionName","0.13.11-beta")), "expected-product-apk-version-name");
            String expectedSha=args.getString("apkSha256", ""), actualSha=sha(new File(context.getApplicationInfo().sourceDir));
            check(expectedSha.matches("[a-f0-9]{64}") && expectedSha.equals(actualSha), "installed-product-matches-frozen-apk-sha256");
            String theme = args.getString("theme", "light"); check(List.of("light", "dark").contains(theme), "requested-theme");
            String scope = args.getString("scope", "renderer-only");
            check(List.of("full", "renderer-only", "monochrome-batch").contains(scope), "requested-probe-scope");
            monochromeBatchEnabled="monochrome-batch".equals(scope);
            check(context.getSharedPreferences("app-appearance", Context.MODE_PRIVATE).edit().putString("mode", theme).commit(), "emulator-theme-preference");
            check(context.getSharedPreferences("preset-preview-template", Context.MODE_PRIVATE).edit().clear().commit(), "temporary-emulator-uses-built-in-synthetic-template");
            String runId=args.getString("runId", "");check(runId.matches("[A-Za-z0-9_-]{1,64}"), "fresh-evidence-run-id");
            output = new File(context.getExternalFilesDir(null), "np3-native/" + version.getLongVersionCode() + "/" + runId + "/" + scope + "/" + theme);
            check(!output.exists() && output.mkdirs(), "fresh-evidence-directory-no-prior-record-removed");
            JSONObject fixtureAudit=new JSONObject(new String(asset("np3-fixtures.json"),java.nio.charset.StandardCharsets.UTF_8).replace("\ufeff",""));
            check(fixtureAudit.getInt("expectedVersion")==version.getLongVersionCode(),"probe-assets-bound-to-requested-version");
            report.put("versionName", version.versionName).put("versionCode", version.getLongVersionCode()).put("apkSha256", actualSha)
                    .put("probeApkSha256",sha(new File(getContext().getApplicationInfo().sourceDir))).put("runId",runId)
                    .put("theme", theme).put("probeScope", scope).put("hardware", Build.HARDWARE).put("monochromeBatchVerified",false)
                    .put("fixtureAudit",fixtureAudit)
                    .put("scope", "Dedicated emulator-5582, airplane mode; built-in synthetic template, 1800x1200 synthetic JPEG and original local corpus recipes. Mono/Peep/Scott checks cover real Android template/JPEG/EXIF/sRGB/cancellation and colour/spatial/strength pixels. Monochrome-batch scope additionally verifies all three recipes through independent batch jobs, reuse and exact source links on one fresh shared synthetic source; MediaStore status/content and mixed per-photo draft export. Full scope additionally covers legacy FC batch/MediaStore/UI. No private image, camera, remote fetch, phone-speed guarantee or NX colour-fidelity certification.");
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(TARGET, TARGET + ".MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            until(() -> (Boolean)get(activity, "ready") && get(activity, "album") != null, "activity-ready");
            ui(() -> { library = get(activity, "library"); Object panel = get(activity, "album"); controller = get(panel, "controller"); batch = get(panel, "photoPresets"); });
            check(!(Boolean)invoke(controller, "hasLiveSession"), "no-live-camera-session");
            importAndParse();
            verifiedQuickSharpCorpusRecipes();
            nativeV3ColourBehaviour();
            templateAndFullResolution();
            nativeSpatialAndStrengthEquivalence();
            nativeMonochromeRecipesAndJpeg();
            if ("full".equals(scope)) { batchAndGallery(); detailAndPicker(); }
            report.put("status", "PASS");
            result.putString("stream", "NP3 native PASS; checks=" + checks + "\n"); result.putString("status", "PASS");
        } catch (Throwable failure) {
            Throwable cause = unwrap(failure);
            result.putString("stream", "NP3 native FAIL; stage=" + stage + "; checks=" + checks + "; " + cause + "\n"); result.putString("status", "FAIL");
            try { report.put("status", "FAIL").put("stage", stage).put("failure", cause.toString()); if (activity != null) capture("failure"); } catch (Exception ignored) { }
        } finally {
            try { report.put("checks", checks).put("assertions", assertions); if (output != null) Files.writeString(new File(output, "report.json").toPath(), report.toString(2) + "\n"); }
            catch (Exception failure) { result.putString("stream", "NP3 native FAIL saving evidence: " + failure + "\n"); result.putString("status", "FAIL"); }
            // All files and MediaStore rows belong to the dedicated emulator's temporary layer.
            try { if (activity != null) ui(() -> activity.finish()); } catch (Exception ignored) { }
        }
        finish("PASS".equals(result.getString("status")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void nativeMonochromeRecipesAndJpeg() throws Exception {
        stage="monochrome-renderer";
        File source=new File(output,"standalone-source.jpg");String sourceSha=sha(source);long sourceTime=source.lastModified();
        Bitmap decoded=BitmapFactory.decodeFile(source.getPath());int w=decoded.getWidth(),h=decoded.getHeight();
        int[] input=new int[w*h];decoded.getPixels(input,0,w,0,0,w,h);decoded.recycle();
        Method render=type("ncp.PresetPreviewProfile").getMethod("render",int[].class,int.class,int.class);
        Method point=type("ncp.PresetPreviewProfile").getMethod("applyArgb",int.class);
        Class<?> profileType=type("ncp.PresetPreviewProfile");Object photoRenderer=construct("album.PhotoPresetRenderer",NONE);
        Class<?>[] renderTypes={File.class,File.class,profileType,BooleanSupplier.class,type("album.PhotoPresetRenderer$Progress")};
        JSONArray results=new JSONArray(),batchResults=new JSONArray();String priorTemplate="",priorFull="";
        for(String label:new String[]{"mono","peep","scott"}) {
            String recipeStem=label.equals("scott")?"scott-ilford":"dreamy-"+label;
            stage="monochrome-renderer-"+label;
            byte[] bytes=asset(recipeStem+".np3"),before=bytes.clone();
            String expected=label.equals("scott")?"ff57c07d5c7df1b6f6ffe031ada0b987f6242043c28a2837dc40e3ebdd14561f":label.equals("mono")?"886cc2b108050b2d7c27052cd17f005d6f98800bb577493971a0f221c247313d":"d3774968a0c3919ba7e4ddabd876bb84d9a77c3d733d7cfad53fcdda937e26af";
            check(bytes.length==(label.equals("scott")?1036:978) && sha(bytes).equals(expected),"native-original-monochrome-sha-"+label);
            Object imported=get(invoke(library,"importFile",new Class<?>[]{String.class,byte[].class},"NP3-Native-Dreamy-"+label+".NP3",bytes),"record");
            check("NP3".equals(get(imported,"fileFormat")) && Arrays.equals(bytes,(byte[])invoke(library,"readPreset",new Class<?>[]{String.class},get(imported,"id"))),"native-monochrome-library-keeps-original-bytes-"+label);
            Object profile=invokeStatic("ncp.PresetPreviewProfiles","fromPreset",new Class<?>[]{byte[].class},bytes);
            check(profile.getClass().getName().equals(TARGET+".ncp.Np3MonochromePreviewProfile"),"native-monochrome-factory-family-"+label);
            String model=invoke(profile,"modelVersion").toString();
            check(model.equals(label.equals("scott")?"np3-0310-monochrome-spatial-v1":"np3-0310-deep-tone-monochrome-spatial-v1"),"native-monochrome-model-identity-"+label);
            check((model+":full-jpeg95-srgb-v1").equals(invokeStatic("album.PhotoPresetRenderer","modelVersion",new Class<?>[]{profileType},profile)),"native-monochrome-full-jpeg-model-identity-"+label);
            Bitmap original=(Bitmap)invoke(renderer,"originalTemplate"),template=null,after=null;
            String templateDigest;
            try {
                String originalDigest=pixels(original);template=(Bitmap)invoke(renderer,"render",new Class<?>[]{byte[].class},bytes);
                after=(Bitmap)invoke(renderer,"originalTemplate");templateDigest=pixels(template);
                check(grayBitmap(template),"native-monochrome-template-is-monochrome-"+label);
                check(originalDigest.equals(pixels(after)),"native-monochrome-template-preserves-original-"+label);
                check(!originalDigest.equals(templateDigest),"native-monochrome-template-changes-synthetic-colour-"+label);
                saveBitmap(template,"template-"+recipeStem+".jpg");
            } finally {original.recycle();if(template!=null)template.recycle();if(after!=null)after.recycle();}
            if(!priorTemplate.isEmpty())check(!priorTemplate.equals(templateDigest),"native-monochrome-recipe-templates-are-distinct-"+label);
            priorTemplate=templateDigest;
            int[] rgba={0x0055aaff,0x013fa170,0x7fb64f20,0xff123456,0x00ffffff,0x01808080,0x7fff0000,0xff00ff00};
            int[] rgbaBefore=rgba.clone(),rgbaOutput=(int[])render.invoke(profile,rgba,4,2);boolean alpha=true,gray=true;
            for(int i=0;i<rgba.length;i++) {
                int result=(Integer)point.invoke(profile,rgba[i]);alpha&=(result>>>24)==(rgba[i]>>>24) && (rgbaOutput[i]>>>24)==(rgba[i]>>>24);
                gray&=isGray(result)&&isGray(rgbaOutput[i]);
            }
            check(alpha&&gray&&Arrays.equals(rgba,rgbaBefore),"native-monochrome-point-and-spatial-alpha-monochrome-"+label);
            long arrayStart=SystemClock.elapsedRealtimeNanos();int[] full=(int[])render.invoke(profile,input,w,h);
            double arrayMs=(SystemClock.elapsedRealtimeNanos()-arrayStart)/1e6;
            check(allGray(full),"native-monochrome-complete-array-is-monochrome-"+label);
            String fullDigest=pixelValues(full);
            if(!priorFull.isEmpty())check(!priorFull.equals(fullDigest),"native-monochrome-recipe-full-images-are-distinct-"+label);
            priorFull=fullDigest;
            JSONArray timings=new JSONArray();
            for(int strength:new int[]{100,37,0}) {
                Object effect=strength==100?profile:invokeStatic("ncp.StrengthPreviewProfile","of",new Class<?>[]{profileType,int.class},profile,strength);
                int[] expectedPixels;
                if(strength==100)expectedPixels=full;
                else {
                    expectedPixels=(int[])render.invoke(effect,input,w,h);boolean mixed=true;
                    for(int i=0;i<input.length;i++)if(expectedPixels[i]!=mixPixel(input[i],full[i],strength)){mixed=false;break;}
                    check(mixed,"native-monochrome-strength-exact-completed-effect-mix-"+label+"-"+strength);
                    if(strength==0)check(Arrays.equals(input,expectedPixels),"native-monochrome-zero-strength-pixels-unchanged-"+label);
                    else check(!allGray(expectedPixels),"native-monochrome-partial-strength-retains-source-colour-"+label);
                }
                File target=new File(output,recipeStem+"-strength"+strength+".jpg");
                long started=SystemClock.elapsedRealtimeNanos();
                invoke(photoRenderer,"render",renderTypes,source,target,effect,(BooleanSupplier)()->false,null);
                double elapsedMs=(SystemClock.elapsedRealtimeNanos()-started)/1e6;
                verifyJpeg(target);compareEncodedPixels(expectedPixels,w,h,target,"dreamy-"+label+"-"+strength);
                if(strength==100){Bitmap actual=BitmapFactory.decodeFile(target.getPath());try{check(grayBitmap(actual),"native-monochrome-jpeg-is-monochrome-"+label);}finally{actual.recycle();}}
                check(sourceSha.equals(sha(source))&&sourceTime==source.lastModified(),"native-monochrome-render-preserves-source-"+label+"-"+strength);
                timings.put(new JSONObject().put("strengthPercent",strength).put("actualJpegPipelineMs",elapsedMs).put("outputSha256",sha(target)).put("file",target.getName()));
            }
            File preCancelled=new File(output,recipeStem+"-pre-cancelled.part");boolean preStopped=false;
            try{invoke(photoRenderer,"render",renderTypes,source,preCancelled,profile,(BooleanSupplier)()->true,null);}
            catch(Exception failure){preStopped=unwrap(failure).getClass().getName().endsWith("$Cancelled");}
            check(preStopped&&!preCancelled.exists(),"native-monochrome-pre-cancellation-has-no-output-"+label);
            AtomicBoolean cancel=new AtomicBoolean();AtomicReference<Double> lastProgress=new AtomicReference<>(0.0);
            Object progress=callback("album.PhotoPresetRenderer$Progress",(name,values)->{double value=((Number)values[0]).doubleValue();lastProgress.set(value);if(value>.15)cancel.set(true);});
            File midCancelled=new File(output,recipeStem+"-mid-cancelled.part");boolean midStopped=false;
            try{invoke(photoRenderer,"render",renderTypes,source,midCancelled,profile,(BooleanSupplier)cancel::get,progress);}
            catch(Exception failure){midStopped=unwrap(failure).getClass().getName().endsWith("$Cancelled");}
            check(midStopped&&cancel.get()&&lastProgress.get()>.15&&lastProgress.get()<.9&&!midCancelled.exists(),"native-monochrome-mid-spatial-cancellation-has-no-output-"+label);
            check(Arrays.equals(bytes,before)&&sourceSha.equals(sha(source)),"native-monochrome-cancellation-preserves-preset-and-source-"+label);
            results.put(new JSONObject().put("asset",recipeStem+".np3").put("presetSha256",expected).put("modelVersion",model)
                    .put("width",w).put("height",h).put("completeArrayRenderMs",arrayMs).put("completeArrayArgbSha256",fullDigest).put("jpegPipelines",timings)
                    .put("batchVerified",monochromeBatchEnabled).put("scope","Emulator timings; synthetic JPEG, no private image or NX fidelity threshold."));
            if(monochromeBatchEnabled)batchResults.put(nativeMonochromeBatch(imported,profile,label));
        }
        report.put("monochrome",results);
        if(monochromeBatchEnabled) { report.put("monochromeBatch",batchResults).put("monochromeBatchVerified",batchResults.length()==3); nativeMixedEdits(); }
    }

    private JSONObject nativeMonochromeBatch(Object preset,Object profile,String label) throws Exception {
        String recipeStem=label.equals("scott")?"scott-ilford":"dreamy-"+label;
        stage="monochrome-batch-"+label;
        if(monochromeBatchSource==null) {
            File directory=new File(getTargetContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),"camera-album");
            check(directory.mkdirs()||directory.isDirectory(),"native-monochrome-owned-batch-source-directory");
            monochromeBatchSource=new File(directory,"np3-dreamy-"+UUID.randomUUID().toString().replace("-","")+"__DREAMY_QA.JPG");
            writeSource(monochromeBatchSource);
            monochromeBatchOriginal=construct("album.DownloadedPhoto",new Class<?>[]{File.class,String.class},monochromeBatchSource,"DREAMY_QA.JPG");
            Files.copy(monochromeBatchSource.toPath(),new File(output,"dreamy-batch-source.jpg").toPath());
        }
        String sourceSha=sha(monochromeBatchSource),presetId=(String)get(preset,"id");long sourceTime=monochromeBatchSource.lastModified();
        long started=SystemClock.elapsedRealtimeNanos();Object first=runBatch(monochromeBatchOriginal,presetId);
        double firstMs=(SystemClock.elapsedRealtimeNanos()-started)/1e6;
        List<?> results=(List<?>)get(first,"results");
        check(((Number)get(first,"completed")).intValue()==1&&((Number)get(first,"failed")).intValue()==0&&((Number)get(first,"reused")).intValue()==0&&results.size()==1,
                "native-monochrome-batch-creates-fresh-output-"+label);
        Object effect=results.get(0);File effectFile=(File)invoke(effect,"file");verifyJpeg(effectFile);
        Bitmap actual=BitmapFactory.decodeFile(effectFile.getPath());String actualPixels;
        try{check(grayBitmap(actual),"native-monochrome-batch-is-monochrome-"+label);actualPixels=pixels(actual);}finally{actual.recycle();}
        check(((File)invoke(invoke(batch,"sourceFor",new Class<?>[]{type("album.DownloadedPhoto")},effect),"file")).equals(monochromeBatchSource.getCanonicalFile()),
                "native-monochrome-batch-keeps-exact-source-link-"+label);
        String effectSha=sha(effectFile);long effectTime=effectFile.lastModified();
        File reference=new File(output,recipeStem+"-batch-reference.jpg");
        invoke(construct("album.PhotoPresetRenderer",NONE),"render",new Class<?>[]{File.class,File.class,type("ncp.PresetPreviewProfile"),BooleanSupplier.class,type("album.PhotoPresetRenderer$Progress")},
                monochromeBatchSource,reference,profile,(BooleanSupplier)()->false,null);
        check(effectSha.equals(sha(reference)),"native-monochrome-batch-matches-complete-renderer-jpeg-bytes-"+label);
        started=SystemClock.elapsedRealtimeNanos();Object repeated=runBatch(effect,presetId);
        double reuseMs=(SystemClock.elapsedRealtimeNanos()-started)/1e6;
        List<?> repeatedResults=(List<?>)get(repeated,"results");
        check(((Number)get(repeated,"completed")).intValue()==1&&((Number)get(repeated,"failed")).intValue()==0&&((Number)get(repeated,"reused")).intValue()==1&&repeatedResults.size()==1
                        &&((File)invoke(repeatedResults.get(0),"file")).getCanonicalFile().equals(effectFile.getCanonicalFile()),
                "native-monochrome-batch-reapplication-reuses-same-output-"+label);
        check(effectSha.equals(sha(effectFile))&&effectTime==effectFile.lastModified()&&sourceSha.equals(sha(monochromeBatchSource))&&sourceTime==monochromeBatchSource.lastModified(),
                "native-monochrome-batch-reuse-preserves-effect-and-original-"+label);
        if(priorMonochromeBatchEffect!=null)check(!priorMonochromeBatchEffect.getCanonicalFile().equals(effectFile.getCanonicalFile())&&!priorMonochromeBatchPixels.equals(actualPixels),
                "native-monochrome-batch-same-source-recipe-identities-and-pixels-are-distinct-"+label);
        priorMonochromeBatchEffect=effectFile;priorMonochromeBatchPixels=actualPixels;
        File saved=new File(output,recipeStem+"-batch100.jpg");Files.copy(effectFile.toPath(),saved.toPath());
        return new JSONObject().put("asset",recipeStem+".np3").put("presetSha256",get(preset,"sha256"))
                .put("modelVersion",invokeStatic("album.PhotoPresetRenderer","modelVersion",new Class<?>[]{type("ncp.PresetPreviewProfile")},profile))
                .put("sourceFile",monochromeBatchSource.getPath()).put("sourceSha256",sourceSha).put("outputFile",effectFile.getPath()).put("outputSha256",effectSha)
                .put("evidenceFile",saved.getName()).put("decodedArgbSha256",actualPixels).put("completed",1).put("failed",0).put("firstReused",0).put("secondReused",1)
                .put("firstBatchMs",firstMs).put("reuseBatchMs",reuseMs).put("sourceLinkVerified",true).put("exactRendererJpegBytesVerified",true).put("mediaStorePublished",true).put("mediaStoreUri",verifyPublished(effect,"native-monochrome-batch-"+label));
    }

    private String verifyPublished(Object photo,String label) throws Exception {
        Object state=invoke(construct("album.PhotoLiveGallery",new Class<?>[]{Context.class},getTargetContext()),"publicationStatus",new Class<?>[]{type("album.DownloadedPhoto")},photo);
        String uri=get(state,"uri").toString();
        check(get(state,"status").toString().equals("PUBLISHED")&&uri.startsWith("content://media/"),label+"-mediastore-published-status");
        try(Cursor rows=getTargetContext().getContentResolver().query(Uri.parse(uri),new String[]{MediaStore.Images.Media.IS_PENDING},null,null,null)) {
            check(rows!=null&&rows.moveToFirst()&&rows.getInt(0)==0,label+"-mediastore-row-committed");
        }
        try(InputStream bytes=getTargetContext().getContentResolver().openInputStream(Uri.parse(uri))) {
            check(bytes!=null&&sha(bytes.readAllBytes()).equals(sha((File)invoke(photo,"file"))),label+"-mediastore-bytes-match-output");
        }
        return uri;
    }

    /** Exercise the same independent frozen draft export path used by the photo editor. */
    private void nativeMixedEdits() throws Exception {
        stage="mixed-draft-export";
        File directory=new File(getTargetContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),"camera-album");
        String owner="np3-native-"+args.getString("runId");
        Object store=construct("album.PhotoEditStore",new Class<?>[]{File.class,String.class},getTargetContext().getFilesDir(),owner);
        java.util.ArrayList<Object> edits=new java.util.ArrayList<>(),originals=new java.util.ArrayList<>(),drafts=new java.util.ArrayList<>(),profiles=new java.util.ArrayList<>();
        java.util.ArrayList<File> sources=new java.util.ArrayList<>();
        String[] assets={"scott-ilford.np3","dreamy-peep.np3"};int[] strengths={37,100};
        String[] sourceShas=new String[2];long[] sourceTimes=new long[2];
        for(int index=0;index<2;index++) {
            File source=new File(directory,"np3-mixed-"+UUID.randomUUID().toString().replace("-","")+"__MIX_"+index+".JPG");writeSource(source);
            Object original=construct("album.DownloadedPhoto",new Class<?>[]{File.class,String.class},source,"MIX_"+index+".JPG");
            byte[] bytes=asset(assets[index]);sourceShas[index]=sha(source);sourceTimes[index]=source.lastModified();
            Object draft=construct("album.PhotoEditStore$Draft",new Class<?>[]{String.class,byte[].class,String.class,int.class,boolean.class},sourceShas[index],bytes,assets[index],strengths[index],false);
            invoke(store,"save",new Class<?>[]{type("album.PhotoEditStore$Draft")},draft);
            Object frozen=invoke(store,"readPending",new Class<?>[]{String.class},sourceShas[index]);
            check(frozen!=null&&invoke(frozen,"identity").equals(invoke(draft,"identity")),"native-mixed-draft-roundtrip-"+index);
            Object local=invokeStatic("album.PhotoPresetBatch$Source","local",new Class<?>[]{type("album.DownloadedPhoto")},original);
            edits.add(invokeStatic("album.PhotoPresetBatch$Edit","draft",new Class<?>[]{type("album.PhotoPresetBatch$Source"),type("album.PhotoEditStore$Draft"),String.class},local,frozen,owner));
            originals.add(original);drafts.add(draft);sources.add(source);
            Object base=invokeStatic("ncp.PresetPreviewProfiles","fromPreset",new Class<?>[]{byte[].class},bytes);
            profiles.add(invokeStatic("ncp.StrengthPreviewProfile","of",new Class<?>[]{type("ncp.PresetPreviewProfile"),int.class},base,strengths[index]));
            Files.copy(source.toPath(),new File(output,"mixed-source-"+index+".jpg").toPath());
        }
        Object first=runEdits(edits);List<?> results=(List<?>)get(first,"results");
        check(((Number)get(first,"completed")).intValue()==2&&((Number)get(first,"failed")).intValue()==0&&((Number)get(first,"reused")).intValue()==0&&results.size()==2,
                "native-mixed-independent-drafts-two-fresh-outputs");
        JSONArray records=new JSONArray();String[] effectShas=new String[2];long[] effectTimes=new long[2];
        for(int index=0;index<2;index++) {
            Object effect=results.get(index);File effectFile=(File)invoke(effect,"file");verifyJpeg(effectFile);
            File reference=new File(output,"mixed-reference-"+index+".jpg");
            invoke(construct("album.PhotoPresetRenderer",NONE),"render",new Class<?>[]{File.class,File.class,type("ncp.PresetPreviewProfile"),BooleanSupplier.class,type("album.PhotoPresetRenderer$Progress")},
                    sources.get(index),reference,profiles.get(index),(BooleanSupplier)()->false,null);
            check(sha(effectFile).equals(sha(reference)),"native-mixed-per-photo-preset-and-strength-match-jpeg-bytes-"+index);
            check(((File)invoke(invoke(batch,"sourceFor",new Class<?>[]{type("album.DownloadedPhoto")},effect),"file")).getCanonicalFile().equals(sources.get(index).getCanonicalFile()),
                    "native-mixed-exact-source-link-"+index);
            Bitmap actual=BitmapFactory.decodeFile(effectFile.getPath());
            try{check(index==0?!grayBitmap(actual):grayBitmap(actual),"native-mixed-distinct-partial-and-full-monochrome-strength-"+index);}finally{actual.recycle();}
            check(sourceShas[index].equals(sha(sources.get(index)))&&sourceTimes[index]==sources.get(index).lastModified(),"native-mixed-preserves-original-"+index);
            Object stored=invoke(store,"read",new Class<?>[]{String.class},sourceShas[index]);
            check((Boolean)get(stored,"exported")&&invoke(stored,"identity").equals(invoke(drafts.get(index),"identity"))&&invoke(store,"readPending",new Class<?>[]{String.class},sourceShas[index])==null,
                    "native-mixed-success-marks-only-same-draft-exported-"+index);
            String uri=verifyPublished(effect,"native-mixed-effect-"+index);verifyPublished(originals.get(index),"native-mixed-original-"+index);
            effectShas[index]=sha(effectFile);effectTimes[index]=effectFile.lastModified();Files.copy(effectFile.toPath(),new File(output,"mixed-effect-"+index+".jpg").toPath());
            records.put(new JSONObject().put("asset",assets[index]).put("strengthPercent",strengths[index]).put("sourceFile",sources.get(index).getPath()).put("sourceSha256",sourceShas[index])
                    .put("outputFile",effectFile.getPath()).put("outputSha256",effectShas[index]).put("modelVersion",invoke(profiles.get(index),"modelVersion")).put("mediaStoreUri",uri));
        }
        Object repeated=runEdits(edits);List<?> repeatedResults=(List<?>)get(repeated,"results");
        check(((Number)get(repeated,"completed")).intValue()==2&&((Number)get(repeated,"failed")).intValue()==0&&((Number)get(repeated,"reused")).intValue()==2&&repeatedResults.size()==2,
                "native-mixed-repeated-independent-drafts-reuse-both");
        for(int index=0;index<2;index++) {
            File file=(File)invoke(repeatedResults.get(index),"file");
            check(effectShas[index].equals(sha(file))&&effectTimes[index]==file.lastModified(),"native-mixed-reuse-does-not-rewrite-"+index);
        }
        check(!effectShas[0].equals(effectShas[1]),"native-mixed-independent-choices-produce-distinct-outputs");
        report.put("mixedEdits",new JSONObject().put("completed",2).put("failed",0).put("firstReused",0).put("secondReused",2).put("choices",records)
                .put("scope","Actual Android startEdits with frozen per-photo drafts; Scott37% and DreamyPeep100%, fresh synthetic photos, committed MediaStore bytes checked read-only."));
    }

    private Object runEdits(List<?> edits) throws Exception {
        CountDownLatch accepted=new CountDownLatch(1),terminal=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();AtomicReference<Object> result=new AtomicReference<>();
        Object callback=callback("album.AlbumController$Callback",(name,values)->{if(name.equals("onError"))error.set(values[0].toString());accepted.countDown();});
        Object finished=callback("album.AlbumController$Callback",(name,values)->{if(name.equals("onSuccess"))result.set(values[0]);else error.set(values[0].toString());terminal.countDown();});
        ui(()->invoke(batch,"startEdits",new Class<?>[]{List.class,type("album.AlbumController$Callback"),type("album.AlbumController$Callback")},edits,callback,finished));
        check(accepted.await(10,TimeUnit.SECONDS)&&error.get()==null,"native-mixed-drafts-start-accepted");
        check(terminal.await(45,TimeUnit.SECONDS)&&error.get()==null&&result.get()!=null,"native-mixed-drafts-terminal-snapshot");
        return result.get();
    }

    private static boolean isGray(int value){return ((value>>>16)&255)==((value>>>8)&255)&&((value>>>8)&255)==(value&255);}
    private static boolean allGray(int[] values){for(int value:values)if(!isGray(value))return false;return true;}
    private static boolean grayBitmap(Bitmap bitmap){int[] values=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(values,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());return allGray(values);}
    private static int mixPixel(int source,int effect,int strength){int result=source&0xff000000;for(int shift=0;shift<=16;shift+=8)result|=((((source>>>shift)&255)*(100-strength)+((effect>>>shift)&255)*strength+50)/100)<<shift;return result;}
    private static String pixelValues(int[] values)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];int at=0;for(int value:values){buffer[at++]=(byte)(value>>>24);buffer[at++]=(byte)(value>>>16);buffer[at++]=(byte)(value>>>8);buffer[at++]=(byte)value;if(at==buffer.length){digest.update(buffer);at=0;}}if(at>0)digest.update(buffer,0,at);return hex(digest.digest());}

    private void verifiedQuickSharpCorpusRecipes() throws Exception {
        int width = 53, height = 41; int[] input = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int gray = 128 + (int)Math.round(42 * Math.sin(x * .29) + 26 * Math.sin(y * .37));
            input[y * width + x] = 0xff000000 | gray * 0x010101;
        }
        Method render = type("ncp.PresetPreviewProfile").getMethod("render", int[].class, int.class, int.class);
        JSONArray accepted = new JSONArray();
        for (String file : new String[] {"quick-minus.np3", "quick-plus.np3"}) {
            byte[] bytes = asset(file), original = bytes.clone(); boolean plus = file.equals("quick-plus.np3");
            Object profile = invokeStatic("ncp.PresetPreviewProfiles", "fromPreset", new Class<?>[]{byte[].class}, bytes);
            check(((Number)invoke(profile, "sharpening")).doubleValue() == (plus ? 3.5 : 0)
                            && ((Number)invoke(profile, "clarity")).doubleValue() == (plus ? 1.5 : 0)
                            && ((Number)invoke(profile, "midRangeSharpening")).doubleValue() == (plus ? 3 : 0),
                    "native-verified-quick-sharp-keeps-exact-spatial-tuple-" + (plus ? "plus" : "minus"));
            byte[] manual = bytes.clone();
            int offset = 4;
            for (int index = 1; index < 20; index++) offset += 8 + read32(manual, offset + 4);
            check(read32(manual, offset) == 0x1400 && read32(manual, offset + 4) == 2,
                    "native-quick-sharp-fixture-has-expected-tlv-" + (plus ? "plus" : "minus"));
            manual[offset + 8] = (byte)255;
            Object manualProfile = invokeStatic("ncp.PresetPreviewProfiles", "fromPreset", new Class<?>[]{byte[].class}, manual);
            int[] linkedOutput = (int[])render.invoke(profile, input, width, height);
            check(Arrays.equals(linkedOutput, (int[])render.invoke(manualProfile, input, width, height)),
                    "native-quick-sharp-does-not-double-apply-spatial-effect-" + (plus ? "plus" : "minus"));
            check(Arrays.equals(bytes, original), "native-quick-sharp-parser-leaves-corpus-bytes-unchanged-" + (plus ? "plus" : "minus"));
            accepted.put(new JSONObject().put("asset", file).put("sha256", sha(original)).put("modelVersion", invoke(profile, "modelVersion")));
        }
        report.put("verifiedQuickSharpCorpus", accepted);
        // Bluegrain rejection is independently asserted by importAndParse(); these
        // copied in-memory manual states are app consistency tests, not NX captures.
    }

    private void nativeV3ColourBehaviour() throws Exception {
        byte[] corpusBefore = supportedBytes.clone();
        // Retain the verified container, reset its controls, and omit any appended
        // curve. These are synthetic parameter checks, not NX reference captures.
        byte[] neutral = Arrays.copyOf(supportedBytes, 392);
        Arrays.fill(neutral, 388, 392, (byte)0);
        for (int index : new int[] {6, 7, 20, 22, 25, 26, 27, 28, 29, 30}) neutral[payloadOffset(neutral, index)] = (byte)128;
        Arrays.fill(neutral, payloadOffset(neutral, 31), payloadOffset(neutral, 31) + 24, (byte)128);
        int grade = payloadOffset(neutral, 32);
        for (int i = 0; i < 3; i++) {
            neutral[grade + i * 4] = (byte)128; neutral[grade + i * 4 + 1] = 0;
            neutral[grade + i * 4 + 2] = neutral[grade + i * 4 + 3] = (byte)128;
        }
        neutral[grade + 16] = (byte)178; neutral[grade + 18] = (byte)128;
        byte[] bright = neutral.clone();
        for (int i = 0; i < 8; i++) bright[payloadOffset(bright, 31) + i * 3 + 2] = (byte)148;
        byte[] contrast = neutral.clone(); contrast[payloadOffset(contrast, 25)] = (byte)228;
        Object brightnessProfile = invokeStatic("ncp.PresetPreviewProfiles", "fromPreset", new Class<?>[]{byte[].class}, bright);
        Object contrastProfile = invokeStatic("ncp.PresetPreviewProfiles", "fromPreset", new Class<?>[]{byte[].class}, contrast);
        check("np3-0310-encoded-hsl-zones-spatial-v3".equals(invoke(brightnessProfile, "modelVersion")), "native-v3-colour-profile-model");
        Method point = type("ncp.PresetPreviewProfile").getMethod("applyArgb", int.class);
        int nearGray = (Integer)point.invoke(brightnessProfile, 0xff818080);
        check(nearGray == 0xff818080, "native-v3-blender-brightness-suppresses-near-gray-fringe");
        int saturated = (Integer)point.invoke(brightnessProfile, 0xffff0000);
        check(saturated == 0xffff3333, "native-v3-blender-brightness-keeps-saturated-response");
        int input = 0xff507080, changed = (Integer)point.invoke(contrastProfile, input);
        int dr = ((changed >>> 16) & 255) - 80, dg = ((changed >>> 8) & 255) - 112, db = (changed & 255) - 128;
        check(dr < 0 && Math.abs(dr - dg) <= 1 && Math.abs(dr - db) <= 1 && ((changed >>> 16) & 255) > 40,
                "native-v3-contrast-retains-chromatic-differences-without-severe-clipping");
        check((Integer)point.invoke(contrastProfile, 0xff000000) == 0xff000000
                        && (Integer)point.invoke(contrastProfile, 0xffffffff) == 0xffffffff,
                "native-v3-contrast-keeps-black-white-endpoints");
        boolean alphaKept = true, sharedPath = true;
        Method render = type("ncp.PresetPreviewProfile").getMethod("render", int[].class, int.class, int.class);
        int[] pixels = {0x00000000, 0x01ffffff, 0x7f818080, 0xffb64f20};
        for (Object profile : new Object[] {brightnessProfile, contrastProfile}) {
            int[] rendered = (int[])render.invoke(profile, pixels, 4, 1);
            for (int i = 0; i < pixels.length; i++) sharedPath &= rendered[i] == (Integer)point.invoke(profile, pixels[i]);
            for (int alpha : new int[] {0, 1, 127, 255}) for (int rgb : new int[] {0, 0xffffff, 0x818080, 0xb64f20}) {
                int result = (Integer)point.invoke(profile, (alpha << 24) | rgb);
                int opaque = (Integer)point.invoke(profile, 0xff000000 | rgb);
                alphaKept &= (result >>> 24) == alpha && (result & 0xffffff) == (opaque & 0xffffff);
            }
        }
        check(alphaKept, "native-v3-colour-preserves-transparent-and-translucent-alpha");
        check(sharedPath, "native-v3-colour-point-and-shared-row-path-agree");
        check(Arrays.equals(corpusBefore, supportedBytes), "native-v3-synthetic-controls-leave-real-corpus-unchanged");
        report.put("v3Colour", new JSONObject().put("nearGrayOutputArgb", Integer.toHexString(nearGray))
                .put("saturatedOutputArgb", Integer.toHexString(saturated)).put("contrastInputArgb", Integer.toHexString(input))
                .put("contrastOutputArgb", Integer.toHexString(changed)).put("alphaSamples", 32)
                .put("scope", "Synthetic Android behaviour checks for the v3 approximation; not NX colour calibration"));
    }

    private static int payloadOffset(byte[] bytes, int index) {
        int offset = 4;
        for (int i = 1; i < index; i++) offset += 8 + read32(bytes, offset + 4);
        return offset + 8;
    }

    private static int read32(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16
                | (bytes[offset + 2] & 255) << 8 | bytes[offset + 3] & 255;
    }

    private void nativeSpatialAndStrengthEquivalence() throws Exception {
        File source = new File(output, "standalone-source.jpg");
        Object profile = invokeStatic("ncp.PresetPreviewProfiles", "fromPreset", new Class<?>[]{byte[].class}, supportedBytes);
        String model = invoke(profile, "modelVersion").toString();
        check(model.contains("spatial-v3"), "actual-product-uses-v3-colour-and-spatial-cache-identity");
        report.put("modelVersion", model);
        Bitmap decoded = BitmapFactory.decodeFile(source.getPath());
        int w = decoded.getWidth(), h = decoded.getHeight(); int[] input = new int[w * h];
        decoded.getPixels(input, 0, w, 0, 0, w, h); decoded.recycle();
        String originalSha = sha(source);
        Method render = type("ncp.PresetPreviewProfile").getMethod("render", int[].class, int.class, int.class);
        Method point = type("ncp.PresetPreviewProfile").getMethod("applyArgb", int.class);
        int[] full = (int[])render.invoke(profile, input, w, h);
        boolean spatialChanged = false;
        for (int i = 0; i < input.length; i++) {
            if (full[i] != (Integer)point.invoke(profile, input[i])) { spatialChanged = true; break; }
        }
        check(spatialChanged, "real-np3-spatial-parameters-affect-image-neighbourhoods");
        compareEncodedPixels(full, w, h, new File(output, "standalone-np3.jpg"), "full-strength");
        Object partial = invokeStatic("ncp.StrengthPreviewProfile", "of", new Class<?>[]{type("ncp.PresetPreviewProfile"), int.class}, profile, 37);
        int[] partialPixels = (int[])render.invoke(partial, input, w, h);
        boolean exactMix = true;
        for (int i = 0; i < input.length; i++) {
            int expected = input[i] & 0xff000000;
            for (int shift = 0; shift <= 16; shift += 8)
                expected |= ((((input[i] >>> shift) & 255) * 63 + ((full[i] >>> shift) & 255) * 37 + 50) / 100) << shift;
            if (partialPixels[i] != expected) { exactMix = false; break; }
        }
        check(exactMix, "native-strength-mixes-complete-spatial-effect-after-filtering");
        Object photoRenderer = construct("album.PhotoPresetRenderer", NONE);
        File partialOutput = new File(output, "standalone-np3-strength37.jpg");
        invoke(photoRenderer, "render", new Class<?>[]{File.class, File.class, type("ncp.PresetPreviewProfile"), BooleanSupplier.class, type("album.PhotoPresetRenderer$Progress")},
                source, partialOutput, partial, (BooleanSupplier)() -> false, null);
        verifyJpeg(partialOutput);
        compareEncodedPixels(partialPixels, w, h, partialOutput, "partial-strength");
        Object zero = invokeStatic("ncp.StrengthPreviewProfile", "of", new Class<?>[]{type("ncp.PresetPreviewProfile"), int.class}, profile, 0);
        check(Arrays.equals(input, (int[])render.invoke(zero, input, w, h)), "native-zero-strength-preserves-exact-input-pixels");
        check(originalSha.equals(sha(source)), "native-spatial-strength-checks-leave-original-file-unchanged");
    }

    private void compareEncodedPixels(int[] expectedPixels, int w, int h, File actualFile, String label) throws Exception {
        Bitmap expected = Bitmap.createBitmap(expectedPixels, w, h, Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try { check(expected.compress(Bitmap.CompressFormat.JPEG, 95, bytes), "native-reference-jpeg-encoded-" + label); }
        finally { expected.recycle(); }
        byte[] encoded = bytes.toByteArray();
        Bitmap expectedDecoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.length), actual = BitmapFactory.decodeFile(actualFile.getPath());
        try { check(pixels(expectedDecoded).equals(pixels(actual)), "native-preview-and-full-size-jpeg-pixels-match-" + label); }
        finally { expectedDecoded.recycle(); actual.recycle(); }
    }

    private void importAndParse() throws Exception {
        supportedBytes = asset("supported.np3"); unsupportedBytes = asset("unsupported.np3");
        supported = get(invoke(library, "importFile", new Class<?>[]{String.class, byte[].class}, "NP3-Native-BleachBypass.NP3", supportedBytes), "record");
        unsupported = get(invoke(library, "importFile", new Class<?>[]{String.class, byte[].class}, "NP3-Native-Bluegrain.NP3", unsupportedBytes), "record");
        check(get(supported, "fileFormat").equals("NP3") && get(unsupported, "fileFormat").equals("NP3"), "real-corpus-presets-import-as-np3");
        check(Arrays.equals(supportedBytes, (byte[])invoke(library, "readPreset", new Class<?>[]{String.class}, get(supported, "id"))), "library-preserves-source-preset-bytes");
        Object profile = invokeStatic("ncp.PresetPreviewProfiles", "fromPreset", new Class<?>[]{byte[].class}, supportedBytes);
        check(invoke(profile, "modelVersion").toString().startsWith("np3-0310-"), "supported-real-recipe-parses-as-np3");
        boolean rejected = false; String reason = "";
        try { invokeStatic("ncp.PresetPreviewProfiles", "fromPreset", new Class<?>[]{byte[].class}, unsupportedBytes); }
        catch (Exception failure) { rejected = true; reason = unwrap(failure).getMessage(); }
        report.put("unsupportedRejected", rejected).put("unsupportedObservedReason", reason);
        check(rejected && reason != null && !reason.isBlank() && reason.contains("不支持"), "unsupported-bluegrain-reports-specific-rejection-reason");
        unsupportedReason = reason;
        report.put("supportedName", get(supported, "displayName")).put("supportedSha256", sha(supportedBytes)).put("unsupportedName", get(unsupported, "displayName"))
                .put("unsupportedReason", reason).put("unsupportedSha256", sha(unsupportedBytes));
    }

    private void templateAndFullResolution() throws Exception {
        renderer = construct("ui.PresetPreviewRenderer", new Class<?>[]{Context.class}, getTargetContext());
        Bitmap original = (Bitmap)invoke(renderer, "originalTemplate"), effect = null, after = null;
        try {
            String originalPixels = pixels(original);
            effect = (Bitmap)invoke(renderer, "render", new Class<?>[]{byte[].class}, supportedBytes);
            after = (Bitmap)invoke(renderer, "originalTemplate");
            check(original.getWidth() == effect.getWidth() && original.getHeight() == effect.getHeight(), "native-template-effect-dimensions");
            check(!originalPixels.equals(pixels(effect)), "real-np3-changes-native-template-pixels");
            check(originalPixels.equals(pixels(after)), "template-original-pixels-remain-unchanged");
            saveBitmap(original, "template-original.jpg"); saveBitmap(effect, "template-np3.jpg");
        } finally { original.recycle(); if (effect != null) effect.recycle(); if (after != null) after.recycle(); }
        boolean rejected = false;
        try { Bitmap bad = (Bitmap)invoke(renderer, "render", new Class<?>[]{byte[].class}, unsupportedBytes); bad.recycle(); }
        catch (Exception failure) { rejected = unwrap(failure).getMessage().equals(unsupportedReason); }
        check(rejected, "native-template-rejects-unsupported-recipe");
        File source = new File(output, "standalone-source.jpg"); writeSource(source);
        String before = sha(source); long modified = source.lastModified();
        Object profile = invokeStatic("ncp.PresetPreviewProfiles", "fromPreset", new Class<?>[]{byte[].class}, supportedBytes);
        Object photoRenderer = construct("album.PhotoPresetRenderer", NONE);
        File target = new File(output, "standalone-np3.jpg");
        invoke(photoRenderer, "render", new Class<?>[]{File.class, File.class, type("ncp.PresetPreviewProfile"), BooleanSupplier.class, type("album.PhotoPresetRenderer$Progress")},
                source, target, profile, (BooleanSupplier)() -> false, null);
        verifyJpeg(target);
        check(sha(source).equals(before) && source.lastModified() == modified, "native-full-jpeg-render-preserves-original");
        check(!sha(target).equals(before), "native-full-jpeg-produces-different-effect-file");
        File cancelled = new File(output, "cancelled.part"); boolean stopped = false;
        try { invoke(photoRenderer, "render", new Class<?>[]{File.class, File.class, type("ncp.PresetPreviewProfile"), BooleanSupplier.class, type("album.PhotoPresetRenderer$Progress")}, source, cancelled, profile, (BooleanSupplier)() -> true, null); }
        catch (Exception failure) { stopped = unwrap(failure).getClass().getName().endsWith("$Cancelled"); }
        check(stopped && !cancelled.exists() && sha(source).equals(before), "native-render-pre-cancellation-publishes-no-file");
    }

    private void batchAndGallery() throws Exception {
        File directory = new File(getTargetContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES), "camera-album");
        check(directory.mkdirs() || directory.isDirectory(), "owned-private-original-directory");
        File source = new File(directory, "np3-native-" + UUID.randomUUID().toString().replace("-", "") + "__NP3_QA.JPG"); writeSource(source);
        Object original = construct("album.DownloadedPhoto", new Class<?>[]{File.class, String.class}, source, "NP3_QA.JPG");
        String sourceSha = sha(source); long modified = source.lastModified();
        Object first = runBatch(original, (String)get(supported, "id"));
        report.put("firstBatchCompleted", get(first, "completed")).put("firstBatchFailed", get(first, "failed")).put("firstBatchReused", get(first, "reused")).put("firstBatchError", get(first, "error"));
        check(((Number)get(first, "completed")).intValue() == 1 && ((Number)get(first, "failed")).intValue() == 0 && ((Number)get(first, "reused")).intValue() == 0, "native-np3-batch-creates-fresh-output");
        List<?> results = (List<?>)get(first, "results"); check(results.size() == 1, "native-np3-batch-has-one-result");
        Object effect = results.get(0); File effectFile = (File)invoke(effect, "file"); verifyJpeg(effectFile);
        check(((File)invoke(invoke(batch, "sourceFor", new Class<?>[]{type("album.DownloadedPhoto")}, effect), "file")).equals(source.getCanonicalFile()), "native-np3-effect-retains-exact-original-link");
        String effectSha = sha(effectFile); long effectTime = effectFile.lastModified();
        Object repeated = runBatch(effect, (String)get(supported, "id"));
        check(((Number)get(repeated, "completed")).intValue() == 1 && ((Number)get(repeated, "reused")).intValue() == 1, "native-np3-reapplication-reuses-complete-output");
        check(effectSha.equals(sha(effectFile)) && effectTime == effectFile.lastModified(), "native-np3-reuse-does-not-rewrite-effect");
        check(sourceSha.equals(sha(source)) && modified == source.lastModified(), "native-np3-batch-preserves-original-bytes-and-time");
        Object gallery = construct("album.PhotoLiveGallery", new Class<?>[]{Context.class}, getTargetContext());
        String uri = (String)invoke(gallery, "publish", new Class<?>[]{type("album.DownloadedPhoto")}, effect);
        check(uri.startsWith("content://media/"), "native-effect-published-to-real-mediastore");
        try (Cursor rows = getTargetContext().getContentResolver().query(Uri.parse(uri), new String[]{MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT}, null, null, null)) {
            check(rows != null && rows.moveToFirst() && rows.getInt(0) == 0, "native-mediastore-row-is-committed");
            report.put("mediaStoreWidth", rows.getInt(1)).put("mediaStoreHeight", rows.getInt(2));
        }
        try (InputStream bytes = getTargetContext().getContentResolver().openInputStream(Uri.parse(uri))) { check(bytes != null && sha(bytes.readAllBytes()).equals(effectSha), "native-mediastore-bytes-match-private-output"); }
        report.put("sourceFile", source.getPath()).put("sourceSha256", sourceSha).put("effectFile", effectFile.getPath()).put("effectSha256", effectSha).put("mediaStoreUri", uri);
        Object failed = runBatch(original, (String)get(unsupported, "id"));
        check(((Number)get(failed, "failed")).intValue() == 1 && ((List<?>)get(failed, "results")).isEmpty() && get(failed, "error").toString().contains(unsupportedReason), "native-batch-unsupported-recipe-fails-explicitly-without-output");
        check(sourceSha.equals(sha(source)) && effectSha.equals(sha(effectFile)), "unsupported-batch-preserves-original-and-prior-effect");
    }

    private Object runBatch(Object photo, String presetId) throws Exception {
        Object source = invokeStatic("album.PhotoPresetBatch$Source", "local", new Class<?>[]{type("album.DownloadedPhoto")}, photo);
        CountDownLatch accepted = new CountDownLatch(1), terminal = new CountDownLatch(1); AtomicReference<String> error = new AtomicReference<>(); AtomicReference<Object> result = new AtomicReference<>();
        Object callback = callback("album.AlbumController$Callback", (name, values) -> { if (name.equals("onError")) error.set(values[0].toString()); accepted.countDown(); });
        Object finished = callback("album.AlbumController$Callback", (name, values) -> { if (name.equals("onSuccess")) result.set(values[0]); else error.set(values[0].toString()); terminal.countDown(); });
        ui(() -> invoke(batch, "startTracked", new Class<?>[]{List.class, String.class, type("album.AlbumController$Callback"), type("album.AlbumController$Callback")}, List.of(source), presetId, callback, finished));
        check(accepted.await(10, TimeUnit.SECONDS) && error.get() == null, "native-batch-start-accepted");
        check(terminal.await(45, TimeUnit.SECONDS) && error.get() == null && result.get() != null, "native-batch-terminal-snapshot");
        return result.get();
    }

    private void detailAndPicker() throws Exception {
        ui(() -> { set(activity, "albumTab", false); set(activity, "settingsTab", false); set(activity, "localTab", true); invoke(activity, "openPreset", new Class<?>[]{Object.class, boolean.class}, supported, false); });
        until(() -> {
            String key = "sim:" + get(supported, "id") + ':' + invoke(get(activity, "presetPreviews"), "versionKey");
            return ((LruCache<?,?>)get(activity, "imageCache")).snapshot().containsKey(key);
        }, "native-detail-renders-supported-np3-preview");
        capture("detail-supported");
        Object picker = construct("ui.PresetSelectionPage", new Class<?>[]{Activity.class}, activity);
        AtomicReference<Object> selected = new AtomicReference<>(); Object cb = callback("ui.PresetSelectionPage$Callback", (name, values) -> { if (name.equals("onSelected")) selected.set(values[0]); });
        ui(() -> invoke(picker, "show", new Class<?>[]{String.class, String.class, boolean.class, BooleanSupplier.class, type("ui.PresetSelectionPage$Callback")}, "选择 NP3 预设", get(supported, "id"), false, (BooleanSupplier)() -> true, cb));
        until(() -> pickerEntry(picker, (String)get(supported, "id"), "READY") != null && pickerEntry(picker, (String)get(unsupported, "id"), "UNSUPPORTED") != null, "native-picker-np3-ready-and-unsupported-states");
        Object rejected = pickerEntry(picker, (String)get(unsupported, "id"), "UNSUPPORTED");
        check(get(rejected, "error").toString().contains(unsupportedReason), "native-picker-shows-specific-unsupported-reason");
        capture("selector-np3");
        Object ready = pickerEntry(picker, (String)get(supported, "id"), "READY");
        ui(() -> invoke(picker, "choose", new Class<?>[]{ready.getClass()}, ready));
        until(() -> selected.get() != null, "native-picker-selects-supported-np3");
        check(get(selected.get(), "id").equals(get(supported, "id")), "native-picker-selected-recipe-identity");
        ui(() -> invoke(picker, "close"));
        ui(() -> invoke(activity, "openPreset", new Class<?>[]{Object.class, boolean.class}, unsupported, false));
        until(() -> {
            String key = "sim:" + get(unsupported, "id") + ':' + invoke(get(activity, "presetPreviews"), "versionKey");
            return ((Set<?>)get(activity, "failedImages")).contains(key);
        }, "native-detail-unsupported-preview-shows-failure-state");
        capture("detail-unsupported");
    }

    private Object pickerEntry(Object picker, String id, String state) throws Exception {
        for (Object entry : (List<?>)get(picker, "entries")) if (get(get(entry, "preset"), "id").equals(id) && get(entry, "state").toString().equals(state)) return entry;
        return null;
    }
    private void writeSource(File file) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1800, 1200, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(bitmap); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] colours = {Color.rgb(62,122,176), Color.rgb(221,167,120), Color.rgb(72,149,91), Color.rgb(212,77,88), Color.rgb(221,191,74), Color.rgb(145,98,180)};
        for (int i = 0; i < colours.length; i++) { paint.setColor(colours[i]); canvas.drawRect((i%3)*600, (i/3)*600, (i%3+1)*600, (i/3+1)*600, paint); }
        paint.setColor(Color.WHITE); paint.setTextSize(64); canvas.drawText("NP3 native QA", 70, 110, paint);
        paint.setTextSize(28); canvas.drawText(UUID.randomUUID().toString(), 70, 170, paint);
        try (FileOutputStream stream = new FileOutputStream(file)) { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream), "native-synthetic-source-encoded"); } finally { bitmap.recycle(); }
        ExifInterface exif = new ExifInterface(file.getPath()); exif.setAttribute("Make", "NP3 Native QA"); exif.setAttribute("Model", "Synthetic 1800x1200"); exif.setAttribute("DateTimeOriginal", "2026:10:18 21:00:00"); exif.setAttribute("Orientation", "6"); exif.setAttribute("ColorSpace", "1"); exif.saveAttributes();
    }
    private void verifyJpeg(File file) throws Exception {
        Bitmap image = BitmapFactory.decodeFile(file.getPath()); check(image != null, "native-output-jpeg-decodes");
        try { check(image.getWidth() == 1800 && image.getHeight() == 1200, "native-output-preserves-full-resolution"); check(ColorSpace.get(ColorSpace.Named.SRGB).equals(image.getColorSpace()), "native-output-is-srgb"); }
        finally { image.recycle(); }
        ExifInterface exif = new ExifInterface(file.getPath()); check("NP3 Native QA".equals(exif.getAttribute("Make")) && "Synthetic 1800x1200".equals(exif.getAttribute("Model")), "native-output-preserves-capture-exif");
        check(exif.getAttributeInt("Orientation", 0) == 6 && exif.getAttributeInt("ColorSpace", 0) == 1, "native-output-preserves-orientation-and-srgb-exif");
    }
    private byte[] asset(String name) throws Exception { try (InputStream input = getContext().getAssets().open(name)) { return input.readAllBytes(); } }
    private void saveBitmap(Bitmap image, String name) throws Exception { try (FileOutputStream stream = new FileOutputStream(new File(output,name))) { check(image.compress(Bitmap.CompressFormat.JPEG,95,stream), "native-template-evidence-encoded"); } }
    private String pixels(Bitmap bitmap) throws Exception { int[] values = new int[bitmap.getWidth()*bitmap.getHeight()]; bitmap.getPixels(values,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight()); return pixelValues(values); }
    private void capture(String name) throws Exception { waitForIdleSync(); Thread.sleep(200); Bitmap image = getUiAutomation().takeScreenshot(); check(image != null, "native-screenshot-"+name); try (FileOutputStream stream = new FileOutputStream(new File(output,name+".png"))) { image.compress(Bitmap.CompressFormat.PNG,100,stream); } finally { image.recycle(); } }
    private String sha(File file) throws Exception { try (InputStream input = Files.newInputStream(file.toPath())) { return sha(input.readAllBytes()); } }
    private String sha(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hex(byte[] bytes) { StringBuilder result = new StringBuilder(); for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT,"%02x",value&255)); return result.toString(); }
    private void until(Check condition, String name) throws Exception { for (int i=0;i<300;i++) { boolean[] ready={false}; ui(() -> ready[0]=condition.test()); if(ready[0]) { check(true,name); return; } Thread.sleep(100); } check(false,name+"-timeout"); }
    private void check(boolean value, String name) { stage=name; if(!value) throw new IllegalStateException(name); assertions.put(name); checks++; android.util.Log.i("Np3NativeQA",name); }
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

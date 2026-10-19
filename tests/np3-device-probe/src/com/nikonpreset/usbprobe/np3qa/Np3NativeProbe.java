package com.nikonpreset.usbprobe.np3qa;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Emulator-only: real Android template, JPEG, batch, MediaStore and NP3 selection boundaries. */
public final class Np3NativeProbe extends Instrumentation {
    private static final String TARGET = "com.nikonpreset.usbprobe";
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

    @Override public void onCreate(Bundle value) { super.onCreate(value); args = value == null ? new Bundle() : value; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE), "dedicated-emulator-hardware");
            Context context = getTargetContext(); loader = context.getClassLoader();
            check(TARGET.equals(context.getPackageName()), "target-package");
            var version = context.getPackageManager().getPackageInfo(TARGET, 0);
            check(version.getLongVersionCode() == Integer.parseInt(args.getString("version", "57")), "expected-product-apk-version");
            String theme = args.getString("theme", "light"); check(List.of("light", "dark").contains(theme), "requested-theme");
            check(context.getSharedPreferences("app-appearance", Context.MODE_PRIVATE).edit().putString("mode", theme).commit(), "emulator-theme-preference");
            output = new File(context.getExternalFilesDir(null), "np3-native/" + theme); check(output.mkdirs() || output.isDirectory(), "evidence-directory");
            Files.deleteIfExists(new File(output, "failure.png").toPath());
            report.put("versionName", version.versionName).put("versionCode", version.getLongVersionCode()).put("apkSha256", sha(new File(context.getApplicationInfo().sourceDir)))
                    .put("theme", theme).put("hardware", Build.HARDWARE).put("scope", "Dedicated emulator-5582, airplane mode; owned synthetic source and local research recipes. Android JPEG/EXIF/template/batch/MediaStore/UI; no camera or remote fetch.");
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(TARGET, TARGET + ".MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            until(() -> (Boolean)get(activity, "ready") && get(activity, "album") != null, "activity-ready");
            ui(() -> { library = get(activity, "library"); Object panel = get(activity, "album"); controller = get(panel, "controller"); batch = get(panel, "photoPresets"); });
            check(!(Boolean)invoke(controller, "hasLiveSession"), "no-live-camera-session");
            importAndParse();
            templateAndFullResolution();
            batchAndGallery();
            detailAndPicker();
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
    private String pixels(Bitmap bitmap) throws Exception { int[] values = new int[bitmap.getWidth()*bitmap.getHeight()]; bitmap.getPixels(values,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight()); MessageDigest digest = MessageDigest.getInstance("SHA-256"); for (int value : values) { digest.update((byte)(value>>>24)); digest.update((byte)(value>>>16)); digest.update((byte)(value>>>8)); digest.update((byte)value); } return hex(digest.digest()); }
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

package cc.huevia.thumbnailqa;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** A real native LocalAlbumPanel query/decoder probe; no display-only sample views. */
public final class ThumbnailPlaceholderProbe extends Instrumentation {
    private static final String TARGET = "cc.huevia", PLACEHOLDER = TARGET + ".ui.PhotoPlaceholder";
    private static final Class<?>[] NONE = new Class<?>[0];
    private final JSONObject report = new JSONObject();
    private final JSONArray assertions = new JSONArray(), fixtures = new JSONArray();
    private final List<Uri> owned = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private Bundle args;
    private Activity activity;
    private Object panel;
    private File output;
    private String stage = "startup";
    private int checks;

    @Override public void onCreate(Bundle supplied) { super.onCreate(supplied); args = supplied == null ? new Bundle() : supplied; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); Throwable failure = null;
        try {
            String mode = args.getString("mode", "phone");
            check(List.of("phone", "font1.3", "dark").contains(mode), "supported-mode");
            output = new File(getTargetContext().getExternalFilesDir(null), "thumbnail-placeholder/" + mode);
            check(output.mkdirs() || output.isDirectory(), "evidence-directory-ready");
            PackageInfo info = getTargetContext().getPackageManager().getPackageInfo(TARGET, 0);
            String apkSha = sha(Files.readAllBytes(new File(info.applicationInfo.sourceDir).toPath()));
            report.put("target", new JSONObject().put("package", TARGET).put("versionCode", info.getLongVersionCode())
                    .put("versionName", info.versionName).put("installedApkSha256", apkSha));
            check(apkSha.equals(args.getString("apkSha256", "")), "installed-app-is-exact-candidate-apk");
            check(info.getLongVersionCode() == Long.parseLong(args.getString("versionCode", "0")), "installed-version-matches-candidate");
            report.put("scope", "Native LocalAlbumPanel MediaStore query, real thumbnail decoding, gated pending state, failed/loaded transition, stale-view tag guard, and refresh recovery. Two generated owned rows only; no real user photo edits and no camera certification.");
            report.put("mode", mode).put("startedAtEpochMillis", System.currentTimeMillis());
            check(getTargetContext().checkSelfPermission("android.permission.READ_MEDIA_IMAGES") == 0, "dedicated-emulator-media-read-permission-granted");
            check(getTargetContext().checkSelfPermission("android.permission.POST_NOTIFICATIONS") == 0, "dedicated-emulator-notification-prompt-isolated");
            int resource = getTargetContext().getResources().getIdentifier("preset_template", "drawable", TARGET);
            check(resource != 0, "fixture-source-is-target-preset-template-resource");
            byte[] sourceBytes; try (InputStream in = getTargetContext().getResources().openRawResource(resource)) { sourceBytes = in.readAllBytes(); }
            Bitmap source = BitmapFactory.decodeResource(getTargetContext().getResources(), resource);
            check(source != null, "fixture-resource-decodes");
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            check(source.compress(Bitmap.CompressFormat.JPEG, 92, encoded), "fixture-valid-jpeg-encoded");
            byte[] good = encoded.toByteArray(), bad = "Huevia owned QA fixture: deliberately invalid JPEG bytes.\n".getBytes(StandardCharsets.UTF_8);
            report.put("fixtureSource", new JSONObject().put("resource", "cc.huevia:drawable/preset_template")
                    .put("resourceBytesSha256", sha(sourceBytes)).put("decodedWidth", source.getWidth()).put("decodedHeight", source.getHeight())
                    .put("jpegQuality", 92).put("jpegSha256", sha(good)).put("invalidBytesSha256", sha(bad)));
            source.recycle();
            String run = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String goodName = "QA_THUMB_" + run + "_GOOD.jpg", badName = "QA_THUMB_" + run + "_BAD.jpg";
            Uri goodUri = seed(goodName, good, "valid JPEG encoded from target resource");
            Uri badUri = seed(badName, bad, "declared image/jpeg with invalid decoder bytes");
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(TARGET, TARGET + ".MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            until(() -> Boolean.TRUE.equals(get(activity, "ready")) && get(activity, "localAlbum") != null, "native-main-activity-ready");
            float expectedFont = mode.equals("font1.3") ? 1.3f : 1f;
            check(Math.abs(activity.getResources().getConfiguration().fontScale - expectedFont) < .001f, "native-activity-font-scale-matches-mode");
            int night = activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            check(night == (mode.equals("dark") ? android.content.res.Configuration.UI_MODE_NIGHT_YES : android.content.res.Configuration.UI_MODE_NIGHT_NO), "native-activity-night-configuration-matches-mode");
            panel = get(activity, "localAlbum");
            ThreadPoolExecutor images = (ThreadPoolExecutor) get(panel, "images");
            CountDownLatch gated = new CountDownLatch(2);
            Runnable gate = () -> { gated.countDown(); try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } };
            images.execute(gate); images.execute(gate);
            check(gated.await(5, TimeUnit.SECONDS), "both-real-thumbnail-workers-gated-before-gallery-query");
            ui(() -> { invoke(activity, "switchRoot", new Class<?>[]{int.class}, 3); invoke(panel, "switchTab", new Class<?>[]{boolean.class}, true); });
            until(() -> !Boolean.TRUE.equals(get(panel, "loadingPhone")) && item(goodName) != null && item(badName) != null, "actual-MediaStore-query-discovers-both-owned-rows");
            String goodKey = (String) invoke(item(goodName), "thumbKey"), badKey = (String) invoke(item(badName), "thumbKey");
            ui(() -> ((ListView) get(panel, "list")).setSelection(0));
            until(() -> image(goodKey) != null && image(badKey) != null && contains("pendingThumbs", goodKey) && contains("pendingThumbs", badKey), "both-visible-native-tiles-pending-real-decode");
            ui(() -> {
                verifyPlaceholder(image(goodKey), false, "loading-good"); verifyPlaceholder(image(badKey), false, "loading-bad");
                report.put("loading", new JSONObject().put("good", describe(image(goodKey))).put("bad", describe(image(badKey)))
                        .put("pendingWorkerQueueSize", images.getQueue().size()));
            });
            capture("01-loading");
            ImageView staleGood = image(goodKey); Drawable staleDrawable = staleGood.getDrawable();
            ui(() -> staleGood.setTag("qa-stale-view-" + run));
            release.countDown();
            until(() -> contains("failedThumbs", badKey) && cached(goodKey) != null && !contains("pendingThumbs", goodKey) && !contains("pendingThumbs", badKey), "real-decoder-caches-good-and-fails-invalid-jpeg");
            ui(() -> {
                check(staleGood.getDrawable() == staleDrawable && !(staleGood.getDrawable() instanceof BitmapDrawable), "real-success-callback-preserves-rebound-stale-view");
                report.put("staleTagGuard", new JSONObject().put("oldViewTag", String.valueOf(staleGood.getTag()))
                        .put("oldDrawableUnchanged", staleGood.getDrawable() == staleDrawable).put("actualDecodedBitmapCached", cached(goodKey) != null));
                ((BaseAdapter) get(panel, "adapter")).notifyDataSetChanged();
            });
            until(() -> image(goodKey) != null && image(goodKey).getDrawable() instanceof BitmapDrawable && image(badKey) != null, "current-native-tile-rebinds-cached-real-bitmap");
            ui(() -> {
                verifyLoaded(image(goodKey), "resolved-good"); verifyPlaceholder(image(badKey), true, "resolved-bad");
                report.put("resolved", new JSONObject().put("good", describe(image(goodKey))).put("bad", describe(image(badKey))));
            });
            capture("02-resolved");
            write(badUri, good);
            ContentValues repaired = new ContentValues(); repaired.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            getTargetContext().getContentResolver().update(badUri, repaired, null, null);
            check(sha(uriBytes(badUri)).equals(sha(good)), "only-owned-bad-row-rewritten-with-valid-jpeg");
            ui(() -> invoke(panel, "refresh"));
            until(() -> !Boolean.TRUE.equals(get(panel, "loadingPhone")) && item(badName) != null, "native-refresh-requeries-repaired-row");
            String recoveredKey = (String) invoke(item(badName), "thumbKey");
            until(() -> image(recoveredKey) != null && image(recoveredKey).getDrawable() instanceof BitmapDrawable && cached(recoveredKey) != null, "native-refresh-recovers-failed-tile-through-real-decoder");
            ui(() -> {
                check(!contains("failedThumbs", recoveredKey), "native-refresh-clears-recovered-failure-state");
                verifyLoaded(image(recoveredKey), "recovered-bad");
                report.put("recovery", new JSONObject().put("uri", badUri.toString()).put("oldThumbnailKey", badKey)
                        .put("newThumbnailKey", recoveredKey).put("replacementJpegSha256", sha(good)).put("tile", describe(image(recoveredKey))));
            });
            capture("03-recovered");
            report.put("status", "PASS");
            result.putString("status", "PASS"); result.putString("stream", "Thumbnail placeholder native PASS\n");
        } catch (Throwable problem) {
            failure = problem;
            try { report.put("status", "FAIL").put("failedStage", stage).put("error", android.util.Log.getStackTraceString(problem)); if (activity != null) capture("failure"); } catch (Throwable ignored) { }
            result.putString("status", "FAIL"); result.putString("stream", "Thumbnail placeholder native FAIL at " + stage + ": " + problem + "\n");
        } finally {
            release.countDown();
            JSONArray cleanup = new JSONArray();
            for (Uri uri : owned) {
                try {
                    int removed = getTargetContext().getContentResolver().delete(uri, null, null);
                    boolean absent; try (Cursor cursor = getTargetContext().getContentResolver().query(uri, new String[]{MediaStore.Images.Media._ID}, null, null, null)) { absent = cursor == null || !cursor.moveToFirst(); }
                    cleanup.put(new JSONObject().put("uri", uri.toString()).put("rowsDeleted", removed).put("verifiedAbsent", absent));
                    if (!absent) throw new IllegalStateException("owned-fixture-cleanup-not-absent");
                } catch (Throwable problem) { failure = problem; try { cleanup.put(new JSONObject().put("uri", uri.toString()).put("error", problem.toString())); } catch (Throwable ignored) { } }
            }
            try {
                report.put("checks", checks).put("assertions", assertions).put("fixtures", fixtures).put("cleanup", cleanup);
                if (failure != null) { report.put("status", "FAIL"); result.putString("status", "FAIL"); result.putString("stream", "Thumbnail placeholder native FAIL: " + failure + "\n"); }
                if (output != null) Files.writeString(new File(output, "report.json").toPath(), report.toString(2));
                if (activity != null) ui(() -> activity.finish());
            } catch (Throwable problem) { result.putString("status", "FAIL"); result.putString("stream", "Thumbnail placeholder native FAIL saving report: " + problem + "\n"); }
        }
        finish("PASS".equals(result.getString("status")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private Uri seed(String name, byte[] bytes, String kind) throws Exception {
        ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HueviaThumbnailQA");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getTargetContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        check(uri != null, "owned-MediaStore-fixture-created-" + kind); owned.add(uri); write(uri, bytes);
        ContentValues published = new ContentValues(); published.put(MediaStore.Images.Media.IS_PENDING, 0);
        getTargetContext().getContentResolver().update(uri, published, null, null);
        fixtures.put(new JSONObject().put("uri", uri.toString()).put("displayName", name).put("kind", kind)
                .put("bytes", bytes.length).put("sha256", sha(bytes)).put("ownedByThisRun", true));
        check(sha(uriBytes(uri)).equals(sha(bytes)), "owned-fixture-provider-bytes-match-" + kind); return uri;
    }
    private void write(Uri uri, byte[] bytes) throws Exception { try (OutputStream stream = getTargetContext().getContentResolver().openOutputStream(uri, "wt")) { if (stream == null) throw new IllegalStateException("owned-provider-write-unavailable"); stream.write(bytes); } }
    private byte[] uriBytes(Uri uri) throws Exception { try (InputStream in = getTargetContext().getContentResolver().openInputStream(uri)) { if (in == null) throw new IllegalStateException("owned-provider-read-unavailable"); return in.readAllBytes(); } }
    private Object item(String name) throws Exception { for (Object candidate : (List<?>) get(panel, "phonePhotos")) if (name.equals(invoke(candidate, "name"))) return candidate; return null; }
    private boolean contains(String name, String key) throws Exception { return ((Set<?>) get(panel, name)).contains(key); }
    @SuppressWarnings("unchecked") private Bitmap cached(String key) throws Exception { return ((LruCache<String, Bitmap>) get(panel, "thumbnails")).get(key); }
    private ImageView image(String key) throws Exception { return find((View) get(panel, "root"), key); }
    private static ImageView find(View view, String key) { if (view instanceof ImageView image && key.equals(view.getTag()) && view.isShown()) return image; if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) { ImageView found = find(group.getChildAt(index), key); if (found != null) return found; } return null; }
    private void verifyPlaceholder(ImageView image, boolean failed, String phase) throws Exception {
        Drawable drawable = image.getDrawable(); check(PLACEHOLDER.equals(drawable.getClass().getName()), "native-PhotoPlaceholder-" + phase);
        check(Boolean.valueOf(failed).equals(get(drawable, "failed")), "native-placeholder-state-" + phase);
        check(drawable.getIntrinsicWidth() == -1 && drawable.getIntrinsicHeight() == -1, "placeholder-has-no-intrinsic-scaling-size-" + phase);
        verifyVisible(image, phase);
    }
    private void verifyLoaded(ImageView image, String phase) throws Exception {
        check(image.getDrawable() instanceof BitmapDrawable, "native-real-BitmapDrawable-" + phase);
        check(image.getScaleType() == ImageView.ScaleType.CENTER_CROP, "real-photo-remains-CENTER_CROP-" + phase); verifyVisible(image, phase);
    }
    private void verifyVisible(ImageView image, String phase) {
        Rect visible = new Rect(); check(image.isShown() && image.getWidth() > 0 && image.getHeight() > 0 && image.getGlobalVisibleRect(visible)
                && visible.width() >= image.getWidth() - 1 && visible.height() >= image.getHeight() - 1, "native-tile-fully-visible-" + phase);
    }
    private JSONObject describe(ImageView image) throws Exception {
        Rect visible = new Rect(); image.getGlobalVisibleRect(visible); Drawable d = image.getDrawable();
        JSONObject result = new JSONObject().put("tag", String.valueOf(image.getTag())).put("drawable", d.getClass().getName())
                .put("scaleType", image.getScaleType().toString()).put("width", image.getWidth()).put("height", image.getHeight())
                .put("visibleBounds", visible.toShortString()).put("intrinsicWidth", d.getIntrinsicWidth()).put("intrinsicHeight", d.getIntrinsicHeight());
        if (PLACEHOLDER.equals(d.getClass().getName())) result.put("failed", get(d, "failed"));
        if (d instanceof BitmapDrawable bitmap) result.put("bitmapWidth", bitmap.getBitmap().getWidth()).put("bitmapHeight", bitmap.getBitmap().getHeight()); return result;
    }
    private void capture(String name) throws Exception {
        waitForIdleSync(); Thread.sleep(180); Bitmap image = getUiAutomation().takeScreenshot(); check(image != null, "raw-native-screenshot-" + name);
        try {
            byte[] png; try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) { check(image.compress(Bitmap.CompressFormat.PNG, 100, bytes), "native-png-encoding-" + name); png = bytes.toByteArray(); }
            try (FileOutputStream stream = new FileOutputStream(new File(output, name + ".png"))) { stream.write(png); }
            JSONObject screenshots = report.optJSONObject("screenshots"); if (screenshots == null) { screenshots = new JSONObject(); report.put("screenshots", screenshots); }
            screenshots.put(name, new JSONObject().put("source", "UiAutomation.takeScreenshot; unmodified native screenshot")
                    .put("width", image.getWidth()).put("height", image.getHeight()).put("sha256", sha(png))
                    .put("density", activity.getResources().getDisplayMetrics().density).put("fontScale", activity.getResources().getConfiguration().fontScale)
                    .put("uiMode", activity.getResources().getConfiguration().uiMode));
        } finally { image.recycle(); }
    }
    private void until(Check condition, String name) throws Exception { for (int attempt = 0; attempt < 200; attempt++) { boolean[] ready = {false}; ui(() -> ready[0] = condition.test()); if (ready[0]) { check(true, name); return; } Thread.sleep(100); } check(false, name + "-timeout"); }
    private void check(boolean condition, String name) { stage = name; if (!condition) throw new IllegalStateException(name); checks++; assertions.put(name); android.util.Log.i("ThumbnailNativeQA", name); }
    private static Object get(Object value, String name) throws Exception { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); }
    private static Object invoke(Object value, String name) throws Exception { return invoke(value, name, NONE); }
    private static Object invoke(Object value, String name, Class<?>[] types, Object... args) throws Exception { Method method = value.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(value, args); }
    private static String sha(byte[] bytes) throws Exception { StringBuilder result = new StringBuilder(); for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return result.toString(); }
    private void ui(Task task) throws Exception { Throwable[] problem = {null}; runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { problem[0] = error; } }); if (problem[0] != null) { if (problem[0] instanceof Exception) throw (Exception) problem[0]; throw new RuntimeException(problem[0]); } }
    private interface Task { void run() throws Exception; }
    private interface Check { boolean test() throws Exception; }
}

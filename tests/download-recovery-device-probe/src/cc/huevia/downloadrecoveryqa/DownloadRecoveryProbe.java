package cc.huevia.downloadrecoveryqa;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.GridView;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** UI state fixture only: never creates camera sockets, fake photo bytes, or a real download. */
public final class DownloadRecoveryProbe extends Instrumentation {
    private static final String TARGET = "cc.huevia";
    private final JSONObject report = new JSONObject();
    private Activity activity;
    private Object panel, controller;
    private File output;
    private Bundle args;
    private int checks;
    private String stage = "startup";
    private final AtomicInteger socketAttempts = new AtomicInteger();

    @Override public void onCreate(Bundle value) { super.onCreate(value); args = value == null ? new Bundle() : value; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE), "dedicated-emulator-only");
            Context context = getTargetContext();
            var info = context.getPackageManager().getPackageInfo(TARGET, 0);
            check(info.getLongVersionCode() == Integer.parseInt(args.getString("version", "73")), "expected-product-version");
            String mode = args.getString("mode", "phone");
            check(List.of("phone", "large-font", "landscape").contains(mode), "known-mode");
            check(!new File(context.getNoBackupFilesDir(), "account/session.enc").exists(), "no-account-session");
            report.put("versionCode", info.getLongVersionCode()).put("versionName", info.versionName)
                    .put("apkSha256", sha(new File(context.getApplicationInfo().sourceDir))).put("mode", mode);
            output = new File(context.getExternalFilesDir(null), "download-recovery-" + info.getLongVersionCode() + "/" + mode);
            check(output.mkdirs() || output.isDirectory(), "output-directory");
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(TARGET, TARGET + ".MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            boolean ready = false;
            for (int i = 0; i < 160; i++) {
                boolean[] value = {false}; ui(() -> value[0] = (Boolean) get(activity, "ready") && get(activity, "album") != null);
                if (value[0]) { ready = true; break; } Thread.sleep(100);
            }
            check(ready, "activity-ready");
            enforceEnvironment(mode);
            ui(() -> {
                panel = get(activity, "album"); controller = get(panel, "controller");
                check(!(Boolean) invoke(controller, "hasLiveSession"), "no-existing-camera-session");
                check(!(Boolean) invoke(get(get(activity, "connections"), "wifiSession"), "isActive"), "no-existing-wifi-route");
                set(activity, "albumTab", true); set(activity, "settingsTab", false); set(activity, "wirelessGuide", false);
                set(panel, "liveTab", false); set(panel, "downloadedTab", false);
                Object screen = get(panel, "screen");
                for (Object choice : screen.getClass().getEnumConstants()) if ("OVERVIEW".equals(choice.toString())) set(panel, "screen", choice);
            });
            Object first = photo(42, "QA_FIRST.JPG"), second = photo(43, "QA_SECOND.JPG");
            ui(() -> {
                set(controller, "downloadRecovery", plan(first, second));
                set(panel, "connection", connection());
                set(panel, "downloading", true); set(panel, "cancelRemaining", false);
                set(panel, "batchTotal", 2); set(panel, "batchCompleted", 0); set(panel, "batchFailed", 0);
                set(panel, "batchCancelled", 0); set(panel, "batchUnconfirmed", 0); set(panel, "publishingDownload", false);
                ((ArrayDeque<Object>) get(panel, "downloadQueue")).clear();
                ((ArrayDeque<Object>) get(panel, "downloadQueue")).add(second);
                check((Boolean) invoke(panel, "isTransferBusy"), "fixture-original-is-busy-before-loss");
                invoke(controller, "disconnectForConnectionLoss");
            });
            waitForIdleSync();
            ui(() -> {
                invoke(panel, "controllerStateChanged");
                check(!(Boolean) invoke(panel, "isTransferBusy") && !(Boolean) get(panel, "downloading"), "loss-unlocks-ui-without-an-old-download-callback");
                check(((ArrayDeque<?>) get(panel, "downloadQueue")).isEmpty(), "old-session-ui-queue-is-retired");
                check((Integer) invoke(controller, "pendingDownloadRecoveryCount") == 2, "loss-retains-both-inflight-and-queued-identities");
                check((Integer) get(panel, "batchUnconfirmed") == 1, "unconfirmed-inflight-original-is-not-reported-complete");
                invoke(activity, "render");
            });
            waitForIdleSync();
            ui(() -> {
                View resume = findText(activity.getWindow().getDecorView(), "连接后继续下载");
                check(resume != null && resume.isShown(), "explicit-reconnect-recovery-entry-visible");
                check(resume.getHeight() >= Math.round(48 * activity.getResources().getDisplayMetrics().density), "recovery-action-has-native-touch-height");
                check(findText(activity.getWindow().getDecorView(), "取消未完成下载") != null, "explicit-cancel-recovery-entry-visible");
                check(!(Boolean) invoke(controller, "hasLiveSession"), "rendering-recovery-never-connects-a-camera");
            });
            revealAction("连接后继续下载", "empty-recovery");
            capture("paused-download-recovery");
            tapAction("取消未完成下载", "empty-cancel");
            ui(() -> {
                check((Integer) invoke(controller, "pendingDownloadRecoveryCount") == 0, "explicit-cancel-discards-memory-plan");
                check(!(Boolean) invoke(panel, "isTransferBusy"), "cancelled-recovery-remains-idle");
            });
            connectedRecoveryChecks(first, second);
            ui(() -> {
                set(controller, "downloadRecovery", plan(first, second)); invoke(controller, "disconnect");
                check((Integer) invoke(controller, "pendingDownloadRecoveryCount") == 0, "explicit-disconnect-discards-memory-plan");
                set(controller, "downloadRecovery", plan(first, second));
                set(panel, "downloading", true); set(panel, "cancelRemaining", false); set(panel, "recoveringDownloads", true);
                invoke(panel, "clearPendingDownloadRecovery");
                check((Integer) invoke(controller, "pendingDownloadRecoveryCount") == 0 && (Boolean) get(panel, "cancelRemaining"), "owner-change-clears-plan-and-stops-remaining-queue");
                check((Boolean) get(panel, "downloading") && !(Boolean) get(panel, "recoveringDownloads"), "owner-change-does-not-pretend-current-local-commit-was-cancelled");
                invoke(panel, "finishInterruptedBatch");
                check(!(Boolean) invoke(panel, "isTransferBusy") && !(Boolean) invoke(controller, "hasLiveSession"), "cleanup-is-idle-without-camera-io");
            });
            fujiGuideChecks();
            check(socketAttempts.get() == 0, "all-native-fixtures-open-zero-camera-sockets");
            report.put("status", "PASS").put("checks", checks).put("scope", "Synthetic native UI state fixture only. No sockets, PTP, real camera, photo transfer, or background-retention verification. Actual metadata/rebind behavior is tested separately by the host peer suite.");
            result.putString("status", "PASS"); result.putString("stream", "Download recovery native PASS; checks=" + checks + "\n");
        } catch (Throwable error) {
            Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
            result.putString("status", "FAIL"); result.putString("stream", "Download recovery native FAIL; " + stage + "; " + cause + "\n");
            try { report.put("status", "FAIL").put("stage", stage).put("failure", cause.toString()).put("checks", checks); if (activity != null) capture("failure"); } catch (Exception ignored) { }
        } finally {
            try {
                if (activity != null) ui(() -> { if (panel != null) invoke(panel, "clearPendingDownloadRecovery"); if (controller != null) invoke(controller, "disconnect"); activity.finish(); });
                if (output != null) Files.writeString(new File(output, "report.json").toPath(), report.toString(2) + "\n");
            } catch (Exception error) { result.putString("status", "FAIL"); result.putString("stream", "Download recovery native FAIL; cleanup=" + error + "\n"); }
        }
        finish("PASS".equals(result.getString("status")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void connectedRecoveryChecks(Object first, Object second) throws Exception {
        ui(() -> {
            Object client = idleClient(); Object info = connection();
            set(controller, "active", client); set(controller, "cachedConnectionInfo", info); set(controller, "sessionConnectionInfo", info);
            // Grid descriptors deliberately belong to an obsolete session. Any
            // thumbnail task must fail at requirePhoto before camera I/O.
            set(controller, "session", "qa-current-session"); set(panel, "connection", info);
            ((List<Object>) get(panel, "photos")).clear(); ((List<Object>) get(panel, "photos")).addAll(List.of(first, second));
            set(controller, "downloadRecovery", plan(first, second));
            check((Boolean) invoke(controller, "isConnected"), "synthetic-connected-grid-fixture-ready");
            invoke(activity, "render");
        });
        waitForIdleSync(); Thread.sleep(150);
        revealAction("继续未完成下载", "connected-recovery");
        ui(() -> {
            GridView grid = (GridView) get(panel, "grid");
            check(grid != null && grid.getHeight() > 0, "pending-recovery-preserves-a-bounded-photo-grid");
            Rect visible = new Rect(); grid.getGlobalVisibleRect(visible); visible.bottom = Math.min(visible.bottom, navigationBounds().top);
            check(visible.height() >= Math.round(48 * activity.getResources().getDisplayMetrics().density), "photo-grid-retains-a-touch-height-above-navigation");
            ViewParent parent = grid.getParent(); boolean nested = false;
            while (parent instanceof View) { if (parent instanceof ScrollView) nested = true; parent = parent.getParent(); }
            check(!nested, "photo-grid-is-not-nested-in-an-unbounded-scroll-view");
        });
        capture("connected-download-recovery");
        int[] priorRequest = {0}; ui(() -> priorRequest[0] = (Integer) get(panel, "downloadRecoveryRequest"));
        tapAction("继续未完成下载", "connected-resume");
        for (int i = 0; i < 100; i++) {
            boolean[] pending = {false}; ui(() -> pending[0] = (Boolean) get(panel, "recoveringDownloads"));
            if (!pending[0]) break; Thread.sleep(20);
        }
        ui(() -> {
            check((Integer) get(panel, "downloadRecoveryRequest") > priorRequest[0], "connected-resume-center-runs-the-real-recovery-action");
            check(!(Boolean) get(panel, "recoveringDownloads") && (Integer) invoke(controller, "pendingDownloadRecoveryCount") == 2,
                    "unidentified-native-fixture-is-rejected-without-losing-pending-identities");
        });
        tapAction("取消未完成下载", "connected-cancel");
        ui(() -> {
            check((Integer) invoke(controller, "pendingDownloadRecoveryCount") == 0, "connected-recovery-is-cancelled-by-real-touch");
            invoke(controller, "disconnect"); ((List<?>) get(panel, "photos")).clear();
        });
        waitForIdleSync();
    }

    private Object idleClient() throws Exception {
        ClassLoader loader = getTargetContext().getClassLoader();
        Class<?> connector = loader.loadClass(TARGET + ".album.PtpIpClient$SocketConnector");
        Object rejecting = Proxy.newProxyInstance(loader, new Class<?>[] {connector}, (proxy, method, values) -> {
            socketAttempts.incrementAndGet(); throw new java.io.IOException("Native UI fixture cannot create a socket");
        });
        Object client = loader.loadClass(TARGET + ".album.PtpIpClient").getConstructor(connector).newInstance(rejecting);
        set(client, "closed", false); set(client, "sessionOpen", true); return client;
    }

    private void fujiGuideChecks() throws Exception {
        ui(() -> {
            selectEnum(panel, "cameraProtocol", "FUJIFILM_X_T20"); selectEnum(panel, "screen", "GUIDE");
            set(panel, "guideScrollY", 0); set(activity, "albumTab", false); set(activity, "settingsTab", true);
            invoke(activity, "render");
        });
        waitForIdleSync();
        ui(() -> {
            View brand = findText(activity.getWindow().getDecorView(), "富士  ›");
            check(brand instanceof TextView && brand.isShown(), "fuji-connection-entry-shows-brand-without-model");
            check("相机类型：富士".contentEquals(brand.getContentDescription()), "fuji-entry-accessibility-label-is-brand-only");
            check(findText(activity.getWindow().getDecorView(), "连接富士相机的 Wi-Fi，浏览并下载 JPEG 照片。") != null, "fuji-guide-introduction-is-brand-only");
            check(findText(activity.getWindow().getDecorView(), "实验适配，尚未通过 X-T20 真机验证。仅支持 JPEG 下载；照片直播、RAF 和向相机导入尼康预设暂不支持。") != null,
                    "fuji-model-specific-compatibility-limit-remains-honest");
        });
        capture("fuji-brand-guide");
    }

    private void revealAction(String text, String label) throws Exception {
        for (int attempt = 0; attempt < 9; attempt++) {
            waitForIdleSync(); Thread.sleep(100);
            boolean[] ready = {false}; int[] geometry = new int[5];
            ui(() -> {
                View target = findText(activity.getWindow().getDecorView(), text);
                check(target != null, label + "-action-exists");
                Rect visible = new Rect(), bounds = fullBounds(target), nav = navigationBounds();
                boolean shown = target.getGlobalVisibleRect(visible);
                int touch = Math.round(48 * activity.getResources().getDisplayMetrics().density);
                ready[0] = shown && visible.height() >= touch && visible.contains(bounds.centerX(), bounds.centerY())
                        && bounds.bottom <= nav.top - 2;
                if (ready[0]) { report.put(label + "Bounds", bounds.toShortString()).put(label + "Navigation", nav.toShortString()); return; }
                ScrollView scroll = null; ViewParent parent = target.getParent();
                while (parent instanceof View) { if (parent instanceof ScrollView) { scroll = (ScrollView) parent; break; } parent = parent.getParent(); }
                check(scroll != null, label + "-action-has-scrollable-recovery-parent");
                Rect viewport = new Rect(); check(scroll.getGlobalVisibleRect(viewport), label + "-scroll-viewport-visible");
                viewport.bottom = Math.min(viewport.bottom, nav.top - 4);
                check(viewport.height() >= touch, label + "-safe-scroll-viewport-has-touch-height");
                geometry[0] = viewport.centerX(); geometry[1] = viewport.top + 4; geometry[2] = viewport.bottom - 4;
                geometry[3] = bounds.centerY(); geometry[4] = viewport.centerY();
            });
            if (ready[0]) { check(true, label + "-full-touch-area-and-center-clear-navigation"); return; }
            float delta = geometry[3] - geometry[4];
            float distance = Math.min(Math.abs(delta), (geometry[2] - geometry[1]) * .7f);
            float center = (geometry[1] + geometry[2]) / 2f;
            float start = center + Math.signum(delta) * distance / 2f, end = center - Math.signum(delta) * distance / 2f;
            drag(geometry[0], start, end);
        }
        check(false, label + "-action-can-be-revealed-above-navigation");
    }

    private void tapAction(String text, String label) throws Exception {
        revealAction(text, label); int[] point = new int[2];
        ui(() -> { Rect bounds = fullBounds(findText(activity.getWindow().getDecorView(), text)); point[0] = bounds.centerX(); point[1] = bounds.centerY(); });
        long time = SystemClock.uptimeMillis(); inject(time, time, MotionEvent.ACTION_DOWN, point[0], point[1]);
        Thread.sleep(60); inject(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point[0], point[1]);
        waitForIdleSync(); Thread.sleep(100);
    }

    private void drag(float x, float start, float end) throws Exception {
        long down = SystemClock.uptimeMillis(); inject(down, down, MotionEvent.ACTION_DOWN, x, start);
        for (int i = 1; i <= 10; i++) { Thread.sleep(20); inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, start + (end - start) * i / 10f); }
        Thread.sleep(120); inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, end);
        Thread.sleep(100); inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, end);
    }
    private void inject(long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        try { check(getUiAutomation().injectInputEvent(event, true), "native-touch-injection"); } finally { event.recycle(); }
    }
    private Rect fullBounds(View view) { int[] location = new int[2]; view.getLocationOnScreen(location); return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight()); }
    private Rect navigationBounds() {
        View nav = findClass(activity.getWindow().getDecorView(), TARGET + ".ui.GlassBar");
        Rect screen = new Rect(); activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(screen);
        return nav == null ? new Rect(screen.left, screen.bottom, screen.right, screen.bottom) : fullBounds(nav);
    }
    private static View findClass(View root, String name) { if (root.getClass().getName().equals(name) && root.isShown()) return root; if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View found = findClass(((ViewGroup) root).getChildAt(i), name); if (found != null) return found; } return null; }
    private static void selectEnum(Object owner, String field, String name) throws Exception { Object current = get(owner, field); for (Object value : current.getClass().getEnumConstants()) if (value.toString().equals(name)) { set(owner, field, value); return; } throw new IllegalArgumentException(name); }

    private Object photo(long handle, String filename) throws Exception {
        ClassLoader loader = getTargetContext().getClassLoader();
        Class<?> type = loader.loadClass(TARGET + ".ptp.PtpObjectInfo"), builderType = loader.loadClass(TARGET + ".ptp.PtpObjectInfo$Builder");
        Object builder = builderType.getConstructor().newInstance();
        invoke(builder, "storageId", new Class<?>[] {long.class}, 1L); invoke(builder, "objectFormat", new Class<?>[] {int.class}, 0x3801);
        invoke(builder, "compressedSize", new Class<?>[] {long.class}, 4096L); invoke(builder, "filename", new Class<?>[] {String.class}, filename);
        Object info = invoke(builder, "build");
        Constructor<?> constructor = loader.loadClass(TARGET + ".album.PhotoItem").getDeclaredConstructor(String.class, long.class, type);
        constructor.setAccessible(true); return constructor.newInstance("qa-retired-session", handle, info);
    }
    private Object plan(Object first, Object second) throws Exception {
        Constructor<?> constructor = getTargetContext().getClassLoader().loadClass(TARGET + ".album.PhotoDownloadRecovery").getDeclaredConstructor(String.class, List.class);
        constructor.setAccessible(true); return constructor.newInstance("01".repeat(16) + "\nNikon\nSynthetic QA", List.of(first, second));
    }
    private Object connection() throws Exception {
        Constructor<?> constructor = getTargetContext().getClassLoader().loadClass(TARGET + ".album.ConnectionInfo").getDeclaredConstructor(String.class, String.class, int.class, int.class);
        constructor.setAccessible(true); return constructor.newInstance("Synthetic QA", "192.0.2.1", 15740, 2);
    }
    private void enforceEnvironment(String mode) throws Exception {
        int requested = "landscape".equals(mode) ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        ui(() -> activity.setRequestedOrientation(requested));
        boolean ready = false;
        for (int attempt = 0; attempt < 120; attempt++) {
            waitForIdleSync(); boolean[] settled = {false};
            ui(() -> settled[0] = environmentSettled(mode));
            if (settled[0]) { ready = true; break; }
            Thread.sleep(100);
        }
        check(ready, "requested-activity-configuration-and-layout-settled");
        Thread.sleep(200); waitForIdleSync();
        ui(() -> {
            check(environmentSettled(mode), "activity-configuration-stays-settled-after-layout");
            Configuration config = activity.getResources().getConfiguration();
            View decor = activity.getWindow().getDecorView();
            report.put("orientation", config.orientation).put("fontScale", config.fontScale)
                    .put("activityWidthPx", decor.getWidth()).put("activityHeightPx", decor.getHeight())
                    .put("screenWidthDp", config.screenWidthDp).put("screenHeightDp", config.screenHeightDp);
        });
    }
    private boolean environmentSettled(String mode) {
        Configuration config = activity.getResources().getConfiguration();
        View decor = activity.getWindow().getDecorView();
        boolean landscape = "landscape".equals(mode);
        int orientation = landscape ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        float font = "large-font".equals(mode) ? 1.3f : 1f;
        return config.orientation == orientation && Math.abs(config.fontScale - font) < .02f
                && decor.getWidth() > 0 && decor.getHeight() > 0 && !decor.isLayoutRequested()
                && (landscape ? decor.getWidth() > decor.getHeight() : decor.getHeight() > decor.getWidth());
    }
    private void capture(String name) throws Exception {
        waitForIdleSync(); Thread.sleep(100);
        if (!"failure".equals(name)) ui(() -> check(environmentSettled(args.getString("mode", "phone")), "actual-activity-configuration-before-" + name));
        Bitmap bitmap = getUiAutomation().takeScreenshot(); check(bitmap != null, "screenshot-" + name);
        if (!"failure".equals(name)) {
            boolean landscape = "landscape".equals(args.getString("mode", "phone"));
            check(landscape ? bitmap.getWidth() > bitmap.getHeight() : bitmap.getHeight() > bitmap.getWidth(), "actual-screenshot-orientation-" + name);
        }
        report.put("screenshotWidthPx", bitmap.getWidth()).put("screenshotHeightPx", bitmap.getHeight());
        try (FileOutputStream out = new FileOutputStream(new File(output, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); }
    }
    private static View findText(View root, String value) {
        if (root.getVisibility() == View.VISIBLE && root instanceof TextView && value.equals(((TextView) root).getText().toString())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View found = findText(((ViewGroup) root).getChildAt(i), value); if (found != null) return found; } return null;
    }
    private void ui(Checked work) { runOnMainSync(() -> { try { work.run(); } catch (Exception error) { throw new RuntimeException(error); } }); }
    private interface Checked { void run() throws Exception; }
    private void check(boolean value, String label) { stage = label; if (!value) throw new IllegalStateException(label); checks++; }
    private static Object get(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static void set(Object owner, String name, Object value) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
    private static Object invoke(Object owner, String name) throws Exception { return invoke(owner, name, new Class<?>[0]); }
    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception { Method method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(owner, args); }
    private static String sha(File file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (var input = Files.newInputStream(file.toPath())) { byte[] buffer = new byte[65536]; int size; while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size); } StringBuilder result = new StringBuilder(); for (byte value : digest.digest()) result.append(String.format("%02x", value & 255)); return result.toString(); }
}

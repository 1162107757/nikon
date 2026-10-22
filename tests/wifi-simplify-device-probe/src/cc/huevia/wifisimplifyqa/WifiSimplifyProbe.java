package cc.huevia.wifisimplifyqa;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Emulator-only UI/route-adoption QA. Continuations are spies; no PTP is opened. */
public final class WifiSimplifyProbe extends Instrumentation {
    private static final String TARGET = "cc.huevia";
    private static final String NEARBY = "android.permission.NEARBY_WIFI_DEVICES";
    private static final int WIFI_SETTINGS_RESULT = 109;
    private final JSONObject report = new JSONObject();
    private Activity activity;
    private Object session, controller;
    private Bundle args;
    private File output;
    private String stage = "arguments", priorAppearance;
    private boolean appearanceCaptured;
    private int checks;
    private Map<String, ?> wifiPreferences;

    @Override public void onCreate(Bundle value) { super.onCreate(value); args = value == null ? new Bundle() : value; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE), "dedicated-emulator-only");
            check(Build.VERSION.SDK_INT >= 33, "android-33-or-newer-test-environment");
            String scope = args.getString("scope", "disconnected");
            check(List.of("disconnected", "connected").contains(scope), "known-scope");
            String theme = args.getString("theme", "light");
            check(List.of("light", "dark").contains(theme), "known-theme");
            Context context = getTargetContext();
            var info = context.getPackageManager().getPackageInfo(TARGET, 0);
            check(info.getLongVersionCode() == Integer.parseInt(args.getString("version", "72")), "expected-product-version-installed");
            report.put("versionCode", info.getLongVersionCode()).put("versionName", info.versionName)
                    .put("installedApkSha256", sha(new File(context.getApplicationInfo().sourceDir)))
                    .put("scope", scope).put("theme", theme).put("hardware", Build.HARDWARE);
            check(!new File(new File(context.getNoBackupFilesDir(), "account"), "session.enc").exists(), "no-account-session");
            var appearance = context.getSharedPreferences("app-appearance", Context.MODE_PRIVATE);
            priorAppearance = appearance.contains("mode") ? appearance.getString("mode", "system") : null;
            appearanceCaptured = true;
            appearance.edit().putString("mode", theme).commit();
            wifiPreferences = context.getSharedPreferences("camera-wifi-ui", Context.MODE_PRIVATE).getAll();
            output = new File(context.getExternalFilesDir(null), "wifi-simplify-" + info.getLongVersionCode() + "/" + scope + "-" + theme);
            check(output.mkdirs() || output.isDirectory(), "output-directory");
            Files.deleteIfExists(new File(output, "failure.png").toPath());
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(TARGET, TARGET + ".MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            boolean ready = false;
            for (int i = 0; i < 160; i++) {
                boolean[] observed = {false};
                ui(() -> observed[0] = (Boolean) get(activity, "ready") && get(activity, "album") != null);
                if (observed[0]) { ready = true; break; }
                Thread.sleep(100);
            }
            check(ready, "main-activity-ready");
            ui(() -> {
                Object runtime = get(activity, "connections");
                session = get(runtime, "wifiSession");
                controller = get(get(activity, "album"), "controller");
                noPtp();
                check(!(Boolean) invoke(session, "isActive"), "no-existing-wifi-session");
            });
            if ("disconnected".equals(scope)) disconnectedChecks(); else connectedChecks();
            check(wifiPreferences.equals(context.getSharedPreferences("camera-wifi-ui", Context.MODE_PRIVATE).getAll()), "synthetic-credentials-never-persisted");
            ui(this::noPtp);
            report.put("status", "PASS").put("checks", checks)
                    .put("limits", "Emulator UI and existing Network adoption only; spy continuations never start a camera handshake. Does not validate a real camera or background retention.");
            result.putString("status", "PASS");
            result.putString("stream", "Wi-Fi simplification native PASS; scope=" + scope + "; checks=" + checks + "\n");
        } catch (Throwable failure) {
            Throwable cause = failure; while (cause.getCause() != null) cause = cause.getCause();
            result.putString("status", "FAIL");
            result.putString("stream", "Wi-Fi simplification native FAIL; stage=" + stage + "; " + cause + "\n");
            try { report.put("status", "FAIL").put("stage", stage).put("failure", cause.toString()).put("checks", checks); if (activity != null) capture("failure"); } catch (Exception ignored) { }
        } finally {
            try {
                if (activity != null) ui(() -> {
                    invoke(activity, "cancelPendingCameraWifi");
                    if (session != null) invoke(session, "disconnect");
                    activity.finish();
                });
                if (appearanceCaptured) {
                    var editor = getTargetContext().getSharedPreferences("app-appearance", Context.MODE_PRIVATE).edit();
                    if (priorAppearance == null) editor.remove("mode"); else editor.putString("mode", priorAppearance);
                    editor.commit();
                }
                if (output != null) Files.writeString(new File(output, "report.json").toPath(), report.toString(2) + "\n");
            } catch (Exception cleanup) {
                result.putString("status", "FAIL");
                result.putString("stream", "Wi-Fi simplification native FAIL; cleanup=" + cleanup + "\n");
            }
        }
        finish("PASS".equals(result.getString("status")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void disconnectedChecks() throws Exception {
        check(getTargetContext().checkSelfPermission(NEARBY) == android.content.pm.PackageManager.PERMISSION_GRANTED, "manual-fallback-permission-pregranted");
        check(candidate() == null, "no-current-wifi-before-disconnected-scope");
        AtomicInteger connected = new AtomicInteger(), failed = new AtomicInteger();
        AlertDialog[] first = new AlertDialog[1];
        ui(() -> {
            open(connected, failed);
            first[0] = chooser();
            check(get(activity, "pendingWifiConnect") == null, "system-selection-needs-no-permission-request");
            check(findText(first[0].getWindow().getDecorView(), "我已连接，继续") != null, "continue-existing-wifi-action-visible");
            clickText(first[0].getWindow().getDecorView(), "我已连接，继续");
            check(first[0].isShowing() && edits(first[0]).isEmpty(), "no-network-retry-keeps-credential-free-chooser");
            check(connected.get() == 0 && failed.get() == 0, "no-network-retry-does-not-settle-request");
            noRoute();
        });
        capture("system-wifi-choice");
        ui(() -> first[0].getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        waitForIdleSync();
        ui(() -> {
            check(failed.get() == 1 && connected.get() == 0, "chooser-cancel-delivers-failure-once");
            check(get(activity, "cameraWifiDialog") == null, "chooser-cancel-clears-dialog-reference");
            first[0].cancel();
        });
        waitForIdleSync();
        ui(() -> {
            check(failed.get() == 1, "duplicate-cancel-does-not-settle-again");
            noRoute();
        });
        manualChecks();
        activeCanceledSettingsResultChecks();
        staleSettingsChecks();
    }

    private void activeCanceledSettingsResultChecks() throws Exception {
        AtomicInteger connected = new AtomicInteger(), failed = new AtomicInteger();
        AlertDialog[] choice = new AlertDialog[1];
        ui(() -> {
            open(connected, failed);
            choice[0] = chooser();
            int ticket = (Integer) get(activity, "wifiUiEpoch");
            TextView status = new TextView(activity);
            // Recreate the pending action's exact production continuation without
            // launching a second system panel or selecting a real Wi-Fi network.
            Runnable pending = () -> {
                try {
                    invoke(activity, "waitForSystemCameraWifi", new Class<?>[]{int.class, Runnable.class, Consumer.class, TextView.class},
                            ticket, (Runnable) connected::incrementAndGet, (Consumer<String>) ignored -> failed.incrementAndGet(), status);
                } catch (Exception error) { throw new RuntimeException(error); }
            };
            set(activity, "pendingSystemWifiReturn", pending);
            invoke(activity, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class}, WIFI_SETTINGS_RESULT, Activity.RESULT_CANCELED, null);
            check(get(activity, "pendingSystemWifiReturn") == null, "active-canceled-null-result-consumes-return-action");
            check(get(activity, "wifiSettingsPoll") != null, "active-canceled-null-result-starts-bounded-wifi-wait");
            check(edits(choice[0]).isEmpty() && get(activity, "wifiRequestLease") == null, "active-settings-wait-does-not-open-credentials-or-service-lease");
        });
        waitForIdleSync();
        ui(() -> {
            check(connected.get() == 0 && failed.get() == 0, "active-result-without-network-keeps-request-pending");
            noRoute();
            choice[0].getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        });
        // Native dialog button callbacks are also delivered by the main Handler.
        waitForIdleSync();
        ui(() -> {
            check(get(activity, "wifiSettingsPoll") == null && get(activity, "pendingSystemWifiReturn") == null, "cancel-removes-active-settings-poll");
        });
        Thread.sleep(550); waitForIdleSync();
        ui(() -> {
            check(connected.get() == 0 && failed.get() == 1, "retired-settings-poll-cannot-continue-or-repeat-failure");
            check(get(activity, "wifiSettingsPoll") == null && get(activity, "cameraWifiDialog") == null, "no-delayed-poll-or-dialog-after-cancel");
            noRoute();
        });
    }

    private void manualChecks() throws Exception {
        AtomicInteger connected = new AtomicInteger(), failed = new AtomicInteger();
        AlertDialog[] manual = new AlertDialog[1];
        EditText[] password = new EditText[1];
        ui(() -> {
            open(connected, failed);
            AlertDialog choice = chooser();
            clickText(choice.getWindow().getDecorView(), "手动填写");
            manual[0] = dialog();
            check(manual[0] != choice && manual[0].isShowing(), "manual-fallback-has-its-own-dialog");
        });
        // AlertDialog dispatches OnShow through its Handler. Allow that message
        // to install the validation click listener before submitting input.
        waitForIdleSync();
        ui(() -> {
            List<EditText> inputs = edits(manual[0]);
            check(inputs.size() == 2, "manual-fallback-has-ssid-and-password-fields");
            EditText network = inputs.get(0); password[0] = inputs.get(1);
            check(!network.isSaveEnabled() && !password[0].isSaveEnabled(), "manual-fields-disable-state-saving");
            check(network.getImportantForAutofill() == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    && password[0].getImportantForAutofill() == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, "manual-fields-disable-autofill");
            check((password[0].getInputType() & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    && password[0].getTransformationMethod() instanceof PasswordTransformationMethod, "manual-password-is-masked");
            network.setText("Huevia_Wifi_QA"); password[0].setText("Synthetic8!");
            check(!password[0].getTransformationMethod().getTransformation(password[0].getText(), password[0]).toString().contains("Synthetic8!"), "synthetic-password-hidden");
            network.setText("");
            manual[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            check(manual[0].isShowing() && contains(manual[0].getWindow().getDecorView(), "最多 32 字节"), "manual-empty-ssid-validation");
            network.setText("Huevia_Wifi_QA"); password[0].setText("123");
            manual[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            check(manual[0].isShowing() && contains(manual[0].getWindow().getDecorView(), "8–63"), "manual-short-password-validation");
            CheckBox openNetwork = findCheck(manual[0].getWindow().getDecorView());
            check(openNetwork != null, "manual-open-network-option");
            openNetwork.setChecked(true);
            check(!password[0].isEnabled() && password[0].length() == 0, "open-network-clears-password");
            openNetwork.setChecked(false); password[0].setText("Synthetic8!");
            noRoute();
            check(connected.get() == 0 && failed.get() == 0, "invalid-manual-submissions-never-open-route");
        });
        capture("manual-fallback-validation");
        ui(() -> manual[0].getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        waitForIdleSync();
        ui(() -> {
            check(failed.get() == 1 && connected.get() == 0, "manual-cancel-settles-once");
            check(password[0].length() == 0, "manual-dismiss-clears-password");
            noRoute();
        });
    }

    private void staleSettingsChecks() throws Exception {
        AtomicInteger connected = new AtomicInteger(), failed = new AtomicInteger();
        Runnable[] stale = new Runnable[1];
        AlertDialog[] choice = new AlertDialog[1];
        ui(() -> {
            open(connected, failed);
            choice[0] = chooser();
        });
        waitForIdleSync();
        ui(() -> {
            choice[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Object pending = get(activity, "pendingSystemWifiReturn");
            check(pending instanceof Runnable, "system-picker-installs-return-action");
            stale[0] = (Runnable) pending;
            invoke(activity, "cancelPendingCameraWifi");
            check(get(activity, "pendingSystemWifiReturn") == null, "cancel-clears-system-picker-return-action");
            stale[0].run();
            invoke(activity, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class}, WIFI_SETTINGS_RESULT, Activity.RESULT_CANCELED, null);
        });
        // Close only the system panel opened by this scope, then replay a late result.
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true);
        getUiAutomation().injectInputEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true);
        waitForIdleSync(); Thread.sleep(250);
        ui(() -> {
            invoke(activity, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class}, WIFI_SETTINGS_RESULT, Activity.RESULT_CANCELED, null);
            check(connected.get() == 0 && failed.get() == 0, "stale-system-result-cannot-settle-retired-request");
            check(get(activity, "cameraWifiDialog") == null && get(activity, "pendingWifiConnect") == null, "stale-result-cannot-reopen-ui-or-permission-flow");
            noRoute();
        });
    }

    private void connectedChecks() throws Exception {
        check(getTargetContext().checkSelfPermission(NEARBY) != android.content.pm.PackageManager.PERMISSION_GRANTED, "nearby-permission-denied-for-adoption");
        check(getTargetContext().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED, "location-permission-denied-for-adoption");
        Network selected = candidate(); check(selected != null, "current-wifi-present-before-adoption");
        AtomicInteger connected = new AtomicInteger(), failed = new AtomicInteger();
        ui(() -> {
            open(connected, failed);
            AlertDialog waiting = (AlertDialog) get(activity, "cameraWifiDialog");
            if (waiting != null) check(edits(waiting).isEmpty(), "adoption-never-shows-credential-fields");
            check(get(activity, "pendingWifiConnect") == null, "adoption-does-not-ask-nearby-or-location-permission");
        });
        for (int i = 0; i < 150 && connected.get() == 0 && failed.get() == 0; i++) { waitForIdleSync(); Thread.sleep(80); }
        ui(() -> {
            check(connected.get() == 1 && failed.get() == 0, "existing-wifi-continues-with-spy-exactly-once");
            check((Boolean) invoke(session, "isConnected"), "adopted-session-is-connected");
            check(selected.equals(get(session, "network")), "adoption-observes-exact-existing-network");
            check(!(Boolean) invoke(session, "usesSpecificRoute"), "adoption-is-not-a-new-wifi-specifier-request");
            check(get(activity, "cameraWifiDialog") == null && get(activity, "wifiRequestLease") == null, "adoption-cleans-waiting-ui-and-operation-lease");
            noPtp();
            invoke(activity, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class}, WIFI_SETTINGS_RESULT, Activity.RESULT_CANCELED, null);
            check(connected.get() == 1 && failed.get() == 0, "unrelated-settings-result-cannot-repeat-continuation");
        });
        capture("existing-wifi-adopted");
        ui(() -> {
            invoke(activity, "cancelPendingCameraWifi");
            check((Boolean) invoke(session, "isConnected"), "ui-cleanup-preserves-healthy-runtime-owned-route");
            noPtp();
            invoke(session, "disconnect");
            noRoute();
        });
    }

    private void open(AtomicInteger connected, AtomicInteger failed) throws Exception {
        invoke(activity, "ensureCameraWifi", new Class<?>[]{Runnable.class, Consumer.class}, (Runnable) connected::incrementAndGet, (Consumer<String>) ignored -> failed.incrementAndGet());
    }
    private AlertDialog chooser() throws Exception {
        AlertDialog choice = dialog();
        check(choice != null && choice.isShowing(), "system-wifi-chooser-visible");
        check(edits(choice).isEmpty(), "system-wifi-chooser-has-no-credential-fields");
        check(choice.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString().equals("选择相机 Wi-Fi"), "system-wifi-picker-is-primary-action");
        check(findText(choice.getWindow().getDecorView(), "手动填写") != null, "manual-credentials-is-secondary-action");
        return choice;
    }
    private AlertDialog dialog() throws Exception { return (AlertDialog) get(activity, "cameraWifiDialog"); }
    private Network candidate() throws Exception {
        Class<?> type = getTargetContext().getClassLoader().loadClass(TARGET + ".album.CameraWifiNetwork");
        return (Network) invoke(type.getConstructor(Context.class).newInstance(getTargetContext()), "connectedWifiNetwork");
    }
    private void noPtp() throws Exception { check(!(Boolean) invoke(controller, "hasLiveSession"), "spy-continuation-never-opens-ptp"); }
    private void noRoute() throws Exception {
        check(!(Boolean) invoke(session, "isActive") && get(session, "callback") == null, "no-wifi-route-or-observer-created");
        noPtp();
    }
    private void capture(String name) throws Exception {
        waitForIdleSync(); Thread.sleep(80);
        Bitmap bitmap = getUiAutomation().takeScreenshot(); check(bitmap != null, "screenshot-" + name);
        try (FileOutputStream out = new FileOutputStream(new File(output, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        finally { bitmap.recycle(); }
    }
    private static List<EditText> edits(AlertDialog dialog) { List<EditText> result = new ArrayList<>(); findEdits(dialog.getWindow().getDecorView(), result); return result; }
    private static void findEdits(View root, List<EditText> result) { if (root instanceof EditText) result.add((EditText) root); if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) findEdits(((ViewGroup) root).getChildAt(i), result); }
    private static CheckBox findCheck(View root) { if (root instanceof CheckBox) return (CheckBox) root; if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { CheckBox result = findCheck(((ViewGroup) root).getChildAt(i)); if (result != null) return result; } return null; }
    private static View findText(View root, String text) { if (root.getVisibility() == View.VISIBLE && root instanceof TextView && ((TextView) root).getText().toString().equals(text)) return root; if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View found = findText(((ViewGroup) root).getChildAt(i), text); if (found != null) return found; } return null; }
    private void clickText(View root, String text) { View found = findText(root, text); check(found != null && found.performClick(), "click-" + text); }
    private static boolean contains(View root, String value) { if (root instanceof TextView && root.getVisibility() == View.VISIBLE && ((TextView) root).getText().toString().contains(value)) return true; if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) if (contains(((ViewGroup) root).getChildAt(i), value)) return true; return false; }
    private static Object get(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    private static Object invoke(Object object, String name) throws Exception { return invoke(object, name, new Class<?>[0]); }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... values) throws Exception { Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(object, values); }
    private static String sha(File file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (var in = Files.newInputStream(file.toPath())) { byte[] buffer = new byte[65536]; int count; while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count); } StringBuilder text = new StringBuilder(64); for (byte value : digest.digest()) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return text.toString(); }
    private void check(boolean condition, String label) { stage = label; if (!condition) throw new IllegalStateException(label); checks++; }
    private interface Task { void run() throws Exception; }
    private void ui(Task task) throws Exception { Throwable[] failure = {null}; runOnMainSync(() -> { try { task.run(); } catch (Throwable error) { failure[0] = error; } }); if (failure[0] != null) { if (failure[0] instanceof Exception) throw (Exception) failure[0]; throw new RuntimeException(failure[0]); } }
}

package com.nikonpreset.usbprobe.qqqa;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;

/** Emulator-only real-view checks. Synthetic flags never start QQ, submit a token, or create a session. */
public final class QqAccountProbe extends Instrumentation {
    private static final String TARGET = "com.nikonpreset.usbprobe";
    private Bundle arguments;
    private Activity activity;
    private Object panel, repository, controller;
    private Object originalScreen, originalSession;
    private boolean originalSettings, originalAlbum, originalWorking, originalRestoring, captured;
    private boolean originalAuthorizing, originalExchanging, originalRefresh, originalMessageError;
    private boolean originalPasswordLogin;
    private boolean syntheticProfile;
    private String originalMessage, originalUsername;
    private long originalEpoch, originalQqEpoch;
    private int checks;
    private String stage = "arguments";

    @Override public void onCreate(Bundle args) {
        super.onCreate(args); arguments = args == null ? new Bundle() : args; start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        String preview = arguments.getString("state", "authorizing");
        try {
            String device = arguments.getString("device", "");
            check("emulator-5554".equals(device) || "emulator-5556".equals(device), "explicit-approved-emulator-required");
            check(isEmulator(), "emulator-required");
            check("idle".equals(preview) || "privacy".equals(preview) || "authorizing".equals(preview) || "exchanging".equals(preview)
                    || "profile".equals(preview) || "password".equals(preview) || "register".equals(preview) || "restoring".equals(preview), "preview-state-valid");
            long hold = Long.parseLong(arguments.getString("holdMs", "10000"));
            check(hold >= 0 && hold <= 10000, "hold-bounded");
            checkNoSavedIdentity();
            stage = "launch-target";
            Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(TARGET, TARGET + ".MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(launch); check(activity != null, "activity-present");
            waitForAccountReady();
            ui(() -> {
                panel = get(activity, "account"); repository = get(panel, "repository"); controller = get(panel, "qqLogin");
                check(invoke(repository, "cachedSession") == null, "memory-session-absent");
                check(!((Boolean) invoke(repository, "isLogoutPending")), "logout-not-pending");
                check(!((Boolean) get(panel, "working")), "account-idle");
                check(!((Boolean) invoke(controller, "isInProgress")), "authorization-idle");
                check(get(controller, "tencent") == null, "sdk-not-initialized-before-consent");
                captureUi(); set(activity, "settingsTab", true); set(activity, "albumTab", false);
                invoke(panel, "open"); invoke(activity, "render");
            });
            settleQqButton();
            ui(this::inspectLocalFormTransitions);
            ui(this::inspectBusyNavigation);
            ui(() -> { set(panel, "restoring", true); invoke(panel, "updateWorkingState"); });
            settleQqButton();
            ui(() -> inspectLoading("restoring", bounds((View) get(panel, "qqButton")), result));
            ui(() -> { set(panel, "restoring", false); invoke(panel, "updateWorkingState"); inspectIdle(); });
            Rect[] idle = new Rect[1];
            ui(() -> { inspectIdle(); idle[0] = bounds((View) get(panel, "qqButton")); });

            ui(() -> {
                Button qq = (Button) get(panel, "qqButton"); check(qq.performClick(), "real-qq-button-clicked");
                inspectPrivacy();
                AlertDialog dialog = (AlertDialog) get(panel, "qqPrivacyDialog");
                check(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(), "privacy-decline-clicked");
            });
            waitForIdleSync();
            ui(() -> {
                check(get(panel, "qqPrivacyDialog") == null, "privacy-decline-dismissed");
                inspectIdle(); inspectUninitialized();
            });

            setSyntheticLoading(true); settleQqButton();
            ui(() -> inspectLoading("authorizing", idle[0], result));
            ui(() -> {
                invoke(panel, "finishQqAuthorization", new Class<?>[]{String.class, boolean.class}, "已取消 QQ 登录。", false);
                inspectIdle(); check(((String) get(panel, "pageMessage")).contains("已取消"), "cancel-feedback-present"); inspectUninitialized();
            });
            setSyntheticLoading(false); settleQqButton();
            ui(() -> inspectLoading("exchanging", idle[0], result));
            ui(() -> { resetSyntheticLoading(); inspectIdle(); inspectUninitialized(); });

            if ("authorizing".equals(preview)) setSyntheticLoading(true);
            else if ("exchanging".equals(preview)) setSyntheticLoading(false);
            else if ("restoring".equals(preview)) ui(() -> { set(panel, "restoring", true); invoke(panel, "updateWorkingState"); });
            else if ("password".equals(preview)) ui(() -> click((Button) get(panel, "loginMethod"), "preview-password-method"));
            else if ("register".equals(preview)) ui(() -> click((Button) get(panel, "changeMode"), "preview-register-method"));
            else if ("privacy".equals(preview)) ui(() -> { ((Button) get(panel, "qqButton")).performClick(); inspectPrivacy(); });
            else if ("profile".equals(preview)) ui(() -> {
                Object synthetic = syntheticSession(); syntheticProfile = true;
                set(repository, "session", synthetic); set(panel, "session", synthetic);
                Object screen = get(panel, "screen"); set(panel, "screen", enumValue(screen.getClass(), "PROFILE"));
                invoke(panel, "discardView"); invoke(activity, "render");
            });
            if ("profile".equals(preview)) {
                settleButton("logoutButton"); ui(this::inspectProfile);
            } else if ("password".equals(preview) || "register".equals(preview)) settleButton("submit");
            else settleQqButton();
            checkNoSavedIdentity();
            Bundle ready = new Bundle(); ready.putString("phase", "READY"); ready.putString("state", preview);
            ready.putInt("checks", checks); ready.putString("stream", "QQ UI probe READY; synthetic=" + !"privacy".equals(preview) + "; state=" + preview + "; holdMs=" + hold + "\n");
            sendStatus(1, ready);
            if (hold > 0) Thread.sleep(hold);
            result.putString("status", "PASS"); result.putInt("checks", checks);
            result.putString("stream", "QQ UI probe PASS; checks=" + checks + "; state=" + preview + "; no SDK authorization or session exchange was invoked\n");
        } catch (Throwable failure) {
            result.putString("status", "FAIL"); result.putInt("checks", checks); result.putString("stage", stage);
            result.putString("stream", "QQ UI probe FAIL; stage=" + stage + "; type=" + failure.getClass().getSimpleName() + "; checks=" + checks + "\n");
        } finally {
            try { restoreUi(); checkNoSavedIdentity(); }
            catch (Throwable failure) { result.putString("status", "FAIL"); result.putString("stage", "restore-ui"); result.putString("stream", "QQ UI probe FAIL; stage=restore-ui\n"); }
        }
        finish("PASS".equals(result.getString("status")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void captureUi() throws Exception {
        originalSettings = (Boolean) get(activity, "settingsTab"); originalAlbum = (Boolean) get(activity, "albumTab");
        originalScreen = get(panel, "screen"); originalSession = get(panel, "session");
        originalWorking = (Boolean) get(panel, "working"); originalRestoring = (Boolean) get(panel, "restoring");
        originalAuthorizing = (Boolean) get(panel, "qqAuthorizing"); originalExchanging = (Boolean) get(panel, "qqExchanging");
        originalRefresh = (Boolean) get(panel, "refreshAfterRestore"); originalMessageError = (Boolean) get(panel, "messageError");
        originalPasswordLogin = (Boolean) get(panel, "passwordLogin");
        originalMessage = (String) get(panel, "pageMessage"); originalUsername = (String) get(panel, "usernameDraft");
        originalEpoch = (Long) get(panel, "epoch"); originalQqEpoch = (Long) get(panel, "qqRequestEpoch"); captured = true;
    }

    private void inspectIdle() throws Exception {
        Button qq = (Button) get(panel, "qqButton"), submit = (Button) get(panel, "submit");
        check("LOGIN".equals(get(panel, "screen").toString()) && !((Boolean) get(panel, "passwordLogin")), "welcome-login-mode");
        check(qq != null && qq.isEnabled() && submit == null, "welcome-qq-only-primary-action");
        check(get(panel, "username") == null && get(panel, "password") == null && get(panel, "confirmation") == null, "welcome-form-absent");
        inspectFlatButton(qq, "qq");
        check(((Button) get(panel, "loginMethod")).isEnabled() && ((Button) get(panel, "changeMode")).isEnabled(), "welcome-secondary-actions-enabled");
        check("使用账号密码登录".contentEquals(((Button) get(panel, "loginMethod")).getText()), "welcome-password-method-label");
        check("注册账号".contentEquals(((Button) get(panel, "changeMode")).getText()), "welcome-registration-label");
        inspectFlatButton(findButton((View) get(panel, "root"), "QQ 登录与隐私说明"), "account-privacy");
        inspectFlatButton(findButton((View) get(panel, "root"), "登录帮助"), "login-help");
        check("QQ 快捷登录".contentEquals(qq.getText()), "qq-button-label");
        float density = activity.getResources().getDisplayMetrics().density;
        check(qq.getWidth() + 1 >= 48 * density && qq.getHeight() + 1 >= 48 * density, "qq-target-at-least-48dp");
        check(((View) get(panel, "qqLoadingContent")).getVisibility() == View.GONE, "idle-qq-loader-hidden");
        check(get(panel, "loadingContent") == null, "welcome-password-loader-absent");
        check(!((Boolean) get(panel, "qqAuthorizing")) && !((Boolean) get(panel, "qqExchanging")) && !((Boolean) get(panel, "working")), "idle-flags-reset");
        ensureEmptyPasswords();
    }

    /** Exercise only local view transitions. Neither submit nor a positive QQ-consent action is clicked. */
    private void inspectLocalFormTransitions() throws Exception {
        click((Button) get(panel, "loginMethod"), "password-method-clicked");
        inspectPasswordMode(false);
        EditText name = (EditText) get(panel, "username"), secret = (EditText) get(panel, "password");
        name.setText("qa_local_only"); secret.setText("local-ui-fixture-only");
        ImageButton eye = (ImageButton) get(panel, "showPassword");
        check(eye.performClick(), "password-eye-clicked");
        check(secret.getTransformationMethod() == null && "隐藏密码".contentEquals(eye.getContentDescription()), "password-revealed-with-action-label");
        check(eye.performClick(), "password-eye-hide-clicked");
        check(secret.getTransformationMethod() instanceof PasswordTransformationMethod
                && "显示密码".contentEquals(eye.getContentDescription()), "password-masked-with-action-label");
        click((Button) get(panel, "loginMethod"), "qq-method-clicked");
        check(secret.length() == 0, "method-switch-clears-detached-password");
        check(get(panel, "password") == null && get(panel, "submit") == null && get(panel, "qqButton") != null, "qq-method-removes-password-form");
        click((Button) get(panel, "loginMethod"), "password-method-reopened");
        inspectPasswordMode(false);
        check("qa_local_only".contentEquals(((EditText) get(panel, "username")).getText()), "method-switch-keeps-username");
        secret = (EditText) get(panel, "password"); secret.setText("local-ui-fixture-only");
        invoke(activity, "goBack");
        check((Boolean) invoke(panel, "isOpen") && !((Boolean) get(panel, "passwordLogin")), "system-back-collapses-password-form");
        check(secret.length() == 0 && get(panel, "password") == null, "system-back-clears-detached-password");
        click((Button) get(panel, "loginMethod"), "password-method-after-back");
        check("qa_local_only".contentEquals(((EditText) get(panel, "username")).getText()), "system-back-keeps-username");
        ensureEmptyPasswords();

        // Registration entered from password login must return to that mode, with both secrets cleared.
        click((Button) get(panel, "changeMode"), "password-registration-clicked");
        inspectPasswordMode(true);
        EditText registrationPassword = (EditText) get(panel, "password"), confirmation = (EditText) get(panel, "confirmation");
        registrationPassword.setText("registration-ui-fixture"); confirmation.setText("registration-ui-fixture");
        check(((ImageButton) get(panel, "showConfirmation")).performClick(), "confirmation-eye-clicked");
        check(registrationPassword.getTransformationMethod() == null && confirmation.getTransformationMethod() == null, "registration-eyes-reveal-both-fields");
        click((Button) get(panel, "changeMode"), "registration-return-login-clicked");
        inspectPasswordMode(false);
        check(registrationPassword.length() == 0 && confirmation.length() == 0, "registration-return-clears-both-detached-secrets");
        check(get(panel, "confirmation") == null && get(panel, "showConfirmation") == null, "login-has-no-confirmation-field");
        ensureEmptyPasswords();

        // The same registration screen entered from the QQ welcome returns to the welcome on Back.
        click((Button) get(panel, "loginMethod"), "welcome-before-registration");
        click((Button) get(panel, "changeMode"), "welcome-registration-clicked");
        inspectPasswordMode(true);
        registrationPassword = (EditText) get(panel, "password"); confirmation = (EditText) get(panel, "confirmation");
        registrationPassword.setText("registration-ui-fixture"); confirmation.setText("registration-ui-fixture");
        invoke(activity, "goBack");
        check((Boolean) invoke(panel, "isOpen") && "LOGIN".equals(get(panel, "screen").toString())
                && !((Boolean) get(panel, "passwordLogin")), "registration-system-back-restores-welcome");
        check(registrationPassword.length() == 0 && confirmation.length() == 0, "registration-system-back-clears-secrets");
        check(get(panel, "username") == null && get(panel, "password") == null && get(panel, "confirmation") == null, "welcome-after-registration-has-no-form");
        inspectUninitialized();
    }

    private void inspectPasswordMode(boolean registering) throws Exception {
        check((registering ? "REGISTER" : "LOGIN").equals(get(panel, "screen").toString()), "password-form-screen-matches");
        check(get(panel, "qqButton") == null && get(panel, "qqLoadingContent") == null, "password-form-has-no-qq-primary");
        EditText name = (EditText) get(panel, "username"), secret = (EditText) get(panel, "password");
        check(name != null && secret != null && name.isEnabled() && secret.isEnabled(), "password-inputs-present-and-enabled");
        check(!name.isSaveEnabled() && !secret.isSaveEnabled(), "credential-inputs-disable-saved-state");
        check(secret.getTransformationMethod() instanceof PasswordTransformationMethod && !((Boolean) get(panel, "passwordVisible")), "new-password-form-masked");
        ImageButton eye = (ImageButton) get(panel, "showPassword");
        check(eye != null && eye.isEnabled() && "显示密码".contentEquals(eye.getContentDescription()), "inline-password-eye-accessible");
        Button submit = (Button) get(panel, "submit"); inspectFlatButton(submit, "password-submit");
        check(submit.isEnabled() && (registering ? "注册并登录" : "登录").contentEquals(submit.getText()), "password-submit-mode-label");
        check(((View) get(panel, "loadingContent")).getVisibility() == View.GONE, "idle-password-loader-hidden");
        check((get(panel, "confirmation") != null) == registering && (get(panel, "showConfirmation") != null) == registering, "confirmation-only-for-registration");
        if (registering) {
            EditText confirmation = (EditText) get(panel, "confirmation");
            check(!confirmation.isSaveEnabled() && confirmation.getTransformationMethod() instanceof PasswordTransformationMethod, "confirmation-transient-and-masked");
        }
    }

    private void inspectBusyNavigation() throws Exception {
        click((Button) get(panel, "loginMethod"), "busy-password-method-opened");
        EditText secret = (EditText) get(panel, "password"); secret.setText("busy-ui-fixture-only");
        set(panel, "working", true); invoke(panel, "updateWorkingState");
        Button method = (Button) get(panel, "loginMethod"), registration = (Button) get(panel, "changeMode");
        check(!method.isEnabled() && !registration.isEnabled() && !((Button) get(panel, "submit")).isEnabled(), "busy-password-actions-disabled");
        check(!((EditText) get(panel, "username")).isEnabled() && !secret.isEnabled()
                && !((ImageButton) get(panel, "showPassword")).isEnabled(), "busy-password-input-and-eye-disabled");
        check(((View) get(panel, "loadingContent")).getVisibility() == View.VISIBLE
                && "正在登录…".contentEquals(((TextView) get(panel, "loadingLabel")).getText()), "busy-password-loading-state");
        // performClick also invokes disabled listeners programmatically; guards must still reject these transitions.
        method.performClick(); registration.performClick();
        check("LOGIN".equals(get(panel, "screen").toString()) && ((Boolean) get(panel, "passwordLogin"))
                && get(panel, "password") == secret, "busy-method-and-registration-guards-hold");
        invoke(activity, "goBack");
        check(!((Boolean) invoke(panel, "isOpen")) && secret.length() == 0, "busy-system-back-closes-and-clears-password");
        check((Boolean) get(panel, "working"), "back-does-not-pretend-to-cancel-network-work");
        reopenWelcome();

        click((Button) get(panel, "loginMethod"), "guest-password-method-opened");
        secret = (EditText) get(panel, "password"); secret.setText("guest-ui-fixture-only");
        set(panel, "working", true); invoke(panel, "updateWorkingState");
        Button guest = findButton(activity.getWindow().getDecorView(), "游客使用");
        click(guest, "busy-guest-header-clicked");
        check(!((Boolean) invoke(panel, "isOpen")) && secret.length() == 0, "busy-guest-closes-and-clears-password");
        check((Boolean) get(panel, "working"), "guest-does-not-pretend-to-cancel-network-work");
        reopenWelcome();

        click((Button) get(panel, "changeMode"), "busy-registration-opened");
        EditText registrationPassword = (EditText) get(panel, "password"), confirmation = (EditText) get(panel, "confirmation");
        registrationPassword.setText("busy-registration-fixture"); confirmation.setText("busy-registration-fixture");
        set(panel, "working", true); invoke(panel, "updateWorkingState");
        check(!registrationPassword.isEnabled() && !confirmation.isEnabled()
                && !((ImageButton) get(panel, "showConfirmation")).isEnabled(), "busy-registration-inputs-and-eye-disabled");
        check(!((Button) get(panel, "submit")).isEnabled() && !((Button) get(panel, "changeMode")).isEnabled(), "busy-registration-actions-disabled");
        ((Button) get(panel, "changeMode")).performClick();
        check("REGISTER".equals(get(panel, "screen").toString()), "busy-registration-return-guard-holds");
        invoke(activity, "goBack");
        check(!((Boolean) invoke(panel, "isOpen")) && registrationPassword.length() == 0 && confirmation.length() == 0, "busy-registration-back-closes-and-clears-both-secrets");
        check((Boolean) get(panel, "working"), "registration-back-does-not-pretend-to-cancel-network-work");
        reopenWelcome();

        // Synthetic authorizing state exercises the page cancellation path without initializing the SDK.
        set(panel, "qqRequestEpoch", get(panel, "epoch")); set(panel, "qqAuthorizing", true); set(panel, "working", true);
        invoke(panel, "updateWorkingState"); invoke(activity, "goBack");
        check(!((Boolean) invoke(panel, "isOpen")) && !((Boolean) get(panel, "qqAuthorizing"))
                && !((Boolean) get(panel, "working")), "qq-authorizing-back-cancels-local-state");
        reopenWelcome(); inspectUninitialized();
    }

    /** Clear only synthetic in-memory flags. open() cannot refresh because the saved and cached sessions are absent. */
    private void reopenWelcome() throws Exception {
        set(panel, "working", false); set(panel, "restoring", false); set(panel, "qqAuthorizing", false); set(panel, "qqExchanging", false);
        invoke(panel, "continueAsGuest"); set(panel, "usernameDraft", ""); invoke(panel, "open"); invoke(activity, "render");
        check(get(panel, "qqButton") != null && get(panel, "submit") == null, "welcome-reopened-without-form");
    }

    private void click(Button button, String label) {
        check(button != null && button.isEnabled(), label + "-available"); check(button.performClick(), label);
    }

    private void inspectPrivacy() throws Exception {
        AlertDialog dialog = (AlertDialog) get(panel, "qqPrivacyDialog"); check(dialog != null && dialog.isShowing(), "privacy-dialog-visible");
        String content = allText(dialog.getWindow().getDecorView());
        check(content.contains("QQ 登录与隐私说明") && content.contains("QQ 互联 SDK") && content.contains("本应用的登录会话"), "privacy-disclosure-present");
        check(content.contains("同意并继续") && content.contains("暂不使用"), "privacy-choice-present");
        inspectFlatButton(findButton(dialog.getWindow().getDecorView(), "查看腾讯 QQ 互联 SDK 隐私声明"), "official-privacy");
        inspectUninitialized();
    }

    private void inspectProfile() throws Exception {
        Object session = get(panel, "session");
        check(syntheticProfile && session != null && session == invoke(repository, "cachedSession"), "profile-uses-explicit-memory-fixture");
        check("qq".equals(get(get(session, "user"), "provider")), "profile-fixture-provider-qq");
        Button refresh = (Button) get(panel, "refreshButton"), logout = (Button) get(panel, "logoutButton");
        check(refresh != null && logout != null && refresh.isEnabled() && logout.isEnabled(), "profile-actions-enabled");
        inspectFlatButton(refresh, "profile-refresh"); inspectFlatButton(logout, "profile-logout");
        inspectFlatButton(findButton((View) get(panel, "root"), "QQ 登录与隐私说明"), "profile-privacy");
        check(get(controller, "tencent") == null && !((Boolean) invoke(controller, "isInProgress")), "profile-sdk-uninitialized");
    }

    private void inspectFlatButton(Button button, String name) {
        check(button != null, name + "-button-present");
        check(button.getElevation() == 0f, name + "-elevation-zero");
        check(button.getTranslationZ() == 0f, name + "-translation-z-zero");
        check(button.getStateListAnimator() == null, name + "-state-animator-absent");
        check(button.getBackground() instanceof android.graphics.drawable.RippleDrawable, name + "-ripple-preserved");
    }

    private Object syntheticSession() throws Exception {
        ClassLoader loader = getTargetContext().getClassLoader();
        Class<?> userClass = Class.forName(TARGET + ".account.AccountRepository$User", true, loader);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", "00000000-0000-4000-8000-000000000001"); values.put("username", "");
        values.put("nickname", "Huevia 预览用户"); values.put("avatarUrl", ""); values.put("provider", "qq"); values.put("status", "active");
        values.put("createdAt", "2026-10-18T00:00:00Z"); values.put("lastLoginAt", "2026-10-18T00:00:00Z");
        values.put("updatedAt", "2026-10-18T00:00:00Z"); values.put("loginCount", java.math.BigDecimal.ONE);
        Constructor<?> userConstructor = userClass.getDeclaredConstructor(Map.class); userConstructor.setAccessible(true);
        Object user = userConstructor.newInstance(values);
        Class<?> sessionClass = Class.forName(TARGET + ".account.AccountRepository$Session", true, loader);
        Constructor<?> sessionConstructor = sessionClass.getDeclaredConstructor(userClass, String.class, String.class, boolean.class);
        sessionConstructor.setAccessible(true);
        // Intentionally invalid as a real server bearer. Rendering only; never refresh, transport or storage.
        return sessionConstructor.newInstance(user, "synthetic-ui-only", "2099-01-01T00:00:00Z", false);
    }

    private void inspectUninitialized() throws Exception {
        check(get(controller, "tencent") == null && !((Boolean) invoke(controller, "isInProgress")), "sdk-uninitialized-and-no-attempt");
        check(invoke(repository, "cachedSession") == null && get(panel, "session") == null, "no-account-session-created");
    }

    private void setSyntheticLoading(boolean authorizing) throws Exception {
        ui(() -> {
            set(panel, "qqRequestEpoch", get(panel, "epoch")); set(panel, "qqAuthorizing", authorizing);
            set(panel, "qqExchanging", !authorizing); set(panel, "working", true);
            invoke(panel, "updateWorkingState");
        });
    }

    private void resetSyntheticLoading() throws Exception {
        set(panel, "qqAuthorizing", false); set(panel, "qqExchanging", false); set(panel, "working", false);
        invoke(panel, "setMessage", new Class<?>[]{String.class, boolean.class}, "", false); invoke(panel, "updateWorkingState");
    }

    private void inspectLoading(String state, Rect idle, Bundle result) throws Exception {
        Button qq = (Button) get(panel, "qqButton"), submit = (Button) get(panel, "submit");
        View overlay = (View) get(panel, "qqLoadingContent"), label = (View) get(panel, "qqLoadingLabel");
        check(overlay instanceof ViewGroup && ((ViewGroup) overlay).getChildCount() >= 2, "qq-loader-children-present");
        View spinner = ((ViewGroup) overlay).getChildAt(0);
        Rect buttonBounds = bounds(qq), overlayBounds = bounds(overlay), spinnerBounds = bounds(spinner), labelBounds = bounds(label);
        result.putString(state + "Bounds", "button=" + fixed(buttonBounds) + ";overlay=" + fixed(overlayBounds) + ";spinner=" + fixed(spinnerBounds) + ";label=" + fixed(labelBounds));
        check(overlay.getVisibility() == View.VISIBLE, "qq-loader-visible");
        check(!qq.isEnabled() && !((Button) get(panel, "loginMethod")).isEnabled()
                && !((Button) get(panel, "changeMode")).isEnabled(), "loading-authentication-and-mode-actions-disabled");
        check(submit == null && get(panel, "username") == null && get(panel, "password") == null, "loading-welcome-keeps-form-absent");
        check(get(panel, "loadingContent") == null, "welcome-has-no-false-password-loader");
        check(buttonBounds.left == idle.left && buttonBounds.right == idle.right, "qq-button-width-stable");
        check(buttonBounds.contains(overlayBounds) && buttonBounds.contains(spinnerBounds) && buttonBounds.contains(labelBounds), "qq-loader-wholly-inside-button");
        check(Math.abs(buttonBounds.centerX() - overlayBounds.centerX()) <= 1 && Math.abs(buttonBounds.centerY() - overlayBounds.centerY()) <= 1, "qq-loader-centered");
        check(overlay.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, "qq-loader-accessibility-hidden");
        check(!overlay.isClickable() && !overlay.isFocusable(), "qq-loader-not-interactive");
        String expected = "restoring".equals(state) ? "正在读取账号…" : "authorizing".equals(state) ? "等待 QQ 授权…" : "正在登录…";
        check(expected.contentEquals(qq.getContentDescription()) && expected.contentEquals(((TextView) label).getText()), "qq-loader-accessible-state-label");
        if ("restoring".equals(state)) check(!((Boolean) get(panel, "qqAuthorizing")) && !((Boolean) get(panel, "qqExchanging")), "restoring-does-not-claim-qq-authorization");
        ensureEmptyPasswords(); inspectUninitialized();
    }

    private void ensureEmptyPasswords() throws Exception {
        for (String name : new String[]{"password", "confirmation"}) {
            EditText input = (EditText) get(panel, name); if (input != null) check(input.length() == 0, "no-password-in-preview");
        }
    }

    private void checkNoSavedIdentity() throws Exception {
        check(TARGET.equals(getTargetContext().getPackageName()), "target-package-matches");
        File account = new File(getTargetContext().getNoBackupFilesDir(), "account");
        for (String file : new String[]{"session.enc", "session.enc.bak", "session.enc.new"}) check(!new File(account, file).exists(), "saved-session-absent");
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        check(!keys.containsAlias(TARGET + ".account.session.v1"), "saved-session-key-absent");
    }

    private void waitForAccountReady() throws Exception {
        long deadline = System.currentTimeMillis() + 10000; boolean[] ready = new boolean[1];
        while (System.currentTimeMillis() < deadline) {
            ui(() -> { Object account = get(activity, "account"); ready[0] = (Boolean) get(activity, "ready") && account != null && !((Boolean) get(account, "restoring")); });
            if (ready[0]) return; Thread.sleep(50);
        }
        check(false, "account-ready-timeout");
    }

    private void settleQqButton() throws Exception {
        settleButton("qqButton");
    }

    private void settleButton(String name) throws Exception {
        waitForIdleSync();
        ui(() -> { Button button = (Button) get(panel, name); if (button != null) button.requestRectangleOnScreen(new Rect(0, 0, button.getWidth(), button.getHeight()), true); });
        waitForIdleSync(); Thread.sleep(400); waitForIdleSync();
    }

    private void restoreUi() throws Exception {
        if (activity == null) return;
        ui(() -> {
            if (captured) {
                invoke(panel, "dismissDialog");
                if (syntheticProfile) { set(repository, "session", null); syntheticProfile = false; }
                set(panel, "working", originalWorking); set(panel, "restoring", originalRestoring);
                set(panel, "qqAuthorizing", originalAuthorizing); set(panel, "qqExchanging", originalExchanging);
                set(panel, "screen", originalScreen); set(panel, "session", originalSession); set(panel, "refreshAfterRestore", originalRefresh);
                set(panel, "passwordLogin", originalPasswordLogin);
                set(panel, "epoch", originalEpoch); set(panel, "qqRequestEpoch", originalQqEpoch);
                set(panel, "pageMessage", originalMessage); set(panel, "messageError", originalMessageError); set(panel, "usernameDraft", originalUsername);
                invoke(panel, "discardView"); set(activity, "settingsTab", originalSettings); set(activity, "albumTab", originalAlbum); invoke(activity, "render");
                check(invoke(repository, "cachedSession") == null && get(controller, "tencent") == null, "cleanup-keeps-account-and-sdk-empty");
            }
            activity.finish();
        });
        waitForIdleSync();
    }

    private static boolean isEmulator() { return "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE) || Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.startsWith("generic/"); }
    private static Rect bounds(View view) { int[] xy = new int[2]; view.getLocationOnScreen(xy); return new Rect(xy[0], xy[1], xy[0] + view.getWidth(), xy[1] + view.getHeight()); }
    private static String fixed(Rect value) { return "[" + value.left + "," + value.top + "," + value.right + "," + value.bottom + "]"; }
    private static String allText(View view) { StringBuilder result = new StringBuilder(); if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n'); if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup) view).getChildCount(); i++) result.append(allText(((ViewGroup) view).getChildAt(i))); return result.toString(); }
    private static Button findButton(View view, String text) { if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view; if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup) view).getChildCount(); i++) { Button found = findButton(((ViewGroup) view).getChildAt(i), text); if (found != null) return found; } return null; }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static Object enumValue(Class<?> type, String value) { return Enum.valueOf((Class) type, value); }
    private void check(boolean value, String label) { stage = label; if (!value) throw new IllegalStateException(); checks++; }
    private static Object get(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    private static Object invoke(Object object, String name) throws Exception { return invoke(object, name, new Class<?>[0]); }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... values) throws Exception { Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(object, values); }
    private interface UiTask { void run() throws Exception; }
    private void ui(UiTask task) throws Exception { Exception[] failure = new Exception[1]; runOnMainSync(() -> { try { task.run(); } catch (Exception error) { failure[0] = error; } }); if (failure[0] != null) throw failure[0]; }
}

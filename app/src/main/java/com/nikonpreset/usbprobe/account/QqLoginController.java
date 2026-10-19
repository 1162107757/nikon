package com.nikonpreset.usbprobe.account;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tencent.connect.common.Constants;
import com.tencent.connect.common.UIListenerManager;
import com.tencent.open.log.Tracer;
import com.tencent.tauth.IUiListener;
import com.tencent.tauth.Tencent;
import com.tencent.tauth.UiError;

import org.json.JSONObject;

/** Activity-scoped QQ authorization. Constructing this class does not initialize the SDK. */
public final class QqLoginController implements AutoCloseable {
    public static final String APP_ID = "1105984855";
    private static final String SCOPE = "get_simple_userinfo";
    private static final long AUTH_TIMEOUT_MILLIS = 5 * 60 * 1000L;
    private static final long RETURN_GRACE_MILLIS = 1000L;
    // QQ fixes the Activity request code. Keep a process-wide boundary until a cancelled provider
    // attempt returns, so its late result cannot be assigned to a newer attempt after recreation.
    private static final QqAuthorizationGate GATE = new QqAuthorizationGate();

    public interface Callback {
        /** Exchange immediately for the app's own session on a worker; never persist or log this token. */
        void onAuthorized(String accessToken);
        void onCancelled();
        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context application;
    private Activity activity;
    private Callback callback;
    private Tencent tencent;
    // The SDK keeps a weak reference. Keep this listener alive until the attempt ends.
    private IUiListener listener;
    private long generation;
    private boolean active, closed, hostResumed = true, leftHost, launched;
    private Runnable timeout, returned;
    private String pendingToken, pendingError;
    private boolean pendingCancelled;

    public QqLoginController(Activity activity, Callback callback) {
        if (activity == null || callback == null) throw new IllegalArgumentException("QQ login host is required");
        this.activity = activity;
        this.callback = callback;
        this.application = activity.getApplicationContext();
    }

    /** Main thread only. The caller must first obtain explicit consent to QQ login and its SDK disclosure. */
    public void start() {
        if (closed || active || hasPendingOutcome()) return;
        if (!hostResumed || activity.isFinishing() || activity.isDestroyed()) {
            callback.onError("请返回应用后重试 QQ 登录。");
            return;
        }
        final long attempt = GATE.begin();
        if (attempt == 0) {
            callback.onError("上次 QQ 授权尚未结束，请关闭 QQ 授权页后重试；若仍无法继续，请重新打开应用。");
            return;
        }
        generation = attempt;
        active = true;
        leftHost = false;
        launched = false;
        try {
            // In the pinned 3.5.19 binary setCustomLogger adds a sink instead of replacing the defaults.
            // These public SDK controls disable the default file level gate and logcat tracer before
            // creating Tencent or passing it any credentials. Recheck them before upgrading the SDK.
            com.tencent.open.log.c.a = 0;
            com.tencent.open.log.c.b = 0;
            com.tencent.open.log.e.a.a(false);
            Tencent.setCustomLogger(new Tracer() {
                @Override protected void doTrace(int level, Thread thread, long time, String tag,
                                                  String message, Throwable problem) { }
            });
            Tencent.setIsPermissionGranted(true);
            if (tencent == null) tencent = Tencent.createInstance(APP_ID, activity.getApplicationContext());
            if (tencent == null) {
                fail(attempt, "QQ 登录初始化失败，请稍后重试。");
                return;
            }
            clearSdkSession();
            Tencent.resetTargetAppInfoCache();
            final Tencent attemptSdk = tencent;
            listener = new IUiListener() {
                @Override public void onComplete(Object response) {
                    // Never retain, stringify, display or log the provider's raw response.
                    String token = null;
                    if (response instanceof JSONObject) {
                        JSONObject json = (JSONObject) response;
                        Object value = json.opt("access_token");
                        if (json.optInt("ret", -1) == 0 && value instanceof String) token = (String) value;
                    }
                    if (token == null || token.length() < 16 || token.length() > 2048
                            || !token.matches("[\\x21-\\x7e]+")) {
                        dispatch(() -> {
                            finishProvider(attempt, attemptSdk);
                            fail(attempt, "QQ 授权结果无效，请重新登录。");
                        });
                        return;
                    }
                    final String accessToken = token;
                    dispatch(() -> { finishProvider(attempt, attemptSdk); authorized(attempt, accessToken); });
                }
                @Override public void onError(UiError error) {
                    // Provider text/details may contain credentials. Only fixed, local messages leave this class.
                    final int code = error == null ? 0 : error.errorCode;
                    dispatch(() -> { finishProvider(attempt, attemptSdk); fail(attempt, errorMessage(code)); });
                }
                @Override public void onCancel() {
                    dispatch(() -> { finishProvider(attempt, attemptSdk); cancelled(attempt); });
                }
                @Override public void onWarning(int code) { /* Warnings are not authorization outcomes. */ }
            };
            timeout = () -> fail(attempt, "QQ 授权已超时，请重新登录。");
            main.postDelayed(timeout, AUTH_TIMEOUT_MILLIS);
            launched = true;
            int result = tencent.login(activity, SCOPE, listener);
            if (result < 0) {
                finishProvider(attempt, tencent);
                fail(attempt, "暂时无法打开 QQ 授权，请确认 QQ 可用后重试。");
            }
        } catch (RuntimeException | LinkageError problem) {
            // login() failed to launch; there is no valid pending result to wait for.
            finishProvider(attempt, tencent);
            fail(attempt, "暂时无法打开 QQ 授权，请稍后重试。");
        }
    }

    /** Forward before handling this Activity's other result codes. Late results are consumed and discarded. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != Constants.REQUEST_LOGIN && requestCode != Constants.REQUEST_API) return false;
        if (closed || !active || listener == null || !GATE.canAcceptResult(generation)) {
            finishProvider(GATE.discardableResult(generation), tencent);
            return true;
        }
        final long attempt = generation;
        try {
            if (!Tencent.onActivityResultData(requestCode, resultCode, data, listener)) cancelled(attempt);
        } catch (RuntimeException | LinkageError problem) {
            fail(attempt, "无法完成 QQ 授权，请重新登录。");
        }
        // Native result processing is synchronous. Drain even malformed/cancelled provider results.
        finishProvider(attempt, tencent);
        return true;
    }

    /** Leaving for QQ is expected, so pause must not cancel the authorization. */
    public void onHostPaused() {
        hostResumed = false;
        if (active) leftHost = true;
        if (returned != null) main.removeCallbacks(returned);
    }

    public void onHostResumed() {
        if (closed) return;
        hostResumed = true;
        deliverPendingOutcome();
        if (active && leftHost) {
            final long attempt = generation;
            if (returned != null) main.removeCallbacks(returned);
            // Activity results normally precede resume. Allow a short grace for vendor-specific ordering.
            returned = () -> cancelled(attempt);
            main.postDelayed(returned, RETURN_GRACE_MILLIS);
        }
    }

    /** Silently abandon the request when the account page closes or another account action takes over. */
    public void cancel() {
        GATE.abandon(generation);
        clearAttempt();
        pendingToken = pendingError = null;
        pendingCancelled = false;
    }

    public boolean isInProgress() { return active || hasPendingOutcome(); }

    private void authorized(long attempt, String token) {
        if (!owns(attempt)) return;
        clearAttempt();
        pendingToken = token;
        deliverPendingOutcome();
    }
    private void fail(long attempt, String message) {
        if (!owns(attempt)) return;
        if (!launched) finishProvider(attempt, tencent);
        else GATE.abandon(attempt);
        clearAttempt();
        pendingError = message;
        deliverPendingOutcome();
    }
    private void cancelled(long attempt) {
        if (!owns(attempt)) return;
        GATE.abandon(attempt);
        clearAttempt();
        pendingCancelled = true;
        deliverPendingOutcome();
    }
    private boolean owns(long attempt) { return !closed && active && attempt == generation; }
    private void finishProvider(long attempt, Tencent sdk) {
        if (attempt == 0) return;
        // The real SDK saves a temporary provider session before calling our listener, even if
        // this Activity has already closed. Remove that cache using only the application context.
        // A callback from an older completed attempt may not clear a newer attempt's listeners.
        try { if (sdk != null) sdk.getQQToken().removeSession(APP_ID); }
        catch (RuntimeException | LinkageError ignored) { /* Never include provider data in logs. */ }
        if (!GATE.isCurrent(attempt)) return;
        clearSdkSession(sdk);
        GATE.complete(attempt);
    }
    private boolean hasPendingOutcome() { return pendingToken != null || pendingError != null || pendingCancelled; }
    private void deliverPendingOutcome() {
        if (closed || !hostResumed || activity.isFinishing() || activity.isDestroyed() || !hasPendingOutcome()) return;
        String token = pendingToken;
        String error = pendingError;
        boolean cancelled = pendingCancelled;
        pendingToken = pendingError = null;
        pendingCancelled = false;
        if (token != null) callback.onAuthorized(token);
        else if (error != null) callback.onError(error);
        else if (cancelled) callback.onCancelled();
    }
    private void clearAttempt() {
        active = false;
        leftHost = false;
        if (timeout != null) main.removeCallbacks(timeout);
        if (returned != null) main.removeCallbacks(returned);
        timeout = returned = null;
        listener = null;
        if (GATE.isCurrent(generation)) clearSdkSession();
    }
    private void clearSdkSession() {
        clearSdkSession(tencent);
    }
    private void clearSdkSession(Tencent sdk) {
        if (sdk == null) return;
        try {
            UIListenerManager manager = UIListenerManager.getInstance();
            manager.getListnerWithRequestCode(Constants.REQUEST_LOGIN);
            manager.getListnerWithRequestCode(Constants.REQUEST_API);
            sdk.logout(application);
        } catch (RuntimeException | LinkageError ignored) { /* Do not log SDK errors or session contents. */ }
    }
    private void dispatch(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) task.run();
        else main.post(task); // Stale callbacks still release their provider boundary; owns() blocks delivery.
    }
    private static String errorMessage(int code) {
        if (code == Constants.ERROR_QQ_NOT_INSTALL) return "请先安装 QQ，再重试 QQ 登录。";
        if (code == Constants.ERROR_QQVERSION_LOW) return "请更新 QQ 后重试登录。";
        if (code == Constants.ERROR_NETWORK_UNAVAILABLE || code == Constants.ERROR_CONNECTTIMEOUT
                || code == Constants.ERROR_SOCKETTIMEOUT || code == Constants.ERROR_IO)
            return "QQ 授权暂时无法连接网络，请稍后重试。";
        if (code == Constants.ERROR_NO_AUTHORITY) return "QQ 暂未允许此次授权，请重试或使用账号密码登录。";
        return "QQ 授权未完成，请重试或使用账号密码登录。";
    }

    @Override public void close() {
        if (closed) return;
        cancel();
        closed = true;
        // cancel() removes our timers. Keep already queued provider outcomes so they can release
        // the process-wide boundary; owns() rejects delivery and the host references are cleared.
        callback = null;
        activity = null;
        tencent = null;
    }
}
